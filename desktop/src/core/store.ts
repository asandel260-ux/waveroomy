import { J, Flow, arr, bool, cancelLater, later, nowMs, obj, objects, parseJson, str, strings, int, flt, Timer, Ids } from "./util";
import { native } from "./native";
import {
  AuditEntry, Avatar, Contact, Conversation, DmMessage, Group, Report, RoomMeta, RoomState, SavedRoom,
  auditFrom, auditJson, contactFrom, contactJson, convFrom, convJson, groupFrom, groupJson, newConv, reportFrom, savedRoomFrom, savedRoomJson
} from "./models";
import { Identity, MyProfile, profileFrom, profileJson } from "./identity";
import { b64decode, b64encode } from "./util";

/* ===========================================================================
 * Disk persistence. Small JSON documents are loaded into memory at start and
 * written through to the platform storage in the background.
 * ========================================================================= */

export const Docs = {
  cache: {} as Record<string, string>,
  async init() { this.cache = await native.docsLoad(); },
  readText(name: string): string | null { return name in this.cache ? this.cache[name] : null; },
  read(name: string): J | null {
    const t = this.readText(name);
    const j = t != null ? parseJson(t) : null;
    if (j) return j;
    const b = this.readText(name + ".bak");
    return b != null ? parseJson(b) : null;
  },
  write(name: string, json: J) {
    const text = JSON.stringify(json);
    const old = this.cache[name];
    if (old !== undefined) { this.cache[name + ".bak"] = old; native.docWrite(name + ".bak", old); }
    this.cache[name] = text;
    native.docWrite(name, text);
  },
  delete(name: string) { delete this.cache[name]; native.docDelete(name); },
  async wipeAll() { this.cache = {}; await native.wipe(); }
};

/** Coalesces bursts of changes into one write. */
export class Debounced {
  private pending: Timer = null;
  constructor(private ms: number, private action: () => void) {}
  poke() {
    if (this.pending != null) return;
    this.pending = later(this.ms, () => { this.pending = null; this.action(); });
  }
  flush() {
    if (this.pending == null) return;
    cancelLater(this.pending);
    this.pending = null;
    this.action();
  }
}

/* ---------------- my profile ---------------- */

export class ProfileStore {
  identity!: Identity;
  profile = new Flow<MyProfile | null>(null);
  photoVersion = new Flow(0);
  photo: Uint8Array | null = null;
  get uid() { return this.identity.uid; }

  async load() {
    const idJson = Docs.read("identity.json");
    const id = idJson ? await Identity.from(idJson) : null;
    if (id) this.identity = id;
    else {
      this.identity = await Identity.generate();
      Docs.write("identity.json", this.identity.toJson());
    }
    const pj = Docs.read("profile.json");
    this.profile.value = pj ? profileFrom(pj) : null;
    this.photo = await native.binRead("photo.jpg");
    this.photoVersion.value++;
  }

  save(p: MyProfile) {
    this.profile.value = p;
    Docs.write("profile.json", profileJson(p));
    this.onChanged?.();
  }
  onChanged?: () => void;

  setPhoto(bytes: Uint8Array | null) {
    this.photo = bytes;
    if (bytes) void native.binWrite("photo.jpg", bytes); else native.binDelete("photo.jpg");
    this.photoVersion.value++;
  }

  /** Whole identity + profile as a backup file (same format as the Android app). */
  exportBackup(): J {
    const j: J = { kind: "waveroom-identity", v: 1, identity: this.identity.toJson() };
    if (this.profile.value) j.profile = profileJson(this.profile.value);
    if (this.photo) j.photo = b64encode(this.photo);
    return j;
  }

  async importBackup(j: J): Promise<boolean> {
    if (str(j, "kind") !== "waveroom-identity") return false;
    const id = await Identity.from(obj(j, "identity") ?? {});
    if (!id) return false;
    this.identity = id;
    Docs.write("identity.json", id.toJson());
    const pj = obj(j, "profile");
    if (pj) this.save(profileFrom(pj));
    const ph = str(j, "photo");
    this.setPhoto(ph ? b64decode(ph) : null);
    return true;
  }
}

/* ---------------- settings ---------------- */

export interface Settings {
  ptt: boolean; sounds: boolean; dmPolicy: string; showOnline: boolean; nearby: boolean; useStun: boolean; customStun: string;
  turnUrl: string; turnUser: string; turnPass: string; fxEnabled: boolean; fxPreset: string; fxIntensity: number;
  shareRes: number; shareFps: number; micDeviceId: string; outputDeviceId: string; chatSlidesOpen: boolean;
  theme: string; accentColor: number; bgColor: number; animations: boolean; useRelay: boolean; reconnect: boolean; findMe: string; rvUrl: string;
  chessSounds: boolean; chessLegal: boolean;
  /** desktop-only */
  pttKey: string; startMinimized: boolean; closeToTray: boolean; notifications: boolean;
}

