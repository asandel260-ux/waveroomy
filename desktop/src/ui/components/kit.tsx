import React, { ReactNode, useEffect, useMemo, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { ICON_PATHS } from "../icons";
import { hsl } from "../theme";
import type { Avatar as AvatarT } from "../../core/models";
import { useFlow } from "../hooks";
import { core } from "../../app/instance";

/* ---------------- icons ---------------- */

export function Icon({ name, size = 20, color, style }: { name: string; size?: number; color?: string; style?: React.CSSProperties }) {
  const paths = ICON_PATHS[name] ?? ICON_PATHS["info"];
  return (
    <svg className="ic" width={size} height={size} viewBox="0 0 24 24" style={{ color, ...style }} aria-hidden>
      {paths.map((d, i) => <path key={i} d={d} />)}
    </svg>
  );
}

/* ---------------- text ---------------- */

export const Tiny = ({ children, style }: { children: ReactNode; style?: React.CSSProperties }) => <span className="tiny" style={style}>{children}</span>;
export const Muted = ({ children, size = 13, style }: { children: ReactNode; size?: number; style?: React.CSSProperties }) => <div className="muted" style={{ fontSize: size, ...style }}>{children}</div>;

/* ---------------- avatar ---------------- */

export function usePhoto(uid: string, mine = false): string | null {
  const v1 = useFlow(core.social.photoVersion);
  const v2 = useFlow(core.profile.photoVersion);
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const bytes = mine ? core.profile.photo : core.social.photoOf(uid);
    if (!bytes) { setUrl(null); return; }
    const u = URL.createObjectURL(new Blob([bytes.slice().buffer]));
    setUrl(u);
    return () => URL.revokeObjectURL(u);
  }, [uid, mine, v1, v2]);
  return url;
}

export function Avatar({ avatar, size = 40, uid = "", mine = false, ring }: { avatar: AvatarT; size?: number; uid?: string; mine?: boolean; ring?: string }) {
  const photo = usePhoto(uid, mine);
  const inner = photo ? (
    <img src={photo} alt="" draggable={false} />
  ) : (
    <div style={{ width: "100%", height: "100%", display: "grid", placeItems: "center", fontSize: size * 0.46, background: `linear-gradient(135deg, ${hsl(avatar.hue, 0.5, 0.38)}, ${hsl(avatar.hue + 40, 0.5, 0.22)})` }}>{avatar.emoji}</div>
  );
  if (ring) return <div className="avatar ring" style={{ width: size, height: size, ["--ring" as any]: ring }}><div className="in">{inner}</div></div>;
  return <div className="avatar" style={{ width: size, height: size }}>{inner}</div>;
}

/* ---------------- buttons ---------------- */

export function Button({ text, onClick, kind = "primary", icon, disabled, small, block, style, title }: {
  text: string; onClick?: (e: React.MouseEvent) => void; kind?: "primary" | "secondary" | "danger" | "ghost" | "ok"; icon?: string; disabled?: boolean; small?: boolean; block?: boolean; style?: React.CSSProperties; title?: string;
}) {
  return (
    <button className={`btn ${kind} ${small ? "small" : ""} ${block ? "block" : ""}`} disabled={disabled} onClick={onClick} style={style} title={title}>
      {icon && <Icon name={icon} size={small ? 15 : 18} />}
      <span className="ellipsis">{text}</span>
    </button>
  );
}

export function IconButton({ icon, onClick, size = 44, iconSize = 20, color, bg, bordered, disabled, badge = 0, title, style }: {
  icon: string; onClick?: (e: React.MouseEvent<HTMLButtonElement>) => void; size?: number; iconSize?: number; color?: string; bg?: string; bordered?: boolean; disabled?: boolean; badge?: number; title?: string; style?: React.CSSProperties;
}) {
  return (
    <button className={`ibtn ${bordered ? "bordered" : ""}`} style={{ width: size, height: size, color, background: bg, ...style }} disabled={disabled} onClick={onClick} title={title ?? icon.replace(/-/g, " ")} aria-label={title ?? icon}>
      <Icon name={icon} size={iconSize} />
      {badge > 0 && <span className="badge">{badge > 9 ? "9+" : badge}</span>}
    </button>
  );
}

