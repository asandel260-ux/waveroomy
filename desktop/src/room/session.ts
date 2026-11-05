import { ChessGame } from "../chess/game";
import { BoardObj, Participant, RoomState, WatchVideo, avatarFrom, avatarJson, canSpeak, chatFrom, metaFrom, newMeta, participantFrom, videoJson, Report } from "../core/models";
import { Debounced } from "../core/store";
import type { ModStore, ProfileStore, RoomsStore, SocialStore } from "../core/store";
import { Emitter, Flow, Ids, J, Timer, arr, bool, cancelLater, dbl, later, nowMs, obj, objects, str } from "../core/util";
import type { Mesh } from "../net/mesh";
import type { PeerLink } from "../net/peerlink";
import { EngineIO, RoomEngine } from "./engine";

export interface ReactionFx { sid: string; emoji: string; name: string; id: number }
export interface Cursor { x: number; y: number; laser: boolean; name: string; hue: number; at: number }

/** What the session needs from the rest of the app (AppCore implements it). */
export interface SessionCore {
  mesh: Mesh;
  profile: ProfileStore;
  social: SocialStore;
  rooms: RoomsStore;
  mod: ModStore;
  dmHub: { onRoomClosed(roomId: string | undefined): void };
  audio: { start(): void; stop(): void; refresh(): void; attach(link: PeerLink): void };
  screen: { stop(announce: boolean): void; refresh(): void };
  lan: { startHosting(h: LanHost): void; stopHosting(): void };
  sfx: { play(name: string): void };
  startService(): void;
  stopService(): void;
}

/** Hooks the LAN server uses to ask the hosting room for things. */
export interface LanHost {
  beacon(): J | null;
  admit(hello: J): string | null;
  issueInvite(roomId: string): Promise<{ id: string; code: string | null; why?: string }>;
  acceptAnswer(answer: J): Promise<boolean>;
}

/**
 * Client-side orchestration of one room. Every member runs this. Exactly one member
 * (the "engine", the earliest host) additionally runs RoomEngine and is the authority.
 * Port of room/RoomSession.kt.
 */
export class RoomSession implements EngineIO, LanHost {
  private st: RoomState | null = null;
  private engine: RoomEngine | null = null;
  get state(): RoomState { if (!this.st) throw new Error("no room"); return this.st; }
  get room(): RoomState | null { return this.st; }

  readonly tick = new Flow(0);
  readonly phase = new Flow<"idle" | "joining" | "live">("idle");
  readonly endedReason = new Flow<string | null>(null);
  readonly joinError = new Flow<string | null>(null);
  readonly toasts = new Emitter<string>();
  readonly reactions = new Emitter<ReactionFx>();
  readonly speaking = new Flow<Set<string>>(new Set());
  readonly cursors = new Flow<Map<string, Cursor>>(new Map());
  readonly unreadChat = new Flow(0);
  readonly deafened = new Flow(false);

  get mySid() { return this.core.mesh.mySid; }
  get myUid() { return this.core.profile.uid; }
  get me(): Participant | undefined { return this.st?.participants.get(this.mySid); }
  get isEngine(): boolean { return this.st?.engineSid() === this.mySid && this.engine != null; }
  get isHost(): boolean { return this.me?.role === "host"; }
  get live(): boolean { return this.phase.value === "live"; }

  /** Link we expect to receive a join answer through (set when we accept an invite). */
  expectJoin = "";

  private joinTarget = "";
  private joinOwnsLink = true;
  private joinTimeout: Timer = null;
  private waiting = new Map<string, (r: J) => void>();
  private timeouts = new Map<string, Timer>();
  private lastCursor = 0;
  private reconnect = new Map<string, number>();
  private chessLoop: Timer = null;
  private chessSeen = new Set<string>();
  private saver = new Debounced(2500, () => { const s = this.st; if (s && s.meta.ownerUid === this.myUid) this.core.rooms.saveData(s); });

  constructor(private core: SessionCore) {}

  bumpTick() {
    this.tick.value = this.tick.value + 1;
    this.saver.poke();
  }
  private toast(t: string) { this.toasts.emit(t); }

  /* ---------------- creating / joining / leaving ---------------- */