export const defaultSettings = (): Settings => ({
  ptt: false, sounds: true, dmPolicy: "everyone", showOnline: true, nearby: true, useStun: true, customStun: "", turnUrl: "", turnUser: "",
  turnPass: "", fxEnabled: false, fxPreset: "studio", fxIntensity: 0.7, shareRes: 720, shareFps: 30, micDeviceId: "", outputDeviceId: "",
  chatSlidesOpen: false, theme: "dark", accentColor: 0, bgColor: 0, animations: true, useRelay: true, reconnect: true, findMe: "everyone", rvUrl: "",
  chessSounds: true, chessLegal: true, pttKey: "Space", startMinimized: false, closeToTray: true, notifications: true
});

export class SettingsStore {
  state = new Flow<Settings>(defaultSettings());
  private saver = new Debounced(300, () => Docs.write("settings.json", this.state.value));
  flush() { this.saver.flush(); }
  load() {
    const j = Docs.read("settings.json");
    if (j) this.state.value = { ...defaultSettings(), ...j };
  }
  update(f: (s: Settings) => Settings) { this.state.value = f(this.state.value); this.saver.poke(); }
}

/* ---------------- people I have met ---------------- */

export class SocialStore {
  contacts = new Flow<Record<string, Contact>>({});
  following = new Flow<string[]>([]);
  followers = new Flow<string[]>([]);
  blocked = new Flow<string[]>([]);
  photoVersion = new Flow(0);
  private photos = new Map<string, Uint8Array>();
  private loading = new Set<string>();
  private saver = new Debounced(600, () => this.persist());
  flush() { this.saver.flush(); }

  load() {
    const j = Docs.read("social.json");
    if (!j) return;
    const m: Record<string, Contact> = {};
    for (const c of objects(arr(j, "contacts"))) { const x = contactFrom(c); m[x.uid] = x; }
    this.contacts.value = m;
    this.following.value = strings(arr(j, "following"));
    this.followers.value = strings(arr(j, "followers"));
    this.blocked.value = strings(arr(j, "blocked"));
  }

  private persist() {
    Docs.write("social.json", {
      contacts: Object.values(this.contacts.value).map(contactJson), following: this.following.value,
      followers: this.followers.value, blocked: this.blocked.value
    });
  }

  contact(uid: string): Contact | undefined { return this.contacts.value[uid]; }
  isBlocked(uid: string) { return this.blocked.value.includes(uid); }
  isFollowing(uid: string) { return this.following.value.includes(uid); }

  upsert(c: Contact) {
    const old = this.contacts.value[c.uid];
    this.contacts.value = { ...this.contacts.value, [c.uid]: old ? { ...c, firstSeen: old.firstSeen, pair: old.pair } : c };
    this.saver.poke();
  }
  setPair(uid: string, pair: string) {
    const c = this.contacts.value[uid];
    if (!c || c.pair === pair) return;
    this.contacts.value = { ...this.contacts.value, [uid]: { ...c, pair } };
    this.saver.poke();
  }
  touchSeen(uid: string) {
    const c = this.contacts.value[uid];
    if (!c) return;
    this.contacts.value = { ...this.contacts.value, [uid]: { ...c, lastSeen: nowMs() } };
    this.saver.poke();
  }

  photoOf(uid: string): Uint8Array | null {
    const c = this.photos.get(uid);
    if (c) return c;
    if (!this.loading.has(uid)) {
      this.loading.add(uid);
      void native.binRead(`photos/${uid}.jpg`).then((b) => {
        if (b) { this.photos.set(uid, b); this.photoVersion.value++; }
      });
    }
    return null;
  }
  setPhoto(uid: string, bytes: Uint8Array | null) {
    if (!bytes || !bytes.length) return;
    this.photos.set(uid, bytes);
    void native.binWrite(`photos/${uid}.jpg`, bytes);
    this.photoVersion.value++;
  }

  follow(uid: string, on: boolean) {
    this.following.value = on ? [...new Set([...this.following.value, uid])] : this.following.value.filter((x) => x !== uid);
    this.saver.poke();
  }
  setFollower(uid: string, on: boolean) {
    this.followers.value = on ? [...new Set([...this.followers.value, uid])] : this.followers.value.filter((x) => x !== uid);
    this.saver.poke();
  }
  block(uid: string, on: boolean) {
    this.blocked.value = on ? [...new Set([...this.blocked.value, uid])] : this.blocked.value.filter((x) => x !== uid);
    if (on) this.following.value = this.following.value.filter((x) => x !== uid);
    this.saver.poke();
  }
}

/* ---------------- direct messages ---------------- */

