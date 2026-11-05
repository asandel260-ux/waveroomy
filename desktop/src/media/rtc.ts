import { Flow } from "../core/util";
import type { SettingsStore } from "../core/store";
import { WORKLET_SOURCE } from "./voicefx-worklet";

export interface FxPreset { id: string; name: string; desc: string }
export const FX_PRESETS: FxPreset[] = [
  { id: "natural", name: "Natural", desc: "Your voice with a gentle clean-up." },
  { id: "studio", name: "Studio", desc: "Compressed, de-rumbled, presence lift." },
  { id: "deep", name: "Deep", desc: "Lower pitch with added chest resonance." },
  { id: "bright", name: "Bright", desc: "Lifted clarity, a touch of air." },
  { id: "radio", name: "Radio", desc: "Broadcast band-pass and drive." },
  { id: "character", name: "Character", desc: "Raised pitch, animated tone." },
  { id: "robotic", name: "Robotic", desc: "Ring-modulated synthetic tone." },
  { id: "vintage", name: "Vintage", desc: "Narrow band, softly saturated, aged." }
];

/**
 * ICE servers, the microphone and the voice-effect chain.
 * mic -> (voice FX worklet) -> analyser (level meter) + MediaStreamDestination (sent to everyone).
 */
export class RtcEngine {
  /** RMS (0..1) of the current mic signal. */
  micLevel = 0;
  readonly micError = new Flow<string | null>(null);
  private ctx: AudioContext | null = null;
  private stream: MediaStream | null = null;
  private fxNode: AudioWorkletNode | null = null;
  private analyser: AnalyserNode | null = null;
  private dest: MediaStreamAudioDestinationNode | null = null;
  private buf = new Float32Array(1024);
  private starting: Promise<MediaStreamTrack | null> | null = null;
  private meter: ReturnType<typeof setInterval> | null = null;
  track: MediaStreamTrack | null = null;

  constructor(private settings: SettingsStore) {}

  iceServers(): RTCIceServer[] {
    const s = this.settings.state.value;
    const out: RTCIceServer[] = [];
    const stun: string[] = [];
    if (s.useStun) stun.push("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302", "stun:stun.cloudflare.com:3478", "stun:stun.nextcloud.com:443");
    for (const u of s.customStun.split(/[,\n ]/)) {
      const t = u.trim();
      if (t) stun.push(t.startsWith("stun:") || t.startsWith("stuns:") ? t : "stun:" + t);
    }
    if (stun.length) out.push({ urls: stun });
    if (s.useRelay && !s.turnUrl.trim()) {
      // last-resort relay so devices on different networks can still connect; it only sees encrypted packets
      out.push({ urls: ["turn:openrelay.metered.ca:80", "turn:openrelay.metered.ca:443", "turn:openrelay.metered.ca:443?transport=tcp", "turns:openrelay.metered.ca:443?transport=tcp"], username: "openrelayproject", credential: "openrelayproject" });
    }
    const turn = s.turnUrl.trim();
    if (turn) out.push({ urls: turn.split(/[,\n ]/).map((x) => x.trim()).filter(Boolean), username: s.turnUser, credential: s.turnPass });
    return out;
  }
  turnConfigured() { return this.settings.state.value.turnUrl.trim().length > 0; }

  /** Creates (once) the processed mic track. Resolves null when the microphone can't be opened. */
  ensureMic(): Promise<MediaStreamTrack | null> {
    if (this.track) return Promise.resolve(this.track);
    return (this.starting ??= this.openMic().finally(() => { this.starting = null; }));
  }

  private async openMic(): Promise<MediaStreamTrack | null> {
    try {
      const dev = this.settings.state.value.micDeviceId;
      this.stream = await navigator.mediaDevices.getUserMedia({
        audio: { deviceId: dev ? { exact: dev } : undefined, echoCancellation: true, noiseSuppression: true, autoGainControl: true, channelCount: 1 }
      });
      const ctx = (this.ctx ??= new AudioContext({ sampleRate: 48000, latencyHint: "interactive" }));
      if (ctx.state === "suspended") await ctx.resume();
      if (!this.fxNode) {
        const url = URL.createObjectURL(new Blob([WORKLET_SOURCE], { type: "text/javascript" }));
        await ctx.audioWorklet.addModule(url);
        URL.revokeObjectURL(url);
      }
      const src = ctx.createMediaStreamSource(this.stream);
      this.fxNode = new AudioWorkletNode(ctx, "voicefx", { numberOfInputs: 1, numberOfOutputs: 1, outputChannelCount: [1] });
      this.analyser = ctx.createAnalyser();
      this.analyser.fftSize = 1024;
      this.dest = ctx.createMediaStreamDestination();
      src.connect(this.fxNode);
      this.fxNode.connect(this.dest);
      this.fxNode.connect(this.analyser);
      this.applyFx();
      this.track = this.dest.stream.getAudioTracks()[0] ?? null;
      if (this.track) this.track.enabled = false;
      this.meter = setInterval(() => this.measure(), 40);
      this.micError.value = null;
      return this.track;
    } catch (e: any) {
      this.releaseMic();
      this.micError.value = e?.name === "NotAllowedError" ? "Microphone access was blocked." : "Couldn't open the microphone.";
      return null;
    }
  }

  private measure() {
    const a = this.analyser;
    if (!a || !this.track) { this.micLevel = 0; return; }
    a.getFloatTimeDomainData(this.buf);
    let sum = 0;
    for (let i = 0; i < this.buf.length; i++) sum += this.buf[i] * this.buf[i];
    this.micLevel = Math.sqrt(sum / this.buf.length);
  }

  releaseMic() {
    if (this.meter) clearInterval(this.meter);
    this.meter = null;
    this.stream?.getTracks().forEach((t) => t.stop());
    this.stream = null;
    this.track?.stop();
    this.track = null;
    try { this.fxNode?.disconnect(); } catch { /* ignore */ }
    this.fxNode = null; this.analyser = null; this.dest = null;
    this.micLevel = 0;
  }

  applyFx() {
    const s = this.settings.state.value;
    this.fxNode?.port.postMessage({ enabled: s.fxEnabled, preset: s.fxPreset, intensity: s.fxIntensity });
  }

  /** Switch microphones while live. */
  async reopenMic() {
    if (!this.track) return;
    const was = this.track.enabled;
    this.releaseMic();
    const t = await this.ensureMic();
    if (t) t.enabled = was;
  }
}
