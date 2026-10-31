import { J, Flow, Ids, Timer, arr, b64decode, b64encode, cancelLater, int, later, nowMs, obj, parseJson, str, strings, utf8 } from "../core/util";
import { Identity } from "../core/identity";
import { avatarFrom, avatarJson, Contact } from "../core/models";
import type { ProfileStore, SocialStore } from "../core/store";
import { LinkListener, PeerLink, RtcConfigSource } from "./peerlink";
import { filterCandidates, pack } from "./sdp";

export interface MeshHandler {
  /** A link finished the signed handshake: [link.uid] is now cryptographically verified. */
  onLinkAuthed(link: PeerLink): void;
  onLinkGone(link: PeerLink, reason: string): void;
  onLinkState(link: PeerLink, state: string): void;
  /** [link] is null when the message was relayed through another member. */
  onMessage(fromSid: string, msg: J, link: PeerLink | null): void;
  onTrack(link: PeerLink, kind: string): void;
}

/**
 * The serverless network: a set of direct WebRTC links to other people. There is
 * no signalling server. The first link to anyone is created by exchanging a code
 * (QR / text / LAN socket); every further link is negotiated *through existing
 * links* (trickle ICE relayed by a member both sides already trust).
 * Port of net/Mesh.kt (wire format identical).
 */
export class Mesh implements LinkListener {
  readonly mySid = Ids.sid();
  handler: MeshHandler | null = null;

  private links = new Map<string, PeerLink>();
  private pending = new Map<string, PeerLink>();        // invites we issued, by invite id
  private route = new Map<string, string>();            // far sid -> neighbour sid that can reach it
  private lastRx = new Map<string, number>();
  private restarts = new Map<string, number>();
  private dropTimers = new Map<string, Timer>();
  private beat: Timer = null;
  hintProvider: ((sid: string) => string | null) | null = null;
  lanHint: (() => string[]) | null = null;
  /** Plain-language status of the most recent first-contact attempt (shown when things go wrong). */
  readonly report = new Flow("");

  constructor(private rtc: RtcConfigSource, private profile: ProfileStore, private social: SocialStore) {}

  onDiag(link: PeerLink) {
    if (link.authed && link.state === "connected") return;
    this.report.value = link.report();
  }

  onTrack(link: PeerLink, kind: string) { this.handler?.onTrack(link, kind); }

  link(sid: string): PeerLink | undefined { return this.links.get(sid); }
  authedLinks(): PeerLink[] { return [...this.links.values()].filter((l) => l.authed); }
  linkForUid(uid: string): PeerLink | undefined { return [...this.links.values()].find((l) => l.authed && l.uid === uid); }
  isConnectedTo(uid: string) { return !!this.linkForUid(uid); }

  start() {
    if (this.beat) return;
    this.beat = later(4000, () => this.heartbeat());
  }

  stop() {
    cancelLater(this.beat); this.beat = null;
    for (const l of [...this.links.values()]) l.close();
    for (const l of [...this.pending.values()]) l.close();
    this.links.clear(); this.pending.clear(); this.route.clear(); this.lastRx.clear(); this.restarts.clear();
    for (const r of this.dropTimers.values()) cancelLater(r);
    this.dropTimers.clear();
  }

  /* ======================================================================
   * Codes (first contact)
   * ==================================================================== */

  /** Issue an invite code. [preview] is shown to whoever opens it (room name etc.). */
  async createInvite(purpose: string, roomId: string, preview: J | null): Promise<{ id: string; code: string | null }> {
    const id = Ids.rand(8);
    const l = new PeerLink(this.rtc, "pending-" + id, true, false, this);
    l.purpose = purpose;
    l.joinRoomId = roomId;
    l.inviteId = id;
    if (!l.start()) return { id, code: null };
    this.pending.set(id, l);
    if (!(await l.createOffer(false))) {
      this.pending.delete(id); l.close();
      return { id, code: null };
    }
    await new Promise<void>((res) => l.whenGathered(6000, res));
    const sdp = l.localSdp();
    if (!sdp) {
      this.pending.delete(id); l.close();
      return { id, code: null };
    }
    const j: J = {
      k: "o", i: id, s: this.mySid, u: this.profile.uid, n: this.profile.profile.value?.displayName ?? "",
      p: purpose, r: roomId, d: filterCandidates(sdp)
    };
    const hint = this.lanHint?.() ?? [];
    if (hint.length) j.l = hint;
    if (preview) j.v = preview;
    // an unanswered invite expires after 10 minutes
    later(600_000, () => this.expireInvite(id));
    return { id, code: await pack(j) };
  }

