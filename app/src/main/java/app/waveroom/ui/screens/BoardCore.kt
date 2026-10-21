package app.waveroom.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.Base64
import app.waveroom.core.BoardObj
import app.waveroom.core.mainHandler
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/* ---------------------------------------------------------------------------
 * Geometry shared by the on-screen board and the exporters
 * ------------------------------------------------------------------------- */

object BoardGeo {
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG)

    fun textLines(o: BoardObj): List<String> = o.text.split("\n")

    fun textSize(o: BoardObj): Float = if (o.size <= 0f) 18f else o.size

    fun measureText(o: BoardObj): Pair<Float, Float> {
        val size = textSize(o)
        tp.textSize = size
        var w = 0f
        for (l in textLines(o)) w = max(w, tp.measureText(l))
        return Pair(w + 4f, textLines(o).size * size * 1.35f + 4f)
    }

    /** Un-rotated bounding box in world coordinates. */
    fun bbox(o: BoardObj): RectF {
        when (o.type) {
            "stroke" -> {
                val p = o.points
                if (p == null || p.size < 2) return RectF(0f, 0f, 0f, 0f)
                var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE
                var i = 0
                while (i + 1 < p.size) {
                    x0 = min(x0, p[i]); x1 = max(x1, p[i]); y0 = min(y0, p[i + 1]); y1 = max(y1, p[i + 1]); i += 2
                }
                val pad = o.width / 2f
                return RectF(x0 - pad, y0 - pad, x1 + pad, y1 + pad)
            }
            "line", "arrow" -> {
                val pad = o.width / 2f
                return RectF(min(o.x1, o.x2) - pad, min(o.y1, o.y2) - pad, max(o.x1, o.x2) + pad, max(o.y1, o.y2) + pad)
            }
            "text" -> {
                val (w, h) = measureText(o)
                return RectF(o.x, o.y, o.x + w, o.y + h)
            }
            else -> return RectF(min(o.x, o.x + o.w), min(o.y, o.y + o.h), max(o.x, o.x + o.w), max(o.y, o.y + o.h))
        }
    }

    fun center(o: BoardObj): PointF {
        val b = bbox(o)
        return PointF((b.left + b.right) / 2f, (b.top + b.bottom) / 2f)
    }

    private fun rot(px: Float, py: Float, cx: Float, cy: Float, a: Float): PointF {
        val c = cos(a); val s = sin(a)
        val dx = px - cx; val dy = py - cy
        return PointF(cx + dx * c - dy * s, cy + dx * s + dy * c)
    }

    fun toLocal(o: BoardObj, wx: Float, wy: Float): PointF {
        if (o.rot == 0f) return PointF(wx, wy)
        val c = center(o)
        return rot(wx, wy, c.x, c.y, -o.rot)
    }

    fun corners(o: BoardObj): List<PointF> {
        val b = bbox(o)
        val c = center(o)
        val pts = listOf(PointF(b.left, b.top), PointF(b.right, b.top), PointF(b.right, b.bottom), PointF(b.left, b.bottom))
        return if (o.rot == 0f) pts else pts.map { rot(it.x, it.y, c.x, c.y, o.rot) }
    }

    fun rotatedBounds(o: BoardObj): RectF {
        val cs = corners(o)
        return RectF(cs.minOf { it.x }, cs.minOf { it.y }, cs.maxOf { it.x }, cs.maxOf { it.y })
    }

    fun union(list: Collection<BoardObj>): RectF? {
        var r: RectF? = null
        for (o in list) {
            val b = rotatedBounds(o)
            if (r == null) r = RectF(b) else r.union(b)
        }
        return r
    }

    fun segDist(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1; val dy = y2 - y1
        val l2 = dx * dx + dy * dy
        var t = if (l2 == 0f) 0f else ((px - x1) * dx + (py - y1) * dy) / l2
        t = max(0f, min(1f, t))
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    fun hit(o: BoardObj, wx: Float, wy: Float, tol: Float): Boolean {
        val l = toLocal(o, wx, wy)
        val x = l.x; val y = l.y
        when (o.type) {
            "stroke" -> {
                val p = o.points ?: return false
                val t = max(tol, o.width / 2f + tol * 0.5f)
                if (p.size == 2) return hypot(x - p[0], y - p[1]) <= t
                var i = 0
                while (i + 3 < p.size) {
                    if (segDist(x, y, p[i], p[i + 1], p[i + 2], p[i + 3]) <= t) return true
                    i += 2
                }
                return false
            }
            "line", "arrow" -> return segDist(x, y, o.x1, o.y1, o.x2, o.y2) <= max(tol, o.width / 2f + tol * 0.5f)
            else -> {
                val b = bbox(o)
                return x >= b.left - tol * 0.4f && x <= b.right + tol * 0.4f && y >= b.top - tol * 0.4f && y <= b.bottom + tol * 0.4f
            }
        }
    }

    fun translate(o: BoardObj, dx: Float, dy: Float) {
        when (o.type) {
            "stroke" -> {
                val p = o.points ?: return
                val n = p.copyOf()
                var i = 0
                while (i + 1 < n.size) { n[i] += dx; n[i + 1] += dy; i += 2 }
                o.points = n
            }
            "line", "arrow" -> { o.x1 += dx; o.y1 += dy; o.x2 += dx; o.y2 += dy }
            else -> { o.x += dx; o.y += dy }
        }
    }

    fun angleOf(x1: Float, y1: Float, x2: Float, y2: Float) = atan2(y2 - y1, x2 - x1)
}

