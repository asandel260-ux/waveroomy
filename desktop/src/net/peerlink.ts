import { J, Ids, Timer, cancelLater, later, nowMs } from "../core/util";
import { forOffer } from "./sdp";

export interface LinkListener {
  /** Trickle mode only: local description is set and ready to be sent. */
  onLocalSdp(link: PeerLink, type: string, sdp: string): void;
  onLocalCandidate(link: PeerLink, mid: string, index: number, sdp: string): void;
  onChannelOpen(link: PeerLink): void;
  onMessage(link: PeerLink, text: string, reliable: boolean): void;
  /** "connecting" | "connected" | "disconnected" | "failed" | "closed" */
  onState(link: PeerLink, state: string): void;
  onDiag(link: PeerLink): void;
  /** a remote audio / video track arrived or changed */
  onTrack(link: PeerLink, kind: string): void;
}

export interface RtcConfigSource { iceServers(): RTCIceServer[] }

const CHUNK = 12000;

/**
 * One WebRTC peer connection to one remote member: an Opus audio transceiver, a
 * video transceiver (used only while someone shares their screen), and two
 * pre-negotiated data channels (reliable "ctl", unreliable "fast"). Everything is
 * negotiated once, so starting/stopping a screen share never renegotiates.
 * Port of net/PeerLink.kt.
 */
export class PeerLink {
  uid = "";
  authed = false;
  hello: J | null = null;
  purpose = "room";            // "room" | "dm" | "sync"
  joinRoomId = "";
  myNonce = Ids.nonce();
  remoteNonce = "";
  remotePub = "";
  helloSent = false;
  inviteId = "";
  createdAt = nowMs();
  state = "new";
  lastRoomSig = 0;
  iceState = "new";
  readonly log: string[] = [];

  private pc: RTCPeerConnection | null = null;
  private ctl: RTCDataChannel | null = null;
  private fast: RTCDataChannel | null = null;
  private audioTx: RTCRtpTransceiver | null = null;
  private videoTx: RTCRtpTransceiver | null = null;
  private remoteSet = false;
  private pending: RTCIceCandidateInit[] = [];
  private gatherCb: (() => void) | null = null;
  private gatherTimeout: Timer = null;
  private closed = false;
  private localTypes: Record<string, number> = {};
  private remoteTypes: Record<string, number> = {};
  private remoteMdns = false;
  private partial = new Map<string, (string | null)[]>();
  /** messages are handled strictly in order, even though signature checks are asynchronous */
  queue: Promise<void> = Promise.resolve();

  constructor(private rtc: RtcConfigSource, public remoteSid: string, readonly offerer: boolean, readonly trickle: boolean, private listener: LinkListener) {}

  private note(s: string) {
    this.log.push(s);
    if (this.log.length > 40) this.log.shift();
  }

  private countTypes(sdp: string): Record<string, number> {
    const m: Record<string, number> = {};
    for (const l of sdp.split(/\r?\n/)) if (l.startsWith("a=candidate:")) {
      const i = l.indexOf(" typ ");
      if (i > 0) { const t = l.substring(i + 5).split(" ")[0]; m[t] = (m[t] ?? 0) + 1; }
      if (l.includes(".local ")) this.remoteMdns = true;
    }
    return m;
  }

  /** Human readable summary of how this link's connection attempt is going. */
  report(): string {
    const fmt = (m: Record<string, number>) => (Object.keys(m).length ? Object.entries(m).map(([k, v]) => `${v} ${k}`).join(", ") : "none");
    let sb = `Connection: ${this.state} · ICE: ${this.iceState}\n`;
    sb += `My candidates: ${fmt(this.localTypes)}\nTheir candidates: ${fmt(this.remoteTypes)}\n`;
    if (this.remoteMdns) sb += "Their address is hidden behind a .local name\n";
    const local = Object.keys(this.localTypes), remote = Object.keys(this.remoteTypes);
    if (this.state !== "connected") {
      let hint: string;
      if (remote.length === 0 && !this.log.some((l) => l.startsWith("remote set"))) hint = "Waiting for their reply to be applied.";
      else if (local.length === 0) hint = "This computer found no network address. Is it online?";
      else if (remote.length && remote.every((t) => t === "host") && local.every((t) => t === "host"))
        hint = "Both devices only shared local addresses. They must be on the same network, with no VPN and no 'AP isolation'. Turn on the relay helper in Settings to connect across networks.";
      else if (!remote.includes("relay") && !local.includes("relay"))
        hint = "Devices on different networks usually need the relay helper. Enable it in Settings → Network on both devices.";
      else hint = "Still negotiating…";
      sb += hint;
    }
    return sb.trim();
  }

  get isOpen(): boolean { return this.ctl?.readyState === "open"; }