  createRoom(title: string, topic: string, tags: string[], unlisted: boolean, maxSpeakers: number, restore: J | null = null, id: string | null = null): boolean {
    if (this.st) return false;
    const p = this.core.profile.profile.value;
    if (!p) return false;
    const ms = Math.min(24, Math.max(2, maxSpeakers));
    const meta = newMeta({
      id: id ?? Ids.id("r_"), title: title.trim().slice(0, 60), topic: topic.trim().slice(0, 140), tags,
      ownerUid: this.myUid, ownerName: p.displayName, unlisted, maxSpeakers: ms
    });
    const s = new RoomState(meta);
    if (restore) {
      const keep = obj(restore, "meta");
      if (keep) s.meta = { ...metaFrom(keep), title: meta.title, topic: meta.topic, tags: meta.tags, unlisted, maxSpeakers: ms, locked: false };
      for (const c of objects(arr(restore, "chat"))) s.chat.push(chatFrom(c));
      s.board.load(arr(restore, "board"));
    }
    this.st = s;
    this.engine = new RoomEngine(this);
    const r = this.engine.handleJoin(this.mySid, this.myUid, p.username, p.displayName, p.avatar, "");
    if (!bool(r, "ok")) { this.st = null; this.engine = null; return false; }
    this.becameLive();
    return true;
  }

  /**
   * Called when an invite we accepted finished its handshake, or (with [roomId]) to join a room over a
   * connection we already have to its host, for example as friends.
   */
  beginJoin(link: PeerLink, roomId = link.joinRoomId) {
    if (this.st) return;
    const rid = roomId;
    if (!rid) return;
    this.joinOwnsLink = link.purpose === "room";
    this.endedReason.value = null;
    this.joinError.value = null;
    this.st = new RoomState(newMeta({ id: rid, title: "Joining…", ownerUid: "" }));
    this.phase.value = "joining";
    this.joinTarget = link.remoteSid;
    this.expectJoin = "";
    this.core.mesh.sendTo(link.remoteSid, { t: "rjoin", room: rid });
    cancelLater(this.joinTimeout);
    this.joinTimeout = later(20_000, () => this.failJoin("The room did not answer. Check the code and try again."));
    this.bumpTick();
  }

  private failJoin(msg: string) {
    cancelLater(this.joinTimeout);
    if (this.phase.value !== "joining") return;
    this.joinError.value = msg;
    const t = this.joinTarget;
    const own = this.joinOwnsLink;
    this.resetLocal();
    // a connection that only exists for this join goes away with it; a friend's connection stays
    if (t && own) this.core.mesh.closeLink(t, "join failed");
  }

  private becameLive() {
    cancelLater(this.joinTimeout);
    this.phase.value = "live";
    const s = this.st;
    if (!s) return;
    this.startChessLoop();
    this.core.mesh.hintProvider = (sid) => { const v = s.participants.get(sid)?.via; return v ? v : null; };
    this.core.startService();
    this.core.audio.start();
    this.deafened.value = false;
    if (s.meta.ownerUid === this.myUid) {
      const old = this.core.rooms.get(s.meta.id);
      this.core.rooms.upsert({ meta: s.meta, lastActiveAt: nowMs(), totalJoins: old?.totalJoins ?? 0, peak: old?.peak ?? 0, isOwner: true });
    }
    if (this.isEngine) this.startLan();
    this.ensureLinks();
    this.core.audio.refresh();
    this.core.sfx.play("join");
    this.bumpTick();
  }

  private startLan() {
    const s = this.st;
    if (s && !s.meta.unlisted) this.core.lan.startHosting(this);
  }

  /** Leave politely, telling everybody. */
  leave() {
    const s = this.st;
    if (s && this.phase.value === "live") {
      this.core.mesh.broadcast({ t: "bye" });
      if (s.meta.ownerUid === this.myUid) this.core.rooms.saveData(s);
    }
    this.resetLocal();
  }

  /** Host ends the room for everyone, then leaves. */
  endRoom() { this.request("room:end", {}, () => this.leave()); }

