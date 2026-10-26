package app.waveroom.chess

import kotlin.math.abs

/*
 * Pure chess rules: legal move generation, castling, en passant, promotion, check / mate /
 * stalemate, insufficient material, and piece drops for Crazyhouse and Bughouse.
 * No Android types in here so it can be reasoned about (and tested) on its own.
 */

const val PAWN = 1
const val KNIGHT = 2
const val BISHOP = 3
const val ROOK = 4
const val QUEEN = 5
const val KING = 6

const val WHITE = 0
const val BLACK = 1

/** Pocket behaviour: none (standard), own (Crazyhouse), external (Bughouse: the game feeds pockets). */
const val POCKET_NONE = 0
const val POCKET_OWN = 1
const val POCKET_EXTERNAL = 2

fun mkPiece(type: Int, color: Int) = type or (color shl 3)
fun typeOf(p: Int) = p and 7
fun colorOf(p: Int) = p shr 3
fun sqName(s: Int): String = "${'a' + (s and 7)}${(s shr 3) + 1}"

fun sqFromName(n: String): Int {
    if (n.length != 2) return -1
    val f = n[0] - 'a'
    val r = n[1] - '1'
    return if (f in 0..7 && r in 0..7) r * 8 + f else -1
}

private const val LETTERS = "?PNBRQK"

data class Move(val from: Int, val to: Int, val promo: Int = 0, val drop: Int = 0) {
    fun uci(): String =
        if (drop > 0) "${LETTERS[drop]}@${sqName(to)}"
        else sqName(from) + sqName(to) + (if (promo > 0) LETTERS[promo].lowercaseChar().toString() else "")

    companion object {
        fun parse(s: String): Move? {
            if (s.length == 4 && s[1] == '@') {
                val t = "PNBRQ".indexOf(s[0]) + 1
                val to = sqFromName(s.substring(2))
                return if (t in 1..5 && to >= 0) Move(-1, to, 0, t) else null
            }
            if (s.length != 4 && s.length != 5) return null
            val from = sqFromName(s.substring(0, 2))
            val to = sqFromName(s.substring(2, 4))
            if (from < 0 || to < 0) return null
            var promo = 0
            if (s.length == 5) {
                promo = "pnbrq".indexOf(s[4]) + 1
                if (promo !in 2..5) return null
            }
            return Move(from, to, promo)
        }
    }
}

class Played(val pos: Position, val captured: Int)

private val KN = arrayOf(intArrayOf(1, 2), intArrayOf(2, 1), intArrayOf(2, -1), intArrayOf(1, -2), intArrayOf(-1, -2), intArrayOf(-2, -1), intArrayOf(-2, 1), intArrayOf(-1, 2))
private val KG = arrayOf(intArrayOf(1, 0), intArrayOf(1, 1), intArrayOf(0, 1), intArrayOf(-1, 1), intArrayOf(-1, 0), intArrayOf(-1, -1), intArrayOf(0, -1), intArrayOf(1, -1))
private val DIAG = arrayOf(intArrayOf(1, 1), intArrayOf(-1, 1), intArrayOf(-1, -1), intArrayOf(1, -1))
private val ORTH = arrayOf(intArrayOf(1, 0), intArrayOf(0, 1), intArrayOf(-1, 0), intArrayOf(0, -1))

class Position(val mode: Int = POCKET_NONE) {
    val sq = IntArray(64)
    var turn = WHITE
    var castle = 15              // 1 = white short, 2 = white long, 4 = black short, 8 = black long
    var ep = -1                  // square a pawn may capture onto en passant
    var half = 0
    var full = 1
    var promoted = 0L            // squares holding a piece that started as a pawn (Crazyhouse)
    val pocket = arrayOf(IntArray(6), IntArray(6))   // pocket[color][pieceType] = droppable count

    fun copy(): Position {
        val n = Position(mode)
        System.arraycopy(sq, 0, n.sq, 0, 64)
        n.turn = turn; n.castle = castle; n.ep = ep; n.half = half; n.full = full; n.promoted = promoted
        for (c in 0..1) System.arraycopy(pocket[c], 0, n.pocket[c], 0, 6)
        return n
    }

