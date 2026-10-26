package app.waveroom.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape

/** HoMit palette: ink on near-black, hairlines, one violet accent (ported from the web theme). */
object WR {
    /** 0 = default. Set from settings; reading these in composition makes the whole UI follow. */
    private var accentArgb by mutableIntStateOf(0)
    private var bgArgb by mutableIntStateOf(0)

    var animations by mutableStateOf(true)
    /** Duration helper: 0 when animations are off. */
    fun ms(n: Int): Int = if (animations) n else 0
    /** Humanist serif used for section labels. */
    val label: FontFamily = FontFamily(
        androidx.compose.ui.text.font.Font(app.waveroom.R.font.caladea_bolditalic, FontWeight.Bold)
    )
    val display: FontFamily = FontFamily(
        androidx.compose.ui.text.font.Font(app.waveroom.R.font.caladea_bold, FontWeight.Bold)
    )

    fun setCustom(accent: Int, bgc: Int) {
        if (accentArgb != accent) accentArgb = accent
        if (bgArgb != bgc) bgArgb = bgc
    }

    private val baseBg: Color get() = if (bgArgb == 0) Color(0xFF0A0710) else Color(bgArgb or (0xFF shl 24))
    val isLight: Boolean get() = baseBg.luminance() > 0.5f
    private fun tint(f: Float): Color = lerp(baseBg, if (isLight) Color.Black else Color.White, f)

    val bg: Color get() = baseBg
    val surface: Color get() = if (bgArgb == 0) Color(0xFF110B1C) else tint(0.03f)
    val surface2: Color get() = if (bgArgb == 0) Color(0xFF181026) else tint(0.06f)
    val surface3: Color get() = if (bgArgb == 0) Color(0xFF21173A) else tint(0.10f)
    val border: Color get() = if (bgArgb == 0) Color(0xFF2A1D45) else tint(0.14f)
    val borderStrong: Color get() = if (bgArgb == 0) Color(0xFF402E69) else tint(0.24f)
    val text: Color get() = if (isLight) Color(0xFF111114) else Color(0xFFF2EFF9)
    val muted: Color get() = if (isLight) Color(0xFF55555E) else Color(0xFFABA3C2)
    val faint: Color get() = if (isLight) Color(0xFF6E6E78) else Color(0xFF8A81A3)
    val accent: Color get() = if (accentArgb == 0) Color(0xFF7C3AED) else Color(accentArgb or (0xFF shl 24))
    val accentBright: Color get() = if (accentArgb == 0) Color(0xFFB690FF) else (if (isLight) lerp(accent, Color.Black, 0.25f) else lerp(accent, Color.White, 0.3f))
    val accentSoft: Color get() = accent.copy(alpha = 0.2f)
    val ok = Color(0xFF4BEA3A)
    /** The green of the waveform: used sparingly, for "live", "on" and "speaking". */
    val neon = Color(0xFF52FF1F)
    val neonSoft = Color(0xFFB8FF93)
    val neonDeep = Color(0xFF2BBE10)

    private val ink = Color(0xFF12032B)
    val gradTop: Color get() = lerp(accent, Color.White, 0.20f)
    val gradBottom: Color get() = lerp(accent, ink, 0.58f)
    /** Glossy violet used on primary buttons, selected pills and my chat bubbles. */
    val primaryBrush: Brush get() = Brush.verticalGradient(listOf(gradTop, gradBottom))
    val neonBrush: Brush get() = Brush.verticalGradient(listOf(Color(0xFF9CFF6B), neon, neonDeep))
    val bubbleBrush: Brush get() = Brush.linearGradient(listOf(lerp(accent, Color.White, 0.06f), lerp(accent, ink, 0.5f)))
    /** Dark at the top, glowing at the bottom, like the stat cards in the design reference. */
    val heroBrush: Brush get() = Brush.verticalGradient(listOf(lerp(accent, Color.Black, 0.86f), lerp(accent, Color.Black, 0.62f), lerp(accent, ink, 0.12f)))
    /** A thin lit edge: violet on one corner, a breath of green on the other. */
    val dockBrush: Brush get() = Brush.verticalGradient(listOf(Color(0xFF2A1D4A).copy(alpha = 0.92f), Color(0xFF150E26).copy(alpha = 0.95f)))
    val rimBrush: Brush get() = Brush.linearGradient(listOf(accentBright.copy(alpha = 0.75f), neon.copy(alpha = 0.42f), accent.copy(alpha = 0.25f)))
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

private fun scheme(): ColorScheme = (if (WR.isLight) lightColorScheme() else darkColorScheme()).copy(
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
    val st by app.waveroom.AppCore.settings.state.collectAsState()
    WR.setCustom(st.accentColor, st.bgColor)
    WR.animations = st.animations
    val view = androidx.compose.ui.platform.LocalView.current
    val light = WR.isLight
    if (!view.isInEditMode) {
        androidx.compose.runtime.SideEffect {
            val w = (view.context as? android.app.Activity)?.window
            if (w != null) {
                val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
                c.isAppearanceLightStatusBars = light
                c.isAppearanceLightNavigationBars = light
            }
        }
    }
    MaterialTheme(colorScheme = scheme(), typography = typo, shapes = shapes, content = content)
}
