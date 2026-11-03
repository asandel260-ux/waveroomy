import { useState } from "react";
import { Icon, Muted, Tiny, Button, Field } from "./kit";

function hsvToRgb(h: number, s: number, v: number): [number, number, number] {
  const f = (n: number) => { const k = (n + h / 60) % 6; return v - v * s * Math.max(0, Math.min(k, 4 - k, 1)); };
  return [Math.round(f(5) * 255), Math.round(f(3) * 255), Math.round(f(1) * 255)];
}
const toArgb = (r: number, g: number, b: number) => ((0xff << 24) | (r << 16) | (g << 8) | b) | 0;
const hsvArgb = (h: number, s: number, v: number) => toArgb(...hsvToRgb(h, s, v));
function argbToHsv(argb: number): [number, number, number] {
  const r = ((argb >> 16) & 255) / 255, g = ((argb >> 8) & 255) / 255, b = (argb & 255) / 255;
  const mx = Math.max(r, g, b), mn = Math.min(r, g, b), d = mx - mn;
  let h = 0;
  if (d > 0) h = mx === r ? ((g - b) / d) % 6 : mx === g ? (b - r) / d + 2 : (r - g) / d + 4;
  return [(h * 60 + 360) % 360, mx === 0 ? 0 : d / mx, mx];
}
const css = (argb: number) => "#" + (argb & 0xffffff).toString(16).padStart(6, "0");
const hexOf = (argb: number) => css(argb).toUpperCase();
const parseHex = (s: string): number | null => { const t = s.trim().replace(/^#/, ""); return /^[0-9a-fA-F]{6}$/.test(t) ? (0xff000000 | parseInt(t, 16)) | 0 : null; };

export const ACCENT_PRESETS = [0xFF7C3AED, 0xFF3B82F6, 0xFF06B6D4, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFFEC4899, 0xFFA855F7, 0xFFFAFAFA].map((x) => x | 0);
export const BG_PRESETS = [0xFF0B0713, 0xFF000000, 0xFF0B1220, 0xFF0A1A14, 0xFF1A0B14, 0xFF1B1B1F, 0xFF2B2B33, 0xFFF4F4F6].map((x) => x | 0);

function Swatch({ c, selected, onClick }: { c: number; selected: boolean; onClick: () => void }) {
  const lum = (((c >> 16) & 255) * 0.3 + ((c >> 8) & 255) * 0.6 + (c & 255) * 0.1) / 255;
  return (
    <button onClick={onClick} aria-label={`Colour ${css(c)}`} aria-pressed={selected} style={{ width: 34, height: 34, borderRadius: "50%", background: css(c), display: "grid", placeItems: "center", boxShadow: `inset 0 0 0 ${selected ? 2 : 1}px ${selected ? "var(--text)" : "var(--border-strong)"}` }}>
      {selected && <Icon name="check" size={16} color={lum > 0.55 ? "#000" : "#fff"} />}
    </button>
  );
}

/** Preset swatches + a custom HSV picker. [value] is ARGB, 0 meaning "default". */
export function ColorPickerCard({ title, subtitle, value, def, presets, onChange }: { title: string; subtitle: string; value: number; def: number; presets: number[]; onChange: (c: number) => void }) {
  const [custom, setCustom] = useState(false);
  const shown = value === 0 ? def : value;
  const [h0, s0, v0] = argbToHsv(shown);
  const [h, setH] = useState(h0), [s, setS] = useState(s0), [v, setV] = useState(v0);
  const [hex, setHex] = useState(hexOf(shown));
  const sync = (c: number) => { const [a, b, d] = argbToHsv(c); setH(a); setS(b); setV(d); setHex(hexOf(c)); };
  const apply = (nh: number, ns: number, nv: number) => { const c = hsvArgb(nh, ns, nv); setHex(hexOf(c)); onChange(c); };
  const range = (label: string, val: number, max: number, set: (x: number) => void, f: (x: number) => void) => (
    <div><Tiny>{label}</Tiny><input type="range" min={0} max={max} step={max / 360} value={val} style={{ width: "100%", accentColor: css(shown) }} onChange={(e) => { const x = parseFloat(e.target.value); set(x); f(x); }} /></div>
  );
  return (
    <div style={{ borderRadius: 10, background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)", padding: 14 }}>
      <div className="hstack g10">
        <div className="grow"><div style={{ fontSize: 15, fontWeight: 600 }}>{title}</div><Muted size={12}>{subtitle}</Muted></div>
        <span style={{ width: 30, height: 30, borderRadius: "50%", background: css(shown), boxShadow: "inset 0 0 0 1px var(--border-strong)" }} />
      </div>
      <div className="hstack" style={{ flexWrap: "wrap", gap: 10, marginTop: 12 }}>
        <Swatch c={def} selected={value === 0} onClick={() => { onChange(0); sync(def); }} />
        {presets.map((p) => <Swatch key={p} c={p} selected={value !== 0 && (value & 0xffffff) === (p & 0xffffff)} onClick={() => { onChange(p); sync(p); }} />)}
        <button onClick={() => setCustom(!custom)} aria-label="Custom colour" style={{ width: 34, height: 34, borderRadius: "50%", display: "grid", placeItems: "center", boxShadow: `inset 0 0 0 1px ${custom ? "var(--accent-bright)" : "var(--border-strong)"}` }}><Icon name="sliders" size={16} /></button>
      </div>
      {custom ? (
        <div style={{ marginTop: 10 }}>
          {range("Hue", h, 360, setH, (x) => apply(x, s, v))}
          {range("Saturation", s, 1, setS, (x) => apply(h, x, v))}
          {range("Brightness", v, 1, setV, (x) => apply(h, s, x))}
          <div className="hstack g8">
            <div className="grow"><Field mono placeholder="#RRGGBB" maxLength={7} value={hex} onChange={(t) => { setHex(t); const c = parseHex(t); if (c != null) onChange(c); }} /></div>
            <Button small kind="ghost" text="Reset" onClick={() => { onChange(0); sync(def); }} />
          </div>
        </div>
      ) : value !== 0 ? <div style={{ marginTop: 10 }}><Button small kind="ghost" text="Reset to default" onClick={() => { onChange(0); sync(def); }} /></div> : null}
    </div>
  );
}
