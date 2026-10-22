package app.waveroom.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.ui.theme.WR

/**
 * Message formatting, Telegram-style:
 *   **bold**  __italic__  ~~strike~~  `mono`  ||spoiler||  [text](url)  > quote  ```code block```
 * Links (http/https) are recognised automatically.
 */
sealed class Block {
    data class Para(val text: String) : Block()
    data class Quote(val text: String) : Block()
    data class Code(val text: String) : Block()
}

fun parseBlocks(src: String): List<Block> {
    val out = ArrayList<Block>()
    val lines = src.split('\n')
    var i = 0
    val para = StringBuilder()
    fun flush() { if (para.isNotEmpty()) { out.add(Block.Para(para.toString().trimEnd('\n'))); para.clear() } }
    while (i < lines.size) {
        val l = lines[i]
        if (l.trimStart().startsWith("```")) {
            flush()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith("```")) { body.append(lines[i]).append('\n'); i++ }
            out.add(Block.Code(body.toString().trimEnd('\n')))
            i++
            continue
        }
        if (l.startsWith(">")) {
            flush()
            val q = StringBuilder()
            while (i < lines.size && lines[i].startsWith(">")) { q.append(lines[i].removePrefix(">").trimStart()).append('\n'); i++ }
            out.add(Block.Quote(q.toString().trimEnd('\n')))
            continue
        }
        para.append(l).append('\n')
        i++
    }
    flush()
    return out
}

private val URL_RE = Regex("https?://[^\\s<>\"]+")

fun firstUrl(text: String): String? = URL_RE.find(text)?.value?.trimEnd('.', ',', ')', '!', '?')

/** Builds styled text. Spoilers are hidden until [revealed] contains their index. */
fun inlineText(src: String, color: Color, linkColor: Color, codeBg: Color, revealed: Set<Int>): AnnotatedString = buildAnnotatedString {
    var i = 0
    var spoiler = 0
    fun closeIdx(token: String, from: Int): Int = src.indexOf(token, from).let { if (it <= from) -1 else it }
    while (i < src.length) {
        when {
            src.startsWith("**", i) && closeIdx("**", i + 2) > 0 -> {
                val e = closeIdx("**", i + 2)
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(inlineText(src.substring(i + 2, e), color, linkColor, codeBg, revealed)); pop(); i = e + 2
            }
            src.startsWith("__", i) && closeIdx("__", i + 2) > 0 -> {
                val e = closeIdx("__", i + 2)
                pushStyle(SpanStyle(fontStyle = FontStyle.Italic)); append(inlineText(src.substring(i + 2, e), color, linkColor, codeBg, revealed)); pop(); i = e + 2
            }
            src.startsWith("~~", i) && closeIdx("~~", i + 2) > 0 -> {
                val e = closeIdx("~~", i + 2)
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)); append(inlineText(src.substring(i + 2, e), color, linkColor, codeBg, revealed)); pop(); i = e + 2
            }
            src.startsWith("||", i) && closeIdx("||", i + 2) > 0 -> {
                val e = closeIdx("||", i + 2)
                val idx = spoiler++
                val inner = src.substring(i + 2, e)
                if (revealed.contains(idx)) {
                    pushStyle(SpanStyle(background = codeBg)); append(inner); pop()
                } else {
                    pushStringAnnotation("spoiler", idx.toString())
                    pushStyle(SpanStyle(color = Color.Transparent, background = color.copy(alpha = 0.35f)))
                    append(inner)
                    pop(); pop()
                }
                i = e + 2
            }
            src[i] == '`' && src.indexOf('`', i + 1) > 0 -> {
                val e = src.indexOf('`', i + 1)
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBg)); append(src.substring(i + 1, e)); pop(); i = e + 1
            }
            src[i] == '[' && Regex("^\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)").find(src.substring(i)) != null -> {
                val m = Regex("^\\[([^\\]]+)]\\((https?://[^)\\s]+)\\)").find(src.substring(i))!!
                pushStringAnnotation("url", m.groupValues[2])
                pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)); append(m.groupValues[1]); pop(); pop()
                i += m.value.length
            }
            src.startsWith("http://", i) || src.startsWith("https://", i) -> {
                val m = URL_RE.find(src, i)
                if (m != null && m.range.first == i) {
                    val u = m.value.trimEnd('.', ',', ')', '!', '?')
                    pushStringAnnotation("url", u)
                    pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)); append(u); pop(); pop()
                    i += u.length
                } else { append(src[i]); i++ }
            }
            else -> { append(src[i]); i++ }
        }
    }
}

@Composable
fun RichText(text: String, color: Color, linkColor: Color, modifier: Modifier = Modifier, size: TextUnit = 15.sp) {
    var revealed by remember(text) { mutableStateOf(setOf<Int>()) }
    val blocks = remember(text) { parseBlocks(text) }
    val codeBg = color.copy(alpha = 0.14f)
    Column(modifier) {
        for (b in blocks) {
            when (b) {
                is Block.Para -> Styled(inlineText(b.text, color, linkColor, codeBg, revealed), color, size) { a, off ->
                    a.getStringAnnotations("spoiler", off, off + 1).firstOrNull()?.let { revealed = revealed + it.item.toInt(); true }
                        ?: a.getStringAnnotations("url", off, off + 1).firstOrNull()?.let { AppCore.platform?.openUrl(it.item); true } ?: false
                }
                is Block.Quote -> Row(Modifier.padding(vertical = 3.dp).height(IntrinsicSize.Min)) {
                    Box(Modifier.width(3.dp).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(linkColor))
                    Styled(inlineText(b.text, color, linkColor, codeBg, revealed), color.copy(alpha = 0.9f), size, Modifier.padding(start = 8.dp)) { a, off ->
                        a.getStringAnnotations("url", off, off + 1).firstOrNull()?.let { AppCore.platform?.openUrl(it.item); true } ?: false
                    }
                }
                is Block.Code -> Text(
                    b.text, color = color, fontFamily = FontFamily.Monospace, fontSize = (size.value - 2).sp,
                    modifier = Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp)).background(codeBg).padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun Styled(a: AnnotatedString, color: Color, size: TextUnit, modifier: Modifier = Modifier, onTap: (AnnotatedString, Int) -> Boolean) {
    var layout by remember { mutableStateOf<androidx.compose.ui.text.TextLayoutResult?>(null) }
    Text(
        a, color = color, fontSize = size,
        onTextLayout = { layout = it },
        modifier = modifier.then(
            Modifier.pointerInputTap { pos ->
                val l = layout ?: return@pointerInputTap
                val off = l.getOffsetForPosition(pos)
                onTap(a, off)
            }
        )
    )
}

private fun Modifier.pointerInputTap(onTap: (androidx.compose.ui.geometry.Offset) -> Unit): Modifier =
    this.then(Modifier.pointerInput(Unit) { detectTapGestures(onTap = onTap) })

/** Wrap or insert formatting tokens around a selection. */
fun wrapSelection(text: String, selStart: Int, selEnd: Int, open: String, close: String = open): Pair<String, IntRange> {
    val s = minOf(selStart, selEnd).coerceIn(0, text.length)
    val e = maxOf(selStart, selEnd).coerceIn(0, text.length)
    val out = text.substring(0, s) + open + text.substring(s, e) + close + text.substring(e)
    val ns = s + open.length
    return out to (ns..(ns + (e - s)))
}
