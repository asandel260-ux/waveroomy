import { DmMessage, Group, GroupMember, canAdd, dmFrom, dmJson, groupChatId, groupFrom, groupJson, isAdmin, newDm } from "../core/models";
import { Media } from "../core/store";
import type { DmStore, GroupStore, ProfileStore, SettingsStore, SocialStore } from "../core/store";
import { Flow, Ids, J, Timer, arr, b64decode, b64encode, bool, cancelLater, int, later, lng, nowMs, obj, objects, str } from "../core/util";
import type { Mesh } from "../net/mesh";
import type { PeerLink } from "../net/peerlink";
import { native } from "../core/native";

export interface IncomingCall { cid: string; uid: string; chat: string; title: string; group: boolean }

export interface HubCore {
  mesh: Mesh;
  profile: ProfileStore;
  social: SocialStore;
  dms: DmStore;
  groups: GroupStore;
  settings: SettingsStore;
  connected: Flow<Set<string>>;
  session: { phase: Flow<string>; room: { meta: { id: string } } | null; createRoom(t: string, tp: string, tags: string[], unlisted: boolean, max: number): boolean; beginJoin(l: PeerLink): void };
  sfx: { play(n: string): void };
  contactName(uid: string): string;
  toast(t: string): void;
  requestMic(): void;
}

export const SAVED = "saved";
export const isGroup = (chat: string) => chat.startsWith("g:");
export const gid = (chat: string) => chat.slice(2);
export const MAX_FILE = 16 * 1024 * 1024;
export const AUTO_MEDIA = 8 * 1024 * 1024;
const ID_RX = /^[A-Za-z0-9_]{1,40}$/;

interface Dl { sid: string; expect: number; next: number; n: number; bytes: number; lastAt: number; parts: Uint8Array[] }

/**
 * Everything messenger-like. With no server there is no mailbox: a message is pushed to whoever is
 * connected right now, and devices of the same chat catch up with each other whenever two of them
 * connect (digest exchange). Attachments are fetched peer to peer in chunks. Port of room/DmHub.kt.
 */
export class DmHub {
  /** chat id currently on screen (so it is not counted as unread) */
  readonly openChat = new Flow<string | null>(null);
  readonly mediaTick = new Flow(0);
  readonly mediaProgress = new Flow<Record<string, number>>({});
  readonly incomingCall = new Flow<IncomingCall | null>(null);

  private ringTimer: Timer = null;
  private outgoing: { roomId: string; chat: string; uids: string[] } | null = null;
  private downloads = new Map<string, Dl>();

  constructor(private core: HubCore) {}
  private get myUid() { return this.core.profile.uid; }
  private get myName() { return this.core.profile.profile.value?.displayName ?? ""; }

  /* ---------------- addressing ---------------- */

  recipients(chat: string): string[] {
    if (chat === SAVED) return [];
    if (isGroup(chat)) {
      const g = this.core.groups.get(gid(chat));
      return g && !g.left ? Object.keys(g.members).filter((u) => u !== this.myUid) : [];
    }
    return [chat];
  }
  titleOf(chat: string): string {
    if (chat === SAVED) return "Saved messages";
    if (isGroup(chat)) return this.core.groups.get(gid(chat))?.name ?? "Group";
    return this.core.contactName(chat);
  }
  private wireChat(chat: string) { return isGroup(chat) ? gid(chat) : "dm"; }
  private chatFor(link: PeerLink, c: string) { return !c || c === "dm" ? link.uid : "g:" + c; }

  private wire(chat: string, m: DmMessage, backfill = false): string {
    const j = dmJson(m);
    delete j.status; delete j.error;
    j.t = "m"; j.c = this.wireChat(chat);
    if (backfill) j.bf = true;
    return JSON.stringify(j);
  }
  private push(chat: string, text: string) {
    for (const u of this.recipients(chat)) this.core.mesh.linkForUid(u)?.send(text);
  }
  connectedCount(chat: string): number { return this.recipients(chat).filter((u) => this.core.connected.value.has(u)).length; }

  /* ---------------- sending ---------------- */

  private dispatch(chat: string, msg: DmMessage) {
    const m = chat === SAVED ? { ...msg, status: "sent" } : msg;
    this.core.dms.put(chat, m);
    if (chat === SAVED) return;
    const rec = this.recipients(chat);
    const sent = rec.filter((u) => this.core.mesh.linkForUid(u)?.send(this.wire(chat, m)) === true).length;
    if (rec.length === 0) this.core.dms.edit(chat, m.id, (x) => ({ ...x, status: "sent" }));
    else if (sent === 0) this.core.dms.edit(chat, m.id, (x) => ({ ...x, status: "pending" }));
  }

