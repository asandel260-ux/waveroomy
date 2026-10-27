package app.waveroom.ui.screens

import android.annotation.SuppressLint
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.waveroom.AppCore
import app.waveroom.core.WatchVideo
import app.waveroom.core.fmtDuration
import app.waveroom.core.onMain
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.MenuEntry
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Segmented
import app.waveroom.ui.components.SwitchRow
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrChip
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrMenu
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.theme.WR
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

fun parseYouTube(url: String): String? {
    val s = url.trim()
    val pats = listOf(
        Regex("[?&]v=([A-Za-z0-9_-]{11})"), Regex("youtu\\.be/([A-Za-z0-9_-]{11})"), Regex("/embed/([A-Za-z0-9_-]{11})"),
        Regex("/shorts/([A-Za-z0-9_-]{11})"), Regex("/live/([A-Za-z0-9_-]{11})")
    )
    for (p in pats) p.find(s)?.let { return it.groupValues[1] }
    if (Regex("^[A-Za-z0-9_-]{11}$").matches(s)) return s
    return null
}

private fun fetchMeta(id: String, cb: (String, String) -> Unit) {
    Thread {
        var title = "YouTube video"
        var author = ""
        try {
            val u = URL("https://www.youtube.com/oembed?url=https%3A%2F%2Fwww.youtube.com%2Fwatch%3Fv%3D$id&format=json")
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 5000
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            title = j.optString("title", title)
            author = j.optString("author_name", "")
        } catch (e: Exception) {
        }
        onMain { cb(title, author) }
    }.also { it.isDaemon = true; it.start() }
}