  private expireInvite(id: string) {
    const l = this.pending.get(id);
    if (!l) return;
    this.pending.delete(id);
    if (l.state !== "connected") l.close(); else this.links.set(l.remoteSid, l);
  }

  cancelInvite(id: string) {
    const l = this.pending.get(id);
    if (!l) return;
    this.pending.delete(id);
    if (l.remoteSid.startsWith("pending-")) l.close();
  }

  /** Accept somebody's invite; resolves with our answer code (to hand back) or null. */
  async acceptInvite(code: J): Promise<{ answer: string | null; remoteSid: string }> {
    if (str(code, "k") !== "o") return { answer: null, remoteSid: "" };
    const remote = str(code, "s");
    if (!remote || remote === this.mySid) return { answer: null, remoteSid: "" };
    const old = this.links.get(remote);
    if (old && old.authed) return { answer: null, remoteSid: remote };
    old?.close();
    const l = new PeerLink(this.rtc, remote, false, false, this);
    l.purpose = str(code, "p", "room");
    l.joinRoomId = str(code, "r");
    l.inviteId = str(code, "i");
    if (!l.start()) return { answer: null, remoteSid: remote };
    this.links.set(remote, l);
    if (!(await l.acceptOffer(str(code, "d")))) {
      this.links.delete(remote); l.close();
      return { answer: null, remoteSid: remote };
    }
    await new Promise<void>((res) => l.whenGathered(6000, res));
    const sdp = l.localSdp();
    if (!sdp) {
      this.links.delete(remote); l.close();
      return { answer: null, remoteSid: remote };
    }
    const j: J = { k: "a", i: l.inviteId, s: this.mySid, u: this.profile.uid, n: this.profile.profile.value?.displayName ?? "", d: filterCandidates(sdp) };
    this.armWatchdog(l, 11 * 60_000);
    return { answer: await pack(j), remoteSid: remote };
  }

  /** The inviter applies the answer code. Returns false if the invite is unknown/expired. */
  async applyAnswer(code: J): Promise<boolean> {
    if (str(code, "k") !== "a") return false;
    const l = this.pending.get(str(code, "i"));
    if (!l) return false;
    const sid = str(code, "s");
    if (!sid || sid === this.mySid) return false;
    if (this.links.get(sid)?.authed) return false;
    if (!(await l.acceptAnswer(str(code, "d")))) return false;
    this.pending.delete(l.inviteId);
    l.remoteSid = sid;
    const old = this.links.get(sid);
    if (old) { this.links.delete(sid); old.close(); }
    this.links.set(sid, l);
    this.armWatchdog(l, 60_000);
    return true;
  }

  /* ======================================================================
   * Relayed (trickle) links
   * ==================================================================== */

  /** Make sure a direct link to [sid] exists or is being negotiated through the mesh. */
  connectTo(sid: string) {
    if (sid === this.mySid || this.links.has(sid)) return;
    const offerer = this.mySid < sid;
    const l = new PeerLink(this.rtc, sid, offerer, true, this);
    if (!l.start()) return;
    this.links.set(sid, l);
    this.armWatchdog(l);
    if (offerer) void l.createOffer(false).then((ok) => { if (!ok) this.dropLink(l, "offer failed"); });
  }

