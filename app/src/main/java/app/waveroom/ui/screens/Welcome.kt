package app.waveroom.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.theme.WR

private data class Slide(val icon: String, val title: String, val body: String)

private val SLIDES = listOf(
    Slide("waveform", "Talk. Just talk.", "Open a room and everyone in it can speak. No stage, no seats, no audience — a host keeps things friendly."),
    Slide("wifi", "No servers. Really.", "Phones connect directly to each other with a QR code or over the same Wi-Fi. Your voice, messages and files never touch a company's computer."),
    Slide("message", "Chats, groups and calls", "Message friends, make group chats, send photos, GIFs and files, keep notes to yourself — and ring anyone you're connected to.")
)

@Composable
fun WelcomeScreen(onStart: () -> Unit) {
    var page by remember { mutableIntStateOf(0) }
    val t = rememberInfiniteTransition(label = "welcome")
    val phase by t.animateFloat(0f, 1f, infiniteRepeatable(tween(WR.ms(3600).coerceAtLeast(1), easing = LinearEasing), RepeatMode.Restart), label = "p")
    val accent = WR.accent

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF260B63), Color(0xFF14072E), WR.bg)))) {
        app.waveroom.ui.components.SilkBackground(Modifier.fillMaxSize(), animated = true)
        app.waveroom.ui.components.NeonWave(Modifier.fillMaxWidth().height(160.dp).align(Alignment.TopCenter).padding(top = 40.dp), amplitude = 42.dp, stroke = 4.dp)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(36.dp))
            Box(Modifier.height(120.dp), contentAlignment = Alignment.Center) {
                AnimatedContent(page, transitionSpec = { (fadeIn(tween(WR.ms(350))) + slideInVertically(tween(WR.ms(350))) { it / 3 }) togetherWith fadeOut(tween(WR.ms(200))) }, label = "icon") { p ->
                    Box(Modifier.size(84.dp).clip(CircleShape).background(WR.primaryBrush).border(1.5.dp, WR.rimBrush, CircleShape), contentAlignment = Alignment.Center) { WrIcon(SLIDES[p].icon, 38.dp, Color.White) }
                }
            }
            Spacer(Modifier.height(130.dp))
            Text("HoMit", color = WR.text, fontFamily = WR.display, fontWeight = FontWeight.Bold, fontSize = 44.sp)
            Spacer(Modifier.height(18.dp))
            AnimatedContent(page, transitionSpec = { (fadeIn(tween(WR.ms(320))) + slideInVertically(tween(WR.ms(320))) { it / 4 }) togetherWith fadeOut(tween(WR.ms(160))) }, label = "text") { p ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(SLIDES[p].title, color = WR.text, fontFamily = WR.display, fontWeight = FontWeight.Bold, fontSize = 24.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text(SLIDES[p].body, color = WR.muted, fontSize = 15.sp, lineHeight = 22.sp, textAlign = TextAlign.Center)
                }
            }
            Spacer(Modifier.weight(1f))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (i in SLIDES.indices) Box(Modifier.size(if (i == page) 22.dp else 8.dp, 8.dp).clip(CircleShape).background(if (i == page) WR.neon else WR.borderStrong))
            }
            Spacer(Modifier.height(22.dp))
            WrButton(if (page == SLIDES.lastIndex) "Get started" else "Next", { if (page == SLIDES.lastIndex) onStart() else page++ }, Modifier.fillMaxWidth())
            Spacer(Modifier.height(6.dp))
            if (page < SLIDES.lastIndex) Text("Skip", color = WR.muted, fontSize = 14.sp, modifier = Modifier.clickable { onStart() }.padding(12.dp))
            else Spacer(Modifier.height(40.dp))
        }
    }
}
