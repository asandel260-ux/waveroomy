package app.waveroom.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.chess.BLACK
import app.waveroom.chess.ChessGame
import app.waveroom.chess.ChessModes
import app.waveroom.chess.Move
import app.waveroom.chess.PAWN
import app.waveroom.chess.POCKET_NONE
import app.waveroom.chess.Position
import app.waveroom.chess.QUEEN
import app.waveroom.chess.WHITE
import app.waveroom.chess.colorOf
import app.waveroom.chess.typeOf
import app.waveroom.core.nowMs
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.EmptyState
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Row2
import app.waveroom.ui.components.SwitchRow
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrChip
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.theme.WR
import kotlinx.coroutines.delay

/* ----------------------------------------------------------------------------
 * Small helpers
 * -------------------------------------------------------------------------- */

private class Pending(val board: Int, val move: Move, val pos: Position)

/** State that belongs to this device's view of the game, not to the game itself. */
private class ChessUi {
    var flip by mutableStateOf(false)
    var view by mutableIntStateOf(-1)          // -1 = live, otherwise number of plies shown
    var dropSel by mutableIntStateOf(0)
    var pending by mutableStateOf<Pending?>(null)
    var showResult by mutableStateOf(true)
    var mySeat = -1
}

private fun fmtMs(ms: Long): String {
    if (ms < 10_000) return "0:%02d.%d".format(ms / 1000, (ms % 1000) / 100)
    val t = ms / 1000
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun copyText(text: String) {
    try {
        val cm = AppCore.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Chess notation", text))
        AppCore.toast("Copied")
    } catch (e: Exception) {
        AppCore.toast("Could not copy")
    }
}

private val START_COUNT = intArrayOf(0, 8, 2, 2, 2, 1)
private val VALUE = intArrayOf(0, 1, 3, 3, 5, 9)

/** Pieces of [color] that are no longer on the board (so the other side captured them). */
private fun missing(pos: Position, color: Int): List<Int> {
    val have = IntArray(6)
    for (s in 0 until 64) { val p = pos.sq[s]; if (p != 0 && colorOf(p) == color && typeOf(p) in 1..5) have[typeOf(p)]++ }
    val out = ArrayList<Int>()
    for (t in QUEEN downTo PAWN) repeat(maxOf(0, START_COUNT[t] - have[t])) { out.add(t) }
    return out
}

private fun historic(g: ChessGame, plies: Int): Triple<Position, Int, Int> {
    var p = Position.initial(g.pocketMode)
    var lf = -1
    var lt = -1
    for (i in 0 until plies) {
        val m = Move.parse(g.boards[0].moves.getOrNull(i) ?: break) ?: break
        p = p.play(m).pos
        lf = m.from; lt = m.to
    }
    return Triple(p, lf, lt)
}

private fun reasonLine(r: String) = if (r.startsWith("No ") || r.startsWith("Timeout vs")) r else "by $r"

/* ----------------------------------------------------------------------------
 * Create-game sheet
 * -------------------------------------------------------------------------- */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChessCreateSheet(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val session = AppCore.session
    var mode by remember { mutableStateOf("blitz") }
    var preset by remember { mutableIntStateOf(3) }
    var about by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val m = ChessModes.get(mode)
    val p = m.presets[preset.coerceIn(0, m.presets.lastIndex)]

    WrSheet(onDismiss, "New chess game") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Tiny("Time controls")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (x in ChessModes.all.filter { !it.variant }) WrChip(x.label, mode == x.id, { mode = x.id; preset = minOf(preset, x.presets.lastIndex) })
            }
            Spacer(Modifier.height(14.dp))
            Tiny("Variants")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (x in ChessModes.all.filter { it.variant }) WrChip(x.label + if (x.id == "bughouse") " · 4 players" else "", mode == x.id, { mode = x.id; preset = minOf(preset, x.presets.lastIndex) })
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { about = !about }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                WrIcon("info", 16.dp, WR.faint)
                Spacer(Modifier.width(8.dp))
                Text("About ${m.label}", color = WR.muted, fontSize = 13.sp, modifier = Modifier.weight(1f))
                WrIcon(if (about) "chevron-up" else "chevron-down", 16.dp, WR.faint)
            }
            if (about) {
                Muted(m.about, size = 13)
                Spacer(Modifier.height(4.dp))
            }
            Spacer(Modifier.height(10.dp))
            Tiny("Base time + increment, in minutes and seconds")
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                m.presets.forEachIndexed { i, (b, inc) -> WrChip("$b+$inc", preset == i, { preset = i }) }
            }
            Spacer(Modifier.height(8.dp))
            Muted("Each player receives the selected base time and gains the increment after every move.", size = 12)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${m.label} · ${p.first}+${p.second}", color = WR.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Muted(if (mode == "bughouse") "4 players · 2 teams" else "2 players", size = 12)
                }
            }
            Spacer(Modifier.height(12.dp))
            WrButton(if (busy) "Creating…" else "CREATE GAME", {
                busy = true
                session.chessCreate(mode, p.first * 60, p.second) { ok ->
                    busy = false
                    if (ok) { onDismiss(); onCreated() }
                }
            }, Modifier.fillMaxWidth(), enabled = !busy)
        }
    }
}

