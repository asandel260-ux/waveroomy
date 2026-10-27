package app.waveroom.ui.screens

import android.graphics.PointF
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import app.waveroom.core.BoardObj
import app.waveroom.core.BoardState
import app.waveroom.core.Ids
import app.waveroom.core.cancelLater
import app.waveroom.core.later
import app.waveroom.core.nowMs
import app.waveroom.room.RoomSession
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

val BOARD_COLORS = listOf(0xEDEDEF, 0x9A9AA3, 0x6E56CF, 0x3DD68C, 0xE5484D, 0xE2B93B, 0x4DA3FF, 0xFF8A3D)
val DRAW_TOOLS = setOf("pencil", "pen", "highlight", "line", "arrow", "rect", "ellipse")

sealed class BOp {
    class Add(val objs: List<BoardObj>) : BOp()
    class Del(val ids: List<String>) : BOp()
    class Patch(val patches: Map<String, JSONObject>) : BOp()
}

class BEntry(val undo: List<BOp>, val redo: List<BOp>)

data class LaserPoint(val x: Float, val y: Float, val t: Long)

/** All interaction logic of the shared blackboard; the Compose layer only draws and forwards touches. */
class BoardController(private val session: RoomSession, val board: BoardState, private val bumpFrame: () -> Unit) {

    var camX by mutableFloatStateOf(0f)
    var camY by mutableFloatStateOf(0f)
    var zoom by mutableFloatStateOf(1f)
    var vw = 1f
    var vh = 1f
    var density = 1f

    var tool by mutableStateOf("select")
    var color by mutableIntStateOf(0xEDEDEF)
    var width by mutableFloatStateOf(3f)
    var opacity by mutableFloatStateOf(1f)
    var dash by mutableStateOf(false)
    var textSize by mutableFloatStateOf(24f)

    var selection by mutableStateOf<Set<String>>(emptySet())
    var draft by mutableStateOf<BoardObj?>(null)
    var marquee by mutableStateOf<RectF?>(null)
    var frame by mutableIntStateOf(0)
    var canUndo by mutableStateOf(false)
    var canRedo by mutableStateOf(false)
    var erasing by mutableStateOf<Set<String>>(emptySet())
    var textEdit by mutableStateOf<TextEdit?>(null)

    class TextEdit(val existing: BoardObj?, val wx: Float, val wy: Float)

    val images = BoardImages { bumpFrame() }
    val lasers = HashMap<String, ArrayList<LaserPoint>>()

    private val history = ArrayList<BEntry>()
    private var histIdx = -1

    private var mode = ""
    private var downS = PointF()
    private var downW = PointF()
    private var downAt = 0L
    private var moved = false
    private var snap = HashMap<String, BoardObj>()
    private var handle = -1
    private var strokePts = FloatArray(256)
    private var strokeN = 0
    private var panCamX = 0f
    private var panCamY = 0f
    private var lastTapAt = 0L
    private var lastTapId: String? = null
    private val pending = HashMap<String, JSONObject>()
    private var flushTimer: Runnable? = null

    private fun redraw() {
        frame++
    }

    fun toWorldX(sx: Float) = sx / zoom + camX
    fun toWorldY(sy: Float) = sy / zoom + camY
    fun toScreenX(wx: Float) = (wx - camX) * zoom
    fun toScreenY(wy: Float) = (wy - camY) * zoom

    fun sorted(): List<BoardObj> = board.sortedByZ()
    fun selected(): List<BoardObj> = selection.mapNotNull { board.objects[it] }

    /* ======================= camera ======================= */

    fun pinch(cx: Float, cy: Float, factor: Float, dx: Float, dy: Float) {
        val wx = toWorldX(cx)
        val wy = toWorldY(cy)
        zoom = (zoom * factor).coerceIn(0.08f, 8f)
        camX = wx - cx / zoom
        camY = wy - cy / zoom
        camX -= dx / zoom
        camY -= dy / zoom
        redraw()
    }