export function Chip({ text, selected, onClick, icon, style }: { text: string; selected?: boolean; onClick?: () => void; icon?: string; style?: React.CSSProperties }) {
  const inner = <>{icon && <Icon name={icon} size={14} color={selected ? "var(--accent-bright)" : "var(--muted)"} />}{text}</>;
  return onClick ? <button className={`chip ${selected ? "on" : ""}`} onClick={onClick} style={style}>{inner}</button> : <span className={`chip ${selected ? "on" : ""}`} style={style}>{inner}</span>;
}

/* ---------------- inputs ---------------- */

export function Field({ value, onChange, label, placeholder, multiline, rows = 3, mono, onEnter, password, leadingIcon, maxLength, autoFocus, style, inputRef, readOnly }: {
  value: string; onChange?: (v: string) => void; label?: string; placeholder?: string; multiline?: boolean; rows?: number; mono?: boolean; onEnter?: () => void;
  password?: boolean; leadingIcon?: string; maxLength?: number; autoFocus?: boolean; style?: React.CSSProperties; inputRef?: React.Ref<any>; readOnly?: boolean;
}) {
  const common = {
    value, placeholder, maxLength, autoFocus, readOnly, ref: inputRef as any,
    onChange: (e: React.ChangeEvent<any>) => onChange?.(e.target.value),
    onKeyDown: (e: React.KeyboardEvent) => { if (e.key === "Enter" && (!multiline || e.ctrlKey) && onEnter) { e.preventDefault(); onEnter(); } },
    spellCheck: false
  };
  return (
    <label className={`field ${mono ? "mono" : ""}`} style={style}>
      {label && <Tiny>{label}</Tiny>}
      <div className="box">
        {leadingIcon && <Icon name={leadingIcon} size={18} color="var(--faint)" />}
        {multiline ? <textarea rows={rows} {...common} /> : <input type={password ? "password" : "text"} {...common} />}
      </div>
    </label>
  );
}

export function SwitchRow({ title, subtitle, checked, onChange, disabled }: { title: string; subtitle?: string; checked: boolean; onChange: (b: boolean) => void; disabled?: boolean }) {
  return (
    <div className="switch-row" onClick={() => !disabled && onChange(!checked)} role="switch" aria-checked={checked}>
      <div className="t"><div style={{ fontSize: 15 }}>{title}</div>{subtitle && <Muted size={12}>{subtitle}</Muted>}</div>
      <span className={`switch ${checked ? "on" : ""}`} aria-disabled={disabled} />
    </div>
  );
}

export function Segmented({ options, selected, onSelect }: { options: [string, string][]; selected: string; onSelect: (k: string) => void }) {
  return (
    <div className="seg">
      {options.map(([k, label]) => <button key={k} className={k === selected ? "on" : ""} onClick={() => onSelect(k)}>{label}</button>)}
    </div>
  );
}

/* ---------------- layout pieces ---------------- */

export function ScreenHeader({ title, onBack, subtitle, actions }: { title: string; onBack?: () => void; subtitle?: string; actions?: ReactNode }) {
  return (
    <div className="header">
      <div className="bar">
        {onBack ? <IconButton icon="chevron-left" onClick={onBack} title="Back" /> : <div style={{ width: 8 }} />}
        <div className="title">
          <h1 className="ellipsis">{title}</h1>
          {subtitle && <Muted size={12}><span className="ellipsis" style={{ display: "block" }}>{subtitle}</span></Muted>}
        </div>
        {actions}
      </div>
      <div className="glow" />
    </div>
  );
}

export const SectionTitle = ({ children, style }: { children: ReactNode; style?: React.CSSProperties }) => <div className="section-title" style={style}><i /><Tiny>{children}</Tiny></div>;

