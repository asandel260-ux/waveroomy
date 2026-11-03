import { CHESS_MARK, ChessGame, ChessModes } from "../chess/game";
import {
  Avatar, BOARD_TYPES, BoardObj, ChatMsg, Participant, REACTIONS, Report, RoomState, WatchVideo, avatarJson, browseJson, canSpeak, chatJson,
  isBanned, metaJson, mkVideo, newBrowse, newWatch, expectedPosition, participantJson, sharingJson, videoJson, watchSnapshot
} from "../core/models";
import { J, Ids, RateLimiter, arr, bool, clamp, dbl, int, lng, nowMs, obj, str, strings } from "../core/util";

/** Everything the engine needs from the device it runs on. */
export interface EngineIO {
  readonly state: RoomState;
  /** Apply [ev] to the local mirror and deliver it to every other participant (except [except]); if [only] is set, deliver just to that sid. */
  emit(ev: string, data: J, only?: string | null, except?: Set<string>): void;
  audit(action: string, target: string, detail: string): void;
  storeReport(r: Report): void;
  bump(counter: string): void;
  noteJoin(count: number): void;
}

const ok = (extra: J = {}): J => ({ ok: true, ...extra });
const err = (msg: string): J => ({ ok: false, error: msg });

/**
 * The room's rules. It runs on exactly one device at a time: the acting host.
 * Everyone else sends it requests and applies the events it emits, so there is
 * still one authority, just not a server. Port of room/RoomEngine.kt.
 */
export class RoomEngine {
  private limiter = new RateLimiter();
  constructor(private io: EngineIO) {}
  private get st(): RoomState { return this.io.state; }

  private isMod(sid: string) { return this.st.participants.get(sid)?.role === "host"; }

  private broadcastState(except: Set<string> = new Set()) {
    this.io.emit("room:state", { meta: metaJson(this.st.meta), participants: [...this.st.participants.values()].map(participantJson) }, null, except);
  }

  /** Re-send the full participant list (used after a host hand-over). */
  reconcile() { this.broadcastState(); }

  private setP(sid: string, f: (p: Participant) => Participant) {
    const p = this.st.participants.get(sid);
    if (!p) return;
    const m = new Map(this.st.participants);
    m.set(sid, f(p));
    this.st.participants = m;
  }

  /* ======================================================================
   * Membership
   * ==================================================================== */

  handleJoin(sid: string, uid: string, username: string, displayName: string, avatar: Avatar, via: string): J {
    const st = this.st;
    if (isBanned(st.meta, uid)) return err("You were removed from this room.");
    const existing = [...st.participants.values()].find((p) => p.uid === uid);
    if (existing && existing.sid !== sid) this.handleLeave(existing.sid);   // same person re-joining from a new session
    if (st.participants.size >= 24) return err("Room is full (24 max).");
    const isOwner = uid === st.meta.ownerUid;
    if (st.meta.locked && !isOwner) return err("This room is locked by the host.");
    const hasHost = [...st.participants.values()].some((p) => p.role === "host");
    const role = isOwner || !hasHost ? "host" : "speaker";
    const p: Participant = {
      sid, uid, username, displayName, avatar, role, muted: true, hand: false, sharing: null, browsing: false, joinedAt: nowMs(), via
    };
    const m = new Map(st.participants);
    m.set(sid, p);
    st.participants = m;
    this.io.noteJoin(m.size);
    this.io.bump("joins");
    const snapshot = st.snapshot();
    this.broadcastState(new Set([sid]));
    this.io.emit("room:peer-joined", { participant: participantJson(p) }, null, new Set([sid]));
    return ok({ you: { sid, role }, snapshot });
  }

  handleLeave(sid: string) {
    const st = this.st;
    const p = st.participants.get(sid);
    if (!p) return;
    const m = new Map(st.participants);
    m.delete(sid);
    st.participants = m;
    if (p.sharing) this.io.emit("screen:stopped", { sid });
    if (m.size === 0) return;
    this.chessLeft(p);
    if (p.role === "host" && ![...m.values()].some((x) => x.role === "host")) {
      const next = [...m.values()].sort((a, b) => a.joinedAt - b.joinedAt || (a.sid < b.sid ? -1 : 1))[0];
      m.set(next.sid, { ...next, role: "host" });
      st.participants = m;
      this.io.emit("room:role", { role: "host", reason: "The host left. You are now hosting." }, next.sid);
    }
    this.broadcastState();
    this.io.emit("room:peer-left", { sid });
  }

