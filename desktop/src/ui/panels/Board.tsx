import { useEffect, useMemo, useReducer, useRef, useState } from "react";
import { core } from "../../app/instance";
import { native } from "../../core/native";
import { rgbToHex } from "../../core/models";
import { nowMs } from "../../core/util";
import { useFlow, useTick } from "../hooks";
import { Button, Dialog, Field, Icon, IconButton, Muted, Segmented, Sheet, Tiny } from "../components/kit";
import { BoardExport, BoardGeo, BoardRenderer } from "./boardCore";
import { BOARD_COLORS, BoardController, DRAW_TOOLS, type TextEdit } from "./BoardController";

const BG = "#0b0812";
const ACCENT = "#7c3aed";

const TOOL_HINTS: Record<string, string> = {
  select: "Click to select · drag to move · drag empty space to box-select",
  hand: "Drag to move around the board",
  pencil: "Draw freely", pen: "Draw with the pen", highlight: "Highlight with a soft marker",
  eraser: "Drag over drawings to erase them", line: "Drag to draw a line", arrow: "Drag to draw an arrow",
  rect: "Drag to draw a rectangle", ellipse: "Drag to draw an ellipse", text: "Click anywhere to add text",
  laser: "Press and drag — everyone sees your pointer",
};
const TOOLS: [string, string][] = [
  ["select", "select"], ["hand", "hand"], ["|", ""],
  ["pencil", "pencil"], ["pen", "pen"], ["highlight", "highlighter"], ["eraser", "eraser"], ["|", ""],
  ["line", "line"], ["arrow", "arrow"], ["rect", "square"], ["ellipse", "circle"], ["text", "type"], ["image", "image"], ["|", ""],
  ["laser", "laser"],
];

function hsv(h: number, s: number, v: number, a = 1): string {
  const f = (n: number) => { const k = (n + h / 60) % 6; return v - v * s * Math.max(0, Math.min(k, 4 - k, 1)); };
  return `rgba(${Math.round(f(5) * 255)},${Math.round(f(3) * 255)},${Math.round(f(1) * 255)},${a})`;
}

/** Reads an image file into a JPEG the board can carry on the wire (kept small, like the phone does). */
async function boardJpeg(file: File): Promise<{ b64: string; w: number; h: number } | null> {
  try {
    const bmp = await createImageBitmap(file);
    const k = Math.min(1, 1600 / Math.max(bmp.width, bmp.height));
    const w = Math.max(1, Math.round(bmp.width * k)), h = Math.max(1, Math.round(bmp.height * k));
    const cv = document.createElement("canvas"); cv.width = w; cv.height = h;
    const cx = cv.getContext("2d")!; cx.fillStyle = "#fff"; cx.fillRect(0, 0, w, h); cx.drawImage(bmp, 0, 0, w, h);
    const url = cv.toDataURL("image/jpeg", 0.8);
    return { b64: url.slice(url.indexOf(",") + 1), w, h };
  } catch { return null; }
}

export function BoardPanel() {
  const session = core.session;
  const room = session.room;
  if (!room) return null;
  return <BoardInner key={room.meta.id} />;
}

