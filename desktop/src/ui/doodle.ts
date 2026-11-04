/* HoMit's visual identity: loose, hand-drawn green line work on purple.
   A scene is a few layers of long meandering ribbons (two uneven edges with a shaded band between them), packed with
   mismatched spirals, loops, scribbles and leaves, plus hatching and fireflies. Everything is drawn with a pressure
   brush (thin at the ends, heavier in the middle) and comes from a seed, so a screen always gets the same picture. */

export type Ink = "green" | "pale" | "bright";
export interface Shape {
  d: string;                       // filled outline of a brush stroke (or a filled band)
  ink?: Ink; o: number;            // ink colour and opacity
  band?: "light" | "dark";         // shaded area between two ribbon edges
  spin?: number;                   // seconds per half turn, sign = direction (spirals and loops turn slowly)
  at?: [number, number];           // centre used for the turn
  tracer?: string;                 // centre line for a light that runs along it
}
export interface Layer { depth: number; shapes: Shape[]; dots: { x: number; y: number; r: number; d: number }[] }
export interface Scene { layers: Layer[]; W: number; H: number }
export interface SceneCfg { angle: number; ribbons: number; spread?: number; calm?: number; flip?: boolean }

function rng(seed: number) {
  let a = seed >>> 0;
  return () => { a = (a + 0x6d2b79f5) >>> 0; let t = a; t = Math.imul(t ^ (t >>> 15), t | 1); t ^= t + Math.imul(t ^ (t >>> 7), t | 61); return ((t ^ (t >>> 14)) >>> 0) / 4294967296; };
}
type P = [number, number];
const f1 = (n: number) => Math.round(n * 10) / 10;
const TAU = Math.PI * 2;

/** Catmull-Rom through the points, sampled every `step` pixels. */
function sample(pts: P[], closed: boolean, step: number): P[] {
  const n = pts.length, out: P[] = [];
  const at = (i: number) => (closed ? pts[((i % n) + n) % n] : pts[Math.max(0, Math.min(n - 1, i))]);
  const segs = closed ? n : n - 1;
  for (let i = 0; i < segs; i++) {
    const p0 = at(i - 1), p1 = at(i), p2 = at(i + 1), p3 = at(i + 2);
    const k = Math.max(2, Math.ceil(Math.hypot(p2[0] - p1[0], p2[1] - p1[1]) / step));
    for (let j = 0; j < k; j++) {
      const t = j / k, t2 = t * t, t3 = t2 * t;
      out.push([0.5 * (2 * p1[0] + (-p0[0] + p2[0]) * t + (2 * p0[0] - 5 * p1[0] + 4 * p2[0] - p3[0]) * t2 + (-p0[0] + 3 * p1[0] - 3 * p2[0] + p3[0]) * t3),
                0.5 * (2 * p1[1] + (-p0[1] + p2[1]) * t + (2 * p0[1] - 5 * p1[1] + 4 * p2[1] - p3[1]) * t2 + (-p0[1] + 3 * p1[1] - 3 * p2[1] + p3[1]) * t3)]);
    }
  }
  if (!closed) out.push(pts[n - 1]);
  return out;
}

/** A pressure brush: the outline of a stroke along `pts`, thin at both ends and heavier in the middle. */
function brush(pts: P[], w: number, R: () => number, taper = 0.55): string {
  const n = pts.length; if (n < 2) return "";
  const phase = R() * 6, left: P[] = [], right: P[] = [];
  for (let i = 0; i < n; i++) {
    const a = pts[Math.max(0, i - 1)], b = pts[Math.min(n - 1, i + 1)];
    let dx = b[0] - a[0], dy = b[1] - a[1]; const l = Math.hypot(dx, dy) || 1; dx /= l; dy /= l;
    const t = i / (n - 1), press = (1 - taper) + taper * Math.pow(Math.sin(Math.PI * t), 0.6), wob = 1 + 0.18 * Math.sin(i * 0.35 + phase);
    const hw = (w * press * wob) / 2;
    left.push([pts[i][0] - dy * hw, pts[i][1] + dx * hw]); right.push([pts[i][0] + dy * hw, pts[i][1] - dx * hw]);
  }
  let d = `M${f1(left[0][0])} ${f1(left[0][1])}`;
  for (let i = 1; i < n; i++) d += `L${f1(left[i][0])} ${f1(left[i][1])}`;
  for (let i = n - 1; i >= 0; i--) d += `L${f1(right[i][0])} ${f1(right[i][1])}`;
  return d + "Z";
}
const pathOf = (pts: P[]) => "M" + pts.map((p) => `${f1(p[0])} ${f1(p[1])}`).join("L");

