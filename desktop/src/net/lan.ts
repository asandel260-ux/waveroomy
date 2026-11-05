import { NearbyPerson, NearbyRoom } from "../core/models";
import { native } from "../core/native";
import { Flow, J, Timer, arr, bool, cancelLater, int, later, nowMs, obj, str, strings } from "../core/util";
import { unpack } from "./sdp";
import type { LanHost } from "../room/session";

export interface LanApp {
  presence(): J | null;
  admitFriend(hello: J): string | null;
  issueFriendInvite(): Promise<string | null>;
  acceptAnswer(answer: J): Promise<boolean>;
}

export const LAN_PORT = 47877;

const parse = (s: string | null): J | null => { if (!s) return null; try { return JSON.parse(s); } catch { return null; } };

/**
 * Same-network discovery without any server: hosts broadcast a tiny UDP beacon, listeners show
 * them as "Nearby rooms", and tapping one runs the offer/answer exchange over a short-lived TCP
 * socket so no code has to be copied by hand. Same line protocol as the Android LanService.
 * The sockets live in the Electron main process; this class only speaks the protocol.
 */
export class LanService {
  readonly nearby = new Flow<NearbyRoom[]>([]);
  readonly people = new Flow<NearbyPerson[]>([]);
  private seen = new Map<string, NearbyRoom>();
  private seenPeople = new Map<string, NearbyPerson>();
  private app: LanApp | null = null;
  private host: LanHost | null = null;
  private listening = false;
  private cleanup: Timer = null;
  private port = 0;
  private beat: Timer = null;
  private incoming = new Map<number, { stage: string; hello: J | null; who: "app" | "host" }>();
  private outHandlers = new Map<number, (line: string | null) => void>();
  private active = 0;

  constructor(private mySid: () => string) {
    native.lan.onBeacon((msg, ip) => this.onBeaconPacket(msg, ip));
    native.lan.onIncoming((id, line) => this.onIncoming(id, line));
    native.lan.onOutgoing((id, line) => this.outHandlers.get(id)?.(line));
  }

  get available() { return native.lan.available; }

  /* ---- listening ---- */

  startListening() {
    if (this.listening || !native.lan.available) return;
    this.listening = true;
    native.lan.listen(true);
    this.cleanup = later(2000, () => this.sweep());
  }

  stopListening() {
    this.listening = false;
    cancelLater(this.cleanup);
    native.lan.listen(false);
    this.seen.clear(); this.seenPeople.clear();
    this.nearby.value = []; this.people.value = [];
  }

  private onBeaconPacket(j: J, ip: string) {
    if (int(j, "wr") !== 1 || str(j, "sid") === this.mySid()) return;
    if (str(j, "k") === "p") {
      const pp: NearbyPerson = { uid: str(j, "uid"), name: str(j, "name"), username: str(j, "user"), avatar: obj(j, "av"), ip, port: int(j, "port"), seenAt: nowMs() };
      if (pp.uid) { this.seenPeople.set(pp.uid, pp); this.publishPeople(); }
      return;
    }
    const r: NearbyRoom = {
      roomId: str(j, "room"), title: str(j, "title"), topic: str(j, "topic"), tags: strings(arr(j, "tags")), hostName: str(j, "host"),
      count: int(j, "n"), speakers: int(j, "spk"), locked: bool(j, "lock"), ip, port: int(j, "port"), seenAt: nowMs(), sid: str(j, "sid")
    };
    this.seen.set(r.roomId + "@" + ip, r);
    this.nearby.value = [...this.seen.values()].sort((a, b) => b.count - a.count);
  }

  private publishPeople() { this.people.value = [...this.seenPeople.values()].sort((a, b) => a.name.toLowerCase() < b.name.toLowerCase() ? -1 : 1); }