const MAXMSG = 3000;
const cap = (l: DmMessage[]) => (l.length > MAXMSG ? l.slice(-MAXMSG) : l);

export class DmStore {
  convs = new Flow<Record<string, Conversation>>({});
  /** Messages and groups this person deleted on this device; never let a sync bring them back. */
  removed = new Set<string>();
  private saver = new Debounced(500, () => this.persist());
  flush() { this.saver.flush(); }

  load() {
    const j = Docs.read("dms.json");
    if (!j) return;
    const m: Record<string, Conversation> = {};
    for (const c of objects(arr(j, "convs"))) { const x = convFrom(c); m[x.uid] = x; }
    this.convs.value = m;
    for (const r of strings(arr(j, "removed"))) this.removed.add(r);
  }

  private persist() {
    while (this.removed.size > 3000) this.removed.delete(this.removed.values().next().value as string);
    Docs.write("dms.json", { convs: Object.values(this.convs.value).map(convJson), removed: [...this.removed] });
  }

  markRemoved(key: string) { this.removed.add(key); this.saver.poke(); }
  unreadTotal(): number { return Object.values(this.convs.value).filter((c) => !c.muted).reduce((a, c) => a + c.unread, 0); }
  find(chat: string, id: string): DmMessage | undefined { return this.convs.value[chat]?.messages.find((m) => m.id === id); }

  private mutate(uid: string, f: (c: Conversation) => Conversation) {
    const cur = this.convs.value[uid] ?? newConv(uid);
    this.convs.value = { ...this.convs.value, [uid]: f(cur) };
    this.saver.poke();
  }

  /** Insert or replace a message (by id). */
  put(chat: string, msg: DmMessage, unreadBump = false) {
    this.mutate(chat, (c) => {
      const i = c.messages.findIndex((m) => m.id === msg.id);
      let list: DmMessage[];
      if (i >= 0) { list = [...c.messages]; list[i] = msg; }
      else list = cap([...c.messages, msg].sort((a, b) => a.at - b.at));
      return { ...c, messages: list, lastAt: Math.max(c.lastAt, msg.at), unread: c.unread + (unreadBump && i < 0 ? 1 : 0) };
    });
  }
  edit(chat: string, id: string, f: (m: DmMessage) => DmMessage) {
    const cur = this.find(chat, id);
    if (cur) this.put(chat, f(cur));
  }
  setPinned(chat: string, id: string) { this.mutate(chat, (c) => ({ ...c, pinned: id })); }
  setMuted(chat: string, on: boolean) { this.mutate(chat, (c) => ({ ...c, muted: on })); }
  ensure(chat: string) { if (!this.convs.value[chat]) this.mutate(chat, (c) => c); }
  removeMessage(chat: string, id: string) {
    this.removed.add(id);
    this.mutate(chat, (c) => ({ ...c, messages: c.messages.filter((m) => m.id !== id), pinned: c.pinned === id ? "" : c.pinned }));
  }
  deleteChat(chat: string) {
    const m = { ...this.convs.value };
    delete m[chat];
    this.convs.value = m;
    this.saver.poke();
  }
  addIncoming(uid: string, msg: DmMessage, markUnread: boolean) {
    this.mutate(uid, (c) => c.messages.some((m) => m.id === msg.id) ? c : { ...c, messages: cap([...c.messages, msg]), unread: c.unread + (markUnread ? 1 : 0), lastAt: msg.at });
  }
  setStatus(uid: string, id: string, status: string, error = "") {
    this.mutate(uid, (c) => ({ ...c, messages: c.messages.map((m) => (m.id === id ? { ...m, status, error } : m)) }));
  }
  markRead(uid: string) {
    const c = this.convs.value[uid];
    if (!c || c.unread === 0) return;
    this.mutate(uid, (x) => ({ ...x, unread: 0 }));
  }
  pending(uid: string, myUid: string): DmMessage[] {
    return this.convs.value[uid]?.messages.filter((m) => m.from === myUid && m.status === "pending") ?? [];
  }
  wipe() { this.convs.value = {}; this.saver.poke(); }
}

/* ---------------- groups ---------------- */

export class GroupStore {
  groups = new Flow<Record<string, Group>>({});
  private saver = new Debounced(400, () => Docs.write("groups.json", { groups: Object.values(this.groups.value).map(groupJson) }));
  flush() { this.saver.flush(); }
  load() {
    const j = Docs.read("groups.json");
    if (!j) return;
    const m: Record<string, Group> = {};
    for (const g of objects(arr(j, "groups"))) { const x = groupFrom(g); m[x.id] = x; }
    this.groups.value = m;
  }
  get(id: string): Group | undefined { return this.groups.value[id]; }
  put(g: Group) { this.groups.value = { ...this.groups.value, [g.id]: g }; this.saver.poke(); }
  remove(id: string) { const m = { ...this.groups.value }; delete m[id]; this.groups.value = m; this.saver.poke(); }
  wipe() { this.groups.value = {}; this.saver.poke(); }
}

