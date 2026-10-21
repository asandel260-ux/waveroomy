package app.waveroom.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import app.waveroom.AppCore
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Segmented
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrChip
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.theme.WR

fun normalizeUrl(input: String): String {
    val t = input.trim()
    if (t.isEmpty()) return ""
    if (Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(t)) return t
    if (!t.contains(' ') && t.contains('.')) return "https://$t"
    return "https://duckduckgo.com/?q=" + Uri.encode(t)
}

@OptIn(ExperimentalLayoutApi::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowsePanel(modifier: Modifier = Modifier) {
    val session = AppCore.session
    val tick by session.tick.collectAsState()
    tick.let { }
    val s = session.room ?: return
    val b = s.browse
    val canControl = session.canBrowseControl()
    val me = session.me
    var web by remember { mutableStateOf<WebView?>(null) }
    var loadedNonce by remember { mutableStateOf(-1) }
    var address by remember { mutableStateOf("") }
    var perms by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }

    LaunchedEffect(b.url) { address = b.url ?: "" }
    LaunchedEffect(b.nonce, b.url, web) {
        val w = web ?: return@LaunchedEffect
        val u = b.url
        if (u != null && b.nonce != loadedNonce) {
            loadedNonce = b.nonce
            if (w.url != u) w.loadUrl(u) else if (b.nonce > 0) w.reload()
        }
        if (u == null) {
            loadedNonce = -1
            w.loadUrl("about:blank")
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            try {
                web?.destroy()
            } catch (e: Throwable) {
            }
        }
    }

    fun go(raw: String) {
        val u = normalizeUrl(raw)
        if (u.isEmpty()) return
        if (!canControl) {
            AppCore.toast("Ask for control to browse for the room")
            return
        }
        val host = try { Uri.parse(u).host ?: "" } catch (e: Exception) { "" }
        session.browseNavigate(u, "", host)
    }

    Column(modifier.background(WR.bg)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            WrIconButton("arrow-left", { session.browseStep(false) }, enabled = canControl && b.canBack, size = 40.dp, iconSize = 18.dp)
            WrIconButton("arrow-right", { session.browseStep(true) }, enabled = canControl && b.canForward, size = 40.dp, iconSize = 18.dp)
            WrIconButton("reload", { session.browseReload() }, enabled = canControl && b.url != null, size = 40.dp, iconSize = 18.dp)
            WrIconButton("home", { session.browseHome() }, enabled = canControl && b.url != null, size = 40.dp, iconSize = 18.dp)
            WrField(address, { address = it }, Modifier.weight(1f).padding(horizontal = 4.dp), placeholder = "Search or enter address", keyboard = androidx.compose.ui.text.input.KeyboardType.Uri, ime = androidx.compose.ui.text.input.ImeAction.Go, onDone = { go(address) })
            WrIconButton("users", { perms = true }, size = 40.dp, iconSize = 18.dp, badge = if (me?.role == "host") b.requests.size else 0)
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Muted(
                if (canControl) "You're driving — everyone sees what you open." else "${b.navigatorName.ifEmpty { "The host" }} is driving. You can follow along.",
                size = 12, modifier = Modifier.weight(1f)
            )
            if (!canControl) {
                WrChip(if (requested) "Requested" else "Request control", selected = !requested, onClick = {
                    if (!requested) { requested = true; session.browseControl("request") }
                })
            } else if (me?.role != "host" && b.mods.contains(me?.uid ?: "")) {
                WrChip("Release", onClick = { session.browseControl("release") })
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(android.graphics.Color.WHITE)
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        settings.mediaPlaybackRequiresUserGesture = true
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                                val u = request.url.toString()
                                val scheme = request.url.scheme ?: ""
                                if (scheme != "http" && scheme != "https") return true
                                val cur = AppCore.session.room?.browse?.url
                                if (u == cur) return false
                                if (!request.isForMainFrame) return false
                                return if (AppCore.session.canBrowseControl()) {
                                    AppCore.session.browseNavigate(u, "", request.url.host ?: "")
                                    false
                                } else {
                                    AppCore.toast("The host is driving. Request control to browse.")
                                    true
                                }
                            }

                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                loading = false
                                val cur = AppCore.session.room?.browse?.url
                                if (url != null && url == cur && AppCore.session.canBrowseControl() && !view.title.isNullOrEmpty()) {
                                    AppCore.session.browseTitle(url, view.title ?: "")
                                }
                            }
                        }
                        web = this
                    }
                }
            )
            if (b.url == null) {
                Column(Modifier.fillMaxSize().background(WR.bg).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    WrIcon("globe", 32.dp, WR.faint)
                    Spacer(Modifier.height(10.dp))
                    Text("Shared browser", color = WR.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Muted("One person drives, everybody follows the same page. Each phone loads the page itself, so your logins stay on your phone.")
                    Spacer(Modifier.height(16.dp))
                    Tiny("Quick start")
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        for ((n, u) in listOf("Wikipedia" to "https://wikipedia.org", "DuckDuckGo" to "https://duckduckgo.com", "OpenStreetMap" to "https://openstreetmap.org", "Hacker News" to "https://news.ycombinator.com")) {
                            WrChip(n, onClick = { go(u) })
                        }
                    }
                }
            }
        }
    }

    if (perms) {
        WrSheet({ perms = false }, "Shared browser") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                if (me?.role == "host") {
                    Tiny("Who can navigate")
                    Spacer(Modifier.height(8.dp))
                    Segmented(listOf("host" to "Host only", "mods" to "Navigators", "everyone" to "Everyone"), b.perm, { session.browsePerm(it) })
                    if (b.requests.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Tiny("Requests")
                        for (r in b.requests) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(r.name, color = WR.text, modifier = Modifier.weight(1f))
                                WrButton("Allow", { session.browseControl("grant", r.uid) }, small = true)
                                Spacer(Modifier.width(6.dp))
                                WrButton("Dismiss", { session.browseControl("dismiss", r.uid) }, small = true, kind = BtnKind.Secondary)
                            }
                        }
                    }
                    val navs = b.mods.mapNotNull { uid -> session.participantByUid(uid)?.let { uid to it.displayName } }
                    if (navs.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Tiny("Navigators")
                        for ((uid, name) in navs) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(name, color = WR.text, modifier = Modifier.weight(1f))
                                WrButton("Revoke", { session.browseControl("revoke", uid) }, small = true, kind = BtnKind.Secondary)
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    WrButton("End browsing session", { perms = false; session.browseEnd() }, Modifier.fillMaxWidth(), kind = BtnKind.Danger)
                } else {
                    Muted("Only the host manages who can navigate. Use “Request control” to ask.")
                }
            }
        }
    }
}