function BoardInner() {
  const session = core.session;
  const board = session.room!.board;
  const [, bump] = useReducer((n: number) => n + 1, 0);
  const ctrl = useMemo(() => new BoardController(session, board, bump), [board]);
  const renderer = useMemo(() => new BoardRenderer(ctrl.images), [ctrl]);
  const cursors = useFlow(session.cursors);
  const tick = useTick(session.tick);
  const wrap = useRef<HTMLDivElement>(null);
  const cv = useRef<HTMLCanvasElement>(null);
  const file = useRef<HTMLInputElement>(null);
  const [exportOpen, setExportOpen] = useState(false);
  const [railOpen, setRailOpen] = useState(true);
  const [paletteOpen, setPaletteOpen] = useState(false);
  const [hint, setHint] = useState<string | null>(null);
  const fitted = useRef(false);
  const pointers = useRef(new Map<number, { x: number; y: number }>());
  const multi = useRef<{ cx: number; cy: number; d: number } | null>(null);
  const spacePan = useRef(false);
  const lastPos = useRef({ x: 0, y: 0 });

  useEffect(() => () => ctrl.dispose(), [ctrl]);
  useEffect(() => { setHint(TOOL_HINTS[ctrl.tool] ?? null); const t = setTimeout(() => setHint(null), 2400); return () => clearTimeout(t); }, [ctrl.tool]);

  // keep the selection to objects that still exist
  useEffect(() => {
    const alive = [...ctrl.selection].filter((i) => board.objects.has(i));
    if (alive.length !== ctrl.selection.size) ctrl.setSelection(alive);
  }, [board.version, tick]);

  // other people's lasers
  useEffect(() => {
    for (const [sid, c] of cursors) {
      if (!c.laser) continue;
      let l = ctrl.lasers.get(sid); if (!l) ctrl.lasers.set(sid, (l = []));
      l.push({ x: c.x, y: c.y, t: nowMs() });
    }
  }, [cursors]);
  useEffect(() => {
    const id = setInterval(() => {
      const t = nowMs();
      for (const [k, l] of ctrl.lasers) { const keep = l.filter((p) => t - p.t <= 900); if (keep.length) ctrl.lasers.set(k, keep); else ctrl.lasers.delete(k); }
      if (ctrl.lasers.size || session.cursors.value.size) bump();
    }, 33);
    return () => clearInterval(id);
  }, [ctrl]);

  const pickImage = () => file.current?.click();
  const onFile = async (f: File | undefined) => {
    ctrl.tool = "select"; bump();
    if (!f) return;
    const r = await boardJpeg(f);
    if (r) ctrl.placeImage(r.b64, r.w, r.h); else core.toast("Could not read that image");
  };

  // size
  useEffect(() => {
    const el = wrap.current; if (!el) return;
    const ro = new ResizeObserver(() => {
      ctrl.vw = el.clientWidth; ctrl.vh = el.clientHeight;
      if (!fitted.current && el.clientWidth > 0) { fitted.current = true; ctrl.fitAll(); } else bump();
    });
    ro.observe(el);
    return () => ro.disconnect();
  }, [ctrl]);

  // keyboard
  useEffect(() => {
    const typing = () => { const a = document.activeElement as HTMLElement | null; return !!a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA" || a.isContentEditable); };
    const down = (e: KeyboardEvent) => {
      if (typing() || ctrl.textEdit || document.querySelector(".dialog, .sheet")) return;
      const mod = e.ctrlKey || e.metaKey;
      if (e.code === "Space" && !e.repeat) { spacePan.current = true; e.preventDefault(); return; }
      if (mod && e.key.toLowerCase() === "z") { e.preventDefault(); if (e.shiftKey) ctrl.redo(); else ctrl.undo(); }
      else if (mod && e.key.toLowerCase() === "y") { e.preventDefault(); ctrl.redo(); }
      else if (mod && e.key.toLowerCase() === "d") { e.preventDefault(); ctrl.duplicate(); }
      else if (mod && e.key.toLowerCase() === "a") { e.preventDefault(); ctrl.setSelection([...board.objects.keys()]); }
      else if (e.key === "Delete" || e.key === "Backspace") { ctrl.deleteSelection(); }
      else if (e.key === "Escape") { ctrl.setSelection([]); }
    };
    const up = (e: KeyboardEvent) => { if (e.code === "Space") spacePan.current = false; };
    window.addEventListener("keydown", down); window.addEventListener("keyup", up);
    return () => { window.removeEventListener("keydown", down); window.removeEventListener("keyup", up); };
  }, [ctrl]);

  // paste an image from the clipboard
  useEffect(() => {
    const onPaste = (e: ClipboardEvent) => {
      if (ctrl.textEdit || document.querySelector(".dialog, .sheet")) return;
      const a = document.activeElement as HTMLElement | null;
      if (a && (a.tagName === "INPUT" || a.tagName === "TEXTAREA")) return;
      const f = [...(e.clipboardData?.files ?? [])].find((x) => x.type.startsWith("image/"));
      if (f) { e.preventDefault(); void onFile(f); }
    };
    window.addEventListener("paste", onPaste);
    return () => window.removeEventListener("paste", onPaste);
  }, [ctrl]);

  // wheel: ctrl/pinch zooms, plain wheel pans (trackpads) – a mouse wheel zooms too when it clearly steps
  useEffect(() => {
    const el = cv.current; if (!el) return;
    const w = (e: WheelEvent) => {
      e.preventDefault();
      const r = el.getBoundingClientRect(), x = e.clientX - r.left, y = e.clientY - r.top;
      if (e.ctrlKey || Math.abs(e.deltaY) >= 50 && e.deltaX === 0) ctrl.zoomAt(x, y, Math.exp(-e.deltaY * (e.ctrlKey ? 0.01 : 0.0015)));
      else ctrl.panBy(-e.deltaX, -e.deltaY);
    };
    el.addEventListener("wheel", w, { passive: false });
    return () => el.removeEventListener("wheel", w);
  }, [ctrl]);

  /* ---- pointer handling ---- */
  const local = (e: React.PointerEvent) => { const r = e.currentTarget.getBoundingClientRect(); return { x: e.clientX - r.left, y: e.clientY - r.top }; };
  const panning = useRef(false);
  const onDown = (e: React.PointerEvent<HTMLCanvasElement>) => {
    e.currentTarget.setPointerCapture(e.pointerId);
    const p = local(e);
    pointers.current.set(e.pointerId, p);
    if (pointers.current.size >= 2) {
      ctrl.cancelGesture();
      const [a, b] = [...pointers.current.values()];
      multi.current = { cx: (a.x + b.x) / 2, cy: (a.y + b.y) / 2, d: Math.max(1, Math.hypot(a.x - b.x, a.y - b.y)) };
      return;
    }
    if (e.button === 1 || e.button === 2 || spacePan.current) {
      panning.current = true; lastPos.current = p; return;
    }
    if (e.button !== 0) return;
    ctrl.onDown(p.x, p.y);
  };
  const onMove = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const p = local(e);
    if (pointers.current.has(e.pointerId)) pointers.current.set(e.pointerId, p);
    if (multi.current && pointers.current.size >= 2) {
      const [a, b] = [...pointers.current.values()];
      const cx = (a.x + b.x) / 2, cy = (a.y + b.y) / 2, d = Math.max(1, Math.hypot(a.x - b.x, a.y - b.y));
      ctrl.pinch(cx, cy, d / multi.current.d, cx - multi.current.cx, cy - multi.current.cy);
      multi.current = { cx, cy, d };
      return;
    }
    if (panning.current) { ctrl.panBy(p.x - lastPos.current.x, p.y - lastPos.current.y); lastPos.current = p; return; }
    if (pointers.current.has(e.pointerId)) ctrl.onMove(p.x, p.y);
    else ctrl.hover(p.x, p.y);
  };
  const onUp = (e: React.PointerEvent<HTMLCanvasElement>) => {
    const p = local(e);
    const had = pointers.current.delete(e.pointerId);
    if (multi.current) { if (pointers.current.size === 0) multi.current = null; return; }
    if (panning.current) { panning.current = false; return; }
    if (had) ctrl.onUp(p.x, p.y);
  };
  const onDouble = (e: React.MouseEvent) => {
    if (ctrl.tool !== "select") return;
    const r = e.currentTarget.getBoundingClientRect();
    const wx = ctrl.toWorldX(e.clientX - r.left), wy = ctrl.toWorldY(e.clientY - r.top);
    const hit = ctrl.sorted().reverse().find((o) => BoardGeo.hit(o, wx, wy, 14 / ctrl.zoom));
    if (hit && hit.type === "text" && !hit.locked) { ctrl.textEdit = { existing: hit, wx: hit.x, wy: hit.y }; bump(); }
  };
  const onDropFile = (e: React.DragEvent) => {
    const f = [...e.dataTransfer.files].find((x) => x.type.startsWith("image/"));
    if (!f) return;
    e.preventDefault(); e.stopPropagation();
    void onFile(f);
  };

  /* ---- paint ---- */
  const paint = () => {
    const canvas = cv.current, el = wrap.current; if (!canvas || !el) return;
    const dpr = window.devicePixelRatio || 1, w = el.clientWidth, h = el.clientHeight;
    if (canvas.width !== Math.round(w * dpr) || canvas.height !== Math.round(h * dpr)) { canvas.width = Math.round(w * dpr); canvas.height = Math.round(h * dpr); }
    const ctx = canvas.getContext("2d")!;
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    ctx.fillStyle = BG; ctx.fillRect(0, 0, w, h);
    const z = ctrl.zoom;
    let step = 32, eff = step * z;
    while (eff < 22) { step *= 2; eff = step * z; }
    while (eff > 88) { step /= 2; eff = step * z; }
    const x0 = Math.floor(ctrl.camX / step) * step, y0 = Math.floor(ctrl.camY / step) * step;
    const r = Math.max(0.75, Math.min(1.25, z));
    ctx.fillStyle = "rgba(237,237,239,.055)";
    for (let wx = x0; (wx - ctrl.camX) * z < w + eff; wx += step)
      for (let wy = y0; (wy - ctrl.camY) * z < h + eff; wy += step) ctx.fillRect((wx - ctrl.camX) * z - r / 2, (wy - ctrl.camY) * z - r / 2, r, r);
    ctx.save();
    ctx.scale(z, z); ctx.translate(-ctrl.camX, -ctrl.camY);
    renderer.drawAll(ctx, ctrl.sorted(), ctrl.erasing);
    if (ctrl.draft) renderer.drawObj(ctx, ctrl.draft);
    ctx.restore();
    overlay(ctx, ctrl);
    const t = nowMs();
    ctx.font = "11px 'Segoe UI', sans-serif";
    for (const [sid, cur] of session.cursors.value) {
      if (t - cur.at > 3500 || sid === session.mySid) continue;
      const sx = ctrl.toScreenX(cur.x), sy = ctrl.toScreenY(cur.y);
      ctx.fillStyle = hsv(cur.hue, 0.6, 0.95); ctx.beginPath(); ctx.arc(sx, sy, 5, 0, Math.PI * 2); ctx.fill();
      ctx.fillStyle = "#fff"; ctx.fillText(cur.name, sx + 9, sy - 6);
    }
    for (const [sid, trail] of ctrl.lasers) {
      if (!trail.length) continue;
      const hue = sid === "me" ? 5 : session.cursors.value.get(sid)?.hue ?? 0;
      ctx.lineCap = "round"; ctx.lineJoin = "round";
      for (let i = 1; i < trail.length; i++) {
        const a = trail[i - 1], b = trail[i], age = (t - b.t) / 900;
        ctx.strokeStyle = hsv(hue, 0.85, 1, Math.max(0, Math.min(1, 1 - age)));
        ctx.lineWidth = 3 + 5 * (1 - age);
        ctx.beginPath(); ctx.moveTo(ctrl.toScreenX(a.x), ctrl.toScreenY(a.y)); ctx.lineTo(ctrl.toScreenX(b.x), ctrl.toScreenY(b.y)); ctx.stroke();
      }
      const head = trail[trail.length - 1];
      ctx.fillStyle = hsv(hue, 0.85, 1); ctx.beginPath(); ctx.arc(ctrl.toScreenX(head.x), ctrl.toScreenY(head.y), 6, 0, Math.PI * 2); ctx.fill();
    }
  };
  useEffect(paint);

  const sel = ctrl.selected();
  const allLocked = sel.length > 0 && sel.every((o) => o.locked);
  const grouped = sel.some((o) => o.group != null);
  const textMode = ctrl.tool === "text" || (sel.length > 0 && sel.every((o) => o.type === "text"));
  const curColor = sel.length ? sel[0].color : ctrl.color;
  const showStyle = DRAW_TOOLS.has(ctrl.tool) || ctrl.tool === "text" || sel.length > 0;
  const style = (patch: Record<string, any>) => { if (sel.length) ctrl.styleSelection(patch); bump(); };
  const cursor = ctrl.tool === "hand" ? "grab" : ctrl.tool === "select" ? "default" : ctrl.tool === "text" ? "text" : "crosshair";
  const empty = board.objects.size === 0 && !ctrl.draft;
  const pill: React.CSSProperties = { background: "rgb(17 11 28 / .92)", border: "1px solid var(--border)", borderRadius: 14, backdropFilter: "blur(8px)" };

  return (
    <div ref={wrap} style={{ position: "relative", flex: 1, minHeight: 0, background: BG, overflow: "hidden" }} onDragOver={(e) => { if ([...e.dataTransfer.types].includes("Files")) e.preventDefault(); }} onDrop={onDropFile}>
      <canvas ref={cv} style={{ position: "absolute", inset: 0, width: "100%", height: "100%", touchAction: "none", cursor: spacePan.current ? "grab" : cursor }}
        onPointerDown={onDown} onPointerMove={onMove} onPointerUp={onUp} onPointerCancel={onUp} onDoubleClick={onDouble} onContextMenu={(e) => e.preventDefault()} />
      <input ref={file} type="file" accept="image/*" hidden onChange={(e) => { const f = e.target.files?.[0]; e.target.value = ""; void onFile(f); }} />

      {/* top-right round actions */}
      <div style={{ position: "absolute", top: 10, right: 10, display: "flex", flexDirection: "column", gap: 8 }}>
        {[["download", true, () => setExportOpen(true), "Export"], ["undo", ctrl.canUndo, () => ctrl.undo(), "Undo (Ctrl+Z)"], ["redo", ctrl.canRedo, () => ctrl.redo(), "Redo (Ctrl+Y)"], ["fit", true, () => ctrl.fitAll(), "Fit everything"]].map(([ic, on, fn, tt]) => (
          <button key={ic as string} className="ibtn" title={tt as string} disabled={!on} onClick={fn as () => void} style={{ width: 40, height: 40, borderRadius: 20, ...pill, color: on ? "var(--text)" : "var(--faint)" }}><Icon name={ic as string} size={18} /></button>
        ))}
      </div>
      <button onClick={() => ctrl.resetZoom()} title="Reset zoom" style={{ position: "absolute", top: 10, left: 10, ...pill, borderRadius: 50, padding: "6px 10px", fontSize: 11, color: "var(--muted)", cursor: "pointer" }}>{Math.round(ctrl.zoom * 100)}%</button>

      {/* tool rail */}
      <div style={{ position: "absolute", left: 8, top: "50%", transform: "translateY(-50%)" }}>
        {!railOpen ? (
          <button onClick={() => setRailOpen(true)} style={{ width: 46, height: 46, borderRadius: 23, background: "var(--accent)", border: "1px solid var(--accent-bright)", color: "#fff", cursor: "pointer", display: "grid", placeItems: "center" }}>
            <Icon name={TOOLS.find((t) => t[0] === ctrl.tool)?.[1] ?? "pencil"} size={22} />
          </button>
        ) : (
          <div style={{ ...pill, borderRadius: 24, padding: 4, display: "flex", flexDirection: "column", alignItems: "center", maxHeight: "calc(100vh - 220px)", overflowY: "auto" }}>
            <button className="ibtn" style={{ width: 42, height: 42, borderRadius: 21, color: "var(--faint)" }} onClick={() => setRailOpen(false)} title="Hide tools"><Icon name="chevron-left" size={18} /></button>
            {TOOLS.map(([id, icon], i) => id === "|" ? <div key={i} style={{ height: 1, width: 24, background: "var(--border)", margin: "4px 0" }} /> : (
              <button key={id} title={id[0].toUpperCase() + id.slice(1)} className="ibtn" onClick={() => { if (id === "image") { pickImage(); return; } ctrl.tool = id; if (id !== "select") ctrl.selection = new Set(); bump(); }}
                style={{ width: 42, height: 42, borderRadius: 21, background: ctrl.tool === id ? "var(--accent)" : "transparent", color: ctrl.tool === id ? "#fff" : "var(--muted)" }}><Icon name={icon} size={20} /></button>
            ))}
          </div>
        )}
      </div>

      {hint && <div style={{ position: "absolute", top: 14, left: "50%", transform: "translateX(-50%)", ...pill, borderRadius: 50, padding: "8px 14px", fontSize: 12, color: "var(--text)", whiteSpace: "nowrap", pointerEvents: "none" }}>{hint}</div>}
      {empty && (
        <div style={{ position: "absolute", inset: 0, display: "grid", placeItems: "center", pointerEvents: "none" }}>
          <div style={{ textAlign: "center", maxWidth: 320 }}>
            <Icon name="pencil" size={26} color="var(--faint)" />
            <div style={{ color: "var(--muted)", fontSize: 15, fontWeight: 500, marginTop: 8 }}>A blank board</div>
            <div style={{ color: "var(--faint)", fontSize: 12 }}>Draw, type or drop in an image — everyone in the room sees it live.</div>
          </div>
        </div>
      )}

      {/* contextual bars */}
      <div style={{ position: "absolute", left: 0, right: 0, bottom: 8, display: "flex", flexDirection: "column", alignItems: "center", gap: 6, pointerEvents: "none" }}>
        {sel.length > 0 && (
          <div style={{ ...pill, display: "flex", alignItems: "center", pointerEvents: "auto", maxWidth: "calc(100% - 80px)", overflowX: "auto" }}>
            <span style={{ fontSize: 12, color: "var(--muted)", padding: "0 4px 0 12px", whiteSpace: "nowrap" }}>{sel.length} selected</span>
            <IconButton icon="copy" title="Duplicate (Ctrl+D)" size={40} iconSize={18} onClick={() => ctrl.duplicate()} />
            <IconButton icon="trash" title="Delete" size={40} iconSize={18} color="var(--danger)" onClick={() => ctrl.deleteSelection()} />
            <IconButton icon={grouped ? "ungroup" : "group"} title={grouped ? "Ungroup" : "Group"} size={40} iconSize={18} disabled={sel.length < 2 && !grouped} onClick={() => ctrl.group(!grouped)} />
            <IconButton icon={allLocked ? "unlock" : "lock"} title={allLocked ? "Unlock" : "Lock"} size={40} iconSize={18} onClick={() => ctrl.lock(!allLocked)} />
            <IconButton icon="arrow-up" title="Bring to front" size={40} iconSize={18} onClick={() => ctrl.order(true)} />
            <IconButton icon="arrow-down" title="Send to back" size={40} iconSize={18} onClick={() => ctrl.order(false)} />
            <IconButton icon="x" title="Deselect" size={40} iconSize={18} onClick={() => ctrl.setSelection([])} />
          </div>
        )}
        {showStyle && (
          <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 6, pointerEvents: "auto" }}>
            {paletteOpen && (
              <div style={{ ...pill, display: "flex", gap: 8, padding: "6px 8px" }}>
                {BOARD_COLORS.map((c) => (
                  <button key={c} onClick={() => { ctrl.color = c; style({ color: rgbToHex(c) }); }} style={{ width: 26, height: 26, borderRadius: 13, background: rgbToHex(c), cursor: "pointer", border: `${c === curColor ? 2 : 1}px solid ${c === curColor ? "#fff" : "var(--border-strong)"}` }} />
                ))}
              </div>
            )}
            <div style={{ ...pill, display: "flex", alignItems: "center", gap: 8, padding: "6px 8px", maxWidth: "calc(100% - 80px)", overflowX: "auto" }}>
              <button onClick={() => setPaletteOpen(!paletteOpen)} title="Colour" style={{ width: 32, height: 32, display: "grid", placeItems: "center", background: "none", border: 0, cursor: "pointer" }}>
                <span style={{ width: 26, height: 26, borderRadius: 13, background: rgbToHex(curColor), border: "2px solid #fff" }} />
              </button>
              <Sep />
              {textMode
                ? [16, 24, 36, 56].map((s) => <Pill key={s} text={`${s}`} on={(sel.length ? sel[0].size : ctrl.textSize) === s} onClick={() => { ctrl.textSize = s; style({ size: s }); }} />)
                : [2, 4, 7, 12].map((w) => <Pill key={w} text={`${w}`} on={(sel.length ? sel[0].width : ctrl.width) === w} onClick={() => { ctrl.width = w; style({ width: w }); }} />)}
              <Sep />
              {[1, 0.6, 0.3].map((o) => <Pill key={o} text={`${Math.round(o * 100)}%`} on={Math.abs((sel.length ? sel[0].opacity : ctrl.opacity) - o) < 0.01} onClick={() => { ctrl.opacity = o; style({ opacity: o }); }} />)}
              {!textMode && <Pill text="- - -" on={sel.length ? sel[0].dash : ctrl.dash} onClick={() => { const on = sel.length ? sel[0].dash : ctrl.dash; ctrl.dash = !on; style({ dash: !on ? 1 : 0 }); }} />}
            </div>
          </div>
        )}
      </div>

      {ctrl.textEdit && <TextDialog key={ctrl.textEdit.wx + "," + ctrl.textEdit.wy} edit={ctrl.textEdit} onCancel={() => { ctrl.textEdit = null; bump(); }} onDone={(t) => ctrl.commitText(ctrl.textEdit!, t)} />}
      {exportOpen && <ExportSheet ctrl={ctrl} onDismiss={() => setExportOpen(false)} />}
    </div>
  );
}

