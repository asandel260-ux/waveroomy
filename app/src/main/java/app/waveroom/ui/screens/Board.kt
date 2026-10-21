package app.waveroom.ui.screens

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.nowMs
import app.waveroom.core.rgbToHex
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ImageUtil
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.theme.WR
import kotlinx.coroutines.delay
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

private const val BG = 0xFF0D0D0F.toInt()
private const val ACCENT = 0xFF6E56CF.toInt()

private val TOOLS = listOf(
    "select" to "select", "hand" to "hand", "|" to "",
    "pencil" to "pencil", "pen" to "pen", "highlight" to "highlighter", "eraser" to "eraser", "|" to "",
    "line" to "line", "arrow" to "arrow", "rect" to "square", "ellipse" to "circle", "text" to "type", "image" to "image", "|" to "",
    "laser" to "laser"
)

@Composable
fun BoardPanel(modifier: Modifier = Modifier) {
    val session = AppCore.session
    val room = session.room ?: return
    val board = room.board
    var frameTick by remember { mutableIntStateOf(0) }
    val ctrl = remember(board) { BoardController(session, board) { frameTick++ } }
    val ver by board.version.collectAsState()
    val cursors by session.cursors.collectAsState()
    val ctx = LocalContext.current
    val density = LocalDensity.current.density
    ctrl.density = density
    var anim by remember { mutableIntStateOf(0) }
    var fitted by remember { mutableStateOf(false) }
    var exportOpen by remember { mutableStateOf(false) }

    DisposableEffect(ctrl) { onDispose { ctrl.dispose() } }
    LaunchedEffect(ver) {
        val alive = ctrl.selection.filter { board.objects.containsKey(it) }.toSet()
        if (alive.size != ctrl.selection.size) ctrl.selection = alive
    }
    LaunchedEffect(cursors) {
        for ((sid, c) in cursors) {
            if (c.laser) {
                val l = ctrl.lasers.getOrPut(sid) { ArrayList() }
                l.add(LaserPoint(c.x, c.y, nowMs()))
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(33)
            val t = nowMs()
            for (l in ctrl.lasers.values) l.removeAll { t - it.t > 900 }
            ctrl.lasers.entries.removeAll { it.value.isEmpty() }
            if (ctrl.lasers.isNotEmpty() || session.cursors.value.isNotEmpty()) anim++
        }
    }
    LaunchedEffect(ctrl.tool) {
        if (ctrl.tool == "image") {
            AppCore.platform?.pickImage { uri ->
                if (uri != null) {
                    Thread {
                        val r = ImageUtil.boardJpeg(ctx, uri)
                        app.waveroom.core.onMain {
                            if (r != null) ctrl.placeImage(android.util.Base64.encodeToString(r.first, android.util.Base64.NO_WRAP), r.second, r.third)
                            else AppCore.toast("Could not read that image")
                        }
                    }.also { it.isDaemon = true; it.start() }
                } else ctrl.tool = "select"
            }
            ctrl.tool = "select"
        }
    }

    val renderer = remember(ctrl) { BoardRenderer(ctrl.images) }
    val gridPaint = remember { Paint().apply { color = 0x0EEDEDEF; style = Paint.Style.FILL } }
    val selPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT; style = Paint.Style.STROKE; strokeWidth = 2f } }
    val handleFill = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); style = Paint.Style.FILL } }
    val marqueeFill = remember { Paint().apply { color = 0x226E56CF; style = Paint.Style.FILL } }
    val dotPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL } }
    val textPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt(); textSize = 11f * density } }
    val laserPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND } }

    Box(modifier.background(Color(BG))) {
        Canvas(
            Modifier.fillMaxSize()
                .onSizeChanged {
                    ctrl.vw = it.width.toFloat(); ctrl.vh = it.height.toFloat()
                    if (!fitted && it.width > 0) { fitted = true; ctrl.fitAll() }
                }
                .pointerInput(ctrl) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        ctrl.onDown(down.position.x, down.position.y)
                        var multi = false
                        var lastCx = 0f; var lastCy = 0f; var lastD = 1f
                        var last = down.position
                        while (true) {
                            val ev = awaitPointerEvent()
                            val pressed = ev.changes.filter { it.pressed }
                            if (pressed.isEmpty()) {
                                if (!multi) ctrl.onUp(last.x, last.y)
                                break
                            }
                            if (pressed.size >= 2) {
                                val a = pressed[0].position
                                val b = pressed[1].position
                                val cx = (a.x + b.x) / 2f; val cy = (a.y + b.y) / 2f
                                val d = hypot(a.x - b.x, a.y - b.y).coerceAtLeast(1f)
                                if (!multi) {
                                    multi = true
                                    ctrl.cancelGesture()
                                } else {
                                    ctrl.pinch(cx, cy, d / lastD, cx - lastCx, cy - lastCy)
                                }
                                lastCx = cx; lastCy = cy; lastD = d
                                ev.changes.forEach { it.consume() }
                            } else if (!multi) {
                                val c = pressed[0]
                                last = c.position
                                ctrl.onMove(c.position.x, c.position.y)
                                c.consume()
                            }
                        }
                    }
                }
        ) {
            @Suppress("UNUSED_VARIABLE")
            val trigger = ver + frameTick + anim + ctrl.frame
            drawIntoCanvas { cv ->
                val c = cv.nativeCanvas
                c.drawColor(BG)
                val z = ctrl.zoom
                // dot grid
                var step = 32f
                var eff = step * z
                while (eff < 22f * density) { step *= 2f; eff = step * z }
                while (eff > 88f * density) { step /= 2f; eff = step * z }
                val x0 = Math.floor((ctrl.camX / step).toDouble()).toFloat() * step
                val y0 = Math.floor((ctrl.camY / step).toDouble()).toFloat() * step
                val r = (z * density).coerceIn(0.75f * density, 1.25f * density)
                var wx = x0
                while ((wx - ctrl.camX) * z < ctrl.vw + eff) {
                    var wy = y0
                    while ((wy - ctrl.camY) * z < ctrl.vh + eff) {
                        val sx = (wx - ctrl.camX) * z; val sy = (wy - ctrl.camY) * z
                        c.drawRect(sx - r / 2, sy - r / 2, sx + r / 2, sy + r / 2, gridPaint)
                        wy += step
                    }
                    wx += step
                }
                // objects
                val save = c.save()
                c.scale(z, z)
                c.translate(-ctrl.camX, -ctrl.camY)
                renderer.drawAll(c, ctrl.sorted(), ctrl.erasing)
                ctrl.draft?.let { renderer.drawObj(c, it) }
                c.restoreToCount(save)
                drawOverlay(c, ctrl, selPaint, handleFill, marqueeFill, density)
                // other people's pointers and lasers
                val t = nowMs()
                for ((sid, cur) in session.cursors.value) {
                    if (t - cur.at > 3500 || sid == session.mySid) continue
                    dotPaint.color = android.graphics.Color.HSVToColor(floatArrayOf(cur.hue.toFloat(), 0.6f, 0.95f))
                    val sx = ctrl.toScreenX(cur.x); val sy = ctrl.toScreenY(cur.y)
                    c.drawCircle(sx, sy, 5f * density, dotPaint)
                    c.drawText(cur.name, sx + 9f * density, sy - 6f * density, textPaint)
                }
                for ((sid, trail) in ctrl.lasers) {
                    if (trail.size < 1) continue
                    val hue = if (sid == "me") 5f else (session.cursors.value[sid]?.hue ?: 0).toFloat()
                    for (i in 1 until trail.size) {
                        val a = trail[i - 1]; val b = trail[i]
                        val age = (t - b.t) / 900f
                        val alpha = ((1f - age).coerceIn(0f, 1f) * 255).toInt()
                        laserPaint.color = android.graphics.Color.HSVToColor(alpha, floatArrayOf(hue, 0.85f, 1f))
                        laserPaint.strokeWidth = (3f + 5f * (1f - age)) * density
                        c.drawLine(ctrl.toScreenX(a.x), ctrl.toScreenY(a.y), ctrl.toScreenX(b.x), ctrl.toScreenY(b.y), laserPaint)
                    }
                    val head = trail.last()
                    dotPaint.color = android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.85f, 1f))
                    c.drawCircle(ctrl.toScreenX(head.x), ctrl.toScreenY(head.y), 6f * density, dotPaint)
                }
            }
        }

        // top-right controls
        Row(
            Modifier.align(Alignment.TopEnd).padding(8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xCC101012)).border(1.dp, WR.border, RoundedCornerShape(14.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            WrIconButton("undo", { ctrl.undo() }, enabled = ctrl.canUndo, size = 40.dp, iconSize = 18.dp)
            WrIconButton("redo", { ctrl.redo() }, enabled = ctrl.canRedo, size = 40.dp, iconSize = 18.dp)
            WrIconButton("fit", { ctrl.fitAll() }, size = 40.dp, iconSize = 18.dp)
            Text("${Math.round(ctrl.zoom * 100)}%", color = WR.muted, fontSize = 11.sp, modifier = Modifier.clickable { ctrl.resetZoom() }.padding(horizontal = 6.dp))
            WrIconButton("download", { exportOpen = true }, size = 40.dp, iconSize = 18.dp)
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (ctrl.selection.isNotEmpty()) SelectionBar(ctrl)
            if (ctrl.tool in DRAW_TOOLS || ctrl.tool == "text" || ctrl.selection.isNotEmpty()) StyleBar(ctrl)
            ToolPalette(ctrl)
        }
    }

    val te = ctrl.textEdit
    if (te != null) {
        var txt by remember(te) { mutableStateOf(te.existing?.text ?: "") }
        AlertDialog(
            onDismissRequest = { ctrl.textEdit = null },
            containerColor = WR.surface2,
            title = { Text(if (te.existing != null) "Edit text" else "Add text", color = WR.text) },
            text = { WrField(txt, { txt = it }, singleLine = false, minLines = 2, maxLines = 6, placeholder = "Type here", maxLength = 2000) },
            confirmButton = { TextButton(onClick = { ctrl.commitText(te, txt) }) { Text("Done", color = WR.accentBright) } },
            dismissButton = { TextButton(onClick = { ctrl.textEdit = null }) { Text("Cancel", color = WR.muted) } }
        )
    }
    if (exportOpen) ExportSheet(ctrl) { exportOpen = false }
}

