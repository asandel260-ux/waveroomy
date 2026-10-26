package app.waveroom.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.waveroom.ui.theme.WR
import kotlin.math.PI
import kotlin.math.sin

/** A soft light across the top half: the "glass" look on buttons and cards. */
fun Modifier.gloss(strength: Float = 0.18f): Modifier = this.drawBehind {
    drawRect(Brush.verticalGradient(listOf(Color.White.copy(alpha = strength), Color.Transparent), endY = size.height * 0.55f))
}

private fun DrawScope.ribbon(y0: Float, amp: Float, thick: Float, drift: Float, brush: Brush) {
    val w = size.width
    val h = size.height
    val dy = drift * amp * 0.5f * h
    val p = Path()
    p.moveTo(-0.1f * w, y0 * h + dy)
    p.cubicTo(0.25f * w, (y0 - amp) * h - dy, 0.6f * w, (y0 + amp) * h + dy, 1.1f * w, (y0 - amp * 0.4f) * h)
    p.lineTo(1.1f * w, (y0 - amp * 0.4f + thick) * h)
    p.cubicTo(0.6f * w, (y0 + amp + thick) * h + dy, 0.25f * w, (y0 - amp + thick) * h - dy, -0.1f * w, (y0 + thick) * h + dy)
    p.close()
    drawPath(p, brush)
}

/** Translucent silk ribbons with one thin line of neon, drawn behind hero content. */
@Composable
fun SilkBackground(modifier: Modifier = Modifier, animated: Boolean = false) {
    val d: Float = if (animated) {
        val t = rememberInfiniteTransition(label = "silk")
        val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Reverse), label = "p")
        phase - 0.5f
    } else 0f
    Canvas(modifier) { drawSilk(d, 1f) }
}

/** The ribbons themselves; [k] scales their strength so the same art can sit quietly behind whole screens. */
fun DrawScope.drawSilk(d: Float, k: Float) {
    val a = WR.accentBright
    val b = WR.accent
    ribbon(0.30f, 0.30f, 0.34f, d, Brush.linearGradient(listOf(a.copy(alpha = 0.00f), b.copy(alpha = 0.30f * k), a.copy(alpha = 0.06f * k)), Offset(0f, 0f), Offset(size.width, size.height)))
    ribbon(0.62f, 0.26f, 0.22f, -d, Brush.linearGradient(listOf(b.copy(alpha = 0.34f * k), a.copy(alpha = 0.10f * k), Color.Transparent), Offset(size.width, 0f), Offset(0f, size.height)))
    ribbon(0.82f, 0.18f, 0.16f, d * 0.7f, Brush.linearGradient(listOf(Color.Transparent, WR.neon.copy(alpha = 0.10f * k), b.copy(alpha = 0.22f * k)), Offset(0f, size.height), Offset(size.width, 0f)))
    // the one bright thread
    val w = size.width
    val h = size.height
    val line = Path()
    line.moveTo(-0.1f * w, 0.62f * h + d * 0.26f * h * 0.5f)
    line.cubicTo(0.25f * w, 0.36f * h, 0.6f * w, 0.88f * h, 1.1f * w, 0.52f * h)
    drawPath(line, WR.neon.copy(alpha = 0.22f * k), style = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round))
    drawPath(line, WR.neonSoft.copy(alpha = 0.55f * k), style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
}

/** The ground every full screen stands on: violet at the top fading to the base colour, with quiet silk. */
fun Modifier.screenBackdrop(): Modifier = this
    .background(Brush.verticalGradient(listOf(lerp(WR.bg, WR.accent, 0.22f), lerp(WR.bg, WR.accent, 0.07f), WR.bg)))
    .drawBehind { drawSilk(0f, 0.34f) }

/** A hand-drawn-feeling neon sine, like the waveform of a voice. */
@Composable
fun NeonWave(modifier: Modifier = Modifier, amplitude: Dp = 26.dp, stroke: Dp = 4.dp, animated: Boolean = true) {
    val phase: Float = if (animated) {
        val t = rememberInfiniteTransition(label = "wave")
        val ph by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(4200, easing = LinearEasing), RepeatMode.Restart), label = "ph")
        ph
    } else 1.2f
    Canvas(modifier) {
        val w = size.width
        val cy = size.height / 2f
        val amp = amplitude.toPx().coerceAtMost(size.height / 2f - stroke.toPx())
        val path = Path()
        var x = 0f
        while (x <= w) {
            val u = x / w
            val env = (sin(u * PI) * (0.55 + 0.45 * sin(u * 3.0 * PI + phase * 0.5))).toFloat()
            val y = cy + amp * env * sin(u * 5.0 * PI + phase).toFloat()
            if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
            x += 3f
        }
        drawPath(path, WR.neon.copy(alpha = 0.10f), style = Stroke(width = stroke.toPx() * 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, WR.neon.copy(alpha = 0.22f), style = Stroke(width = stroke.toPx() * 2.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, WR.neon, style = Stroke(width = stroke.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A big rounded card with a dark-to-glowing violet gradient, silk, and a lit edge. */
@Composable
fun HeroCard(modifier: Modifier = Modifier, radius: Dp = 22.dp, animated: Boolean = false, content: @Composable BoxScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).background(WR.heroBrush).border(1.dp, WR.rimBrush, shape)) {
        SilkBackground(Modifier.matchParentSize(), animated)
        content()
    }
}

