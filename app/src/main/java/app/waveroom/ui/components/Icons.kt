package app.waveroom.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

private val cache = HashMap<String, ImageVector>()

fun iconVector(name: String): ImageVector {
    cache[name]?.let { return it }
    val b = ImageVector.Builder(
        name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f
    )
    val paths = ICON_PATHS[name] ?: ICON_PATHS["info"] ?: emptyList()
    for (d in paths) {
        try {
            b.addPath(
                pathData = PathParser().parsePathString(d).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round
            )
        } catch (e: Exception) {
        }
    }
    val v = b.build()
    cache[name] = v
    return v
}

/** Lucide icon from the web app, drawn natively. */
@Composable
fun WrIcon(name: String, size: Dp = 20.dp, tint: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Icon(imageVector = iconVector(name), contentDescription = null, tint = tint, modifier = modifier.size(size))
}