/** A mismatched closed or half-open loop: radius and squash change round the circle, and the end overshoots the start. */
function blobPts(cx: number, cy: number, r: number, R: () => number): P[] {
  const n = 7 + Math.floor(R() * 4), squash = 0.55 + R() * 0.5, rot = R() * TAU, over = R() < 0.6 ? 0.25 + R() * 0.5 : 0;
  const pts: P[] = [];
  const total = n + (over ? 1 : 0);
  for (let k = 0; k < total; k++) {
    const a = (k / n) * TAU + (R() - 0.5) * 0.5, rr = r * (0.55 + R() * 0.7);
    const x = Math.cos(a) * rr, y = Math.sin(a) * rr * squash;
    pts.push([cx + x * Math.cos(rot) - y * Math.sin(rot), cy + x * Math.sin(rot) + y * Math.cos(rot)]);
  }
  if (over) pts.push([pts[0][0] + (R() - 0.5) * r * 0.5, pts[0][1] + (R() - 0.5) * r * 0.5]);
  return sample(pts, !over, 8);
}
function spiralPts(cx: number, cy: number, r: number, R: () => number): P[] {
  const turns = 1.2 + R() * 1.8, dir = R() < 0.5 ? 1 : -1, a0 = R() * TAU, shrink = 0.7 + R() * 0.28, tail = R() < 0.5 ? r * (0.4 + R() * 0.9) : 0, squash = 0.7 + R() * 0.3;
  const pts: P[] = []; const n = Math.floor(14 + turns * 10);
  for (let k = n; k >= 0; k--) {                         // from the outside in
    const t = k / n, a = a0 + dir * (1 - t) * turns * TAU, rr = r * Math.pow(t, 1 - shrink + 0.3) * (1 + (R() - 0.5) * 0.12);
    pts.push([cx + Math.cos(a) * rr, cy + Math.sin(a) * rr * squash]);
  }
  if (tail) { const a = a0; pts.unshift([cx + Math.cos(a) * (r + tail), cy + Math.sin(a) * (r + tail) * 0.8 + (R() - 0.5) * 10]); }
  return sample(pts, false, 8);
}
/** Cursive loops, like handwriting going along a line. */
function scribblePts(cx: number, cy: number, L: number, ang: number, R: () => number): P[] {
  const loops = 3 + Math.floor(R() * 4), A = L * (0.12 + R() * 0.16), B = A * (0.8 + R() * 0.9), pts: P[] = [];
  const steps = loops * 9;
  for (let k = 0; k <= steps; k++) {
    const t = k / steps, th = t * loops * TAU, x = (t - 0.5) * L + Math.sin(th) * A * 1.2, y = Math.cos(th) * B * (0.7 + 0.5 * Math.sin(t * 5 + 1));
    pts.push([cx + x * Math.cos(ang) - y * Math.sin(ang), cy + x * Math.sin(ang) + y * Math.cos(ang)]);
  }
  return sample(pts, false, 8);
}
function leafParts(cx: number, cy: number, L: number, ang: number, R: () => number): P[][] {
  const bulgeA = 0.22 + R() * 0.22, bulgeB = 0.1 + R() * 0.22, pos = 0.35 + R() * 0.3, curve = (R() - 0.5) * L * 0.25;
  const rot = (x: number, y: number): P => [cx + x * Math.cos(ang) - y * Math.sin(ang), cy + x * Math.sin(ang) + y * Math.cos(ang)];
  const out: P[][] = [];
  out.push(sample([rot(-L / 2, 0), rot(-L / 2 + L * pos, -L * bulgeA + curve * 0.3), rot(L / 2, curve * 0.5), rot(-L / 2 + L * pos, L * bulgeB + curve * 0.3)], true, 6));
  out.push(sample([rot(-L / 2, 0), rot(0, curve * 0.4), rot(L / 2, curve * 0.5)], false, 6));
  const ticks = 2 + Math.floor(R() * 4);
  for (let k = 0; k < ticks; k++) { const x = -L / 2 + L * (0.2 + 0.18 * k + R() * 0.05), side = R() < 0.7 ? -1 : 1; out.push([rot(x, curve * 0.4), rot(x + L * 0.1, side * L * (0.08 + R() * 0.1) + curve * 0.4)]); }
  return out;
}