    fun fitAll() {
        val all = board.objects.values
        val u = BoardGeo.union(all)
        if (u == null) {
            zoom = 1f; camX = -vw / 2f; camY = -vh / 2f
        } else {
            val pad = 60f * density
            val z = min((vw - pad * 2) / max(1f, u.width()), (vh - pad * 2) / max(1f, u.height())).coerceIn(0.08f, 2f)
            zoom = z
            camX = u.centerX() - vw / z / 2f
            camY = u.centerY() - vh / z / 2f
        }
        redraw()
    }

    fun resetZoom() {
        val wx = toWorldX(vw / 2f)
        val wy = toWorldY(vh / 2f)
        zoom = 1f
        camX = wx - vw / 2f
        camY = wy - vh / 2f
        redraw()
    }

    /* ======================= network helpers ======================= */

    private fun patchOf(o: BoardObj): JSONObject {
        val j = o.toJson(false)
        j.remove("id"); j.remove("type"); j.remove("by")
        return j
    }

    private fun queuePatch(o: BoardObj) {
        pending[o.id] = patchOf(o)
        if (flushTimer == null) flushTimer = later(70) { flushPending() }
    }

    private fun flushPending() {
        flushTimer = null
        val items = HashMap(pending)
        pending.clear()
        for ((id, p) in items) if (board.objects.containsKey(id)) session.boardUpdate(id, p)
    }

    private fun applyOps(ops: List<BOp>) {
        for (op in ops) {
            when (op) {
                is BOp.Add -> for (o in op.objs) {
                    if (!board.objects.containsKey(o.id)) session.boardAdd(o.deepCopy())
                }
                is BOp.Del -> {
                    val ids = op.ids.filter { board.objects.containsKey(it) }
                    if (ids.isNotEmpty()) {
                        for (i in ids) pending.remove(i)
                        session.boardDelete(ids)
                        images.forget(ids)
                    }
                    selection = selection - op.ids.toSet()
                }
                is BOp.Patch -> for ((id, p) in op.patches) {
                    if (board.objects.containsKey(id)) {
                        pending.remove(id)
                        board.patch(id, p)
                        session.boardUpdate(id, p)
                    }
                }
            }
        }
        redraw()
    }

    /* ======================= history ======================= */

    private fun push(e: BEntry) {
        while (history.size > histIdx + 1) history.removeAt(history.size - 1)
        history.add(e)
        if (history.size > 100) history.removeAt(0)
        histIdx = history.size - 1
        syncHist()
    }

    private fun syncHist() {
        canUndo = histIdx >= 0
        canRedo = histIdx < history.size - 1
    }

    fun undo() {
        if (histIdx < 0) return
        applyOps(history[histIdx].undo)
        histIdx--
        syncHist()
    }

    fun redo() {
        if (histIdx >= history.size - 1) return
        histIdx++
        applyOps(history[histIdx].redo)
        syncHist()
    }

    private fun patchEntry(before: Map<String, BoardObj>) {
        val b = HashMap<String, JSONObject>()
        val a = HashMap<String, JSONObject>()
        for ((id, o) in before) {
            val cur = board.objects[id] ?: continue
            b[id] = patchOf(o)
            a[id] = patchOf(cur)
        }
        if (b.isNotEmpty()) push(BEntry(listOf(BOp.Patch(b)), listOf(BOp.Patch(a))))
    }

    /* ======================= hit testing ======================= */

    private fun expandGroup(ids: Set<String>): Set<String> {
        val groups = ids.mapNotNull { board.objects[it]?.group }.toSet()
        if (groups.isEmpty()) return ids
        val out = HashSet(ids)
        for (o in board.objects.values) if (o.group != null && o.group in groups) out.add(o.id)
        return out
    }

    private fun hitObject(wx: Float, wy: Float): BoardObj? {
        val tol = 14f * density / zoom
        val list = sorted()
        for (i in list.indices.reversed()) if (BoardGeo.hit(list[i], wx, wy, tol)) return list[i]
        return null
    }

