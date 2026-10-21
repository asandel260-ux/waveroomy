package app.waveroom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.waveroom.AppCore
import app.waveroom.core.Participant
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Segmented
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.theme.WR
import kotlinx.coroutines.delay
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/** Shows somebody's shared screen (or the controls, if it is ours). */
@Composable
fun StreamPanel(p: Participant, modifier: Modifier = Modifier) {
    val session = AppCore.session
    val mine = p.sid == session.mySid
    if (mine) {
        SharerPanel(p, modifier)
        return
    }
    var track by remember(p.sid, p.sharing?.at) { mutableStateOf<VideoTrack?>(null) }
    var hasFrame by remember(p.sid, p.sharing?.at) { mutableStateOf(false) }
    LaunchedEffect(p.sid, p.sharing?.at) {
        var tries = 0
        while (track == null && tries < 120) {
            track = session.linkTo(p.sid)?.remoteVideo()
            if (track == null) delay(500)
            tries++
        }
    }
    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val t = track
        if (t != null) {
            val view = remember(t) { arrayOfNulls<SurfaceViewRenderer>(1) }
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val r = SurfaceViewRenderer(ctx)
                    r.init(AppCore.rtc.egl.eglBaseContext, object : RendererCommon.RendererEvents {
                        override fun onFirstFrameRendered() {
                            app.waveroom.core.onMain { hasFrame = true }
                        }

                        override fun onFrameResolutionChanged(videoWidth: Int, videoHeight: Int, rotation: Int) {}
                    })
                    r.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                    r.setEnableHardwareScaler(true)
                    t.addSink(r)
                    view[0] = r
                    r
                }
            )
            DisposableEffect(t) {
                onDispose {
                    try {
                        view[0]?.let { t.removeSink(it); it.release() }
                    } catch (e: Throwable) {
                    }
                }
            }
        }
        if (!hasFrame) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                Spacer(Modifier.height(10.dp))
                Text("Waiting for ${p.displayName}'s screen…", color = Color(0xCCFFFFFF), fontSize = 13.sp)
            }
        }
        Box(Modifier.align(Alignment.TopStart).padding(10.dp).background(Color(0x99000000), androidx.compose.foundation.shape.RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 5.dp)) {
            Text("${p.displayName} · ${p.sharing?.res}p ${p.sharing?.fps}fps", color = Color.White, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SharerPanel(p: Participant, modifier: Modifier) {
    val active by AppCore.screen.active.collectAsState()
    var res by remember { mutableStateOf(p.sharing?.res ?: 720) }
    var fps by remember { mutableStateOf(p.sharing?.fps ?: 30) }
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(if (active) "You're sharing your screen" else "Starting…", color = WR.text, fontSize = 20.sp)
        Spacer(Modifier.height(6.dp))
        Muted("Everyone in the room can see it. Switch to another app to show it.")
        Spacer(Modifier.height(20.dp))
        Tiny("Resolution")
        Spacer(Modifier.height(8.dp))
        Segmented(listOf("240" to "240p", "480" to "480p", "720" to "720p", "1080" to "1080p"), res.toString(), {
            res = it.toInt(); AppCore.screen.setQuality(res, fps); AppCore.session.shareQuality(res, fps)
        })
        Spacer(Modifier.height(12.dp))
        Tiny("Frame rate")
        Spacer(Modifier.height(8.dp))
        Segmented(listOf("5" to "5", "15" to "15", "30" to "30", "60" to "60"), fps.toString(), {
            fps = it.toInt(); AppCore.screen.setQuality(res, fps); AppCore.session.shareQuality(res, fps)
        })
        Spacer(Modifier.height(24.dp))
        WrButton("Stop sharing", { AppCore.screen.stop(true) }, Modifier.fillMaxWidth(), kind = BtnKind.Danger, icon = "x")
    }
}
