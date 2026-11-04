import { Flow, J, b64decode, b64encode, hex, later, lng, nowMs, sha256, str, unutf8, utf8 } from "../core/util";
import { unpack } from "../net/sdp";
import type { AppCore } from "./core";
import { Discovery, inboxTopic } from "./discovery";

const DEFAULT_URL = "https://ntfy.sh";
const urlSafe = (b: Uint8Array) => b64encode(b).replace(/\+/g, "-").replace(/\//g, "_");

interface Peer { uid: string; key: Uint8Array; topic: string }

/**
 * Lets saved friends find each other again from anywhere, without any account or server of ours.
 * Same topics, AES-GCM notes and offerer rule as the Android app (Rendezvous.kt).
 */
export class Rendezvous {
  readonly status = new Flow("starting");
  private peers = new Map<string, Peer>();
  private subKey = "";
  private abort: AbortController | null = null;
  private subGen = 0;
  private lastEventTime = 0;
  private seenIds: string[] = [];
  private partial = new Map<string, (string | null)[]>();
  private handled: string[] = [];
  private tries = new Map<string, number>();
  private lastOffer = new Map<string, number>();
  private started = false;
  private inbox = "";
  private topicList = "";
  private pollSince = 0;
  private polling = false;

  constructor(private core: AppCore, private disc: Discovery) {}

  start() {
    if (this.started) return;
    this.started = true;
    later(4000, () => void this.tick());
    later(9000, () => void this.pollLoop());
  }

  /**
   * The live stream is cut by some networks and proxies, so the mailboxes are also read the plain way
   * every 12 s. Notes seen twice are ignored (seenIds), so both paths can run side by side.
   */
  private async pollLoop() {
    if (!this.polling && this.topicList && this.core.settings.state.value.reconnect) {
      this.polling = true;
      try {
        const since = this.pollSince > 0 ? String(this.pollSince) : "5m";
        const r = await fetch(`${this.baseUrl()}/${this.topicList}/json?poll=1&since=${since}`, { signal: AbortSignal.timeout(10000) });
        if (r.ok) {
          for (const line of (await r.text()).split("\n").slice(0, 300)) {
            if (!line.trim()) continue;
            let j: J; try { j = JSON.parse(line); } catch { continue; }
            if (str(j, "event") !== "message") continue;
            const tm = lng(j, "time");
            if (tm > this.pollSince) this.pollSince = tm;
            void this.onEvent(str(j, "id"), str(j, "topic"), str(j, "message"));
          }
        }
      } catch { /* try again next round */ }
      this.polling = false;
    }
    later(12_000, () => void this.pollLoop());
  }
  lost(uid: string) { this.tries.delete(uid); later(2500, () => void this.tick(true)); }
  kick() { this.tries.clear(); later(500, () => void this.tick(true)); }
  resume() { this.kick(); }

  private baseUrl(): string {
    const u = this.core.settings.state.value.rvUrl.trim().replace(/\/+$/, "");
    return /^https?:\/\//.test(u) ? u : DEFAULT_URL;
  }

  async post(topic: string, body: string): Promise<boolean> {
    try {
      const r = await fetch(`${this.baseUrl()}/${topic}`, { method: "POST", body, signal: AbortSignal.timeout(8000) });
      if (r.status === 429) this.status.value = "the message board is limiting us, will retry later";
      return r.ok;
    } catch { return false; }
  }

  /** Read what a mailbox still holds. */
  async poll(topic: string): Promise<J[]> {
    const r = await fetch(`${this.baseUrl()}/${topic}/json?poll=1&since=13h`, { signal: AbortSignal.timeout(10000) });
    if (!r.ok) throw new Error("HTTP " + r.status);
    const out: J[] = [];
    for (const line of (await r.text()).split("\n").slice(0, 200)) {
      if (!line.trim()) continue;
      try { out.push(JSON.parse(line)); } catch { /* ignore */ }
    }
    return out;
  }

  private async friends(): Promise<Peer[]> {
    const out: Peer[] = [];
    for (const c of Object.values(this.core.social.contacts.value)) {
      if (!c.pair || this.core.social.blocked.value.includes(c.uid)) continue;
      out.push({ uid: c.uid, key: await sha256("homit-rv-key" + c.pair), topic: "homit-" + hex(await sha256("homit-rv-topic" + c.pair)).slice(0, 40) });
    }
    return out;
  }

  private async tick(once = false) {
    try {
      if (!this.core.settings.state.value.reconnect) { this.status.value = "off"; this.stopSub(); }
      else {
        const fl = await this.friends();
        await this.ensureSub(fl);
        const me = this.core.profile.uid;
        for (const p of fl) {
          if (this.core.connected.value.has(p.uid)) { this.tries.delete(p.uid); continue; }
          const n = this.tries.get(p.uid) ?? 0;
          if (n >= 12) continue;   // stop knocking after ~10 minutes until the app is used again
          this.tries.set(p.uid, n + 1);
          if (me > p.uid) void this.send(p, { t: "knock" });
          else if (n < 3 || nowMs() - (this.lastOffer.get(p.uid) ?? 0) > 240_000) void this.offerTo(p);
        }
      }
    } catch (e) { console.warn("Rv tick", e); }
    if (!once) later(45_000, () => void this.tick());
  }

  private async offerTo(p: Peer) {
    if (nowMs() - (this.lastOffer.get(p.uid) ?? 0) < 60_000) return;
    this.lastOffer.set(p.uid, nowMs());
    const { code } = await this.core.createInviteCode("dm");
    if (code) void this.send(p, { t: "offer", code });
  }

  /* ---- crypto (AES-GCM, iv(12) + ciphertext+tag, url-safe base64 with padding) ---- */

  private async seal(key: Uint8Array, plain: string): Promise<string> {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const k = await crypto.subtle.importKey("raw", key as BufferSource, "AES-GCM", false, ["encrypt"]);
    const ct = new Uint8Array(await crypto.subtle.encrypt({ name: "AES-GCM", iv, tagLength: 128 }, k, utf8(plain) as BufferSource));
    const all = new Uint8Array(12 + ct.length);
    all.set(iv); all.set(ct, 12);
    return urlSafe(all);
  }

  private async open(key: Uint8Array, b64: string): Promise<string | null> {
    try {
      const raw = b64decode(b64);
      const k = await crypto.subtle.importKey("raw", key as BufferSource, "AES-GCM", false, ["decrypt"]);
      return unutf8(new Uint8Array(await crypto.subtle.decrypt({ name: "AES-GCM", iv: raw.slice(0, 12), tagLength: 128 }, k, raw.slice(12))));
    } catch { return null; }
  }

  private async send(p: Peer, note: J) {
    note.from = this.core.profile.uid;
    note.ts = nowMs();
    const sealed = await this.seal(p.key, JSON.stringify(note));
    const id = urlSafe(crypto.getRandomValues(new Uint8Array(6)));
    const chunks: string[] = [];
    for (let i = 0; i < sealed.length; i += 2800) chunks.push(sealed.slice(i, i + 2800));
    try {
      for (let i = 0; i < chunks.length; i++) {
        const ok = await this.post(p.topic, JSON.stringify({ m: id, n: chunks.length, i, d: chunks[i] }));
        if (!ok) throw new Error("post");
      }
    } catch { this.status.value = "can't reach the message board"; }
  }

  /* ---- subscribe (server-sent events over fetch) ---- */

  private stopSub() { this.topicList = ""; this.subGen++; this.abort?.abort(); this.abort = null; this.subKey = ""; }

  private async ensureSub(fl: Peer[]) {
    this.peers = new Map(fl.map((p) => [p.topic, p]));
    this.inbox = this.core.settings.state.value.findMe !== "nobody" ? await inboxTopic(this.core.profile.uid) : "";
    const all = [...fl.map((p) => p.topic), ...(this.inbox ? [this.inbox] : [])];
    if (all.length === 0) { this.status.value = "nobody to look for yet"; this.stopSub(); return; }
    const topics = [...all].sort().join(",");
    this.topicList = topics;
    const key = this.baseUrl() + "|" + topics;
    if (key === this.subKey && this.abort) return;
    this.stopSub();
    this.subKey = key;
    const gen = ++this.subGen;
    const base = this.baseUrl();
    const ac = new AbortController();
    this.abort = ac;
    void (async () => {
      let wait = 3000;
      while (gen === this.subGen) {
        try {
          const since = this.lastEventTime > 0 ? String(this.lastEventTime) : "4m";
          const r = await fetch(`${base}/${topics}/sse?since=${since}`, { signal: ac.signal });
          if (!r.ok || !r.body) throw new Error("HTTP " + r.status);
          if (gen === this.subGen) this.status.value = this.peers.size === 0 ? "listening for friend requests" : `listening for ${this.peers.size} friend(s)`;
          wait = 3000;
          const reader = r.body.getReader();
          const dec = new TextDecoder();
          let buf = "";
          while (gen === this.subGen) {
            const { done, value } = await reader.read();
            if (done) break;
            buf += dec.decode(value, { stream: true });
            let nl: number;
            while ((nl = buf.indexOf("\n")) >= 0) {
              const line = buf.slice(0, nl).trim();
              buf = buf.slice(nl + 1);
              if (!line.startsWith("data:")) continue;
              let j: J;
              try { j = JSON.parse(line.slice(5).trim()); } catch { continue; }
              if (str(j, "event") !== "message") continue;
              const tm = lng(j, "time");
              if (tm > this.lastEventTime) this.lastEventTime = tm;
              void this.onEvent(str(j, "id"), str(j, "topic"), str(j, "message"));
            }
          }
        } catch { if (gen === this.subGen) this.status.value = "can't reach the message board"; }
        if (gen !== this.subGen) break;
        await new Promise((res) => setTimeout(res, wait));
        wait = Math.min(wait * 2, 60_000);
      }
    })();
  }

  /* ---- receive ---- */

  private async onEvent(evId: string, topic: string, message: string) {
    if (evId) {
      if (this.seenIds.includes(evId)) return;
      this.seenIds.push(evId);
      if (this.seenIds.length > 400) this.seenIds.shift();
    }
    if (this.inbox && topic === this.inbox) { void this.disc.onInbox(message); return; }
    const p = this.peers.get(topic);
    if (!p) return;
    let w: J;
    try { w = JSON.parse(message); } catch { return; }
    const m = str(w, "m"), n = typeof w.n === "number" ? w.n : 1, i = typeof w.i === "number" ? w.i : 0;
    if (n < 1 || n > 8 || i < 0 || i >= n) return;
    let arr = this.partial.get(m);
    if (!arr) { arr = new Array(n).fill(null); this.partial.set(m, arr); }
    if (arr.length !== n) return;
    arr[i] = str(w, "d");
    if (arr.some((x) => x == null)) {
      if (this.partial.size > 40) this.partial.delete(this.partial.keys().next().value as string);
      return;
    }
    this.partial.delete(m);
    const plain = await this.open(p.key, arr.join(""));
    if (!plain) return;
    let note: J;
    try { note = JSON.parse(plain); } catch { return; }
    if (str(note, "from") !== p.uid) return;
    if (Math.abs(nowMs() - lng(note, "ts")) > 4 * 60_000) return;
    if (this.core.connected.value.has(p.uid)) return;
    const me = this.core.profile.uid;
    const once = async (code: string) => {
      const h = hex(await sha256(code)).slice(0, 24);
      if (this.handled.includes(h)) return false;
      this.handled.push(h);
      if (this.handled.length > 200) this.handled.shift();
      return true;
    };
    switch (str(note, "t")) {
      case "knock": if (me < p.uid) void this.offerTo(p); break;
      case "offer": {
        const code = str(note, "code");
        if (me > p.uid && str((await unpack(code)) ?? {}, "p") === "dm" && (await once(code))) {
          const res = await this.core.handleCode(code, false);
          if (res.kind === "answer") void this.send(p, { t: "answer", code: res.code });
        }
        break;
      }
      case "answer": {
        const code = str(note, "code");
        if (me < p.uid && (await once(code))) await this.core.handleCode(code, true);
        break;
      }
    }
  }
}