  private async onSig(from: string, d: J) {
    const desc = obj(d, "desc");
    const cand = obj(d, "cand");
    let l = this.links.get(from);
    if (desc) {
      const type = str(desc, "type");
      const sdp = str(desc, "sdp");
      if (type === "offer") {
        if (l && l.offerer && !l.authed && l.state !== "connected") {
          // glare: both sides offered. The smaller sid keeps offering, the other yields.
          if (this.mySid < from) return;
          l.close(); this.links.delete(from); l = undefined;
        }
        if (!l) {
          const nl = new PeerLink(this.rtc, from, false, true, this);
          if (!nl.start()) return;
          this.links.set(from, nl);
          this.armWatchdog(nl);
          l = nl;
        }
        const link = l;
        if (!(await link.acceptOffer(sdp))) this.dropLink(link, "answer failed");
      } else if (type === "answer") {
        if (l && !(await l.acceptAnswer(sdp))) console.warn("bad answer from", from);
      }
    } else if (cand) {
      let link = l;
      if (!link) {
        // candidate raced ahead of its offer
        const nl = new PeerLink(this.rtc, from, false, true, this);
        if (!nl.start()) return;
        this.links.set(from, nl);
        this.armWatchdog(nl);
        link = nl;
      }
      link.addRemoteCandidate(str(cand, "mid"), int(cand, "idx"), str(cand, "sdp"));
    }
  }

  /* ======================================================================
   * LinkListener
   * ==================================================================== */

  onLocalSdp(link: PeerLink, type: string, sdp: string) {
    this.sendTo(link.remoteSid, { t: "sig", d: { desc: { type, sdp } } });
  }

  onLocalCandidate(link: PeerLink, mid: string, index: number, sdp: string) {
    if (sdp.includes(" fe80:")) return;
    this.sendTo(link.remoteSid, { t: "sig", d: { cand: { mid, idx: index, sdp } } });
  }

  onChannelOpen(link: PeerLink) {
    this.lastRx.set(link.remoteSid, nowMs());
    if (!link.helloSent) {
      link.helloSent = true;
      this.sendHello(link);
    }
  }

  onState(link: PeerLink, state: string) {
    this.handler?.onLinkState(link, state);
    if (state !== "connected") this.report.value = link.report();
    switch (state) {
      case "connected":
        cancelLater(this.dropTimers.get(link.remoteSid)); this.dropTimers.delete(link.remoteSid);
        this.restarts.delete(link.remoteSid);
        break;
      case "disconnected": this.scheduleDrop(link, 7000); break;
      case "failed": {
        const n = this.restarts.get(link.remoteSid) ?? 0;
        if (link.offerer && n < 2) {
          this.restarts.set(link.remoteSid, n + 1);
          void link.restartIce();
          this.scheduleDrop(link, 10000);
        } else this.dropLink(link, "ice failed");
        break;
      }
    }
  }

  private scheduleDrop(link: PeerLink, ms: number) {
    if (this.dropTimers.has(link.remoteSid)) return;
    this.dropTimers.set(link.remoteSid, later(ms, () => {
      this.dropTimers.delete(link.remoteSid);
      if (this.links.get(link.remoteSid) === link && link.state !== "connected") {
        if (link.offerer && (this.restarts.get(link.remoteSid) ?? 0) < 2 && link.state === "disconnected") {
          this.restarts.set(link.remoteSid, (this.restarts.get(link.remoteSid) ?? 0) + 1);
          void link.restartIce();
          this.scheduleDrop(link, 8000);
        } else this.dropLink(link, "connection lost");
      }
    }));
  }

  private armWatchdog(link: PeerLink, ms = 25_000) {
    later(ms, () => {
      if (this.links.get(link.remoteSid) === link && (link.state !== "connected" || !link.authed)) this.dropLink(link, link.state !== "connected" ? "no path" : "no handshake");
    });
  }

  dropLink(link: PeerLink, reason: string) {
    if (!link.authed && !link.trickle) this.report.value = link.report() + "\nStopped: " + reason;
    if (this.links.get(link.remoteSid) === link) this.links.delete(link.remoteSid);
    this.pending.delete(link.inviteId);
    cancelLater(this.dropTimers.get(link.remoteSid)); this.dropTimers.delete(link.remoteSid);
    link.close();
    this.lastRx.delete(link.remoteSid);
    for (const [k, v] of [...this.route]) if (v === link.remoteSid) this.route.delete(k);
    this.handler?.onLinkGone(link, reason);
  }