private const val PLAYER_HTML = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:#000;overflow:hidden}#p{width:100%;height:100%}</style></head><body><div id="p"></div>
<script src="https://www.youtube.com/iframe_api"></script>
<script>
var player=null,ready=false,pend=null;
function onYouTubeIframeAPIReady(){player=new YT.Player('p',{playerVars:{controls:0,disablekb:1,rel:0,iv_load_policy:3,playsinline:1,fs:0,modestbranding:1,origin:'https://www.youtube.com',host:'https://www.youtube.com'},
events:{onReady:function(){ready=true;WR.ready();if(pend){load(pend.id,pend.t,pend.play);pend=null}},
onStateChange:function(e){WR.state(e.data)},onError:function(e){WR.error(e.data)}}});}
function load(id,t,play){if(!ready){pend={id:id,t:t,play:play};return}
if(play)player.loadVideoById({videoId:id,startSeconds:t});else player.cueVideoById({videoId:id,startSeconds:t});}
function play(){if(ready)player.playVideo()} function pause(){if(ready)player.pauseVideo()}
function seek(t){if(ready)player.seekTo(t,true)} function rate(r){if(ready)player.setPlaybackRate(r)} function vol(v){if(ready)player.setVolume(v)}
setInterval(function(){if(ready&&player.getCurrentTime){WR.tick(player.getCurrentTime(),player.getDuration()||0,player.getPlayerState(),player.getPlaybackRate())}},500);
</script></body></html>"""

/** Fallback: play the watch page itself and drive its <video> element (works for videos whose owner blocked embedding). */
private const val PAGE_JS = """(function(){if(window.__wr)return;window.__wr=1;
var st=document.createElement('style');st.textContent='html,body{overflow:hidden!important;background:#000!important}body *{visibility:hidden!important}video{visibility:visible!important;position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:2147483647!important;object-fit:contain!important;background:#000!important;pointer-events:none!important}';document.documentElement.appendChild(st);
function V(){return document.querySelector('video')}
window.load=function(){};
window.play=function(){var v=V();if(v){var p=v.play();if(p&&p.catch)p.catch(function(){})}};
window.pause=function(){var v=V();if(v)v.pause()};
window.seek=function(t){var v=V();if(v)v.currentTime=t};
window.rate=function(r){var v=V();if(v)v.playbackRate=r};
window.vol=function(x){var v=V();if(v){v.muted=(x==0);v.volume=x/100}};
WR.ready();
setInterval(function(){var v=V();if(!v)return;var s=v.ended?0:(v.paused?2:(v.readyState<3?3:1));
var d=(isFinite(v.duration)&&v.duration>0)?v.duration:0;if(!d){try{d=parseFloat(window.ytInitialPlayerResponse.videoDetails.lengthSeconds)||0}catch(e){}}WR.tick(v.currentTime||0,d,s,v.playbackRate)},500);
})();"""

private class YtBridge(
    val onReady: () -> Unit,
    val onState: (Int) -> Unit,
    val onTick: (Double, Double, Int, Double) -> Unit,
    val onError: (Int) -> Unit
) {
    @JavascriptInterface fun ready() = onMain { onReady() }
    @JavascriptInterface fun state(s: Int) = onMain { onState(s) }
    @JavascriptInterface fun tick(cur: Double, dur: Double, state: Int, rate: Double) = onMain { onTick(cur, dur, state, rate) }
    @JavascriptInterface fun error(code: Int) = onMain { onError(code) }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WatchPanel(modifier: Modifier = Modifier, land: Boolean = false) {
    val session = AppCore.session
    val tick by session.tick.collectAsState()
    tick.let { }
    val s = session.room ?: return
    val w = s.watch
    val canControl = session.canWatchControl()
    val isHost = session.isHost

    var web by remember { mutableStateOf<WebView?>(null) }
    var ready by remember { mutableStateOf(false) }
    var cur by remember { mutableStateOf(0.0) }
    var dur by remember { mutableStateOf(0.0) }
    var pstate by remember { mutableStateOf(-1) }
    var loadedId by remember { mutableStateOf<String?>(null) }
    var lastFix by remember { mutableStateOf(0L) }
    var drag by remember { mutableStateOf<Float?>(null) }
    var url by remember { mutableStateOf("") }
    var perms by remember { mutableStateOf(false) }
    var speedMenu by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var fallback by remember { mutableStateOf(false) }

    fun js(code: String) {
        web?.evaluateJavascript(code, null)
    }

    val bridge = remember {
        YtBridge(
            onReady = { ready = true },
            onState = { st ->
                pstate = st
                if (st == 0 && AppCore.session.isHost && AppCore.session.room?.watch?.playing == true) {
                    AppCore.session.watchControl("skip")
                }
            },
            onTick = tickFn@{ c, d, st, r ->
                cur = c; dur = d; pstate = st
                val cw = AppCore.session.room?.watch ?: return@tickFn
                val v = cw.video ?: return@tickFn
                if (d > 0 && v.duration == 0) AppCore.session.watchMeta(d.toInt())
                val expected = cw.expectedPosition()
                val now = System.currentTimeMillis()
                if (cw.playing && st != 1 && st != 3) js2(web, "play()")
                if (!cw.playing && st == 1) js2(web, "pause()")
                if (abs(c - expected) > 0.9 && now - lastFix > 1500 && st != -1 && st != 5) {
                    lastFix = now
                    js2(web, "seek($expected)")
                }
                if (abs(r - cw.rate) > 0.01) js2(web, "rate(${cw.rate})")
            },
            onError = { code ->
                if (!fallback) {
                    // embedding refused (101/150/153 ...): switch to playing the watch page directly
                    fallback = true
                    loadedId = null
                    ready = false
                    errorMsg = null
                } else errorMsg = "This video can't be played (error $code)."
            }
        )
    }

    // load whichever video the room is on
    LaunchedEffect(w.video?.id, ready, fallback) {
        val v = w.video
        if (fallback) {
            if (v != null && v.id != loadedId) {
                loadedId = v.id
                ready = false
                errorMsg = null
                web?.loadUrl("https://m.youtube.com/watch?v=${v.id}")
            }
            if (v == null) loadedId = null
            return@LaunchedEffect
        }
        if (ready && v != null && v.id != loadedId) {
            loadedId = v.id
            errorMsg = null
            val cw = AppCore.session.room?.watch ?: w
            js("load('${v.id}',${cw.expectedPosition()},${cw.playing})")
        }
        if (v == null) loadedId = null
    }
    // keep playing state in step even between ticks
    LaunchedEffect(w.playing, w.position, w.anchorMs) {
        if (ready && loadedId != null) {
            if (w.playing) js("play()") else js("pause()")
            js("seek(${w.expectedPosition()})")
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                web?.evaluateJavascript("pause()", null)
                web?.destroy()
            } catch (e: Throwable) {
            }
        }
    }

    fun submitUrl() {
        val id = parseYouTube(url)
        if (id == null) {
            AppCore.toast("That doesn't look like a YouTube link")
            return
        }
        url = ""
        fetchMeta(id) { title, author ->
            val v = WatchVideo(id, title, author, 0)
            if (w.video == null) session.watchStart(v) else session.watchQueueAdd(v)
        }
    }

    val playerBox: @Composable (Modifier) -> Unit = { pm ->
        Box(pm.background(Color.Black)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.BLACK)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        webChromeClient = WebChromeClient()
                        settings.userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                                val h = request?.url?.host ?: return true
                                return !(h == "youtube.com" || h.endsWith(".youtube.com") || h.endsWith(".youtube-nocookie.com") || h == "youtube-nocookie.com")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                if (url != null && url.contains("youtube.com/watch")) view?.evaluateJavascript(PAGE_JS, null)
                            }
                        }
                        addJavascriptInterface(bridge, "WR")
                        loadDataWithBaseURL("https://www.youtube.com/", PLAYER_HTML, "text/html", "utf-8", null)
                        web = this
                    }
                }
            )
            if (w.video == null) {
                Column(Modifier.fillMaxSize().background(WR.bg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    WrIcon("youtube", 36.dp, WR.faint)
                    Spacer(Modifier.height(8.dp))
                    Text("Nothing playing", color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Muted(if (canControl) "Paste a YouTube link below" else "Waiting for someone to pick a video", size = 12)
                }
            }
            val err = errorMsg
            if (err != null && w.video != null) {
                Box(Modifier.fillMaxSize().background(Color(0xCC000000)).padding(20.dp), contentAlignment = Alignment.Center) {
                    Text(err, color = Color.White, fontSize = 13.sp)
                }
            }
                }
    }
    val lowerPart: @Composable androidx.compose.foundation.layout.ColumnScope.(Boolean) -> Unit = { scroll ->
        // transport
        val v = w.video
        if (v != null) {
            val total = if (dur > 0) dur else v.duration.toDouble()
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(v.title, color = WR.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Muted(listOf(v.author, if (w.controllerName.isNotEmpty()) "last: ${w.controllerName}" else "").filter { it.isNotEmpty() }.joinToString(" · "), size = 12, maxLines = 1)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Tiny(fmtDuration(drag?.toDouble() ?: cur))
                    Slider(
                        value = (drag ?: cur.toFloat()).coerceIn(0f, maxOf(1f, total.toFloat())),
                        onValueChange = { if (canControl) drag = it },
                        onValueChangeFinished = { drag?.let { session.watchControl("seek", it.toDouble()) }; drag = null },
                        valueRange = 0f..maxOf(1f, total.toFloat()),
                        enabled = canControl && total > 0,
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        colors = SliderDefaults.colors(thumbColor = WR.accentBright, activeTrackColor = WR.accent, inactiveTrackColor = WR.surface3)
                    )
                    Tiny(fmtDuration(total))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    WrIconButton("restart", { session.watchControl("restart") }, enabled = canControl)
                    WrIconButton(if (w.playing) "pause" else "play", { session.watchControl(if (w.playing) "pause" else "play") }, enabled = canControl, size = 52.dp, bg = if (canControl) WR.accent else WR.surface2, tint = Color.White)
                    WrIconButton("skip", { session.watchControl("skip") }, enabled = canControl)
                    Box {
                        WrChip("${w.rate}x", onClick = { if (canControl) speedMenu = true })
                        WrMenu(speedMenu, { speedMenu = false }, listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0).map { r -> MenuEntry("${r}x") { session.watchControl("rate", r) } })
                    }
                    WrIconButton(if (muted) "volume-x" else "volume", { muted = !muted; js("vol(${if (muted) 0 else 100})") })
                    if (isHost) WrIconButton("sliders", { perms = true })
                }
                if (!canControl) Muted(if (w.perm == "mods") "Only playback moderators can control this." else "The host controls playback.", size = 12)
            }
        }
        // add + queue
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            WrField(url, { url = it }, Modifier.weight(1f), placeholder = "YouTube link", leadingIcon = "link", onDone = { submitUrl() }, ime = androidx.compose.ui.text.input.ImeAction.Go)
            Spacer(Modifier.width(8.dp))
            WrButton(if (w.video == null) "Play" else "Queue", { submitUrl() }, small = true, enabled = canControl && url.isNotBlank())
        }
        if (w.queue.isNotEmpty()) {
            Tiny("Up next · ${w.queue.size}", Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
            if (scroll) {
                for (q in w.queue) { androidx.compose.runtime.key(q.qid.ifEmpty { q.id }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.title, color = WR.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("added by ${q.by}", size = 12)
                        }
                        if (canControl) WrIconButton("x", { session.watchQueueRemove(q.qid) }, size = 36.dp, iconSize = 16.dp, tint = WR.muted)
                    }
                } }
            } else LazyColumn(Modifier.weight(1f)) {
                items(w.queue, key = { it.qid.ifEmpty { it.id } }) { q ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(q.title, color = WR.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Muted("added by ${q.by}", size = 12)
                        }
                        if (canControl) WrIconButton("x", { session.watchQueueRemove(q.qid) }, size = 36.dp, iconSize = 16.dp, tint = WR.muted)
                    }
                }
            }
        }
        }
    if (land) {
        Row(modifier.background(WR.bg)) {
            Box(Modifier.weight(1.4f).fillMaxHeight().background(Color.Black), contentAlignment = Alignment.Center) {
                playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            }
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) { lowerPart(true) }
        }
    } else {
        Column(modifier.background(WR.bg)) {
            playerBox(Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            lowerPart(false)
        }
    }

    if (perms) {
        val mods = w.mods
        WrSheet({ perms = false }, "Watch Together") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Tiny("Who can control playback")
                Spacer(Modifier.height(8.dp))
                Segmented(listOf("host" to "Host only", "mods" to "Moderators", "everyone" to "Everyone"), w.perm, { session.watchPerm(it) })
                if (w.perm == "mods") {
                    Spacer(Modifier.height(12.dp))
                    for (p in s.participants.values.filter { it.role != "host" }) {
                        SwitchRow(p.displayName, null, mods.contains(p.uid), { session.watchMod(p.uid, it) })
                    }
                }
                Spacer(Modifier.height(14.dp))
                WrButton("End Watch Together", { perms = false; session.watchEnd() }, Modifier.fillMaxWidth(), kind = BtnKind.Danger)
            }
        }
    }
}

private fun js2(web: WebView?, code: String) {
    web?.evaluateJavascript(code, null)
}