  private base(text: string, reply: string): DmMessage {
    return newDm({ id: Ids.id("m_"), from: this.myUid, text, at: nowMs(), status: "pending", reply, authorName: this.myName });
  }

  send(chat: string, text: string, reply = "") {
    const t = text.trim().slice(0, 4000);
    if (!t) return;
    this.dispatch(chat, this.base(t, reply));
  }

  sendGifUrl(chat: string, url: string, reply = "") {
    const u = url.trim();
    if (!u.startsWith("http")) return;
    this.dispatch(chat, { ...this.base("", reply), kind: "gif", url: u, mime: "image/gif" });
  }

  /** Store a picked attachment in private storage and send it. */
  async sendFile(chat: string, f: { name: string; type: string; bytes: Uint8Array }, kind: string, reply = "", caption = "") {
    try {
      if (f.bytes.length > MAX_FILE) { this.core.toast("Files over 16 MB can't be sent"); return; }
      const id = Ids.id("m_");
      await Media.write(id, f.bytes);
      const mime = f.type || "application/octet-stream";
      const k = mime === "image/gif" ? "gif" : kind === "image" && mime.startsWith("image/") ? "image" : "file";
      this.dispatch(chat, newDm({ id, from: this.myUid, text: caption.trim(), at: nowMs(), status: "pending", kind: k, name: f.name, mime, size: f.bytes.length, reply, authorName: this.myName }));
      this.mediaTick.value++;
    } catch { this.core.toast("Couldn't read that file"); }
  }

  async pickAndSend(chat: string, kind: "image" | "file", reply = "") {
    const f = await native.pickFile(kind === "image" ? "image/*" : "");
    if (f) await this.sendFile(chat, f, kind, reply);
  }

  retry(chat: string, id: string) {
    const m = this.core.dms.find(chat, id);
    if (!m) return;
    this.core.dms.edit(chat, id, (x) => ({ ...x, status: "pending" }));
    this.push(chat, this.wire(chat, m));
  }

  async forward(toChat: string, m: DmMessage) {
    const id = Ids.id("m_");
    const orig = m.authorName || this.core.contactName(m.from);
    const copy: DmMessage = { ...m, id, from: this.myUid, at: nowMs(), status: "pending", reply: "", edited: false, ver: 0, reactions: {}, fwd: m.fwd || orig, authorName: this.myName };
    if ((m.kind === "image" || m.kind === "file" || m.kind === "gif") && !m.url) {
      const b = await Media.read(m.id);
      if (!b) { this.core.toast("Download it first"); return; }
      await Media.write(id, b);
    }
    this.dispatch(toChat, copy);
  }

  edit(chat: string, id: string, text: string) {
    const t = text.trim().slice(0, 4000);
    const m = this.core.dms.find(chat, id);
    if (!m || m.from !== this.myUid || m.deleted) return;
    if (!t && m.kind === "text") return;
    const n = { ...m, text: t, edited: true, ver: m.ver + 1 };
    this.core.dms.put(chat, n);
    this.push(chat, this.wire(chat, n));
  }

  delete(chat: string, id: string, everyone: boolean) {
    const m = this.core.dms.find(chat, id);
    if (!m) return;
    if (!everyone || chat === SAVED) { this.core.dms.removeMessage(chat, id); return; }
    const g = isGroup(chat) ? this.core.groups.get(gid(chat)) : undefined;
    if (m.from !== this.myUid && !(g && isAdmin(g, this.myUid))) return;
    const n: DmMessage = { ...m, text: "", kind: "text", url: "", name: "", size: 0, mime: "", deleted: true, ver: m.ver + 1, reactions: {}, reply: "" };
    this.core.dms.put(chat, n);
    Media.delete(id);
    this.push(chat, this.wire(chat, n));
  }

  react(chat: string, id: string, emoji: string) {
    const m = this.core.dms.find(chat, id);
    if (!m) return;
    const had = m.reactions[emoji]?.includes(this.myUid) === true;
    this.applyReaction(chat, id, emoji, this.myUid, !had);
    this.push(chat, JSON.stringify({ t: "mr", c: this.wireChat(chat), id, e: emoji, on: !had }));
  }

  private applyReaction(chat: string, id: string, emoji: string, uid: string, on: boolean) {
    this.core.dms.edit(chat, id, (m) => {
      if (m.deleted) return m;
      const r = { ...m.reactions };
      const cur = (r[emoji] ?? []).filter((x) => x !== uid);
      const next = on ? [...cur, uid] : cur;
      if (next.length === 0) delete r[emoji]; else r[emoji] = next;
      return { ...m, reactions: r };
    });
  }

