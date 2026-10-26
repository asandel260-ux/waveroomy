package app.waveroom.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.waveroom.chess.BISHOP
import app.waveroom.chess.BLACK
import app.waveroom.chess.KING
import app.waveroom.chess.KNIGHT
import app.waveroom.chess.Move
import app.waveroom.chess.PAWN
import app.waveroom.chess.Position
import app.waveroom.chess.QUEEN
import app.waveroom.chess.ROOK
import app.waveroom.chess.WHITE
import app.waveroom.chess.colorOf
import app.waveroom.chess.sqName
import app.waveroom.chess.typeOf
import app.waveroom.ui.theme.WR
import kotlin.math.roundToInt

/* ----------------------------------------------------------------------------
 * Pieces: drawn as geometry in a 100 x 100 box, so they stay crisp at any size and
 * never depend on a font (which on Android can turn chess glyphs into emoji).
 * -------------------------------------------------------------------------- */

private class Art(val fills: List<Path>, val strokes: List<Path> = emptyList())

private fun poly(vararg pts: Float): Path {
    val p = Path()
    p.moveTo(pts[0], pts[1])
    var i = 2
    while (i < pts.size) { p.lineTo(pts[i], pts[i + 1]); i += 2 }
    p.close()
    return p
}

private fun circle(cx: Float, cy: Float, r: Float): Path = Path().apply { addOval(androidx.compose.ui.geometry.Rect(cx - r, cy - r, cx + r, cy + r)) }

private fun line(x1: Float, y1: Float, x2: Float, y2: Float): Path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }

private val ART: Map<Int, Art> by lazy {
    val base = poly(26f, 88f, 74f, 88f, 74f, 80f, 26f, 80f)
    mapOf(
        PAWN to Art(listOf(circle(50f, 29f, 12f), poly(43f, 40f, 57f, 40f, 66f, 74f, 34f, 74f), poly(28f, 74f, 72f, 74f, 72f, 82f, 28f, 82f))),
        ROOK to Art(
            listOf(
                poly(30f, 22f, 41f, 22f, 41f, 29f, 45f, 29f, 45f, 22f, 55f, 22f, 55f, 29f, 59f, 29f, 59f, 22f, 70f, 22f, 70f, 40f, 30f, 40f),
                poly(36f, 40f, 64f, 40f, 67f, 74f, 33f, 74f), poly(27f, 74f, 73f, 74f, 73f, 83f, 27f, 83f)
            )
        ),
        KNIGHT to Art(
            listOf(
                Path().apply {
                    moveTo(28f, 83f); lineTo(30f, 68f); cubicTo(31f, 56f, 36f, 50f, 44f, 44f)
                    cubicTo(38f, 44f, 31f, 47f, 26f, 52f); lineTo(24f, 45f); cubicTo(28f, 36f, 37f, 28f, 45f, 25f)
                    lineTo(46f, 14f); lineTo(55f, 22f); cubicTo(68f, 26f, 75f, 42f, 73f, 60f)
                    cubicTo(72f, 68f, 71f, 75f, 72f, 83f); close()
                }
            ),
            listOf(line(48f, 34f, 52f, 34f))
        ),
        BISHOP to Art(
            listOf(
                circle(50f, 15f, 5f),
                Path().apply {
                    moveTo(50f, 20f); cubicTo(62f, 30f, 66f, 42f, 60f, 54f); lineTo(67f, 74f); lineTo(33f, 74f); lineTo(40f, 54f)
                    cubicTo(34f, 42f, 38f, 30f, 50f, 20f); close()
                },
                poly(28f, 74f, 72f, 74f, 72f, 83f, 28f, 83f)
            ),
            listOf(line(50f, 30f, 57f, 42f))
        ),
        QUEEN to Art(
            listOf(
                poly(26f, 76f, 19f, 32f, 35f, 52f, 41f, 26f, 50f, 50f, 59f, 26f, 65f, 52f, 81f, 32f, 74f, 76f),
                circle(19f, 28f, 4.5f), circle(41f, 22f, 4.5f), circle(59f, 22f, 4.5f), circle(81f, 28f, 4.5f),
                poly(24f, 76f, 76f, 76f, 76f, 84f, 24f, 84f)
            )
        ),
        KING to Art(
            listOf(
                poly(46f, 10f, 54f, 10f, 54f, 17f, 61f, 17f, 61f, 24f, 54f, 24f, 54f, 33f, 46f, 33f, 46f, 24f, 39f, 24f, 39f, 17f, 46f, 17f),
                Path().apply {
                    moveTo(50f, 33f); cubicTo(70f, 34f, 76f, 48f, 70f, 58f); lineTo(68f, 76f); lineTo(32f, 76f); lineTo(30f, 58f)
                    cubicTo(24f, 48f, 30f, 34f, 50f, 33f); close()
                },
                poly(26f, 76f, 74f, 76f, 74f, 84f, 26f, 84f)
            )
        )
    )
}