  private resetLocal(reason: string | null = null) {
    cancelLater(this.joinTimeout);
    cancelLater(this.chessLoop);
    const s = this.st;
    this.core.dmHub.onRoomClosed(s?.meta.id);
    if (s && s.meta.ownerUid === this.myUid && this.phase.value === "live") this.core.rooms.saveData(s);
    this.core.screen.stop(false);
    this.core.audio.stop();
    this.core.lan.stopHosting();
    for (const r of this.timeouts.values()) cancelLater(r);
    this.timeouts.clear(); this.waiting.clear();
    const toClose = this.core.mesh.authedLinks().filter((l) => l.purpose !== "dm" && l.purpose !== "sync").map((l) => l.remoteSid);
    this.st = null; this.engine = null;
    this.phase.value = "idle";
    this.speaking.value = new Set();
    this.cursors.value = new Map();
    this.unreadChat.value = 0;
    this.joinTarget = ""; this.expectJoin = "";
    // give the last "ended / kicked" packets a moment to leave before the links close
    later(350, () => { for (const sid of toClose) this.core.mesh.closeLink(sid, "left room"); });
    this.reconnect.clear();
    this.core.mesh.hintProvider = null;
    this.core.stopService();
    if (reason != null) this.endedReason.value = reason;
    this.bumpTick();
  }

  /* ---------------- mesh events ---------------- */

  onLinkAuthed(link: PeerLink) {
    this.reconnect.delete(link.remoteSid);
    if (link.remoteSid === this.expectJoin && link.purpose === "room") { this.beginJoin(link); return; }
    const s = this.st;
    if (!s) return;
    if (this.phase.value === "live" && s.participants.get(link.remoteSid)) this.core.audio.attach(link);
  }

  onLinkGone(link: PeerLink, reason: string) {
    const s = this.st;
    if (!s) return;
    const sid = link.remoteSid;
    if (!link.authed && !(this.phase.value === "joining" && sid === this.joinTarget)) return;
    if (this.phase.value === "joining" && sid === this.joinTarget) { this.failJoin("The connection to the room was lost."); return; }
    if (this.phase.value !== "live") return;
    if (!s.participants.get(sid)) return;
    this.participantGone(sid, reason);
  }

  private participantGone(sid: string, reason: string) {
    const s = this.st;
    if (!s) return;
    const gone = s.participants.get(sid);
    if (!gone) return;
    if (this.isEngine) {
      if (reason === "left") this.engine?.handleLeave(sid);
      else {
        // a dropped link is often a blip; give the peer a few seconds to come back before removing them
        this.core.mesh.connectTo(sid);
        later(6000, () => {
          const cur = this.st;
          if (cur && this.phase.value === "live" && this.isEngine && cur.participants.has(sid) && !this.core.mesh.link(sid)?.authed) this.engine?.handleLeave(sid);
        });
      }
      return;
    }
    if (reason === "left") {
      if (sid === s.engineSid()) this.takeover(sid);
      return;
    }
    if (sid === s.engineSid()) {
      // one dropped link is not proof the host is gone: try to reach them again before taking over
      this.core.mesh.connectTo(sid);
      later(7000, () => {
        const cur = this.st;
        if (cur && this.phase.value === "live" && cur.engineSid() === sid && cur.participants.has(sid) && !this.core.mesh.link(sid)?.authed) this.takeover(sid);
      });
      return;
    }
    // someone else's link dropped; the host will announce it if they really left.
    const n = this.reconnect.get(sid) ?? 0;
    if (n < 3) {
      this.reconnect.set(sid, n + 1);
      later(3000 * (n + 1), () => {
        const cur = this.st;
        if (cur && cur.participants.has(sid) && !this.core.mesh.link(sid)?.authed) this.core.mesh.connectTo(sid);
      });
    }
    if (gone.sharing) this.bumpTick();
  }

  /** The acting host disappeared. Everyone computes the same successor, no messages needed. */
  private takeover(goneSid: string) {
    const s = this.st;
    if (!s) return;
    const m = new Map(s.participants);
    const gone = m.get(goneSid);
    if (!gone) return;
    m.delete(goneSid);
    if (m.size === 0) { s.participants = m; this.leave(); return; }
    if (![...m.values()].some((p) => p.role === "host")) {
      const next = [...m.values()].sort((a, b) => a.joinedAt - b.joinedAt || (a.sid < b.sid ? -1 : a.sid > b.sid ? 1 : 0))[0];
      m.set(next.sid, { ...next, role: "host" });
    }
    s.participants = m;
    const sp = new Set(this.speaking.value); sp.delete(goneSid); this.speaking.value = sp;
    this.core.audio.refresh();
    if (gone.sharing) this.toast(`${gone.displayName} stopped sharing`);
    if (s.engineSid() === this.mySid) {
      this.engine = new RoomEngine(this);
      this.toast("The host left. You are hosting now");
      this.core.mesh.hintProvider = (sid) => { const v = s.participants.get(sid)?.via; return v ? v : null; };
      this.startLan();
      this.engine.reconcile();
    } else this.toast(`${gone.displayName} (host) left`);
    this.bumpTick();
  }