/* ----------------------------------------------------------------------------
 * Challenge card (shown in room chat)
 * -------------------------------------------------------------------------- */

@Composable
fun ChessChallengeCard(gameId: String, text: String, onOpen: () -> Unit) {
    val session = AppCore.session
    val s = session.room
    val g = s?.chess?.games?.get(gameId)
    val me = session.myUid
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WR.surface2).border(1.dp, WR.border, RoundedCornerShape(10.dp)).padding(12.dp)
    ) {
        Text(text, color = WR.text, fontSize = 13.sp)
        Spacer(Modifier.height(10.dp))
        if (g == null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WrIcon("chess", 18.dp, WR.faint)
                Spacer(Modifier.width(8.dp))
                Muted("Finished", size = 13)
            }
            return
        }
        val seated = g.seatOf(me) >= 0
        val state = when (g.status) {
            "waiting" -> if (g.filled()) "Full" else "Waiting"
            "live" -> "Started"
            "over" -> "Finished"
            "cancelled" -> "Cancelled"
            else -> "Expired"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(8.dp)).background(WR.surface3), contentAlignment = Alignment.Center) { WrIcon("chess", 20.dp, WR.accentBright) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(g.seats[0]?.name ?: "—", color = WR.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${g.label} · ${g.timeLabel}", color = WR.muted, fontSize = 12.sp)
                Text(if (g.bughouse) "${g.count()} / 4 Players" else "2 Players", color = WR.faint, fontSize = 12.sp)
            }
            Spacer(Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
                val tone = when (state) { "Waiting" -> WR.ok; "Full", "Started" -> WR.accentBright; else -> WR.faint }
                Text(state.uppercase(), color = tone, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Spacer(Modifier.height(6.dp))
                when {
                    g.status == "waiting" && !seated && !g.filled() ->
                        WrButton("JOIN GAME", {
                            session.chessAct("chess:join", g.id) { ok -> if (ok) onOpen() }
                            if (g.bughouse) onOpen()
                        }, small = true)
                    g.status == "waiting" || g.status == "live" -> WrButton(if (seated) "Open" else "Watch", onOpen, small = true, kind = BtnKind.Secondary)
                    g.status == "over" -> WrButton("View", onOpen, small = true, kind = BtnKind.Secondary)
                }
            }
        }
        if (g.status == "waiting" && g.creatorUid == me) {
            Spacer(Modifier.height(8.dp))
            WrButton("Cancel challenge", { session.chessAct("chess:cancel", g.id) }, small = true, kind = BtnKind.Ghost)
        }
    }
}

/* ----------------------------------------------------------------------------
 * The panel (one of the room's tabs)
 * -------------------------------------------------------------------------- */