    /** 8 box handles (0..7: TL,T,TR,R,BR,B,BL,L) in world coordinates, or endpoints for lines. */
    fun handlePoints(): List<PointF> {
        val sel = selected()
        if (sel.isEmpty()) return emptyList()
        if (sel.size == 1 && (sel[0].type == "line" || sel[0].type == "arrow")) {
            return listOf(PointF(sel[0].x1, sel[0].y1), PointF(sel[0].x2, sel[0].y2))
        }
        if (sel.size == 1) {
            val o = sel[0]
            val b = BoardGeo.bbox(o)
            val c = BoardGeo.center(o)
            val raw = listOf(
                PointF(b.left, b.top), PointF(b.centerX(), b.top), PointF(b.right, b.top), PointF(b.right, b.centerY()),
                PointF(b.right, b.bottom), PointF(b.centerX(), b.bottom), PointF(b.left, b.bottom), PointF(b.left, b.centerY())
            )
            return raw.map { rotPt(it, c, o.rot) }
        }
        val u = BoardGeo.union(sel) ?: return emptyList()
        return listOf(
            PointF(u.left, u.top), PointF(u.centerX(), u.top), PointF(u.right, u.top), PointF(u.right, u.centerY()),
            PointF(u.right, u.bottom), PointF(u.centerX(), u.bottom), PointF(u.left, u.bottom), PointF(u.left, u.centerY())
        )
    }

    fun rotateHandle(): PointF? {
        val sel = selected()
        if (sel.size != 1) return null
        val o = sel[0]
        if (o.type == "line" || o.type == "arrow" || o.locked) return null
        val b = BoardGeo.bbox(o)
        val c = BoardGeo.center(o)
        return rotPt(PointF(b.centerX(), b.top - 30f * density / zoom), c, o.rot)
    }

    private fun rotPt(p: PointF, c: PointF, a: Float): PointF {
        if (a == 0f) return PointF(p.x, p.y)
        val cs = cos(a); val sn = sin(a)
        val dx = p.x - c.x; val dy = p.y - c.y
        return PointF(c.x + dx * cs - dy * sn, c.y + dx * sn + dy * cs)
    }

    private fun hitHandle(wx: Float, wy: Float): Int {
        val sel = selected()
        if (sel.isEmpty() || sel.all { it.locked }) return -1
        val r = 26f * density / zoom
        rotateHandle()?.let { if (hypot(wx - it.x, wy - it.y) < r) return 100 }
        val hp = handlePoints()
        for (i in hp.indices) if (hypot(wx - hp[i].x, wy - hp[i].y) < r) return i
        return -1
    }

    /* ======================= gestures ======================= */

    fun onDown(sx: Float, sy: Float) {
        downS = PointF(sx, sy)
        downW = PointF(toWorldX(sx), toWorldY(sy))
        downAt = nowMs()
        moved = false
        handle = -1
        snap.clear()
        mode = ""
        val wx = downW.x
        val wy = downW.y
        when (tool) {
            "hand" -> { mode = "pan"; panCamX = camX; panCamY = camY }
            "laser" -> { mode = "laser"; laser(wx, wy) }
            "eraser" -> { mode = "erase"; erasing = emptySet(); eraseAt(wx, wy) }
            "text" -> mode = "text"
            "image" -> mode = ""
            "select" -> {
                val h = hitHandle(wx, wy)
                if (h >= 0) {
                    mode = if (h == 100) "rotate" else "resize"
                    handle = h
                    snapshot()
                } else {
                    val o = hitObject(wx, wy)
                    if (o != null) {
                        if (!selection.contains(o.id)) selection = expandGroup(setOf(o.id))
                        mode = "move"
                        snapshot()
                    } else {
                        mode = "marquee"
                        marquee = RectF(wx, wy, wx, wy)
                    }
                }
            }
            else -> {
                if (tool in DRAW_TOOLS) {
                    mode = "draw"
                    startDraft(wx, wy)
                }
            }
        }
        redraw()
    }

