package app.waveroom.chess

import app.waveroom.core.arr
import app.waveroom.core.int
import app.waveroom.core.lng
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.objects
import app.waveroom.core.str
import app.waveroom.core.strings
import app.waveroom.core.jsonArrayOf
import org.json.JSONArray
import org.json.JSONObject

/** Chat messages starting with this carry a chess challenge card: MARK + gameId + US + text. */
const val CHESS_MARK = "\u001Fchess:"

/* ----------------------------------------------------------------------------
 * Modes and presets (data, so more can be added without touching the UI)
 * -------------------------------------------------------------------------- */

data class ChessMode(val id: String, val label: String, val variant: Boolean, val about: String, val presets: List<Pair<Int, Int>>)

object ChessModes {
    val all = listOf(
        ChessMode(
            "standard", "Standard", false,
            "Normal chess rules with no special variant mechanics. The objective is checkmate, with standard draw conditions.",
            listOf(15 to 10, 30 to 0, 30 to 20, 60 to 0)
        ),
        ChessMode(
            "rapid", "Rapid", false,
            "Normal chess played with a longer time control than Blitz or Bullet.",
            listOf(10 to 0, 10 to 5, 15 to 10, 30 to 0)
        ),
        ChessMode(
            "blitz", "Blitz", false,
            "Normal chess played at a fast time control.",
            listOf(3 to 0, 3 to 2, 5 to 0, 5 to 3)
        ),
        ChessMode(
            "bullet", "Bullet", false,
            "Extremely fast chess intended for very short games.",
            listOf(1 to 0, 1 to 1, 2 to 0)
        ),
        ChessMode(
            "crazyhouse", "Crazyhouse", true,
            "Normal chess plus piece drops. A captured piece goes to your reserve, changes colour, and can later be dropped on any empty square instead of making a normal move. Pawns cannot be dropped on the first or eighth rank.",
            listOf(3 to 0, 3 to 2, 5 to 0, 5 to 3)
        ),
        ChessMode(
            "bughouse", "Bughouse", true,
            "Four players, two boards, two teams of two. Pieces you capture go to your teammate's reserve, and your teammate can drop them on their board. The game depends on both boards and on talking to your partner.",
            listOf(1 to 0, 2 to 0, 3 to 0, 3 to 2)
        )
    )

    fun get(id: String): ChessMode = all.firstOrNull { it.id == id } ?: all[0]
    fun valid(id: String) = all.any { it.id == id }
}

/* ----------------------------------------------------------------------------
 * Game state. Replicated to everyone as JSON; the acting host is the only writer.
 * -------------------------------------------------------------------------- */

class ChessSeat(val uid: String, val name: String)

class ChessBoard(var pos: Position) {
    val moves = ArrayList<String>()   // UCI, drops as "N@e4"
    val sans = ArrayList<String>()
    var lastFrom = -1
    var lastTo = -1
    var check = false
}

class ChessGame(val id: String, val mode: String, val baseSec: Int, val incSec: Int, val creatorUid: String, val createdAt: Long) {
    val bughouse = mode == "bughouse"
    val n = if (bughouse) 4 else 2
    val pocketMode = when (mode) { "crazyhouse" -> POCKET_OWN; "bughouse" -> POCKET_EXTERNAL; else -> POCKET_NONE }
    val seats = arrayOfNulls<ChessSeat>(n)
    val boards: List<ChessBoard> = List(if (bughouse) 2 else 1) { ChessBoard(Position.initial(pocketMode)) }
    val clocks = LongArray(n)
    /** Local time at which `clocks` was true for each board's running side. */
    val stamp = LongArray(2)
    var status = "waiting"       // waiting | live | over | cancelled | expired
    var winner = -1              // two players: 0 white, 1 black. Bughouse: winning team. 2 = draw
    var reason = ""
    var drawBy = -1              // seat that offered a draw
    val rematch = LinkedHashSet<String>()
    var startedAt = 0L
    var endedAt = 0L
    var next = ""                // id of the rematch
    /** Host-only: last time anything happened, for aborting games nobody starts. */
    var activityAt = -1L

    val label: String get() = ChessModes.get(mode).label
    val timeLabel: String get() = "${fmtBase(baseSec)}+$incSec"
    val live get() = status == "live"
    val over get() = status == "over"
    val finished get() = status == "over" || status == "cancelled" || status == "expired"

