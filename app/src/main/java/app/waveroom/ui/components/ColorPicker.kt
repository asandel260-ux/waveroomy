package app.waveroom.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.ui.theme.WR

private fun hsv(h: Float, s: Float, v: Float): Int = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v)) or (0xFF shl 24)

private fun toHsv(argb: Int): FloatArray {
    val out = FloatArray(3)
    android.graphics.Color.colorToHSV(argb, out)
    return out
}

private fun hexOf(argb: Int): String = String.format("#%06X", argb and 0xFFFFFF)

private fun parseHex(s: String): Int? {
    val t = s.trim().removePrefix("#")
    if (t.length != 6) return null
    return t.toIntOrNull(16)?.let { it or (0xFF shl 24) }
}

/**
 * Preset swatches + a custom HSV picker. [value] is ARGB, 0 meaning "default".
 * [maxBrightness] lets the background picker offer light themes too.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPickerCard(
    title: String,
    subtitle: String,
    value: Int,
    default: Color,
    presets: List<Int>,
    onChange: (Int) -> Unit
) {
    var custom by remember { mutableStateOf(false) }
    val shown = if (value == 0) default.toArgb() else value
    val hsvNow = toHsv(shown)
    var h by remember { mutableStateOf(hsvNow[0]) }
    var sat by remember { mutableStateOf(hsvNow[1]) }
    var v by remember { mutableStateOf(hsvNow[2]) }
    var hex by remember(shown) { mutableStateOf(hexOf(shown)) }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WR.surface).border(1.dp, WR.border, RoundedCornerShape(12.dp)).padding(14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = WR.text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Muted(subtitle, size = 12)
            }
            Box(Modifier.size(30.dp).clip(CircleShape).background(Color(shown)).border(1.dp, WR.borderStrong, CircleShape))
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            // default swatch
            Swatch(default, value == 0) { onChange(0); toHsv(default.toArgb()).let { h = it[0]; sat = it[1]; v = it[2] } }
            for (p in presets) Swatch(Color(p or (0xFF shl 24)), value != 0 && (value and 0xFFFFFF) == (p and 0xFFFFFF)) { onChange(p or (0xFF shl 24)); toHsv(p).let { h = it[0]; sat = it[1]; v = it[2] } }
            Box(
                Modifier.size(34.dp).clip(CircleShape).border(1.dp, if (custom) WR.accentBright else WR.borderStrong, CircleShape).clickable { custom = !custom },
                contentAlignment = Alignment.Center
            ) { WrIcon("sliders", 16.dp, WR.text) }
        }
        if (custom) {
            Spacer(Modifier.height(10.dp))
            val colors = SliderDefaults.colors(thumbColor = Color(shown), activeTrackColor = WR.accent, inactiveTrackColor = WR.surface3)
            Tiny("Hue")
            Slider(h, { h = it; onChange(hsv(h, sat, v)) }, valueRange = 0f..360f, colors = colors)
            Tiny("Saturation")
            Slider(sat, { sat = it; onChange(hsv(h, sat, v)) }, valueRange = 0f..1f, colors = colors)
            Tiny("Brightness")
            Slider(v, { v = it; onChange(hsv(h, sat, v)) }, valueRange = 0f..1f, colors = colors)
            Row(verticalAlignment = Alignment.CenterVertically) {
                WrField(hex, { hex = it; parseHex(it)?.let(onChange) }, Modifier.weight(1f), placeholder = "#RRGGBB", mono = true, maxLength = 7)
                Spacer(Modifier.width(8.dp))
                WrButton("Reset", { onChange(0) }, kind = BtnKind.Ghost, small = true)
            }
        } else if (value != 0) {
            Spacer(Modifier.height(10.dp))
            WrButton("Reset to default", { onChange(0) }, kind = BtnKind.Ghost, small = true)
        }
    }
}

@Composable
private fun Swatch(c: Color, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(c)
            .border(if (selected) 2.dp else 1.dp, if (selected) WR.text else WR.borderStrong, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { if (selected) WrIcon("check", 16.dp, if (c.red * 0.3f + c.green * 0.6f + c.blue * 0.1f > 0.55f) Color.Black else Color.White) }
}

val ACCENT_PRESETS = listOf(0xFF3B82F6.toInt(), 0xFF06B6D4.toInt(), 0xFF10B981.toInt(), 0xFFF59E0B.toInt(), 0xFFEF4444.toInt(), 0xFFEC4899.toInt(), 0xFFA855F7.toInt(), 0xFFFAFAFA.toInt())
val BG_PRESETS = listOf(0xFF000000.toInt(), 0xFF0B1220.toInt(), 0xFF0A1A14.toInt(), 0xFF1A0B14.toInt(), 0xFF1B1B1F.toInt(), 0xFF2B2B33.toInt(), 0xFFF4F4F6.toInt())