    fun onMove(sx: Float, sy: Float) {
        if (hypot(sx - downS.x, sy - downS.y) > 6f * density) moved = true
        val wx = toWorldX(sx)
        val wy = toWorldY(sy)
        when (mode) {
            "pan" -> {
                camX = panCamX - (sx - downS.x) / zoom
                camY = panCamY - (sy - downS.y) / zoom
            }
            "laser" -> laser(wx, wy)
            "erase" -> eraseAt(wx, wy)
            "draw" -> updateDraft(wx, wy)
            "marquee" -> marquee = RectF(min(downW.x, wx), min(downW.y, wy), max(downW.x, wx), max(downW.y, wy))
            "move" -> if (moved) moveSelection(wx - downW.x, wy - downW.y)
            "resize" -> resize(wx, wy)
            "rotate" -> rotateSel(wx, wy)
        }
        if (tool != "laser" && mode != "pan") session.sendCursor(wx, wy, false)
        redraw()
    }

    fun onUp(sx: Float, sy: Float) {
        val wx = toWorldX(sx)
        val wy = toWorldY(sy)
        when (mode) {
            "draw" -> commitDraft()
            "erase" -> {
                val ids = erasing.toList()
                erasing = emptySet()
                if (ids.isNotEmpty()) {
                    val copies = ids.mapNotNull { board.objects[it]?.deepCopy() }
                    session.boardDelete(ids)
                    images.forget(ids)
                    selection = selection - ids.toSet()
                    push(BEntry(listOf(BOp.Add(copies)), listOf(BOp.Del(ids))))
                }
            }
            "marquee" -> {
                val m = marquee
                if (m != null && moved) {
                    val hit = board.objects.values.filter { RectF.intersects(m, BoardGeo.rotatedBounds(it)) }.map { it.id }.toSet()
                    selection = expandGroup(hit)
                } else if (!moved) {
                    selection = emptySet()
                }
                marquee = null
            }
            "move", "resize", "rotate" -> {
                if (moved) {
                    flushPending()
                    patchEntry(snap)
                } else if (mode == "move") {
                    // a tap on an object: second tap quickly on a text edits it
                    val o = hitObject(wx, wy)
                    val t = nowMs()
                    if (o != null && o.type == "text" && lastTapId == o.id && t - lastTapAt < 400 && !o.locked) {
                        textEdit = TextEdit(o, o.x, o.y)
                    }
                    lastTapAt = t
                    lastTapId = o?.id
                }
            }
            "text" -> if (!moved) textEdit = TextEdit(null, wx, wy)
            "laser" -> session.hideCursor()
        }
        mode = ""
        snap.clear()
        redraw()
    }

    fun cancelGesture() {
        if (mode == "draw") draft = null
        if (mode == "marquee") marquee = null
        if (mode == "erase") erasing = emptySet()
        if (mode == "move" || mode == "resize" || mode == "rotate") {
            // put things back to where the gesture started
            for ((id, o) in snap) board.objects[id]?.let { restoreGeometry(it, o) }
            board.touch()
        }
        mode = ""
        snap.clear()
        redraw()
    }

    private fun restoreGeometry(dst: BoardObj, src: BoardObj) {
        dst.points = src.points?.copyOf()
        dst.x = src.x; dst.y = src.y; dst.w = src.w; dst.h = src.h
        dst.x1 = src.x1; dst.y1 = src.y1; dst.x2 = src.x2; dst.y2 = src.y2
        dst.size = src.size; dst.rot = src.rot
    }

    private fun snapshot() {
        snap.clear()
        for (o in selected()) snap[o.id] = o.deepCopy()
    }

    /* ---------------- drawing ---------------- */

