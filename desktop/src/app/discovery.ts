import { Avatar, avatarFrom, avatarJson, Contact } from "../core/models";
import { Docs } from "../core/store";
import { Identity, uidOf } from "../core/identity";
import { Flow, J, arr, hex, later, lng, nowMs, obj, objects, sha256, str, utf8 } from "../core/util";
import type { Card } from "./types";
import type { Rendezvous } from "./rendezvous";
import type { AppCore } from "./core";

export interface FriendRequest { card: Card; at: number }

export const userTopic = async (username: string) =>
  "homit-u-" + hex(await sha256("homit-user:" + username.trim().replace(/^@+/, "").toLowerCase())).slice(0, 32);
export const inboxTopic = async (uid: string) => "homit-i-" + hex(await sha256("homit-inbox:" + uid)).slice(0, 32);

const payload = (uid: string, user: string, name: string, ts: number) => utf8(`homit-card|${uid}|${user}|${name}|${ts}`);

/**
 * Finding people without scanning anything and without a server of ours.
 * Port of Discovery.kt (same topic names, same signed card format).
 */
export class Discovery {
  readonly requests = new Flow<FriendRequest[]>([]);
  readonly results = new Flow<Card[]>([]);
  readonly searching = new Flow(false);
  readonly searchNote = new Flow("");
  rv!: Rendezvous;

  constructor(private core: AppCore) {}

  async init() {
    const j = Docs.read("requests.json");
    for (const r of objects(arr(j ?? {}, "r"))) {
      const c = await this.cardOf(obj(r, "card"), true);
      if (c) this.requests.value = [...this.requests.value, { card: c, at: lng(r, "at") }];
    }
    later(8000, () => void this.publishLoop());
  }

  private saveRequests() {
    Docs.write("requests.json", { r: this.requests.value.map((r) => ({ card: this.cardJson(r.card), at: r.at })) });
  }

  private cardJson(c: Card): J {
    const j: J = { uid: c.uid, pub: c.pub, user: c.username, name: c.name, av: c.avatar, ts: c.ts };
    if (c.signed) j.sig = c.sig;
    return j;
  }

  /** My own card, freshly signed. */
  async myCard(): Promise<J | null> {
    const p = this.core.profile.profile.value;
    if (!p) return null;
    const ts = nowMs();
    const name = p.displayName.slice(0, 60);
    const sig = await this.core.profile.identity.sign(payload(this.core.profile.uid, p.username, name, ts));
    return { uid: this.core.profile.uid, pub: this.core.profile.identity.pubB64, user: p.username, name, av: avatarJson(p.avatar), ts, sig };
  }

  /** Parse and verify a card. With [needSig] false (a pasted link) the key is taken on trust. */
  private async cardOf(j: J | null, needSig: boolean): Promise<Card | null> {
    if (!j) return null;
    const pub = str(j, "pub"), uid = str(j, "uid");
    if (pub.length < 40 || pub.length > 300 || uid !== (await uidOf(pub))) return null;
    const user = str(j, "user").slice(0, 40), name = str(j, "name").slice(0, 60);
    const ts = lng(j, "ts"), sig = str(j, "sig");
    if (needSig && !(await Identity.verify(pub, payload(uid, user, name, ts), sig))) return null;
    return { uid, pub, username: user, name, avatar: obj(j, "av") ?? avatarJson(avatarFrom(null)), ts, signed: needSig, sig };
  }

  /* ---- publishing ---- */

  private async publishLoop() {
    try { await this.publishCard(); } catch { /* ignore */ }
    later(25 * 60_000, () => void this.publishLoop());
  }

  async publishCard() {
    const st = this.core.settings.state.value;
    if (!st.reconnect || st.findMe !== "everyone") return;
    const c = await this.myCard();
    if (!c) return;
    await this.rv.post(await userTopic(str(c, "user")), JSON.stringify(c));
  }

  /** Called when the profile changes so searches see the new name quickly. */
  profileChanged() { later(1500, () => void this.publishCard()); }

  /* ---- link ---- */

  myLink(): string | null {
    const p = this.core.profile.profile.value;
    if (!p) return null;
    return "waveroom://add?k=" + encodeURIComponent(this.core.profile.identity.pubB64) + "&u=" + encodeURIComponent(p.username) + "&n=" + encodeURIComponent(p.displayName);
  }