    fun seatOf(uid: String): Int = seats.indexOfFirst { it?.uid == uid }
    fun filled(): Boolean = seats.all { it != null }
    fun count(): Int = seats.count { it != null }
    fun boardOf(seat: Int) = seat / 2
    fun colorOf(seat: Int) = seat % 2
    fun partner(seat: Int) = when (seat) { 0 -> 3; 1 -> 2; 2 -> 1; else -> 0 }
    fun teamOf(seat: Int) = if (!bughouse) seat else if (seat == 0 || seat == 3) 0 else 1
    fun seatFor(board: Int, color: Int) = board * 2 + color

    fun running(seat: Int): Boolean {
        if (!live) return false
        val b = boards[boardOf(seat)]
        return b.moves.size >= 2 && b.pos.turn == colorOf(seat)
    }

    fun remaining(seat: Int, now: Long): Long {
        var r = clocks[seat]
        if (running(seat)) r -= now - stamp[boardOf(seat)]
        return maxOf(0L, r)
    }

    /** Move the game from "waiting" to "live". Two-player games randomise colours. */
    fun begin(now: Long, randomise: Boolean) {
        if (!bughouse && randomise && Math.random() < 0.5) {
            val t = seats[0]; seats[0] = seats[1]; seats[1] = t
        }
        status = "live"
        startedAt = now
        activityAt = now
        for (i in 0 until n) clocks[i] = baseSec * 1000L
        for (i in stamp.indices) stamp[i] = now
        drawBy = -1
    }

    private fun settle(now: Long) {
        for (s in 0 until n) if (running(s)) clocks[s] = maxOf(0L, clocks[s] - (now - stamp[boardOf(s)]))
    }

    fun finish(winner: Int, reason: String, now: Long) {
        settle(now)
        status = "over"
        this.winner = winner
        this.reason = reason
        endedAt = now
        drawBy = -1
    }

    fun cancel(now: Long, expired: Boolean) {
        status = if (expired) "expired" else "cancelled"
        endedAt = now
    }

    fun resign(seat: Int, now: Long) {
        val w = if (bughouse) 1 - teamOf(seat) else 1 - colorOf(seat)
        finish(w, "Resignation", now)
    }

    private fun loserWins(seat: Int) = if (bughouse) 1 - teamOf(seat) else 1 - colorOf(seat)

    /** Called by the host a few times a second. Returns true when the game changed. */
    fun tick(now: Long): Boolean {
        if (status == "waiting") {
            if (now - createdAt > 10 * 60_000L) { cancel(now, true); return true }
            return false
        }
        if (!live) return false
        if (activityAt < 0L) activityAt = now
        // nobody has moved on a board for a minute: call it off
        if (boards.any { it.moves.size < 2 } && now - activityAt > 60_000L) {
            finish(2, "No moves were played", now)
            return true
        }
        for (s in 0 until n) {
            if (running(s) && remaining(s, now) <= 0L) {
                clocks[s] = 0
                flag(s, now)
                return true
            }
        }
        return false
    }

    private fun flag(seat: Int, now: Long) {
        val b = boards[boardOf(seat)]
        // a flag against a side that cannot be mated is only a draw
        if (!bughouse && pocketMode == POCKET_NONE && b.pos.insufficient()) finish(2, "Timeout vs insufficient material", now)
        else finish(loserWins(seat), "Timeout", now)
    }