  /* ======================================================================
   * Requests
   * ==================================================================== */

  handle(from: string, kind: string, d: J): J {
    const st = this.st;
    const me = st.participants.get(from);
    if (!me) return err("Join a room first.");
    switch (kind) {
      case "room:mute": {
        const muted = bool(d, "muted");
        if (!muted && !canSpeak(me.role)) return err("Ask the host for the stage first.");
        this.setP(from, (p) => ({ ...p, muted }));
        this.io.emit("room:mute", { sid: from, muted });
        return ok();
      }
      case "room:hand":
        this.setP(from, (p) => ({ ...p, hand: bool(d, "raised") }));
        this.broadcastState();
        return ok();
      case "room:reaction": {
        const emoji = str(d, "emoji");
        if (!REACTIONS.includes(emoji)) return err("Unknown reaction.");
        if (!this.limiter.allow("react:" + from, 20, 10_000)) return err("Slow down a little.");
        this.io.emit("room:reaction", { sid: from, emoji, displayName: me.displayName });
        return ok();
      }
      case "room:promote": return this.promote(from, str(d, "sid"));
      case "room:demote": return this.demote(from, str(d, "sid"));
      case "room:mute-user": {
        if (!this.isMod(from)) return err("Only the host can do that.");
        const t = st.participants.get(str(d, "sid"));
        if (!t) return err("Not in the room.");
        this.setP(t.sid, (p) => ({ ...p, muted: true }));
        this.io.emit("room:force-muted", {}, t.sid);
        this.io.emit("room:mute", { sid: t.sid, muted: true });
        this.io.audit("room.mute", "@" + t.username, "microphone muted by host");
        return ok();
      }
      case "room:kick": return this.kick(from, str(d, "sid"), 0, false);
      case "room:ban": return this.kick(from, str(d, "sid"), lng(d, "ms"), true);
      case "room:unban": {
        if (!this.isMod(from)) return err("Only the host can do that.");
        const uid = str(d, "uid");
        const b = { ...st.meta.bans };
        delete b[uid];
        st.meta = { ...st.meta, bans: b };
        this.broadcastState();
        this.io.audit("room.unban", uid, "ban lifted");
        return ok();
      }
      case "room:lock": {
        if (!this.isMod(from)) return err("Only the host can do that.");
        st.meta = { ...st.meta, locked: bool(d, "locked") };
        this.broadcastState();
        this.io.audit(st.meta.locked ? "room.lock" : "room.unlock", st.meta.title, "");
        return ok();
      }
      case "room:edit": {
        if (!this.isMod(from)) return err("Only the host can do that.");
        const title = str(d, "title").trim().slice(0, 60);
        if (title.length < 3) return err("Give the room a title (3+ characters).");
        const tags = [...new Set(strings(arr(d, "tags")).map((t) => t.trim().toLowerCase().slice(0, 20)).filter((t) => t))].slice(0, 6);
        st.meta = { ...st.meta, title, topic: str(d, "topic").trim().slice(0, 140), tags };
        this.broadcastState();
        return ok();
      }
      case "room:end": {
        if (!this.isMod(from)) return err("Only the host can do that.");
        this.io.audit("room.end", st.meta.title, "ended for everyone");
        this.io.emit("room:ended", { reason: "The host ended this room." }, null, new Set([from]));
        return ok();
      }
      case "chat:send": return this.chatSend(from, me, str(d, "text"));
      case "chat:react": return this.chatReact(from, me, d);
      case "chat:delete": return this.chatDelete(from, me, str(d, "id"));
      case "screen:start": return this.screenStart(from, me, obj(d, "quality"));
      case "screen:quality": {
        if (!me.sharing) return ok();
        const q = this.cleanQuality(obj(d, "quality"));
        this.setP(from, (p) => ({ ...p, sharing: p.sharing ? { ...p.sharing, res: q[0], fps: q[1] } : null }));
        this.broadcastState();
        return ok();
      }
      case "screen:stop":
        if (me.sharing) {
          this.setP(from, (p) => ({ ...p, sharing: null }));
          this.broadcastState();
          this.io.emit("screen:stopped", { sid: from });
        }
        return ok();
      case "report:file": {
        if (!this.limiter.allow("report:" + me.uid, 5, 60_000)) return err("Slow down a little.");
        const r: Report = {
          id: Ids.id("rep_"), reporterUid: me.uid, reporterName: me.displayName, targetType: str(d, "targetType"), targetId: str(d, "targetId"),
          targetLabel: str(d, "targetLabel"), roomId: st.meta.id, roomTitle: st.meta.title, category: str(d, "category", "other"),
          details: str(d, "details").slice(0, 1000), status: "open", createdAt: nowMs(), note: "", resolvedAt: 0, outgoing: false, excerpt: str(d, "excerpt").slice(0, 300)
        };
        this.io.storeReport(r);
        return ok();
      }
    }
    if (kind.startsWith("watch:")) return this.watch(from, me, kind, d);
    if (kind.startsWith("board:")) return this.board(from, me, kind, d);
    if (kind.startsWith("browse:")) return this.browse(from, me, kind, d);
    if (kind.startsWith("chess:")) return this.chess(me, kind, d);
    return err("Unknown request.");
  }