const Sep = () => <div style={{ width: 1, height: 22, background: "var(--border)", flex: "none" }} />;
const Pill = ({ text, on, onClick }: { text: string; on: boolean; onClick: () => void }) => (
  <button onClick={onClick} style={{ borderRadius: 8, padding: "5px 10px", fontSize: 12, cursor: "pointer", whiteSpace: "nowrap", background: on ? "var(--accent-soft)" : "transparent", border: `1px solid ${on ? "var(--accent)" : "var(--border)"}`, color: on ? "var(--accent-bright)" : "var(--muted)" }}>{text}</button>
);

function overlay(ctx: CanvasRenderingContext2D, ctrl: BoardController) {
  const selected = ctrl.selected();
  const sx = (x: number) => ctrl.toScreenX(x), sy = (y: number) => ctrl.toScreenY(y);
  ctx.lineWidth = 1.5; ctx.strokeStyle = ACCENT; ctx.setLineDash([]);
  if (selected.length) {
    if (selected.length === 1 && (selected[0].type === "line" || selected[0].type === "arrow")) { /* endpoints only */ }
    else if (selected.length === 1) {
      const cs = BoardGeo.corners(selected[0]);
      ctx.beginPath(); cs.forEach((p, i) => (i ? ctx.lineTo(sx(p.x), sy(p.y)) : ctx.moveTo(sx(p.x), sy(p.y)))); ctx.closePath(); ctx.stroke();
    } else {
      ctx.globalAlpha = 0.35;
      for (const o of selected) { const b = BoardGeo.rotatedBounds(o); ctx.strokeRect(sx(b.left), sy(b.top), sx(b.right) - sx(b.left), sy(b.bottom) - sy(b.top)); }
      ctx.globalAlpha = 1;
      const u = BoardGeo.union(selected);
      if (u) ctx.strokeRect(sx(u.left), sy(u.top), sx(u.right) - sx(u.left), sy(u.bottom) - sy(u.top));
    }
    if (selected.some((o) => !o.locked)) {
      const hs = 6;
      for (const p of ctrl.handlePoints()) { ctx.fillStyle = "#fff"; ctx.fillRect(sx(p.x) - hs, sy(p.y) - hs, hs * 2, hs * 2); ctx.strokeRect(sx(p.x) - hs, sy(p.y) - hs, hs * 2, hs * 2); }
      const rh = ctrl.rotateHandle();
      if (rh) { ctx.fillStyle = "#fff"; ctx.beginPath(); ctx.arc(sx(rh.x), sy(rh.y), 8, 0, Math.PI * 2); ctx.fill(); ctx.stroke(); }
    }
  }
  const m = ctrl.marquee;
  if (m) {
    ctx.fillStyle = "rgba(110,86,207,.13)"; ctx.fillRect(sx(m.left), sy(m.top), sx(m.right) - sx(m.left), sy(m.bottom) - sy(m.top));
    ctx.strokeRect(sx(m.left), sy(m.top), sx(m.right) - sx(m.left), sy(m.bottom) - sy(m.top));
  }
}

