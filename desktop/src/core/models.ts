import { J, arr, bool, clamp, dbl, flt, int, lng, nowMs, obj, objects, str, strings } from "./util";
import { ChessGame, ChessState } from "../chess/game";

/* ===========================================================================
 * Plain data models. Everything round-trips through JSON because the same
 * structures travel over WebRTC data channels and are persisted to disk.
 * Field names are identical to the Android app (core/Models.kt).
 * ========================================================================= */

export interface Avatar { emoji: string; hue: number }
export const defaultAvatar = (): Avatar => ({ emoji: "🎧", hue: 265 });
export const avatarJson = (a: Avatar): J => ({ e: a.emoji, h: a.hue });
export const avatarFrom = (j: J | null): Avatar => (j ? { emoji: str(j, "e", "🎧"), hue: int(j, "h", 265) } : defaultAvatar());

export const AVATAR_EMOJIS = ["🎧", "🎙️", "🎹", "🎸", "🥁", "🎷", "📻", "🎤", "🌙", "⚡", "🔥", "🌊", "🪐", "🦊", "🐳", "🌵"];
export const REACTIONS = ["👏", "🔥", "❤️", "😂", "🎉", "👍", "💯", "🤯"];

export const canSpeak = (_role: string): boolean => true;

/* ---------------- room ---------------- */

export interface RoomMeta {
  id: string; title: string; topic: string; tags: string[]; ownerUid: string; ownerName: string;
  unlisted: boolean; locked: boolean; maxSpeakers: number; createdAt: number;
  /** uid -> banned until (epoch ms); 0 means permanent */
  bans: Record<string, number>;
}

export const newMeta = (p: Partial<RoomMeta> & { id: string; title: string; ownerUid: string }): RoomMeta => ({
  topic: "", tags: [], ownerName: "", unlisted: false, locked: false, maxSpeakers: 8, createdAt: nowMs(), bans: {}, ...p
});

export function isBanned(m: RoomMeta, uid: string): boolean {
  const until = m.bans[uid];
  if (until === undefined) return false;
  return until === 0 || until > nowMs();
}

export const metaJson = (m: RoomMeta): J => ({
  id: m.id, title: m.title, topic: m.topic, tags: m.tags, owner: m.ownerUid, ownerName: m.ownerName,
  unlisted: m.unlisted, locked: m.locked, maxSpeakers: m.maxSpeakers, createdAt: m.createdAt, bans: m.bans
});

export function metaFrom(j: J): RoomMeta {
  const bans: Record<string, number> = {};
  const bj = obj(j, "bans");
  if (bj) for (const k of Object.keys(bj)) bans[k] = lng(bj, k, 0);
  return {
    id: str(j, "id"), title: str(j, "title", "Untitled room"), topic: str(j, "topic"), tags: strings(arr(j, "tags")),
    ownerUid: str(j, "owner"), ownerName: str(j, "ownerName"), unlisted: bool(j, "unlisted"), locked: bool(j, "locked"),
    maxSpeakers: int(j, "maxSpeakers", 8), createdAt: lng(j, "createdAt", nowMs()), bans
  };
}

export interface Sharing { res: number; fps: number; at: number }
export const sharingJson = (s: Sharing): J => ({ res: s.res, fps: s.fps, at: s.at });
export const sharingFrom = (j: J | null): Sharing | null => (j ? { res: int(j, "res", 720), fps: int(j, "fps", 30), at: lng(j, "at") } : null);

export interface Participant {
  sid: string; uid: string; username: string; displayName: string; avatar: Avatar; role: string;
  muted: boolean; hand: boolean; sharing: Sharing | null; browsing: boolean; joinedAt: number; via: string;
}

export const participantJson = (p: Participant): J => {
  const j: J = {
    sid: p.sid, uid: p.uid, username: p.username, displayName: p.displayName, avatar: avatarJson(p.avatar),
    role: p.role, muted: p.muted, hand: p.hand, browsing: p.browsing, joinedAt: p.joinedAt, via: p.via
  };
  if (p.sharing) j.sharing = sharingJson(p.sharing);
  return j;
};