private fun drawOverlay(c: android.graphics.Canvas, ctrl: BoardController, sel: Paint, fill: Paint, marqueeFill: Paint, density: Float) {
    val selected = ctrl.selected()
    if (selected.isNotEmpty()) {
        sel.strokeWidth = 1.5f * density
        if (selected.size == 1 && (selected[0].type == "line" || selected[0].type == "arrow")) {
            // endpoints only
        } else if (selected.size == 1) {
            val cs = BoardGeo.corners(selected[0])
            val p = Path()
            for ((i, pt) in cs.withIndex()) {
                val sx = ctrl.toScreenX(pt.x); val sy = ctrl.toScreenY(pt.y)
                if (i == 0) p.moveTo(sx, sy) else p.lineTo(sx, sy)
            }
            p.close()
            c.drawPath(p, sel)
        } else {
            for (o in selected) {
                val b = BoardGeo.rotatedBounds(o)
                sel.alpha = 90
                c.drawRect(ctrl.toScreenX(b.left), ctrl.toScreenY(b.top), ctrl.toScreenX(b.right), ctrl.toScreenY(b.bottom), sel)
                sel.alpha = 255
            }
            val u = BoardGeo.union(selected)
            if (u != null) c.drawRect(ctrl.toScreenX(u.left), ctrl.toScreenY(u.top), ctrl.toScreenX(u.right), ctrl.toScreenY(u.bottom), sel)
        }
        if (selected.any { !it.locked }) {
            val hs = 6f * density
            val hp = ctrl.handlePoints()
            for (pt in hp) {
                val sx = ctrl.toScreenX(pt.x); val sy = ctrl.toScreenY(pt.y)
                c.drawRect(sx - hs, sy - hs, sx + hs, sy + hs, fill)
                c.drawRect(sx - hs, sy - hs, sx + hs, sy + hs, sel)
            }
            ctrl.rotateHandle()?.let { rh ->
                val sx = ctrl.toScreenX(rh.x); val sy = ctrl.toScreenY(rh.y)
                c.drawCircle(sx, sy, 8f * density, fill)
                c.drawCircle(sx, sy, 8f * density, sel)
            }
        } else {
            // locked: a small lock-ish marker is enough
        }
    }
    ctrl.marquee?.let { m ->
        val l = ctrl.toScreenX(m.left); val t = ctrl.toScreenY(m.top); val r = ctrl.toScreenX(m.right); val b = ctrl.toScreenY(m.bottom)
        c.drawRect(l, t, r, b, marqueeFill)
        c.drawRect(l, t, r, b, sel)
    }
}

