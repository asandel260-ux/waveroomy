import { J, arr, int, lng, nowMs, objects, str, strings } from "../core/util";
import { Move, POCKET_EXTERNAL, POCKET_NONE, POCKET_OWN, Position, colorOf as pieceColor } from "./rules";

/** Chat messages starting with this carry a chess challenge card: MARK + gameId + US + text. */
export const CHESS_MARK = "\u001Fchess:";

export interface ChessMode { id: string; label: string; variant: boolean; about: string; presets: [number, number][] }

export const ChessModes = {
  all: [
    { id: "standard", label: "Standard", variant: false, about: "Normal chess rules with no special variant mechanics. The objective is checkmate, with standard draw conditions.", presets: [[15, 10], [30, 0], [30, 20], [60, 0]] },
    { id: "rapid", label: "Rapid", variant: false, about: "Normal chess played with a longer time control than Blitz or Bullet.", presets: [[10, 0], [10, 5], [15, 10], [30, 0]] },
    { id: "blitz", label: "Blitz", variant: false, about: "Normal chess played at a fast time control.", presets: [[3, 0], [3, 2], [5, 0], [5, 3]] },
    { id: "bullet", label: "Bullet", variant: false, about: "Extremely fast chess intended for very short games.", presets: [[1, 0], [1, 1], [2, 0]] },
    { id: "crazyhouse", label: "Crazyhouse", variant: true, about: "Normal chess plus piece drops. A captured piece goes to your reserve, changes colour, and can later be dropped on any empty square instead of making a normal move. Pawns cannot be dropped on the first or eighth rank.", presets: [[3, 0], [3, 2], [5, 0], [5, 3]] },
    { id: "bughouse", label: "Bughouse", variant: true, about: "Four players, two boards, two teams of two. Pieces you capture go to your teammate's reserve, and your teammate can drop them on their board. The game depends on both boards and on talking to your partner.", presets: [[1, 0], [2, 0], [3, 0], [3, 2]] }
  ] as ChessMode[],
  get(id: string): ChessMode { return this.all.find((m) => m.id === id) ?? this.all[0]; },
  valid(id: string): boolean { return this.all.some((m) => m.id === id); }
};

export interface ChessSeat { uid: string; name: string }

export class ChessBoard {
  moves: string[] = [];   // UCI, drops as "N@e4"
  sans: string[] = [];
  lastFrom = -1;
  lastTo = -1;
  check = false;
  constructor(public pos: Position) {}
}

export class ChessGame {
  readonly bughouse: boolean;
  readonly n: number;
  readonly pocketMode: number;
  seats: (ChessSeat | null)[];
  boards: ChessBoard[];
  clocks: number[];
  /** Local time at which `clocks` was true for each board's running side. */
  stamp = [0, 0];
  status = "waiting";       // waiting | live | over | cancelled | expired
  winner = -1;              // two players: 0 white, 1 black. Bughouse: winning team. 2 = draw
  reason = "";
  drawBy = -1;
  rematch = new Set<string>();
  startedAt = 0;
  endedAt = 0;
  next = "";
  /** Host-only: last time anything happened, for aborting games nobody starts. */
  activityAt = -1;

  constructor(public id: string, public mode: string, public baseSec: number, public incSec: number, public creatorUid: string, public createdAt: number) {
    this.bughouse = mode === "bughouse";
    this.n = this.bughouse ? 4 : 2;
    this.pocketMode = mode === "crazyhouse" ? POCKET_OWN : mode === "bughouse" ? POCKET_EXTERNAL : POCKET_NONE;
    this.seats = new Array(this.n).fill(null);
    this.boards = Array.from({ length: this.bughouse ? 2 : 1 }, () => new ChessBoard(Position.initial(this.pocketMode)));
    this.clocks = new Array(this.n).fill(0);
  }

  get label() { return ChessModes.get(this.mode).label; }
  get timeLabel() { return `${ChessGame.fmtBase(this.baseSec)}+${this.incSec}`; }
  get live() { return this.status === "live"; }
  get over() { return this.status === "over"; }
  get finished() { return this.status === "over" || this.status === "cancelled" || this.status === "expired"; }