export function Card({ children, onClick, style }: { children: ReactNode; onClick?: () => void; style?: React.CSSProperties }) {
  return onClick ? <button className="card" onClick={onClick} style={style}>{children}</button> : <div className="card" style={style}>{children}</div>;
}

export function EmptyState({ icon, title, text, action }: { icon: string; title: string; text: string; action?: ReactNode }) {
  return (
    <div className="empty">
      <div className="orb"><Icon name={icon} size={26} /></div>
      <h3>{title}</h3>
      <Muted>{text}</Muted>
      {action && <div style={{ marginTop: 16 }}>{action}</div>}
    </div>
  );
}

export function Row2({ title, subtitle, icon, trailing, onClick, danger }: { title: ReactNode; subtitle?: ReactNode; icon?: string; trailing?: ReactNode; onClick?: () => void; danger?: boolean }) {
  const inner = (
    <>
      {icon && <div className="ico"><Icon name={icon} size={19} /></div>}
      <div className="grow"><div className="t">{title}</div>{subtitle && <Muted size={12}>{subtitle}</Muted>}</div>
      {trailing ?? (onClick ? <Icon name="chevron-right" size={18} color="var(--faint)" /> : null)}
    </>
  );
  return onClick ? <button className={`row2 ${danger ? "danger" : ""}`} onClick={onClick}>{inner}</button> : <div className={`row2 ${danger ? "danger" : ""}`}>{inner}</div>;
}

/* ---------------- overlays ---------------- */

function Portal({ children }: { children: ReactNode }) { return createPortal(children, document.body); }

function useEscape(cb: () => void) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => { if (e.key === "Escape") { e.stopPropagation(); cb(); } };
    window.addEventListener("keydown", h, true);
    return () => window.removeEventListener("keydown", h, true);
  }, [cb]);
}

export function Dialog({ title, children, actions, onDismiss }: { title?: string; children: ReactNode; actions?: ReactNode; onDismiss: () => void }) {
  useEscape(onDismiss);
  return (
    <Portal>
      <div className="scrim" onMouseDown={(e) => { if (e.target === e.currentTarget) onDismiss(); }}>
        <div className="dialog" role="dialog">
          {title && <h2>{title}</h2>}
          {children}
          {actions && <div className="actions">{actions}</div>}
        </div>
      </div>
    </Portal>
  );
}

export function ConfirmDialog({ title, text, confirm, onConfirm, onDismiss, danger, cancel = "Cancel" }: {
  title: string; text: string; confirm: string; onConfirm: () => void; onDismiss: () => void; danger?: boolean; cancel?: string;
}) {
  return (
    <Dialog title={title} onDismiss={onDismiss} actions={<>
      <button style={{ color: "var(--muted)" }} onClick={onDismiss}>{cancel}</button>
      <button style={{ color: danger ? "var(--danger)" : "var(--accent-bright)" }} onClick={() => { onConfirm(); onDismiss(); }}>{confirm}</button>
    </>}>
      <div className="body-t">{text}</div>
    </Dialog>
  );
}

export function InputDialog({ title, initial = "", placeholder = "", confirm = "OK", onConfirm, onDismiss }: {
  title: string; initial?: string; placeholder?: string; confirm?: string; onConfirm: (v: string) => void; onDismiss: () => void;
}) {
  const [v, setV] = useState(initial);
  return (
    <Dialog title={title} onDismiss={onDismiss} actions={<>
      <button style={{ color: "var(--muted)" }} onClick={onDismiss}>Cancel</button>
      <button style={{ color: "var(--accent-bright)" }} onClick={() => { onConfirm(v.trim()); onDismiss(); }}>{confirm}</button>
    </>}>
      <Field value={v} onChange={setV} placeholder={placeholder} autoFocus onEnter={() => { onConfirm(v.trim()); onDismiss(); }} />
    </Dialog>
  );
}

