import type { SettingsStore } from "../core/store";

const SOUNDS: Record<string, [number, number][]> = {
  join: [[523, 0.07], [784, 0.10]],
  leave: [[659, 0.07], [440, 0.10]],
  message: [[880, 0.06]],
  hand: [[698, 0.05], [698, 0.05], [932, 0.08]],
  mute: [[330, 0.07]],
  unmute: [[494, 0.07]],
  c_move: [[300, 0.035]],
  c_cap: [[220, 0.03], [150, 0.06]],
  c_check: [[740, 0.05], [988, 0.07]],
  c_end: [[523, 0.09], [392, 0.09], [262, 0.16]],
  c_low: [[880, 0.03]]
};

/** Tiny synthesized interface sounds (no asset files); same notes as the Android app. */
export class Sfx {
  private ctx: AudioContext | null = null;
  private cache = new Map<string, AudioBuffer>();
  constructor(private settings: SettingsStore) {}

  private buffer(name: string): AudioBuffer {
    const hit = this.cache.get(name);
    if (hit) return hit;
    const ctx = (this.ctx ??= new AudioContext());
    const parts = SOUNDS[name] ?? [[600, 0.05]];
    const rate = ctx.sampleRate;
    const total = parts.reduce((a, [, d]) => a + Math.floor(rate * d), 0);
    const buf = ctx.createBuffer(1, total, rate);
    const data = buf.getChannelData(0);
    let o = 0;
    for (const [f, d] of parts) {
      const n = Math.floor(rate * d);
      for (let i = 0; i < n; i++) {
        const env = Math.min(1, i / (rate * 0.008)) * Math.min(1, (n - i) / (rate * 0.03));
        data[o + i] = Math.sin((2 * Math.PI * f * i) / rate) * 0.28 * env;
      }
      o += n;
    }
    this.cache.set(name, buf);
    return buf;
  }

  setSink(id: string) { try { void (this.ctx as any)?.setSinkId?.(id || ""); } catch { /* ignore */ } }

  play(name: string) {
    const cfg = this.settings.state.value;
    if (!cfg.sounds) return;
    if (name.startsWith("c_") && !cfg.chessSounds) return;
    try {
      const buf = this.buffer(name);
      const ctx = this.ctx!;
      if (ctx.state === "suspended") void ctx.resume();
      const src = ctx.createBufferSource();
      src.buffer = buf;
      src.connect(ctx.destination);
      src.start();
    } catch { /* ignore */ }
  }
}