/* ---------------------------------------------------------------------------
 * Image cache (decode off the main thread)
 * ------------------------------------------------------------------------- */

class BoardImages(private val onLoaded: () -> Unit) {
    private val map = HashMap<String, Bitmap>()
    private val pending = HashSet<String>()

    fun get(o: BoardObj): Bitmap? {
        map[o.id]?.let { return it }
        val src = o.src ?: return null
        if (src.isEmpty() || !pending.add(o.id)) return null
        val id = o.id
        Thread {
            try {
                val bytes = Base64.decode(src, Base64.DEFAULT)
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                var sample = 1
                while (max(opts.outWidth, opts.outHeight) / sample > 2200) sample *= 2
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                mainHandler.post {
                    if (bmp != null) map[id] = bmp
                    pending.remove(id)
                    onLoaded()
                }
            } catch (e: Throwable) {
                mainHandler.post { pending.remove(id) }
            }
        }.also { it.isDaemon = true; it.start() }
        return null
    }

    fun forget(ids: Collection<String>) {
        for (i in ids) map.remove(i)
    }
}

/* ---------------------------------------------------------------------------
 * Drawing
 * ------------------------------------------------------------------------- */

class BoardRenderer(private val images: BoardImages) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pathCache = HashMap<String, Triple<Int, Int, Path>>()

    private fun strokePaint(o: BoardObj) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        paint.color = 0xFF000000.toInt() or o.color
        paint.alpha = (o.opacity.coerceIn(0f, 1f) * 255).toInt()
        paint.strokeWidth = max(0.5f, o.width)
        if (o.dash) {
            val d = max(4f, o.width * 2.5f)
            paint.pathEffect = DashPathEffect(floatArrayOf(d, d), 0f)
        }
    }

    private fun strokePath(o: BoardObj): Path? {
        val p = o.points ?: return null
        val key = System.identityHashCode(p)
        val c = pathCache[o.id]
        if (c != null && c.first == key && c.second == p.size) return c.third
        val path = Path()
        val n = p.size / 2
        if (n >= 2) {
            path.moveTo(p[0], p[1])
            for (i in 1 until n - 1) {
                val mx = (p[i * 2] + p[i * 2 + 2]) / 2f
                val my = (p[i * 2 + 1] + p[i * 2 + 3]) / 2f
                path.quadTo(p[i * 2], p[i * 2 + 1], mx, my)
            }
            path.lineTo(p[(n - 1) * 2], p[(n - 1) * 2 + 1])
        }
        pathCache[o.id] = Triple(key, p.size, path)
        if (pathCache.size > 3000) pathCache.clear()
        return path
    }

    fun drawObj(c: Canvas, o: BoardObj) {
        val save = c.save()
        if (o.rot != 0f) {
            val ct = BoardGeo.center(o)
            c.rotate(Math.toDegrees(o.rot.toDouble()).toFloat(), ct.x, ct.y)
        }
        when (o.type) {
            "stroke" -> {
                val p = o.points
                if (p != null && p.size >= 2) {
                    if (p.size < 4) {
                        fill.reset(); fill.isAntiAlias = true
                        fill.color = 0xFF000000.toInt() or o.color
                        fill.alpha = (o.opacity.coerceIn(0f, 1f) * 255).toInt()
                        c.drawCircle(p[0], p[1], max(1f, o.width / 2f), fill)
                    } else {
                        strokePaint(o)
                        strokePath(o)?.let { c.drawPath(it, paint) }
                    }
                }
            }
            "line", "arrow" -> {
                strokePaint(o)
                c.drawLine(o.x1, o.y1, o.x2, o.y2, paint)
                if (o.type == "arrow") {
                    val ang = atan2(o.y2 - o.y1, o.x2 - o.x1)
                    val hl = max(10f, o.width * 3.2f)
                    paint.pathEffect = null
                    c.drawLine(o.x2, o.y2, o.x2 - hl * cos(ang - 0.44f), o.y2 - hl * sin(ang - 0.44f), paint)
                    c.drawLine(o.x2, o.y2, o.x2 - hl * cos(ang + 0.44f), o.y2 - hl * sin(ang + 0.44f), paint)
                }
            }
            "rect" -> {
                strokePaint(o)
                val b = BoardGeo.bbox(o)
                c.drawRect(b.left + o.width / 2f, b.top + o.width / 2f, b.right - o.width / 2f, b.bottom - o.width / 2f, paint)
            }
            "ellipse" -> {
                strokePaint(o)
                val b = BoardGeo.bbox(o)
                c.drawOval(RectF(b.left + o.width / 2f, b.top + o.width / 2f, b.right - o.width / 2f, b.bottom - o.width / 2f), paint)
            }
            "text" -> {
                val size = BoardGeo.textSize(o)
                fill.reset(); fill.isAntiAlias = true
                fill.color = 0xFF000000.toInt() or o.color
                fill.alpha = (o.opacity.coerceIn(0f, 1f) * 255).toInt()
                fill.textSize = size
                fill.isFakeBoldText = false
                val lines = BoardGeo.textLines(o)
                for ((i, l) in lines.withIndex()) c.drawText(l, o.x + 2f, o.y + 2f + i * size * 1.35f - fill.ascent(), fill)
            }
            "image" -> {
                val b = BoardGeo.bbox(o)
                val bmp = images.get(o)
                if (bmp != null) {
                    fill.reset(); fill.isAntiAlias = true; fill.isFilterBitmap = true
                    fill.alpha = (o.opacity.coerceIn(0f, 1f) * 255).toInt()
                    c.drawBitmap(bmp, null, b, fill)
                } else {
                    paint.reset(); paint.style = Paint.Style.STROKE; paint.color = 0xFF37373F.toInt(); paint.strokeWidth = 1f
                    paint.pathEffect = DashPathEffect(floatArrayOf(4f, 4f), 0f)
                    c.drawRect(b, paint)
                }
            }
        }
        c.restoreToCount(save)
    }

    fun drawAll(c: Canvas, objs: List<BoardObj>, skip: Set<String> = emptySet()) {
        for (o in objs) if (o.id !in skip) drawObj(c, o)
    }
}