  /* ---------------- stage ---------------- */

  private promote(from: string, sid: string): J {
    if (!this.isMod(from)) return err("Only the host can do that.");
    const t = this.st.participants.get(sid);
    if (!t) return err("Not in the room.");
    const speakers = [...this.st.participants.values()].filter((p) => canSpeak(p.role)).length;
    if (t.role === "listener") {
      if (speakers >= this.st.meta.maxSpeakers) return err(`The stage is full (${this.st.meta.maxSpeakers} speakers).`);
      this.setP(sid, (p) => ({ ...p, role: "speaker", hand: false }));
      this.io.emit("room:role", { role: "speaker", reason: "You were invited to speak." }, sid);
      this.broadcastState();
    }
    return ok();
  }

  private demote(from: string, sid: string): J {
    if (!this.isMod(from)) return err("Only the host can do that.");
    const t = this.st.participants.get(sid);
    if (!t) return err("Not in the room.");
    if (t.role !== "speaker") return ok();
    const wasSharing = !!t.sharing;
    this.setP(sid, (p) => ({ ...p, role: "listener", muted: true, sharing: null }));
    if (wasSharing) this.io.emit("screen:stopped", { sid });
    this.io.emit("room:role", { role: "listener", reason: "You were moved to the audience." }, sid);
    this.broadcastState();
    return ok();
  }

  private kick(from: string, sid: string, banMs: number, ban: boolean): J {
    if (!this.isMod(from)) return err("Only the host can do that.");
    const t = this.st.participants.get(sid);
    if (!t) return err("Not in the room.");
    if (sid === from) return err("You can't remove yourself.");
    if (ban) {
      const until = banMs <= 0 ? 0 : nowMs() + banMs;
      this.st.meta = { ...this.st.meta, bans: { ...this.st.meta.bans, [t.uid]: until } };
      this.io.audit("room.ban", "@" + t.username, until === 0 ? "permanent" : "until " + new Date(until).toLocaleString(undefined, { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }));
    } else this.io.audit("room.kick", "@" + t.username, "removed from room");
    this.io.emit("room:kicked", { reason: ban ? "You were banned from this room by the host." : "You were removed from the room by the host." }, sid);
    this.handleLeave(sid);
    return ok();
  }

  /* ---------------- chat ---------------- */

  private chatSend(_from: string, me: Participant, text0: string): J {
    if (!this.limiter.allow("chat:" + me.uid, 15, 10_000)) return err("Slow down a little.");
    const t = text0.replace(/\u001F/g, "").trim().slice(0, 500);
    if (!t) return err("Empty message.");
    const msg: ChatMsg = { id: Ids.id("m_"), uid: me.uid, username: me.username, displayName: me.displayName, avatar: me.avatar, text: t, at: nowMs(), deleted: false, reactions: {} };
    this.io.emit("chat:new", chatJson(msg));
    this.io.bump("msgs");
    return ok({ id: msg.id });
  }

  private chatReact(_from: string, me: Participant, d: J): J {
    const emoji = str(d, "emoji");
    if (!REACTIONS.includes(emoji)) return ok();
    const m = this.st.chat.find((x) => x.id === str(d, "id"));
    if (!m || m.deleted) return ok();
    const r: Record<string, string[]> = { ...m.reactions };
    const cur = [...(r[emoji] ?? [])];
    const i = cur.indexOf(me.uid);
    if (i >= 0) cur.splice(i, 1); else cur.push(me.uid);
    if (cur.length === 0) delete r[emoji]; else r[emoji] = cur;
    this.io.emit("chat:update", { id: m.id, deleted: m.deleted, reactions: r });
    return ok();
  }