  onMessage(from: string, m: J, link: PeerLink | null) {
    switch (str(m, "t")) {
      case "rjoin": if (link) this.onRJoin(link, m); break;
      case "rfwd": if (link) this.onRFwd(from, m); break;
      case "rjoined": this.onRJoined(from, m); break;
      case "req": if (link) this.onReq(from, link, m); break;
      case "res": {
        const id = str(m, "id");
        const cb = this.waiting.get(id);
        if (cb) {
          this.waiting.delete(id);
          cancelLater(this.timeouts.get(id)); this.timeouts.delete(id);
          cb(obj(m, "r") ?? { ok: false, error: "No answer." });
        }
        break;
      }
      case "ev": this.onEv(from, m); break;
      case "bye": if (link && this.phase.value === "live" && this.st?.participants.has(from)) this.participantGone(from, "left"); break;
      case "spk":
        if (link && this.st?.participants.has(from)) {
          const sp = new Set(this.speaking.value);
          if (bool(m, "v")) sp.add(from); else sp.delete(from);
          this.speaking.value = sp;
        }
        break;
      case "cur": if (link) this.onCursor(from, m); break;
    }
  }

  /* ---- joining on the receiving side ---- */

  private whoOf(link: PeerLink): J | null {
    const c = this.core.social.contact(link.uid);
    if (!c) return null;
    return { sid: link.remoteSid, uid: c.uid, user: c.username, name: c.displayName, av: avatarJson(c.avatar) };
  }

  private onRJoin(link: PeerLink, m: J) {
    const s = this.st;
    const deny = (error: string) => this.core.mesh.sendTo(link.remoteSid, { t: "rjoined", r: { ok: false, error } });
    if (!s || this.phase.value !== "live" || s.meta.id !== str(m, "room")) { deny("That room is not open on this device any more."); return; }
    const who = this.whoOf(link);
    if (!who) { deny("Could not identify you. Try again."); return; }
    if (this.isEngine) this.doJoin(who, this.mySid);
    else {
      const eng = s.engineSid();
      if (!eng || !this.core.mesh.sendTo(eng, { t: "rfwd", who, intro: this.mySid })) deny("The host cannot be reached right now.");
    }
  }

  private onRFwd(from: string, m: J) {
    const s = this.st;
    if (!s || !this.isEngine || !s.participants.has(from)) return;
    const who = obj(m, "who");
    if (!who) return;
    const wsid = str(who, "sid");
    if (!wsid || wsid === this.mySid || wsid.length > 64 || str(who, "uid").length > 64) return;
    const ex = s.participants.get(wsid);
    if (ex && ex.uid !== str(who, "uid")) return;
    this.core.mesh.noteRoute(wsid, from);
    this.doJoin(who, from);
  }

  private doJoin(who: J, via: string) {
    const eng = this.engine;
    if (!eng) return;
    const sid = str(who, "sid");
    let r: J;
    try {
      r = eng.handleJoin(sid, str(who, "uid").slice(0, 64), str(who, "user").slice(0, 40), (str(who, "name") || str(who, "user")).slice(0, 60), avatarFrom(obj(who, "av")), via);
    } catch { r = { ok: false, error: "Could not join." }; }
    this.core.mesh.sendTo(sid, { t: "rjoined", r, engine: this.mySid });
    if (bool(r, "ok")) {
      this.core.sfx.play("join");
      this.toast(`${str(who, "name")} joined`);
      this.core.audio.refresh();
    }
    this.bumpTick();
  }

  private onRJoined(from: string, m: J) {
    if (this.phase.value !== "joining") return;
    if (from !== this.joinTarget && from !== str(m, "engine")) return;
    const r = obj(m, "r");
    if (!r) return;
    if (!bool(r, "ok")) { this.failJoin(str(r, "error", "Could not join.")); return; }
    const s = this.st;
    const snap = obj(r, "snapshot");
    if (!s || !snap) return;
    s.loadSnapshot(snap);
    this.becameLive();
    this.ensureLinks();   // direct links to everybody else, negotiated through the mesh
    this.bumpTick();
  }