    private fun startDraft(wx: Float, wy: Float) {
        val t = tool
        val type = when (t) {
            "line" -> "line"; "arrow" -> "arrow"; "rect" -> "rect"; "ellipse" -> "ellipse"; else -> "stroke"
        }
        val o = BoardObj(Ids.id("b_"), type)
        o.color = color
        o.width = when (t) { "highlight" -> max(10f, width * 4f); "pen" -> max(3f, width); else -> width }
        o.opacity = if (t == "highlight") 0.35f else opacity
        o.dash = dash && t != "pencil" && t != "pen" && t != "highlight"
        when (type) {
            "stroke" -> {
                strokeN = 0
                addPt(wx, wy)
                o.points = strokePts.copyOf(2)
            }
            "line", "arrow" -> { o.x1 = wx; o.y1 = wy; o.x2 = wx; o.y2 = wy }
            else -> { o.x = wx; o.y = wy; o.w = 0f; o.h = 0f }
        }
        draft = o
    }

    private fun addPt(x: Float, y: Float) {
        if (strokeN * 2 + 2 > strokePts.size) strokePts = strokePts.copyOf(strokePts.size * 2)
        strokePts[strokeN * 2] = x
        strokePts[strokeN * 2 + 1] = y
        strokeN++
    }

    private fun updateDraft(wx: Float, wy: Float) {
        val o = draft ?: return
        when (o.type) {
            "stroke" -> {
                val lx = strokePts[(strokeN - 1) * 2]
                val ly = strokePts[(strokeN - 1) * 2 + 1]
                if (hypot(wx - lx, wy - ly) * zoom > 1.6f * density && strokeN < 6000) {
                    addPt(wx, wy)
                    o.points = strokePts.copyOf(strokeN * 2)
                }
            }
            "line", "arrow" -> {
                var x = wx
                var y = wy
                // hold-still snapping to 15° steps feels natural with a finger: snap when close
                val a = atan2(y - o.y1, x - o.x1)
                val step = Math.PI.toFloat() / 12f
                val snapped = Math.round(a / step) * step
                if (abs(a - snapped) < 0.05f) {
                    val d = hypot(x - o.x1, y - o.y1)
                    x = o.x1 + d * cos(snapped); y = o.y1 + d * sin(snapped)
                }
                o.x2 = x; o.y2 = y
            }
            else -> { o.w = wx - o.x; o.h = wy - o.y }
        }
        draft = o
        redraw()
    }

    private fun commitDraft() {
        val o = draft ?: return
        draft = null
        when (o.type) {
            "stroke" -> if (strokeN < 2) o.points = floatArrayOf(downW.x, downW.y)
            "line", "arrow" -> if (hypot(o.x2 - o.x1, o.y2 - o.y1) * zoom < 6f * density) return
            else -> {
                if (abs(o.w) * zoom < 6f * density && abs(o.h) * zoom < 6f * density) return
            }
        }
        if (o.type == "rect" || o.type == "ellipse") {
            val b = BoardGeo.bbox(o)
            o.x = b.left + o.width / 2f; o.y = b.top + o.width / 2f
            o.w = b.width() - o.width; o.h = b.height() - o.width
            if (o.w < 1f || o.h < 1f) { o.x = b.left; o.y = b.top; o.w = b.width(); o.h = b.height() }
        }
        session.boardAdd(o)
        push(BEntry(listOf(BOp.Del(listOf(o.id))), listOf(BOp.Add(listOf(o.deepCopy())))))
    }

    /* ---------------- erase / laser ---------------- */

    private fun eraseAt(wx: Float, wy: Float) {
        val tol = 12f * density / zoom
        val list = sorted()
        for (i in list.indices.reversed()) {
            val o = list[i]
            if (o.locked || erasing.contains(o.id)) continue
            if (BoardGeo.hit(o, wx, wy, tol)) {
                erasing = erasing + o.id
                break
            }
        }
    }

    private fun laser(wx: Float, wy: Float) {
        val l = lasers.getOrPut("me") { ArrayList() }
        l.add(LaserPoint(wx, wy, nowMs()))
        session.sendCursor(wx, wy, true)
    }

    /* ---------------- transforms ---------------- */

    private fun moveSelection(dx: Float, dy: Float) {
        for ((id, src) in snap) {
            val o = board.objects[id] ?: continue
            if (o.locked) continue
            restoreGeometry(o, src)
            BoardGeo.translate(o, dx, dy)
            queuePatch(o)
        }
        board.touch()
    }

