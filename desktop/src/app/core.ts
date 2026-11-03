import { Docs, DmStore, GroupStore, Media, ModStore, ProfileStore, RoomsStore, SettingsStore, SocialStore } from "../core/store";
import { avatarJson } from "../core/models";
import { pairSecret } from "../core/identity";
import { Emitter, Flow, J, arr, later, nowMs, obj, str, strings } from "../core/util";
import { native } from "../core/native";
import { Mesh, MeshHandler } from "../net/mesh";
import { LanApp, LanService } from "../net/lan";
import type { PeerLink } from "../net/peerlink";
import { unpack } from "../net/sdp";
import { DmHub } from "../room/dmhub";
import { RoomSession } from "../room/session";
import { AudioController } from "../media/audio";
import { RtcEngine } from "../media/rtc";
import { ScreenShare } from "../media/screen";
import { Sfx } from "../media/sfx";
import { Discovery } from "./discovery";
import { Rendezvous } from "./rendezvous";
import type { Card, CodeResult } from "./types";
import { DeviceSync } from "./sync";

const DM_TYPES = new Set(["m", "mr", "mq", "mb", "dmack", "fol", "gh", "gr", "gi", "gu", "gl", "call", "callr", "callx"]);

/** The application: owns every store and service and routes network events between them. */
export class AppCore implements MeshHandler, LanApp {
  readonly profile = new ProfileStore();
  readonly settings = new SettingsStore();
  readonly social = new SocialStore();
  readonly dms = new DmStore();
  readonly groups = new GroupStore();
  readonly rooms = new RoomsStore();
  readonly mod = new ModStore();

  readonly rtc = new RtcEngine(this.settings);
  readonly mesh = new Mesh(this.rtc, this.profile, this.social);
  readonly lan = new LanService(() => this.mesh.mySid);
  readonly session: RoomSession = new RoomSession(this);
  readonly audio = new AudioController(this);
  readonly sfx = new Sfx(this.settings);
  readonly screen = new ScreenShare(this);
  readonly dmHub: DmHub = new DmHub(this);
  readonly discovery = new Discovery(this);
  readonly rendezvous = new Rendezvous(this, this.discovery);
  readonly sync = new DeviceSync(this);

  readonly toasts = new Emitter<string>();
  /** uids we currently hold a verified live link to */
  readonly connected = new Flow<Set<string>>(new Set());
  /** a code that arrived (homit:// link, drag and drop) and should be opened by the UI */
  readonly pendingCode = new Flow<string | null>(null);
  readonly pendingAdd = new Flow<Card | null>(null);
  readonly ready = new Flow(false);
  private lanTries = new Map<string, number>();
  private inited = false;

  async init() {
    if (this.inited) return;
    this.inited = true;
    await Docs.init();
    await Media.init();
    this.settings.load(); this.social.load(); this.dms.load(); this.groups.load(); this.rooms.load(); this.mod.load();
    await this.profile.load();
    this.profile.onChanged = () => { this.discovery.profileChanged(); this.sync.profileChanged(); };
    this.mesh.handler = this;
    this.discovery.rv = this.rendezvous;
    this.mesh.start();
    this.mesh.lanHint = () => this.lanAddrs;
    void this.refreshLanAddrs();
    void this.lan.startServer(this);
    if (this.settings.state.value.nearby) this.lan.startListening();
    await this.discovery.init();
    this.rendezvous.start();
    this.sync.start();
    later(3000, () => this.lanReconnectTick());
    window.addEventListener("beforeunload", () => this.flushAll());
    window.addEventListener("online", () => this.rendezvous.resume());
    this.ready.value = true;
  }

  private lanAddrs: string[] = [];
  private async refreshLanAddrs() {
    try { this.lanAddrs = await this.lan.addresses(); } catch { /* ignore */ }
    later(15_000, () => void this.refreshLanAddrs());
  }