  private ensureLinks() {
    const s = this.st;
    if (!s) return;
    const intro = this.joinTarget;
    for (const p of s.participants.values()) {
      if (p.sid === this.mySid) continue;
      if (intro && p.sid !== intro) this.core.mesh.noteRoute(p.sid, intro);
      if (!this.core.mesh.link(p.sid)) this.core.mesh.connectTo(p.sid);
    }
  }

  /* ---------------- requests / events ---------------- */

  request(kind: string, data: J, cb?: (r: J) => void) {
    const s = this.st;
    if (!s) { cb?.({ ok: false, error: "Not in a room." }); return; }
    if (this.engine && this.isEngine) {
      let r: J;
      try { r = this.engine.handle(this.mySid, kind, data); } catch { r = { ok: false, error: "Something went wrong." }; }
      cb?.(r);
      this.bumpTick();
      return;
    }
    const target = s.engineSid();
    const id = Ids.id("q_");
    if (!target || !this.core.mesh.sendTo(target, { t: "req", id, k: kind, d: data })) {
      cb?.({ ok: false, error: "The host cannot be reached right now." });
      return;
    }
    this.waiting.set(id, (r) => {
      if (!bool(r, "ok") && str(r, "error") === "Join a room first.") {
        this.toast("You lost the connection to this room");
        this.resetLocal("You lost the connection to the room.");
      } else cb?.(r);
    });
    this.timeouts.set(id, later(9000, () => {
      this.timeouts.delete(id);
      const f = this.waiting.get(id);
      this.waiting.delete(id);
      f?.({ ok: false, error: "The host did not answer in time." });
    }));
  }

  private onReq(from: string, link: PeerLink, m: J) {
    const s = this.st;
    if (!s) return;
    const p = s.participants.get(from);
    let res: J;
    if (!this.engine || !this.isEngine) res = { ok: false, error: "Not the host any more." };
    else if (!p || p.uid !== link.uid) res = { ok: false, error: "Join a room first." };
    else {
      try { res = this.engine.handle(from, str(m, "k"), obj(m, "d") ?? {}); } catch { res = { ok: false, error: "Something went wrong." }; }
    }
    this.core.mesh.sendTo(from, { t: "res", id: str(m, "id"), r: res });
    this.bumpTick();
  }

  emit(ev: string, data: J, only: string | null = null, except: Set<string> = new Set()) {
    const s = this.st;
    if (!s) return;
    const msg = { t: "ev", e: ev, d: data };
    if (only != null) {
      if (only === this.mySid) this.sideEffects(ev, data); else this.core.mesh.sendTo(only, msg);
      return;
    }
    s.apply(ev, data);
    for (const p of [...s.participants.values()]) {
      if (p.sid === this.mySid || except.has(p.sid)) continue;
      this.core.mesh.sendTo(p.sid, msg);
    }
    if (!except.has(this.mySid)) this.sideEffects(ev, data);
    this.bumpTick();
  }

  private onEv(from: string, m: J) {
    const s = this.st;
    if (!s || this.phase.value !== "live") return;
    if (this.isEngine || from !== s.engineSid()) return;
    const e = str(m, "e");
    const d = obj(m, "d") ?? {};
    s.apply(e, d);
    this.sideEffects(e, d);
    this.bumpTick();
  }