  saveToSaved(m: DmMessage) { void this.forward(SAVED, m); this.core.toast("Saved"); }

  togglePin(chat: string, id: string) {
    const cur = this.core.dms.convs.value[chat]?.pinned ?? "";
    this.core.dms.setPinned(chat, cur === id ? "" : id);
  }

  /* ---------------- link events ---------------- */

  onLinkAuthed(link: PeerLink) {
    const uid = link.uid;
    for (const m of this.core.dms.pending(uid, this.myUid)) link.send(this.wire(uid, m));
    if (this.core.social.following.value.includes(uid)) link.send(JSON.stringify({ t: "fol", on: true }));
    const jr = link.joinRoomId;
    if (jr.startsWith("group:") && link.offerer) {
      const g = this.core.groups.get(jr.slice(6));
      if (g && canAdd(g, this.myUid) && !g.left && !(uid in g.members)) this.addMember(g.id, uid);
    }
    for (const g of Object.values(this.core.groups.groups.value)) {
      if (g.left || !(uid in g.members)) continue;
      this.sendDigest(link, g);
    }
  }

  private sendDigest(link: PeerLink, g: Group) {
    const conv = this.core.dms.convs.value[groupChatId(g)];
    const ids = [...(conv?.messages.slice(-150).map((m) => `${m.id}:${m.ver}`) ?? []), ...[...this.core.dms.removed].slice(-150).map((x) => `${x}:999999`)];
    link.send(JSON.stringify({ t: "gh", g: g.id, rev: g.rev, ids }));
  }

  sendFollow(uid: string, on: boolean) { this.core.mesh.linkForUid(uid)?.send(JSON.stringify({ t: "fol", on })); }

  onMessage(link: PeerLink, m: J) {
    const uid = link.uid;
    switch (str(m, "t")) {
      case "fol":
        this.core.social.setFollower(uid, bool(m, "on"));
        if (bool(m, "on")) this.core.toast(`${this.core.contactName(uid)} followed you`);
        break;
      case "dmack": {
        const chat = this.chatFor(link, str(m, "c"));
        const cur = this.core.dms.find(chat, str(m, "id"));
        if (!cur) return;
        if (bool(m, "ok")) { if (cur.status !== "recv") this.core.dms.setStatus(chat, cur.id, "sent"); }
        else if (!isGroup(chat)) this.core.dms.setStatus(chat, cur.id, "failed", str(m, "err", "Not delivered"));
        break;
      }
      case "m": this.onChatMessage(link, m); break;
      case "mr": {
        const chat = this.chatFor(link, str(m, "c"));
        if (isGroup(chat) && !(uid in (this.core.groups.get(gid(chat))?.members ?? {}))) return;
        this.applyReaction(chat, str(m, "id"), str(m, "e"), uid, bool(m, "on"));
        break;
      }
      case "mq": void this.onMediaRequest(link, m); break;
      case "mb": this.onMediaChunk(link, m); break;
      case "gh": this.onDigest(link, m); break;
      case "gr": {
        const g = this.core.groups.get(str(m, "g"));
        if (g && uid in g.members && !g.left) this.sendGroupInfo(link, g);
        break;
      }
      case "gi": this.onGroupInfo(link, m); break;
      case "gu": this.onGroupUpdate(link, m); break;
      case "gl": this.onGroupLeave(link, m); break;
      case "call": this.onCall(link, m); break;
      case "callr": {
        const o = this.outgoing;
        if (!o || o.roomId !== str(m, "cid") || !o.uids.includes(uid)) return;
        const why = str(m, "r") === "busy" ? "is busy" : "declined the call";
        this.core.toast(`${this.core.contactName(uid)} ${why}`);
        this.addSystem(o.chat, `${this.core.contactName(uid)} ${why}`);
        break;
      }
      case "callx": {
        const c = this.incomingCall.value;
        if (c && c.cid === str(m, "cid") && c.uid === uid) {
          this.clearRing();
          this.addSystem(c.chat, `Missed call from ${this.core.contactName(c.uid)}`, c.uid);
        }
        break;
      }
    }
  }

  private refuse(link: PeerLink): string | null {
    const uid = link.uid;
    const st = this.core.settings.state.value;
    const me = this.myName || "This person";
    if (this.core.social.blocked.value.includes(uid)) return "Not delivered";
    if (st.dmPolicy === "nobody") return `${me} isn't accepting messages`;
    if (st.dmPolicy === "following" && !this.core.social.following.value.includes(uid)) return `${me} only accepts messages from people they follow`;
    return null;
  }

