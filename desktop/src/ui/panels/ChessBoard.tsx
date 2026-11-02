import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { BISHOP, KING, KNIGHT, Move, PAWN, Position, QUEEN, ROOK, WHITE, colorOf, sqName, typeOf } from "../../chess/rules";

/* Pieces are geometry in a 100 x 100 box, so they stay crisp at any size and never depend on a font. */
const poly = (...p: number[]) => { let d = `M${p[0]} ${p[1]}`; for (let i = 2; i < p.length; i += 2) d += `L${p[i]} ${p[i + 1]}`; return d + "Z"; };
const circ = (cx: number, cy: number, r: number) => `M${cx - r} ${cy}a${r} ${r} 0 1 0 ${2 * r} 0a${r} ${r} 0 1 0 ${-2 * r} 0Z`;
const ART: Record<number, { fills: string[]; strokes?: string[] }> = {
  [PAWN]: { fills: [circ(50, 29, 12), poly(43, 40, 57, 40, 66, 74, 34, 74), poly(28, 74, 72, 74, 72, 82, 28, 82)] },
  [ROOK]: { fills: [poly(30, 22, 41, 22, 41, 29, 45, 29, 45, 22, 55, 22, 55, 29, 59, 29, 59, 22, 70, 22, 70, 40, 30, 40), poly(36, 40, 64, 40, 67, 74, 33, 74), poly(27, 74, 73, 74, 73, 83, 27, 83)] },
  [KNIGHT]: { fills: ["M28 83L30 68C31 56 36 50 44 44C38 44 31 47 26 52L24 45C28 36 37 28 45 25L46 14L55 22C68 26 75 42 73 60C72 68 71 75 72 83Z"], strokes: ["M48 34L52 34"] },
  [BISHOP]: { fills: [circ(50, 15, 5), "M50 20C62 30 66 42 60 54L67 74L33 74L40 54C34 42 38 30 50 20Z", poly(28, 74, 72, 74, 72, 83, 28, 83)], strokes: ["M50 30L57 42"] },
  [QUEEN]: { fills: [poly(26, 76, 19, 32, 35, 52, 41, 26, 50, 50, 59, 26, 65, 52, 81, 32, 74, 76), circ(19, 28, 4.5), circ(41, 22, 4.5), circ(59, 22, 4.5), circ(81, 28, 4.5), poly(24, 76, 76, 76, 76, 84, 24, 84)] },
  [KING]: { fills: [poly(46, 10, 54, 10, 54, 17, 61, 17, 61, 24, 54, 24, 54, 33, 46, 33, 46, 24, 39, 24, 39, 17, 46, 17), "M50 33C70 34 76 48 70 58L68 76L32 76L30 58C24 48 30 34 50 33Z", poly(26, 76, 74, 76, 74, 84, 26, 84)] }
};
export const PIECE_LIGHT = "#EFEDE6", PIECE_DARK = "#16181D", SQ_LIGHT = "#DDD8EC", SQ_DARK = "#6E5F96";

export function PieceShape({ type, color, alpha = 1 }: { type: number; color: number; alpha?: number }) {
  const art = ART[type];
  if (!art) return null;
  const fill = color === WHITE ? PIECE_LIGHT : PIECE_DARK;
  const edge = color === WHITE ? "#1B1D22" : "#CFCBC2";
  return (
    <g opacity={alpha}>
      {art.fills.map((d, i) => <path key={i} d={d} fill={fill} stroke={edge} strokeWidth={2.2} strokeLinejoin="round" />)}
      {art.strokes?.map((d, i) => <path key={"s" + i} d={d} fill="none" stroke={edge} strokeWidth={2.6} strokeLinecap="round" />)}
    </g>
  );
}

/** A single piece as a standalone little image (reserves, captured rows, promotion). */
export function PieceIcon({ type, color, size, alpha = 1 }: { type: number; color: number; size: number; alpha?: number }) {
  return <svg width={size} height={size} viewBox="0 0 100 100" style={{ display: "block", flex: "none" }}><PieceShape type={type} color={color} alpha={alpha} /></svg>;
}

export const pieceName = (t: number) => (t === PAWN ? "pawn" : t === KNIGHT ? "knight" : t === BISHOP ? "bishop" : t === ROOK ? "rook" : t === QUEEN ? "queen" : "king");

/**
 * myColor: colour this device may move for, or -1 when only watching.
 * legal: this position's legal moves (only needed when interactive).
 * dropType: a reserve piece picked by the player, 0 when none.
 */