  private sideEffects(e: string, d: J) {
    const s = this.st;
    if (!s) return;
    switch (e) {
      case "room:state": {
        if (!s.participants.has(this.mySid) && this.phase.value === "live") {
          this.toast("You are no longer in the room");
          this.resetLocal("You were removed from the room.");
          return;
        }
        this.speaking.value = new Set([...this.speaking.value].filter((x) => s.participants.has(x)));
        this.ensureLinks();
        this.core.audio.refresh();
        this.core.screen.refresh();
        break;
      }
      case "room:peer-joined": {
        const pj = obj(d, "participant");
        const p = pj ? participantFrom(pj) : null;
        if (p && p.sid !== this.mySid) {
          this.toast(`${p.displayName} joined`);
          this.core.sfx.play("join");
          if (!this.core.mesh.link(p.sid)) this.core.mesh.connectTo(p.sid);
          later(1500, () => this.core.screen.refresh());
        }
        break;
      }
      case "room:peer-left": {
        const sid = str(d, "sid");
        const sp = new Set(this.speaking.value); sp.delete(sid); this.speaking.value = sp;
        const cu = new Map(this.cursors.value); cu.delete(sid); this.cursors.value = cu;
        // a connection that only existed for the room goes with the person; a friend's connection stays
        const gl = this.core.mesh.link(sid);
        if (gl && gl.purpose !== "dm" && gl.purpose !== "sync") this.core.mesh.closeLink(sid, "left");
        this.core.sfx.play("leave");
        break;
      }
      case "room:role": this.toast(str(d, "reason")); this.core.audio.refresh(); this.core.sfx.play("hand"); break;
      case "room:force-muted": this.toast("The host muted your microphone"); this.core.audio.refresh(); break;
      case "room:mute": this.core.audio.refresh(); break;
      case "room:kicked": this.toast(str(d, "reason")); this.resetLocal(str(d, "reason")); break;
      case "room:ended": this.resetLocal(str(d, "reason", "The room has ended.")); break;
      case "room:reaction": this.reactions.emit({ sid: str(d, "sid"), emoji: str(d, "emoji"), name: str(d, "displayName"), id: nowMs() + Math.floor(Math.random() * 1000) }); break;
      case "chat:new":
        if (str(d, "uid") !== this.myUid) { this.unreadChat.value = this.unreadChat.value + 1; this.core.sfx.play("message"); }
        break;
      case "screen:started": case "screen:stopped": this.core.audio.refresh(); break;
      case "chess:game":
        if (str(d, "status") === "waiting" && str(d, "creator") !== this.myUid && !this.chessSeen.has(str(d, "id"))) {
          this.chessSeen.add(str(d, "id"));
          const g = ChessGame.from(d);
          if (g) { this.toast(`${g.seats[0]?.name ?? "Someone"} created a ${g.timeLabel} ${g.label} game`); this.core.sfx.play("message"); }
        }
        break;
      case "notify": this.toast(str(d, "text")); break;
    }
  }

  audit(action: string, target: string, detail: string) {
    const s = this.st;
    if (s) this.core.mod.log(action, s.meta, target, detail);
  }
  storeReport(r: Report) { this.core.mod.addReport(r); this.toast("A report was filed to you"); }
  bump(counter: string) { this.core.mod.bump(counter); }
  noteJoin(count: number) {
    const s = this.st;
    if (!s) return;
    const sr = this.core.rooms.get(s.meta.id);
    if (!sr) return;
    this.core.rooms.upsert({ ...sr, meta: s.meta, lastActiveAt: nowMs(), totalJoins: sr.totalJoins + 1, peak: Math.max(sr.peak, count) });
  }

  /* ---------------- local-network hosting ---------------- */

  beacon(): J | null {
    const s = this.st;
    if (!s || !this.isEngine || s.meta.unlisted || this.phase.value !== "live") return null;
    const host = [...s.participants.values()].find((p) => p.role === "host")?.displayName ?? s.meta.ownerName;
    return { room: s.meta.id, title: s.meta.title, topic: s.meta.topic, tags: s.meta.tags, host, n: s.participants.size, spk: [...s.participants.values()].filter((p) => canSpeak(p.role)).length, lock: s.meta.locked };
  }

  admit(hello: J): string | null {
    const s = this.st;
    if (!s) return "This room is closed.";
    if (!this.isEngine) return "This device is not hosting.";
    const uid = str(hello, "uid");
    if (s.meta.bans[uid] !== undefined && (s.meta.bans[uid] === 0 || s.meta.bans[uid] > nowMs())) return "You were removed from this room.";
    if (s.meta.locked && uid !== s.meta.ownerUid) return "This room is locked by the host.";
    if (s.participants.size >= 24) return "Room is full.";
    return null;
  }

  async issueInvite(_roomId: string): Promise<{ id: string; code: string | null; why?: string }> {
    const s = this.st;
    if (!s) return { id: "", code: null };
    return this.core.mesh.createInvite("room", s.meta.id, this.previewJson());
  }
  acceptAnswer(answer: J): Promise<boolean> { return this.core.mesh.applyAnswer(answer); }

  previewJson(): J {
    const s = this.st;
    if (!s) return {};
    return { t: s.meta.title, h: s.meta.ownerName, n: s.participants.size, l: s.meta.locked };
  }