export function makeScene(seed: number, W: number, H: number, cfg: SceneCfg): Scene {
  const R = rng(seed);
  const calm = cfg.calm ?? 1;
  const layers: Layer[] = [];
  const specs = [{ depth: 0, k: 0.55, o: 0.5, ribbons: Math.max(1, Math.round(cfg.ribbons * 0.4)) }, { depth: 1, k: 0.9, o: 0.85, ribbons: Math.max(2, Math.round(cfg.ribbons * 0.6)) }, { depth: 2, k: 1.5, o: 1, ribbons: Math.max(1, Math.round(cfg.ribbons * 0.3)) }];
  for (const sp of specs) {
    const shapes: Shape[] = [], dots: Layer["dots"] = [];
    const ink = (): Ink => { const r = R(); return r < 0.74 ? "green" : r < 0.9 ? "pale" : "bright"; };
    for (let rb = 0; rb < sp.ribbons; rb++) {
      // the centre line: a long walk whose heading drifts, entering and leaving beyond the edges
      const dirx = Math.cos(cfg.angle + (R() - 0.5) * (cfg.spread ?? 0.5)), diry = Math.sin(cfg.angle + (R() - 0.5) * (cfg.spread ?? 0.5));
      const margin = 160, along = Math.hypot(W, H) + margin * 2;
      const cx0 = R() * W, cy0 = R() * H;
      let x = cx0 - dirx * along / 2, y = cy0 - diry * along / 2, h = Math.atan2(diry, dirx);
      const centre: P[] = [], widths: number[] = [];
      const base = (70 + R() * 90) * sp.k, ph = R() * 9, ph2 = R() * 9;
      const target = Math.atan2(diry, dirx);
      for (let s = 0; s < along; s += 12) {
        h += (Math.sin(s * 0.006 + ph) * 0.022 + Math.sin(s * 0.017 + ph2) * 0.012) * calm + (target - h) * 0.004;
        x += Math.cos(h) * 12; y += Math.sin(h) * 12;
        centre.push([x, y]); widths.push(base * (0.65 + 0.55 * (0.5 + 0.5 * Math.sin(s * 0.009 + ph2 * 2))) * (0.85 + R() * 0.3));
      }
      const L: P[] = [], Rr: P[] = [];
      for (let i = 0; i < centre.length; i++) {
        const a = centre[Math.max(0, i - 1)], b = centre[Math.min(centre.length - 1, i + 1)];
        let dx = b[0] - a[0], dy = b[1] - a[1]; const l = Math.hypot(dx, dy) || 1; dx /= l; dy /= l;
        const j1 = Math.sin(i * 0.21 + ph) * 3.2 + Math.sin(i * 0.53 + ph2) * 1.2, j2 = Math.sin(i * 0.19 + ph2) * 3.2 + Math.sin(i * 0.47 + ph) * 1.2;
        L.push([centre[i][0] - dy * (widths[i] / 2 + j1), centre[i][1] + dx * (widths[i] / 2 + j1)]);
        Rr.push([centre[i][0] + dy * (widths[i] / 2 + j2), centre[i][1] - dx * (widths[i] / 2 + j2)]);
      }
      // the shaded band between the edges
      const bandPts = [...L, ...Rr.slice().reverse()];
      shapes.push({ d: "M" + bandPts.map((p) => `${f1(p[0])} ${f1(p[1])}`).join("L") + "Z", o: sp.o * (R() < 0.5 ? 0.09 : 0.14), band: R() < 0.5 ? "light" : "dark" });
      // the two edges, each drawn in a few separate strokes so the pressure changes along them
      for (const edge of [L, Rr]) {
        let i0 = 0;
        while (i0 < edge.length - 6) {
          const len = Math.floor(30 + R() * 70), seg = edge.slice(i0, Math.min(edge.length, i0 + len));
          const w = (1.6 + R() * 1.8) * sp.k;
          const tracer = R() < 0.12 ? pathOf(seg) : undefined;
          shapes.push({ d: brush(seg, w, R, 0.5), ink: ink(), o: sp.o, tracer });
          i0 += len - 2 - Math.floor(R() * 4);                       // overlap a little, like a lifted pen put back down
        }
        if (R() < 0.7) shapes.push({ d: brush(edge.filter((_, i) => i % 1 === 0).slice(0, edge.length), 5.5 * sp.k, R, 0.2), ink: "green", o: sp.o * 0.22 }); // soft wide under-line
      }
      // the filling: mismatched shapes down the middle, sizes held by the width of the band at that spot
      let s = 3 + Math.floor(R() * 4);
      while (s < centre.length - 3) {
        const wd = widths[s], r = wd * (0.18 + R() * 0.3) * (R() < 0.12 ? 1.5 : 1);
        const a = centre[Math.max(0, s - 1)], b = centre[Math.min(centre.length - 1, s + 1)];
        const hd = Math.atan2(b[1] - a[1], b[0] - a[0]), off = (R() - 0.5) * Math.max(0, wd - r * 2) * 0.9;
        const px = centre[s][0] - Math.sin(hd) * off, py = centre[s][1] + Math.cos(hd) * off;
        const kind = R(), w = (1.5 + R() * 1.3) * sp.k, ic = ink();
        if (kind < 0.3) { shapes.push({ d: brush(spiralPts(px, py, r, R), w, R, 0.45), ink: ic, o: sp.o, spin: (R() < 0.5 ? -1 : 1) * (24 + R() * 40), at: [px, py] }); }
        else if (kind < 0.58) { const pts = blobPts(px, py, r, R); shapes.push({ d: brush(pts, w, R, 0.4), ink: ic, o: sp.o }); if (R() < 0.3) shapes.push({ d: pathOf(pts) + "Z", ink: "green", o: sp.o * 0.12, band: undefined }); }
        else if (kind < 0.74) { shapes.push({ d: brush(scribblePts(px, py, wd * (0.7 + R() * 0.8), hd + (R() - 0.5) * 0.7, R), w, R, 0.4), ink: ic, o: sp.o }); }
        else if (kind < 0.88) { for (const part of leafParts(px, py, r * 2.6, hd + (R() - 0.5) * 1.2, R)) shapes.push({ d: brush(part, w * 0.9, R, 0.5), ink: ic, o: sp.o }); }
        else { const n = 3 + Math.floor(R() * 5), a0 = hd + 1.2 + (R() - 0.5) * 0.6; for (let k = 0; k < n; k++) { const ox = px + k * r * 0.35 * Math.cos(hd), oy = py + k * r * 0.35 * Math.sin(hd), ln = r * (0.8 + R() * 0.9); shapes.push({ d: brush(sample([[ox, oy], [ox + Math.cos(a0) * ln * 0.5 + (R() - 0.5) * 3, oy + Math.sin(a0) * ln * 0.5], [ox + Math.cos(a0) * ln, oy + Math.sin(a0) * ln]], false, 5), w * 0.85, R, 0.6), ink: ic, o: sp.o }); } }
        s += Math.max(3, Math.floor((r * (2.4 + R() * 2.6)) / 12));
      }
    }
    // a few loose scribbles that belong to no ribbon, some cut off by the edges
    for (let i = 0; i < Math.round(4 * sp.k * (cfg.ribbons / 6)); i++) {
      const px = (R() < 0.4 ? (R() < 0.5 ? -10 : W + 10) : R() * W), py = R() * H, r = (24 + R() * 50) * sp.k;
      const k = R();
      const pts = k < 0.4 ? spiralPts(px, py, r, R) : k < 0.75 ? blobPts(px, py, r, R) : scribblePts(px, py, r * 3, R() * TAU, R);
      shapes.push({ d: brush(pts, (1.5 + R() * 1.4) * sp.k, R, 0.45), ink: R() < 0.8 ? "green" : "pale", o: sp.o, spin: k < 0.4 ? (R() < 0.5 ? -1 : 1) * (28 + R() * 40) : undefined, at: [px, py] });
    }
    for (let i = 0; i < 12; i++) dots.push({ x: R() * W, y: R() * H, r: (1.5 + R() * 3) * sp.k, d: R() * 6 });
    layers.push({ depth: sp.depth, shapes, dots });
  }
  return { layers, W, H };
}