  /** Saved friends who show up on the same network are reconnected automatically. */
  private lanReconnectTick() {
    try {
      const st = this.settings.state.value;
      const me = this.profile.uid;
      if (st.nearby && this.profile.profile.value) {
        for (const p of this.lan.people.value) {
          const c = this.social.contact(p.uid);
          if (!c || !c.pair || this.social.isBlocked(p.uid) || this.connected.value.has(p.uid)) continue;
          const last = this.lanTries.get(p.uid) ?? 0;
          if (nowMs() - last < 25_000) continue;
          // lower id goes first; the other waits a moment in case the first try already works
          if (me > p.uid && nowMs() - last < 33_000 && last !== 0) continue;
          this.lanTries.set(p.uid, nowMs());
          if (me > p.uid) later(6000, () => { if (!this.connected.value.has(p.uid)) void this.connectNearby(p); });
          else void this.connectNearby(p);
        }
      }
    } catch { /* ignore */ }
    later(4000, () => this.lanReconnectTick());
  }

  flushAll() {
    this.dms.flush(); this.groups.flush(); this.social.flush(); this.rooms.flush(); this.mod.flush(); this.settings.flush();
  }

  toast(t: string) { if (t) this.toasts.emit(t); }
  contactName(uid: string): string { return this.social.contact(uid)?.displayName ?? "Someone"; }

  /** Ask for the microphone early so the permission prompt doesn't land mid-call. */
  requestMic() { void this.rtc.ensureMic().then(() => { if (this.session.phase.value === "idle") this.rtc.releaseMic(); }); }

  /* ---------------- background service hooks (desktop: nothing to start) ---------------- */
  startService() { native.setBadge(0); }
  stopService() { /* nothing */ }

  /* ---------------- first contact: codes ---------------- */

  async createInviteCode(purpose: string): Promise<{ id: string; code: string | null }> {
    if (purpose.startsWith("group:")) {
      const g = this.groups.get(purpose.slice(6));
      if (!g || g.left) return { id: "", code: null };
      return this.mesh.createInvite("dm", purpose, { t: g.name, h: "group" });
    }
    if (purpose === "room") {
      const s = this.session.room;
      if (!s || !this.session.live) return { id: "", code: null };
      return this.mesh.createInvite("room", s.meta.id, this.session.previewJson());
    }
    if (purpose === "sync") return this.mesh.createInvite("sync", "", { t: this.profile.profile.value?.displayName ?? "", h: "my device" });
    const p = this.profile.profile.value;
    return this.mesh.createInvite("dm", "", { t: p?.displayName ?? "", h: p?.username ?? "" });
  }

  async previewOfCode(text: string): Promise<J | null> { const j = await unpack(text); return j ? obj(j, "v") : null; }

  async handleCode(text: string, autoDeliver = true): Promise<CodeResult> {
    const j = await unpack(text);
    if (!j) return { kind: "error", msg: "That doesn't look like a HoMit code." };
    switch (str(j, "k")) {
      case "o": {
        const purpose = str(j, "p", "room");
        if (purpose === "room" && this.session.phase.value !== "idle") return { kind: "error", msg: "Leave your current room before joining another." };
        const uid = str(j, "u");
        if (this.social.isBlocked(uid)) return { kind: "error", msg: "You blocked this person." };
        if (uid === this.profile.uid && purpose !== "sync") return { kind: "error", msg: "That's your own code." };
        const { answer, remoteSid } = await this.mesh.acceptInvite(j);
        if (!answer) {
          if (remoteSid && this.mesh.link(remoteSid)?.authed) return { kind: "error", msg: "You are already connected to this person." };
          return { kind: "error", msg: "Could not prepare a connection. Try again." };
        }
        if (purpose === "room") this.session.expectJoin = remoteSid;
        const title = str(obj(j, "v") ?? {}, "t") || str(j, "n");
        const addrs = strings(arr(j, "l"));
        let auto = false;
        if (autoDeliver && addrs.length) auto = await this.lan.deliverAnswer(addrs, answer);
        return { kind: "answer", code: answer, from: remoteSid, title, purpose, auto };
      }
      case "a": {
        const ok = await this.mesh.applyAnswer(j);
        return ok ? { kind: "applied" } : { kind: "error", msg: "That answer doesn't match an open invite. It may have expired. Create a new invite." };
      }
      default: return { kind: "error", msg: "Unknown code." };
    }
  }

  /* ---------------- social ---------------- */

  follow(uid: string, on: boolean) { this.social.follow(uid, on); this.dmHub.sendFollow(uid, on); }

