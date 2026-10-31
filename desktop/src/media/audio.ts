import { canSpeak } from "../core/models";
import { Flow, Timer, cancelLater, later, nowMs } from "../core/util";
import type { PeerLink } from "../net/peerlink";
import type { AppCore } from "../app/core";

interface Sink { el: HTMLAudioElement; ctx: AudioContext; gain: GainNode; trackId: string }

/**
 * Everything about sound in a room: whether the mic is live, who we hear, per-person volume,
 * deafen, speaking detection, output device and push-to-talk.
 */
export class AudioController {
  readonly level = new Flow(0);
  readonly speakingSelf = new Flow(false);
  readonly pttHeld = new Flow(false);
  private running = false;
  private poll: Timer = null;
  private lastVoice = 0;
  private volumes = new Map<string, number>();
  private sinks = new Map<string, Sink>();
  private refreshing = false;
  private again = false;

  constructor(private core: AppCore) {}

  start() {
    if (this.running) return;
    this.running = true;
    this.poll = later(80, () => this.tick());
  }

  stop() {
    if (!this.running) return;
    this.running = false;
    cancelLater(this.poll); this.poll = null;
    this.core.rtc.releaseMic();
    for (const l of this.core.mesh.authedLinks()) l.setSendAudio(null);
    for (const s of this.sinks.values()) this.dropSink(s);
    this.sinks.clear();
    this.level.value = 0;
    this.speakingSelf.value = false;
    this.pttHeld.value = false;
  }

  private dropSink(s: Sink) {
    try { s.el.srcObject = null; s.el.remove(); } catch { /* ignore */ }
    try { void s.ctx.close(); } catch { /* ignore */ }
  }

  userVolume(uid: string): number { return this.volumes.get(uid) ?? 1; }
  setUserVolume(uid: string, v: number) { this.volumes.set(uid, Math.min(2, Math.max(0, v))); this.refresh(); }

  private wantsMic(): boolean { const me = this.core.session.me; return !!me && canSpeak(me.role); }
  private micLive(): boolean {
    const me = this.core.session.me;
    if (!me || !canSpeak(me.role) || me.muted) return false;
    return !this.core.settings.state.value.ptt || this.pttHeld.value;
  }

  setPtt(down: boolean) {
    if (this.pttHeld.value === down) return;
    this.pttHeld.value = down;
    this.refresh();
  }

  private inRoom(l: PeerLink) { return this.core.session.room?.participants.has(l.remoteSid) === true; }

  /** Recompute everything from the room state. Cheap; call after any relevant change. */
  refresh() {
    if (!this.running) return;
    if (this.refreshing) { this.again = true; return; }
    this.refreshing = true;
    void this.doRefresh().finally(() => {
      this.refreshing = false;
      if (this.again) { this.again = false; this.refresh(); }
    });
  }

  private async doRefresh() {
    if (this.wantsMic()) {
      const t = await this.core.rtc.ensureMic();
      if (!this.running) return;
      if (t) {
        t.enabled = this.micLive();
        for (const l of this.core.mesh.authedLinks()) l.setSendAudio(this.inRoom(l) ? t : null);
      } else this.core.toast(this.core.rtc.micError.value ?? "Couldn't open the microphone.");
    } else {
      for (const l of this.core.mesh.authedLinks()) l.setSendAudio(null);
      this.core.rtc.releaseMic();
      if (this.speakingSelf.value) this.setSpeaking(false);
    }
    for (const l of this.core.mesh.authedLinks()) this.applyRemote(l);
    this.prune();
  }

  attach(link: PeerLink) {
    if (!this.running || !link.authed) return;
    const t = this.core.rtc.track;
    link.setSendAudio(this.wantsMic() && this.inRoom(link) && t ? t : null);
    this.applyRemote(link);
    if (this.wantsMic() && !t) this.refresh();
  }

  private prune() {
    const live = new Set(this.core.mesh.authedLinks().map((l) => l.remoteSid));
    for (const [sid, s] of this.sinks) if (!live.has(sid)) { this.dropSink(s); this.sinks.delete(sid); }
  }

  private async applyRemote(link: PeerLink) {
    const s = this.core.session.room;
    const track = link.remoteAudio();
    if (!s || !track) return;
    const p = s.participants.get(link.remoteSid);
    const allowed = !!p && canSpeak(p.role) && !this.core.session.deafened.value;
    let sink = this.sinks.get(link.remoteSid);
    if (!sink || sink.trackId !== track.id) {
      if (sink) this.dropSink(sink);
      sink = this.makeSink(track);
      this.sinks.set(link.remoteSid, sink);
    }
    sink.gain.gain.value = allowed ? this.userVolume(link.uid) : 0;
    const dev = this.core.settings.state.value.outputDeviceId;
    try { await (sink.ctx as any).setSinkId?.(dev || ""); } catch { /* ignore */ }
  }

  /** Chromium only starts a remote WebRTC audio track once it is attached to a media element; the
   *  element stays muted and the sound goes through a gain node so volume can go above 100%. */
  private makeSink(track: MediaStreamTrack): Sink {
    const stream = new MediaStream([track]);
    const el = document.createElement("audio");
    el.muted = true;
    el.srcObject = stream;
    el.autoplay = true;
    el.style.display = "none";
    document.body.appendChild(el);
    void el.play().catch(() => {});
    const ctx = new AudioContext({ latencyHint: "interactive" });
    const src = ctx.createMediaStreamSource(stream);
    const gain = ctx.createGain();
    src.connect(gain).connect(ctx.destination);
    if (ctx.state === "suspended") void ctx.resume();
    return { el, ctx, gain, trackId: track.id };
  }

  private tick() {
    if (!this.running) return;
    const lvl = this.core.rtc.micLevel;
    const live = this.micLive();
    this.level.value = live ? lvl : 0;
    if (live && lvl > 0.018) {
      this.lastVoice = nowMs();
      if (!this.speakingSelf.value) this.setSpeaking(true);
    } else if (this.speakingSelf.value && nowMs() - this.lastVoice > 450) this.setSpeaking(false);
    this.poll = later(80, () => this.tick());
  }

  private setSpeaking(on: boolean) {
    this.speakingSelf.value = on;
    this.core.session.setSpeakingSelf(on);
  }
}