    private fun rotateSel(wx: Float, wy: Float) {
        val src = snap.values.firstOrNull() ?: return
        val o = board.objects[src.id] ?: return
        val c = BoardGeo.center(src)
        var a = atan2(wy - c.y, wx - c.x) + Math.PI.toFloat() / 2f
        val step = Math.PI.toFloat() / 12f
        val s = Math.round(a / step) * step
        if (abs(a - s) < 0.06f) a = s
        o.rot = a
        queuePatch(o)
        board.touch()
    }

    private fun resize(wx: Float, wy: Float) {
        val sel = snap.values.toList()
        if (sel.isEmpty()) return
        if (sel.size == 1) {
            val s0 = sel[0]
            val o = board.objects[s0.id] ?: return
            if (s0.type == "line" || s0.type == "arrow") {
                restoreGeometry(o, s0)
                if (handle == 0) { o.x1 = wx; o.y1 = wy } else { o.x2 = wx; o.y2 = wy }
                queuePatch(o); board.touch()
                return
            }
            resizeSingle(o, s0, wx, wy)
            queuePatch(o); board.touch()
        } else {
            resizeMulti(sel, wx, wy)
        }
    }

    private fun resizeSingle(o: BoardObj, s0: BoardObj, wx: Float, wy: Float) {
        val b = BoardGeo.bbox(s0)
        val c0 = BoardGeo.center(s0)
        val p = BoardGeo.toLocal(s0, wx, wy)
        var l = b.left; var t = b.top; var r = b.right; var bt = b.bottom
        val h = handle
        if (h == 0 || h == 6 || h == 7) l = min(p.x, r - 4f)
        if (h == 2 || h == 3 || h == 4) r = max(p.x, l + 4f)
        if (h == 0 || h == 1 || h == 2) t = min(p.y, bt - 4f)
        if (h == 4 || h == 5 || h == 6) bt = max(p.y, t + 4f)
        var nw = r - l
        var nh = bt - t
        val corner = h == 0 || h == 2 || h == 4 || h == 6
        val ow = max(1f, b.width())
        val oh = max(1f, b.height())
        var uniform = 1f
        if ((s0.type == "image" || s0.type == "text") && corner) {
            uniform = max(nw / ow, nh / oh)
            nw = ow * uniform; nh = oh * uniform
            if (h == 0 || h == 6) l = r - nw else r = l + nw
            if (h == 0 || h == 2) t = bt - nh else bt = t + nh
        } else if (s0.type == "text") {
            return
        }
        val cLocal = PointF((l + r) / 2f, (t + bt) / 2f)
        val c1 = rotPt(cLocal, c0, s0.rot)
        restoreGeometry(o, s0)
        when (s0.type) {
            "rect", "ellipse", "image" -> { o.x = c1.x - nw / 2f; o.y = c1.y - nh / 2f; o.w = nw; o.h = nh }
            "text" -> {
                o.size = max(6f, s0.size * uniform)
                val (tw, th) = BoardGeo.measureText(o)
                o.x = c1.x - tw / 2f; o.y = c1.y - th / 2f
            }
            "stroke" -> {
                val pts = s0.points ?: return
                val sx = nw / ow; val sy = nh / oh
                val n = pts.copyOf()
                var i = 0
                while (i + 1 < n.size) {
                    n[i] = c1.x + (pts[i] - c0.x) * sx
                    n[i + 1] = c1.y + (pts[i + 1] - c0.y) * sy
                    i += 2
                }
                o.points = n
            }
        }
    }