val PIECE_LIGHT = Color(0xFFEFEDE6)
val PIECE_DARK = Color(0xFF16181D)
val SQ_LIGHT = Color(0xFFD7D4CC)
val SQ_DARK = Color(0xFF727C88)

fun DrawScope.drawPiece(type: Int, color: Int, x: Float, y: Float, size: Float, alpha: Float = 1f) {
    val art = ART[type] ?: return
    val fill = (if (color == WHITE) PIECE_LIGHT else PIECE_DARK).copy(alpha = alpha)
    val edge = (if (color == WHITE) Color(0xFF1B1D22) else Color(0xFFCFCBC2)).copy(alpha = alpha)
    withTransform({
        translate(x, y)
        scale(size / 100f, size / 100f, Offset.Zero)
    }) {
        for (p in art.fills) {
            drawPath(p, fill)
            drawPath(p, edge, style = Stroke(width = 2.2f))
        }
        for (p in art.strokes) drawPath(p, edge, style = Stroke(width = 2.6f))
    }
}

/** A single piece as a standalone little canvas (reserves, captured rows, promotion). */
@Composable
fun PieceIcon(type: Int, color: Int, size: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawPiece(type, color, 0f, 0f, this.size.width) }
}

/* ----------------------------------------------------------------------------
 * The board
 * -------------------------------------------------------------------------- */

/**
 * @param myColor the colour this device may move for, or -1 when only watching.
 * @param legal   this position's legal moves (only needed when [interactive]).
 * @param dropType a reserve piece picked by the player, 0 when none.
 */