export function Sheet({ title, children, onDismiss, center }: { title?: string; children: ReactNode; onDismiss: () => void; center?: boolean }) {
  useEscape(onDismiss);
  return (
    <Portal>
      <div className={`scrim ${center ? "" : "end"}`} onMouseDown={(e) => { if (e.target === e.currentTarget) onDismiss(); }}>
        <div className={`sheet ${center ? "center" : ""}`}>
          {!center && <div className="grip" />}
          {title && <h2>{title}</h2>}
          {children}
          <div style={{ height: 12 }} />
        </div>
      </div>
    </Portal>
  );
}

export interface MenuEntry { label: string; icon?: string; danger?: boolean; onClick: () => void }

export function Menu({ at, entries, onDismiss }: { at: { x: number; y: number }; entries: MenuEntry[]; onDismiss: () => void }) {
  useEscape(onDismiss);
  const ref = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState({ left: at.x, top: at.y });
  useEffect(() => {
    const r = ref.current?.getBoundingClientRect();
    if (!r) return;
    setPos({ left: Math.max(8, Math.min(at.x, window.innerWidth - r.width - 8)), top: Math.max(8, Math.min(at.y, window.innerHeight - r.height - 8)) });
  }, [at.x, at.y]);
  return (
    <Portal>
      <div style={{ position: "fixed", inset: 0, zIndex: 59 }} onMouseDown={onDismiss} onContextMenu={(e) => { e.preventDefault(); onDismiss(); }} />
      <div className="menu" ref={ref} style={pos}>
        {entries.map((e, i) => (
          <button key={i} className={e.danger ? "danger" : ""} onClick={() => { onDismiss(); e.onClick(); }}>
            {e.icon && <Icon name={e.icon} size={18} />}{e.label}
          </button>
        ))}
      </div>
    </Portal>
  );
}

/** Hook: returns [open(event), element]. Open from a click (anchored under the element) or a right click. */
export function useMenu(entries: MenuEntry[] | (() => MenuEntry[])): [(e: React.MouseEvent) => void, ReactNode] {
  const [at, setAt] = useState<{ x: number; y: number } | null>(null);
  const open = (e: React.MouseEvent) => {
    e.preventDefault(); e.stopPropagation();
    if (e.type === "contextmenu") setAt({ x: e.clientX, y: e.clientY });
    else { const r = (e.currentTarget as HTMLElement).getBoundingClientRect(); setAt({ x: r.left, y: r.bottom + 4 }); }
  };
  const el = at ? <Menu at={at} entries={typeof entries === "function" ? entries() : entries} onDismiss={() => setAt(null)} /> : null;
  return [open, el];
}

/* ---------------- toasts ---------------- */

export function ToastHost() {
  const [items, setItems] = useState<{ id: number; text: string; out?: boolean }[]>([]);
  useEffect(() => {
    const add = (t: string) => {
      if (!t.trim()) return;
      const id = Math.random();
      setItems((l) => [...l.slice(-2), { id, text: t }]);
      setTimeout(() => setItems((l) => l.map((x) => (x.id === id ? { ...x, out: true } : x))), 3200);
      setTimeout(() => setItems((l) => l.filter((x) => x.id !== id)), 3500);
    };
    const a = core.toasts.on(add);
    const b = core.session.toasts.on(add);
    return () => { a(); b(); };
  }, []);
  return <div className="toasts">{items.map((t) => <div key={t.id} className={`toast ${t.out ? "out" : ""}`}>{t.text}</div>)}</div>;
}

/* ---------------- silk background ---------------- */