  seatOf(uid: string): number { return this.seats.findIndex((s) => s?.uid === uid); }
  filled(): boolean { return this.seats.every((s) => s != null); }
  count(): number { return this.seats.filter((s) => s != null).length; }
  boardOf(seat: number) { return Math.floor(seat / 2); }
  colorOf(seat: number) { return seat % 2; }
  partner(seat: number) { return [3, 2, 1, 0][seat]; }
  teamOf(seat: number) { return !this.bughouse ? seat : seat === 0 || seat === 3 ? 0 : 1; }
  seatFor(board: number, color: number) { return board * 2 + color; }

  running(seat: number): boolean {
    if (!this.live) return false;
    const b = this.boards[this.boardOf(seat)];
    return b.moves.length >= 2 && b.pos.turn === this.colorOf(seat);
  }

  remaining(seat: number, now: number): number {
    let r = this.clocks[seat];
    if (this.running(seat)) r -= now - this.stamp[this.boardOf(seat)];
    return Math.max(0, r);
  }

  /** Move the game from "waiting" to "live". Two-player games randomise colours. */
  begin(now: number, randomise: boolean) {
    if (!this.bughouse && randomise && Math.random() < 0.5) {
      const t = this.seats[0]; this.seats[0] = this.seats[1]; this.seats[1] = t;
    }
    this.status = "live";
    this.startedAt = now;
    this.activityAt = now;
    for (let i = 0; i < this.n; i++) this.clocks[i] = this.baseSec * 1000;
    for (let i = 0; i < this.stamp.length; i++) this.stamp[i] = now;
    this.drawBy = -1;
  }

  private settle(now: number) {
    for (let s = 0; s < this.n; s++) if (this.running(s)) this.clocks[s] = Math.max(0, this.clocks[s] - (now - this.stamp[this.boardOf(s)]));
  }

  finish(winner: number, reason: string, now: number) {
    this.settle(now);
    this.status = "over";
    this.winner = winner;
    this.reason = reason;
    this.endedAt = now;
    this.drawBy = -1;
  }

  cancel(now: number, expired: boolean) {
    this.status = expired ? "expired" : "cancelled";
    this.endedAt = now;
  }

  resign(seat: number, now: number) {
    this.finish(this.bughouse ? 1 - this.teamOf(seat) : 1 - this.colorOf(seat), "Resignation", now);
  }

  private loserWins(seat: number) { return this.bughouse ? 1 - this.teamOf(seat) : 1 - this.colorOf(seat); }

  /** Called by the host a few times a second. Returns true when the game changed. */
  tick(now: number): boolean {
    if (this.status === "waiting") {
      if (now - this.createdAt > 10 * 60_000) { this.cancel(now, true); return true; }
      return false;
    }
    if (!this.live) return false;
    if (this.activityAt < 0) this.activityAt = now;
    if (this.boards.some((b) => b.moves.length < 2) && now - this.activityAt > 60_000) {
      this.finish(2, "No moves were played", now);
      return true;
    }
    for (let s = 0; s < this.n; s++) {
      if (this.running(s) && this.remaining(s, now) <= 0) {
        this.clocks[s] = 0;
        this.flag(s, now);
        return true;
      }
    }
    return false;
  }

  private flag(seat: number, now: number) {
    const b = this.boards[this.boardOf(seat)];
    if (!this.bughouse && this.pocketMode === POCKET_NONE && b.pos.insufficient()) this.finish(2, "Timeout vs insufficient material", now);
    else this.finish(this.loserWins(seat), "Timeout", now);
  }

