/*
 * Pure chess rules: legal move generation, castling, en passant, promotion, check / mate /
 * stalemate, insufficient material, and piece drops for Crazyhouse and Bughouse.
 * A line-by-line port of chess/ChessRules.kt.
 */

export const PAWN = 1, KNIGHT = 2, BISHOP = 3, ROOK = 4, QUEEN = 5, KING = 6;
export const WHITE = 0, BLACK = 1;
export const POCKET_NONE = 0, POCKET_OWN = 1, POCKET_EXTERNAL = 2;

export const mkPiece = (type: number, color: number) => type | (color << 3);
export const typeOf = (p: number) => p & 7;
export const colorOf = (p: number) => p >> 3;
export const sqName = (s: number) => String.fromCharCode(97 + (s & 7)) + ((s >> 3) + 1);

export function sqFromName(n: string): number {
  if (n.length !== 2) return -1;
  const f = n.charCodeAt(0) - 97;
  const r = n.charCodeAt(1) - 49;
  return f >= 0 && f <= 7 && r >= 0 && r <= 7 ? r * 8 + f : -1;
}

const LETTERS = "?PNBRQK";

export class Move {
  constructor(public from: number, public to: number, public promo = 0, public drop = 0) {}
  uci(): string {
    if (this.drop > 0) return `${LETTERS[this.drop]}@${sqName(this.to)}`;
    return sqName(this.from) + sqName(this.to) + (this.promo > 0 ? LETTERS[this.promo].toLowerCase() : "");
  }
  equals(o: Move) { return this.from === o.from && this.to === o.to && this.promo === o.promo && this.drop === o.drop; }
  static parse(s: string): Move | null {
    if (s.length === 4 && s[1] === "@") {
      const t = "PNBRQ".indexOf(s[0]) + 1;
      const to = sqFromName(s.substring(2));
      return t >= 1 && t <= 5 && to >= 0 ? new Move(-1, to, 0, t) : null;
    }
    if (s.length !== 4 && s.length !== 5) return null;
    const from = sqFromName(s.substring(0, 2));
    const to = sqFromName(s.substring(2, 4));
    if (from < 0 || to < 0) return null;
    let promo = 0;
    if (s.length === 5) {
      promo = "pnbrq".indexOf(s[4]) + 1;
      if (promo < 2 || promo > 5) return null;
    }
    return new Move(from, to, promo);
  }
}

export interface Played { pos: Position; captured: number }

const KN = [[1, 2], [2, 1], [2, -1], [1, -2], [-1, -2], [-2, -1], [-2, 1], [-1, 2]];
const KG = [[1, 0], [1, 1], [0, 1], [-1, 1], [-1, 0], [-1, -1], [0, -1], [1, -1]];
const DIAG = [[1, 1], [-1, 1], [-1, -1], [1, -1]];
const ORTH = [[1, 0], [0, 1], [-1, 0], [0, -1]];

export const PIECE_CHARS = ".PNBRQK..pnbrqk";

export class Position {
  sq = new Int8Array(64);
  turn = WHITE;
  castle = 15;          // 1 = white short, 2 = white long, 4 = black short, 8 = black long
  ep = -1;              // square a pawn may capture onto en passant
  half = 0;
  full = 1;
  prom = new Uint8Array(64);   // squares holding a piece that started as a pawn (Crazyhouse)
  pocket: number[][] = [new Array(6).fill(0), new Array(6).fill(0)];

  constructor(public mode: number = POCKET_NONE) {}

  copy(): Position {
    const n = new Position(this.mode);
    n.sq.set(this.sq);
    n.turn = this.turn; n.castle = this.castle; n.ep = this.ep; n.half = this.half; n.full = this.full;
    n.prom.set(this.prom);
    for (let c = 0; c < 2; c++) n.pocket[c] = [...this.pocket[c]];
    return n;
  }

  kingSq(color: number): number {
    const k = mkPiece(KING, color);
    for (let i = 0; i < 64; i++) if (this.sq[i] === k) return i;
    return -1;
  }