    private fun resizeMulti(sel: List<BoardObj>, wx: Float, wy: Float) {
        val u = BoardGeo.union(sel) ?: return
        val h = handle
        var l = u.left; var t = u.top; var r = u.right; var bt = u.bottom
        if (h == 0 || h == 6 || h == 7) l = min(wx, r - 4f)
        if (h == 2 || h == 3 || h == 4) r = max(wx, l + 4f)
        if (h == 0 || h == 1 || h == 2) t = min(wy, bt - 4f)
        if (h == 4 || h == 5 || h == 6) bt = max(wy, t + 4f)
        val sx = (r - l) / max(1f, u.width())
        val sy = (bt - t) / max(1f, u.height())
        val ax = if (h == 0 || h == 6 || h == 7) u.right else u.left
        val ay = if (h == 0 || h == 1 || h == 2) u.bottom else u.top
        val nax = if (h == 0 || h == 6 || h == 7) r else l
        val nay = if (h == 0 || h == 1 || h == 2) bt else t
        fun mx(x: Float) = nax + (x - ax) * sx
        fun my(y: Float) = nay + (y - ay) * sy
        for (s in sel) {
            val o = board.objects[s.id] ?: continue
            if (o.locked) continue
            restoreGeometry(o, s)
            when (s.type) {
                "stroke" -> {
                    val p = s.points ?: continue
                    val n = p.copyOf()
                    var i = 0
                    while (i + 1 < n.size) { n[i] = mx(p[i]); n[i + 1] = my(p[i + 1]); i += 2 }
                    o.points = n
                }
                "line", "arrow" -> { o.x1 = mx(s.x1); o.y1 = my(s.y1); o.x2 = mx(s.x2); o.y2 = my(s.y2) }
                "text" -> {
                    val k = (sx + sy) / 2f
                    o.size = max(6f, s.size * k); o.x = mx(s.x); o.y = my(s.y)
                }
                else -> {
                    val x0 = mx(s.x); val x1 = mx(s.x + s.w); val y0 = my(s.y); val y1 = my(s.y + s.h)
                    o.x = x0; o.y = y0; o.w = x1 - x0; o.h = y1 - y0
                }
            }
            queuePatch(o)
        }
        board.touch()
    }

    /* ======================= selection actions ======================= */

    fun styleSelection(patch: JSONObject) {
        val sel = selected().filter { !it.locked }
        if (sel.isEmpty()) return
        val before = HashMap<String, BoardObj>()
        for (o in sel) before[o.id] = o.deepCopy()
        for (o in sel) {
            val p = JSONObject(patch.toString())
            o.applyPatch(p)
            queuePatch(o)
        }
        board.touch()
        flushPending()
        patchEntry(before)
    }

    fun duplicate() {
        val sel = selected()
        if (sel.isEmpty()) return
        val gmap = HashMap<String, String>()
        val copies = sel.map { o ->
            val c = o.deepCopy()
            val n = BoardObj(Ids.id("b_"), o.type)
            n.points = c.points; n.x = c.x; n.y = c.y; n.w = c.w; n.h = c.h; n.x1 = c.x1; n.y1 = c.y1; n.x2 = c.x2; n.y2 = c.y2
            n.text = c.text; n.size = c.size; n.src = c.src; n.color = c.color; n.width = c.width; n.opacity = c.opacity
            n.dash = c.dash; n.rot = c.rot; n.locked = false
            n.group = c.group?.let { g -> gmap.getOrPut(g) { Ids.id("g_") } }
            BoardGeo.translate(n, 24f, 24f)
            n
        }
        for (n in copies) session.boardAdd(n)
        selection = copies.map { it.id }.toSet()
        push(BEntry(listOf(BOp.Del(copies.map { it.id })), listOf(BOp.Add(copies.map { it.deepCopy() }))))
        redraw()
    }

    fun deleteSelection() {
        val sel = selected().filter { !it.locked }
        if (sel.isEmpty()) return
        val copies = sel.map { it.deepCopy() }
        val ids = sel.map { it.id }
        session.boardDelete(ids)
        images.forget(ids)
        selection = emptySet()
        push(BEntry(listOf(BOp.Add(copies)), listOf(BOp.Del(ids))))
        redraw()
    }

