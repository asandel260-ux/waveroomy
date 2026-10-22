package app.waveroom.ui.screens

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.CodeResult
import app.waveroom.core.obj
import app.waveroom.core.str
import app.waveroom.core.int
import app.waveroom.core.bool
import app.waveroom.net.Sdp
import app.waveroom.ui.Nav
import app.waveroom.ui.Route
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.QrDisplay
import app.waveroom.ui.components.QrScanner
import app.waveroom.ui.components.ScreenHeader
import app.waveroom.ui.components.Segmented
import app.waveroom.ui.components.SwitchRow
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.theme.WR
import kotlinx.coroutines.delay

fun readClipboard(): String {
    return try {
        val cm = AppCore.app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
    } catch (e: Exception) {
        ""
    }
}

/* ============================== Create room ============================== */

@Composable
fun CreateRoomScreen(nav: Nav) {
    var title by remember { mutableStateOf("") }
    var topic by remember { mutableStateOf("") }
    var tags by remember { mutableStateOf("") }
    var unlisted by remember { mutableStateOf(false) }
    var seats by remember { mutableStateOf("8") }
    var error by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(WR.bg).imePadding()) {
        ScreenHeader("New room", { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            WrField(title, { title = it }, label = "Title", placeholder = "Friday night jam", maxLength = 60)
            Spacer(Modifier.height(14.dp))
            WrField(topic, { topic = it }, label = "Topic (optional)", placeholder = "What is this about?", singleLine = false, maxLines = 3, maxLength = 140)
            Spacer(Modifier.height(14.dp))
            WrField(tags, { tags = it }, label = "Tags (comma separated)", placeholder = "music, chill")
            Spacer(Modifier.height(20.dp))
            SwitchRow("Unlisted", "Don't announce this room on the local network. People need your QR code.", unlisted, { unlisted = it })
            Muted("You are the host. If you leave, hosting passes to the person who has been here longest — the room keeps going.", size = 12)
            if (error != null) {
                Spacer(Modifier.height(10.dp))
                Text(error!!, color = WR.danger, fontSize = 13.sp)
            }
        }
        Box(Modifier.padding(20.dp).navigationBarsPadding()) {
            WrButton("Start room", {
                val t = title.trim()
                if (t.length < 3) { error = "Give the room a title (3+ characters)."; return@WrButton }
                AppCore.platform?.requestMic { if (it) AppCore.startService() }
                val tg = tags.split(',').map { it.trim().lowercase().take(20) }.filter { it.isNotEmpty() }.distinct().take(6)
                if (!AppCore.session.createRoom(t, topic, tg, unlisted, 24)) error = "Could not start the room."
            }, Modifier.fillMaxWidth(), icon = "mic")
        }
    }
}

/* ============================== Scanner overlay ============================== */