@Composable
private fun ToolPalette(ctrl: BoardController) {
    Row(
        Modifier.padding(horizontal = 8.dp, vertical = 8.dp).clip(RoundedCornerShape(18.dp)).background(Color(0xEE101012))
            .border(1.dp, WR.border, RoundedCornerShape(18.dp)).horizontalScroll(rememberScrollState()).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for ((id, icon) in TOOLS) {
            if (id == "|") {
                Box(Modifier.padding(horizontal = 4.dp).width(1.dp).height(24.dp).background(WR.border))
            } else {
                val on = ctrl.tool == id
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(12.dp)).background(if (on) WR.accent else Color.Transparent)
                        .clickable { ctrl.tool = id; if (id != "select") ctrl.selection = emptySet() },
                    contentAlignment = Alignment.Center
                ) { WrIcon(icon, 20.dp, if (on) Color.White else WR.muted) }
            }
        }
    }
}

@Composable
private fun SelectionBar(ctrl: BoardController) {
    val sel = ctrl.selected()
    val allLocked = sel.isNotEmpty() && sel.all { it.locked }
    val grouped = sel.any { it.group != null }
    Row(
        Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE101012)).border(1.dp, WR.border, RoundedCornerShape(14.dp)).horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Muted("${sel.size} selected", size = 12, modifier = Modifier.padding(start = 12.dp, end = 4.dp))
        WrIconButton("copy", { ctrl.duplicate() }, size = 40.dp, iconSize = 18.dp)
        WrIconButton("trash", { ctrl.deleteSelection() }, size = 40.dp, iconSize = 18.dp, tint = WR.danger)
        WrIconButton(if (grouped) "ungroup" else "group", { ctrl.group(!grouped) }, size = 40.dp, iconSize = 18.dp, enabled = sel.size > 1 || grouped)
        WrIconButton(if (allLocked) "unlock" else "lock", { ctrl.lock(!allLocked) }, size = 40.dp, iconSize = 18.dp)
        WrIconButton("arrow-up", { ctrl.order(true) }, size = 40.dp, iconSize = 18.dp)
        WrIconButton("arrow-down", { ctrl.order(false) }, size = 40.dp, iconSize = 18.dp)
        WrIconButton("x", { ctrl.selection = emptySet() }, size = 40.dp, iconSize = 18.dp)
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun StyleBar(ctrl: BoardController) {
    val sel = ctrl.selected()
    val textMode = ctrl.tool == "text" || (sel.isNotEmpty() && sel.all { it.type == "text" })
    val curColor = if (sel.isNotEmpty()) sel[0].color else ctrl.color
    Row(
        Modifier.padding(horizontal = 8.dp).clip(RoundedCornerShape(14.dp)).background(Color(0xEE101012)).border(1.dp, WR.border, RoundedCornerShape(14.dp))
            .horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        for (col in BOARD_COLORS) {
            Box(
                Modifier.size(26.dp).clip(CircleShape).background(Color(0xFF000000.toInt() or col))
                    .border(if (col == curColor) 2.dp else 1.dp, if (col == curColor) Color.White else WR.borderStrong, CircleShape)
                    .clickable {
                        ctrl.color = col
                        if (sel.isNotEmpty()) ctrl.styleSelection(JSONObject().put("color", rgbToHex(col)))
                    }
            )
        }
        Box(Modifier.width(1.dp).height(22.dp).background(WR.border))
        if (textMode) {
            for (s in listOf(16f, 24f, 36f, 56f)) {
                val on = (if (sel.isNotEmpty()) sel[0].size else ctrl.textSize) == s
                Chip("${s.toInt()}", on) {
                    ctrl.textSize = s
                    if (sel.isNotEmpty()) ctrl.styleSelection(JSONObject().put("size", s.toDouble()))
                }
            }
        } else {
            for (w in listOf(2f, 4f, 7f, 12f)) {
                val on = (if (sel.isNotEmpty()) sel[0].width else ctrl.width) == w
                Chip("${w.toInt()}", on) {
                    ctrl.width = w
                    if (sel.isNotEmpty()) ctrl.styleSelection(JSONObject().put("width", w.toDouble()))
                }
            }
        }
        Box(Modifier.width(1.dp).height(22.dp).background(WR.border))
        for (o in listOf(1f, 0.6f, 0.3f)) {
            val on = abs((if (sel.isNotEmpty()) sel[0].opacity else ctrl.opacity) - o) < 0.01f
            Chip("${(o * 100).toInt()}%", on) {
                ctrl.opacity = o
                if (sel.isNotEmpty()) ctrl.styleSelection(JSONObject().put("opacity", o.toDouble()))
            }
        }
        if (!textMode) {
            val on = if (sel.isNotEmpty()) sel[0].dash else ctrl.dash
            Chip("- - -", on) {
                ctrl.dash = !on
                if (sel.isNotEmpty()) ctrl.styleSelection(JSONObject().put("dash", if (!on) 1 else 0))
            }
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) WR.accentSoft else Color.Transparent)
            .border(1.dp, if (on) WR.accent else WR.border, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)
    ) { Text(text, color = if (on) WR.accentBright else WR.muted, fontSize = 12.sp) }
}