  private onChatMessage(link: PeerLink, j: J) {
    const uid = link.uid;
    const chat = this.chatFor(link, str(j, "c"));
    const backfill = bool(j, "bf");
    let msg = this.sanitize({ ...dmFrom(j), status: "recv" });
    if (!msg) return;
    if (this.core.dms.removed.has(msg.id)) return;
    const group = isGroup(chat);
    const g = group ? this.core.groups.get(gid(chat)) : undefined;
    const ack = (ok: boolean, err = "") => { link.send(JSON.stringify({ t: "dmack", id: msg!.id, ok, err, c: group ? gid(chat) : "dm" })); };
    if (group) {
      if (!g || g.left || !(uid in g.members) || !(msg.from in g.members)) { ack(false, "Not a member"); return; }
      if (msg.from !== uid && !backfill) return;
      if (this.core.social.blocked.value.includes(msg.from)) { ack(true); return; }
    } else if (msg.from !== uid) return;
    const existing = this.core.dms.find(chat, msg.id);
    if (existing) {
      const allowed = msg.from === existing.from && (existing.from === uid || (msg.deleted && !!g && isAdmin(g, uid)));
      if (msg.ver > existing.ver && allowed) {
        if (msg.deleted) Media.delete(msg.id);
        this.core.dms.put(chat, { ...msg, status: existing.status, reactions: msg.deleted ? {} : existing.reactions });
      }
      ack(true);
      return;
    }
    if (msg.from !== uid && !group) return;
    if (!group) {
      const refusal = this.refuse(link);
      if (refusal) { ack(false, refusal); return; }
    }
    const mine = msg.from === this.myUid;
    if (mine) msg = { ...msg, status: "sent" };
    const muted = this.core.dms.convs.value[chat]?.muted === true;
    const notify = !mine && !backfill && this.openChat.value !== chat && !muted;
    this.core.dms.put(chat, msg, notify);
    ack(true);
    if (notify) {
      this.core.sfx.play("message");
      const who = group ? `${g?.name ?? "Group"} · ${msg.authorName || this.core.contactName(msg.from)}` : this.core.contactName(uid);
      this.core.toast(`${who}: ${this.preview(msg)}`);
    }
    if ((msg.kind === "image" || msg.kind === "gif") && !msg.url && msg.size <= AUTO_MEDIA) this.requestMedia(link, msg.id);
  }

  /** Remote messages are untrusted: bound every field before they touch the store or the disk. */
  private sanitize(m: DmMessage): DmMessage | null {
    if (!ID_RX.test(m.id)) return null;
    const now = nowMs();
    const rx: Record<string, string[]> = {};
    let n = 0;
    for (const [e, u] of Object.entries(m.reactions)) { if (n++ >= 24) break; rx[e.slice(0, 16)] = u.slice(0, 60).map((x) => x.slice(0, 40)); }
    return {
      ...m, from: m.from.slice(0, 40), text: m.text.slice(0, 4000), name: m.name.slice(0, 120).replace(/[/\\]/g, "_"),
      mime: m.mime.slice(0, 100), url: m.url.slice(0, 2000), authorName: m.authorName.slice(0, 60), fwd: m.fwd.slice(0, 60),
      reply: m.reply.slice(0, 40), size: Math.min(MAX_FILE, Math.max(0, m.size)), ver: Math.min(100000, Math.max(0, m.ver)),
      at: m.at <= 0 || m.at > now + 300_000 ? now : m.at, reactions: rx,
      kind: ["text", "image", "gif", "file", "call"].includes(m.kind) ? m.kind : "text"
    };
  }

  preview(m: DmMessage): string {
    if (m.deleted) return "Message deleted";
    if (m.kind === "image") return "📷 Photo" + (m.text ? ` · ${m.text.slice(0, 40)}` : "");
    if (m.kind === "gif") return "GIF";
    if (m.kind === "file") return `📎 ${m.name}`;
    return m.text.replace(/\n/g, " ").slice(0, 80);
  }

  /* ---------------- attachments ---------------- */

  download(chat: string, m: DmMessage) {
    const from = [...new Set(isGroup(chat) ? [m.from, ...this.recipients(chat)] : [chat])];
    let link: PeerLink | undefined;
    for (const u of from) { link = this.core.mesh.linkForUid(u); if (link) break; }
    if (!link) { this.core.toast("Not connected to anyone who has it"); return; }
    this.requestMedia(link, m.id);
  }

  private failDownload(id: string) { this.downloads.delete(id); this.setProgress(id, null); }