  attacked(s: number, by: number): boolean {
    const f = s & 7, r = s >> 3;
    const pr = by === WHITE ? r - 1 : r + 1;
    if (pr >= 0 && pr <= 7) {
      const pawn = mkPiece(PAWN, by);
      if (f > 0 && this.sq[pr * 8 + f - 1] === pawn) return true;
      if (f < 7 && this.sq[pr * 8 + f + 1] === pawn) return true;
    }
    const kn = mkPiece(KNIGHT, by);
    for (const o of KN) {
      const nf = f + o[0], nr = r + o[1];
      if (nf >= 0 && nf <= 7 && nr >= 0 && nr <= 7 && this.sq[nr * 8 + nf] === kn) return true;
    }
    const kg = mkPiece(KING, by);
    for (const o of KG) {
      const nf = f + o[0], nr = r + o[1];
      if (nf >= 0 && nf <= 7 && nr >= 0 && nr <= 7 && this.sq[nr * 8 + nf] === kg) return true;
    }
    const bq1 = mkPiece(BISHOP, by), bq2 = mkPiece(QUEEN, by);
    for (const o of DIAG) {
      let nf = f + o[0], nr = r + o[1];
      while (nf >= 0 && nf <= 7 && nr >= 0 && nr <= 7) {
        const p = this.sq[nr * 8 + nf];
        if (p !== 0) { if (p === bq1 || p === bq2) return true; break; }
        nf += o[0]; nr += o[1];
      }
    }
    const rq1 = mkPiece(ROOK, by);
    for (const o of ORTH) {
      let nf = f + o[0], nr = r + o[1];
      while (nf >= 0 && nf <= 7 && nr >= 0 && nr <= 7) {
        const p = this.sq[nr * 8 + nf];
        if (p !== 0) { if (p === rq1 || p === bq2) return true; break; }
        nf += o[0]; nr += o[1];
      }
    }
    return false;
  }

  inCheck(color: number): boolean {
    const k = this.kingSq(color);
    return k >= 0 && this.attacked(k, 1 - color);
  }

  private addPawn(out: Move[], from: number, to: number, promo: boolean) {
    if (promo) for (const t of [QUEEN, ROOK, BISHOP, KNIGHT]) out.push(new Move(from, to, t));
    else out.push(new Move(from, to));
  }

  private pseudo(): Move[] {
    const out: Move[] = [];
    const me = this.turn;
    for (let s = 0; s < 64; s++) {
      const p = this.sq[s];
      if (p === 0 || colorOf(p) !== me) continue;
      const f = s & 7, r = s >> 3;
      switch (typeOf(p)) {
        case PAWN: {
          const dir = me === WHITE ? 1 : -1;
          const startR = me === WHITE ? 1 : 6;
          const promR = me === WHITE ? 7 : 0;
          const nr = r + dir;
          if (nr >= 0 && nr <= 7) {
            const t = nr * 8 + f;
            if (this.sq[t] === 0) {
              this.addPawn(out, s, t, nr === promR);
              if (r === startR && this.sq[(nr + dir) * 8 + f] === 0) out.push(new Move(s, (nr + dir) * 8 + f));
            }
            for (const df of [-1, 1]) {
              const nf = f + df;
              if (nf < 0 || nf > 7) continue;
              const t2 = nr * 8 + nf;
              if (this.sq[t2] !== 0 && colorOf(this.sq[t2]) !== me) this.addPawn(out, s, t2, nr === promR);
              else if (t2 === this.ep && this.sq[t2] === 0) out.push(new Move(s, t2));
            }
          }
          break;
        }
        case KNIGHT: for (const o of KN) this.step(out, s, f + o[0], r + o[1], me); break;
        case KING: for (const o of KG) this.step(out, s, f + o[0], r + o[1], me); break;
        case BISHOP: this.slide(out, s, DIAG, me); break;
        case ROOK: this.slide(out, s, ORTH, me); break;
        case QUEEN: this.slide(out, s, DIAG, me); this.slide(out, s, ORTH, me); break;
      }
    }
    this.castling(out);
    if (this.mode !== POCKET_NONE) this.drops(out);
    return out;
  }