export function ChessBoardView({ pos, flipped, lastFrom, lastTo, myColor, interactive, legal, showLegal, dropType, onMove, label = "Chessboard" }: {
  pos: Position; flipped: boolean; lastFrom: number; lastTo: number; myColor: number; interactive: boolean; legal: Move[]; showLegal: boolean; dropType: number; onMove: (m: Move) => void; label?: string;
}) {
  const [selected, setSelected] = useState(-1);
  const [drag, setDrag] = useState<{ sq: number; x: number; y: number } | null>(null);
  const [promo, setPromo] = useState<Move[] | null>(null);
  const [t, setT] = useState(1);
  const svg = useRef<SVGSVGElement>(null);
  const skipAnim = useRef(false);
  const first = useRef(true);
  const fen = useMemo(() => pos.fen(), [pos]);
  useEffect(() => { setSelected(-1); setPromo(null); }, [fen]);
  useEffect(() => {
    if (first.current) { first.current = false; setT(1); return; }
    if (lastFrom < 0 || skipAnim.current) { skipAnim.current = false; setT(1); return; }
    let raf = 0; const t0 = performance.now();
    const step = (n: number) => { const k = Math.min(1, (n - t0) / 130); setT(1 - (1 - k) * (1 - k)); if (k < 1) raf = requestAnimationFrame(step); };
    setT(0); raf = requestAnimationFrame(step);
    return () => cancelAnimationFrame(raf);
  }, [lastFrom, lastTo, fen]);

  const targets: Move[] = !interactive ? [] : dropType > 0 ? legal.filter((m) => m.drop === dropType) : selected >= 0 ? legal.filter((m) => m.from === selected) : [];
  const checkSq = pos.inCheck(pos.turn) ? pos.kingSq(pos.turn) : -1;
  const px = (sq: number): [number, number] => { const f = sq & 7, r = sq >> 3; return !flipped ? [f * 100, (7 - r) * 100] : [(7 - f) * 100, r * 100]; };
  const sqAt = (cx: number, cy: number): number => {
    const b = svg.current!.getBoundingClientRect();
    const f = Math.min(7, Math.max(0, Math.floor(((cx - b.left) / b.width) * 8)));
    const r = Math.min(7, Math.max(0, Math.floor(((cy - b.top) / b.height) * 8)));
    return !flipped ? (7 - r) * 8 + f : r * 8 + (7 - f);
  };
  const tryMove = (from: number, to: number): boolean => {
    const c = legal.filter((m) => m.from === from && m.to === to && m.drop === 0);
    if (c.length === 0) return false;
    if (c.length > 1) setPromo(c); else onMove(c[0]);
    return true;
  };

  const down = (e: React.PointerEvent) => {
    if (!interactive || promo) return;
    const s0 = sqAt(e.clientX, e.clientY);
    const prev = selected;
    const own = pos.sq[s0] !== 0 && colorOf(pos.sq[s0]) === myColor && dropType === 0;
    if (own) setSelected(s0);
    const sx = e.clientX, sy = e.clientY;
    let moved = false;
    const el = svg.current!;
    el.setPointerCapture(e.pointerId);
    const mv = (ev: PointerEvent) => {
      if (own && (moved || Math.hypot(ev.clientX - sx, ev.clientY - sy) > 5)) { moved = true; setDrag({ sq: s0, x: ev.clientX, y: ev.clientY }); }
    };
    const up = (ev: PointerEvent) => {
      el.removeEventListener("pointermove", mv); el.removeEventListener("pointerup", up); el.removeEventListener("pointercancel", up);
      try { el.releasePointerCapture(e.pointerId); } catch { /* ignore */ }
      setDrag(null);
      const end = sqAt(ev.clientX, ev.clientY);
      if (moved) {
        if (end !== s0) { skipAnim.current = true; if (!tryMove(s0, end)) skipAnim.current = false; }
      } else if (dropType > 0) {
        const m = legal.find((x) => x.drop === dropType && x.to === end);
        if (m) onMove(m);
      } else if (prev >= 0 && prev !== s0 && tryMove(prev, s0)) { /* moved by tap */ }
      else setSelected(own && prev !== s0 ? s0 : -1);
    };
    el.addEventListener("pointermove", mv); el.addEventListener("pointerup", up); el.addEventListener("pointercancel", up);
  };

  const cells: ReactNode[] = [];
  for (let sq = 0; sq < 64; sq++) { const [x, y] = px(sq); cells.push(<rect key={sq} x={x} y={y} width={100} height={100} fill={((sq & 7) + (sq >> 3)) % 2 === 1 ? SQ_LIGHT : SQ_DARK} />); }
  const hl = (sq: number, a: number) => { const [x, y] = px(sq); return <rect key={"h" + sq + a} x={x} y={y} width={100} height={100} fill="#52FF1F" opacity={a} />; };
  const coords: ReactNode[] = [];
  for (let i = 0; i < 8; i++) {
    const fileSq = !flipped ? i : 7 - i, rankSq = !flipped ? 7 - i : i;
    const lightFile = (fileSq + (!flipped ? 0 : 7)) % 2 === 1, lightRank = ((!flipped ? 0 : 7) + rankSq) % 2 === 1;
    coords.push(<text key={"f" + i} x={i * 100 + 82} y={800 - 8} fontSize={20} fontWeight={700} textAnchor="middle" fill={lightFile ? SQ_DARK : SQ_LIGHT}>{String.fromCharCode(97 + fileSq)}</text>);
    coords.push(<text key={"r" + i} x={12} y={i * 100 + 28} fontSize={20} fontWeight={700} textAnchor="middle" fill={lightRank ? SQ_DARK : SQ_LIGHT}>{rankSq + 1}</text>);
  }
  const pieces: ReactNode[] = [];
  for (let sq = 0; sq < 64; sq++) {
    const p = pos.sq[sq];
    if (p === 0 || (drag && drag.sq === sq)) continue;
    let [x, y] = px(sq);
    if (sq === lastTo && lastFrom >= 0 && t < 1) { const [fx, fy] = px(lastFrom); x = fx + (x - fx) * t; y = fy + (y - fy) * t; }
    pieces.push(<g key={sq} transform={`translate(${x} ${y}) scale(1)`}><PieceShape type={typeOf(p)} color={colorOf(p)} /></g>);
  }
  const hints: ReactNode[] = [];
  if (showLegal || dropType > 0) for (const m of targets) {
    const [x, y] = px(m.to);
    const capture = pos.sq[m.to] !== 0 || (m.drop === 0 && typeOf(pos.sq[m.from]) === PAWN && (m.from & 7) !== (m.to & 7));
    hints.push(capture ? <circle key={"t" + m.to + m.promo} cx={x + 50} cy={y + 50} r={44} fill="none" stroke="#111114" strokeOpacity={0.34} strokeWidth={9} /> : <circle key={"t" + m.to + m.promo} cx={x + 50} cy={y + 50} r={15} fill="#111114" fillOpacity={0.3} />);
  }
  let dragEl: ReactNode = null;
  if (drag && svg.current) {
    const b = svg.current.getBoundingClientRect(); const k = 800 / b.width;
    const p = pos.sq[drag.sq]; const big = 125;
    dragEl = <g transform={`translate(${(drag.x - b.left) * k - big / 2} ${(drag.y - b.top) * k - big / 2 - 50}) scale(${big / 100})`}><PieceShape type={typeOf(p)} color={colorOf(p)} /></g>;
  }
  return (
    <div style={{ position: "relative", width: "100%", aspectRatio: "1", touchAction: "none" }}>
      <svg ref={svg} viewBox="0 0 800 800" role="img" aria-label={label} style={{ width: "100%", height: "100%", display: "block", cursor: interactive ? "pointer" : "default", userSelect: "none" }} onPointerDown={down}>
        <defs><radialGradient id="chk"><stop offset="0" stopColor="#ff5a5a" stopOpacity={0.85} /><stop offset="1" stopColor="#ff5a5a" stopOpacity={0} /></radialGradient></defs>
        {cells}
        {lastFrom >= 0 && hl(lastFrom, 0.26)}{lastTo >= 0 && hl(lastTo, 0.36)}{selected >= 0 && hl(selected, 0.5)}
        {checkSq >= 0 && (() => { const [x, y] = px(checkSq); return <circle cx={x + 50} cy={y + 50} r={75} fill="url(#chk)" />; })()}
        {coords}{pieces}{hints}{dragEl}
      </svg>
      {promo && (
        <div style={{ position: "absolute", inset: 0, background: "rgb(0 0 0 / .45)", display: "grid", placeItems: "center" }} onClick={() => setPromo(null)}>
          <div className="hstack g6" style={{ background: "var(--surface)", borderRadius: 2, padding: 8, boxShadow: "inset 0 0 0 1px var(--border-strong)" }}>
            {promo.map((m) => {
              const col = colorOf(pos.sq[promo[0].from]);
              return <button key={m.promo} title={`Promote to ${pieceName(m.promo)} on ${sqName(promo[0].to)}`} style={{ width: 52, height: 52, borderRadius: 2, display: "grid", placeItems: "center", background: col === WHITE ? SQ_DARK : SQ_LIGHT }} onClick={(e) => { e.stopPropagation(); setPromo(null); onMove(m); }}><PieceIcon type={m.promo} color={col} size={42} /></button>;
            })}
          </div>
        </div>
      )}
    </div>
  );
}