    fun kingSq(color: Int): Int {
        val k = mkPiece(KING, color)
        for (i in 0 until 64) if (sq[i] == k) return i
        return -1
    }

    fun attacked(s: Int, by: Int): Boolean {
        val f = s and 7
        val r = s shr 3
        val pr = if (by == WHITE) r - 1 else r + 1
        if (pr in 0..7) {
            val pawn = mkPiece(PAWN, by)
            if (f > 0 && sq[pr * 8 + f - 1] == pawn) return true
            if (f < 7 && sq[pr * 8 + f + 1] == pawn) return true
        }
        val kn = mkPiece(KNIGHT, by)
        for (o in KN) {
            val nf = f + o[0]; val nr = r + o[1]
            if (nf in 0..7 && nr in 0..7 && sq[nr * 8 + nf] == kn) return true
        }
        val kg = mkPiece(KING, by)
        for (o in KG) {
            val nf = f + o[0]; val nr = r + o[1]
            if (nf in 0..7 && nr in 0..7 && sq[nr * 8 + nf] == kg) return true
        }
        val bq1 = mkPiece(BISHOP, by); val bq2 = mkPiece(QUEEN, by)
        for (o in DIAG) {
            var nf = f + o[0]; var nr = r + o[1]
            while (nf in 0..7 && nr in 0..7) {
                val p = sq[nr * 8 + nf]
                if (p != 0) { if (p == bq1 || p == bq2) return true; break }
                nf += o[0]; nr += o[1]
            }
        }
        val rq1 = mkPiece(ROOK, by)
        for (o in ORTH) {
            var nf = f + o[0]; var nr = r + o[1]
            while (nf in 0..7 && nr in 0..7) {
                val p = sq[nr * 8 + nf]
                if (p != 0) { if (p == rq1 || p == bq2) return true; break }
                nf += o[0]; nr += o[1]
            }
        }
        return false
    }

    fun inCheck(color: Int): Boolean {
        val k = kingSq(color)
        return k >= 0 && attacked(k, 1 - color)
    }

    private fun addPawn(out: ArrayList<Move>, from: Int, to: Int, promo: Boolean) {
        if (promo) for (t in intArrayOf(QUEEN, ROOK, BISHOP, KNIGHT)) out.add(Move(from, to, t))
        else out.add(Move(from, to))
    }

    private fun pseudo(): ArrayList<Move> {
        val out = ArrayList<Move>(64)
        val me = turn
        for (s in 0 until 64) {
            val p = sq[s]
            if (p == 0 || colorOf(p) != me) continue
            val f = s and 7
            val r = s shr 3
            when (typeOf(p)) {
                PAWN -> {
                    val dir = if (me == WHITE) 1 else -1
                    val startR = if (me == WHITE) 1 else 6
                    val promR = if (me == WHITE) 7 else 0
                    val nr = r + dir
                    if (nr in 0..7) {
                        val t = nr * 8 + f
                        if (sq[t] == 0) {
                            addPawn(out, s, t, nr == promR)
                            if (r == startR && sq[(nr + dir) * 8 + f] == 0) out.add(Move(s, (nr + dir) * 8 + f))
                        }
                        for (df in intArrayOf(-1, 1)) {
                            val nf = f + df
                            if (nf !in 0..7) continue
                            val t2 = nr * 8 + nf
                            if (sq[t2] != 0 && colorOf(sq[t2]) != me) addPawn(out, s, t2, nr == promR)
                            else if (t2 == ep && sq[t2] == 0) out.add(Move(s, t2))
                        }
                    }
                }
                KNIGHT -> for (o in KN) step(out, s, f + o[0], r + o[1], me)
                KING -> for (o in KG) step(out, s, f + o[0], r + o[1], me)
                BISHOP -> slide(out, s, DIAG, me)
                ROOK -> slide(out, s, ORTH, me)
                QUEEN -> { slide(out, s, DIAG, me); slide(out, s, ORTH, me) }
            }
        }
        castling(out)
        if (mode != POCKET_NONE) drops(out)
        return out
    }