@Composable
fun ChessPanel(modifier: Modifier, land: Boolean, expanded: Boolean, onExpand: (Boolean) -> Unit, onClose: () -> Unit, onNew: () -> Unit) {
    val session = AppCore.session
    val tick by session.tick.collectAsState()
    tick.let { }
    val s = session.room ?: return
    val g = s.chess.current
    Box(modifier.background(WR.bg)) {
        when {
            g == null -> EmptyState("chess", "No game yet", "Start a game and invite the room to play or watch.", Modifier.align(Alignment.Center)) {
                WrButton("New game", onNew, icon = "plus")
            }
            g.status == "waiting" -> ChessLobby(g, onNew)
            g.live || g.over -> ChessWorkspace(g, land, expanded, onExpand, onClose, onNew)
            else -> EmptyState("chess", if (g.status == "expired") "That challenge expired" else "That challenge was cancelled", "Nobody joined in time.", Modifier.align(Alignment.Center)) {
                WrButton("New game", onNew, icon = "plus")
            }
        }
    }
}

@Composable
private fun ChessLobby(g: ChessGame, onNew: () -> Unit) {
    val session = AppCore.session
    val me = session.myUid
    val mySeat = g.seatOf(me)
    val owner = g.creatorUid == me || session.isHost
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        app.waveroom.ui.components.HeroCard(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                WrIcon("chess", 30.dp, WR.neon)
                Spacer(Modifier.height(8.dp))
                Text("${g.label} · ${g.timeLabel}", color = WR.text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = WR.display)
                Muted(if (g.filled()) "FULL" else "WAITING FOR PLAYERS", size = 11)
                app.waveroom.ui.components.NeonWave(Modifier.fillMaxWidth().height(36.dp), amplitude = 9.dp, stroke = 2.5.dp)
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (g.bughouse) {
                TeamCard("Team 1", listOf(0 to "Board A · White", 3 to "Board B · Black"), g, mySeat)
                TeamCard("Team 2", listOf(1 to "Board A · Black", 2 to "Board B · White"), g, mySeat)
                Muted("Teammates play opposite colours on opposite boards. Pieces one of you captures go to the other's reserve.", size = 12)
            } else {
                SeatRow("Player 1", 0, g, mySeat, false)
                SeatRow("Player 2", 1, g, mySeat, mySeat < 0 && !g.filled())
                Muted("Colours are chosen at random when the game starts.", size = 12)
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.widthIn(max = 420.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (g.bughouse && g.creatorUid == me || g.bughouse && owner) {
                WrButton("START GAME", { session.chessAct("chess:start", g.id) }, Modifier.fillMaxWidth(), enabled = g.filled())
            }
            if (mySeat >= 0 && g.creatorUid != me) WrButton("Leave game", { session.chessAct("chess:leave", g.id) }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
            if (owner) WrButton("Cancel game", { session.chessAct("chess:cancel", g.id) }, Modifier.fillMaxWidth(), kind = BtnKind.Danger)
        }
        Spacer(Modifier.height(10.dp))
        Tiny("Unclaimed challenges expire after 10 minutes")
    }
}

@Composable
private fun TeamCard(title: String, seats: List<Pair<Int, String>>, g: ChessGame, mySeat: Int) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WR.surface).border(1.dp, WR.border, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(if (title == "Team 1") WR.accentBright else WR.warn))
            Spacer(Modifier.width(8.dp))
            Tiny(title.uppercase(), color = WR.muted)
        }
        for ((seat, label) in seats) SeatRow(label, seat, g, mySeat, mySeat < 0, flat = true)
    }
}