/* ---------------------------------------------------------------------------
 * Export: PNG / JPG / SVG / PDF
 * ------------------------------------------------------------------------- */

object BoardExport {
    const val BG = 0xFF0D0D0F.toInt()

    fun region(objs: Collection<BoardObj>, pad: Float = 40f): RectF? {
        val u = BoardGeo.union(objs) ?: return null
        return RectF(u.left - pad, u.top - pad, u.right + pad, u.bottom + pad)
    }

    fun raster(objs: List<BoardObj>, region: RectF, images: BoardImages, jpeg: Boolean): ByteArray {
        val maxSide = 4096f
        val scale = min(2f, maxSide / max(region.width(), region.height()))
        val w = max(1, (region.width() * scale).toInt())
        val h = max(1, (region.height() * scale).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(BG)
        c.scale(scale, scale)
        c.translate(-region.left, -region.top)
        BoardRenderer(images).drawAll(c, objs)
        val bos = ByteArrayOutputStream()
        bmp.compress(if (jpeg) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 92, bos)
        bmp.recycle()
        return bos.toByteArray()
    }

    fun pdf(objs: List<BoardObj>, region: RectF, images: BoardImages): ByteArray {
        val doc = android.graphics.pdf.PdfDocument()
        val scale = min(1f, 2000f / max(region.width(), region.height()))
        val w = max(1, (region.width() * scale).toInt())
        val h = max(1, (region.height() * scale).toInt())
        val page = doc.startPage(android.graphics.pdf.PdfDocument.PageInfo.Builder(w, h, 1).create())
        val c = page.canvas
        c.drawColor(BG)
        c.scale(scale, scale)
        c.translate(-region.left, -region.top)
        BoardRenderer(images).drawAll(c, objs)
        doc.finishPage(page)
        val bos = ByteArrayOutputStream()
        doc.writeTo(bos)
        doc.close()
        return bos.toByteArray()
    }

    private fun hex(c: Int) = "#%06x".format(c and 0xFFFFFF)
    private fun f(v: Float) = "%.1f".format(java.util.Locale.US, v)
    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    fun svg(objs: List<BoardObj>, region: RectF): ByteArray {
        val sb = StringBuilder()
        sb.append("""<svg xmlns="http://www.w3.org/2000/svg" width="${f(region.width())}" height="${f(region.height())}" viewBox="${f(region.left)} ${f(region.top)} ${f(region.width())} ${f(region.height())}">""")
        sb.append("""<rect x="${f(region.left)}" y="${f(region.top)}" width="${f(region.width())}" height="${f(region.height())}" fill="#0d0d0f"/>""")
        for (o in objs) {
            val c = BoardGeo.center(o)
            val tr = if (o.rot != 0f) """ transform="rotate(${f(Math.toDegrees(o.rot.toDouble()).toFloat())} ${f(c.x)} ${f(c.y)})"""" else ""
            val st = """stroke="${hex(o.color)}" stroke-width="${f(o.width)}" stroke-opacity="${o.opacity}" stroke-linecap="round" stroke-linejoin="round" fill="none"""" +
                (if (o.dash) """ stroke-dasharray="${f(max(4f, o.width * 2.5f))}"""" else "")
            when (o.type) {
                "stroke" -> {
                    val p = o.points ?: continue
                    if (p.size < 4) {
                        sb.append("""<circle cx="${f(p[0])}" cy="${f(p[1])}" r="${f(o.width / 2)}" fill="${hex(o.color)}" fill-opacity="${o.opacity}"$tr/>""")
                    } else {
                        val d = StringBuilder("M${f(p[0])} ${f(p[1])}")
                        val n = p.size / 2
                        for (i in 1 until n - 1) {
                            d.append(" Q${f(p[i * 2])} ${f(p[i * 2 + 1])} ${f((p[i * 2] + p[i * 2 + 2]) / 2)} ${f((p[i * 2 + 1] + p[i * 2 + 3]) / 2)}")
                        }
                        d.append(" L${f(p[(n - 1) * 2])} ${f(p[(n - 1) * 2 + 1])}")
                        sb.append("""<path d="$d" $st$tr/>""")
                    }
                }
                "line", "arrow" -> {
                    sb.append("""<g$tr><line x1="${f(o.x1)}" y1="${f(o.y1)}" x2="${f(o.x2)}" y2="${f(o.y2)}" $st/>""")
                    if (o.type == "arrow") {
                        val ang = atan2(o.y2 - o.y1, o.x2 - o.x1)
                        val hl = max(10f, o.width * 3.2f)
                        sb.append("""<path d="M${f(o.x2 - hl * cos(ang - 0.44f))} ${f(o.y2 - hl * sin(ang - 0.44f))} L${f(o.x2)} ${f(o.y2)} L${f(o.x2 - hl * cos(ang + 0.44f))} ${f(o.y2 - hl * sin(ang + 0.44f))}" $st/>""")
                    }
                    sb.append("</g>")
                }
                "rect" -> {
                    val b = BoardGeo.bbox(o)
                    sb.append("""<rect x="${f(b.left)}" y="${f(b.top)}" width="${f(b.width())}" height="${f(b.height())}" $st$tr/>""")
                }
                "ellipse" -> {
                    val b = BoardGeo.bbox(o)
                    sb.append("""<ellipse cx="${f(b.centerX())}" cy="${f(b.centerY())}" rx="${f(b.width() / 2)}" ry="${f(b.height() / 2)}" $st$tr/>""")
                }
                "text" -> {
                    val size = BoardGeo.textSize(o)
                    sb.append("""<text font-family="sans-serif" font-size="${f(size)}" fill="${hex(o.color)}" fill-opacity="${o.opacity}"$tr>""")
                    for ((i, l) in BoardGeo.textLines(o).withIndex()) {
                        sb.append("""<tspan x="${f(o.x + 2)}" y="${f(o.y + 2 + size + i * size * 1.35f - size * 0.2f)}">${esc(l)}</tspan>""")
                    }
                    sb.append("</text>")
                }
                "image" -> {
                    val b = BoardGeo.bbox(o)
                    sb.append("""<image x="${f(b.left)}" y="${f(b.top)}" width="${f(b.width())}" height="${f(b.height())}" opacity="${o.opacity}" href="data:image/jpeg;base64,${o.src ?: ""}"$tr/>""")
                }
            }
        }
        sb.append("</svg>")
        return sb.toString().toByteArray(Charsets.UTF_8)
    }
}