/** Where attachments live on this device, one file per message id. */
export const Media = {
  names: new Set<string>(),
  async init() { for (const n of await native.binList()) if (n.startsWith("media/")) this.names.add(n.slice(6)); },
  key(id: string) { return id.replace(/[^A-Za-z0-9_]/g, ""); },
  has(id: string) { return this.names.has(this.key(id)); },
  async read(id: string): Promise<Uint8Array | null> { return native.binRead("media/" + this.key(id)); },
  async write(id: string, bytes: Uint8Array) { await native.binWrite("media/" + this.key(id), bytes); this.names.add(this.key(id)); },
  delete(id: string) { native.binDelete("media/" + this.key(id)); this.names.delete(this.key(id)); },
  wipe() { for (const n of this.names) native.binDelete("media/" + n); this.names.clear(); }
};

/* ---------------- saved rooms ---------------- */

export class RoomsStore {
  rooms = new Flow<SavedRoom[]>([]);
  private saver = new Debounced(400, () => Docs.write("rooms.json", { rooms: this.rooms.value.map(savedRoomJson) }));
  flush() { this.saver.flush(); }
  load() {
    const j = Docs.read("rooms.json");
    if (j) this.rooms.value = objects(arr(j, "rooms")).map(savedRoomFrom);
  }
  get(id: string): SavedRoom | undefined { return this.rooms.value.find((r) => r.meta.id === id); }
  upsert(r: SavedRoom) {
    const list = [...this.rooms.value];
    const i = list.findIndex((x) => x.meta.id === r.meta.id);
    if (i >= 0) list[i] = r; else list.unshift(r);
    this.rooms.value = list.sort((a, b) => b.lastActiveAt - a.lastActiveAt);
    this.saver.poke();
  }
  delete(id: string) {
    this.rooms.value = this.rooms.value.filter((r) => r.meta.id !== id);
    Docs.delete(`rooms/${id}.json`);
    this.saver.poke();
  }
  saveData(state: RoomState) { Docs.write(`rooms/${state.meta.id}.json`, state.toSavedJson()); }
  loadData(id: string): J | null { return Docs.read(`rooms/${id}.json`); }
}

/* ---------------- moderation desk ---------------- */

export class ModStore {
  reports = new Flow<Report[]>([]);
  audit = new Flow<AuditEntry[]>([]);
  stats = new Flow<J>({});
  private saver = new Debounced(500, () => this.persist());
  flush() { this.saver.flush(); }
  load() {
    const j = Docs.read("mod.json");
    if (!j) return;
    this.reports.value = objects(arr(j, "reports")).map(reportFrom);
    this.audit.value = objects(arr(j, "audit")).map(auditFrom);
    this.stats.value = obj(j, "stats") ?? {};
  }
  private persist() {
    Docs.write("mod.json", { reports: this.reports.value.slice(0, 500), audit: this.audit.value.slice(0, 1000).map(auditJson), stats: this.stats.value });
  }
  addReport(r: Report) { this.reports.value = [r, ...this.reports.value]; this.saver.poke(); }
  resolve(id: string, status: string, note: string) {
    this.reports.value = this.reports.value.map((r) => (r.id === id ? { ...r, status, note, resolvedAt: nowMs() } : r));
    this.saver.poke();
  }
  log(action: string, room: RoomMeta, target: string, detail: string) {
    this.audit.value = [{ id: Ids.id("a_"), action, roomId: room.id, roomTitle: room.title, targetLabel: target, detail, at: nowMs() }, ...this.audit.value];
    this.saver.poke();
  }
  /** Count something per day, e.g. "joins" or "msgs"; kept for 30 days. */
  bump(counter: string, by = 1) {
    const day = String(Math.floor(nowMs() / 86_400_000));
    const s = JSON.parse(JSON.stringify(this.stats.value));
    const c = (s[counter] ??= {});
    c[day] = (c[day] ?? 0) + by;
    const cutoff = Math.floor(nowMs() / 86_400_000) - 30;
    for (const k of Object.keys(c)) if (Number(k) < cutoff) delete c[k];
    this.stats.value = s;
    this.saver.poke();
  }
  series(counter: string, days: number): number[] {
    const today = Math.floor(nowMs() / 86_400_000);
    const c = this.stats.value[counter];
    const out: number[] = [];
    for (let i = days - 1; i >= 0; i--) out.push(c?.[String(today - i)] ?? 0);
    return out;
  }
  wipe() { this.reports.value = []; this.audit.value = []; this.stats.value = {}; this.saver.poke(); }
}