  start(): boolean {
    let p: RTCPeerConnection;
    try {
      p = new RTCPeerConnection({ iceServers: this.rtc.iceServers(), bundlePolicy: "max-bundle", rtcpMuxPolicy: "require" });
    } catch (e) {
      console.warn("RTCPeerConnection failed", e);
      return false;
    }
    this.pc = p;
    p.oniceconnectionstatechange = () => {
      this.iceState = p.iceConnectionState;
      this.note("ice " + this.iceState);
      if (!this.closed) this.listener.onDiag(this);
    };
    p.onicecandidate = (ev) => {
      const c = ev.candidate;
      if (!c) return;
      const ty = / typ (\w+)/.exec(c.candidate)?.[1] ?? "?";
      this.localTypes[ty] = (this.localTypes[ty] ?? 0) + 1;
      if (this.trickle && !this.closed) this.listener.onLocalCandidate(this, c.sdpMid ?? "", c.sdpMLineIndex ?? 0, c.candidate);
    };
    p.onicegatheringstatechange = () => { if (p.iceGatheringState === "complete") this.fireGather(); };
    p.onconnectionstatechange = () => {
      const s = p.connectionState;
      if (s === "new" || this.closed) return;
      this.state = s;
      this.listener.onState(this, s);
    };
    p.ontrack = (ev) => { if (!this.closed) this.listener.onTrack(this, ev.track.kind); };

    this.ctl = p.createDataChannel("ctl", { negotiated: true, id: 0, ordered: true });
    this.fast = p.createDataChannel("fast", { negotiated: true, id: 1, ordered: false, maxRetransmits: 0 });
    this.watch(this.ctl, true);
    this.watch(this.fast, false);

    if (this.offerer) {
      this.audioTx = p.addTransceiver("audio", { direction: "sendrecv" });
      this.videoTx = p.addTransceiver("video", { direction: "sendrecv" });
    }
    return true;
  }

  private watch(dc: RTCDataChannel, reliable: boolean) {
    dc.onopen = () => { if (reliable && !this.closed) this.listener.onChannelOpen(this); };
    dc.onmessage = (ev) => {
      if (this.closed) return;
      if (typeof ev.data === "string") this.receiveFrame(ev.data, reliable);
      else if (ev.data instanceof ArrayBuffer) this.receiveFrame(new TextDecoder().decode(ev.data), reliable);
    };
  }

  /* ---- framing: long messages are split into chunks ---- */

  private receiveFrame(text: string, reliable: boolean) {
    if (text.length > 0 && text.charCodeAt(0) === 1) {
      const p1 = text.indexOf("|", 1);
      const p2 = text.indexOf("|", p1 + 1);
      const p3 = text.indexOf("|", p2 + 1);
      if (p1 < 0 || p2 < 0 || p3 < 0) return;
      const id = text.substring(1, p1);
      const i = parseInt(text.substring(p1 + 1, p2));
      const n = parseInt(text.substring(p2 + 1, p3));
      if (!Number.isFinite(i) || !Number.isFinite(n) || n <= 0 || n > 1000 || i < 0 || i >= n) return;
      let arr = this.partial.get(id);
      if (!arr) { arr = new Array(n).fill(null); this.partial.set(id, arr); }
      if (arr.length !== n) return;
      if (this.partial.size > 64) this.partial.delete(this.partial.keys().next().value as string);
      arr[i] = text.substring(p3 + 1);
      if (arr.every((x) => x != null)) {
        this.partial.delete(id);
        this.listener.onMessage(this, arr.join(""), reliable);
      }
      if (this.partial.size > 20) this.partial.clear();
      return;
    }
    this.listener.onMessage(this, text, reliable);
  }

  buffered(): number { return this.ctl?.bufferedAmount ?? 0; }

  /** Sends one JSON text. Returns false if the channel is not open. */
  send(text: string, reliable = true): boolean {
    const dc = reliable ? this.ctl : this.fast;
    if (!dc || dc.readyState !== "open") return false;
    try {
      if (text.length <= CHUNK) dc.send(text);
      else if (!reliable) return false;
      else {
        const id = Ids.rand(6);
        const n = Math.ceil(text.length / CHUNK);
        for (let i = 0; i < n; i++) dc.send(`\u0001${id}|${i}|${n}|${text.substring(i * CHUNK, Math.min(text.length, (i + 1) * CHUNK))}`);
      }
      return true;
    } catch (e) {
      console.warn("send failed", e);
      return false;
    }
  }

  /* ---- signalling ---- */