@Composable
private fun ExportSheet(ctrl: BoardController, onDismiss: () -> Unit) {
    var scope by remember { mutableStateOf(if (ctrl.selection.isNotEmpty()) "selection" else "all") }
    fun export(fmt: String) {
        val objs = if (scope == "selection") ctrl.selected() else ctrl.sorted()
        val region = BoardExport.region(objs)
        if (region == null || objs.isEmpty()) {
            AppCore.toast("Nothing to export yet")
            return
        }
        try {
            val (bytes, ext, mime) = when (fmt) {
                "png" -> Triple(BoardExport.raster(objs, region, ctrl.images, false), "png", "image/png")
                "jpg" -> Triple(BoardExport.raster(objs, region, ctrl.images, true), "jpg", "image/jpeg")
                "svg" -> Triple(BoardExport.svg(objs, region), "svg", "image/svg+xml")
                else -> Triple(BoardExport.pdf(objs, region, ctrl.images), "pdf", "application/pdf")
            }
            AppCore.platform?.saveFile("waveroom-board.$ext", mime, bytes) { ok -> AppCore.toast(if (ok) "Saved" else "Not saved") }
            onDismiss()
        } catch (e: Throwable) {
            AppCore.toast("Export failed: ${e.message}")
        }
    }
    WrSheet(onDismiss, "Export blackboard") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Tiny("What to export")
            Spacer(Modifier.height(8.dp))
            app.waveroom.ui.components.Segmented(
                listOf("all" to "Everything", "selection" to "Selection"), scope, { if (it == "all" || ctrl.selection.isNotEmpty()) scope = it }
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in listOf("png", "jpg", "svg", "pdf")) {
                    WrButton(f.uppercase(), { export(f) }, Modifier.weight(1f), kind = BtnKind.Secondary, small = true)
                }
            }
        }
    }
}