export function Silk({ opacity = 1 }: { opacity?: number }) {
  return (
    <svg className="silk" viewBox="0 0 100 100" preserveAspectRatio="none" style={{ opacity }}>
      <defs>
        <linearGradient id="s1" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stopColor="var(--accent-bright)" stopOpacity="0" /><stop offset=".5" stopColor="var(--accent)" stopOpacity=".30" /><stop offset="1" stopColor="var(--accent-bright)" stopOpacity=".06" /></linearGradient>
        <linearGradient id="s2" x1="1" y1="0" x2="0" y2="1"><stop offset="0" stopColor="var(--accent)" stopOpacity=".34" /><stop offset=".5" stopColor="var(--accent-bright)" stopOpacity=".1" /><stop offset="1" stopColor="var(--accent)" stopOpacity="0" /></linearGradient>
        <linearGradient id="s3" x1="0" y1="1" x2="1" y2="0"><stop offset="0" stopColor="#52FF1F" stopOpacity="0" /><stop offset=".5" stopColor="#52FF1F" stopOpacity=".1" /><stop offset="1" stopColor="var(--accent)" stopOpacity=".22" /></linearGradient>
      </defs>
      <path d="M-10 30 C25 0 60 60 110 24 L110 58 C60 94 25 34 -10 64 Z" fill="url(#s1)" />
      <path d="M-10 62 C25 36 60 88 110 52 L110 74 C60 114 25 58 -10 84 Z" fill="url(#s2)" />
      <path d="M-10 82 C25 64 60 100 110 76 L110 92 C60 112 25 90 -10 98 Z" fill="url(#s3)" />
      <path d="M-10 62 C25 36 60 88 110 52" fill="none" stroke="#52FF1F" strokeOpacity=".22" strokeWidth=".8" vectorEffect="non-scaling-stroke" style={{ strokeWidth: 5 }} />
      <path d="M-10 62 C25 36 60 88 110 52" fill="none" stroke="#B8FF93" strokeOpacity=".55" vectorEffect="non-scaling-stroke" style={{ strokeWidth: 1.2 }} />
    </svg>
  );
}

/* ---------------- rich text ---------------- */

type Block = { k: "p" | "q" | "c"; t: string };

export function parseBlocks(src: string): Block[] {
  const out: Block[] = [];
  const lines = src.split("\n");
  let i = 0;
  let para = "";
  const flush = () => { if (para) { out.push({ k: "p", t: para.replace(/\n+$/, "") }); para = ""; } };
  while (i < lines.length) {
    const l = lines[i];
    if (l.trimStart().startsWith("```")) {
      flush();
      let body = ""; i++;
      while (i < lines.length && !lines[i].trimStart().startsWith("```")) { body += lines[i] + "\n"; i++; }
      out.push({ k: "c", t: body.replace(/\n+$/, "") });
      i++; continue;
    }
    if (l.startsWith(">")) {
      flush();
      let q = "";
      while (i < lines.length && lines[i].startsWith(">")) { q += lines[i].slice(1).trimStart() + "\n"; i++; }
      out.push({ k: "q", t: q.replace(/\n+$/, "") });
      continue;
    }
    para += l + "\n"; i++;
  }
  flush();
  return out;
}