function TextDialog({ edit, onCancel, onDone }: { edit: TextEdit; onCancel: () => void; onDone: (t: string) => void }) {
  const [txt, setTxt] = useState(edit.existing?.text ?? "");
  return (
    <Dialog title={edit.existing ? "Edit text" : "Add text"} onDismiss={onCancel}
      actions={<><Button kind="ghost" text="Cancel" onClick={onCancel} /><Button kind="ghost" text="Done" onClick={() => onDone(txt)} /></>}>
      <Field value={txt} onChange={setTxt} multiline rows={3} placeholder="Type here" maxLength={2000} autoFocus />
    </Dialog>
  );
}

function ExportSheet({ ctrl, onDismiss }: { ctrl: BoardController; onDismiss: () => void }) {
  const [scope, setScope] = useState(ctrl.selection.size ? "selection" : "all");
  const run = async (fmt: string) => {
    const objs = scope === "selection" ? ctrl.selected() : ctrl.sorted();
    const region = BoardExport.region(objs);
    if (!region || !objs.length) { core.toast("Nothing to export yet"); return; }
    try {
      const bytes = fmt === "png" ? await BoardExport.raster(objs, region, ctrl.images, false)
        : fmt === "jpg" ? await BoardExport.raster(objs, region, ctrl.images, true)
        : fmt === "svg" ? BoardExport.svg(objs, region)
        : await BoardExport.pdf(objs, region, ctrl.images);
      const ok = await native.saveFile(`homit-board.${fmt}`, bytes);
      core.toast(ok ? "Saved" : "Not saved");
      if (ok) onDismiss();
    } catch (e: any) { core.toast("Export failed: " + (e?.message ?? e)); }
  };
  return (
    <Sheet title="Export blackboard" onDismiss={onDismiss} center>
      <Tiny>What to export</Tiny>
      <div style={{ height: 8 }} />
      <Segmented options={[["all", "Everything"], ["selection", "Selection"]]} selected={scope} onSelect={(k) => { if (k === "all" || ctrl.selection.size) setScope(k); }} />
      <div style={{ height: 16 }} />
      <div style={{ display: "flex", gap: 8 }}>
        {["png", "jpg", "svg", "pdf"].map((f) => <div key={f} style={{ flex: 1 }}><Button block small kind="secondary" text={f.toUpperCase()} onClick={() => void run(f)} /></div>)}
      </div>
    </Sheet>
  );
}