  private chatDelete(from: string, me: Participant, id: string): J {
    const m = this.st.chat.find((x) => x.id === id);
    if (!m) return ok();
    const mine = m.uid === me.uid;
    if (!mine && !this.isMod(from)) return err("Only the author or the host can delete this.");
    this.io.emit("chat:update", { id, deleted: true, reactions: m.reactions });
    if (!mine) this.io.audit("message.delete", "@" + m.username, "message removed in-room");
    return ok();
  }

  /* ---------------- screen share ---------------- */

  private cleanQuality(q: J | null): [number, number] {
    const res = q ? int(q, "res", 720) : 720;
    const fps = q ? int(q, "fps", 30) : 30;
    return [[240, 480, 720, 1080].includes(res) ? res : 720, [5, 15, 30, 60].includes(fps) ? fps : 30];
  }

  private screenStart(from: string, me: Participant, q: J | null): J {
    if (!canSpeak(me.role)) return err("Take the stage to share your screen.");
    const other = [...this.st.participants.values()].find((p) => p.sharing && p.sid !== from);
    if (other) return err(`${other.displayName} is already sharing.`);
    const cq = this.cleanQuality(q);
    const sh = { res: cq[0], fps: cq[1], at: nowMs() };
    this.setP(from, (p) => ({ ...p, sharing: sh }));
    this.broadcastState();
    this.io.emit("screen:started", { sid: from, displayName: me.displayName, quality: sharingJson(sh) });
    return ok();
  }

  /* ---------------- Watch Together ---------------- */

  private watchCanControl(me: Participant): boolean {
    const w = this.st.watch;
    if (w.perm === "everyone") return true;
    if (w.perm === "mods") return me.role === "host" || w.mods.includes(me.uid);
    return me.role === "host";
  }

  private denyWatch(): J {
    return err(this.st.watch.perm === "mods" ? "Only playback moderators can control this video." : "Playback is currently controlled by the room host.");
  }

  private cleanVideo(v: J | null): WatchVideo | null {
    if (!v || !/^[A-Za-z0-9_-]{11}$/.test(str(v, "id"))) return null;
    return mkVideo(str(v, "id"), str(v, "title", "YouTube video").slice(0, 200), str(v, "author").slice(0, 120), clamp(int(v, "duration"), 0, 86400));
  }

  private loadVideo(v: WatchVideo, me: Participant) {
    this.st.watch = { ...this.st.watch, video: v, playing: true, position: 0, anchorMs: nowMs(), rate: 1, controllerName: me.displayName };
    this.io.emit("watch:load", watchSnapshot(this.st.watch));
  }