  async createOffer(iceRestart: boolean): Promise<boolean> {
    const p = this.pc;
    if (!p || this.closed) return false;
    try {
      const d = await p.createOffer({ iceRestart });
      if (this.closed) return false;
      const munged = forOffer(d.sdp ?? "");
      await p.setLocalDescription({ type: "offer", sdp: munged });
      if (this.trickle && !this.closed) this.listener.onLocalSdp(this, "offer", munged);
      return true;
    } catch (e) {
      console.warn("createOffer failed", e);
      return false;
    }
  }

  /** Apply a remote offer and produce our answer. */
  async acceptOffer(sdp: string): Promise<boolean> {
    const p = this.pc;
    if (!p) return false;
    this.remoteTypes = this.countTypes(sdp);
    this.note("remote set (offer): " + JSON.stringify(this.remoteTypes));
    try {
      await p.setRemoteDescription({ type: "offer", sdp });
      if (this.closed) return false;
      this.remoteSet = true;
      for (const tx of p.getTransceivers()) {
        try { tx.direction = "sendrecv"; } catch { /* ignore */ }
        const k = tx.receiver.track?.kind;
        if (k === "audio") this.audioTx = tx;
        if (k === "video") this.videoTx = tx;
      }
      await this.flushCandidates();
      const d = await p.createAnswer();
      if (this.closed) return false;
      await p.setLocalDescription(d);
      if (this.trickle && !this.closed) this.listener.onLocalSdp(this, "answer", d.sdp ?? "");
      return true;
    } catch (e) {
      console.warn("acceptOffer failed", e);
      return false;
    }
  }

  async acceptAnswer(sdp: string): Promise<boolean> {
    const p = this.pc;
    if (!p) return false;
    this.remoteTypes = this.countTypes(sdp);
    this.note("remote set (answer): " + JSON.stringify(this.remoteTypes));
    try {
      await p.setRemoteDescription({ type: "answer", sdp });
      this.remoteSet = true;
      await this.flushCandidates();
      return true;
    } catch (e) {
      console.warn("acceptAnswer failed", e);
      return false;
    }
  }

  addRemoteCandidate(mid: string, index: number, sdp: string) {
    const c: RTCIceCandidateInit = { candidate: sdp, sdpMid: mid || null, sdpMLineIndex: index };
    if (!this.remoteSet) this.pending.push(c);
    else void this.pc?.addIceCandidate(c).catch(() => {});
  }

  private async flushCandidates() {
    for (const c of this.pending) { try { await this.pc?.addIceCandidate(c); } catch { /* ignore */ } }
    this.pending = [];
  }

  /** Non-trickle: invoke [cb] once candidate gathering is complete (or after [timeoutMs]). */
  whenGathered(timeoutMs: number, cb: () => void) {
    if (this.pc?.iceGatheringState === "complete") return cb();
    this.gatherCb = cb;
    this.gatherTimeout = later(timeoutMs, () => this.fireGather());
  }

  private fireGather() {
    const cb = this.gatherCb;
    if (!cb) return;
    this.gatherCb = null;
    cancelLater(this.gatherTimeout);
    this.gatherTimeout = null;
    cb();
  }

  localSdp(): string | null { return this.pc?.localDescription?.sdp ?? null; }

  async restartIce(): Promise<boolean> {
    if (!this.trickle || !this.offerer) return false;
    return this.createOffer(true);
  }

  /* ---- media ---- */

  setSendAudio(track: MediaStreamTrack | null) {
    try { void this.audioTx?.sender.replaceTrack(track); } catch (e) { console.warn("setSendAudio", e); }
  }

  setSendVideo(track: MediaStreamTrack | null) {
    try { void this.videoTx?.sender.replaceTrack(track); } catch (e) { console.warn("setSendVideo", e); }
  }

  async tuneVideo(bitrateBps: number, fps: number) {
    try {
      const s = this.videoTx?.sender;
      if (!s) return;
      const params = s.getParameters();
      if (!params.encodings || params.encodings.length === 0) params.encodings = [{}];
      params.encodings[0].maxBitrate = bitrateBps;
      params.encodings[0].maxFramerate = fps;
      (params as any).degradationPreference = "maintain-resolution";
      await s.setParameters(params);
    } catch (e) {
      console.warn("tuneVideo", e);
    }
  }

  remoteAudio(): MediaStreamTrack | null {
    const t = this.audioTx?.receiver.track;
    return t && t.kind === "audio" ? t : null;
  }

  remoteVideo(): MediaStreamTrack | null {
    const t = this.videoTx?.receiver.track;
    return t && t.kind === "video" ? t : null;
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    cancelLater(this.gatherTimeout);
    try { this.ctl?.close(); this.fast?.close(); } catch { /* ignore */ }
    try { this.pc?.close(); } catch { /* ignore */ }
    this.pc = null; this.ctl = null; this.fast = null;
    this.state = "closed";
  }
}