    /**
     * Play [uci] for [seat]. [ply] is the number of moves the sender saw on that board; it guards against
     * duplicate and stale submissions. Returns an error message, or null when the game state changed.
     */
    fun applyMove(seat: Int, uci: String, ply: Int, now: Long): String? {
        if (!live) return "This game is not running."
        if (seat !in 0 until n) return "You are not playing."
        val b = boards[boardOf(seat)]
        if (b.pos.turn != colorOf(seat)) return "It is not your turn."
        if (ply != b.moves.size) return "The board changed — try again."
        val mv = Move.parse(uci) ?: return "Illegal move."
        val legal = b.pos.legalMoves()
        if (mv !in legal) return "Illegal move."
        val bi = boardOf(seat)
        val clockRuns = b.moves.size >= 2
        if (clockRuns) {
            clocks[seat] -= now - stamp[bi]
            if (clocks[seat] <= 0L) {
                clocks[seat] = 0
                flag(seat, now)
                return null
            }
        }
        var san = b.pos.san(mv, legal)
        val played = b.pos.play(mv)
        b.pos = played.pos
        b.moves.add(mv.uci())
        b.lastFrom = mv.from
        b.lastTo = mv.to
        if (bughouse && played.captured > 0) {
            val p = partner(seat)
            val pb = boards[boardOf(p)]
            pb.pos.pocket[colorOf(p)][played.captured]++
        }
        if (clockRuns) clocks[seat] += incSec * 1000L
        stamp[bi] = now
        activityAt = now
        drawBy = -1
        val pos = b.pos
        val check = pos.inCheck(pos.turn)
        b.check = check
        val replies = pos.legalMoves()
        san += if (replies.isEmpty() && check) "#" else if (check) "+" else ""
        b.sans.add(san)

        val mover = seat
        when {
            replies.isEmpty() && check -> finish(if (bughouse) teamOf(mover) else colorOf(mover), "Checkmate", now)
            replies.isEmpty() && !bughouse -> finish(2, "Stalemate", now)
            !bughouse && pos.insufficient() -> finish(2, "Insufficient material", now)
            !bughouse && pos.half >= 100 -> finish(2, "Fifty-move rule", now)
            !bughouse && repetitions(bi) >= 3 -> finish(2, "Threefold repetition", now)
        }
        return null
    }

    /** How many times the current position of [board] has occurred (replays the move list). */
    fun repetitions(board: Int): Int {
        val b = boards[board]
        if (bughouse) return 0
        var p = Position.initial(pocketMode)
        val target = b.pos.key()
        var count = if (p.key() == target) 1 else 0
        for (u in b.moves) {
            val m = Move.parse(u) ?: break
            p = p.play(m).pos
            if (p.key() == target) count++
        }
        return count
    }

    /** Draw offers: two-player games only. Returns an error or null. */
    fun drawOffer(seat: Int): String? {
        if (!live) return "This game is not running."
        if (bughouse) return "Draws are not offered in Bughouse."
        if (drawBy == seat) return null
        if (drawBy >= 0) return "A draw is already on offer."
        drawBy = seat
        return null
    }

    fun winnerLine(): String = when {
        winner == 2 -> "DRAW"
        bughouse -> "TEAM ${winner + 1} WON"
        else -> (seats[winner]?.name ?: if (winner == 0) "WHITE" else "BLACK").uppercase() + " WON"
    }

    /* ---------------- notation ---------------- */

    fun pgn(): String {
        val sb = StringBuilder()
        val date = java.text.SimpleDateFormat("yyyy.MM.dd", java.util.Locale.US).format(java.util.Date(if (startedAt > 0) startedAt else createdAt))
        val res = when (winner) { -1 -> "*"; 2 -> "1/2-1/2"; 0 -> "1-0"; else -> "0-1" }
        if (!bughouse) {
            sb.append("[Event \"HoMit $label\"]\n[Site \"HoMit voice room\"]\n[Date \"$date\"]\n")
            sb.append("[White \"${seats[0]?.name ?: "?"}\"]\n[Black \"${seats[1]?.name ?: "?"}\"]\n")
            sb.append("[Result \"$res\"]\n[TimeControl \"$baseSec+$incSec\"]\n")
            if (mode == "crazyhouse") sb.append("[Variant \"Crazyhouse\"]\n")
            if (over) sb.append("[Termination \"$reason\"]\n")
            sb.append('\n').append(moveText(0))
            if (res != "*") sb.append(' ').append(res)
        } else {
            sb.append("HoMit Bughouse $timeLabel · $date\n")
            val team = { t: Int -> "Team ${t + 1}: " + (0 until 4).filter { teamOf(it) == t }.joinToString(" & ") { seats[it]?.name ?: "?" } }
            sb.append(team(0)).append('\n').append(team(1)).append('\n')
            if (over) sb.append("Result: ${if (winner == 2) "draw" else "Team ${winner + 1} won"} — $reason\n")
            sb.append("\nBoard A (${seats[0]?.name ?: "?"} / ${seats[1]?.name ?: "?"}):\n").append(moveText(0))
            sb.append("\n\nBoard B (${seats[2]?.name ?: "?"} / ${seats[3]?.name ?: "?"}):\n").append(moveText(1))
        }
        return sb.toString()
    }