  private watch(_from: string, me: Participant, kind: string, d: J): J {
    const st = this.st;
    const w = st.watch;
    switch (kind) {
      case "watch:start": {
        if (!this.watchCanControl(me)) return this.denyWatch();
        const v = this.cleanVideo(obj(d, "video"));
        if (!v) return err("Invalid YouTube link.");
        this.loadVideo(v, me);
        return ok();
      }
      case "watch:control": {
        const v = w.video;
        if (!v) return err("Nothing is playing.");
        if (!this.watchCanControl(me)) return this.denyWatch();
        const nowPos = expectedPosition(w);
        const dur = v.duration;
        let nw = w;
        switch (str(d, "action")) {
          case "play": nw = { ...w, position: nowPos, playing: true }; break;
          case "pause": nw = { ...w, position: nowPos, playing: false }; break;
          case "seek": {
            const raw = dbl(d, "value");
            const target = Number.isFinite(raw) ? clamp(raw, 0, 86_400) : 0;
            nw = { ...w, position: Math.max(0, dur > 0 ? Math.min(target, dur) : target) };
            break;
          }
          case "rate": nw = { ...w, position: nowPos, rate: clamp(dbl(d, "value", 1), 0.25, 2) }; break;
          case "restart": nw = { ...w, position: 0, playing: true }; break;
          case "skip": {
            if (w.queue.length === 0) {
              st.watch = { ...w, playing: false, position: nowPos, anchorMs: nowMs() };
              this.io.emit("watch:state", { ...watchSnapshot(st.watch), controller: me.displayName });
              return ok();
            }
            const next = w.queue[0];
            st.watch = { ...w, queue: w.queue.slice(1) };
            this.loadVideo(next, me);
            return ok();
          }
          default: return err("Unknown action.");
        }
        st.watch = { ...nw, anchorMs: nowMs(), controllerName: me.displayName };
        this.io.emit("watch:state", watchSnapshot(st.watch));
        return ok();
      }
      case "watch:meta": {
        const v = w.video;
        const dur = int(d, "duration");
        if (v && dur > 0 && v.duration === 0) this.io.emit("watch:meta", { duration: Math.min(86400, dur) });
        return ok();
      }
      case "watch:queue": {
        if (!this.watchCanControl(me)) return this.denyWatch();
        switch (str(d, "action")) {
          case "add": {
            const v = this.cleanVideo(obj(d, "item"));
            if (!v) return err("Invalid YouTube link.");
            if (w.queue.length >= 50) return err("Queue is full.");
            const item = { ...v, qid: Ids.id("q_"), by: me.displayName };
            if (!w.video) { this.loadVideo(item, me); return ok(); }
            st.watch = { ...w, queue: [...w.queue, item] };
            break;
          }
          case "remove": {
            const qid = str(obj(d, "item") ?? {}, "qid");
            let i = -1;
            if (qid) i = w.queue.findIndex((x) => x.qid === qid);
            else if ("index" in d) { const idx = int(d, "index", -1); if (idx >= 0 && idx < w.queue.length) i = idx; }
            if (i < 0) return err("That item is no longer in the queue.");
            st.watch = { ...w, queue: w.queue.filter((_, n) => n !== i) };
            break;
          }
          case "clear": st.watch = { ...w, queue: [] }; break;
        }
        this.io.emit("watch:queue", { queue: st.watch.queue.map(videoJson) });
        return ok();
      }
      case "watch:perm": {
        if (me.role !== "host") return err("Only the host can change playback permissions.");
        const mode = str(d, "mode");
        if (!["host", "mods", "everyone"].includes(mode)) return err("Bad mode.");
        this.io.emit("watch:perm", { perm: mode });
        return ok();
      }
      case "watch:mod": {
        if (me.role !== "host") return err("Only the host can manage playback moderators.");
        const uid = str(d, "uid");
        const mods = bool(d, "on") ? (w.mods.includes(uid) ? w.mods : [...w.mods, uid]) : w.mods.filter((x) => x !== uid);
        this.io.emit("watch:mods", { mods });
        return ok();
      }
      case "watch:end": {
        if (me.role !== "host") return err("Only the host can end Watch Together.");
        this.io.emit("watch:ended", {});
        return ok();
      }
    }
    return err("Unknown request.");
  }

  /* ---------------- chess ---------------- */

  private pushGame(g: ChessGame) { this.io.emit("chess:game", g.toJson()); }

  /** Called a few times a second by the acting host: clocks, expiry, abandoned starts. */
  tick() {
    const g = this.st.chess.current;
    if (!g || g.finished) return;
    if (g.tick(nowMs())) this.pushGame(g);
  }

  private chessLeft(p: Participant) {
    if ([...this.st.participants.values()].some((x) => x.uid === p.uid)) return;
    const g = this.st.chess.active;
    if (!g) return;
    const seat = g.seatOf(p.uid);
    if (seat < 0) return;
    if (g.live) { g.resign(seat, nowMs()); this.pushGame(g); return; }
    if (g.status !== "waiting") return;
    if (g.creatorUid === p.uid) g.cancel(nowMs(), false); else g.seats[seat] = null;
    this.pushGame(g);
  }