  private step(out: Move[], from: number, nf: number, nr: number, me: number) {
    if (nf < 0 || nf > 7 || nr < 0 || nr > 7) return;
    const t = nr * 8 + nf;
    const q = this.sq[t];
    if (q === 0 || colorOf(q) !== me) out.push(new Move(from, t));
  }

  private slide(out: Move[], from: number, dirs: number[][], me: number) {
    const f = from & 7, r = from >> 3;
    for (const o of dirs) {
      let nf = f + o[0], nr = r + o[1];
      while (nf >= 0 && nf <= 7 && nr >= 0 && nr <= 7) {
        const t = nr * 8 + nf;
        const q = this.sq[t];
        if (q === 0) out.push(new Move(from, t));
        else { if (colorOf(q) !== me) out.push(new Move(from, t)); break; }
        nf += o[0]; nr += o[1];
      }
    }
  }

  private castling(out: Move[]) {
    const s = this.sq;
    if (this.turn === WHITE) {
      if (s[4] !== mkPiece(KING, WHITE)) return;
      if ((this.castle & 1) !== 0 && s[7] === mkPiece(ROOK, WHITE) && s[5] === 0 && s[6] === 0 && !this.attacked(4, BLACK) && !this.attacked(5, BLACK)) out.push(new Move(4, 6));
      if ((this.castle & 2) !== 0 && s[0] === mkPiece(ROOK, WHITE) && s[1] === 0 && s[2] === 0 && s[3] === 0 && !this.attacked(4, BLACK) && !this.attacked(3, BLACK)) out.push(new Move(4, 2));
    } else {
      if (s[60] !== mkPiece(KING, BLACK)) return;
      if ((this.castle & 4) !== 0 && s[63] === mkPiece(ROOK, BLACK) && s[61] === 0 && s[62] === 0 && !this.attacked(60, WHITE) && !this.attacked(61, WHITE)) out.push(new Move(60, 62));
      if ((this.castle & 8) !== 0 && s[56] === mkPiece(ROOK, BLACK) && s[57] === 0 && s[58] === 0 && s[59] === 0 && !this.attacked(60, WHITE) && !this.attacked(59, WHITE)) out.push(new Move(60, 58));
    }
  }

  private drops(out: Move[]) {
    const pk = this.pocket[this.turn];
    for (let t = PAWN; t <= QUEEN; t++) {
      if (pk[t] <= 0) continue;
      for (let s = 0; s < 64; s++) {
        if (this.sq[s] !== 0) continue;
        if (t === PAWN) { const rk = s >> 3; if (rk === 0 || rk === 7) continue; }
        out.push(new Move(-1, s, 0, t));
      }
    }
  }

  play(m: Move): Played {
    const n = this.copy();
    const c = this.turn;
    let cap = 0;
    if (m.drop > 0) {
      n.sq[m.to] = mkPiece(m.drop, c);
      n.pocket[c][m.drop]--;
      n.ep = -1;
      n.half = 0;
    } else {
      const p = this.sq[m.from];
      const t = typeOf(p);
      const target = this.sq[m.to];
      if (target !== 0) {
        cap = typeOf(target);
        if (this.prom[m.to]) cap = PAWN;
      } else if (t === PAWN && (m.from & 7) !== (m.to & 7)) {
        const capSq = (m.from >> 3) * 8 + (m.to & 7);
        cap = PAWN;
        n.sq[capSq] = 0;
        n.prom[capSq] = 0;
      }
      const wasPromoted = this.prom[m.from] === 1;
      n.prom[m.from] = 0; n.prom[m.to] = 0;
      n.sq[m.from] = 0;
      n.sq[m.to] = m.promo > 0 ? mkPiece(m.promo, c) : p;
      if (m.promo > 0 || wasPromoted) n.prom[m.to] = 1;
      if (t === KING && Math.abs((m.to & 7) - (m.from & 7)) === 2) {
        const rank = m.from >> 3;
        if ((m.to & 7) === 6) { n.sq[rank * 8 + 7] = 0; n.sq[rank * 8 + 5] = mkPiece(ROOK, c); }
        else { n.sq[rank * 8] = 0; n.sq[rank * 8 + 3] = mkPiece(ROOK, c); }
      }
      if (t === KING) n.castle &= ~(c === WHITE ? 3 : 12);
      for (const [corner, bit] of [[0, 2], [7, 1], [56, 8], [63, 4]]) {
        if (m.from === corner || m.to === corner) n.castle &= ~bit;
      }
      n.ep = t === PAWN && Math.abs((m.to >> 3) - (m.from >> 3)) === 2 ? (m.from + m.to) >> 1 : -1;
      n.half = t === PAWN || cap > 0 ? 0 : this.half + 1;
      if (cap > 0 && this.mode === POCKET_OWN) n.pocket[c][cap]++;
    }
    if (c === BLACK) n.full = this.full + 1;
    n.turn = 1 - c;
    return { pos: n, captured: cap };
  }