@Composable
fun ScannerOverlay(title: String, hint: String, onCode: (String) -> Unit, onClose: () -> Unit) {
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    BackHandler { onClose() }
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black)) {
        QrScanner(onCode, Modifier.fillMaxSize(), onProgress = { a, b -> progress = Pair(a, b) })
        Box(
            Modifier.align(Alignment.Center).size(260.dp).clip(RoundedCornerShape(24.dp))
                .border(2.dp, WR.accentBright, RoundedCornerShape(24.dp))
        )
        Column(Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                WrIconButton("x", onClose, bg = androidx.compose.ui.graphics.Color(0x99000000))
                Spacer(Modifier.size(12.dp))
                Text(title, color = androidx.compose.ui.graphics.Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val p = progress
            if (p != null) Text("Reading code… ${p.first} of ${p.second}", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Text(hint, color = androidx.compose.ui.graphics.Color(0xCCFFFFFF), fontSize = 13.sp, textAlign = TextAlign.Center)
        }
    }
}

/* ============================== Invite (show my code) ============================== */

@Composable
fun InviteScreen(nav: Nav, purpose: String) {
    var code by remember { mutableStateOf<String?>(null) }
    var inviteId by remember { mutableStateOf("") }
    var failed by remember { mutableStateOf(false) }
    var scanning by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf("") }
    var applying by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val connected by AppCore.connected.collectAsState()
    val before = remember { AppCore.connected.value }
    val roomTitle = AppCore.session.room?.meta?.title

    LaunchedEffect(purpose) {
        AppCore.createInviteCode(purpose) { id, c ->
            inviteId = id
            if (c == null) failed = true else code = c
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (inviteId.isNotEmpty()) AppCore.mesh.cancelInvite(inviteId) }
    }
    // success: somebody new is now connected
    LaunchedEffect(connected) {
        val added = connected - before
        if (added.isNotEmpty()) {
            val uid = added.first()
            AppCore.toast("Connected to ${AppCore.contactName(uid)}")
            if (purpose == "dm") {
                nav.pop(); nav.push(Route.Chat(uid))
            } else nav.pop()
        }
    }
    LaunchedEffect(applying) {
        if (applying) {
            delay(40_000)
            if (applying) { applying = false; error = "The connection didn't come up.\n\n" + AppCore.mesh.report.value }
        }
    }

    fun applyReply(text: String) {
        error = null
        AppCore.handleCode(text) { res ->
            when (res) {
                is CodeResult.Applied -> applying = true
                is CodeResult.Error -> error = res.msg
                is CodeResult.Answer -> error = "That is an invite, not a reply. Ask them to show their reply code."
            }
        }
    }

    Box(Modifier.fillMaxSize().background(WR.bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ScreenHeader(
                if (purpose == "room") "Invite to room" else if (purpose.startsWith("group:")) "Add to group" else "Add a friend", { nav.pop() },
                subtitle = if (purpose == "room") roomTitle else if (purpose.startsWith("group:")) AppCore.groups.get(purpose.removePrefix("group:"))?.name else null
            )
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                when {
                    failed -> {
                        Muted("Could not create an invite. Check that the microphone/network are available and try again.")
                    }
                    code == null -> {
                        Spacer(Modifier.height(80.dp))
                        CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                        Spacer(Modifier.height(12.dp))
                        Muted("Preparing a direct connection…")
                    }
                    else -> {
                        val c = code!!
                        Step(1, "They scan this code")
                        Spacer(Modifier.height(4.dp))
                        Muted("On the same Wi-Fi that is all it takes — the link comes up by itself.", size = 12, align = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        QrDisplay(c)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WrButton("Copy", { AppCore.platform?.copy("HoMit invite", c) }, kind = BtnKind.Secondary, small = true, icon = "copy")
                            WrButton("Share", { AppCore.platform?.shareText(if (purpose.startsWith("group:")) "Join my HoMit group “${AppCore.groups.get(purpose.removePrefix("group:"))?.name ?: ""}”: $c" else "Join me on HoMit: $c") }, kind = BtnKind.Secondary, small = true, icon = "share")
                        }
                        Spacer(Modifier.height(24.dp))
                        Step(2, "Different networks? Scan the reply on their screen")
                        Spacer(Modifier.height(10.dp))
                        if (applying) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.size(10.dp))
                                Muted("Connecting directly…")
                            }
                        } else {
                            WrButton("Scan their reply", {
                                AppCore.platform?.requestCamera { ok -> if (ok) scanning = true else AppCore.toast("Camera permission is needed to scan") }
                            }, Modifier.fillMaxWidth(), icon = "scan")
                            Spacer(Modifier.height(10.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                WrField(reply, { reply = it }, Modifier.weight(1f), placeholder = "…or paste their reply code", mono = true)
                                Spacer(Modifier.size(8.dp))
                                WrButton("Go", { applyReply(reply.ifBlank { readClipboard() }) }, small = true, kind = BtnKind.Secondary)
                            }
                        }
                        if (error != null) {
                            Spacer(Modifier.height(10.dp))
                            Text(error!!, color = WR.danger, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(18.dp))
                        Muted("This code works once and expires in 10 minutes. It carries only connection details — nothing about you beyond your display name.", size = 12, align = TextAlign.Center)
                    }
                }
            }
        }
        if (scanning) {
            ScannerOverlay("Scan their reply", "Point at the reply code shown on their phone", onCode = { scanning = false; applyReply(it) }, onClose = { scanning = false })
        }
    }
}