  private requestMedia(link: PeerLink, id: string) {
    if (Media.has(id) || id in this.mediaProgress.value || this.downloads.has(id)) return;
    let size = 0;
    for (const c of Object.values(this.core.dms.convs.value)) { const f = c.messages.find((x) => x.id === id); if (f) { size = f.size; break; } }
    this.downloads.set(id, { sid: link.remoteSid, expect: size, next: 0, n: -1, bytes: 0, lastAt: nowMs(), parts: [] });
    this.setProgress(id, 0.001);
    link.send(JSON.stringify({ t: "mq", id }));
    const watch = () => later(15_000, () => {
      const d = this.downloads.get(id);
      if (!d) return;
      if (nowMs() - d.lastAt > 14_000) { this.failDownload(id); this.core.toast("Download interrupted. Click to retry."); } else watch();
    });
    watch();
  }

  private setProgress(id: string, p: number | null) {
    const m = { ...this.mediaProgress.value };
    if (p == null) delete m[id]; else m[id] = p;
    this.mediaProgress.value = m;
  }

  private canAccess(uid: string, id: string): boolean {
    for (const [chat, conv] of Object.entries(this.core.dms.convs.value)) {
      if (chat === SAVED) continue;
      if (!conv.messages.some((m) => m.id === id)) continue;
      if (chat === uid && conv.messages.some((m) => m.id === id && m.from === this.myUid)) return true;
      if (isGroup(chat) && uid in (this.core.groups.get(gid(chat))?.members ?? {})) return true;
    }
    return false;
  }

  private async onMediaRequest(link: PeerLink, m: J) {
    const id = str(m, "id");
    if (!ID_RX.test(id)) return;
    const bytes = Media.has(id) && this.canAccess(link.uid, id) ? await Media.read(id) : null;
    if (!bytes || bytes.length > MAX_FILE) { link.send(JSON.stringify({ t: "mb", id, i: 0, n: 0 })); return; }
    const chunk = 24_000;
    const n = Math.max(1, Math.ceil(bytes.length / chunk));
    for (let i = 0; i < n; i++) {
      let waited = 0;
      while (link.buffered() > 256_000 && waited < 1500) { await new Promise((r) => setTimeout(r, 8)); waited++; }
      if (waited >= 1500) return;      // peer is not draining: give up, they can retry
      const d = b64encode(bytes.subarray(i * chunk, Math.min(bytes.length, (i + 1) * chunk)));
      if (!link.send(JSON.stringify({ t: "mb", id, i, n, d }))) return;
    }
  }

  private onMediaChunk(link: PeerLink, m: J) {
    const id = str(m, "id");
    const dl = this.downloads.get(id);
    if (!dl || dl.sid !== link.remoteSid) return;
    const n = int(m, "n"), i = int(m, "i");
    if (n <= 0) { this.failDownload(id); this.core.toast("That file isn't available from this person"); return; }
    if (dl.n < 0) {
      if (i !== 0 || n > MAX_FILE / 20_000 + 2) { this.failDownload(id); return; }
      dl.n = n;
    }
    if (n !== dl.n || i !== dl.next) { this.failDownload(id); return; }
    const d = str(m, "d");
    if (d.length > 40_000) { this.failDownload(id); return; }
    dl.next = i + 1;
    dl.lastAt = nowMs();
    let bytes: Uint8Array;
    try { bytes = b64decode(d); } catch { this.failDownload(id); return; }
    dl.bytes += bytes.length;
    if (dl.bytes > MAX_FILE || (dl.expect > 0 && dl.bytes > dl.expect + 1024)) { this.failDownload(id); return; }
    dl.parts.push(bytes);
    if (i === n - 1) {
      const all = new Uint8Array(dl.bytes);
      let o = 0;
      for (const p of dl.parts) { all.set(p, o); o += p.length; }
      this.downloads.delete(id);
      void Media.write(id, all).then(() => { this.setProgress(id, null); this.mediaTick.value++; });
    } else if (i % 4 === 0) this.setProgress(id, (i + 1) / n);
  }

  /* ---------------- groups ---------------- */

  private memberOf(uid: string, role = "member"): GroupMember | null {
    if (uid === this.myUid) {
      const p = this.core.profile.profile.value;
      return p ? { uid, name: p.displayName, username: p.username, avatar: p.avatar, role, addedAt: nowMs() } : null;
    }
    const c = this.core.social.contact(uid);
    return c ? { uid, name: c.displayName, username: c.username, avatar: c.avatar, role, addedAt: nowMs() } : null;
  }