@Composable
private fun SeatRow(label: String, seat: Int, g: ChessGame, mySeat: Int, canTake: Boolean, flat: Boolean = false) {
    val session = AppCore.session
    val s = g.seats[seat]
    val who = session.room?.participants?.values?.firstOrNull { it.uid == s?.uid }
    var m = Modifier.fillMaxWidth()
    if (!flat) m = m.clip(RoundedCornerShape(10.dp)).background(WR.surface).border(1.dp, WR.border, RoundedCornerShape(10.dp))
    Row(m.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (who != null) AvatarView(who.avatar, 32.dp, uid = who.uid, mine = who.uid == session.myUid)
        else Box(Modifier.size(32.dp).clip(CircleShape).border(1.dp, WR.borderStrong, CircleShape))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(if (s != null) s.name + if (seat == mySeat) " (you)" else "" else "Open seat", color = if (s != null) WR.text else WR.faint, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Tiny(label)
        }
        if (s == null && canTake && mySeat < 0) WrButton("Take seat", { session.chessAct("chess:join", g.id, "seat" to seat) }, small = true)
    }
}

/* ----------------------------------------------------------------------------
 * Workspace: live game, spectating and review
 * -------------------------------------------------------------------------- */

@Composable
private fun ChessWorkspace(g: ChessGame, land: Boolean, expanded: Boolean, onExpand: (Boolean) -> Unit, onClose: () -> Unit, onNew: () -> Unit) {
    val session = AppCore.session
    val cfg by AppCore.settings.state.collectAsState()
    val ui = remember(g.id) { ChessUi() }
    ui.mySeat = g.seatOf(session.myUid)
    val now by produceState(nowMs(), g.id, g.status) {
        while (true) { value = nowMs(); delay(if (g.live) 100L else 1000L) }
    }
    var moreOpen by remember { mutableStateOf(false) }
    var resignAsk by remember { mutableStateOf(false) }

    // sounds, once per new move
    val counts = remember(g.id) { IntArray(2) { g.boards.getOrNull(it)?.moves?.size ?: 0 } }
    var announcedEnd by remember(g.id) { mutableStateOf(g.over) }
    val total = g.boards.sumOf { it.moves.size }
    LaunchedEffect(total, g.status) {
        for (i in g.boards.indices) {
            val b = g.boards[i]
            if (b.moves.size > counts[i]) {
                counts[i] = b.moves.size
                val san = b.sans.lastOrNull() ?: ""
                AppCore.sfx.play(if (san.endsWith("+") || san.endsWith("#")) "c_check" else if (san.contains('x')) "c_cap" else "c_move")
                if (ui.pending?.board == i) ui.pending = null
            }
        }
        if (g.over && !announcedEnd) { announcedEnd = true; AppCore.sfx.play("c_end") }
    }
    LaunchedEffect(ui.pending) {
        if (ui.pending != null) { delay(4000); ui.pending = null }
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        val variant = g.pocketMode != POCKET_NONE
        val chrome = (2 * 46 + (if (variant) 2 * 40 else 0)).dp

        val info: @Composable (Modifier) -> Unit = { mod ->
            Column(mod, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                InfoBlock(g, ui, onClose, onNew, expanded, onExpand, { moreOpen = true }, { resignAsk = true })
            }
        }

        if (!g.bughouse && !land) {
            val size = maxOf(200.dp, minOf(w, h - chrome - 150.dp))
            Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                BoardBlock(g, 0, size, ui, now, cfg.chessLegal)
                Spacer(Modifier.height(8.dp))
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBlock(g, ui, onClose, onNew, expanded, onExpand, { moreOpen = true }, { resignAsk = true })
                }
                Spacer(Modifier.height(4.dp))
                MoveList(g, ui, Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp))
            }
        } else if (!g.bughouse) {
            val size = maxOf(160.dp, minOf(h - chrome, w * 0.62f))
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BoardBlock(g, 0, size, ui, now, cfg.chessLegal)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).fillMaxHeight().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoveList(g, ui, Modifier.weight(1f).fillMaxWidth())
                    InfoBlock(g, ui, onClose, onNew, expanded, onExpand, { moreOpen = true }, { resignAsk = true })
                }
            }
        } else if (land) {
            val size = maxOf(150.dp, minOf(h - chrome, (w - 170.dp) / 2))
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BoardBlock(g, 0, size, ui, now, cfg.chessLegal)
                BoardBlock(g, 1, size, ui, now, cfg.chessLegal)
                Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBlock(g, ui, onClose, onNew, expanded, onExpand, { moreOpen = true }, { resignAsk = true })
                }
            }
        } else {
            val mine = if (ui.mySeat >= 0) g.boardOf(ui.mySeat) else 0
            val big = maxOf(240.dp, minOf(w, h - chrome - 20.dp))
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
                BoardBlock(g, mine, big, ui, now, cfg.chessLegal)
                Spacer(Modifier.height(10.dp))
                Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    InfoBlock(g, ui, onClose, onNew, expanded, onExpand, { moreOpen = true }, { resignAsk = true })
                }
                Spacer(Modifier.height(12.dp))
                BoardBlock(g, 1 - mine, w * 0.78f, ui, now, cfg.chessLegal)
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    if (resignAsk) {
        ConfirmDialog(
            "Resign this game?", if (g.bughouse) "Your team loses the game." else "You lose the game.", "Resign",
            { session.chessAct("chess:resign", g.id) }, { resignAsk = false }, danger = true
        )
    }
    if (moreOpen) {
        WrSheet({ moreOpen = false }, "Game") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                SwitchRow("Move sounds", checked = cfg.chessSounds, onChange = { v -> AppCore.settings.update { it.copy(chessSounds = v) } })
                SwitchRow("Highlight legal moves", checked = cfg.chessLegal, onChange = { v -> AppCore.settings.update { it.copy(chessLegal = v) } })
            }
            Row2("Flip board", null, "flip", onClick = { ui.flip = !ui.flip; moreOpen = false })
            Row2(if (expanded) "Show room controls" else "Large workspace", null, if (expanded) "collapse" else "expand", onClick = { onExpand(!expanded); moreOpen = false })
            Row2("Copy notation (PGN)", null, "copy", onClick = { copyText(g.pgn()); moreOpen = false })
            if (g.bughouse) {
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Tiny("Moves")
                    Spacer(Modifier.height(4.dp))
                    Text("Board A", color = WR.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(g.moveText(0).ifEmpty { "—" }, color = WR.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.height(8.dp))
                    Text("Board B", color = WR.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    Text(g.moveText(1).ifEmpty { "—" }, color = WR.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
            Row2("Close chess workspace", "The game keeps going", "x", onClick = { moreOpen = false; onClose() })
        }
    }
}

/* ---------------- one board with its two players ---------------- */

@Composable
private fun BoardBlock(g: ChessGame, b: Int, size: Dp, ui: ChessUi, now: Long, showLegal: Boolean) {
    val session = AppCore.session
    val board = g.boards[b]
    val mySeat = ui.mySeat
    val myBoard = if (mySeat >= 0) g.boardOf(mySeat) else -1
    val myColor = if (mySeat >= 0 && myBoard == b) g.colorOf(mySeat) else -1
    val natural = when {
        mySeat < 0 -> WHITE
        b == myBoard -> g.colorOf(mySeat)
        else -> g.colorOf(g.partner(mySeat))
    }
    val bottomColor = if (ui.flip) 1 - natural else natural
    val flipped = bottomColor == BLACK
    val bottomSeat = g.seatFor(b, bottomColor)
    val topSeat = g.seatFor(b, 1 - bottomColor)

    val viewing = !g.bughouse && ui.view in 0 until board.moves.size
    val pend = ui.pending?.takeIf { it.board == b }
    val shown: Triple<Position, Int, Int> = when {
        viewing -> remember(g.id, ui.view) { historic(g, ui.view) }
        pend != null -> Triple(pend.pos, pend.move.from, pend.move.to)
        else -> Triple(board.pos, board.lastFrom, board.lastTo)
    }
    val interactive = g.live && myColor >= 0 && !viewing && ui.pending == null && board.pos.turn == myColor
    val fen = board.pos.fen()
    val legal = remember(fen, interactive) { if (interactive) board.pos.legalMoves() else emptyList() }
    val variant = g.pocketMode != POCKET_NONE

    Column(Modifier.width(size)) {
        PlayerStrip(g, topSeat, now, shown.first, b)
        if (variant) PocketRow(shown.first, 1 - bottomColor, interactive && 1 - bottomColor == myColor, ui)
        Box {
            ChessBoardView(
                pos = shown.first, flipped = flipped, lastFrom = shown.second, lastTo = shown.third, myColor = myColor,
                interactive = interactive, legal = legal, showLegal = showLegal, dropType = if (interactive) ui.dropSel else 0,
                onMove = { m ->
                    ui.pending = Pending(b, m, board.pos.play(m).pos)
                    ui.dropSel = 0
                    session.chessAct("chess:move", g.id, "m" to m.uci(), "ply" to board.moves.size) { ok -> if (!ok) ui.pending = null }
                },
                modifier = Modifier.fillMaxWidth(),
                label = if (g.bughouse) "Board ${if (b == 0) "A" else "B"}" else "Chessboard"
            )
            if (g.over && ui.showResult && !g.bughouse) ResultOverlay(g, ui, Modifier.align(Alignment.Center))
        }
        if (variant) PocketRow(shown.first, bottomColor, interactive && bottomColor == myColor, ui)
        PlayerStrip(g, bottomSeat, now, shown.first, b)
    }
}

@Composable
private fun PlayerStrip(g: ChessGame, seat: Int, now: Long, pos: Position, board: Int) {
    val session = AppCore.session
    val s = g.seats[seat]
    val who = session.room?.participants?.values?.firstOrNull { it.uid == s?.uid }
    val running = g.running(seat)
    val toMove = g.live && g.boards[board].pos.turn == g.colorOf(seat)
    val remaining = g.remaining(seat, now)
    val low = running && remaining < 10_000
    val team = if (g.bughouse) g.teamOf(seat) else -1
    val color = g.colorOf(seat)
    val caps = if (g.pocketMode == POCKET_NONE) missing(pos, 1 - color) else emptyList()
    val lead = caps.sumOf { VALUE[it] } - missing(pos, color).sumOf { VALUE[it] }
    Row(
        Modifier.fillMaxWidth().height(46.dp).background(WR.surface).padding(end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.width(3.dp).fillMaxHeight().background(if (team == 0) WR.accentBright else if (team == 1) WR.warn else if (toMove) WR.accent else Color.Transparent))
        Spacer(Modifier.width(8.dp))
        if (who != null) AvatarView(who.avatar, 28.dp, uid = who.uid, mine = who.uid == session.myUid)
        else Box(Modifier.size(28.dp).clip(CircleShape).border(1.dp, WR.borderStrong, CircleShape))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (color == WHITE) PIECE_LIGHT else PIECE_DARK).border(1.dp, WR.borderStrong, CircleShape))
                Spacer(Modifier.width(6.dp))
                Text(
                    (s?.name ?: "Waiting…") + if (s != null && s.uid == session.myUid) " (you)" else "",
                    color = WR.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)
                )
                if (g.live && s != null && who == null) { Spacer(Modifier.width(6.dp)); Tiny("away") }
            }
            if (g.bughouse) Tiny("Team ${team + 1} · Board ${if (board == 0) "A" else "B"}")
            else if (caps.isNotEmpty() || lead != 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    for (t in caps.take(14)) PieceIcon(t, 1 - color, 13.dp)
                    if (lead > 0) { Spacer(Modifier.width(4.dp)); Text("+$lead", color = WR.muted, fontSize = 11.sp) }
                }
            }
        }
        Box(
            Modifier.clip(RoundedCornerShape(6.dp))
                .background(if (low) WR.dangerSoft else if (running) WR.accentSoft else WR.surface2)
                .border(1.dp, if (low) WR.danger else if (running) WR.accent else WR.border, RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
            Text(
                fmtMs(remaining), color = if (low) WR.danger else if (running) WR.text else WR.muted,
                fontSize = 17.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
private fun PocketRow(pos: Position, color: Int, selectable: Boolean, ui: ChessUi) {
    Row(
        Modifier.fillMaxWidth().height(40.dp).background(WR.surface2).padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Tiny("RESERVE")
        Spacer(Modifier.width(8.dp))
        for (t in QUEEN downTo PAWN) {
            val n = pos.pocket[color][t]
            val on = selectable && ui.dropSel == t
            Box(
                Modifier.padding(horizontal = 2.dp).size(34.dp).clip(RoundedCornerShape(6.dp))
                    .background(if (on) WR.accentSoft else Color.Transparent)
                    .border(1.dp, if (on) WR.accent else if (n > 0) WR.borderStrong else Color.Transparent, RoundedCornerShape(6.dp))
                    .clickable(enabled = selectable && n > 0) { ui.dropSel = if (on) 0 else t },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(28.dp)) {
                    drawPiece(t, color, 0f, 0f, size.width, if (n > 0) 1f else 0.22f)
                }
                if (n > 1) Text("$n", color = WR.text, fontSize = 10.sp, fontWeight = FontWeight.Bold, modifier = Modifier.align(Alignment.BottomEnd).padding(end = 2.dp))
            }
        }
        if (selectable) { Spacer(Modifier.weight(1f)); Tiny(if (ui.dropSel > 0) "Tap a square to drop" else "Tap a piece to drop it") }
    }
}

/* ---------------- result, actions, moves ---------------- */

@Composable
private fun ResultOverlay(g: ChessGame, ui: ChessUi, modifier: Modifier) {
    Column(
        modifier.clip(RoundedCornerShape(10.dp)).background(WR.surface.copy(alpha = 0.94f)).border(1.dp, WR.borderStrong, RoundedCornerShape(10.dp)).padding(horizontal = 22.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(g.winnerLine(), color = WR.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = WR.display, letterSpacing = 1.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(2.dp))
        Text(reasonLine(g.reason), color = WR.muted, fontSize = 13.sp, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        WrButton("Review board", { ui.showResult = false }, small = true, kind = BtnKind.Secondary)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoBlock(
    g: ChessGame, ui: ChessUi, onClose: () -> Unit, onNew: () -> Unit,
    expanded: Boolean, onExpand: (Boolean) -> Unit, onMore: () -> Unit, onResign: () -> Unit
) {
    val session = AppCore.session
    val me = session.myUid
    val seat = ui.mySeat
    val seated = seat >= 0

    if (g.bughouse && g.over) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WR.surface).border(1.dp, WR.borderStrong, RoundedCornerShape(10.dp)).padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(g.winnerLine(), color = WR.text, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = WR.display, letterSpacing = 1.sp)
            Text(reasonLine(g.reason), color = WR.muted, fontSize = 13.sp)
            if (g.winner in 0..1) Muted((0 until 4).filter { g.teamOf(it) == g.winner }.joinToString(" & ") { g.seats[it]?.name ?: "?" }, size = 12)
        }
    }

    if (g.live) {
        if (!seated) Tiny("You are watching")
        else if (g.bughouse) {
            val partner = g.seats[g.partner(seat)]?.name ?: "your partner"
            Muted("You and $partner play together. Captures go to their reserve.", size = 12)
        }
        // draw offers
        if (g.drawBy >= 0 && seated && !g.bughouse) {
            if (g.drawBy != seat) {
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WR.surface2).border(1.dp, WR.accent, RoundedCornerShape(10.dp)).padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("${g.seats[g.drawBy]?.name ?: "Your opponent"} offers a draw", color = WR.text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                    WrButton("Accept", { session.chessAct("chess:draw", g.id, "act" to "accept") }, small = true)
                    Spacer(Modifier.width(6.dp))
                    WrButton("Decline", { session.chessAct("chess:draw", g.id, "act" to "decline") }, small = true, kind = BtnKind.Secondary)
                }
            } else Muted("Draw offered — waiting for your opponent.", size = 12)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (seated) {
                WrButton("Resign", onResign, Modifier.weight(1f), kind = BtnKind.Danger, small = true, icon = "flag")
                if (!g.bughouse) WrButton(
                    if (g.drawBy == seat) "Offered" else "Offer draw", { session.chessAct("chess:draw", g.id, "act" to "offer") },
                    Modifier.weight(1f), kind = BtnKind.Secondary, small = true, enabled = g.drawBy < 0
                )
            } else Spacer(Modifier.weight(1f))
            WrIconButton("flip", { ui.flip = !ui.flip }, size = 40.dp, bg = WR.surface2, border = WR.border, desc = "Flip board")
            WrIconButton(if (expanded) "collapse" else "expand", { onExpand(!expanded) }, size = 40.dp, bg = WR.surface2, border = WR.border, desc = if (expanded) "Show room controls" else "Large workspace")
            WrIconButton("more", onMore, size = 40.dp, bg = WR.surface2, border = WR.border, desc = "Game settings")
        }
    } else if (g.over) {
        if (!g.bughouse && !ui.showResult) {
            Text("${g.winnerLine()} · ${reasonLine(g.reason)}", color = WR.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (seated && g.next.isEmpty()) {
                val iVoted = g.rematch.contains(me)
                val other = g.rematch.any { it != me }
                WrButton(
                    when { iVoted -> "Waiting for others…"; other -> "Accept rematch"; else -> "Rematch" },
                    { session.chessAct("chess:rematch", g.id) }, small = true, enabled = !iVoted
                )
            }
            WrButton("New game", onNew, small = true, kind = BtnKind.Secondary)
            WrButton("Copy PGN", { copyText(g.pgn()) }, small = true, kind = BtnKind.Secondary, icon = "copy")
            WrButton("Close", onClose, small = true, kind = BtnKind.Ghost)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WrIconButton("flip", { ui.flip = !ui.flip }, size = 40.dp, bg = WR.surface2, border = WR.border, desc = "Flip board")
            WrIconButton(if (expanded) "collapse" else "expand", { onExpand(!expanded) }, size = 40.dp, bg = WR.surface2, border = WR.border, desc = if (expanded) "Show room controls" else "Large workspace")
            WrIconButton("more", onMore, size = 40.dp, bg = WR.surface2, border = WR.border, desc = "Game settings")
        }
    }
}

@Composable
private fun MoveList(g: ChessGame, ui: ChessUi, modifier: Modifier) {
    if (g.bughouse) return
    val sans = g.boards[0].sans
    val listState = rememberLazyListState()
    val rows = (sans.size + 1) / 2
    LaunchedEffect(sans.size) { if (rows > 0 && ui.view < 0) listState.animateScrollToItem(rows - 1) }
    val live = ui.view < 0 || ui.view >= sans.size
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(WR.surface).border(1.dp, WR.border, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Tiny("MOVES")
            Spacer(Modifier.weight(1f))
            val cur = if (live) sans.size else ui.view
            WrIconButton("chevron-left", { ui.view = maxOf(0, cur - 1) }, size = 36.dp, iconSize = 18.dp, enabled = sans.isNotEmpty() && cur > 0, desc = "Previous move")
            WrIconButton("chevron-right", { val n = cur + 1; ui.view = if (n >= sans.size) -1 else n }, size = 36.dp, iconSize = 18.dp, enabled = !live, desc = "Next move")
            if (!live) WrChip("Live", true, { ui.view = -1 })
        }
        if (sans.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Tiny("No moves yet") }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState) {
                itemsIndexed((0 until rows).toList()) { _, r ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${r + 1}.", color = WR.faint, fontSize = 12.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.width(32.dp))
                        for (k in 0..1) {
                            val i = r * 2 + k
                            val san = sans.getOrNull(i)
                            val selected = if (live) i == sans.size - 1 else i + 1 == ui.view
                            Box(
                                Modifier.weight(1f).clip(RoundedCornerShape(5.dp)).background(if (selected) WR.accentSoft else Color.Transparent)
                                    .clickable(enabled = san != null) { ui.view = if (i + 1 >= sans.size) -1 else i + 1 }.padding(horizontal = 8.dp, vertical = 6.dp)
                            ) {
                                if (san != null) Text(san, color = if (selected) WR.accentBright else WR.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            }
        }
    }
}