  /**
   * Play [uci] for [seat]. [ply] is the number of moves the sender saw on that board; it guards against
   * duplicate and stale submissions. Returns an error message, or null when the game state changed.
   */
  applyMove(seat: number, uci: string, ply: number, now: number): string | null {
    if (!this.live) return "This game is not running.";
    if (seat < 0 || seat >= this.n) return "You are not playing.";
    const bi = this.boardOf(seat);
    const b = this.boards[bi];
    if (b.pos.turn !== this.colorOf(seat)) return "It is not your turn.";
    if (ply !== b.moves.length) return "The board changed — try again.";
    const mv = Move.parse(uci);
    if (!mv) return "Illegal move.";
    const legal = b.pos.legalMoves();
    if (!legal.some((m) => m.equals(mv))) return "Illegal move.";
    const clockRuns = b.moves.length >= 2;
    if (clockRuns) {
      this.clocks[seat] -= now - this.stamp[bi];
      if (this.clocks[seat] <= 0) {
        this.clocks[seat] = 0;
        this.flag(seat, now);
        return null;
      }
    }
    let san = b.pos.san(mv, legal);
    const played = b.pos.play(mv);
    b.pos = played.pos;
    b.moves.push(mv.uci());
    b.lastFrom = mv.from;
    b.lastTo = mv.to;
    if (this.bughouse && played.captured > 0) {
      const p = this.partner(seat);
      const pb = this.boards[this.boardOf(p)];
      pb.pos.pocket[this.colorOf(p)][played.captured]++;
    }
    if (clockRuns) this.clocks[seat] += this.incSec * 1000;
    this.stamp[bi] = now;
    this.activityAt = now;
    this.drawBy = -1;
    const pos = b.pos;
    const check = pos.inCheck(pos.turn);
    b.check = check;
    const replies = pos.legalMoves();
    san += replies.length === 0 && check ? "#" : check ? "+" : "";
    b.sans.push(san);

    if (replies.length === 0 && check) this.finish(this.bughouse ? this.teamOf(seat) : this.colorOf(seat), "Checkmate", now);
    else if (replies.length === 0 && !this.bughouse) this.finish(2, "Stalemate", now);
    else if (!this.bughouse && pos.insufficient()) this.finish(2, "Insufficient material", now);
    else if (!this.bughouse && pos.half >= 100) this.finish(2, "Fifty-move rule", now);
    else if (!this.bughouse && this.repetitions(bi) >= 3) this.finish(2, "Threefold repetition", now);
    return null;
  }

  /** How many times the current position of [board] has occurred (replays the move list). */
  repetitions(board: number): number {
    const b = this.boards[board];
    if (this.bughouse) return 0;
    let p = Position.initial(this.pocketMode);
    const target = b.pos.key();
    let count = p.key() === target ? 1 : 0;
    for (const u of b.moves) {
      const m = Move.parse(u);
      if (!m) break;
      p = p.play(m).pos;
      if (p.key() === target) count++;
    }
    return count;
  }

  drawOffer(seat: number): string | null {
    if (!this.live) return "This game is not running.";
    if (this.bughouse) return "Draws are not offered in Bughouse.";
    if (this.drawBy === seat) return null;
    if (this.drawBy >= 0) return "A draw is already on offer.";
    this.drawBy = seat;
    return null;
  }

  winnerLine(): string {
    if (this.winner === 2) return "DRAW";
    if (this.bughouse) return `TEAM ${this.winner + 1} WON`;
    return (this.seats[this.winner]?.name ?? (this.winner === 0 ? "WHITE" : "BLACK")).toUpperCase() + " WON";
  }

  /* ---------------- notation ---------------- */

  pgn(): string {
    const d = new Date(this.startedAt > 0 ? this.startedAt : this.createdAt);
    const date = `${d.getFullYear()}.${String(d.getMonth() + 1).padStart(2, "0")}.${String(d.getDate()).padStart(2, "0")}`;
    const res = this.winner === -1 ? "*" : this.winner === 2 ? "1/2-1/2" : this.winner === 0 ? "1-0" : "0-1";
    let sb = "";
    if (!this.bughouse) {
      sb += `[Event "HoMit ${this.label}"]\n[Site "HoMit voice room"]\n[Date "${date}"]\n`;
      sb += `[White "${this.seats[0]?.name ?? "?"}"]\n[Black "${this.seats[1]?.name ?? "?"}"]\n`;
      sb += `[Result "${res}"]\n[TimeControl "${this.baseSec}+${this.incSec}"]\n`;
      if (this.mode === "crazyhouse") sb += `[Variant "Crazyhouse"]\n`;
      if (this.over) sb += `[Termination "${this.reason}"]\n`;
      sb += "\n" + this.moveText(0);
      if (res !== "*") sb += " " + res;
    } else {
      sb += `HoMit Bughouse ${this.timeLabel} · ${date}\n`;
      const team = (t: number) => `Team ${t + 1}: ` + [0, 1, 2, 3].filter((i) => this.teamOf(i) === t).map((i) => this.seats[i]?.name ?? "?").join(" & ");
      sb += team(0) + "\n" + team(1) + "\n";
      if (this.over) sb += `Result: ${this.winner === 2 ? "draw" : `Team ${this.winner + 1} won`} — ${this.reason}\n`;
      sb += `\nBoard A (${this.seats[0]?.name ?? "?"} / ${this.seats[1]?.name ?? "?"}):\n` + this.moveText(0);
      sb += `\n\nBoard B (${this.seats[2]?.name ?? "?"} / ${this.seats[3]?.name ?? "?"}):\n` + this.moveText(1);
    }
    return sb;
  }