  private sweep() {
    const t = nowMs();
    let changed = false;
    for (const [k, v] of this.seen) if (t - v.seenAt > 7000) { this.seen.delete(k); changed = true; }
    if (changed) this.nearby.value = [...this.seen.values()].sort((a, b) => b.count - a.count);
    let pc = false;
    for (const [k, v] of this.seenPeople) if (t - v.seenAt > 8000) { this.seenPeople.delete(k); pc = true; }
    if (pc) this.publishPeople();
    if (this.listening) this.cleanup = later(2000, () => this.sweep());
  }

  /* ---- hosting ---- */

  startHosting(h: LanHost) { this.host = h; }
  stopHosting() { this.host = null; }

  /** Always-on: a TCP server (friend connects and answer delivery) plus presence/room beacons. */
  async startServer(a: LanApp) {
    this.app = a;
    if (!native.lan.available || this.port) return;
    try { this.port = await native.lan.start(); } catch { return; }
    this.pushBeacons();
  }

  private pushBeacons() {
    const packets: J[] = [];
    const b = this.host?.beacon();
    if (b) packets.push({ ...b, wr: 1, sid: this.mySid(), port: this.port });
    const p = this.app?.presence();
    if (p) packets.push({ ...p, wr: 1, k: "p", sid: this.mySid(), port: this.port });
    native.lan.setBeacons(packets);
    this.beat = later(2500, () => this.pushBeacons());
  }

  async addresses(): Promise<string[]> { return (await native.lan.addresses()).slice(0, 3); }

  /* ---- host side of one socket ---- */

  private reply(id: number, j: J) { native.lan.send(id, JSON.stringify(j)); }

  private async onIncoming(id: number, line: string | null) {
    let st = this.incoming.get(id);
    if (line == null) { if (st) { this.incoming.delete(id); this.active--; } return; }
    if (!st) {
      if (this.active >= 6) { native.lan.close(id); return; }
      this.active++;
      st = { stage: "hello", hello: null, who: "host" };
      this.incoming.set(id, st);
      // slow or silent peers don't get to hold a slot
      later(60_000, () => { if (this.incoming.has(id)) native.lan.close(id); });
    }
    const j = parse(line);
    if (!j) { native.lan.close(id); return; }
    try {
      if (st.stage === "hello") await this.onHello(id, st, j);
      else if (st.stage === "answer") await this.onAnswer(id, st, j);
    } catch { native.lan.close(id); }
  }

  private async onHello(id: number, st: { stage: string; hello: J | null; who: "app" | "host" }, hello: J) {
    st.hello = hello;
    switch (str(hello, "t")) {
      case "ans": {
        const parsed = await unpack(str(hello, "code"));
        let ok = false;
        if (parsed && this.app) ok = await Promise.race([this.app.acceptAnswer(parsed), new Promise<boolean>((r) => setTimeout(() => r(false), 8000))]);
        this.reply(id, { t: ok ? "ok" : "deny" });
        native.lan.close(id);
        return;
      }
      case "friend": {
        const deny = this.app ? this.app.admitFriend(hello) : "Busy.";
        if (deny) { this.reply(id, { t: "deny", error: deny }); native.lan.close(id); return; }
        const code = await this.app!.issueFriendInvite();
        if (!code) { this.reply(id, { t: "deny", error: "Could not prepare a connection." }); native.lan.close(id); return; }
        st.who = "app";
        st.stage = "answer";
        this.reply(id, { t: "offer", code });
        return;
      }
      case "join": {
        const h = this.host;
        const deny = h ? h.admit(hello) : "This room is closed.";
        if (deny) { this.reply(id, { t: "deny", error: deny }); native.lan.close(id); return; }
        const { code, why } = await h!.issueInvite(str(hello, "roomId"));
        if (!code) { this.reply(id, { t: "deny", error: "The host could not prepare a connection" + (why ? ` (${why})` : "") + "." }); native.lan.close(id); return; }
        st.who = "host";
        st.stage = "answer";
        this.reply(id, { t: "offer", code });
        return;
      }
      default: native.lan.close(id);
    }
  }