  createGroup(name: string, about: string, uids: string[], emoji: string, hue: number): string {
    const id = Ids.rand(10);
    const members: Record<string, GroupMember> = {};
    const me = this.memberOf(this.myUid, "owner");
    if (me) members[this.myUid] = me;
    for (const u of uids) { const m = this.memberOf(u); if (m) members[u] = m; }
    const g: Group = { id, name: name.trim().slice(0, 60), about: about.trim().slice(0, 200), ownerUid: this.myUid, members, createdAt: nowMs(), rev: 1, membersCanAdd: true, emoji, hue, left: false };
    this.core.groups.put(g);
    this.core.dms.ensure(groupChatId(g));
    this.addSystem(groupChatId(g), "You created the group");
    for (const u of uids) { const l = this.core.mesh.linkForUid(u); if (l) this.sendGroupInfo(l, g); }
    return groupChatId(g);
  }

  private sendGroupInfo(link: PeerLink, g: Group) {
    const conv = this.core.dms.convs.value[groupChatId(g)];
    const recent = (conv?.messages.slice(-60).filter((m) => m.from) ?? []).map((m) => JSON.parse(this.wire(groupChatId(g), m, true)));
    link.send(JSON.stringify({ t: "gi", g: groupJson(g), msgs: recent }));
  }

  private onDigest(link: PeerLink, m: J) {
    const gidv = str(m, "g");
    const g = this.core.groups.get(gidv);
    if (this.core.dms.removed.has("g:" + gidv)) return;
    if (!g || g.left) { link.send(JSON.stringify({ t: "gr", g: gidv })); return; }
    if (!(link.uid in g.members)) return;
    const have = new Map<string, number>();
    const a = arr(m, "ids");
    if (a) for (let k = 0; k < Math.min(a.length, 400); k++) { const p = String(a[k]).split(":"); have.set(p[0], parseInt(p[1] ?? "0", 10) || 0); }
    if (lng(m, "rev") < g.rev) link.send(JSON.stringify({ t: "gu", g: groupJson(g) }));
    const conv = this.core.dms.convs.value[groupChatId(g)];
    const missing = (conv?.messages.slice(-150).filter((x) => (have.get(x.id) ?? -1) < x.ver && x.from) ?? []).slice(-100);
    for (const x of missing) link.send(this.wire(groupChatId(g), x, true));
  }

  private onGroupInfo(link: PeerLink, m: J) {
    const gj = obj(m, "g");
    if (!gj) return;
    const g = groupFrom(gj);
    if (!g.id || g.id.length > 40 || Object.keys(g.members).length > 256 || g.name.length > 60 || g.about.length > 200) return;
    if (!(this.myUid in g.members) || !(link.uid in g.members)) return;
    if (this.core.social.blocked.value.includes(link.uid)) return;
    const cur = this.core.groups.get(g.id);
    if (this.core.dms.removed.has("g:" + g.id) || cur?.left) return;
    if (cur && !(link.uid in cur.members)) return;
    if (!cur && this.refuse(link)) return;
    if (!(cur && cur.rev >= g.rev && !cur.left)) {
      if (!cur && this.core.settings.state.value.dmPolicy === "nobody") return;
      this.core.groups.put({ ...g, left: false });
      this.core.dms.ensure(groupChatId(g));
      if (!cur) {
        this.addSystem(groupChatId(g), `You joined “${g.name}”`);
        this.core.sfx.play("join");
        this.core.toast(`Added to group: ${g.name}`);
      }
    }
    for (const x of objects(arr(m, "msgs"))) this.onChatMessage(link, x);
  }

  private onGroupUpdate(link: PeerLink, m: J) {
    const gj = obj(m, "g");
    if (!gj) return;
    const g = groupFrom(gj);
    const cur = this.core.groups.get(g.id);
    if (!cur) return;
    if (cur.left || this.core.dms.removed.has("g:" + g.id)) return;
    if (!(link.uid in cur.members) || g.rev <= cur.rev || g.rev > cur.rev + 50) return;
    if (!this.validGroupChange(cur, g, link.uid)) return;
    if (!(this.myUid in g.members)) {
      this.core.groups.put({ ...cur, left: true, rev: g.rev });
      this.addSystem(groupChatId(cur), "You were removed from this group");
      return;
    }
    const added = Object.keys(g.members).filter((u) => !(u in cur.members));
    const gone = Object.keys(cur.members).filter((u) => !(u in g.members));
    this.core.groups.put({ ...g, left: false });
    for (const u of added) this.addSystem(groupChatId(g), `${g.members[u]?.name ?? "Someone"} joined`);
    for (const u of gone) this.addSystem(groupChatId(g), `${cur.members[u]?.name ?? "Someone"} left`);
    if (cur.name !== g.name) this.addSystem(groupChatId(g), `Group renamed to “${g.name}”`);
  }

