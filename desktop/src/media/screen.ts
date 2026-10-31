import { Flow } from "../core/util";
import { native } from "../core/native";
import type { PeerLink } from "../net/peerlink";
import type { AppCore } from "../app/core";

/** Captures a screen or window and sends it to every connected room member. */
export class ScreenShare {
  readonly active = new Flow(false);
  private stream: MediaStream | null = null;
  private res = 720;
  private fps = 30;
  private gen = 0;

  constructor(private core: AppCore) {}

  private bitrate(res: number, fps: number): number {
    const base = res === 240 ? 400_000 : res === 480 ? 1_000_000 : res === 720 ? 2_500_000 : 5_000_000;
    return Math.floor(base * (0.55 + (0.45 * fps) / 30));
  }

  /** [sourceId] comes from the source picker (native.screens.sources()). */
  async start(sourceId: string, audio: boolean, res: number, fps: number): Promise<string | null> {
    if (this.active.value) return null;
    this.res = res; this.fps = fps;
    const my = ++this.gen;
    try {
      await native.screens.choose(sourceId, audio);
      const s = await navigator.mediaDevices.getDisplayMedia({
        video: { frameRate: { ideal: fps, max: fps }, height: { ideal: res, max: res } },
        audio: audio ? { echoCancellation: false, noiseSuppression: false } as any : false
      });
      if (my !== this.gen || !this.core.session.live) { s.getTracks().forEach((t) => t.stop()); return "Screen sharing was cancelled"; }
      this.stream = s;
      const v = s.getVideoTracks()[0];
      try { (v as any).contentHint = fps >= 30 ? "motion" : "detail"; } catch { /* ignore */ }
      v.onended = () => this.stop(true);
      this.active.value = true;
      for (const l of this.core.mesh.authedLinks()) this.attach(l);
      return null;
    } catch (e: any) {
      this.stop(false);
      return e?.message || "Screen capture failed";
    }
  }

  /** Room membership changed: start sending to newcomers. */
  refresh() { if (this.active.value) for (const l of this.core.mesh.authedLinks()) this.attach(l); }

  attach(link: PeerLink) {
    const v = this.stream?.getVideoTracks()[0];
    if (!v || !this.active.value || !link.authed) return;
    // only people in the room get to see the screen, not friends who happen to be linked
    if (!this.core.session.room?.participants.has(link.remoteSid)) return;
    link.setSendVideo(v);
    void link.tuneVideo(this.bitrate(this.res, this.fps), this.fps);
  }

  async setQuality(res: number, fps: number) {
    this.res = res; this.fps = fps;
    const v = this.stream?.getVideoTracks()[0];
    try { await v?.applyConstraints({ frameRate: { ideal: fps, max: fps }, height: { ideal: res, max: res } }); } catch { /* ignore */ }
    for (const l of this.core.mesh.authedLinks()) void l.tuneVideo(this.bitrate(res, fps), fps);
  }

  stop(announce: boolean) {
    const was = this.active.value;
    this.gen++;
    this.active.value = false;
    for (const l of this.core.mesh.authedLinks()) l.setSendVideo(null);
    this.stream?.getTracks().forEach((t) => { t.onended = null; t.stop(); });
    this.stream = null;
    if (was && announce) this.core.session.announceStopShare();
  }
}
