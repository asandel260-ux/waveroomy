import type { Settings } from "../core/store";

/* HoMit palette (same values as the Android WR object), applied as CSS variables. */

const rgb = (n: number): [number, number, number] => [(n >> 16) & 255, (n >> 8) & 255, n & 255];
const toCss = (c: [number, number, number]) => `rgb(${Math.round(c[0])} ${Math.round(c[1])} ${Math.round(c[2])})`;
const lerp = (a: [number, number, number], b: [number, number, number], k: number): [number, number, number] => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
const lum = (c: [number, number, number]) => (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255;
const WHITE: [number, number, number] = [255, 255, 255];
const BLACK: [number, number, number] = [0, 0, 0];
const INK: [number, number, number] = [0x12, 0x03, 0x2b];

export function applyTheme(st: Pick<Settings, "accentColor" | "bgColor" | "animations">) {
  const root = document.documentElement.style;
  const custBg = st.bgColor !== 0;
  const bg = custBg ? rgb(st.bgColor) : rgb(0x0a0710);
  const light = lum(bg) > 0.5;
  const tint = (f: number) => lerp(bg, light ? BLACK : WHITE, f);
  const accent = st.accentColor !== 0 ? rgb(st.accentColor) : rgb(0x7c3aed);
  const accentBright = st.accentColor !== 0 ? (light ? lerp(accent, BLACK, 0.25) : lerp(accent, WHITE, 0.3)) : rgb(0xb690ff);
  const set = (k: string, v: string) => root.setProperty(k, v);
  set("--bg", toCss(bg));
  set("--surface", custBg ? toCss(tint(0.03)) : "rgb(17 11 28)");
  set("--surface2", custBg ? toCss(tint(0.06)) : "rgb(24 16 38)");
  set("--surface3", custBg ? toCss(tint(0.1)) : "rgb(33 23 58)");
  set("--border", custBg ? toCss(tint(0.14)) : "rgb(42 29 69)");
  set("--border-strong", custBg ? toCss(tint(0.24)) : "rgb(64 46 105)");
  set("--text", light ? "rgb(17 17 20)" : "rgb(242 239 249)");
  set("--muted", light ? "rgb(85 85 94)" : "rgb(171 163 194)");
  set("--faint", light ? "rgb(110 110 120)" : "rgb(138 129 163)");
  set("--accent", toCss(accent));
  set("--accent-bright", toCss(accentBright));
  const a = (al: number) => `rgb(${accent.map(Math.round).join(" ")} / ${al})`;
  const ab = (al: number) => `rgb(${accentBright.map(Math.round).join(" ")} / ${al})`;
  set("--accent-soft", a(0.2));
  set("--accent-30", a(0.3));
  set("--accent-10", a(0.1));
  set("--accent-bright-22", ab(0.22));
  set("--accent-bright-70", ab(0.7));
  set("--grad-top", toCss(lerp(accent, WHITE, 0.2)));
  set("--grad-bottom", toCss(lerp(accent, INK, 0.58)));
  set("--bubble-a", toCss(lerp(accent, WHITE, 0.06)));
  set("--bubble-b", toCss(lerp(accent, INK, 0.5)));
  set("--hero-a", toCss(lerp(accent, BLACK, 0.86)));
  set("--hero-b", toCss(lerp(accent, BLACK, 0.62)));
  set("--hero-c", toCss(lerp(accent, INK, 0.12)));
  set("--rim", `linear-gradient(135deg, ${ab(0.75)}, rgb(82 255 31 / 0.42), ${a(0.25)})`);
  set("--anim", st.animations ? "1" : "0");
  document.documentElement.classList.toggle("light", light);
  document.documentElement.classList.toggle("no-anim", !st.animations);
}

/** Avatar / accent colours from a hue, same maths as WR.hue. */
export function hsl(h: number, s = 0.45, l = 0.32): string {
  const hh = (((h % 360) + 360) % 360);
  return `hsl(${hh} ${s * 100}% ${l * 100}%)`;
}