  /* ---------------- actions used by the UI ---------------- */

  private err = (r: J) => { if (!bool(r, "ok")) this.toast(str(r, "error")); };

  setMuted(muted: boolean) {
    const s = this.st, p = this.me;
    if (!s || !p) return;
    if (!canSpeak(p.role) && !muted) { this.toast("Ask the host for the stage first"); return; }
    const m = new Map(s.participants); m.set(this.mySid, { ...p, muted }); s.participants = m;
    this.core.audio.refresh();
    this.core.sfx.play(muted ? "mute" : "unmute");
    this.bumpTick();
    this.request("room:mute", { muted });
  }

  toggleDeafen() {
    this.deafened.value = !this.deafened.value;
    this.core.audio.refresh();
    this.toast(this.deafened.value ? "Deafened. You can't hear the room" : "Listening again");
  }

  raiseHand(on: boolean) { this.request("room:hand", { raised: on }); }
  react(emoji: string) { this.request("room:reaction", { emoji }); }
  promote(sid: string) { this.request("room:promote", { sid }, this.err); }
  demote(sid: string) { this.request("room:demote", { sid }, this.err); }
  muteUser(sid: string) { this.request("room:mute-user", { sid }, this.err); }
  kick(sid: string) { this.request("room:kick", { sid }, this.err); }
  ban(sid: string, ms: number) { this.request("room:ban", { sid, ms }, this.err); }
  unban(uid: string) { this.request("room:unban", { uid }); }
  setLocked(on: boolean) { this.request("room:lock", { locked: on }, this.err); }
  editRoom(title: string, topic: string, tags: string[], cb: (r: J) => void) { this.request("room:edit", { title, topic, tags }, cb); }

  sendChat(text: string) { this.request("chat:send", { text }, this.err); }
  reactChat(id: string, emoji: string) { this.request("chat:react", { id, emoji }); }
  deleteChat(id: string) { this.request("chat:delete", { id }, this.err); }

  fileReport(targetType: string, targetId: string, targetLabel: string, category: string, details: string, excerpt: string, cb: (ok: boolean) => void) {
    const s = this.st;
    if (!s) { cb(false); return; }
    const mine: Report = {
      id: Ids.id("rep_"), reporterUid: this.myUid, reporterName: this.me?.displayName ?? "", targetType, targetId, targetLabel, roomId: s.meta.id, roomTitle: s.meta.title,
      category, details: details.slice(0, 1000), status: "open", createdAt: nowMs(), note: "", resolvedAt: 0, outgoing: true, excerpt: excerpt.slice(0, 300)
    };
    this.request("report:file", { targetType, targetId, targetLabel, category, details, excerpt }, (r) => {
      if (bool(r, "ok")) this.core.mod.addReport(mine);
      cb(bool(r, "ok"));
    });
  }

  /* ---- screen share ---- */
  announceShare(res: number, fps: number, cb: (r: J) => void) { this.request("screen:start", { quality: { res, fps } }, cb); }
  shareQuality(res: number, fps: number) { this.request("screen:quality", { quality: { res, fps } }); }
  announceStopShare() { this.request("screen:stop", {}); }

  /* ---- Watch Together ---- */
  watchStart(v: WatchVideo) { this.request("watch:start", { video: videoJson(v) }, this.err); }
  watchControl(action: string, value = 0) { this.request("watch:control", { action, value }, this.err); }
  watchMeta(duration: number) { this.request("watch:meta", { duration }); }
  watchQueueAdd(v: WatchVideo) { this.request("watch:queue", { action: "add", item: videoJson(v) }, this.err); }
  watchQueueRemove(qid: string) { this.request("watch:queue", { action: "remove", item: { qid } }, this.err); }
  watchQueueClear() { this.request("watch:queue", { action: "clear" }, this.err); }
  watchPerm(mode: string) { this.request("watch:perm", { mode }, this.err); }
  watchMod(uid: string, on: boolean) { this.request("watch:mod", { uid, on }, this.err); }
  watchEnd() { this.request("watch:end", {}, this.err); }