  private chess(me: Participant, kind: string, d: J): J {
    const st = this.st;
    const now = nowMs();
    if (!this.limiter.allow("chess:" + me.uid, 60, 10_000)) return err("Slow down a little.");
    if (kind === "chess:create") {
      const mode = str(d, "mode");
      if (!ChessModes.valid(mode)) return err("Unknown game mode.");
      const base = int(d, "base"), inc = int(d, "inc");
      if (base < 30 || base > 10800 || inc < 0 || inc > 180) return err("That time control is not available.");
      if (st.chess.active) return err("A game is already open. Finish or cancel it first.");
      const g = new ChessGame(Ids.id("g_"), mode, base, inc, me.uid, now);
      g.seats[0] = { uid: me.uid, name: me.displayName };
      this.pushGame(g);
      const text = CHESS_MARK + g.id + "\u001F" + `${me.displayName} created a ${g.timeLabel} ${g.label} game.`;
      this.io.emit("chat:new", chatJson({ id: Ids.id("m_"), uid: me.uid, username: me.username, displayName: me.displayName, avatar: me.avatar, text, at: now, deleted: false, reactions: {} }));
      return ok({ id: g.id });
    }
    const g = st.chess.games.get(str(d, "id"));
    if (!g) return err("That game is gone.");
    const seat = g.seatOf(me.uid);
    const isCreatorOrHost = g.creatorUid === me.uid || me.role === "host";
    switch (kind) {
      case "chess:join": {
        if (g.status !== "waiting") return err("This game is no longer open.");
        if (seat >= 0) return ok();
        let want = int(d, "seat", -1);
        if (g.bughouse) {
          if (want < 0 || want > 3) want = g.seats.findIndex((s) => s == null);
          else if (g.seats[want] != null) return err("That seat is taken.");
        } else want = g.seats.findIndex((s) => s == null);
        if (want < 0) return err("This game is full.");
        g.seats[want] = { uid: me.uid, name: me.displayName };
        if (!g.bughouse && g.filled()) g.begin(now, true);
        this.pushGame(g);
        return ok();
      }
      case "chess:leave": {
        if (g.status !== "waiting") return err("Resign to leave a running game.");
        if (seat < 0) return ok();
        if (g.creatorUid === me.uid) g.cancel(now, false); else g.seats[seat] = null;
        this.pushGame(g);
        return ok();
      }
      case "chess:cancel": {
        if (g.status !== "waiting") return err("This game already started.");
        if (!isCreatorOrHost) return err("Only the person who made the game can cancel it.");
        g.cancel(now, false);
        this.pushGame(g);
        return ok();
      }
      case "chess:start": {
        if (g.status !== "waiting") return ok();
        if (!isCreatorOrHost) return err("Only the person who made the game can start it.");
        if (!g.filled()) return err("Waiting for all four players.");
        g.begin(now, false);
        this.pushGame(g);
        return ok();
      }
      case "chess:move": {
        if (seat < 0) return err("You are not playing.");
        const e = g.applyMove(seat, str(d, "m"), int(d, "ply", -1), now);
        if (e != null) return err(e);
        this.pushGame(g);
        return ok();
      }
      case "chess:resign": {
        if (seat < 0 || !g.live) return err("You are not playing.");
        g.resign(seat, now);
        this.pushGame(g);
        return ok();
      }
      case "chess:draw": {
        if (seat < 0 || !g.live) return err("You are not playing.");
        switch (str(d, "act")) {
          case "offer": { const e = g.drawOffer(seat); if (e) return err(e); break; }
          case "accept":
            if (g.drawBy < 0 || g.drawBy === seat) return ok();
            g.finish(2, "Agreement", now);
            break;
          case "decline": if (g.drawBy < 0 || g.drawBy === seat) return ok(); g.drawBy = -1; break;
          default: return err("Unknown request.");
        }
        this.pushGame(g);
        return ok();
      }
      case "chess:rematch": {
        if (seat < 0 || !g.over) return err("There is nothing to rematch.");
        if (g.next || st.chess.active) return ok();
        g.rematch.add(me.uid);
        const ids = g.seats.filter((s) => s).map((s) => s!.uid);
        if (ids.some((id) => ![...st.participants.values()].some((p) => p.uid === id))) {
          this.pushGame(g);
          return err("Someone left the room.");
        }
        if (ids.every((id) => g.rematch.has(id))) {
          const ng = new ChessGame(Ids.id("g_"), g.mode, g.baseSec, g.incSec, g.creatorUid, now);
          for (let i = 0; i < g.n; i++) {
            const src = g.seats[i ^ 1];
            if (!src) continue;
            const name = [...st.participants.values()].find((p) => p.uid === src.uid)?.displayName ?? src.name;
            ng.seats[i] = { uid: src.uid, name };
          }
          ng.begin(now, false);
          g.next = ng.id;
          this.pushGame(g);
          this.pushGame(ng);
        } else this.pushGame(g);
        return ok();
      }
    }
    return err("Unknown request.");
  }