export const participantFrom = (j: J): Participant => ({
  sid: str(j, "sid"), uid: str(j, "uid"), username: str(j, "username"), displayName: str(j, "displayName"),
  avatar: avatarFrom(obj(j, "avatar")), role: str(j, "role", "listener"), muted: bool(j, "muted", true),
  hand: bool(j, "hand"), sharing: sharingFrom(obj(j, "sharing")), browsing: bool(j, "browsing"),
  joinedAt: lng(j, "joinedAt", nowMs()), via: str(j, "via")
});

export interface ChatMsg {
  id: string; uid: string; username: string; displayName: string; avatar: Avatar; text: string; at: number;
  deleted: boolean; reactions: Record<string, string[]>;
}

export function reactionsFrom(j: J | null): Record<string, string[]> {
  const out: Record<string, string[]> = {};
  if (j) for (const k of Object.keys(j)) out[k] = strings(Array.isArray(j[k]) ? j[k] : []);
  return out;
}

export const chatJson = (m: ChatMsg): J => ({
  id: m.id, uid: m.uid, username: m.username, displayName: m.displayName, avatar: avatarJson(m.avatar),
  text: m.text, at: m.at, deleted: m.deleted, reactions: m.reactions
});

export const chatFrom = (j: J): ChatMsg => ({
  id: str(j, "id"), uid: str(j, "uid"), username: str(j, "username"), displayName: str(j, "displayName"),
  avatar: avatarFrom(obj(j, "avatar")), text: str(j, "text"), at: lng(j, "at"), deleted: bool(j, "deleted"),
  reactions: reactionsFrom(obj(j, "reactions"))
});

/* ---------------- blackboard ---------------- */

export const BOARD_TYPES = new Set(["stroke", "line", "arrow", "rect", "ellipse", "text", "image"]);

