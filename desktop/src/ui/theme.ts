import type { Settings } from "../core/store";

/* HoMit palette (same values as the Android WR object), applied as CSS variables. */

const rgb = (n: number): [number, number, number] => [(n >> 16) & 255, (n >> 8) & 255, n & 255];
const toCss = (c: [number, number, number]) => `rgb(${Math.round(c[0])} ${Math.round(c[1])} ${Math.round(c[2])})`;
const lerp = (a: [number, number, number], b: [number, number, number], k: number): [number, number, number] => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
const lum = (c: [number, number, number]) => (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255;
const WHITE: [number, number, number] = [255, 255, 255];
const BLACK: [number, number, number] = [0, 0, 0];

export function applyTheme(st: Pick<Settings, "accentColor" | "bgColor" | "animations">) {
  const root = document.documentElement.style;
  const custBg = st.bgColor !== 0;
  const bg = custBg ? rgb(st.bgColor) : rgb(0x0a0a0a);
  const light = lum(bg) > 0.5;
  const tint = (f: number) => lerp(bg, light ? BLACK : WHITE, f);
  const accent = st.accentColor !== 0 ? rgb(st.accentColor) : rgb(0x2e5bff);
  const accentBright = light ? lerp(accent, BLACK, 0.2) : lerp(accent, WHITE, 0.4);
  const set = (k: string, v: string) => root.setProperty(k, v);
  set("--bg", toCss(bg));
  set("--surface", toCss(tint(0.04)));
  set("--surface2", toCss(tint(0.07)));
  set("--surface3", toCss(tint(0.11)));
  set("--border", toCss(tint(0.14)));
  set("--border-strong", toCss(tint(0.26)));
  set("--text", light ? "rgb(10 10 10)" : "rgb(250 250 250)");
  set("--muted", light ? "rgb(82 82 82)" : "rgb(163 163 163)");
  set("--faint", light ? "rgb(115 115 115)" : "rgb(115 115 115)");
  set("--accent", toCss(accent));
  set("--accent-bright", toCss(accentBright));
  set("--on-accent", lum(accent) > 0.6 ? "#000" : "#fff");
  const a = (al: number) => `rgb(${accent.map(Math.round).join(" ")} / ${al})`;
  const ab = (al: number) => `rgb(${accentBright.map(Math.round).join(" ")} / ${al})`;
  set("--accent-soft", a(0.16));
  set("--accent-30", a(0.3));
  set("--accent-10", a(0.08));
  set("--accent-bright-22", ab(0.22));
  set("--accent-bright-70", ab(0.7));
  // one flat accent everywhere; kept as variables so older call sites stay valid
  for (const k of ["--grad-top", "--grad-bottom", "--bubble-a", "--bubble-b"]) set(k, toCss(accent));
  for (const k of ["--hero-a", "--hero-b", "--hero-c"]) set(k, toCss(tint(0.04)));
  set("--anim", st.animations ? "1" : "0");
  document.documentElement.classList.toggle("light", light);
  document.documentElement.classList.toggle("no-anim", !st.animations);
}

/** Avatar / accent colours from a hue, same maths as WR.hue. */
export function hsl(h: number, s = 0.45, l = 0.32): string {
  const hh = (((h % 360) + 360) % 360);
  return `hsl(${hh} ${s * 100}% ${l * 100}%)`;
}
