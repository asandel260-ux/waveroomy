/* HoMit's visual identity: loose hand-drawn green line work on purple.
   Instead of one big picture, a scene is a set of separate drawings (vines, spiral curls, loop chains, tubes, leaves,
   hatching, bubbles) that float about on their own. Each is a few smooth strokes of an even, thin pen; sizes and shapes
   all differ. Everything comes from a seed, so a screen always gets the same set. */

export type Ink = "green" | "pale" | "bright";
export interface Stroke { d: string; w: number; o: number; ink: Ink }
export interface Piece {
  strokes: Stroke[]; vb: [number, number, number, number];   // drawing and its bounds
  x: number; y: number;                                      // where it starts, in % of the screen
  scale: number; z: number;                                  // size and depth (deeper = fainter and slower)
  r0: number; r1: number; dx: number; dy: number;            // drift: rotation and travel in vw / vh
  dur: number; delay: number; sdur: number;                  // seconds
  light?: { dur: number; delay: number };                    // a bright spark that runs along the first line
}
export type Mix = Partial<Record<Kind, number>>;
export type Kind = "vine" | "tube" | "curls" | "chain" | "leaf" | "hatch" | "cells" | "swirl";

function rng(seed: number) {
  let a = seed >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
type P = [number, number];
const TAU = Math.PI * 2;
const f1 = (n: number) => Math.round(n * 10) / 10;

function smooth(pts: P[], closed = false): string {
  const n = pts.length;
  const at = (i: number) => (closed ? pts[((i % n) + n) % n] : pts[Math.max(0, Math.min(n - 1, i))]);
  let d = `M${f1(pts[0][0])} ${f1(pts[0][1])}`;
  for (let i = 0; i < (closed ? n : n - 1); i++) {
    const p0 = at(i - 1), p1 = at(i), p2 = at(i + 1), p3 = at(i + 2);
    d += `C${f1(p1[0] + (p2[0] - p0[0]) / 6)} ${f1(p1[1] + (p2[1] - p0[1]) / 6)} ${f1(p2[0] - (p3[0] - p1[0]) / 6)} ${f1(p2[1] - (p3[1] - p1[1]) / 6)} ${f1(p2[0])} ${f1(p2[1])}`;
  }
  return closed ? d + "Z" : d;
}

class Pen {
  strokes: Stroke[] = [];
  x0 = Infinity; y0 = Infinity; x1 = -Infinity; y1 = -Infinity;
  constructor(public R: () => number, public ink: () => Ink) {}
  line(pts: P[], w = 2, o = 1, closed = false, ink?: Ink) {
    for (const [x, y] of pts) { this.x0 = Math.min(this.x0, x); this.y0 = Math.min(this.y0, y); this.x1 = Math.max(this.x1, x); this.y1 = Math.max(this.y1, y); }
    this.strokes.push({ d: smooth(pts, closed), w, o, ink: ink ?? this.ink() });
  }
  vb(): [number, number, number, number] { const pad = 10; return [this.x0 - pad, this.y0 - pad, this.x1 - this.x0 + pad * 2, this.y1 - this.y0 + pad * 2]; }
}

/** A gently S-shaped path of length L: the heading is a sum of two slow waves, so it never kinks. */
function walk(R: () => number, L: number, n = 22): P[] {
  const h0 = (R() - 0.5) * 1.4, a1 = 0.35 + R() * 0.55, a2 = 0.1 + R() * 0.3, f1_ = 0.6 + R() * 0.9, f2 = 1.6 + R() * 1.6, p1 = R() * TAU, p2 = R() * TAU;
  const pts: P[] = [[0, 0]]; let x = 0, y = 0;
  for (let i = 1; i <= n; i++) {
    const t = i / n, h = h0 + a1 * Math.sin(t * TAU * f1_ * 0.5 + p1) + a2 * Math.sin(t * TAU * f2 * 0.5 + p2);
    x += Math.cos(h) * (L / n); y += Math.sin(h) * (L / n); pts.push([x, y]);
  }
  return pts;
}
const normalAt = (pts: P[], i: number): P => { const a = pts[Math.max(0, i - 1)], b = pts[Math.min(pts.length - 1, i + 1)]; const dx = b[0] - a[0], dy = b[1] - a[1], l = Math.hypot(dx, dy) || 1; return [-dy / l, dx / l]; };

/** A spiral that ends in a short tail, rotated and squashed so no two match. */
function spiral(cx: number, cy: number, r: number, R: () => number): P[] {
  const turns = 1.3 + R() * 1.5, dir = R() < 0.5 ? 1 : -1, a0 = R() * TAU, squash = 0.72 + R() * 0.28, tail = r * (0.5 + R() * 1.2), tilt = R() * TAU;
  const pts: P[] = []; const n = Math.floor(10 + turns * 9);
  for (let k = 0; k <= n; k++) {
    const t = 1 - k / n, a = a0 + dir * (1 - t) * turns * TAU, rr = r * Math.pow(t, 0.8 + R() * 0.0) * (1 + Math.sin(a * 2 + tilt) * 0.05);
    const x = Math.cos(a) * rr, y = Math.sin(a) * rr * squash;
    pts.push([cx + x * Math.cos(tilt) - y * Math.sin(tilt), cy + x * Math.sin(tilt) + y * Math.cos(tilt)]);
  }
  const first = pts[0], dirx = Math.cos(a0 + dir * 0.0 + Math.PI / 2 * -dir), diry = Math.sin(a0 + dir * 0.0 + Math.PI / 2 * -dir);
  pts.unshift([first[0] + dirx * tail * 0.5, first[1] + diry * tail * 0.5]);
  pts.unshift([first[0] + dirx * tail, first[1] + diry * tail + (R() - 0.5) * tail * 0.3]);
  return pts;
}
/** A round, uneven bubble. */
function bubble(cx: number, cy: number, r: number, R: () => number, open = false): P[] {
  const n = 7 + Math.floor(R() * 3), squash = 0.6 + R() * 0.4, rot = R() * TAU, pts: P[] = [];
  for (let k = 0; k < n; k++) { const a = (k / n) * TAU, rr = r * (0.75 + R() * 0.4); const x = Math.cos(a) * rr, y = Math.sin(a) * rr * squash; pts.push([cx + x * Math.cos(rot) - y * Math.sin(rot), cy + x * Math.sin(rot) + y * Math.cos(rot)]); }
  if (open) { pts.push([pts[0][0] + (R() - 0.5) * r * 0.6, pts[0][1] + (R() - 0.5) * r * 0.6]); }
  return pts;
}

const MAKE: Record<Kind, (pen: Pen, R: () => number) => void> = {
  vine(pen, R) {
    const L = 360 + R() * 340, pts = walk(R, L);
    pen.line(pts, 2, 1);
    pen.line(pts.map((p, i) => { const n = normalAt(pts, i); const o = 5 + Math.sin(i * 0.4) * 1.6; return [p[0] + n[0] * o, p[1] + n[1] * o] as P; }), 6, 0.22, false, "green");   // the faded companion line
    const k = 2 + Math.floor(R() * 3);
    for (let j = 0; j < k; j++) {                                                  // curls growing off it
      const i = 3 + Math.floor(R() * (pts.length - 6)), n = normalAt(pts, i), side = R() < 0.5 ? -1 : 1, r = 9 + R() * 18, stem = 12 + R() * 14;
      const sx = pts[i][0] + n[0] * side * stem, sy = pts[i][1] + n[1] * side * stem;
      pen.line([pts[i], [pts[i][0] + n[0] * side * stem * 0.5 + 3, pts[i][1] + n[1] * side * stem * 0.5]], 1.6, 1);
      pen.line(spiral(sx + n[0] * side * r * 0.6, sy + n[1] * side * r * 0.6, r, R), 1.8, 1);
    }
  },
  tube(pen, R) {
    const L = 320 + R() * 300, c = walk(R, L, 20), gap0 = 40 + R() * 34, ph = R() * TAU;
    const A: P[] = [], B: P[] = [];
    c.forEach((p, i) => { const n = normalAt(c, i), g = (gap0 * (0.75 + 0.35 * Math.sin(i * 0.35 + ph))) / 2; A.push([p[0] + n[0] * g, p[1] + n[1] * g]); B.push([p[0] - n[0] * g, p[1] - n[1] * g]); });
    pen.line(A, 2, 1); pen.line(B, 2, 1);
    pen.line(A.map((p, i) => [p[0] + normalAt(c, i)[0] * 5, p[1] + normalAt(c, i)[1] * 5] as P), 6, 0.2, false, "green");
    let i = 2 + Math.floor(R() * 2);
    while (i < c.length - 2) {
      const g = Math.hypot(A[i][0] - B[i][0], A[i][1] - B[i][1]), r = g * (0.2 + R() * 0.22), off = (R() - 0.5) * (g - 2 * r) * 0.7, n = normalAt(c, i);
      const cx = c[i][0] + n[0] * off, cy = c[i][1] + n[1] * off, k = R();
      if (k < 0.5) pen.line(spiral(cx, cy, r, R), 1.8, 1); else if (k < 0.85) pen.line(bubble(cx, cy, r * 1.1, R, R() < 0.5), 1.8, 1, false); else { for (let h = 0; h < 4; h++) pen.line([[cx - r + h * 4, cy - r * 0.8], [cx - r * 0.2 + h * 4, cy + r * 0.8]], 1.4, 0.9); }
      i += 2 + Math.floor(R() * 2);
    }
  },
  curls(pen, R) {
    const n = 3 + Math.floor(R() * 3); let x = 0, y = 0, ang = R() * TAU;
    for (let k = 0; k < n; k++) {
      const r = 14 + R() * 38; pen.line(spiral(x, y, r, R), 1.9, 1);
      ang += (R() - 0.5) * 1.6; const step = r * (1.5 + R() * 0.8); const nx = x + Math.cos(ang) * step, ny = y + Math.sin(ang) * step;
      if (R() < 0.6) pen.line([[x + Math.cos(ang) * r * 0.7, y + Math.sin(ang) * r * 0.7], [(x + nx) / 2 + (R() - 0.5) * 12, (y + ny) / 2 + (R() - 0.5) * 12], [nx - Math.cos(ang) * 6, ny - Math.sin(ang) * 6]], 1.5, 0.9);
      x = nx; y = ny;
    }
  },
  chain(pen, R) {
    const L = 260 + R() * 220, loops = 4 + Math.floor(R() * 4), A = 10 + R() * 16, B = A * (0.8 + R() * 0.7), base = walk(R, L, loops * 8), pts: P[] = [];
    base.forEach((p, i) => { const t = i / (base.length - 1), th = t * loops * TAU, n = normalAt(base, i), tan: P = [n[1], -n[0]]; const ox = Math.sin(th) * A * 1.3, oy = Math.cos(th) * B * (0.8 + 0.4 * Math.sin(t * 4)); pts.push([p[0] + tan[0] * ox + n[0] * oy, p[1] + tan[1] * ox + n[1] * oy]); });
    pen.line(pts, 1.9, 1);
  },
  leaf(pen, R) {
    const L = 70 + R() * 90, bA = 0.2 + R() * 0.2, bB = 0.08 + R() * 0.2, pos = 0.35 + R() * 0.3, cv = (R() - 0.5) * L * 0.3, rot = (x: number, y: number): P => [x, y];
    pen.line([rot(0, 0), rot(L * pos, -L * bA + cv * 0.3), rot(L, cv * 0.5), rot(L * pos, L * bB + cv * 0.3)], 1.9, 1, true);
    pen.line([rot(0, 0), rot(L * 0.5, cv * 0.4), rot(L, cv * 0.5)], 1.7, 1);
    const k = 3 + Math.floor(R() * 3);
    for (let j = 0; j < k; j++) { const x = L * (0.18 + j * 0.15 + R() * 0.04), s = R() < 0.75 ? -1 : 1; pen.line([rot(x, cv * 0.4), rot(x + L * 0.1, s * L * (0.07 + R() * 0.09) + cv * 0.4)], 1.4, 0.9); }
    pen.line([rot(0, 0), rot(-L * 0.25, L * 0.12 * (R() - 0.3))], 1.7, 1);
  },
  hatch(pen, R) {
    const n = 5 + Math.floor(R() * 4), a = (R() - 0.5) * 1.2 + 1.3, len = 40 + R() * 60;
    for (let k = 0; k < n; k++) { const ox = k * (8 + R() * 5), l = len * (0.7 + R() * 0.5), aa = a + (R() - 0.5) * 0.25; pen.line([[ox, 0], [ox + Math.cos(aa) * l * 0.5 + (R() - 0.5) * 3, Math.sin(aa) * l * 0.5], [ox + Math.cos(aa) * l, Math.sin(aa) * l]], 1.5, 0.95); }
  },
  cells(pen, R) {
    const n = 2 + Math.floor(R() * 3); let x = 0, y = 0;
    for (let k = 0; k < n; k++) { const r = 20 + R() * 40; pen.line(bubble(x, y, r, R, R() < 0.6), 1.9, 1, false); x += (r + 8 + R() * 30) * (R() < 0.5 ? 1 : 0.6); y += (R() - 0.3) * r * 1.3; }
  },
  swirl(pen, R) {
    const r = 30 + R() * 50; pen.line(spiral(0, 0, r, R), 1.9, 1);
    if (R() < 0.7) pen.line(spiral(r * 1.1, -r * 0.5, r * 0.45, R), 1.8, 1);
    pen.line(walk(R, r * 2.2, 10).map(([x, y]) => [x - r * 0.4, y + r * 1.1] as P), 6, 0.2, false, "green");
  },
};

export function makeScene(seed: number, count: number, mix: Mix, opts: { calm?: number; spread?: number } = {}): Piece[] {
  const R = rng(seed), calm = opts.calm ?? 1;
  const kinds = Object.keys(mix) as Kind[], total = kinds.reduce((s, k) => s + (mix[k] ?? 0), 0);
  const pieces: Piece[] = [];
  for (let i = 0; i < count; i++) {
    let r = R() * total, kind = kinds[0];
    for (const k of kinds) { r -= mix[k] ?? 0; if (r <= 0) { kind = k; break; } }
    const ink = (): Ink => { const q = R(); return q < 0.76 ? "green" : q < 0.92 ? "pale" : "bright"; };
    const pen = new Pen(R, ink); MAKE[kind](pen, R);
    const z = R(), scale = (0.55 + z * 0.95) * (kind === "hatch" ? 0.9 : 1);
    pieces.push({ strokes: pen.strokes, vb: pen.vb(), x: R() * 100 - 8, y: R() * 100 - 8, scale, z,
      r0: (R() - 0.5) * 40, r1: (R() - 0.5) * 40 + (R() - 0.5) * 40 * calm, dx: (R() - 0.5) * 60 * calm * (0.5 + z), dy: (R() - 0.5) * 44 * calm * (0.5 + z),
      dur: (46 - z * 20) * (0.8 + R() * 0.6) / Math.max(0.5, calm), delay: -R() * 60, sdur: 6 + R() * 9,
      light: R() < 0.5 && kind !== "hatch" ? { dur: 5 + R() * 7, delay: -R() * 12 } : undefined });
  }
  return pieces;
}
