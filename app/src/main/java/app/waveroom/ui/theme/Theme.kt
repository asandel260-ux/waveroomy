package app.waveroom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** Waveroom palette: ink on near-black, hairlines, one violet accent (ported from the web theme). */
object WR {
    val bg = Color(0xFF0A0A0B)
    val surface = Color(0xFF101012)
    val surface2 = Color(0xFF16161A)
    val surface3 = Color(0xFF1D1D22)
    val border = Color(0xFF232328)
    val borderStrong = Color(0xFF37373F)
    val text = Color(0xFFEDEDEF)
    val muted = Color(0xFF9A9AA3)
    val faint = Color(0xFF616169)
    val accent = Color(0xFF6E56CF)
    val accentBright = Color(0xFF9B84EE)
    val accentSoft = Color(0x336E56CF)
    val ok = Color(0xFF3DD68C)
    val warn = Color(0xFFF5B544)
    val danger = Color(0xFFE5484D)
    val dangerSoft = Color(0x26E5484D)
    val mono = FontFamily.Monospace

    fun hue(h: Int, s: Float = 0.45f, l: Float = 0.32f): Color = hsl(h.toFloat(), s, l)

    fun hsl(h: Float, s: Float, l: Float): Color {
        val c = (1f - kotlin.math.abs(2f * l - 1f)) * s
        val hp = ((h % 360f) + 360f) % 360f / 60f
        val x = c * (1f - kotlin.math.abs(hp % 2f - 1f))
        val (r1, g1, b1) = when {
            hp < 1f -> Triple(c, x, 0f)
            hp < 2f -> Triple(x, c, 0f)
            hp < 3f -> Triple(0f, c, x)
            hp < 4f -> Triple(0f, x, c)
            hp < 5f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = l - c / 2f
        return Color(r1 + m, g1 + m, b1 + m)
    }
}

private val scheme: ColorScheme = darkColorScheme(
    primary = WR.accent,
    onPrimary = Color.White,
    primaryContainer = WR.accentSoft,
    onPrimaryContainer = WR.accentBright,
    secondary = WR.accentBright,
    background = WR.bg,
    onBackground = WR.text,
    surface = WR.surface,
    onSurface = WR.text,
    surfaceVariant = WR.surface2,
    onSurfaceVariant = WR.muted,
    surfaceContainer = WR.surface,
    surfaceContainerHigh = WR.surface2,
    surfaceContainerHighest = WR.surface3,
    surfaceContainerLow = WR.surface,
    surfaceContainerLowest = WR.bg,
    outline = WR.borderStrong,
    outlineVariant = WR.border,
    error = WR.danger,
    onError = Color.White,
    scrim = Color(0xCC000000)
)

private val typo = Typography(
    displayLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-1.4).sp),
    displayMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 34.sp, letterSpacing = (-1.1).sp),
    headlineLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 32.sp, letterSpacing = (-0.8).sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 28.sp, letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 18.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.sp)
)

private val shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(14.dp),
    extraLarge = RoundedCornerShape(20.dp)
)

@Composable
fun WaveroomTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, typography = typo, shapes = shapes, content = content)
}
