/**
 * The voice-effect DSP as an AudioWorklet (same building blocks and presets as the Android
 * VoiceFx.kt: RBJ biquads, compressor, soft clip, ring modulation, dual-head pitch shifter).
 * Kept as a source string so it can be loaded from a Blob URL without extra build steps.
 */
export const WORKLET_SOURCE = String.raw`
const dbToLin = (db) => Math.pow(10, db / 20);
const lerp = (a, b, k) => a + (b - a) * k;
class Biquad {
  constructor(sr, type, freq, q = 0.707, gainDb = 0) {
    this.sr = sr; this.type = type; this.f = freq; this.q = q; this.gainDb = gainDb;
    this.x1 = 0; this.x2 = 0; this.y1 = 0; this.y2 = 0; this.calc();
  }
  set(o) { if (o.freq !== undefined) this.f = o.freq; if (o.gainDb !== undefined) this.gainDb = o.gainDb; this.calc(); }
  calc() {
    const fc = Math.min(this.sr * 0.45, Math.max(10, this.f));
    const w0 = 2 * Math.PI * fc / this.sr, cw = Math.cos(w0), sw = Math.sin(w0), alpha = sw / (2 * this.q);
    const a = Math.pow(10, this.gainDb / 40);
    let nb0, nb1, nb2, na0, na1, na2;
    switch (this.type) {
      case "lowpass": nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha; break;
      case "highpass": nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2; na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha; break;
      case "peaking": nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a; na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a; break;
      case "lowshelf": {
        const sq = 2 * Math.sqrt(a) * alpha;
        nb0 = a * ((a + 1) - (a - 1) * cw + sq); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - sq);
        na0 = (a + 1) + (a - 1) * cw + sq; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - sq; break;
      }
      default: {
        const sq = 2 * Math.sqrt(a) * alpha;
        nb0 = a * ((a + 1) + (a - 1) * cw + sq); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - sq);
        na0 = (a + 1) - (a - 1) * cw + sq; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - sq;
      }
    }
    this.b0 = nb0 / na0; this.b1 = nb1 / na0; this.b2 = nb2 / na0; this.a1 = na1 / na0; this.a2 = na2 / na0;
  }
  p(x) {
    const y = this.b0 * x + this.b1 * this.x1 + this.b2 * this.x2 - this.a1 * this.y1 - this.a2 * this.y2;
    this.x2 = this.x1; this.x1 = x; this.y2 = this.y1; this.y1 = y; return y;
  }
}
class Gain { constructor() { this.g = 1; } p(x) { return x * this.g; } }
class SoftClip {
  constructor() { this.k = 0.0001; this.norm = Math.tanh(this.k); }
  drive(d) { this.k = Math.max(0.0001, d); this.norm = Math.tanh(this.k); }
  p(x) { return Math.tanh(this.k * Math.min(1.5, Math.max(-1.5, x))) / this.norm; }
}
class Compressor {
  constructor(sr, thr, ratio, atk = 0.004, rel = 0.16) {
    this.thresholdDb = thr; this.ratio = ratio; this.atk = Math.exp(-1 / (atk * sr)); this.rel = Math.exp(-1 / (rel * sr)); this.env = 0;
  }
  p(x) {
    const a = Math.abs(x);
    this.env = a > this.env ? this.atk * this.env + (1 - this.atk) * a : this.rel * this.env + (1 - this.rel) * a;
    const db = 20 * Math.log(Math.max(this.env, 1e-6)) / Math.LN10;
    if (db <= this.thresholdDb) return x;
    const over = db - this.thresholdDb;
    return x * dbToLin(-(over - over / this.ratio));
  }
}
class RingMod {
  constructor(sr, f) { this.sr = sr; this.f = f; this.ph = 0; }
  p(x) { this.ph += 2 * Math.PI * this.f / this.sr; if (this.ph > 2 * Math.PI) this.ph -= 2 * Math.PI; return x * Math.sin(this.ph); }
}
class PitchShifter {
  constructor(sr) {
    this.sr = sr; this.period = 0.16; this.fade = 0.03; this.base = 0.008;
    this.line = new Float32Array(Math.floor(sr * 0.5) + 8); this.w = 0; this.phase = 0; this.g = 0; this.intrinsic = this.base;
  }
  setRatio(r0) { const r = Math.min(1.7, Math.max(0.6, r0)); this.g = this.period * (1 - r); this.intrinsic = this.base + (this.g < 0 ? -this.g : 0); }
  env(ph) {
    const s = ph * this.period;
    if (s < this.fade) return s / this.fade;
    if (s < this.period / 2) return 1;
    if (s < this.period / 2 + this.fade) return 1 - (s - this.period / 2) / this.fade;
    return 0;
  }
  read(d) {
    let pos = this.w - d; const n = this.line.length;
    while (pos < 0) pos += n;
    const i0 = Math.floor(pos) % n, i1 = (i0 + 1) % n, fr = pos - Math.floor(pos);
    return this.line[i0] * (1 - fr) + this.line[i1] * fr;
  }
  p(x) {
    this.line[this.w] = x;
    let out = 0;
    for (let head = 0; head < 2; head++) {
      const ph = (this.phase + head * 0.5) % 1;
      const d = (this.intrinsic + this.g * ph) * this.sr;
      out += this.env(ph) * this.read(Math.min(this.line.length - 2, Math.max(1, d)));
    }
    this.phase += 1 / (this.period * this.sr); if (this.phase >= 1) this.phase -= 1;
    this.w = (this.w + 1) % this.line.length;
    return out;
  }
}
class Noise {
  constructor() { this.last = 0; this.level = 0; }
  next() { const white = Math.random() * 2 - 1; this.last = (this.last + 0.02 * white) / 1.02; return this.last * 3.5 * this.level; }
}
function buildChain(id, sr) {
  switch (id) {
    case "natural": { const hp = new Biquad(sr, "highpass", 70, 0.7), pres = new Biquad(sr, "peaking", 2800, 0.9, 0);
      return { process: (x) => pres.p(hp.p(x)), set: (k) => pres.set({ gainDb: 1.5 * k }) }; }
    case "studio": { const hp = new Biquad(sr, "highpass", 85, 0.8), body = new Biquad(sr, "lowshelf", 180, 0.707, 0), pres = new Biquad(sr, "peaking", 3200, 0.9, 0), comp = new Compressor(sr, -18, 4), mk = new Gain();
      return { process: (x) => mk.p(comp.p(pres.p(body.p(hp.p(x))))), set: (k) => { body.set({ gainDb: 1.5 * k }); pres.set({ gainDb: 3 * k }); comp.thresholdDb = lerp(-6, -20, k); mk.g = dbToLin(2.5 * k); } }; }
    case "deep": { const ps = new PitchShifter(sr), shelf = new Biquad(sr, "lowshelf", 160, 0.707, 0), lift = new Gain();
      return { process: (x) => lift.p(shelf.p(ps.p(x))), set: (k) => { ps.setRatio(lerp(1, 0.8, k)); shelf.set({ gainDb: 4.5 * k }); lift.g = dbToLin(1.5 * k); } }; }
    case "bright": { const ps = new PitchShifter(sr), hp = new Biquad(sr, "highpass", 110, 0.8), air = new Biquad(sr, "highshelf", 5500, 0.707, 0);
      return { process: (x) => air.p(hp.p(ps.p(x))), set: (k) => { ps.setRatio(lerp(1, 1.12, k)); air.set({ gainDb: 4.5 * k }); } }; }
    case "radio": { const hp = new Biquad(sr, "highpass", 90, 0.7), lp = new Biquad(sr, "lowpass", 12000, 0.7), ws = new SoftClip(), comp = new Compressor(sr, -24, 8, 0.003, 0.12), mk = new Gain();
      return { process: (x) => mk.p(comp.p(ws.p(lp.p(hp.p(x))))), set: (k) => { hp.set({ freq: lerp(90, 300, k) }); lp.set({ freq: lerp(12000, 3400, k) }); ws.drive(0.0001 + 1.6 * k); mk.g = dbToLin(3.5 * k); } }; }
    case "character": { const ps = new PitchShifter(sr), peak = new Biquad(sr, "peaking", 1800, 1.2, 0);
      return { process: (x) => peak.p(ps.p(x)), set: (k) => { ps.setRatio(lerp(1, 1.3, k)); peak.set({ gainDb: 5 * k }); } }; }
    case "robotic": { const rm = new RingMod(sr, 72), hp = new Biquad(sr, "highpass", 200, 0.8), lp = new Biquad(sr, "lowpass", 4200, 0.8); let dry = 1, wet = 0;
      return { process: (x) => x * dry + lp.p(hp.p(rm.p(x))) * wet, set: (k) => { wet = 0.95 * k; dry = 1 - 0.75 * k; } }; }
    default: { const hp = new Biquad(sr, "highpass", 100, 1), lp = new Biquad(sr, "lowpass", 10000, 1), lp2 = new Biquad(sr, "lowpass", 12000, 0.7), ws = new SoftClip(), level = new Gain(), noise = new Noise();
      return { process: (x) => level.p(ws.p(lp2.p(lp.p(hp.p(x))))) + noise.next(), set: (k) => { hp.set({ freq: lerp(100, 420, k) }); lp.set({ freq: lerp(10000, 2900, k) }); lp2.set({ freq: lerp(12000, 3600, k) }); ws.drive(0.0001 + 2.4 * k); level.g = dbToLin(2 * k); noise.level = 0.006 * k; } }; }
  }
}
class VoiceFxProcessor extends AudioWorkletProcessor {
  constructor() {
    super();
    this.enabled = false; this.preset = "studio"; this.k = 0.7; this.chain = null; this.builtFor = ""; this.builtK = -1;
    this.port.onmessage = (e) => { const d = e.data; this.enabled = !!d.enabled; this.preset = d.preset; this.k = d.intensity; };
  }
  process(inputs, outputs) {
    const inp = inputs[0], out = outputs[0];
    if (!inp || !inp[0]) return true;
    const x = inp[0], y = out[0];
    if (!this.enabled) { y.set(x); for (let c = 1; c < out.length; c++) out[c].set(x); return true; }
    if (this.builtFor !== this.preset) { this.chain = buildChain(this.preset, sampleRate); this.builtFor = this.preset; this.builtK = -1; }
    if (this.builtK !== this.k) { this.chain.set(this.k); this.builtK = this.k; }
    for (let i = 0; i < x.length; i++) { const v = this.chain.process(x[i]); y[i] = v > 1 ? 1 : v < -1 ? -1 : v; }
    for (let c = 1; c < out.length; c++) out[c].set(y);
    return true;
  }
}
registerProcessor("voicefx", VoiceFxProcessor);
`;