@Composable
fun ChessBoardView(
    pos: Position,
    flipped: Boolean,
    lastFrom: Int,
    lastTo: Int,
    myColor: Int,
    interactive: Boolean,
    legal: List<Move>,
    showLegal: Boolean,
    dropType: Int,
    onMove: (Move) -> Unit,
    modifier: Modifier = Modifier,
    label: String = "Chessboard"
) {
    var selected by remember(pos) { mutableIntStateOf(-1) }
    var dragSq by remember { mutableIntStateOf(-1) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }
    var promo by remember(pos) { mutableStateOf<List<Move>?>(null) }
    var skipAnim by remember { mutableStateOf(false) }
    val anim = remember { Animatable(1f) }
    val density = LocalDensity.current

    var first by remember { mutableStateOf(true) }
    LaunchedEffect(lastFrom, lastTo, pos.full, pos.turn) {
        if (first) { first = false; anim.snapTo(1f); return@LaunchedEffect }
        if (lastFrom >= 0 && !skipAnim) {
            anim.snapTo(0f)
            anim.animateTo(1f, tween(130, easing = LinearOutSlowInEasing))
        } else anim.snapTo(1f)
        skipAnim = false
    }

    val targets: List<Move> = when {
        !interactive -> emptyList()
        dropType > 0 -> legal.filter { it.drop == dropType }
        selected >= 0 -> legal.filter { it.from == selected }
        else -> emptyList()
    }
    val checkSq = if (pos.inCheck(pos.turn)) pos.kingSq(pos.turn) else -1

    fun tryMove(from: Int, to: Int): Boolean {
        val c = legal.filter { it.from == from && it.to == to && it.drop == 0 }
        if (c.isEmpty()) return false
        if (c.size > 1) promo = c else onMove(c[0])
        return true
    }

    Box(modifier.aspectRatio(1f).semantics { contentDescription = label }) {
        Canvas(
            Modifier.fillMaxSize().pointerInput(pos, legal, flipped, interactive, myColor, dropType) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val cell = size.width / 8f
                    fun sqAt(o: Offset): Int {
                        val f = (o.x / cell).toInt().coerceIn(0, 7)
                        val r = (o.y / cell).toInt().coerceIn(0, 7)
                        return if (!flipped) (7 - r) * 8 + f else r * 8 + (7 - f)
                    }
                    if (!interactive || promo != null) return@awaitEachGesture
                    val s0 = sqAt(down.position)
                    val prev = selected
                    val own = pos.sq[s0] != 0 && colorOf(pos.sq[s0]) == myColor && dropType == 0
                    if (own) selected = s0
                    var moved = false
                    var last = down.position
                    val slop = viewConfiguration.touchSlop
                    drag(down.id) { ch ->
                        last = ch.position
                        if (own && (last - down.position).getDistance() > slop) {
                            moved = true
                            dragSq = s0
                            dragPos = last
                        }
                        ch.consume()
                    }
                    dragSq = -1
                    val end = sqAt(last)
                    if (moved) {
                        if (end != s0) {
                            skipAnim = true
                            if (!tryMove(s0, end)) skipAnim = false
                        }
                    } else if (dropType > 0) {
                        val m = legal.firstOrNull { it.drop == dropType && it.to == end }
                        if (m != null) onMove(m)
                    } else {
                        if (prev >= 0 && prev != s0 && tryMove(prev, s0)) {
                            // moved by tap
                        } else selected = if (own && prev != s0) s0 else -1
                    }
                }
            }
        ) {
            val cell = size.width / 8f
            fun px(sq: Int): Offset {
                val f = sq and 7
                val r = sq shr 3
                return if (!flipped) Offset(f * cell, (7 - r) * cell) else Offset((7 - f) * cell, r * cell)
            }
            for (sq in 0 until 64) {
                val f = sq and 7
                val r = sq shr 3
                val o = px(sq)
                drawRect(if ((f + r) % 2 == 1) SQ_LIGHT else SQ_DARK, o, Size(cell, cell))
            }
            // last move + selection
            if (lastFrom >= 0) drawRect(WR.accent.copy(alpha = 0.34f), px(lastFrom), Size(cell, cell))
            if (lastTo >= 0) drawRect(WR.accent.copy(alpha = 0.44f), px(lastTo), Size(cell, cell))
            if (selected >= 0) drawRect(WR.accent.copy(alpha = 0.55f), px(selected), Size(cell, cell))
            if (checkSq >= 0) {
                val o = px(checkSq)
                drawCircle(androidx.compose.ui.graphics.Brush.radialGradient(listOf(WR.danger.copy(alpha = 0.85f), Color.Transparent), center = o + Offset(cell / 2, cell / 2), radius = cell * 0.75f), cell * 0.75f, o + Offset(cell / 2, cell / 2))
            }
            // coordinates
            val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = cell * 0.2f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            for (i in 0..7) {
                val fileSq = if (!flipped) i else 7 - i
                val rankSq = if (!flipped) 7 - i else i
                val lightFile = (fileSq + (if (!flipped) 0 else 7)) % 2 == 1
                paint.color = (if (lightFile) SQ_DARK else SQ_LIGHT).toArgb()
                drawContext.canvas.nativeCanvas.drawText("${'a' + fileSq}", i * cell + cell * 0.82f, 8 * cell - cell * 0.06f, paint.also { it.textAlign = android.graphics.Paint.Align.CENTER })
                val lightRank = ((if (!flipped) 0 else 7) + rankSq) % 2 == 1
                paint.color = (if (lightRank) SQ_DARK else SQ_LIGHT).toArgb()
                drawContext.canvas.nativeCanvas.drawText("${rankSq + 1}", cell * 0.12f, i * cell + cell * 0.28f, paint.also { it.textAlign = android.graphics.Paint.Align.CENTER })
            }
            // pieces
            val t = anim.value
            for (sq in 0 until 64) {
                val p = pos.sq[sq]
                if (p == 0) continue
                if (sq == dragSq) continue
                var o = px(sq)
                if (sq == lastTo && lastFrom >= 0 && t < 1f) {
                    val fo = px(lastFrom)
                    o = Offset(fo.x + (o.x - fo.x) * t, fo.y + (o.y - fo.y) * t)
                }
                drawPiece(typeOf(p), colorOf(p), o.x, o.y, cell)
            }
            // move hints on top of pieces
            if (showLegal || dropType > 0) {
                for (m in targets) {
                    val o = px(m.to)
                    val c = o + Offset(cell / 2, cell / 2)
                    if (pos.sq[m.to] != 0 || (m.drop == 0 && typeOf(pos.sq[m.from]) == PAWN && (m.from and 7) != (m.to and 7))) {
                        drawCircle(Color(0xFF111114).copy(alpha = 0.34f), cell * 0.47f, c, style = Stroke(width = cell * 0.09f))
                    } else drawCircle(Color(0xFF111114).copy(alpha = 0.30f), cell * 0.15f, c)
                }
            }
            if (dragSq >= 0) {
                val p = pos.sq[dragSq]
                val big = cell * 1.25f
                drawPiece(typeOf(p), colorOf(p), dragPos.x - big / 2, dragPos.y - big / 2 - cell * 0.5f, big)
            }
        }
        val choices = promo
        if (choices != null) {
            val to = choices[0].to
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable { promo = null }, contentAlignment = Alignment.Center) {
                Row(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(WR.surface).border(1.dp, WR.borderStrong, RoundedCornerShape(10.dp)).padding(8.dp),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
                ) {
                    val col = colorOf(pos.sq[choices[0].from])
                    for (m in choices) {
                        Box(
                            Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(if (col == WHITE) SQ_DARK else SQ_LIGHT)
                                .semantics { contentDescription = "Promote to ${pieceName(m.promo)} on ${sqName(to)}" }
                                .clickable { promo = null; onMove(m) },
                            contentAlignment = Alignment.Center
                        ) { PieceIcon(m.promo, col, 42.dp) }
                    }
                }
            }
        }
    }
}

fun pieceName(t: Int) = when (t) { PAWN -> "pawn"; KNIGHT -> "knight"; BISHOP -> "bishop"; ROOK -> "rook"; QUEEN -> "queen"; else -> "king" }