  async cardFromLink(text: string): Promise<Card | null> {
    try {
      const i = text.indexOf("waveroom://add");
      if (i < 0) return null;
      const raw = text.slice(i).split(/\s/)[0];
      const q = new URLSearchParams(raw.slice(raw.indexOf("?") + 1));
      const pub = q.get("k") ?? "";
      return await this.cardOf({ uid: await uidOf(pub), pub, user: q.get("u") ?? "", name: q.get("n") ?? "" }, false);
    } catch { return null; }
  }

  /* ---- search ---- */

  async search(query: string) {
    const q = query.trim();
    if (!q) return;
    this.results.value = [];
    this.searchNote.value = "";
    const lk = await this.cardFromLink(q);
    if (lk) { this.results.value = [lk]; return; }
    const name = q.replace(/^@+/, "").toLowerCase();
    if (name.length < 3) { this.searchNote.value = "Type at least 3 letters of the @username."; return; }
    this.searching.value = true;
    const found = new Map<string, Card>();
    let failed = false;
    try {
      for (const line of await this.rv.poll(await userTopic(name))) {
        let parsed: J | null = null;
        try { parsed = JSON.parse(str(line, "message")); } catch { continue; }
        const c = await this.cardOf(parsed, true);
        if (!c || c.username.toLowerCase() !== name || c.uid === this.core.profile.uid) continue;
        const old = found.get(c.uid);
        if (!old || old.ts < c.ts) found.set(c.uid, c);
      }
    } catch { failed = true; }
    this.searching.value = false;
    this.results.value = [...found.values()].sort((a, b) => b.ts - a.ts);
    this.searchNote.value = failed ? "Couldn't reach the message board. Check your connection, or ask them for their link."
      : found.size === 0 ? "Nobody found. They may be offline for a while, or hidden. Ask for their link." : "";
  }

  /* ---- requests ---- */

  private toContact(c: Card): Contact {
    return { uid: c.uid, username: c.username, displayName: c.name, avatar: avatarFrom(c.avatar) as Avatar, bio: "", pronouns: "", pub: c.pub, lastSeen: 0, firstSeen: nowMs(), pair: "" };
  }

  async sendRequest(card: Card) {
    const core = this.core;
    if (card.uid === core.profile.uid) return;
    if (core.social.blocked.value.includes(card.uid)) { core.toast("You blocked this person."); return; }
    if (!core.social.contact(card.uid)) core.social.upsert(this.toContact(card));
    const pair = await core.pairFor(card.uid, card.pub);
    if (pair) core.social.setPair(card.uid, pair);
    const me = await this.myCard();
    if (!me) return;
    const ok = await this.rv.post(await inboxTopic(card.uid), JSON.stringify({ t: "req", to: card.uid, card: me, ts: nowMs() }));
    core.toast(ok ? `Request sent to ${card.name}` : "Couldn't reach the message board. Try again in a moment.");
    this.rv.kick();
  }

  /** A message arrived in my inbox. */
  async onInbox(message: string) {
    const core = this.core;
    if (core.settings.state.value.findMe === "nobody") return;
    let j: J;
    try { j = JSON.parse(message); } catch { return; }
    if (str(j, "t") !== "req" || str(j, "to") !== core.profile.uid) return;
    if (Math.abs(nowMs() - lng(j, "ts")) > 13 * 3600_000) return;
    const c = await this.cardOf(obj(j, "card"), true);
    if (!c || c.uid === core.profile.uid || core.social.blocked.value.includes(c.uid)) return;
    const known = core.social.contact(c.uid);
    if (known && known.pair) {
      // they asked, and I had already asked them (or we are friends): that is a yes
      if (await core.pairFor(c.uid, c.pub)) this.rv.kick();
      return;
    }
    if (this.requests.value.some((r) => r.card.uid === c.uid)) return;
    this.requests.value = [...this.requests.value, { card: c, at: nowMs() }].slice(-30);
    this.saveRequests();
    core.toast(`${c.name} wants to connect`);
    core.sfx.play("join");
  }

  async accept(r: FriendRequest) {
    const c = r.card;
    this.core.social.upsert(this.toContact(c));
    const pair = await this.core.pairFor(c.uid, c.pub);
    if (pair) this.core.social.setPair(c.uid, pair);
    this.requests.value = this.requests.value.filter((x) => x.card.uid !== c.uid);
    this.saveRequests();
    this.core.toast(`${c.name} added. Connecting…`);
    this.rv.kick();
  }

  decline(r: FriendRequest) {
    this.requests.value = this.requests.value.filter((x) => x.card.uid !== r.card.uid);
    this.saveRequests();
  }
}