  /** A group update is only believed if the sender was allowed to make exactly that change. */
  private validGroupChange(cur: Group, g: Group, sender: string): boolean {
    if (g.ownerUid !== cur.ownerUid || Object.keys(g.members).length > 256) return false;
    const admin = isAdmin(cur, sender);
    const isOwner = cur.ownerUid === sender;
    for (const [u, mem] of Object.entries(g.members)) {
      const old = cur.members[u];
      if (!old) { if (!canAdd(cur, sender) || mem.role !== "member") return false; }
      else if (old.role !== mem.role && !isOwner) return false;
    }
    for (const u of Object.keys(cur.members)) if (!(u in g.members) && (!admin || u === cur.ownerUid)) return false;
    if (!admin && (g.name !== cur.name || g.about !== cur.about || g.emoji !== cur.emoji || g.hue !== cur.hue || g.membersCanAdd !== cur.membersCanAdd)) return false;
    if (g.name.length > 60 || g.about.length > 200) return false;
    return true;
  }

  private onGroupLeave(link: PeerLink, m: J) {
    const g = this.core.groups.get(str(m, "g"));
    if (!g) return;
    const who = g.members[link.uid];
    if (!who) return;
    const members = { ...g.members };
    delete members[link.uid];
    let owner = g.ownerUid;
    if (owner === link.uid) {
      const vals = Object.values(members);
      const next = vals.find((x) => x.role === "admin") ?? vals.sort((a, b) => a.addedAt - b.addedAt)[0];
      if (next) { owner = next.uid; members[next.uid] = { ...next, role: "owner" }; }
    }
    this.core.groups.put({ ...g, members, rev: g.rev + 1, ownerUid: owner });
    this.addSystem(groupChatId(g), `${who.name} left`);
  }

  private broadcastGroup(g: Group) {
    const s = JSON.stringify({ t: "gu", g: groupJson(g) });
    for (const u of Object.keys(g.members)) if (u !== this.myUid) this.core.mesh.linkForUid(u)?.send(s);
  }

  addMember(gidv: string, uid: string): boolean {
    const g = this.core.groups.get(gidv);
    if (!g || !canAdd(g, this.myUid) || uid in g.members) return false;
    const mem = this.memberOf(uid);
    if (!mem) return false;
    const ng = { ...g, members: { ...g.members, [uid]: mem }, rev: g.rev + 1 };
    this.core.groups.put(ng);
    this.addSystem(groupChatId(ng), `${mem.name} was added`);
    this.broadcastGroup(ng);
    const l = this.core.mesh.linkForUid(uid);
    if (l) this.sendGroupInfo(l, ng);
    return true;
  }

  removeMember(gidv: string, uid: string) {
    const g = this.core.groups.get(gidv);
    if (!g || !isAdmin(g, this.myUid) || uid === g.ownerUid || uid === this.myUid) return;
    const gone = g.members[uid];
    if (!gone) return;
    const members = { ...g.members };
    delete members[uid];
    const ng = { ...g, members, rev: g.rev + 1 };
    this.core.groups.put(ng);
    this.addSystem(groupChatId(ng), `${gone.name} was removed`);
    this.broadcastGroup(ng);
    this.core.mesh.linkForUid(uid)?.send(JSON.stringify({ t: "gu", g: groupJson(ng) }));
  }

  setAdmin(gidv: string, uid: string, on: boolean) {
    const g = this.core.groups.get(gidv);
    if (!g || g.ownerUid !== this.myUid) return;
    const m = g.members[uid];
    if (!m || m.role === "owner") return;
    const ng = { ...g, members: { ...g.members, [uid]: { ...m, role: on ? "admin" : "member" } }, rev: g.rev + 1 };
    this.core.groups.put(ng);
    this.broadcastGroup(ng);
  }

  updateGroup(gidv: string, name: string, about: string, membersCanAdd: boolean, emoji: string, hue: number) {
    const g = this.core.groups.get(gidv);
    if (!g || !isAdmin(g, this.myUid)) return;
    const ng = { ...g, name: name.trim().slice(0, 60) || g.name, about: about.trim().slice(0, 200), membersCanAdd, emoji, hue, rev: g.rev + 1 };
    this.core.groups.put(ng);
    if (ng.name !== g.name) this.addSystem(groupChatId(ng), `You renamed the group to “${ng.name}”`);
    this.broadcastGroup(ng);
  }