    fun group(on: Boolean) {
        val sel = selected()
        if (sel.size < 2 && on) return
        val before = HashMap<String, BoardObj>()
        for (o in sel) before[o.id] = o.deepCopy()
        val gid = Ids.id("g_")
        for (o in sel) {
            o.group = if (on) gid else null
            session.boardUpdate(o.id, JSONObject().put("group", if (on) gid else JSONObject.NULL))
        }
        board.touch()
        val b = HashMap<String, JSONObject>()
        val a = HashMap<String, JSONObject>()
        for (o in sel) {
            b[o.id] = JSONObject().put("group", before[o.id]?.group ?: JSONObject.NULL)
            a[o.id] = JSONObject().put("group", if (on) gid else JSONObject.NULL)
        }
        push(BEntry(listOf(BOp.Patch(b)), listOf(BOp.Patch(a))))
    }

    fun lock(on: Boolean) {
        val sel = selected()
        if (sel.isEmpty()) return
        val b = HashMap<String, JSONObject>()
        val a = HashMap<String, JSONObject>()
        for (o in sel) {
            b[o.id] = JSONObject().put("locked", if (o.locked) 1 else 0)
            a[o.id] = JSONObject().put("locked", if (on) 1 else 0)
            o.locked = on
            session.boardUpdate(o.id, JSONObject().put("locked", if (on) 1 else 0))
        }
        board.touch()
        push(BEntry(listOf(BOp.Patch(b)), listOf(BOp.Patch(a))))
    }

    fun order(front: Boolean) {
        val sel = selected().sortedBy { it.z }
        if (sel.isEmpty()) return
        val b = HashMap<String, JSONObject>()
        val a = HashMap<String, JSONObject>()
        val minZ = board.objects.values.minOfOrNull { it.z } ?: 0
        for ((i, o) in sel.withIndex()) {
            val nz = if (front) board.z + i else max(0, minZ - sel.size + i)
            b[o.id] = JSONObject().put("z", o.z)
            a[o.id] = JSONObject().put("z", nz)
            o.z = nz
            session.boardUpdate(o.id, JSONObject().put("z", nz))
        }
        if (front) board.z += sel.size
        board.touch()
        push(BEntry(listOf(BOp.Patch(b)), listOf(BOp.Patch(a))))
    }

    /* ======================= text / images ======================= */

    fun commitText(edit: TextEdit, text: String) {
        val t = text.trimEnd()
        val ex = edit.existing
        if (ex != null) {
            if (t.isEmpty()) {
                val copy = ex.deepCopy()
                session.boardDelete(listOf(ex.id))
                push(BEntry(listOf(BOp.Add(listOf(copy))), listOf(BOp.Del(listOf(ex.id)))))
            } else if (t != ex.text) {
                val before = ex.deepCopy()
                ex.text = t
                session.boardUpdate(ex.id, JSONObject().put("text", t))
                board.touch()
                patchEntry(mapOf(ex.id to before))
            }
        } else if (t.isNotEmpty()) {
            val o = BoardObj(Ids.id("b_"), "text")
            o.x = edit.wx; o.y = edit.wy; o.text = t.take(2000); o.size = textSize; o.color = color; o.opacity = opacity
            session.boardAdd(o)
            push(BEntry(listOf(BOp.Del(listOf(o.id))), listOf(BOp.Add(listOf(o.deepCopy())))))
        }
        textEdit = null
        redraw()
    }

    fun placeImage(base64: String, pxW: Int, pxH: Int) {
        val o = BoardObj(Ids.id("b_"), "image")
        val maxSide = 480f
        val k = min(1f, maxSide / max(pxW, pxH).toFloat())
        o.w = pxW * k; o.h = pxH * k
        val cx = toWorldX(vw / 2f); val cy = toWorldY(vh / 2f)
        o.x = cx - o.w / 2f; o.y = cy - o.h / 2f
        o.src = base64
        session.boardAdd(o)
        selection = setOf(o.id)
        push(BEntry(listOf(BOp.Del(listOf(o.id))), listOf(BOp.Add(listOf(o.deepCopy())))))
        tool = "select"
        redraw()
    }

    fun dispose() {
        cancelLater(flushTimer)
        flushPending()
    }
}