  /* ---------------- shared blackboard ---------------- */

  private board(from: string, me: Participant, kind: string, d: J): J {
    const b = this.st.board;
    if (kind !== "board:clear" && !this.limiter.allow("board:" + from, 400, 10_000)) return err("Slow down a little.");
    switch (kind) {
      case "board:add": {
        const raw = obj(d, "obj");
        if (!raw) return err("Invalid object.");
        if (!BOARD_TYPES.has(str(raw, "type"))) return err("Invalid object.");
        const len = JSON.stringify(raw).length;
        if (len > 900_000) return err("Object too large (max ~600 KB image).");
        const o = BoardObj.from(raw);
        if (!o) return err("Invalid object.");
        if ((o.points?.length ?? 0) > 12_000) return err("Stroke too long.");
        if (b.objects.size >= 4000) return err("Board is full (4000 objects).");
        if (b.bytes() + len > 8_000_000) return err("Board storage limit reached.");
        if (!o.id || b.objects.has(o.id)) return err("Duplicate id.");
        o.z = b.z;
        o.by = me.uid;
        this.io.emit("board:add", { obj: o.toJson() }, null, new Set([from]));
        return ok({ id: o.id, z: o.z });
      }
      case "board:update": {
        const id = str(d, "id");
        const patch = obj(d, "patch");
        if (!patch) return err("No patch.");
        if (JSON.stringify(patch).length > 300_000) return err("Change too large.");
        const o = b.objects.get(id);
        if (!o) return err("Object not found.");
        const clean: J = JSON.parse(JSON.stringify(patch));
        delete clean.id; delete clean.type; delete clean.src; delete clean.by;
        if ("text" in clean) clean.text = str(clean, "text").slice(0, 2000);
        if ("z" in clean) clean.z = clamp(int(clean, "z"), 0, 1_000_000_000);
        o.applyPatch(clean);
        if (o.z >= b.z) b.z = o.z + 1;
        b.touch();
        this.io.emit("board:update", { id, patch: clean }, null, new Set([from]));
        return ok();
      }
      case "board:delete": {
        const ids = strings(arr(d, "ids")).slice(0, 500);
        const removed = ids.filter((i) => b.objects.has(i));
        if (removed.length) {
          b.remove(removed);
          this.io.emit("board:delete", { ids: removed }, null, new Set([from]));
        }
        return ok({ removed });
      }
      case "board:clear": {
        if (me.role !== "host") return err("Only the host can clear the board.");
        this.io.emit("board:cleared", {});
        this.io.audit("board.clear", this.st.meta.title, "blackboard cleared");
        return ok();
      }
    }
    return err("Unknown request.");
  }

  /* ---------------- shared browser ---------------- */

  private browseCan(me: Participant): boolean {
    const b = this.st.browse;
    if (b.perm === "everyone") return true;
    if (b.perm === "mods") return me.role === "host" || b.mods.includes(me.uid);
    return me.role === "host";
  }

  private browseDeny(): J {
    return { ...err(this.st.browse.perm === "mods" ? "Only navigators can browse for the room." : "The room host is driving this session."), needsControl: true };
  }