    private fun step(out: ArrayList<Move>, from: Int, nf: Int, nr: Int, me: Int) {
        if (nf !in 0..7 || nr !in 0..7) return
        val t = nr * 8 + nf
        val q = sq[t]
        if (q == 0 || colorOf(q) != me) out.add(Move(from, t))
    }

    private fun slide(out: ArrayList<Move>, from: Int, dirs: Array<IntArray>, me: Int) {
        val f = from and 7
        val r = from shr 3
        for (o in dirs) {
            var nf = f + o[0]; var nr = r + o[1]
            while (nf in 0..7 && nr in 0..7) {
                val t = nr * 8 + nf
                val q = sq[t]
                if (q == 0) out.add(Move(from, t))
                else { if (colorOf(q) != me) out.add(Move(from, t)); break }
                nf += o[0]; nr += o[1]
            }
        }
    }

    private fun castling(out: ArrayList<Move>) {
        if (turn == WHITE) {
            if (sq[4] != mkPiece(KING, WHITE)) return
            if (castle and 1 != 0 && sq[7] == mkPiece(ROOK, WHITE) && sq[5] == 0 && sq[6] == 0 &&
                !attacked(4, BLACK) && !attacked(5, BLACK)) out.add(Move(4, 6))
            if (castle and 2 != 0 && sq[0] == mkPiece(ROOK, WHITE) && sq[1] == 0 && sq[2] == 0 && sq[3] == 0 &&
                !attacked(4, BLACK) && !attacked(3, BLACK)) out.add(Move(4, 2))
        } else {
            if (sq[60] != mkPiece(KING, BLACK)) return
            if (castle and 4 != 0 && sq[63] == mkPiece(ROOK, BLACK) && sq[61] == 0 && sq[62] == 0 &&
                !attacked(60, WHITE) && !attacked(61, WHITE)) out.add(Move(60, 62))
            if (castle and 8 != 0 && sq[56] == mkPiece(ROOK, BLACK) && sq[57] == 0 && sq[58] == 0 && sq[59] == 0 &&
                !attacked(60, WHITE) && !attacked(59, WHITE)) out.add(Move(60, 58))
        }
    }

    private fun drops(out: ArrayList<Move>) {
        val pk = pocket[turn]
        for (t in PAWN..QUEEN) {
            if (pk[t] <= 0) continue
            for (s in 0 until 64) {
                if (sq[s] != 0) continue
                if (t == PAWN) { val rk = s shr 3; if (rk == 0 || rk == 7) continue }
                out.add(Move(-1, s, 0, t))
            }
        }
    }

    fun play(m: Move): Played {
        val n = copy()
        val c = turn
        var cap = 0
        if (m.drop > 0) {
            n.sq[m.to] = mkPiece(m.drop, c)
            n.pocket[c][m.drop]--
            n.ep = -1
            n.half = 0
        } else {
            val p = sq[m.from]
            val t = typeOf(p)
            val target = sq[m.to]
            if (target != 0) {
                cap = typeOf(target)
                if ((promoted ushr m.to) and 1L == 1L) cap = PAWN
            } else if (t == PAWN && (m.from and 7) != (m.to and 7)) {
                // en passant
                val capSq = (m.from shr 3) * 8 + (m.to and 7)
                cap = PAWN
                n.sq[capSq] = 0
                n.promoted = n.promoted and (1L shl capSq).inv()
            }
            val wasPromoted = (promoted ushr m.from) and 1L == 1L
            n.promoted = n.promoted and (1L shl m.from).inv() and (1L shl m.to).inv()
            n.sq[m.from] = 0
            n.sq[m.to] = if (m.promo > 0) mkPiece(m.promo, c) else p
            if (m.promo > 0 || wasPromoted) n.promoted = n.promoted or (1L shl m.to)
            if (t == KING && abs((m.to and 7) - (m.from and 7)) == 2) {
                val rank = m.from shr 3
                if ((m.to and 7) == 6) { n.sq[rank * 8 + 7] = 0; n.sq[rank * 8 + 5] = mkPiece(ROOK, c) }
                else { n.sq[rank * 8] = 0; n.sq[rank * 8 + 3] = mkPiece(ROOK, c) }
            }
            // castling rights
            if (t == KING) n.castle = n.castle and (if (c == WHITE) 3 else 12).inv()
            for ((corner, bit) in arrayOf(0 to 2, 7 to 1, 56 to 8, 63 to 4)) {
                if (m.from == corner || m.to == corner) n.castle = n.castle and bit.inv()
            }
            n.ep = if (t == PAWN && abs((m.to shr 3) - (m.from shr 3)) == 2) (m.from + m.to) / 2 else -1
            n.half = if (t == PAWN || cap > 0) 0 else half + 1
            if (cap > 0 && mode == POCKET_OWN) n.pocket[c][cap]++
        }
        if (c == BLACK) n.full = full + 1
        n.turn = 1 - c
        return Played(n, cap)
    }

