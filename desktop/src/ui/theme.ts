import type { Settings } from "../core/store";

/* HoMit palette (same values as the Android WR object), applied as CSS variables. */

const rgb = (n: number): [number, number, number] => [(n >> 16) & 255, (n >> 8) & 255, n & 255];
const toCss = (c: [number, number, number]) => `rgb(${Math.round(c[0])} ${Math.round(c[1])} ${Math.round(c[2])})`;
const lerp = (a: [number, number, number], b: [number, number, number], k: number): [number, number, number] => [a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k];
const lum = (c: [number, number, number]) => (0.2126 * c[0] + 0.7152 * c[1] + 0.0722 * c[2]) / 255;
const WHITE: [number, number, number] = [255, 255, 255];
const BLACK: [number, number, number] = [0, 0, 0];

type Rgb = [number, number, number];
const hex = (s: string): Rgb => rgb(parseInt(s.slice(1), 16));

/** Two complete palettes (see DESIGN.md for the reasoning). The owner's custom accent and background override them. */
const PALETTES = {
  dark: { bg: "#4a2679", s1: "rgb(33 11 68 / .86)", s2: "rgb(45 17 90 / .88)", s3: "rgb(62 26 116 / .92)", b1: "rgb(91 224 51 / .42)", b2: "rgb(91 224 51 / .85)", text: "#ffffff", muted: "#eadfff", faint: "#d8caf8", accent: "#5be033", bright: "#8dfc62", live: "#52ff1f", onLive: "#052200", doodle: "#5be033", danger: "#ffa3a3", warn: "#f5b544", scrim: "rgb(26 6 56 / .78)", lift: "0 14px 36px rgb(15 0 40 / .55)" },
  light: { bg: "#efe6ff", s1: "rgb(255 255 255 / .86)", s2: "rgb(243 234 255 / .92)", s3: "rgb(228 213 255 / .95)", b1: "rgb(40 140 20 / .45)", b2: "rgb(40 140 20 / .9)", text: "#1a0f3d", muted: "#4a3d78", faint: "#5d5088", accent: "#1a7a08", bright: "#0f5a05", live: "#178009", onLive: "#ffffff", doodle: "#2f9a16", danger: "#a81f1f", warn: "#9a5b00", scrim: "rgb(26 15 61 / .45)", lift: "0 12px 32px rgb(60 20 140 / .2)" },
} as const;

export function resolveMode(theme: string): "dark" | "light" {
  if (theme === "light" || theme === "dark") return theme;
  return window.matchMedia?.("(prefers-color-scheme: light)").matches ? "light" : "dark";
}

export function applyTheme(st: Pick<Settings, "accentColor" | "bgColor" | "animations" | "theme">) {
  const root = document.documentElement.style;
  const custBg = st.bgColor !== 0;
  let mode = resolveMode(st.theme);
  if (custBg) mode = lum(rgb(st.bgColor)) > 0.5 ? "light" : "dark";
  const P = PALETTES[mode];
  const light = mode === "light";
  const set = (k: string, v: string) => root.setProperty(k, v);
  const bg = custBg ? rgb(st.bgColor) : hex(P.bg);
  const tint = (f: number) => lerp(bg, light ? BLACK : WHITE, f);
  set("--bg", toCss(bg));
  set("--surface", custBg ? toCss(tint(0.04)) : P.s1);
  set("--surface2", custBg ? toCss(tint(0.07)) : P.s2);
  set("--surface3", custBg ? toCss(tint(0.11)) : P.s3);
  set("--border", custBg ? toCss(tint(0.14)) : P.b1);
  set("--border-strong", custBg ? toCss(tint(0.26)) : P.b2);
  set("--text", P.text); set("--muted", P.muted); set("--faint", P.faint);
  const accent = st.accentColor !== 0 ? rgb(st.accentColor) : hex(P.accent);
  const bright = st.accentColor !== 0 ? (light ? lerp(accent, BLACK, 0.3) : lerp(accent, WHITE, 0.35)) : hex(P.bright);
  set("--accent", toCss(accent));
  set("--accent-bright", toCss(bright));
  set("--focus", P.live);
  set("--on-accent", lum(accent) > 0.62 ? "#120a22" : "#ffffff");
  const a = (al: number) => `rgb(${accent.map(Math.round).join(" ")} / ${al})`;
  const ab = (al: number) => `rgb(${bright.map(Math.round).join(" ")} / ${al})`;
  set("--accent-soft", a(0.18)); set("--accent-30", a(0.3)); set("--accent-10", a(0.08));
  set("--accent-bright-22", ab(0.22)); set("--accent-bright-70", ab(0.7));
  for (const k of ["--grad-top", "--grad-bottom"]) set(k, toCss(accent));
  for (const k of ["--bubble-a", "--bubble-b"]) set(k, toCss(light ? accent : lerp(accent, BLACK, 0.12)));
  for (const k of ["--hero-a", "--hero-b", "--hero-c"]) set(k, P.s1);
  set("--doodle", P.doodle);
  set("--live", P.live); set("--on-live", P.onLive);
  for (const k of ["--ok", "--neon", "--neon-soft", "--neon-deep"]) set(k, P.live);
  set("--danger", P.danger); set("--warn", P.warn);
  set("--danger-soft", light ? "rgb(198 40 40 / .1)" : "rgb(255 107 107 / .14)");
  set("--scrim", P.scrim); set("--lift", P.lift);
  set("--anim", st.animations ? "1" : "0");
  document.documentElement.classList.toggle("light", light);
  document.documentElement.classList.toggle("no-anim", !st.animations);
}

/** Avatar / accent colours from a hue, same maths as WR.hue. */
export function hsl(h: number, s = 0.45, l = 0.32): string {
  const hh = (((h % 360) + 360) % 360);
  return `hsl(${hh} ${s * 100}% ${l * 100}%)`;
}