const URL_RE = /https?:\/\/[^\s<>"]+/y;
const LINK_RE = /^\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)/;
export const firstUrl = (text: string): string | null => { const m = /https?:\/\/[^\s<>"]+/.exec(text); return m ? m[0].replace(/[.,)!?]+$/, "") : null; };

function inline(src: string, revealed: Set<number>, reveal: (i: number) => void, counter: { n: number }, key = ""): ReactNode[] {
  const out: ReactNode[] = [];
  let i = 0, buf = "", k = 0;
  const flush = () => { if (buf) { out.push(buf); buf = ""; } };
  const close = (tok: string, from: number) => { const j = src.indexOf(tok, from); return j > from ? j : -1; };
  while (i < src.length) {
    let e: number;
    if (src.startsWith("**", i) && (e = close("**", i + 2)) > 0) { flush(); out.push(<b key={key + k++}>{inline(src.slice(i + 2, e), revealed, reveal, counter, key + k)}</b>); i = e + 2; }
    else if (src.startsWith("__", i) && (e = close("__", i + 2)) > 0) { flush(); out.push(<i key={key + k++}>{inline(src.slice(i + 2, e), revealed, reveal, counter, key + k)}</i>); i = e + 2; }
    else if (src.startsWith("~~", i) && (e = close("~~", i + 2)) > 0) { flush(); out.push(<s key={key + k++}>{inline(src.slice(i + 2, e), revealed, reveal, counter, key + k)}</s>); i = e + 2; }
    else if (src.startsWith("||", i) && (e = close("||", i + 2)) > 0) {
      flush();
      const idx = counter.n++;
      const inner = src.slice(i + 2, e);
      out.push(revealed.has(idx) ? <code key={key + k++}>{inner}</code> : <span key={key + k++} className="spoiler" onClick={() => reveal(idx)}>{inner}</span>);
      i = e + 2;
    }
    else if (src[i] === "`" && (e = src.indexOf("`", i + 1)) > 0) { flush(); out.push(<code key={key + k++}>{src.slice(i + 1, e)}</code>); i = e + 1; }
    else if (src[i] === "[" && LINK_RE.test(src.slice(i))) {
      const m = LINK_RE.exec(src.slice(i))!;
      flush(); out.push(<a key={key + k++} onClick={() => core.openUrl(m[2])} style={{ color: "var(--link, inherit)" }}>{m[1]}</a>); i += m[0].length;
    }
    else if (src.startsWith("http://", i) || src.startsWith("https://", i)) {
      URL_RE.lastIndex = i;
      const m = URL_RE.exec(src);
      if (m) { const u = m[0].replace(/[.,)!?]+$/, ""); flush(); out.push(<a key={key + k++} onClick={() => core.openUrl(u)}>{u}</a>); i += u.length; }
      else buf += src[i++];
    }
    else buf += src[i++];
  }
  flush();
  return out;
}

/** Message formatting, Telegram-style: **bold** __italic__ ~~strike~~ `mono` ||spoiler|| [text](url) > quote ```code``` */
export function RichText({ text, color, linkColor, size = 15 }: { text: string; color: string; linkColor: string; size?: number }) {
  const [revealed, setRevealed] = useState<Set<number>>(new Set());
  useEffect(() => setRevealed(new Set()), [text]);
  const blocks = useMemo(() => parseBlocks(text), [text]);
  const reveal = (i: number) => setRevealed((s) => new Set(s).add(i));
  const counter = { n: 0 };
  return (
    <div className="rich selectable" style={{ color, fontSize: size, ["--link" as any]: linkColor }}>
      {blocks.map((b, i) =>
        b.k === "p" ? <p key={i}>{inline(b.t, revealed, reveal, counter)}</p> :
        b.k === "q" ? <blockquote key={i} style={{ borderColor: linkColor }}>{inline(b.t, revealed, reveal, counter)}</blockquote> :
        <pre key={i}>{b.t}</pre>
      )}
    </div>
  );
}

/* ---------------- backdrops, hero card, neon wave ---------------- */

const mix = (c: string, pct: number) => `color-mix(in srgb, ${c} ${pct}%, var(--bg))`;

/** The ground every full screen stands on: violet at the top fading to the base colour. style 0 silk, 1 aurora (room), 2 beams (settings), 3 contours (chat), 4 glows (forms). */
export function Backdrop({ style = 0, children, className = "", css }: { style?: number; children?: ReactNode; className?: string; css?: React.CSSProperties }) {
  const base = `linear-gradient(180deg, ${mix("var(--accent)", 22)}, ${mix("var(--accent)", 7)} 45%, var(--bg))`;
  let layers = "";
  if (style === 1) layers = `radial-gradient(circle at 15% 12%, rgb(124 58 237 / .34), transparent 70%), radial-gradient(circle at 95% 45%, rgb(82 255 31 / .075), transparent 55%), radial-gradient(circle at 50% 100%, rgb(124 58 237 / .18), transparent 65%), `;
  else if (style === 2) layers = `linear-gradient(110deg, transparent 40%, rgb(182 144 255 / .10) 45%, transparent 52%), linear-gradient(110deg, transparent 58%, rgb(182 144 255 / .07) 62%, transparent 68%), `;
  else if (style === 3) layers = `radial-gradient(circle at 0 0, rgb(124 58 237 / .22), transparent 70%), radial-gradient(circle at 100% 100%, rgb(82 255 31 / .05), transparent 60%), `;
  else if (style === 4) layers = `radial-gradient(circle at 100% 10%, rgb(124 58 237 / .34), transparent 65%), radial-gradient(circle at 0 88%, rgb(82 255 31 / .07), transparent 60%), `;
  return (
    <div className={`backdrop ${className}`} style={{ position: "relative", flex: 1, minHeight: 0, display: "flex", flexDirection: "column", background: layers + base, ...css }}>
      {style === 0 && <Silk opacity={0.34} />}
      {children}
    </div>
  );
}

export function NeonWave({ height = 160, amplitude = 42, stroke = 4, animated = true }: { height?: number; amplitude?: number; stroke?: number; animated?: boolean }) {
  const ref = useRef<HTMLCanvasElement>(null);
  useEffect(() => {
    const cv = ref.current;
    if (!cv) return;
    let raf = 0;
    const draw = (t: number) => {
      const dpr = window.devicePixelRatio || 1;
      const w = cv.clientWidth, h = cv.clientHeight;
      if (cv.width !== Math.round(w * dpr)) { cv.width = Math.round(w * dpr); cv.height = Math.round(h * dpr); }
      const g = cv.getContext("2d")!;
      g.setTransform(dpr, 0, 0, dpr, 0, 0);
      g.clearRect(0, 0, w, h);
      const phase = animated && !document.documentElement.classList.contains("no-anim") ? ((t % 4200) / 4200) * Math.PI * 2 : 1.2;
      const cy = h / 2, amp = Math.min(amplitude, h / 2 - stroke);
      g.beginPath();
      for (let x = -6; x <= w + 6; x += 3) {
        const u = x / w;
        const env = 0.62 + 0.38 * Math.sin(u * 3 * Math.PI + phase);
        const y = cy + amp * env * Math.sin(u * 5 * Math.PI - phase);
        if (x === -6) g.moveTo(x, y); else g.lineTo(x, y);
      }
      g.lineCap = "round"; g.lineJoin = "round";
      for (const [wm, a] of [[5, 0.1], [2.4, 0.22], [1, 1]] as const) { g.strokeStyle = `rgba(82,255,31,${a})`; g.lineWidth = stroke * wm; g.stroke(); }
      if (animated) raf = requestAnimationFrame(draw);
    };
    raf = requestAnimationFrame(draw);
    return () => cancelAnimationFrame(raf);
  }, [amplitude, stroke, animated]);
  return <canvas ref={ref} style={{ width: "100%", height, display: "block" }} />;
}

export function HeroCard({ children, radius = 22, style }: { children: ReactNode; radius?: number; style?: React.CSSProperties }) {
  return (
    <div style={{ position: "relative", borderRadius: radius, overflow: "hidden", background: "linear-gradient(180deg, var(--hero-a), var(--hero-b), var(--hero-c))", boxShadow: "0 0 0 1px rgb(182 144 255 / .45)", ...style }}>
      <Silk />
      <div style={{ position: "relative" }}>{children}</div>
    </div>
  );
}

export function GroupAvatar({ emoji, hue, size }: { emoji: string; hue: number; size: number }) {
  return (
    <div style={{ width: size, height: size, borderRadius: "50%", display: "grid", placeItems: "center", flex: "none", fontSize: size * 0.5, background: `linear-gradient(135deg, ${hsl(hue, 0.55, 0.42)}, ${hsl(hue + 40, 0.5, 0.22)})`, boxShadow: "inset 0 0 0 1px var(--border)" }}>
      {emoji === "👥" ? "💬" : emoji}
    </div>
  );
}

export function Spinner({ size = 20 }: { size?: number }) {
  return <span style={{ width: size, height: size, borderRadius: "50%", border: "2px solid var(--accent-30)", borderTopColor: "var(--accent-bright)", display: "inline-block", animation: "spin .8s linear infinite" }} />;
}