  /* ---- chess ---- */
  chessCreate(mode: string, base: number, inc: number, cb: (ok: boolean) => void) {
    this.request("chess:create", { mode, base, inc }, (r) => { this.err(r); cb(bool(r, "ok")); });
  }
  chessAct(kind: string, id: string, extra: J = {}, cb?: (ok: boolean) => void) {
    this.request(kind, { id, ...extra }, (r) => { this.err(r); cb?.(bool(r, "ok")); });
  }
  private startChessLoop() {
    cancelLater(this.chessLoop);
    this.chessLoop = later(250, () => {
      if (this.st && this.phase.value === "live") {
        if (this.isEngine) { try { this.engine?.tick(); } catch { /* ignore */ } }
        this.startChessLoop();
      }
    });
  }

  /* ---- blackboard ---- */
  boardAdd(o: BoardObj, cb?: (ok: boolean) => void) {
    const s = this.st;
    if (!s) return;
    o.by = this.myUid;
    if (!this.isEngine) { o.z = s.board.z; s.board.add(o); }
    this.request("board:add", { obj: o.toJson() }, (r) => {
      if (!bool(r, "ok")) {
        if (!this.isEngine) s.board.remove([o.id]);
        this.toast(str(r, "error"));
      }
      cb?.(bool(r, "ok"));
      this.bumpTick();
    });
  }
  boardUpdate(id: string, patch: J) {
    const s = this.st;
    if (!s) return;
    if (!this.isEngine) s.board.patch(id, patch);
    this.request("board:update", { id, patch }, this.err);
    this.bumpTick();
  }
  boardDelete(ids: string[]) {
    const s = this.st;
    if (!s) return;
    if (!this.isEngine) s.board.remove(ids);
    this.request("board:delete", { ids });
    this.bumpTick();
  }
  boardClear() { this.request("board:clear", {}, this.err); }

  sendCursor(x: number, y: number, laser: boolean) {
    const t = nowMs();
    if (t - this.lastCursor < 40) return;
    this.lastCursor = t;
    this.core.mesh.broadcast({ t: "cur", x, y, l: laser }, false);
  }
  private onCursor(from: string, m: J) {
    const p = this.st?.participants.get(from);
    if (!p) return;
    const map = new Map(this.cursors.value);
    if ("off" in m) map.delete(from);
    else map.set(from, { x: dbl(m, "x"), y: dbl(m, "y"), laser: bool(m, "l"), name: p.displayName, hue: p.avatar.hue, at: nowMs() });
    this.cursors.value = map;
  }
  hideCursor() { this.core.mesh.broadcast({ t: "cur", off: true }, false); }

  /** Our own speaking state, from the mic meter; shared directly with everyone. */
  setSpeakingSelf(on: boolean) {
    const sp = new Set(this.speaking.value);
    if (on) sp.add(this.mySid); else sp.delete(this.mySid);
    this.speaking.value = sp;
    this.core.mesh.broadcast({ t: "spk", v: on }, false);
  }

  /* ---- shared browser ---- */
  browsePresence(open: boolean) { this.request("browse:presence", { open }); }
  browseNavigate(url: string, title: string, host: string) { this.request("browse:navigate", { url, title, host }, this.err); }
  browseStep(forward: boolean) { this.request("browse:step", { dir: forward ? "forward" : "back" }, this.err); }
  browseReload() { this.request("browse:reload", {}, this.err); }
  browseHome() { this.request("browse:home", {}, this.err); }
  browseTitle(url: string, title: string) { this.request("browse:title", { url, title }); }
  browsePerm(mode: string) { this.request("browse:perm", { mode }, this.err); }
  browseControl(action: string, uid = "") { this.request("browse:control", { action, uid }, this.err); }
  browseEnd() { this.request("browse:end", {}, this.err); }

  /* ---- helpers for the UI ---- */
  canBrowseControl(): boolean {
    const p = this.me, b = this.st?.browse;
    if (!p || !b) return false;
    return b.perm === "everyone" || (b.perm === "mods" ? p.role === "host" || b.mods.includes(p.uid) : p.role === "host");
  }
  canWatchControl(): boolean {
    const p = this.me, w = this.st?.watch;
    if (!p || !w) return false;
    return w.perm === "everyone" || (w.perm === "mods" ? p.role === "host" || w.mods.includes(p.uid) : p.role === "host");
  }
  participantByUid(uid: string): Participant | undefined { return [...(this.st?.participants.values() ?? [])].find((p) => p.uid === uid); }
  linkTo(sid: string): PeerLink | undefined { const l = this.core.mesh.link(sid); return l?.authed ? l : undefined; }
}