  private async onAnswer(id: number, st: { who: "app" | "host" }, ans: J) {
    if (str(ans, "t") !== "answer") { native.lan.close(id); return; }
    const parsed = await unpack(str(ans, "code"));
    if (!parsed) { native.lan.close(id); return; }
    if (st.who === "app") void this.app?.acceptAnswer(parsed); else void this.host?.acceptAnswer(parsed);
    this.reply(id, { t: "ok" });
    later(500, () => native.lan.close(id));
  }

  /* ---- joiner side ---- */

  /**
   * Push our answer code straight to the inviter when it is on the same network. All of their addresses are
   * tried at once and each gets 2.5 s to connect: when the inviter is somewhere else, those private addresses
   * lead nowhere, and waiting for each of them in turn used to hold the reply back for many seconds.
   */
  async deliverAnswer(addrs: string[], code: string): Promise<boolean> {
    const one = async (a: string): Promise<boolean> => {
      const i = a.lastIndexOf(":");
      const conn = native.lan.connect(a.slice(0, i), parseInt(a.slice(i + 1), 10)).catch(() => -1);
      const id = await Promise.race([conn, new Promise<number>((r) => setTimeout(() => r(-2), 2500))]);
      if (id === -2) { void conn.then((late) => { if (late >= 0) native.lan.close(late); }); return false; }
      if (id < 0) return false;
      const r = await new Promise<J | null>((res) => {
        const t = setTimeout(() => { this.outHandlers.delete(id); native.lan.close(id); res(null); }, 8000);
        this.outHandlers.set(id, (line) => { clearTimeout(t); this.outHandlers.delete(id); native.lan.close(id); res(parse(line)); });
        native.lan.send(id, JSON.stringify({ t: "ans", code }));
      });
      return !!r && str(r, "t") === "ok";
    };
    return new Promise<boolean>((resolve) => {
      let left = addrs.length;
      if (!left) { resolve(false); return; }
      for (const a of addrs) void one(a).catch(() => false).then((ok) => { if (ok) resolve(true); else if (--left === 0) resolve(false); });
    });
  }

  connectPerson(p: NearbyPerson, myHello: J, accept: (offer: string) => Promise<string | null>): Promise<string | null> {
    return this.join({ roomId: "", ip: p.ip, port: p.port }, myHello, accept, "friend");
  }

  /** Resolves with null on success or a user-facing error. */
  async join(room: { roomId: string; ip: string; port: number }, myHello: J, accept: (offer: string) => Promise<string | null>, kind = "join"): Promise<string | null> {
    const id = await native.lan.connect(room.ip, room.port).catch(() => -1);
    if (id < 0) return "Could not reach the host on this network.";
    const queue: (string | null)[] = [];
    let wake: (() => void) | null = null;
    this.outHandlers.set(id, (line) => { queue.push(line); wake?.(); });
    const next = (ms: number) => new Promise<string | null>((res) => {
      const t = setTimeout(() => res(null), ms);
      const check = () => { if (queue.length) { clearTimeout(t); res(queue.shift() ?? null); } else wake = check; };
      check();
    });
    try {
      native.lan.send(id, JSON.stringify({ ...myHello, t: kind, roomId: room.roomId }));
      const resp = parse(await next(40_000));
      if (!resp) return "Could not reach the host on this network.";
      if (str(resp, "t") === "deny") return str(resp, "error", "The host refused the connection.");
      if (str(resp, "t") !== "offer") return "Unexpected reply.";
      const answer = await Promise.race([accept(str(resp, "code")), new Promise<null>((r) => setTimeout(() => r(null), 30_000))]);
      if (!answer) return "Could not prepare a connection.";
      native.lan.send(id, JSON.stringify({ t: "answer", code: answer }));
      await next(8000);
      return null;
    } finally {
      this.outHandlers.delete(id);
      native.lan.close(id);
    }
  }
}