    fun legalMoves(): List<Move> {
        val me = turn
        val res = ArrayList<Move>(48)
        for (m in pseudo()) {
            val n = play(m).pos
            val k = n.kingSq(me)
            if (k < 0 || !n.attacked(k, 1 - me)) res.add(m)
        }
        return res
    }

    /** Standard algebraic notation for [m]; [legal] must be this position's legal moves. Check marks are added by the caller. */
    fun san(m: Move, legal: List<Move>): String {
        if (m.drop > 0) return (if (m.drop == PAWN) "" else LETTERS[m.drop].toString()) + "@" + sqName(m.to)
        val p = sq[m.from]
        val t = typeOf(p)
        if (t == KING && abs((m.to and 7) - (m.from and 7)) == 2) return if ((m.to and 7) == 6) "O-O" else "O-O-O"
        val cap = sq[m.to] != 0 || (t == PAWN && (m.from and 7) != (m.to and 7))
        val sb = StringBuilder()
        if (t == PAWN) {
            if (cap) sb.append('a' + (m.from and 7)).append('x')
        } else {
            sb.append(LETTERS[t])
            val others = legal.filter { it.drop == 0 && it.to == m.to && it.from != m.from && typeOf(sq[it.from]) == t }
            if (others.isNotEmpty()) {
                val sameFile = others.any { (it.from and 7) == (m.from and 7) }
                val sameRank = others.any { (it.from shr 3) == (m.from shr 3) }
                if (!sameFile) sb.append('a' + (m.from and 7))
                else if (!sameRank) sb.append('1' + (m.from shr 3))
                else sb.append(sqName(m.from))
            }
            if (cap) sb.append('x')
        }
        sb.append(sqName(m.to))
        if (m.promo > 0) sb.append('=').append(LETTERS[m.promo])
        return sb.toString()
    }

    /** King and minor-piece endings where nobody can ever mate. Never true with drops. */
    fun insufficient(): Boolean {
        if (mode != POCKET_NONE) return false
        var knights = 0
        val bishopColors = HashSet<Int>()
        var bishops = 0
        for (s in 0 until 64) {
            when (typeOf(sq[s])) {
                PAWN, ROOK, QUEEN -> return false
                KNIGHT -> knights++
                BISHOP -> { bishops++; bishopColors.add(((s shr 3) + (s and 7)) and 1) }
            }
        }
        if (knights + bishops <= 1) return true
        return knights == 0 && bishopColors.size == 1
    }

    /** Position identity for repetition: pieces, side, rights, a usable en-passant square and pockets. */
    fun key(): String {
        val sb = StringBuilder(80)
        for (i in 0 until 64) sb.append(PIECE_CHARS[sq[i]])
        sb.append(turn).append(castle)
        var epk = -1
        if (ep >= 0) {
            val me = turn
            val dir = if (me == WHITE) -1 else 1
            val f = ep and 7
            val r = (ep shr 3) + dir
            val pawn = mkPiece(PAWN, me)
            if (r in 0..7 && ((f > 0 && sq[r * 8 + f - 1] == pawn) || (f < 7 && sq[r * 8 + f + 1] == pawn))) epk = ep
        }
        sb.append(epk)
        if (mode != POCKET_NONE) for (c in 0..1) for (t in PAWN..QUEEN) sb.append(pocket[c][t]).append(',')
        return sb.toString()
    }

