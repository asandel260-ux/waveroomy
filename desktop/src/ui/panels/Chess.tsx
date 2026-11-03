import { useEffect, useMemo, useRef, useState } from "react";
import { core } from "../../app/instance";
import { ChessGame, ChessModes } from "../../chess/game";
import { BLACK, Move, PAWN, POCKET_NONE, Position, QUEEN, WHITE, colorOf, typeOf } from "../../chess/rules";
import { nowMs } from "../../core/util";
import { useFlow, useTick } from "../hooks";
import { Avatar, Button, Chip, ConfirmDialog, EmptyState, HeroCard, Icon, IconButton, Muted, NeonWave, Row2, Sheet, SwitchRow, Tiny } from "../components/kit";
import { ChessBoardView, PIECE_DARK, PIECE_LIGHT, PieceIcon } from "./ChessBoard";

const START_COUNT = [0, 8, 2, 2, 2, 1];
const VALUE = [0, 1, 3, 3, 5, 9];

function fmtMs(ms: number): string {
  if (ms < 10_000) return `0:${String(Math.floor(ms / 1000)).padStart(2, "0")}.${Math.floor((ms % 1000) / 100)}`;
  const t = Math.floor(ms / 1000), h = Math.floor(t / 3600), m = Math.floor((t % 3600) / 60), s = t % 60;
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}` : `${m}:${String(s).padStart(2, "0")}`;
}
const copyText = (text: string) => { core.copy(text); core.toast("Copied"); };

/** Pieces of [color] that are no longer on the board (so the other side captured them). */
function missing(pos: Position, color: number): number[] {
  const have = [0, 0, 0, 0, 0, 0];
  for (let s = 0; s < 64; s++) { const p = pos.sq[s]; if (p !== 0 && colorOf(p) === color && typeOf(p) >= 1 && typeOf(p) <= 5) have[typeOf(p)]++; }
  const out: number[] = [];
  for (let t = QUEEN; t >= PAWN; t--) for (let i = 0; i < Math.max(0, START_COUNT[t] - have[t]); i++) out.push(t);
  return out;
}
function historic(g: ChessGame, plies: number): { pos: Position; lf: number; lt: number } {
  let p = Position.initial(g.pocketMode);
  let lf = -1, lt = -1;
  for (let i = 0; i < plies; i++) {
    const m = Move.parse(g.boards[0].moves[i] ?? "");
    if (!m) break;
    p = p.play(m).pos; lf = m.from; lt = m.to;
  }
  return { pos: p, lf, lt };
}
const reasonLine = (r: string) => (r.startsWith("No ") || r.startsWith("Timeout vs") ? r : `by ${r}`);

/* ---------------- create-game sheet ---------------- */

export function ChessCreateSheet({ onDismiss, onCreated }: { onDismiss: () => void; onCreated: () => void }) {
  const [mode, setMode] = useState("blitz");
  const [preset, setPreset] = useState(3);
  const [about, setAbout] = useState(false);
  const [busy, setBusy] = useState(false);
  const m = ChessModes.get(mode);
  const p = m.presets[Math.min(preset, m.presets.length - 1)];
  return (
    <Sheet title="New chess game" center onDismiss={onDismiss}>
      <div style={{ padding: "0 20px 16px", maxHeight: "74vh", overflowY: "auto" }}>
        <Tiny>Time controls</Tiny><div style={{ height: 6 }} />
        <div className="hstack" style={{ flexWrap: "wrap", gap: 8 }}>{ChessModes.all.filter((x) => !x.variant).map((x) => <Chip key={x.id} text={x.label} selected={mode === x.id} onClick={() => { setMode(x.id); setPreset(Math.min(preset, x.presets.length - 1)); }} />)}</div>
        <div style={{ height: 14 }} /><Tiny>Variants</Tiny><div style={{ height: 6 }} />
        <div className="hstack" style={{ flexWrap: "wrap", gap: 8 }}>{ChessModes.all.filter((x) => x.variant).map((x) => <Chip key={x.id} text={x.label + (x.id === "bughouse" ? " · 4 players" : "")} selected={mode === x.id} onClick={() => { setMode(x.id); setPreset(Math.min(preset, x.presets.length - 1)); }} />)}</div>
        <button className="hstack g8" style={{ width: "100%", padding: "6px 0", marginTop: 10 }} onClick={() => setAbout(!about)}>
          <Icon name="info" size={16} color="var(--faint)" /><span className="grow" style={{ textAlign: "left", color: "var(--muted)", fontSize: 13 }}>About {m.label}</span><Icon name={about ? "chevron-up" : "chevron-down"} size={16} color="var(--faint)" />
        </button>
        {about && <div style={{ marginBottom: 4 }}><Muted>{m.about}</Muted></div>}
        <div style={{ height: 10 }} /><Tiny>Base time + increment, in minutes and seconds</Tiny><div style={{ height: 6 }} />
        <div className="hstack" style={{ flexWrap: "wrap", gap: 8 }}>{m.presets.map(([b, inc], i) => <Chip key={i} text={`${b}+${inc}`} selected={preset === i} onClick={() => setPreset(i)} />)}</div>
        <div style={{ height: 8 }} /><Muted size={12}>Each player receives the selected base time and gains the increment after every move.</Muted>
        <div style={{ height: 18 }} />
        <div style={{ fontSize: 15, fontWeight: 600 }}>{m.label} · {p[0]}+{p[1]}</div><Muted size={12}>{mode === "bughouse" ? "4 players · 2 teams" : "2 players"}</Muted>
        <div style={{ height: 12 }} />
        <Button block disabled={busy} text={busy ? "Creating…" : "Create game"} onClick={() => { setBusy(true); core.session.chessCreate(mode, p[0] * 60, p[1], (ok) => { setBusy(false); if (ok) { onDismiss(); onCreated(); } }); }} />
      </div>
    </Sheet>
  );
}

/* ---------------- challenge card (shown in room chat) ---------------- */

export function ChessChallengeCard({ gameId, text, onOpen }: { gameId: string; text: string; onOpen: () => void }) {
  const session = core.session;
  useTick(session.tick);
  const g = session.room?.chess.games.get(gameId);
  const me = session.myUid;
  return (
    <div style={{ borderRadius: "var(--r-s)", background: "var(--surface2)", boxShadow: "inset 0 0 0 1px var(--border)", padding: 12 }}>
      <div style={{ fontSize: 13 }}>{text}</div>
      <div style={{ height: 10 }} />
      {!g ? <div className="hstack g8"><Icon name="chess" size={18} color="var(--faint)" /><Muted>Finished</Muted></div> : (() => {
        const seated = g.seatOf(me) >= 0;
        const state = g.status === "waiting" ? (g.filled() ? "Full" : "Waiting") : g.status === "live" ? "Started" : g.status === "over" ? "Finished" : g.status === "cancelled" ? "Cancelled" : "Expired";
        const tone = state === "Waiting" ? "var(--ok)" : state === "Full" || state === "Started" ? "var(--accent-bright)" : "var(--faint)";
        return (
          <>
            <div className="hstack g10">
              <div style={{ width: 36, height: 36, borderRadius: "var(--r-s)", background: "var(--surface3)", display: "grid", placeItems: "center" }}><Icon name="chess" size={20} color="var(--accent-bright)" /></div>
              <div className="grow" style={{ minWidth: 0 }}>
                <div className="ellipsis" style={{ fontSize: 14, fontWeight: 600 }}>{g.seats[0]?.name ?? "Open seat"}</div>
                <div style={{ color: "var(--muted)", fontSize: 12 }}>{g.label} · {g.timeLabel}</div>
                <div style={{ color: "var(--faint)", fontSize: 12 }}>{g.bughouse ? `${g.count()} / 4 Players` : "2 Players"}</div>
              </div>
              <div className="stack" style={{ alignItems: "flex-end", gap: 6 }}>
                <span style={{ color: tone, fontSize: 10, fontWeight: 700, letterSpacing: 1 }}>{state.toUpperCase()}</span>
                {g.status === "waiting" && !seated && !g.filled() ? <Button small text="JOIN GAME" onClick={() => { session.chessAct("chess:join", g.id, {}, (ok) => { if (ok) onOpen(); }); if (g.bughouse) onOpen(); }} />
                  : g.status === "waiting" || g.status === "live" ? <Button small kind="secondary" text={seated ? "Open" : "Watch"} onClick={onOpen} />
                  : g.status === "over" ? <Button small kind="secondary" text="View" onClick={onOpen} /> : null}
              </div>
            </div>
            {g.status === "waiting" && g.creatorUid === me && <div style={{ marginTop: 8 }}><Button small kind="ghost" text="Cancel challenge" onClick={() => session.chessAct("chess:cancel", g.id)} /></div>}
          </>
        );
      })()}
    </div>
  );
}

/* ---------------- the panel (one of the room's tabs) ---------------- */

export function ChessPanel({ focus, setFocus, onExit, onCreate }: { land?: boolean; focus: boolean; setFocus: (b: boolean) => void; onExit: () => void; onCreate: () => void }) {
  const session = core.session;
  useTick(session.tick);
  const s = session.room;
  if (!s) return null;
  const g = s.chess.current;
  return (
    <div style={{ flex: 1, minHeight: 0, background: "var(--bg)", display: "flex", flexDirection: "column", position: "relative" }}>
      {!g ? <div style={{ flex: 1, display: "grid", placeItems: "center" }}><EmptyState icon="chess" title="No game yet" text="Start a game and invite the room to play or watch." action={<Button icon="plus" text="New game" onClick={onCreate} />} /></div>
        : g.status === "waiting" ? <ChessLobby g={g} />
        : g.live || g.over ? <ChessWorkspace key={g.id} g={g} expanded={focus} onExpand={setFocus} onClose={onExit} onNew={onCreate} />
        : <div style={{ flex: 1, display: "grid", placeItems: "center" }}><EmptyState icon="chess" title={g.status === "expired" ? "That challenge expired" : "That challenge was cancelled"} text="Nobody joined in time." action={<Button icon="plus" text="New game" onClick={onCreate} />} /></div>}
    </div>
  );
}

function ChessLobby({ g }: { g: ChessGame }) {
  const session = core.session;
  const me = session.myUid;
  const mySeat = g.seatOf(me);
  const owner = g.creatorUid === me || session.isHost;
  return (
    <div className="scroll"><div style={{ maxWidth: 440, margin: "0 auto", padding: 20, display: "flex", flexDirection: "column", alignItems: "center" }}>
      <div style={{ width: "100%" }}>
        <HeroCard>
          <div style={{ paddingTop: 20, display: "flex", flexDirection: "column", alignItems: "center" }}>
            <Icon name="chess" size={30} />
            <div className="display" style={{ fontSize: 22, marginTop: 8 }}>{g.label} · {g.timeLabel}</div>
            <Muted size={11}>{g.filled() ? "FULL" : "WAITING FOR PLAYERS"}</Muted>
            <div style={{ width: "100%" }}><NeonWave height={36} amplitude={9} stroke={2.5} /></div>
          </div>
        </HeroCard>
      </div>
      <div style={{ height: 18 }} />
      <div className="stack g10" style={{ width: "100%" }}>
        {g.bughouse ? <>
          <TeamCard title="Team 1" seats={[[0, "Board A · White"], [3, "Board B · Black"]]} g={g} mySeat={mySeat} />
          <TeamCard title="Team 2" seats={[[1, "Board A · Black"], [2, "Board B · White"]]} g={g} mySeat={mySeat} />
          <Muted size={12}>Teammates play opposite colours on opposite boards. Pieces one of you captures go to the other's reserve.</Muted>
        </> : <>
          <SeatRow label="Player 1" seat={0} g={g} mySeat={mySeat} canTake={false} />
          <SeatRow label="Player 2" seat={1} g={g} mySeat={mySeat} canTake={mySeat < 0 && !g.filled()} />
          <Muted size={12}>Colours are chosen at random when the game starts.</Muted>
        </>}
      </div>
      <div style={{ height: 18 }} />
      <div className="stack g8" style={{ width: "100%" }}>
        {g.bughouse && owner && <Button block text="START GAME" disabled={!g.filled()} onClick={() => session.chessAct("chess:start", g.id)} />}
        {mySeat >= 0 && g.creatorUid !== me && <Button block kind="secondary" text="Leave game" onClick={() => session.chessAct("chess:leave", g.id)} />}
        {owner && <Button block kind="danger" text="Cancel game" onClick={() => session.chessAct("chess:cancel", g.id)} />}
      </div>
      <div style={{ height: 10 }} /><Tiny>Unclaimed challenges expire after 10 minutes</Tiny>
    </div></div>
  );
}

function TeamCard({ title, seats, g, mySeat }: { title: string; seats: [number, string][]; g: ChessGame; mySeat: number }) {
  return (
    <div style={{ borderRadius: "var(--r-s)", background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)" }}>
      <div className="hstack g8" style={{ padding: "8px 12px" }}><span style={{ width: 8, height: 8, background: title === "Team 1" ? "var(--accent-bright)" : "var(--warn)" }} /><Tiny>{title.toUpperCase()}</Tiny></div>
      {seats.map(([seat, label]) => <SeatRow key={seat} label={label} seat={seat} g={g} mySeat={mySeat} canTake={mySeat < 0} flat />)}
    </div>
  );
}

function SeatRow({ label, seat, g, mySeat, canTake, flat }: { label: string; seat: number; g: ChessGame; mySeat: number; canTake: boolean; flat?: boolean }) {
  const session = core.session;
  const s = g.seats[seat];
  const who = [...(session.room?.participants.values() ?? [])].find((p) => p.uid === s?.uid);
  return (
    <div className="hstack g10" style={{ padding: "8px 12px", ...(flat ? {} : { borderRadius: "var(--r-s)", background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)" }) }}>
      {who ? <Avatar avatar={who.avatar} size={32} name={who.displayName} uid={who.uid} mine={who.uid === session.myUid} /> : <span style={{ width: 32, height: 32, borderRadius: "50%", boxShadow: "inset 0 0 0 1px var(--border-strong)" }} />}
      <div className="grow" style={{ minWidth: 0 }}>
        <div className="ellipsis" style={{ fontSize: 14, fontWeight: 500, color: s ? "var(--text)" : "var(--faint)" }}>{s ? s.name + (seat === mySeat ? " (you)" : "") : "Open seat"}</div>
        <Tiny>{label}</Tiny>
      </div>
      {!s && canTake && mySeat < 0 && <Button small text="Take seat" onClick={() => session.chessAct("chess:join", g.id, { seat })} />}
    </div>
  );
}

/* ---------------- workspace: live game, spectating and review ---------------- */

interface Pending { board: number; move: Move; pos: Position }

function ChessWorkspace({ g, expanded, onExpand, onClose, onNew }: { g: ChessGame; expanded: boolean; onExpand: (b: boolean) => void; onClose: () => void; onNew: () => void }) {
  const session = core.session;
  const cfg = useFlow(core.settings.state);
  const [flip, setFlip] = useState(false);
  const [view, setView] = useState(-1);
  const [dropSel, setDropSel] = useState(0);
  const [pending, setPendingS] = useState<Pending | null>(null);
  const [showResult, setShowResult] = useState(true);
  const [moreOpen, setMoreOpen] = useState(false);
  const [resignAsk, setResignAsk] = useState(false);
  const [now, setNow] = useState(nowMs());
  const mySeat = g.seatOf(session.myUid);
  const ui = { flip, view, dropSel, pending, showResult, mySeat, setFlip, setView, setDropSel, setPending: setPendingS, setShowResult };
  const box = useRef<HTMLDivElement>(null);
  const [dim, setDim] = useState({ w: 900, h: 600 });

  useEffect(() => { const t = setInterval(() => setNow(nowMs()), g.live ? 100 : 1000); return () => clearInterval(t); }, [g.id, g.status]);
  useEffect(() => {
    const el = box.current; if (!el) return;
    const ro = new ResizeObserver(() => setDim({ w: el.clientWidth, h: el.clientHeight }));
    ro.observe(el); setDim({ w: el.clientWidth, h: el.clientHeight });
    return () => ro.disconnect();
  }, []);

  const counts = useRef([0, 1].map((i) => g.boards[i]?.moves.length ?? 0));
  const announcedEnd = useRef(g.over);
  const total = g.boards.reduce((n, b) => n + b.moves.length, 0);
  useEffect(() => {
    g.boards.forEach((b, i) => {
      if (b.moves.length > counts.current[i]) {
        counts.current[i] = b.moves.length;
        const san = b.sans[b.sans.length - 1] ?? "";
        core.sfx.play(san.endsWith("+") || san.endsWith("#") ? "c_check" : san.includes("x") ? "c_cap" : "c_move");
        if (pending?.board === i) setPendingS(null);
      }
    });
    if (g.over && !announcedEnd.current) { announcedEnd.current = true; core.sfx.play("c_end"); }
  }, [total, g.status]);
  useEffect(() => { if (!pending) return; const t = setTimeout(() => setPendingS(null), 4000); return () => clearTimeout(t); }, [pending]);

  const variant = g.pocketMode !== POCKET_NONE;
  const chrome = 2 * 46 + (variant ? 2 * 40 : 0);
  const info = <InfoBlock g={g} ui={ui} onClose={onClose} onNew={onNew} expanded={expanded} onExpand={onExpand} onMore={() => setMoreOpen(true)} onResign={() => setResignAsk(true)} />;
  const board = (b: number, size: number) => <BoardBlock g={g} b={b} size={size} ui={ui} now={now} showLegal={cfg.chessLegal} />;

  let body;
  if (!g.bughouse) {
    const size = Math.max(200, Math.min(dim.h - chrome - 8, dim.w - 340));
    body = (
      <div style={{ display: "flex", height: "100%", alignItems: "center", justifyContent: "center", gap: 16, padding: "0 12px" }}>
        {board(0, size)}
        <div style={{ width: 300, height: Math.min(dim.h - 16, size + chrome), display: "flex", flexDirection: "column", gap: 8 }}>
          <MoveList g={g} ui={ui} />
          {info}
        </div>
      </div>
    );
  } else {
    const size = Math.max(150, Math.min(dim.h - chrome - 8, (dim.w - 260) / 2));
    body = (
      <div style={{ display: "flex", height: "100%", alignItems: "center", justifyContent: "center", gap: 10, padding: "0 12px" }}>
        {board(0, size)}{board(1, size)}
        <div style={{ width: 220, maxHeight: "100%", overflowY: "auto", display: "flex", flexDirection: "column", gap: 8 }}>{info}</div>
      </div>
    );
  }
  return (
    <div ref={box} style={{ flex: 1, minHeight: 0, position: "relative" }}>
      <div style={{ position: "absolute", inset: 0 }}>{body}</div>
      {resignAsk && <ConfirmDialog danger title="Resign this game?" text={g.bughouse ? "Your team loses the game." : "You lose the game."} confirm="Resign" onDismiss={() => setResignAsk(false)} onConfirm={() => session.chessAct("chess:resign", g.id)} />}
      {moreOpen && (
        <Sheet title="Game" center onDismiss={() => setMoreOpen(false)}>
          <div style={{ padding: "0 20px" }}>
            <SwitchRow title="Move sounds" checked={cfg.chessSounds} onChange={(v) => core.settings.update((s) => ({ ...s, chessSounds: v }))} />
            <SwitchRow title="Highlight legal moves" checked={cfg.chessLegal} onChange={(v) => core.settings.update((s) => ({ ...s, chessLegal: v }))} />
          </div>
          <Row2 title="Flip board" icon="flip" onClick={() => { setFlip(!flip); setMoreOpen(false); }} />
          <Row2 title={expanded ? "Show room controls" : "Large workspace"} icon={expanded ? "collapse" : "expand"} onClick={() => { onExpand(!expanded); setMoreOpen(false); }} />
          <Row2 title="Copy notation (PGN)" icon="copy" onClick={() => { copyText(g.pgn()); setMoreOpen(false); }} />
          {g.bughouse && (
            <div style={{ padding: "8px 20px" }}>
              <Tiny>Moves</Tiny>
              <div style={{ color: "var(--muted)", fontSize: 12, fontWeight: 500, marginTop: 4 }}>Board A</div><div className="mono" style={{ fontSize: 13 }}>{g.moveText(0) || "No moves yet"}</div>
              <div style={{ color: "var(--muted)", fontSize: 12, fontWeight: 500, marginTop: 8 }}>Board B</div><div className="mono" style={{ fontSize: 13 }}>{g.moveText(1) || "No moves yet"}</div>
            </div>
          )}
          <Row2 title="Close chess workspace" subtitle="The game keeps going" icon="x" onClick={() => { setMoreOpen(false); onClose(); }} />
        </Sheet>
      )}
    </div>
  );
}

interface Ui { flip: boolean; view: number; dropSel: number; pending: Pending | null; showResult: boolean; mySeat: number; setFlip: (b: boolean) => void; setView: (n: number) => void; setDropSel: (n: number) => void; setPending: (p: Pending | null) => void; setShowResult: (b: boolean) => void }

function BoardBlock({ g, b, size, ui, now, showLegal }: { g: ChessGame; b: number; size: number; ui: Ui; now: number; showLegal: boolean }) {
  const session = core.session;
  const board = g.boards[b];
  const mySeat = ui.mySeat;
  const myBoard = mySeat >= 0 ? g.boardOf(mySeat) : -1;
  const myColor = mySeat >= 0 && myBoard === b ? g.colorOf(mySeat) : -1;
  const natural = mySeat < 0 ? WHITE : b === myBoard ? g.colorOf(mySeat) : g.colorOf(g.partner(mySeat));
  const bottomColor = ui.flip ? 1 - natural : natural;
  const flipped = bottomColor === BLACK;
  const bottomSeat = g.seatFor(b, bottomColor);
  const topSeat = g.seatFor(b, 1 - bottomColor);
  const viewing = !g.bughouse && ui.view >= 0 && ui.view < board.moves.length;
  const pend = ui.pending && ui.pending.board === b ? ui.pending : null;
  const hist = useMemo(() => (viewing ? historic(g, ui.view) : null), [g.id, ui.view, viewing]);
  const shown = hist ? { pos: hist.pos, lf: hist.lf, lt: hist.lt } : pend ? { pos: pend.pos, lf: pend.move.from, lt: pend.move.to } : { pos: board.pos, lf: board.lastFrom, lt: board.lastTo };
  const interactive = g.live && myColor >= 0 && !viewing && !ui.pending && board.pos.turn === myColor;
  const legal = useMemo(() => (interactive ? board.pos.legalMoves() : []), [board.pos.fen(), interactive]);
  const variant = g.pocketMode !== POCKET_NONE;
  return (
    <div style={{ width: size, flex: "none" }}>
      <PlayerStrip g={g} seat={topSeat} now={now} pos={shown.pos} board={b} />
      {variant && <PocketRow pos={shown.pos} color={1 - bottomColor} selectable={interactive && 1 - bottomColor === myColor} ui={ui} />}
      <div style={{ position: "relative" }}>
        <ChessBoardView pos={shown.pos} flipped={flipped} lastFrom={shown.lf} lastTo={shown.lt} myColor={myColor} interactive={interactive} legal={legal} showLegal={showLegal} dropType={interactive ? ui.dropSel : 0}
          label={g.bughouse ? `Board ${b === 0 ? "A" : "B"}` : "Chessboard"}
          onMove={(m) => { ui.setPending({ board: b, move: m, pos: board.pos.play(m).pos }); ui.setDropSel(0); session.chessAct("chess:move", g.id, { m: m.uci(), ply: board.moves.length }, (ok) => { if (!ok) ui.setPending(null); }); }} />
        {g.over && ui.showResult && !g.bughouse && (
          <div style={{ position: "absolute", left: "50%", top: "50%", transform: "translate(-50%,-50%)", borderRadius: "var(--r-s)", background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border-strong)", padding: "16px 22px", textAlign: "center" }}>
            <div className="display" style={{ fontSize: 20, letterSpacing: 1 }}>{g.winnerLine()}</div>
            <div style={{ color: "var(--muted)", fontSize: 13, marginTop: 2 }}>{reasonLine(g.reason)}</div>
            <div style={{ height: 12 }} /><Button small kind="secondary" text="Review board" onClick={() => ui.setShowResult(false)} />
          </div>
        )}
      </div>
      {variant && <PocketRow pos={shown.pos} color={bottomColor} selectable={interactive && bottomColor === myColor} ui={ui} />}
      <PlayerStrip g={g} seat={bottomSeat} now={now} pos={shown.pos} board={b} />
    </div>
  );
}

function PlayerStrip({ g, seat, now, pos, board }: { g: ChessGame; seat: number; now: number; pos: Position; board: number }) {
  const session = core.session;
  const s = g.seats[seat];
  const who = [...(session.room?.participants.values() ?? [])].find((p) => p.uid === s?.uid);
  const running = g.running(seat);
  const toMove = g.live && g.boards[board].pos.turn === g.colorOf(seat);
  const remaining = g.remaining(seat, now);
  const low = running && remaining < 10_000;
  const team = g.bughouse ? g.teamOf(seat) : -1;
  const color = g.colorOf(seat);
  const caps = g.pocketMode === POCKET_NONE ? missing(pos, 1 - color) : [];
  const lead = caps.reduce((n, t) => n + VALUE[t], 0) - missing(pos, color).reduce((n, t) => n + VALUE[t], 0);
  return (
    <div className="hstack" style={{ height: 46, background: "var(--surface)", paddingRight: 8 }}>
      <div style={{ width: 3, alignSelf: "stretch", background: team === 0 ? "var(--accent-bright)" : team === 1 ? "var(--warn)" : toMove ? "var(--accent)" : "transparent" }} />
      <div style={{ width: 8 }} />
      {who ? <Avatar avatar={who.avatar} size={28} name={who.displayName} uid={who.uid} mine={who.uid === session.myUid} /> : <span style={{ width: 28, height: 28, borderRadius: "50%", boxShadow: "inset 0 0 0 1px var(--border-strong)" }} />}
      <div style={{ width: 8 }} />
      <div className="grow" style={{ minWidth: 0 }}>
        <div className="hstack g6">
          <span style={{ width: 9, height: 9, borderRadius: "50%", background: color === WHITE ? PIECE_LIGHT : PIECE_DARK, boxShadow: "inset 0 0 0 1px var(--border-strong)" }} />
          <span className="ellipsis" style={{ fontSize: 13, fontWeight: 500 }}>{(s?.name ?? "Waiting…") + (s && s.uid === session.myUid ? " (you)" : "")}</span>
          {g.live && s && !who && <Tiny>away</Tiny>}
        </div>
        {g.bughouse ? <Tiny>Team {team + 1} · Board {board === 0 ? "A" : "B"}</Tiny>
          : (caps.length > 0 || lead !== 0) && <div className="hstack" style={{ gap: 0 }}>{caps.slice(0, 14).map((t, i) => <PieceIcon key={i} type={t} color={1 - color} size={13} />)}{lead > 0 && <span style={{ color: "var(--muted)", fontSize: 11, marginLeft: 4 }}>+{lead}</span>}</div>}
      </div>
      <div className="mono" style={{ borderRadius: "var(--r-s)", padding: "5px 10px", fontSize: 17, fontWeight: 600, background: low ? "var(--danger-soft)" : running ? "var(--accent-30)" : "var(--surface2)", boxShadow: `inset 0 0 0 1px ${low ? "var(--danger)" : running ? "var(--accent)" : "var(--border)"}`, color: low ? "var(--danger)" : running ? "var(--text)" : "var(--muted)" }}>{fmtMs(remaining)}</div>
    </div>
  );
}

function PocketRow({ pos, color, selectable, ui }: { pos: Position; color: number; selectable: boolean; ui: Ui }) {
  const items = [];
  for (let t = QUEEN; t >= PAWN; t--) {
    const n = pos.pocket[color][t];
    const on = selectable && ui.dropSel === t;
    items.push(
      <button key={t} disabled={!(selectable && n > 0)} onClick={() => ui.setDropSel(on ? 0 : t)} style={{ position: "relative", width: 34, height: 34, margin: "0 2px", borderRadius: "var(--r-s)", display: "grid", placeItems: "center", background: on ? "var(--accent-30)" : "transparent", boxShadow: `inset 0 0 0 1px ${on ? "var(--accent)" : n > 0 ? "var(--border-strong)" : "transparent"}` }}>
        <PieceIcon type={t} color={color} size={28} alpha={n > 0 ? 1 : 0.22} />
        {n > 1 && <span style={{ position: "absolute", right: 2, bottom: 0, fontSize: 10, fontWeight: 700 }}>{n}</span>}
      </button>
    );
  }
  return <div className="hstack" style={{ height: 40, background: "var(--surface2)", padding: "0 8px", gap: 0 }}><Tiny>RESERVE</Tiny><span style={{ width: 8 }} />{items}{selectable && <><span className="grow" /><Tiny>{ui.dropSel > 0 ? "Click a square to drop" : "Click a piece to drop it"}</Tiny></>}</div>;
}

function InfoBlock({ g, ui, onClose, onNew, expanded, onExpand, onMore, onResign }: { g: ChessGame; ui: Ui; onClose: () => void; onNew: () => void; expanded: boolean; onExpand: (b: boolean) => void; onMore: () => void; onResign: () => void }) {
  const session = core.session;
  const me = session.myUid;
  const seat = ui.mySeat;
  const seated = seat >= 0;
  const tools = <>
    <IconButton icon="flip" size={40} bg="var(--surface2)" bordered title="Flip board" onClick={() => ui.setFlip(!ui.flip)} />
    <IconButton icon={expanded ? "collapse" : "expand"} size={40} bg="var(--surface2)" bordered title={expanded ? "Show room controls" : "Large workspace"} onClick={() => onExpand(!expanded)} />
    <IconButton icon="more" size={40} bg="var(--surface2)" bordered title="Game settings" onClick={onMore} />
  </>;
  return (
    <div className="stack g8">
      {g.bughouse && g.over && (
        <div style={{ borderRadius: "var(--r-s)", background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border-strong)", padding: 14, textAlign: "center" }}>
          <div className="display" style={{ fontSize: 20, letterSpacing: 1 }}>{g.winnerLine()}</div>
          <div style={{ color: "var(--muted)", fontSize: 13 }}>{reasonLine(g.reason)}</div>
          {g.winner >= 0 && g.winner <= 1 && <Muted size={12}>{[0, 1, 2, 3].filter((i) => g.teamOf(i) === g.winner).map((i) => g.seats[i]?.name ?? "?").join(" & ")}</Muted>}
        </div>
      )}
      {g.live ? <>
        {!seated ? <Tiny>You are watching</Tiny> : g.bughouse && <Muted size={12}>You and {g.seats[g.partner(seat)]?.name ?? "your partner"} play together. Captures go to their reserve.</Muted>}
        {g.drawBy >= 0 && seated && !g.bughouse && (g.drawBy !== seat ? (
          <div className="hstack g6" style={{ borderRadius: "var(--r-s)", background: "var(--surface2)", boxShadow: "inset 0 0 0 1px var(--accent)", padding: 10 }}>
            <span className="grow" style={{ fontSize: 13 }}>{g.seats[g.drawBy]?.name ?? "Your opponent"} offers a draw</span>
            <Button small text="Accept" onClick={() => session.chessAct("chess:draw", g.id, { act: "accept" })} /><Button small kind="secondary" text="Decline" onClick={() => session.chessAct("chess:draw", g.id, { act: "decline" })} />
          </div>
        ) : <Muted size={12}>Draw offered. Waiting for your opponent.</Muted>)}
        <div className="hstack g8">
          {seated ? <>
            <div className="grow"><Button block small kind="danger" icon="flag" text="Resign" onClick={onResign} /></div>
            {!g.bughouse && <div className="grow"><Button block small kind="secondary" text={g.drawBy === seat ? "Offered" : "Offer draw"} disabled={g.drawBy >= 0} onClick={() => session.chessAct("chess:draw", g.id, { act: "offer" })} /></div>}
          </> : <span className="grow" />}
          {tools}
        </div>
      </> : g.over ? <>
        {!g.bughouse && !ui.showResult && <div style={{ fontSize: 13, fontWeight: 600 }}>{g.winnerLine()} · {reasonLine(g.reason)}</div>}
        <div className="hstack" style={{ flexWrap: "wrap", gap: 8 }}>
          {seated && g.next === "" && (() => { const iVoted = g.rematch.has(me); const other = [...g.rematch].some((u) => u !== me); return <Button small disabled={iVoted} text={iVoted ? "Waiting for others…" : other ? "Accept rematch" : "Rematch"} onClick={() => session.chessAct("chess:rematch", g.id)} />; })()}
          <Button small kind="secondary" text="New game" onClick={onNew} />
          <Button small kind="secondary" icon="copy" text="Copy PGN" onClick={() => copyText(g.pgn())} />
          <Button small kind="ghost" text="Close" onClick={onClose} />
        </div>
        <div className="hstack g8">{tools}</div>
      </> : null}
    </div>
  );
}

function MoveList({ g, ui }: { g: ChessGame; ui: Ui }) {
  if (g.bughouse) return null;
  const sans = g.boards[0].sans;
  const rows = Math.ceil(sans.length / 2);
  const end = useRef<HTMLDivElement>(null);
  useEffect(() => { if (rows > 0 && ui.view < 0) end.current?.scrollIntoView({ block: "nearest" }); }, [sans.length]);
  const live = ui.view < 0 || ui.view >= sans.length;
  const cur = live ? sans.length : ui.view;
  return (
    <div style={{ flex: 1, minHeight: 0, borderRadius: "var(--r-s)", background: "var(--surface)", boxShadow: "inset 0 0 0 1px var(--border)", display: "flex", flexDirection: "column" }}>
      <div className="hstack" style={{ padding: "2px 8px" }}>
        <Tiny>MOVES</Tiny><span className="grow" />
        <IconButton icon="chevron-left" size={36} iconSize={18} disabled={!(sans.length > 0 && cur > 0)} title="Previous move" onClick={() => ui.setView(Math.max(0, cur - 1))} />
        <IconButton icon="chevron-right" size={36} iconSize={18} disabled={live} title="Next move" onClick={() => { const n = cur + 1; ui.setView(n >= sans.length ? -1 : n); }} />
        {!live && <Chip text="Live" selected onClick={() => ui.setView(-1)} />}
      </div>
      {sans.length === 0 ? <div style={{ flex: 1, display: "grid", placeItems: "center" }}><Tiny>No moves yet</Tiny></div> : (
        <div className="scroll">
          {Array.from({ length: rows }, (_, r) => (
            <div key={r} className="hstack" style={{ padding: "0 8px" }}>
              <span className="mono" style={{ width: 32, color: "var(--faint)", fontSize: 12 }}>{r + 1}.</span>
              {[0, 1].map((k) => {
                const i = r * 2 + k; const san = sans[i];
                const selected = live ? i === sans.length - 1 : i + 1 === ui.view;
                return <button key={k} disabled={san == null} className="grow mono" style={{ textAlign: "left", borderRadius: "var(--r-s)", padding: "6px 8px", fontSize: 13, background: selected ? "var(--accent-30)" : "transparent", color: selected ? "var(--accent-bright)" : "var(--text)" }} onClick={() => ui.setView(i + 1 >= sans.length ? -1 : i + 1)}>{san ?? ""}</button>;
              })}
            </div>
          ))}
          <div ref={end} />
        </div>
      )}
    </div>
  );
}
