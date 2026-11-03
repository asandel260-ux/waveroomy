/* HoMit's visual identity: hand-drawn green line work on purple (vines, spirals, loops, leaf hatching, fireflies).
   Everything is generated from a seed, so the same seed always draws the same picture. */

export interface Stroke { d: string; w: number; o: number; len: number }
export interface Doodle { strokes: Stroke[]; dots: { x: number; y: number; r: number }[] }

function rng(seed: number) {
  let a = seed >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}

type P = [number, number];
const f = (n: number) => Math.round(n * 10) / 10;

/** Smooth curve through points (Catmull-Rom as cubic Beziers). */
function smooth(pts: P[], closed = false): string {
  const n = pts.length;
  const at = (i: number) => (closed ? pts[(i + n) % n] : pts[Math.max(0, Math.min(n - 1, i))]);
  let d = `M${f(pts[0][0])} ${f(pts[0][1])}`;
  const last = closed ? n : n - 1;
  for (let i = 0; i < last; i++) {
    const p0 = at(i - 1), p1 = at(i), p2 = at(i + 1), p3 = at(i + 2);
    d += `C${f(p1[0] + (p2[0] - p0[0]) / 6)} ${f(p1[1] + (p2[1] - p0[1]) / 6)} ${f(p2[0] - (p3[0] - p1[0]) / 6)} ${f(p2[1] - (p3[1] - p1[1]) / 6)} ${f(p2[0])} ${f(p2[1])}`;
  }
  return closed ? d + "Z" : d;
}
const lenOf = (pts: P[]) => { let s = 0; for (let i = 1; i < pts.length; i++) s += Math.hypot(pts[i][0] - pts[i - 1][0], pts[i][1] - pts[i - 1][1]); return s * 1.1; };

export function makeDoodle(seed: number, W: number, H: number, opts: { vines?: number; spirals?: number; loops?: number; hatches?: number; leaves?: number; dots?: number } = {}): Doodle {
  const R = rng(seed);
  const strokes: Stroke[] = [];
  const dots: Doodle["dots"] = [];
  const between = (a: number, b: number) => a + (b - a) * R();
  const add = (pts: P[], w: number, o: number, closed = false) => strokes.push({ d: smooth(pts, closed), w, o, len: lenOf(pts) * (closed ? 1.2 : 1) });

  // Long leaning vines, each drawn as a soft wide line with a thin one on top, slightly apart.
  const nv = opts.vines ?? 7;
  for (let i = 0; i < nv; i++) {
    const x0 = (W / nv) * (i + between(-0.2, 0.6)), lean = between(0.18, 0.42) * H;
    const pts: P[] = [];
    for (let k = 0; k <= 8; k++) pts.push([x0 + lean * (k / 8) + between(-42, 42) - lean * 0.2, -40 + (H + 80) * (k / 8) + between(-18, 18)]);
    add(pts, 6, 0.3);
    add(pts.map(([x, y]) => [x + between(2, 5), y + between(-2, 2)] as P), 2, 1);
  }
  // Spirals.
  for (let i = 0; i < (opts.spirals ?? 9); i++) {
    const cx = between(0.05, 0.95) * W, cy = between(0.05, 0.95) * H, r = between(16, 46), turns = between(1.6, 2.6), dir = R() < 0.5 ? 1 : -1, a0 = between(0, 6.28);
    const pts: P[] = [];
    for (let k = 0; k <= 26; k++) { const t = k / 26, a = a0 + dir * t * turns * 6.283, rr = r * (1 - t * 0.85) + between(-1.5, 1.5); pts.push([cx + Math.cos(a) * rr, cy + Math.sin(a) * rr]); }
    add(pts, 2, 1);
  }
  // Wobbly closed loops, like the bubbles between the vines.
  for (let i = 0; i < (opts.loops ?? 8); i++) {
    const cx = between(0.05, 0.95) * W, cy = between(0.05, 0.95) * H, rx = between(26, 70), ry = between(22, 60), n = 7;
    const pts: P[] = [];
    for (let k = 0; k < n; k++) { const a = (k / n) * 6.283 + between(-0.2, 0.2), j = between(0.78, 1.15); pts.push([cx + Math.cos(a) * rx * j, cy + Math.sin(a) * ry * j]); }
    add(pts, 2, 1, true);
  }
  // Hatching: a few near-parallel strokes in a patch.
  for (let i = 0; i < (opts.hatches ?? 3); i++) {
    const x = between(0, 0.9) * W, y = between(0, 0.9) * H, ang = between(-0.5, 0.5), len = between(60, 130), cnt = Math.round(between(4, 7));
    for (let k = 0; k < cnt; k++) {
      const ox = x + k * 11, oy = y + k * between(-2, 4);
      add([[ox, oy], [ox + Math.cos(ang + 1.3) * len * 0.5 + between(-3, 3), oy + Math.sin(ang + 1.3) * len * 0.5], [ox + Math.cos(ang + 1.3) * len + between(-4, 4), oy + Math.sin(ang + 1.3) * len]], 2, 1);
    }
  }
  // Leaves: an outline, a rib and a few ticks.
  for (let i = 0; i < (opts.leaves ?? 3); i++) {
    const cx = between(0.1, 0.9) * W, cy = between(0.1, 0.9) * H, L = between(70, 130), Wd = L * between(0.28, 0.4), a = between(-1.2, 1.2);
    const rot = (x: number, y: number): P => [cx + x * Math.cos(a) - y * Math.sin(a), cy + x * Math.sin(a) + y * Math.cos(a)];
    add([rot(-L / 2, 0), rot(-L / 4, -Wd), rot(L / 4, -Wd * 0.9), rot(L / 2, 0), rot(L / 4, Wd * 0.9), rot(-L / 4, Wd)], 2, 1, true);
    add([rot(-L / 2, 0), rot(0, between(-3, 3)), rot(L / 2, 0)], 2, 1);
    for (let k = 1; k <= 4; k++) { const x = -L / 2 + (L / 5) * k; add([rot(x, 0), rot(x + 8, -Wd * 0.7)], 1.6, 0.9); add([rot(x, 0), rot(x + 8, Wd * 0.7)], 1.6, 0.9); }
  }
  for (let i = 0; i < (opts.dots ?? 18); i++) dots.push({ x: between(0, W), y: between(0, H), r: between(2, 4.5) });
  return { strokes, dots };
}