  private browse(from: string, me: Participant, kind: string, d: J): J {
    const st = this.st;
    const b = st.browse;
    switch (kind) {
      case "browse:presence": {
        const open = bool(d, "open");
        if (me.browsing !== open) {
          this.setP(from, (p) => ({ ...p, browsing: open }));
          this.broadcastState();
        }
        return ok();
      }
      case "browse:navigate": {
        if (!this.browseCan(me)) return this.browseDeny();
        if (!this.limiter.allow("nav:" + from, 30, 10_000)) return err("Slow down a little.");
        const url = str(d, "url");
        if (!/^https?:\/\//i.test(url) || url.length > 2048) return err("That address could not be opened.");
        let hist = b.history.slice(0, b.idx + 1);
        if (hist[hist.length - 1] !== url) hist = [...hist, url];
        if (hist.length > 60) hist = hist.slice(-60);
        const nb = { ...b, url, title: str(d, "title").slice(0, 200), host: str(d, "host").slice(0, 120), history: hist, idx: hist.length - 1, navigatorUid: me.uid, navigatorName: me.displayName, nonce: b.nonce + 1 };
        this.io.emit("browse:load", browseJson(nb));
        return ok();
      }
      case "browse:step": {
        if (!this.browseCan(me)) return this.browseDeny();
        const next = b.idx + (str(d, "dir") === "forward" ? 1 : -1);
        if (next < 0 || next >= b.history.length) return err("No more history.");
        const url = b.history[next];
        let host = "";
        try { host = new URL(url).hostname; } catch { /* ignore */ }
        const nb = { ...b, url, host, title: host, idx: next, navigatorUid: me.uid, navigatorName: me.displayName, nonce: b.nonce + 1 };
        this.io.emit("browse:load", browseJson(nb));
        return ok();
      }
      case "browse:reload": {
        if (!this.browseCan(me)) return this.browseDeny();
        if (b.url == null) return err("Nothing to reload.");
        this.io.emit("browse:reload", browseJson({ ...b, nonce: b.nonce + 1, navigatorUid: me.uid, navigatorName: me.displayName }));
        return ok();
      }
      case "browse:title": {
        if (b.url != null && str(d, "url") === b.url && this.browseCan(me)) this.io.emit("browse:state", browseJson({ ...b, title: str(d, "title").slice(0, 200) }));
        return ok();
      }
      case "browse:home": {
        if (!this.browseCan(me)) return this.browseDeny();
        this.io.emit("browse:load", browseJson({ ...b, url: null, title: "", host: "", history: [], idx: -1, nonce: b.nonce + 1 }));
        return ok();
      }
      case "browse:perm": {
        if (me.role !== "host") return err("Only the host can change browsing permissions.");
        const mode = str(d, "mode");
        if (!["host", "mods", "everyone"].includes(mode)) return err("Bad mode.");
        this.io.emit("browse:state", browseJson({ ...b, perm: mode, requests: mode !== "host" ? [] : b.requests }));
        return ok();
      }
      case "browse:control": return this.browseControl(me, d);
      case "browse:end": {
        if (me.role !== "host") return err("Only the host can end the browsing session.");
        this.io.emit("browse:ended", browseJson(newBrowse({ perm: b.perm, mods: b.mods, nonce: b.nonce + 1 })));
        return ok();
      }
    }
    return err("Unknown request.");
  }

  private browseControl(me: Participant, d: J): J {
    const st = this.st;
    const b = st.browse;
    const uid = str(d, "uid");
    switch (str(d, "action")) {
      case "request": {
        if (this.browseCan(me)) return ok({ already: true });
        if (!this.limiter.allow("navreq:" + me.uid, 5, 60_000)) return err("You already asked. Give it a moment.");
        const rq = b.requests.some((r) => r.uid === me.uid) ? b.requests : [...b.requests, { uid: me.uid, name: me.displayName, at: nowMs() }];
        this.io.emit("browse:state", browseJson({ ...b, requests: rq }));
        for (const h of [...st.participants.values()].filter((p) => p.role === "host")) this.io.emit("notify", { text: `${me.displayName} asked to control the browser.` }, h.sid);
        return ok();
      }
      case "grant": {
        if (me.role !== "host") return err("Only the host can hand over control.");
        if (!uid) return err("Missing person.");
        const mods = b.mods.includes(uid) ? b.mods : [...b.mods, uid];
        this.io.emit("browse:state", browseJson({ ...b, mods, perm: b.perm === "host" ? "mods" : b.perm, requests: b.requests.filter((r) => r.uid !== uid) }));
        for (const p of [...st.participants.values()].filter((x) => x.uid === uid)) this.io.emit("notify", { text: "You can now navigate the shared browser." }, p.sid);
        return ok();
      }
      case "revoke": {
        if (me.role !== "host") return err("Only the host can change navigators.");
        this.io.emit("browse:state", browseJson({ ...b, mods: b.mods.filter((x) => x !== uid), requests: b.requests.filter((r) => r.uid !== uid) }));
        return ok();
      }
      case "release": {
        this.io.emit("browse:state", browseJson({ ...b, mods: b.mods.filter((x) => x !== me.uid), requests: b.requests.filter((r) => r.uid !== me.uid) }));
        return ok();
      }
      case "dismiss": {
        if (me.role !== "host") return err("Only the host can do that.");
        this.io.emit("browse:state", browseJson({ ...b, requests: b.requests.filter((r) => r.uid !== uid) }));
        return ok();
      }
    }
    return err("Unknown action.");
  }
}
void avatarJson; void newWatch;