@Composable
fun Step(n: Int, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(RoundedCornerShape(12.dp)).background(WR.accentSoft), contentAlignment = Alignment.Center) {
            Text(n.toString(), color = WR.accentBright, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Text(text, color = WR.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}

/* ============================== Join (scan / paste their code) ============================== */

@Composable
fun JoinScreen(nav: Nav, initial: String) {
    var text by remember { mutableStateOf(initial) }
    var scanning by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<CodeResult.Answer?>(null) }
    var waiting by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val connected by AppCore.connected.collectAsState()
    val before = remember { AppCore.connected.value }

    val parsed = remember(text) { if (text.isBlank()) null else Sdp.unpack(text) }
    val isOffer = parsed?.str("k") == "o"
    val preview = parsed?.obj("v")

    fun submit(raw: String) {
        error = null
        AppCore.platform?.requestMic { }
        AppCore.handleCode(raw) { res ->
            when (res) {
                is CodeResult.Answer -> { answer = res; waiting = true }
                is CodeResult.Applied -> waiting = true
                is CodeResult.Error -> error = res.msg
            }
        }
    }

    LaunchedEffect(Unit) {
        if (initial.isNotEmpty()) {
            val p = Sdp.unpack(initial)
            if (p != null && p.str("k") == "a") submit(initial)
        }
    }
    LaunchedEffect(connected) {
        val added = connected - before
        if (waiting && added.isNotEmpty()) {
            val uid = added.first()
            val dm = answer?.purpose == "dm"
            AppCore.toast("Connected to ${AppCore.contactName(uid)}")
            if (dm) { nav.pop(); nav.push(Route.Chat(uid)) }
            // for rooms the session navigates to the Room screen on its own
        }
    }
    LaunchedEffect(waiting) {
        if (waiting) {
            delay(60_000)
            if (waiting && AppCore.session.phase.value == "idle") {
                waiting = false; answer = null
                error = "The connection didn't come up.\n\n" + AppCore.mesh.report.value.ifBlank { "Ask for a fresh invite and try again." }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(WR.bg)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            ScreenHeader("Join with a code", { nav.pop() })
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val a = answer
                if (a != null && a.auto) {
                    Spacer(Modifier.height(80.dp))
                    CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Connecting directly…", color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    Muted("You're on the same Wi-Fi, so no reply code is needed.", align = TextAlign.Center)
                    Spacer(Modifier.height(16.dp))
                    ConnectionReport()
                } else if (a != null) {
                    Step(2, if (a.purpose == "room") "Show this reply to the host" else "Show this reply to them")
                    Spacer(Modifier.height(6.dp))
                    Muted("Have them tap “Scan their reply”. The link comes up by itself.", align = TextAlign.Center)
                    Spacer(Modifier.height(4.dp))
                    Muted("Tip: if you are both on the same Wi-Fi, create the code again from the other phone's Messages tab → On this Wi-Fi — no codes needed.", size = 12, align = TextAlign.Center)
                    Spacer(Modifier.height(14.dp))
                    QrDisplay(a.code)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WrButton("Copy", { AppCore.platform?.copy("HoMit reply", a.code) }, kind = BtnKind.Secondary, small = true, icon = "copy")
                        WrButton("Share", { AppCore.platform?.shareText(a.code) }, kind = BtnKind.Secondary, small = true, icon = "share")
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(10.dp))
                        Muted("Waiting for the link…")
                    }
                    Spacer(Modifier.height(10.dp))
                    ConnectionReport()
                    Spacer(Modifier.height(10.dp))
                    WrButton("Start over", { answer = null; waiting = false; text = "" }, kind = BtnKind.Ghost, small = true)
                } else if (waiting) {
                    Spacer(Modifier.height(80.dp))
                    CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
                    Spacer(Modifier.height(12.dp))
                    Muted("Connecting directly…")
                } else {
                    Step(1, "Scan their code")
                    Spacer(Modifier.height(10.dp))
                    WrButton("Scan QR code", {
                        AppCore.platform?.requestCamera { ok -> if (ok) scanning = true else AppCore.toast("Camera permission is needed to scan") }
                    }, Modifier.fillMaxWidth(), icon = "scan")
                    Spacer(Modifier.height(16.dp))
                    Tiny("Or paste the code")
                    Spacer(Modifier.height(8.dp))
                    WrField(text, { text = it }, placeholder = "WR1.…", mono = true, singleLine = false, maxLines = 4)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WrButton("Paste", { text = readClipboard() }, kind = BtnKind.Secondary, small = true, icon = "copy")
                    }
                    if (isOffer && preview != null) {
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WR.surface).border(1.dp, WR.border, RoundedCornerShape(14.dp)).padding(16.dp)) {
                            Column {
                                Tiny(if (parsed?.str("p") == "dm") "Friend invite" else "Room")
                                Spacer(Modifier.height(4.dp))
                                Text(preview.str("t").ifEmpty { "Someone" }, color = WR.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                                if (parsed?.str("p") != "dm") Muted("Hosted by ${preview.str("h")} · ${preview.int("n")} here" + (if (preview.bool("l")) " · locked" else ""))
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    WrButton(if (isOffer && parsed?.str("p") != "dm") "Join room" else "Continue", { submit(text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank())
                    if (error != null) {
                        Spacer(Modifier.height(10.dp))
                        Text(error!!, color = WR.danger, fontSize = 13.sp)
                    }
                }
            }
        }
        if (scanning) {
            ScannerOverlay("Scan a code", "Point at the QR code on their phone", onCode = { scanning = false; text = it; submit(it) }, onClose = { scanning = false })
        }
    }
}


/** Plain-language view of what the last connection attempt found. Helps when two phones can't reach each other. */
@Composable
fun ConnectionReport() {
    val r by AppCore.mesh.report.collectAsState()
    var open by remember { mutableStateOf(false) }
    if (r.isBlank()) return
    Column(Modifier.fillMaxWidth()) {
        Text(if (open) "Hide connection details" else "Connection details", color = WR.accentBright, fontSize = 12.sp, modifier = Modifier.clickable { open = !open }.padding(vertical = 6.dp))
        if (open) Text(r, color = WR.muted, fontSize = 12.sp, fontFamily = WR.mono, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WR.surface).padding(10.dp))
    }
}