  closeLink(sid: string, reason = "closed") {
    const l = this.links.get(sid);
    if (l) this.dropLink(l, reason);
  }

  /* ======================================================================
   * Handshake: hello + signed auth
   * ==================================================================== */

  private sendHello(link: PeerLink) {
    const p = this.profile.profile.value;
    if (!p) return;
    const h: J = {
      t: "hello", v: 1, sid: this.mySid, uid: this.profile.uid, pub: this.profile.identity.pubB64,
      name: p.displayName, user: p.username, av: avatarJson(p.avatar), bio: p.bio, pron: p.pronouns, nonce: link.myNonce,
      purpose: link.purpose, room: link.joinRoomId
    };
    if (this.profile.photo) h.photo = b64encode(this.profile.photo);
    link.send(JSON.stringify(h));
  }

  private authPayload(nonce: string, signerSid: string, verifierSid: string): Uint8Array {
    return utf8(`wr-auth|${nonce}|${signerSid}|${verifierSid}`);
  }

  private async onHello(link: PeerLink, m: J) {
    if (link.authed || link.remotePub) return;
    const pub = str(m, "pub"), uid = str(m, "uid"), sid = str(m, "sid");
    // claim the slot synchronously so a duplicate hello is ignored while we check the key
    link.remotePub = pub;
    if (!pub || !sid || uid !== (await Identity_uidOf(pub))) { link.remotePub = ""; this.dropLink(link, "bad hello"); return; }
    if (link.remoteSid !== sid && !link.remoteSid.startsWith("pending-")) { this.dropLink(link, "sid mismatch"); return; }
    const sameAccount = uid === this.profile.uid;
    if (sameAccount && str(m, "purpose") !== "sync") { this.dropLink(link, "self"); return; }
    if (this.social.isBlocked(uid)) { this.dropLink(link, "blocked"); return; }
    link.uid = uid;
    link.remoteNonce = str(m, "nonce");
    if (str(m, "purpose")) link.purpose = str(m, "purpose");
    if (str(m, "room")) link.joinRoomId = str(m, "room");
    link.hello = m;   // the profile is only saved once the signature checks out
    const sig = await this.profile.identity.sign(this.authPayload(link.remoteNonce, this.mySid, link.remoteSid));
    link.send(JSON.stringify({ t: "auth", sig }));
  }

  private async onAuth(link: PeerLink, m: J) {
    if (!link.remotePub || link.authed) return;
    const ok = await Identity.verify(link.remotePub, this.authPayload(link.myNonce, link.remoteSid, this.mySid), str(m, "sig"));
    if (!ok) { this.dropLink(link, "auth failed"); return; }
    if (link.authed) return;
    link.authed = true;
    const h = link.hello;
    if (h && link.uid !== this.profile.uid) {
      const c: Contact = {
        uid: link.uid, username: str(h, "user").slice(0, 40), displayName: (str(h, "name") || str(h, "user")).slice(0, 60),
        avatar: avatarFrom(obj(h, "av")), bio: str(h, "bio").slice(0, 400), pronouns: str(h, "pron").slice(0, 40), pub: link.remotePub,
        lastSeen: nowMs(), firstSeen: nowMs(), pair: ""
      };
      this.social.upsert(c);
      const ph = str(h, "photo");
      if (ph && ph.length < 700_000) { try { this.social.setPhoto(link.uid, b64decode(ph)); } catch { /* ignore */ } }
    }
    link.hello = null;
    if (link.uid !== this.profile.uid) this.social.touchSeen(link.uid);
    // two links to the same person: both sides keep the one whose offerer has the lower id
    for (const other of [...this.links.values()]) {
      if (other !== link && other.uid === link.uid && other.authed && (other.purpose === "sync") === (link.purpose === "sync")) {
        const mine = link.offerer ? this.profile.uid : link.uid;
        const theirs = other.offerer ? this.profile.uid : other.uid;
        const keepNew = mine !== theirs ? mine < theirs : other.createdAt < link.createdAt;
        if (keepNew) { this.links.delete(other.remoteSid); other.close(); }
        else { this.links.delete(link.remoteSid); this.pending.delete(link.inviteId); link.close(); return; }
      }
    }
    this.handler?.onLinkAuthed(link);
  }