  leaveGroup(gidv: string) {
    const g = this.core.groups.get(gidv);
    if (!g) return;
    const s = JSON.stringify({ t: "gl", g: g.id, rev: g.rev + 1 });
    for (const u of Object.keys(g.members)) if (u !== this.myUid) this.core.mesh.linkForUid(u)?.send(s);
    const members = { ...g.members };
    delete members[this.myUid];
    this.core.groups.put({ ...g, left: true, members, rev: g.rev + 1 });
    this.addSystem(groupChatId(g), "You left the group");
  }

  deleteChat(chat: string) {
    if (isGroup(chat)) {
      const g = this.core.groups.get(gid(chat));
      if (g && !g.left) this.leaveGroup(g.id);
      this.core.groups.remove(gid(chat));
      this.core.dms.markRemoved("g:" + gid(chat));
    }
    this.core.dms.deleteChat(chat);
  }

  private addSystem(chat: string, text: string, from = "") {
    this.core.dms.put(chat, newDm({ id: Ids.id("s_"), from, text, at: nowMs(), status: "sent", kind: "call" }));
  }

  /* ---------------- calls: a private voice room that the other side is rung into ---------------- */

  startCall(chat: string): boolean {
    if (this.core.session.phase.value !== "idle") { this.core.toast("Leave your current room first"); return false; }
    const rec = this.recipients(chat).filter((u) => this.core.connected.value.has(u));
    if (rec.length === 0) { this.core.toast("Nobody from this chat is connected right now. Connect over Wi-Fi or with a code first."); return false; }
    this.core.requestMic();
    const title = isGroup(chat) ? `Call · ${this.titleOf(chat)}` : `Call with ${this.titleOf(chat)}`;
    if (!this.core.session.createRoom(title, "", [], true, 24)) { this.core.toast("Couldn't start the call"); return false; }
    const rid = this.core.session.room?.meta.id;
    if (!rid) return false;
    const s = JSON.stringify({ t: "call", cid: rid, c: this.wireChat(chat), title });
    this.outgoing = { roomId: rid, chat, uids: rec };
    for (const u of rec) this.core.mesh.linkForUid(u)?.send(s);
    this.addSystem(chat, "You started a call");
    return true;
  }

  onRoomClosed(roomId: string | undefined) {
    const o = this.outgoing;
    if (!o || !roomId || o.roomId !== roomId) return;
    this.outgoing = null;
    const s = JSON.stringify({ t: "callx", cid: roomId });
    for (const u of o.uids) this.core.mesh.linkForUid(u)?.send(s);
  }

  private onCall(link: PeerLink, m: J) {
    const uid = link.uid;
    const chat = this.chatFor(link, str(m, "c"));
    if (this.core.social.blocked.value.includes(uid)) return;
    if (isGroup(chat) && !(uid in (this.core.groups.get(gid(chat))?.members ?? {}))) return;
    if (!isGroup(chat) && this.refuse(link)) return;
    if (this.core.session.phase.value !== "idle" || this.incomingCall.value) {
      link.send(JSON.stringify({ t: "callr", cid: str(m, "cid"), r: "busy" }));
      return;
    }
    this.incomingCall.value = { cid: str(m, "cid"), uid, chat, title: str(m, "title"), group: isGroup(chat) };
    this.ring();
  }

  private ring() {
    cancelLater(this.ringTimer);
    const c = this.incomingCall.value;
    if (!c) return;
    this.core.sfx.play("join");
    let rounds = 0;
    const tick = () => {
      if (!this.incomingCall.value) return;
      rounds++;
      if (rounds > 18) {
        this.addSystem(c.chat, `Missed call from ${this.core.contactName(c.uid)}`, c.uid);
        this.clearRing();
        return;
      }
      this.core.sfx.play("join");
      this.ringTimer = later(2400, tick);
    };
    this.ringTimer = later(2400, tick);
  }

  private clearRing() {
    cancelLater(this.ringTimer);
    this.ringTimer = null;
    this.incomingCall.value = null;
  }

  acceptCall() {
    const c = this.incomingCall.value;
    if (!c) return;
    const link = this.core.mesh.linkForUid(c.uid);
    this.clearRing();
    if (!link) { this.core.toast("The caller is no longer connected"); return; }
    if (this.core.session.phase.value !== "idle") return;
    this.core.requestMic();
    link.joinRoomId = c.cid;
    this.core.session.beginJoin(link);
  }

  declineCall() {
    const c = this.incomingCall.value;
    if (!c) return;
    this.core.mesh.linkForUid(c.uid)?.send(JSON.stringify({ t: "callr", cid: c.cid, r: "decline" }));
    this.clearRing();
  }
}