export const rgbToHex = (c: number): string => "#" + (c & 0xffffff).toString(16).padStart(6, "0");
export function hexToRgb(s: string): number {
  const v = parseInt(String(s).replace(/^#/, ""), 16);
  return Number.isFinite(v) ? v & 0xffffff : 0xededef;
}

const sane = (v: number): number => (Number.isFinite(v) ? clamp(v, -1_000_000, 1_000_000) : 0);
const round1 = (v: number): number => Math.round(v * 10) / 10;

export class BoardObj {
  points: number[] | null = null;
  x = 0; y = 0; w = 0; h = 0; x1 = 0; y1 = 0; x2 = 0; y2 = 0;
  text = ""; size = 18; src: string | null = null;
  color = 0xededef; width = 3; opacity = 1; dash = false; rot = 0; z = 0; locked = false;
  group: string | null = null; by = "";
  constructor(public id: string, public type: string) {}

  deepCopy(): BoardObj {
    const o = new BoardObj(this.id, this.type);
    Object.assign(o, this);
    o.points = this.points ? [...this.points] : null;
    return o;
  }

  toJson(withSrc = true): J {
    const j: J = { id: this.id, type: this.type };
    switch (this.type) {
      case "stroke": j.points = (this.points ?? []).map(round1); break;
      case "line": case "arrow": j.x1 = round1(this.x1); j.y1 = round1(this.y1); j.x2 = round1(this.x2); j.y2 = round1(this.y2); break;
      case "text": j.x = round1(this.x); j.y = round1(this.y); j.text = this.text; j.size = this.size; break;
      default: j.x = round1(this.x); j.y = round1(this.y); j.w = round1(this.w); j.h = round1(this.h);
    }
    if (this.type === "image" && withSrc) j.src = this.src ?? "";
    j.color = rgbToHex(this.color); j.width = this.width; j.opacity = this.opacity;
    j.dash = this.dash ? 1 : 0; j.rot = this.rot; j.z = this.z; j.locked = this.locked ? 1 : 0; j.by = this.by;
    if (this.group != null) j.group = this.group;
    return j;
  }

  applyPatch(p: J): void {
    const has = (k: string) => k in p;
    if (has("points") && p.points != null && Array.isArray(p.points)) this.points = p.points.map((v: any) => sane(Number(v)));
    if (has("x")) this.x = sane(flt(p, "x"));
    if (has("y")) this.y = sane(flt(p, "y"));
    if (has("w")) this.w = sane(flt(p, "w"));
    if (has("h")) this.h = sane(flt(p, "h"));
    if (has("x1")) this.x1 = sane(flt(p, "x1"));
    if (has("y1")) this.y1 = sane(flt(p, "y1"));
    if (has("x2")) this.x2 = sane(flt(p, "x2"));
    if (has("y2")) this.y2 = sane(flt(p, "y2"));
    if (has("text")) this.text = str(p, "text").slice(0, 2000);
    if (has("size")) this.size = clamp(sane(flt(p, "size", 18)), 4, 600);
    if (has("color")) this.color = hexToRgb(str(p, "color"));
    if (has("width")) this.width = clamp(sane(flt(p, "width", 3)), 0.5, 200);
    if (has("opacity")) this.opacity = clamp(sane(flt(p, "opacity", 1)), 0, 1);
    if (has("dash")) this.dash = int(p, "dash") !== 0;
    if (has("rot")) this.rot = sane(flt(p, "rot"));
    if (has("z")) this.z = int(p, "z");
    if (has("locked")) this.locked = int(p, "locked") !== 0;
    if (has("group")) this.group = p.group == null ? null : str(p, "group");
  }

  static from(j: J): BoardObj | null {
    const type = str(j, "type");
    if (!BOARD_TYPES.has(type)) return null;
    const o = new BoardObj(str(j, "id"), type);
    if ("src" in j) o.src = str(j, "src");
    if ("by" in j) o.by = str(j, "by");
    o.applyPatch(j);
    return o;
  }
}

export class BoardState {
  objects = new Map<string, BoardObj>();
  z = 1;
  version = 0;
  touch() { this.version++; }
  clear() { this.objects.clear(); this.z = 1; this.touch(); }
  add(o: BoardObj) { this.objects.set(o.id, o); if (o.z >= this.z) this.z = o.z + 1; this.touch(); }
  remove(ids: Iterable<string>) { for (const id of ids) this.objects.delete(id); this.touch(); }
  patch(id: string, p: J) {
    const o = this.objects.get(id);
    if (!o) return;
    o.applyPatch(p);
    if (o.z >= this.z) this.z = o.z + 1;
    this.touch();
  }
  bytes(): number {
    let n = 0;
    for (const o of this.objects.values()) n += o.type === "image" ? (o.src?.length ?? 0) + 200 : o.type === "stroke" ? (o.points?.length ?? 0) * 7 + 200 : 260;
    return n;
  }
  sortedByZ(): BoardObj[] { return [...this.objects.values()].sort((a, b) => a.z - b.z); }
  toJson(): J[] { return [...this.objects.values()].map((o) => o.toJson()); }
  load(a: any[] | null) {
    this.objects.clear();
    this.z = 1;
    if (a) for (const j of objects(a)) {
      const o = BoardObj.from(j);
      if (!o) continue;
      this.objects.set(o.id, o);
      if (o.z >= this.z) this.z = o.z + 1;
    }
    this.touch();
  }
}

/* ---------------- Watch Together ---------------- */

export interface WatchVideo { id: string; title: string; author: string; duration: number; qid: string; by: string }
export const mkVideo = (id: string, title: string, author = "", duration = 0, qid = "", by = ""): WatchVideo => ({ id, title, author, duration, qid, by });
export const videoJson = (v: WatchVideo): J => ({ id: v.id, title: v.title, author: v.author, duration: v.duration, qid: v.qid, by: v.by });
export const videoFrom = (j: J): WatchVideo => ({
  id: str(j, "id"), title: str(j, "title", "YouTube video"), author: str(j, "author"), duration: int(j, "duration"), qid: str(j, "qid"), by: str(j, "by")
});

export interface WatchState {
  video: WatchVideo | null; playing: boolean; position: number; anchorMs: number; rate: number;
  controllerName: string; perm: string; mods: string[]; queue: WatchVideo[];
}
export const newWatch = (p: Partial<WatchState> = {}): WatchState => ({
  video: null, playing: false, position: 0, anchorMs: nowMs(), rate: 1, controllerName: "", perm: "host", mods: [], queue: [], ...p
});

export function expectedPosition(w: WatchState): number {
  let p = w.position;
  if (w.playing && w.video) p += ((nowMs() - w.anchorMs) / 1000) * w.rate;
  const d = w.video?.duration ?? 0;
  if (d > 0) p = Math.min(p, d);
  return Math.max(0, p);
}

export const watchSnapshot = (w: WatchState): J => ({
  video: w.video ? videoJson(w.video) : null, playing: w.playing, position: expectedPosition(w), rate: w.rate,
  controller: w.controllerName, perm: w.perm, mods: w.mods, queue: w.queue.map(videoJson)
});

export const watchFromSnapshot = (j: J): WatchState => ({
  video: obj(j, "video") ? videoFrom(obj(j, "video")) : null, playing: bool(j, "playing"), position: dbl(j, "position"),
  anchorMs: nowMs(), rate: dbl(j, "rate", 1), controllerName: str(j, "controller"), perm: str(j, "perm", "host"),
  mods: strings(arr(j, "mods")), queue: objects(arr(j, "queue")).map(videoFrom)
});

/* ---------------- shared browser ---------------- */

export interface NavRequest { uid: string; name: string; at: number }
export interface BrowseState {
  url: string | null; title: string; host: string; nonce: number; navigatorUid: string; navigatorName: string;
  perm: string; mods: string[]; history: string[]; idx: number; requests: NavRequest[];
}
export const newBrowse = (p: Partial<BrowseState> = {}): BrowseState => ({
  url: null, title: "", host: "", nonce: 0, navigatorUid: "", navigatorName: "", perm: "host", mods: [], history: [], idx: -1, requests: [], ...p
});
export const browseJson = (b: BrowseState): J => ({
  url: b.url, title: b.title, host: b.host, nonce: b.nonce, navUid: b.navigatorUid, navName: b.navigatorName, perm: b.perm,
  mods: b.mods, history: b.history, idx: b.idx, requests: b.requests.map((r) => ({ uid: r.uid, name: r.name, at: r.at }))
});
export const browseFrom = (j: J): BrowseState => ({
  url: j.url == null ? null : str(j, "url"), title: str(j, "title"), host: str(j, "host"), nonce: int(j, "nonce"),
  navigatorUid: str(j, "navUid"), navigatorName: str(j, "navName"), perm: str(j, "perm", "host"), mods: strings(arr(j, "mods")),
  history: strings(arr(j, "history")), idx: int(j, "idx", -1),
  requests: objects(arr(j, "requests")).map((r) => ({ uid: str(r, "uid"), name: str(r, "name"), at: lng(r, "at") }))
});

/* ---------------- the replicated room ---------------- */

export class RoomState {
  participants = new Map<string, Participant>();
  chat: ChatMsg[] = [];
  board = new BoardState();
  watch: WatchState = newWatch();
  browse: BrowseState = newBrowse();
  chess = new ChessState();
  constructor(public meta: RoomMeta) {}

  /** The member who runs the room logic: earliest-joined participant holding the host role. */
  engineSid(): string | null {
    const hosts = [...this.participants.values()].filter((p) => p.role === "host");
    hosts.sort((a, b) => a.joinedAt - b.joinedAt || (a.sid < b.sid ? -1 : a.sid > b.sid ? 1 : 0));
    return hosts.length ? hosts[0].sid : null;
  }

  snapshot(): J {
    return {
      meta: metaJson(this.meta),
      participants: [...this.participants.values()].map(participantJson),
      chat: this.chat.slice(-300).map(chatJson),
      board: this.board.toJson(), watch: watchSnapshot(this.watch), browse: browseJson(this.browse), chess: this.chess.toJson()
    };
  }

  loadSnapshot(j: J) {
    const m = obj(j, "meta");
    if (m) this.meta = metaFrom(m);
    this.participants = new Map(objects(arr(j, "participants")).map(participantFrom).map((p) => [p.sid, p]));
    this.chat = objects(arr(j, "chat")).map(chatFrom);
    this.board.load(arr(j, "board"));
    this.watch = obj(j, "watch") ? watchFromSnapshot(obj(j, "watch")) : newWatch();
    this.browse = obj(j, "browse") ? browseFrom(obj(j, "browse")) : newBrowse();
    this.chess.load(arr(j, "chess"));
  }

  toSavedJson(): J {
    return { meta: metaJson(this.meta), chat: this.chat.slice(-300).map(chatJson), board: this.board.toJson() };
  }

  applyState(d: J) {
    const m = obj(d, "meta");
    if (m) this.meta = metaFrom(m);
    this.participants = new Map(objects(arr(d, "participants")).map(participantFrom).map((p) => [p.sid, p]));
  }

  apply(ev: string, d: J) {
    switch (ev) {
      case "room:state": this.applyState(d); break;
      case "room:mute": {
        const p = this.participants.get(str(d, "sid"));
        if (p) this.participants.set(p.sid, { ...p, muted: bool(d, "muted") });
        break;
      }
      case "chat:new": {
        const m = chatFrom(d);
        if (!this.chat.some((x) => x.id === m.id)) this.chat.push(m);
        while (this.chat.length > 300) this.chat.shift();
        break;
      }
      case "chat:update": {
        const id = str(d, "id");
        const i = this.chat.findIndex((x) => x.id === id);
        if (i >= 0) this.chat[i] = { ...this.chat[i], deleted: bool(d, "deleted", this.chat[i].deleted), reactions: reactionsFrom(obj(d, "reactions")) };
        break;
      }
      case "board:add": { const o = BoardObj.from(obj(d, "obj") ?? {}); if (o) this.board.add(o); break; }
      case "board:update": this.board.patch(str(d, "id"), obj(d, "patch") ?? {}); break;
      case "board:delete": this.board.remove(strings(arr(d, "ids"))); break;
      case "board:cleared": this.board.clear(); break;
      case "watch:load": case "watch:state": this.watch = watchFromSnapshot(d); break;
      case "watch:meta": if (this.watch.video) this.watch = { ...this.watch, video: { ...this.watch.video, duration: int(d, "duration") } }; break;
      case "watch:queue": this.watch = { ...this.watch, queue: objects(arr(d, "queue")).map(videoFrom) }; break;
      case "watch:perm": this.watch = { ...this.watch, perm: str(d, "perm", "host") }; break;
      case "watch:mods": this.watch = { ...this.watch, mods: strings(arr(d, "mods")) }; break;
      case "watch:ended": this.watch = newWatch({ perm: this.watch.perm, mods: this.watch.mods }); break;
      case "browse:load": case "browse:state": case "browse:reload": this.browse = browseFrom(d); break;
      case "browse:ended": this.browse = newBrowse({ perm: this.browse.perm, mods: this.browse.mods }); break;
      case "chess:game": { const g = ChessGame.from(d); if (g) this.chess.put(g); break; }
    }
  }
}

/* ---------------- local-only records ---------------- */

export interface Contact {
  uid: string; username: string; displayName: string; avatar: Avatar; bio: string; pronouns: string; pub: string;
  lastSeen: number; firstSeen: number; pair: string;
}
export const contactJson = (c: Contact): J => ({
  pair: c.pair, uid: c.uid, username: c.username, displayName: c.displayName, avatar: avatarJson(c.avatar), bio: c.bio,
  pronouns: c.pronouns, pub: c.pub, lastSeen: c.lastSeen, firstSeen: c.firstSeen
});
export const contactFrom = (j: J): Contact => ({
  uid: str(j, "uid"), username: str(j, "username"), displayName: str(j, "displayName"), avatar: avatarFrom(obj(j, "avatar")),
  bio: str(j, "bio"), pronouns: str(j, "pronouns"), pub: str(j, "pub"), lastSeen: lng(j, "lastSeen"),
  firstSeen: lng(j, "firstSeen", nowMs()), pair: str(j, "pair")
});

export interface DmMessage {
  id: string; from: string; text: string; at: number;
  /** "sent" | "pending" | "failed" for my messages, "recv" for theirs */
  status: string; error: string;
  /** text | image | gif | file | call */
  kind: string; name: string; mime: string; size: number; url: string; reply: string; fwd: string; authorName: string;
  edited: boolean; deleted: boolean; ver: number; reactions: Record<string, string[]>;
}

export const newDm = (p: Partial<DmMessage> & { id: string; from: string; at: number }): DmMessage => ({
  text: "", status: "recv", error: "", kind: "text", name: "", mime: "", size: 0, url: "", reply: "", fwd: "", authorName: "",
  edited: false, deleted: false, ver: 0, reactions: {}, ...p
});

export function dmJson(m: DmMessage): J {
  const j: J = { id: m.id, from: m.from, text: m.text, at: m.at, status: m.status, error: m.error };
  if (m.kind !== "text") j.kind = m.kind;
  if (m.name) j.name = m.name;
  if (m.mime) j.mime = m.mime;
  if (m.size > 0) j.size = m.size;
  if (m.url) j.url = m.url;
  if (m.reply) j.reply = m.reply;
  if (m.fwd) j.fwd = m.fwd;
  if (m.authorName) j.an = m.authorName;
  if (m.edited) j.edited = true;
  if (m.deleted) j.deleted = true;
  if (m.ver > 0) j.ver = m.ver;
  if (Object.keys(m.reactions).length) j.rx = m.reactions;
  return j;
}

export function dmFrom(j: J): DmMessage {
  const rx: Record<string, string[]> = {};
  const o = obj(j, "rx");
  if (o) for (const k of Object.keys(o)) rx[k] = strings(Array.isArray(o[k]) ? o[k] : []);
  return {
    id: str(j, "id"), from: str(j, "from"), text: str(j, "text"), at: lng(j, "at"), status: str(j, "status", "recv"),
    error: str(j, "error"), kind: str(j, "kind", "text"), name: str(j, "name"), mime: str(j, "mime"), size: lng(j, "size"),
    url: str(j, "url"), reply: str(j, "reply"), fwd: str(j, "fwd"), authorName: str(j, "an"), edited: bool(j, "edited"),
    deleted: bool(j, "deleted"), ver: int(j, "ver"), reactions: rx
  };
}

/** A conversation: [uid] is a person's uid, "saved" for Saved Messages, or "g:<groupId>". */
export interface Conversation { uid: string; messages: DmMessage[]; unread: number; lastAt: number; pinned: string; muted: boolean }
export const newConv = (uid: string): Conversation => ({ uid, messages: [], unread: 0, lastAt: 0, pinned: "", muted: false });
export const convJson = (c: Conversation): J => ({
  uid: c.uid, unread: c.unread, lastAt: c.lastAt, messages: c.messages.slice(-800).map(dmJson), pinned: c.pinned, muted: c.muted
});
export const convFrom = (j: J): Conversation => ({
  uid: str(j, "uid"), messages: objects(arr(j, "messages")).map(dmFrom), unread: int(j, "unread"), lastAt: lng(j, "lastAt"),
  pinned: str(j, "pinned"), muted: bool(j, "muted")
});

export interface Report {
  id: string; reporterUid: string; reporterName: string; targetType: string; targetId: string; targetLabel: string;
  roomId: string; roomTitle: string; category: string; details: string; status: string; createdAt: number; note: string;
  resolvedAt: number; outgoing: boolean; excerpt: string;
}
export const reportFrom = (j: J): Report => ({
  id: str(j, "id"), reporterUid: str(j, "reporterUid"), reporterName: str(j, "reporterName"), targetType: str(j, "targetType"),
  targetId: str(j, "targetId"), targetLabel: str(j, "targetLabel"), roomId: str(j, "roomId"), roomTitle: str(j, "roomTitle"),
  category: str(j, "category"), details: str(j, "details"), status: str(j, "status", "open"), createdAt: lng(j, "createdAt"),
  note: str(j, "note"), resolvedAt: lng(j, "resolvedAt"), outgoing: bool(j, "outgoing"), excerpt: str(j, "excerpt")
});

export interface AuditEntry { id: string; action: string; roomId: string; roomTitle: string; targetLabel: string; detail: string; at: number }
export const auditJson = (a: AuditEntry): J => ({ id: a.id, action: a.action, roomId: a.roomId, roomTitle: a.roomTitle, target: a.targetLabel, detail: a.detail, at: a.at });
export const auditFrom = (j: J): AuditEntry => ({
  id: str(j, "id"), action: str(j, "action"), roomId: str(j, "roomId"), roomTitle: str(j, "roomTitle"),
  targetLabel: str(j, "target"), detail: str(j, "detail"), at: lng(j, "at")
});

export interface SavedRoom { meta: RoomMeta; lastActiveAt: number; totalJoins: number; peak: number; isOwner: boolean }
export const savedRoomJson = (r: SavedRoom): J => ({ meta: metaJson(r.meta), lastActiveAt: r.lastActiveAt, totalJoins: r.totalJoins, peak: r.peak, isOwner: r.isOwner });
export const savedRoomFrom = (j: J): SavedRoom => ({
  meta: metaFrom(obj(j, "meta") ?? {}), lastActiveAt: lng(j, "lastActiveAt"), totalJoins: int(j, "totalJoins"), peak: int(j, "peak"), isOwner: bool(j, "isOwner")
});

export interface NearbyRoom {
  roomId: string; title: string; topic: string; tags: string[]; hostName: string; count: number; speakers: number;
  locked: boolean; ip: string; port: number; seenAt: number;
  /** the host's session id: tells us whether we already hold a connection to them */
  sid: string;
}
export interface NearbyPerson { uid: string; name: string; username: string; avatar: J | null; ip: string; port: number; seenAt: number }

/* ---------------- groups ---------------- */

export interface GroupMember { uid: string; name: string; username: string; avatar: Avatar; role: string; addedAt: number }
export const memberJson = (m: GroupMember): J => ({ uid: m.uid, name: m.name, user: m.username, av: avatarJson(m.avatar), role: m.role, at: m.addedAt });
export const memberFrom = (j: J): GroupMember => ({
  uid: str(j, "uid"), name: str(j, "name"), username: str(j, "user"), avatar: avatarFrom(obj(j, "av")), role: str(j, "role", "member"), addedAt: lng(j, "at")
});

export interface Group {
  id: string; name: string; about: string; ownerUid: string; members: Record<string, GroupMember>; createdAt: number; rev: number;
  membersCanAdd: boolean; emoji: string; hue: number; left: boolean;
}
export const groupChatId = (g: Group) => "g:" + g.id;
export const roleOf = (g: Group, uid: string) => g.members[uid]?.role ?? "";
export const isAdmin = (g: Group, uid: string) => { const r = roleOf(g, uid); return r === "owner" || r === "admin"; };
export const canAdd = (g: Group, uid: string) => uid in g.members && (g.membersCanAdd || isAdmin(g, uid));
export const groupJson = (g: Group): J => ({
  id: g.id, name: g.name, about: g.about, owner: g.ownerUid, members: Object.values(g.members).map(memberJson), at: g.createdAt,
  rev: g.rev, mca: g.membersCanAdd, emoji: g.emoji, hue: g.hue, left: g.left
});
export function groupFrom(j: J): Group {
  const members: Record<string, GroupMember> = {};
  for (const m of objects(arr(j, "members"))) { const gm = memberFrom(m); members[gm.uid] = gm; }
  return {
    id: str(j, "id"), name: str(j, "name"), about: str(j, "about"), ownerUid: str(j, "owner"), members, createdAt: lng(j, "at"),
    rev: lng(j, "rev"), membersCanAdd: bool(j, "mca", true), emoji: str(j, "emoji", "💬"), hue: int(j, "hue", 265), left: bool(j, "left")
  };
}