  /* ======================================================================
   * Messaging
   * ==================================================================== */

  onMessage(link: PeerLink, text: string, _reliable: boolean) {
    this.lastRx.set(link.remoteSid, nowMs());
    link.queue = link.queue.then(() => this.process(link, text)).catch((e) => console.warn("message failed", e));
  }

  private async process(link: PeerLink, text: string) {
    const m = parseJson(text);
    if (!m) return;
    switch (str(m, "t")) {
      case "hello": await this.onHello(link, m); break;
      case "auth": await this.onAuth(link, m); break;
      case "p": break;
      case "fwd": if (link.authed) await this.onForward(link, m); break;
      default:
        if (link.authed) {
          if (str(m, "t") === "sig") { const d = obj(m, "d"); if (d) await this.onSig(link.remoteSid, d); }
          else this.handler?.onMessage(link.remoteSid, m, link);
        }
    }
  }

  private async onForward(via: PeerLink, m: J) {
    const from = str(m, "from"), to = str(m, "to");
    const inner = obj(m, "m");
    if (!inner || !from || from === this.mySid) return;
    // someone I am directly linked to talks to me directly; a relayed copy claiming to be them is a forgery
    if (from !== via.remoteSid && this.links.get(from)?.authed) return;
    if (from !== via.remoteSid) this.route.set(from, via.remoteSid);
    if (to === this.mySid) {
      if (str(inner, "t") === "sig") { const d = obj(inner, "d"); if (d) await this.onSig(from, d); }
      else {
        const dl = this.links.get(from);
        this.handler?.onMessage(from, inner, dl && dl.authed ? dl : null);
      }
      return;
    }
    const ttl = Math.min(int(m, "ttl", 0), 3);
    if (ttl <= 0) return;
    m.ttl = ttl - 1;
    const direct = this.links.get(to);
    if (direct && direct.authed) direct.send(JSON.stringify(m));
    else for (const l of this.authedLinks()) if (l !== via) l.send(JSON.stringify(m));
  }

  /** Send to a specific member: directly if linked, otherwise relayed through the mesh. */
  sendTo(sid: string, msg: J, reliable = true): boolean {
    const direct = this.links.get(sid);
    if (direct && (direct.authed || str(msg, "t") === "sig") && direct.isOpen) return direct.send(JSON.stringify(msg), reliable);
    const env = JSON.stringify({ t: "fwd", from: this.mySid, to: sid, ttl: 3, m: msg });
    const viaSid = this.route.get(sid);
    let hop = viaSid ? this.links.get(viaSid) : undefined;
    if (!hop || !hop.authed) {
      const h = this.hintProvider?.(sid);
      hop = h ? this.links.get(h) : undefined;
      if (hop && !hop.authed) hop = undefined;
    }
    if (hop) return hop.send(env);
    let sent = false;
    for (const l of this.authedLinks()) sent = l.send(env) || sent;
    return sent;
  }

  broadcast(msg: J, reliable = true, except: Set<string> = new Set()) {
    const s = JSON.stringify(msg);
    for (const l of this.authedLinks()) if (!except.has(l.remoteSid)) l.send(s, reliable);
  }

  /** Called by the room layer so relayed negotiation knows the route to far members. */
  noteRoute(farSid: string, viaSid: string) {
    if (farSid !== viaSid) this.route.set(farSid, viaSid);
  }

  private heartbeat() {
    const t = nowMs();
    for (const l of [...this.links.values()]) {
      if (!l.authed) continue;
      l.send('{"t":"p"}');
      const last = this.lastRx.get(l.remoteSid) ?? t;
      if (t - last > 15_000) this.dropLink(l, "timeout");
    }
    this.beat = later(4000, () => this.heartbeat());
  }
}

import { uidOf as Identity_uidOf } from "../core/identity";