  block(uid: string, on: boolean) {
    this.social.block(uid, on);
    if (on) {
      this.dmHub.sendFollow(uid, false);
      const l = this.mesh.linkForUid(uid);
      if (l) this.mesh.closeLink(l.remoteSid, "blocked");
    }
  }

  async wipeAll() {
    try { this.session.leave(); } catch { /* ignore */ }
    await Docs.wipeAll();
    this.dms.wipe(); this.mod.wipe(); this.groups.wipe(); Media.wipe();
    this.social.contacts.value = {}; this.social.following.value = []; this.social.followers.value = []; this.social.blocked.value = [];
    this.rooms.rooms.value = [];
    await this.profile.load();
  }

  /** After restoring a backup the whole stack must start over with the new identity. */
  async afterIdentityChange() {
    location.reload();
  }

  /** Both devices derive the same friendship secret from their two identity keys; nothing has to be exchanged. */
  pairFor(uid: string, pub: string): Promise<string | null> { return pairSecret(this.profile.identity, this.profile.uid, uid, pub); }

  /* ---------------- LanApp ---------------- */

  presence(): J | null {
    const st = this.settings.state.value;
    const p = this.profile.profile.value;
    if (!p || !st.nearby || !st.showOnline) return null;
    return { uid: this.profile.uid, name: p.displayName, user: p.username, av: avatarJson(p.avatar) };
  }
  admitFriend(hello: J): string | null { return this.social.isBlocked(str(hello, "uid")) ? "Not available." : null; }
  async issueFriendInvite(): Promise<string | null> { return (await this.createInviteCode("dm")).code; }
  acceptAnswer(answer: J): Promise<boolean> { return this.mesh.applyAnswer(answer); }

  /** Connect to somebody found on the same Wi-Fi, no codes involved. Resolves with an error text or null. */
  async connectNearby(p: { uid: string; name: string; ip: string; port: number; username: string; avatar: J | null; seenAt: number }): Promise<string | null> {
    const me = this.profile.profile.value;
    if (!me) return "Profile missing";
    if (this.social.isBlocked(p.uid)) return "You blocked this person.";
    return this.lan.connectPerson(p, { uid: this.profile.uid, name: me.displayName }, async (code) => {
      const r = await this.handleCode(code, false);
      return r.kind === "answer" ? r.code : null;
    });
  }

  /* ---------------- MeshHandler ---------------- */

  private refreshConnected() {
    this.connected.value = new Set(this.mesh.authedLinks().filter((l) => l.uid !== this.profile.uid).map((l) => l.uid));
  }

  onLinkAuthed(link: PeerLink) {
    this.refreshConnected();
    if (link.uid === this.profile.uid) { this.sync.onLinkAuthed(link); return; }
    if (link.purpose === "dm") void this.shareFriendSecret(link);
    this.dmHub.onLinkAuthed(link);
    this.session.onLinkAuthed(link);
    this.audio.attach(link);
    this.screen.attach(link);
    if (link.purpose === "dm") this.toast(link.offerer ? `${this.contactName(link.uid)} connected` : `Connected to ${this.contactName(link.uid)}`);
  }

  private async shareFriendSecret(link: PeerLink) {
    const c = this.social.contact(link.uid);
    if (!c) return;
    const s = await this.pairFor(link.uid, c.pub);
    if (s) this.social.setPair(link.uid, s);
    this.rendezvous.kick();
  }

  onLinkGone(link: PeerLink, reason: string) {
    this.refreshConnected();
    if (link.uid === this.profile.uid) { this.sync.onLinkGone(link); return; }
    if (link.authed && link.purpose === "dm") this.rendezvous.lost(link.uid);
    this.session.onLinkGone(link, reason);
  }

  onLinkState(link: PeerLink, state: string) {
    if (state === "connected" && link.authed && link.uid !== this.profile.uid) this.audio.attach(link);
    this.refreshConnected();
  }

  onTrack(link: PeerLink, _kind: string) {
    if (link.authed) this.audio.attach(link);
    this.session.bumpTick();
  }

  onMessage(fromSid: string, msg: J, link: PeerLink | null) {
    const t = str(msg, "t");
    if (link && link.uid === this.profile.uid) { this.sync.onMessage(link, msg); return; }
    if (DM_TYPES.has(t)) { if (link) this.dmHub.onMessage(link, msg); }
    else this.session.onMessage(fromSid, msg, link);
  }
}