    fun fen(): String {
        val sb = StringBuilder()
        for (r in 7 downTo 0) {
            var empty = 0
            for (f in 0..7) {
                val s = r * 8 + f
                val p = sq[s]
                if (p == 0) { empty++; continue }
                if (empty > 0) { sb.append(empty); empty = 0 }
                sb.append(PIECE_CHARS[p])
                if ((promoted ushr s) and 1L == 1L) sb.append('~')
            }
            if (empty > 0) sb.append(empty)
            if (r > 0) sb.append('/')
        }
        sb.append(' ').append(if (turn == WHITE) 'w' else 'b').append(' ')
        val cs = StringBuilder()
        if (castle and 1 != 0) cs.append('K'); if (castle and 2 != 0) cs.append('Q')
        if (castle and 4 != 0) cs.append('k'); if (castle and 8 != 0) cs.append('q')
        sb.append(if (cs.isEmpty()) "-" else cs.toString()).append(' ')
        sb.append(if (ep >= 0) sqName(ep) else "-").append(' ').append(half).append(' ').append(full).append(' ')
        val pk = StringBuilder()
        for (c in 0..1) for (t in PAWN..QUEEN) repeat(pocket[c][t]) {
            val ch = LETTERS[t]
            pk.append(if (c == WHITE) ch else ch.lowercaseChar())
        }
        sb.append(if (pk.isEmpty()) "-" else pk.toString())
        return sb.toString()
    }

    companion object {
        const val PIECE_CHARS = ".PNBRQK..pnbrqk"

        fun initial(mode: Int): Position {
            val p = Position(mode)
            val back = intArrayOf(ROOK, KNIGHT, BISHOP, QUEEN, KING, BISHOP, KNIGHT, ROOK)
            for (f in 0..7) {
                p.sq[f] = mkPiece(back[f], WHITE)
                p.sq[8 + f] = mkPiece(PAWN, WHITE)
                p.sq[48 + f] = mkPiece(PAWN, BLACK)
                p.sq[56 + f] = mkPiece(back[f], BLACK)
            }
            return p
        }

        fun fromFen(fen: String, mode: Int): Position? {
            try {
                val parts = fen.trim().split(' ')
                if (parts.size < 7) return null
                val p = Position(mode)
                val rows = parts[0].split('/')
                if (rows.size != 8) return null
                for (i in 0..7) {
                    val r = 7 - i
                    var f = 0
                    for (ch in rows[i]) {
                        when {
                            ch.isDigit() -> f += ch - '0'
                            ch == '~' -> p.promoted = p.promoted or (1L shl (r * 8 + f - 1))
                            else -> {
                                val idx = PIECE_CHARS.indexOf(ch)
                                if (idx <= 0 || f > 7) return null
                                p.sq[r * 8 + f] = idx
                                f++
                            }
                        }
                    }
                    if (f != 8) return null
                }
                p.turn = if (parts[1] == "b") BLACK else WHITE
                var c = 0
                for (ch in parts[2]) c = c or when (ch) { 'K' -> 1; 'Q' -> 2; 'k' -> 4; 'q' -> 8; else -> 0 }
                p.castle = c
                p.ep = if (parts[3] == "-") -1 else sqFromName(parts[3])
                p.half = parts[4].toIntOrNull() ?: 0
                p.full = parts[5].toIntOrNull() ?: 1
                if (parts[6] != "-") for (ch in parts[6]) {
                    val t = "PNBRQ".indexOf(ch.uppercaseChar()) + 1
                    if (t > 0) p.pocket[if (ch.isUpperCase()) WHITE else BLACK][t]++
                }
                return p
            } catch (e: Exception) {
                return null
            }
        }
    }
}