    fun moveText(board: Int): String {
        val b = boards[board]
        val sb = StringBuilder()
        for (i in b.sans.indices) {
            if (i % 2 == 0) { if (i > 0) sb.append(' '); sb.append(i / 2 + 1).append(". ") } else sb.append(' ')
            sb.append(b.sans[i])
        }
        return sb.toString()
    }

    /* ---------------- wire format ---------------- */

    fun toJson(): JSONObject {
        val ss = JSONArray()
        for (s in seats) ss.put(if (s == null) JSONObject() else JSONObject().put("uid", s.uid).put("name", s.name))
        val bs = JSONArray()
        for (b in boards) {
            bs.put(
                JSONObject().put("fen", b.pos.fen()).put("mv", jsonArrayOf(b.moves)).put("sn", jsonArrayOf(b.sans))
                    .put("lf", b.lastFrom).put("lt", b.lastTo).put("ck", b.check)
            )
        }
        val ck = JSONArray()
        val t = nowMs()
        for (i in 0 until n) ck.put(remaining(i, t))
        return JSONObject()
            .put("id", id).put("mode", mode).put("base", baseSec).put("inc", incSec).put("creator", creatorUid)
            .put("at", createdAt).put("status", status).put("seats", ss).put("boards", bs).put("clocks", ck)
            .put("winner", winner).put("reason", reason).put("draw", drawBy).put("rematch", jsonArrayOf(rematch))
            .put("start", startedAt).put("end", endedAt).put("next", next)
    }

    companion object {
        fun from(j: JSONObject, localNow: Long = nowMs()): ChessGame? {
            try {
                val mode = j.str("mode")
                if (!ChessModes.valid(mode)) return null
                val g = ChessGame(j.str("id"), mode, j.int("base"), j.int("inc"), j.str("creator"), j.lng("at"))
                g.status = j.str("status", "waiting")
                j.arr("seats")?.objects()?.forEachIndexed { i, s ->
                    if (i < g.n && s.str("uid").isNotEmpty()) g.seats[i] = ChessSeat(s.str("uid"), s.str("name"))
                }
                j.arr("boards")?.objects()?.forEachIndexed { i, bj ->
                    if (i < g.boards.size) {
                        val b = g.boards[i]
                        Position.fromFen(bj.str("fen"), g.pocketMode)?.let { b.pos = it }
                        bj.arr("mv")?.strings()?.let { b.moves.addAll(it) }
                        bj.arr("sn")?.strings()?.let { b.sans.addAll(it) }
                        b.lastFrom = bj.int("lf", -1); b.lastTo = bj.int("lt", -1)
                        b.check = bj.optBoolean("ck", false)
                    }
                }
                j.arr("clocks")?.let { a -> for (i in 0 until minOf(a.length(), g.n)) g.clocks[i] = a.optLong(i) }
                g.winner = j.int("winner", -1)
                g.reason = j.str("reason")
                g.drawBy = j.int("draw", -1)
                j.arr("rematch")?.strings()?.let { g.rematch.addAll(it) }
                g.startedAt = j.lng("start"); g.endedAt = j.lng("end"); g.next = j.str("next")
                for (i in g.stamp.indices) g.stamp[i] = localNow
                return g
            } catch (e: Exception) {
                return null
            }
        }

        fun fmtBase(sec: Int): String = if (sec % 60 == 0) "${sec / 60}" else if (sec < 60) "${sec}s" else String.format("%.1f", sec / 60.0)
    }
}

/** The room's chess games. Newest last; only the newest can be active. */
class ChessState {
    val games = LinkedHashMap<String, ChessGame>()

    val current: ChessGame? get() = games.values.lastOrNull()
    val active: ChessGame? get() = current?.takeIf { it.status == "waiting" || it.status == "live" }

    fun put(g: ChessGame) {
        games[g.id] = g
        while (games.size > 6) games.remove(games.keys.first())
    }

    fun toJson(): JSONArray {
        val a = JSONArray()
        for (g in games.values) a.put(g.toJson())
        return a
    }

    fun load(a: JSONArray?) {
        games.clear()
        a?.objects()?.forEach { ChessGame.from(it)?.let { g -> put(g) } }
    }

    fun clear() = games.clear()
}