  moveText(board: number): string {
    const b = this.boards[board];
    let sb = "";
    for (let i = 0; i < b.sans.length; i++) {
      if (i % 2 === 0) { if (i > 0) sb += " "; sb += `${i / 2 + 1}. `; } else sb += " ";
      sb += b.sans[i];
    }
    return sb;
  }

  /* ---------------- wire format ---------------- */

  toJson(): J {
    const t = nowMs();
    return {
      id: this.id, mode: this.mode, base: this.baseSec, inc: this.incSec, creator: this.creatorUid, at: this.createdAt,
      status: this.status,
      seats: this.seats.map((s) => (s ? { uid: s.uid, name: s.name } : {})),
      boards: this.boards.map((b) => ({ fen: b.pos.fen(), mv: b.moves, sn: b.sans, lf: b.lastFrom, lt: b.lastTo, ck: b.check })),
      // clocks are sent as of now, so a receiver that restarts its timers on receipt stays correct
      clocks: Array.from({ length: this.n }, (_, i) => this.remaining(i, t)),
      winner: this.winner, reason: this.reason, draw: this.drawBy, rematch: [...this.rematch],
      start: this.startedAt, end: this.endedAt, next: this.next
    };
  }

  static from(j: J, localNow = nowMs()): ChessGame | null {
    try {
      const mode = str(j, "mode");
      if (!ChessModes.valid(mode)) return null;
      const g = new ChessGame(str(j, "id"), mode, int(j, "base"), int(j, "inc"), str(j, "creator"), lng(j, "at"));
      g.status = str(j, "status", "waiting");
      objects(arr(j, "seats")).forEach((s, i) => {
        if (i < g.n && str(s, "uid")) g.seats[i] = { uid: str(s, "uid"), name: str(s, "name") };
      });
      objects(arr(j, "boards")).forEach((bj, i) => {
        if (i < g.boards.length) {
          const b = g.boards[i];
          const p = Position.fromFen(str(bj, "fen"), g.pocketMode);
          if (p) b.pos = p;
          b.moves.push(...strings(arr(bj, "mv")));
          b.sans.push(...strings(arr(bj, "sn")));
          b.lastFrom = int(bj, "lf", -1); b.lastTo = int(bj, "lt", -1);
          b.check = bj.ck === true;
        }
      });
      const ck = arr(j, "clocks");
      if (ck) for (let i = 0; i < Math.min(ck.length, g.n); i++) g.clocks[i] = Number(ck[i]) || 0;
      g.winner = int(j, "winner", -1);
      g.reason = str(j, "reason");
      g.drawBy = int(j, "draw", -1);
      for (const r of strings(arr(j, "rematch"))) g.rematch.add(r);
      g.startedAt = lng(j, "start"); g.endedAt = lng(j, "end"); g.next = str(j, "next");
      for (let i = 0; i < g.stamp.length; i++) g.stamp[i] = localNow;
      return g;
    } catch {
      return null;
    }
  }

  static fmtBase(sec: number): string {
    return sec % 60 === 0 ? `${sec / 60}` : sec < 60 ? `${sec}s` : (sec / 60).toFixed(1);
  }
}

/** The room's chess games. Newest last; only the newest can be active. */
export class ChessState {
  games = new Map<string, ChessGame>();
  get current(): ChessGame | null { const v = [...this.games.values()]; return v.length ? v[v.length - 1] : null; }
  get active(): ChessGame | null { const c = this.current; return c && (c.status === "waiting" || c.status === "live") ? c : null; }
  put(g: ChessGame) {
    this.games.set(g.id, g);
    while (this.games.size > 6) this.games.delete(this.games.keys().next().value as string);
  }
  toJson(): J[] { return [...this.games.values()].map((g) => g.toJson()); }
  load(a: any[] | null) { this.games.clear(); for (const j of objects(a)) { const g = ChessGame.from(j); if (g) this.put(g); } }
  clear() { this.games.clear(); }
}
export { pieceColor };