  legalMoves(): Move[] {
    const me = this.turn;
    const res: Move[] = [];
    for (const m of this.pseudo()) {
      const n = this.play(m).pos;
      const k = n.kingSq(me);
      if (k < 0 || !n.attacked(k, 1 - me)) res.push(m);
    }
    return res;
  }

  /** Standard algebraic notation for [m]; [legal] must be this position's legal moves. Check marks are added by the caller. */
  san(m: Move, legal: Move[]): string {
    if (m.drop > 0) return (m.drop === PAWN ? "" : LETTERS[m.drop]) + "@" + sqName(m.to);
    const p = this.sq[m.from];
    const t = typeOf(p);
    if (t === KING && Math.abs((m.to & 7) - (m.from & 7)) === 2) return (m.to & 7) === 6 ? "O-O" : "O-O-O";
    const cap = this.sq[m.to] !== 0 || (t === PAWN && (m.from & 7) !== (m.to & 7));
    let sb = "";
    if (t === PAWN) {
      if (cap) sb += String.fromCharCode(97 + (m.from & 7)) + "x";
    } else {
      sb += LETTERS[t];
      const others = legal.filter((x) => x.drop === 0 && x.to === m.to && x.from !== m.from && typeOf(this.sq[x.from]) === t);
      if (others.length) {
        const sameFile = others.some((x) => (x.from & 7) === (m.from & 7));
        const sameRank = others.some((x) => (x.from >> 3) === (m.from >> 3));
        if (!sameFile) sb += String.fromCharCode(97 + (m.from & 7));
        else if (!sameRank) sb += String.fromCharCode(49 + (m.from >> 3));
        else sb += sqName(m.from);
      }
      if (cap) sb += "x";
    }
    sb += sqName(m.to);
    if (m.promo > 0) sb += "=" + LETTERS[m.promo];
    return sb;
  }

  /** King and minor-piece endings where nobody can ever mate. Never true with drops. */
  insufficient(): boolean {
    if (this.mode !== POCKET_NONE) return false;
    let knights = 0, bishops = 0;
    const colors = new Set<number>();
    for (let s = 0; s < 64; s++) {
      switch (typeOf(this.sq[s])) {
        case PAWN: case ROOK: case QUEEN: return false;
        case KNIGHT: knights++; break;
        case BISHOP: bishops++; colors.add(((s >> 3) + (s & 7)) & 1); break;
      }
    }
    if (knights + bishops <= 1) return true;
    return knights === 0 && colors.size === 1;
  }

  /** Position identity for repetition: pieces, side, rights, a usable en-passant square and pockets. */
  key(): string {
    let sb = "";
    for (let i = 0; i < 64; i++) sb += PIECE_CHARS[this.sq[i]];
    sb += this.turn + "" + this.castle;
    let epk = -1;
    if (this.ep >= 0) {
      const me = this.turn;
      const dir = me === WHITE ? -1 : 1;
      const f = this.ep & 7;
      const r = (this.ep >> 3) + dir;
      const pawn = mkPiece(PAWN, me);
      if (r >= 0 && r <= 7 && ((f > 0 && this.sq[r * 8 + f - 1] === pawn) || (f < 7 && this.sq[r * 8 + f + 1] === pawn))) epk = this.ep;
    }
    sb += epk;
    if (this.mode !== POCKET_NONE) for (let c = 0; c < 2; c++) for (let t = PAWN; t <= QUEEN; t++) sb += this.pocket[c][t] + ",";
    return sb;
  }

  fen(): string {
    let sb = "";
    for (let r = 7; r >= 0; r--) {
      let empty = 0;
      for (let f = 0; f < 8; f++) {
        const s = r * 8 + f;
        const p = this.sq[s];
        if (p === 0) { empty++; continue; }
        if (empty > 0) { sb += empty; empty = 0; }
        sb += PIECE_CHARS[p];
        if (this.prom[s]) sb += "~";
      }
      if (empty > 0) sb += empty;
      if (r > 0) sb += "/";
    }
    sb += " " + (this.turn === WHITE ? "w" : "b") + " ";
    let cs = "";
    if (this.castle & 1) cs += "K";
    if (this.castle & 2) cs += "Q";
    if (this.castle & 4) cs += "k";
    if (this.castle & 8) cs += "q";
    sb += (cs || "-") + " ";
    sb += (this.ep >= 0 ? sqName(this.ep) : "-") + " " + this.half + " " + this.full + " ";
    let pk = "";
    for (let c = 0; c < 2; c++) for (let t = PAWN; t <= QUEEN; t++) for (let i = 0; i < this.pocket[c][t]; i++) {
      const ch = LETTERS[t];
      pk += c === WHITE ? ch : ch.toLowerCase();
    }
    return sb + (pk || "-");
  }

  static initial(mode: number): Position {
    const p = new Position(mode);
    const back = [ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK];
    for (let f = 0; f < 8; f++) {
      p.sq[f] = mkPiece(back[f], WHITE);
      p.sq[8 + f] = mkPiece(PAWN, WHITE);
      p.sq[48 + f] = mkPiece(PAWN, BLACK);
      p.sq[56 + f] = mkPiece(back[f], BLACK);
    }
    return p;
  }

  static fromFen(fen: string, mode: number): Position | null {
    try {
      const parts = fen.trim().split(" ");
      if (parts.length < 7) return null;
      const p = new Position(mode);
      const rows = parts[0].split("/");
      if (rows.length !== 8) return null;
      for (let i = 0; i < 8; i++) {
        const r = 7 - i;
        let f = 0;
        for (const ch of rows[i]) {
          if (ch >= "0" && ch <= "9") f += ch.charCodeAt(0) - 48;
          else if (ch === "~") { const idx = r * 8 + f - 1; if (idx >= 0 && idx < 64) p.prom[idx] = 1; }
          else {
            const idx = PIECE_CHARS.indexOf(ch);
            if (idx <= 0 || f > 7) return null;
            p.sq[r * 8 + f] = idx;
            f++;
          }
        }
        if (f !== 8) return null;
      }
      p.turn = parts[1] === "b" ? BLACK : WHITE;
      let c = 0;
      for (const ch of parts[2]) c |= ch === "K" ? 1 : ch === "Q" ? 2 : ch === "k" ? 4 : ch === "q" ? 8 : 0;
      p.castle = c;
      p.ep = parts[3] === "-" ? -1 : sqFromName(parts[3]);
      p.half = parseInt(parts[4]) || 0;
      p.full = parseInt(parts[5]) || 1;
      if (parts[6] !== "-") for (const ch of parts[6]) {
        const t = "PNBRQ".indexOf(ch.toUpperCase()) + 1;
        if (t > 0) p.pocket[ch === ch.toUpperCase() ? WHITE : BLACK][t]++;
      }
      return p;
    } catch {
      return null;
    }
  }
}
