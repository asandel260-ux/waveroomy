package app.waveroom.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.Participant
import app.waveroom.core.REACTIONS
import app.waveroom.core.canSpeak
import app.waveroom.ui.Nav
import app.waveroom.ui.Route
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.MenuEntry
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrChip
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrMenu
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.components.Hairline
import app.waveroom.ui.theme.WR
import kotlin.math.roundToInt

private data class Floating(val id: Long, val emoji: String, val name: String, val x: Float)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoomScreen(nav: Nav) {
    val session = AppCore.session
    val tick by session.tick.collectAsState()
    val phase by session.phase.collectAsState()
    val speaking by session.speaking.collectAsState()
    val connected by AppCore.connected.collectAsState()
    val unread by session.unreadChat.collectAsState()
    val deaf by session.deafened.collectAsState()
    val settings by AppCore.settings.state.collectAsState()
    val sharing by AppCore.screen.active.collectAsState()
    tick.let { }
    val s = session.room
    val me = session.me

    var mode by remember { mutableStateOf("stage") }
    var chatOpen by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }
    var fxOpen by remember { mutableStateOf(false) }
    var reactOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var leaveConfirm by remember { mutableStateOf(false) }
    var person by remember { mutableStateOf<String?>(null) }
    var shareSheet by remember { mutableStateOf(false) }
    var reportRoom by remember { mutableStateOf(false) }
    val floats = remember { mutableStateListOf<Floating>() }

    LaunchedEffect(Unit) {
        session.reactions.collect { r ->
            floats.add(Floating(r.id, r.emoji, r.name, (0.15f + Math.random().toFloat() * 0.7f)))
            if (floats.size > 12) floats.removeAt(0)
        }
    }
    BackHandler(enabled = phase != "idle") {
        if (phase == "joining") session.leave() else if (mode != "stage") mode = "stage" else leaveConfirm = true
    }

    if (s == null || phase == "idle") {
        Box(Modifier.fillMaxSize().background(WR.bg))
        return
    }
    if (phase == "joining") {
        Column(Modifier.fillMaxSize().background(WR.bg), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            Spacer(Modifier.height(14.dp))
            Text("Joining room…", color = WR.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Muted("Setting up direct connections")
            Spacer(Modifier.height(24.dp))
            WrButton("Cancel", { session.leave() }, kind = BtnKind.Ghost, small = true)
        }
        return
    }

    val participants = s.participants.values.toList()
    val speakers = participants.filter { canSpeak(it.role) }.sortedBy { it.joinedAt }
    val audience = participants.filter { !canSpeak(it.role) }.sortedWith(compareByDescending<Participant> { it.hand }.thenBy { it.joinedAt })
    val sharer = participants.firstOrNull { it.sharing != null }
    val watching = s.watch.video != null
    val browsing = s.browse.url != null
    val isHost = me?.role == "host"
    val canTalk = me != null && canSpeak(me.role)

    // tabs fall back to the stage when their activity ends
    LaunchedEffect(sharer?.sid, watching, browsing) {
        if (mode == "screen" && sharer == null) mode = "stage"
    }
    DisposableEffect(mode) {
        val m = mode
        if (m == "browse") session.browsePresence(true)
        onDispose { if (m == "browse") session.browsePresence(false) }
    }

    val land = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE

    val titleBlock: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.meta.title, color = WR.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (s.meta.locked) {
                    Spacer(Modifier.width(6.dp))
                    WrIcon("lock", 14.dp, WR.warn)
                }
            }
            val live = participants.count { it.sid == session.mySid || connected.contains(it.uid) }
            Muted("${participants.size} in room" + (if (live < participants.size) " · connecting ${participants.size - live}" else ""), size = 12, maxLines = 1)
        }
    }
    val actionsBlock: @Composable () -> Unit = {
        WrIconButton("user-plus", { nav.push(Route.Invite("room")) }, tint = WR.text)
        Box {
            WrIconButton("more-v", { menuOpen = true }, tint = WR.text)
            WrMenu(menuOpen, { menuOpen = false }, buildList {
                if (isHost) add(MenuEntry("Room settings", "sliders") { settingsOpen = true })
                if (canTalk) add(MenuEntry("Voice effects", "waveform") { fxOpen = true })
                add(MenuEntry(if (AppCore.audio.speakerOn.value) "Use earpiece" else "Use speaker", "volume") { AppCore.audio.setSpeaker(!AppCore.audio.speakerOn.value) })
                add(MenuEntry("Report this room", "flag") { reportRoom = true })
                add(MenuEntry("Leave room", "log-out", danger = true) { leaveConfirm = true })
            })
        }
    }
    val tabChips: @Composable () -> Unit = {
        Tab("Stage", "users", mode == "stage", false) { mode = "stage" }
        if (sharer != null) Tab("Screen", "monitor", mode == "screen", true) { mode = "screen" }
        Tab("Watch", "youtube", mode == "watch", watching) { mode = "watch" }
        Tab("Board", "board", mode == "board", s.board.objects.isNotEmpty()) { mode = "board" }
        Tab("Browser", "globe", mode == "browse", browsing) { mode = "browse" }
    }
    val modeAction: @Composable () -> Unit = {
        val iShare = sharer?.sid == session.mySid
        when (mode) {
            "screen" -> if (iShare) WrButton("Stop sharing", { AppCore.screen.stop(true); mode = "stage" }, kind = BtnKind.Danger, small = true)
            "watch" -> if (watching && session.canWatchControl()) WrButton("End Watch", { session.watchEnd(); mode = "stage" }, kind = BtnKind.Danger, small = true)
            "browse" -> if (browsing && session.canBrowseControl()) WrButton("Close browser", { session.browseEnd(); mode = "stage" }, kind = BtnKind.Danger, small = true)
        }
    }
    val topBar: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            titleBlock(Modifier.weight(1f))
            actionsBlock()
        }
    }
    val tabsRow: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { tabChips() }
        if (mode != "stage") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                WrButton("Exit to stage", { mode = "stage" }, kind = BtnKind.Ghost, small = true, icon = "arrow-left")
                Spacer(Modifier.weight(1f))
                modeAction()
            }
        }
        Hairline()
    }
    /** Landscape: one slim row so the content gets the height. */
    val landHeader: @Composable () -> Unit = {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            titleBlock(Modifier.widthIn(max = 130.dp))
            Spacer(Modifier.width(10.dp))
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (mode != "stage") WrButton("Exit", { mode = "stage" }, kind = BtnKind.Ghost, small = true, icon = "arrow-left")
                tabChips()
                modeAction()
            }
            actionsBlock()
        }
        Hairline()
    }
    val mainArea: @Composable (Modifier) -> Unit = { mod ->
        Box(mod.fillMaxWidth()) {
            when (mode) {
                "screen" -> if (sharer != null) StreamPanel(sharer, Modifier.fillMaxSize())
                "watch" -> WatchPanel(Modifier.fillMaxSize(), land)
                "board" -> BoardPanel(Modifier.fillMaxSize())
                "browse" -> BrowsePanel(Modifier.fillMaxSize())
                else -> {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = if (land) 8.dp else 14.dp)) {
                        if (sharer != null) {
                            SharingBanner(sharer) { mode = "screen" }
                            Spacer(Modifier.height(14.dp))
                        }
                        Tiny("In the room · ${participants.size}")
                        Spacer(Modifier.height(12.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(if (land) 10.dp else 16.dp), modifier = Modifier.fillMaxWidth()) {
                            for (p in speakers) StageTile(p, speaking.contains(p.sid), p.sid == session.mySid || connected.contains(p.uid)) { person = p.sid }
                        }
                        if (participants.size == 1) {
                            Spacer(Modifier.height(20.dp))
                            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                Muted("Nobody else is here yet.", size = 14)
                                Spacer(Modifier.height(10.dp))
                                WrButton("Invite someone", { nav.push(Route.Invite("room")) }, icon = "user-plus", small = true)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
            for (f in floats.toList()) {
                androidx.compose.runtime.key(f.id) {
                    FloatingReaction(f) { floats.removeAll { it.id == f.id } }
                }
            }
        }
    }
    val controls: @Composable (Boolean) -> Unit = { vertical ->
        val items: @Composable () -> Unit = {
            WrIconButton(if (deaf) "volume-x" else "headphones", { session.toggleDeafen() }, size = 48.dp, tint = if (deaf) WR.danger else WR.text, bg = if (deaf) WR.dangerSoft else WR.surface2, border = WR.border)
            WrIconButton("smile-plus", { reactOpen = true }, size = 48.dp, bg = WR.surface2, border = WR.border)
            MicButton(muted = me?.muted != false, ptt = settings.ptt, compact = vertical)
            WrIconButton("monitor", { if (sharing) AppCore.screen.stop(true) else shareSheet = true }, size = 48.dp, tint = if (sharing) WR.accentBright else WR.text, bg = if (sharing) WR.accentSoft else WR.surface2, border = if (sharing) WR.accent else WR.border)
            WrIconButton("message", { chatOpen = true; session.unreadChat.value = 0 }, size = 48.dp, bg = WR.surface2, border = WR.border, badge = unread)
        }
        if (vertical) {
            Column(
                Modifier.fillMaxHeight().background(WR.surface).statusBarsPadding().navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.SpaceEvenly, horizontalAlignment = Alignment.CenterHorizontally
            ) { items() }
        } else {
            Row(
                Modifier.fillMaxWidth().background(WR.surface).navigationBarsPadding().padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically
            ) { items() }
        }
    }

    Box(Modifier.fillMaxSize().background(WR.bg).windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        if (land) {
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    Box(Modifier.statusBarsPadding()) { landHeader() }
                    mainArea(Modifier.weight(1f))
                }
                Box(Modifier.fillMaxHeight()) { Row { Box(Modifier.width(1.dp).fillMaxHeight().background(WR.border)); controls(true) } }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding()) { Column { topBar(); tabsRow() } }
                mainArea(Modifier.weight(1f))
                Hairline()
                controls(false)
            }
        }
    }

    /* ---------------- overlays ---------------- */
    if (reactOpen) {
        WrSheet({ reactOpen = false }, "React") {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                for (e in REACTIONS) {
                    Box(Modifier.size(56.dp).clip(CircleShape).background(WR.surface2).clickable { session.react(e); reactOpen = false }, contentAlignment = Alignment.Center) {
                        Text(e, fontSize = 26.sp)
                    }
                }
            }
        }
    }
    if (chatOpen) ChatSheet(nav) { chatOpen = false }
    if (settingsOpen) RoomSettingsSheet { settingsOpen = false }
    if (fxOpen) WrSheet({ fxOpen = false }, "Voice effects") { Column(Modifier.padding(horizontal = 20.dp)) { VoiceFxPanel() } }
    if (shareSheet) ShareSheet { shareSheet = false }
    val pid = person
    if (pid != null) {
        val p = s.participants[pid]
        if (p != null) PersonSheet(p, nav) { person = null } else person = null
    }
    if (leaveConfirm) {
        if (isHost) {
            ConfirmDialog(
                "Leave room?",
                if (participants.size > 1) "You can hand the room over by leaving — the longest-present member becomes host. Or end it for everyone." else "You're the only one here. The room will be saved on this phone.",
                "Leave", { session.leave() }, { leaveConfirm = false }
            )
        } else {
            ConfirmDialog("Leave room?", "You can rejoin with a new invite.", "Leave", { session.leave() }, { leaveConfirm = false }, danger = true)
        }
    }
    if (reportRoom) {
        ReportDialog("room", s.meta.id, s.meta.title, "", { reportRoom = false })
    }
}

@Composable
private fun Tab(label: String, icon: String, on: Boolean, live: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(if (on) WR.accentSoft else Color.Transparent)
            .border(1.dp, if (on) WR.accent else WR.border, RoundedCornerShape(50)).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WrIcon(icon, 15.dp, if (on) WR.accentBright else WR.muted)
        Spacer(Modifier.width(6.dp))
        Text(label, color = if (on) WR.accentBright else WR.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StageTile(p: Participant, speaking: Boolean, online: Boolean, onClick: () -> Unit) {
    val mine = p.sid == AppCore.session.mySid
    Column(Modifier.width(96.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            AvatarView(p.avatar, 76.dp, uid = p.uid, mine = mine, ring = if (speaking) WR.accentBright else if (!online) WR.borderStrong else Color.Transparent)
            if (p.muted) {
                Box(Modifier.align(Alignment.BottomEnd).size(24.dp).clip(CircleShape).background(WR.surface3).border(1.dp, WR.border, CircleShape), contentAlignment = Alignment.Center) {
                    WrIcon("mic-off", 13.dp, WR.danger)
                }
            }
            if (p.sharing != null) {
                Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(WR.surface3), contentAlignment = Alignment.Center) {
                    WrIcon("monitor", 12.dp, WR.accentBright)
                }
            }
            if (!online) {
                Box(Modifier.align(Alignment.Center).alpha(0.9f)) { CircularProgressIndicator(color = WR.muted, strokeWidth = 2.dp, modifier = Modifier.size(22.dp)) }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (p.role == "host") {
                WrIcon("crown", 12.dp, WR.warn)
                Spacer(Modifier.width(3.dp))
            }
            Text(if (mine) "You" else p.displayName, color = WR.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun AudienceTile(p: Participant, online: Boolean, onClick: () -> Unit) {
    val mine = p.sid == AppCore.session.mySid
    Column(Modifier.width(64.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Box {
            AvatarView(p.avatar, 50.dp, uid = p.uid, mine = mine, modifier = Modifier.alpha(if (online) 1f else 0.5f))
            if (p.hand) {
                Box(Modifier.align(Alignment.TopEnd).size(22.dp).clip(CircleShape).background(WR.warn), contentAlignment = Alignment.Center) {
                    WrIcon("hand", 12.dp, Color(0xFF1A1100))
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(if (mine) "You" else p.displayName, color = WR.muted, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SharingBanner(p: Participant, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WR.surface).border(1.dp, WR.border, RoundedCornerShape(14.dp))
            .clickable(onClick = onOpen).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        WrIcon("monitor", 22.dp, WR.accentBright)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("${if (p.sid == AppCore.session.mySid) "You are" else p.displayName + " is"} sharing a screen", color = WR.text, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Muted("${p.sharing?.res}p · ${p.sharing?.fps} fps", size = 12)
        }
        WrChip(if (p.sid == AppCore.session.mySid) "Preview" else "Watch", selected = true)
    }
}

@Composable
private fun HandsBanner(hands: List<Participant>, onApprove: (Participant) -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WR.surface).border(1.dp, WR.warn.copy(alpha = 0.4f), RoundedCornerShape(14.dp)).padding(12.dp)) {
        Tiny("Raised hands · ${hands.size}", color = WR.warn)
        Spacer(Modifier.height(8.dp))
        for (h in hands.take(4)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarView(h.avatar, 32.dp, uid = h.uid)
                Spacer(Modifier.width(10.dp))
                Text(h.displayName, color = WR.text, fontSize = 14.sp, modifier = Modifier.weight(1f), maxLines = 1)
                WrButton("Invite to speak", { onApprove(h) }, small = true)
            }
        }
    }
}

@Composable
private fun MicButton(muted: Boolean, ptt: Boolean, compact: Boolean = false) {
    val session = AppCore.session
    val level by AppCore.audio.level.collectAsState()
    val ring = (level * 6f).coerceIn(0f, 1f)
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "mic")
    val halo by pulse.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "halo")
    val d = if (compact) 60 else 72
    fun unmute() {
        val p = AppCore.platform
        if (p == null) session.setMuted(false)
        else p.requestMic { ok -> if (ok) session.setMuted(false) else AppCore.toast("Microphone access is off. Allow it in Android settings → Apps → HoMit → Permissions.") }
    }
    val base = Modifier.size(d.dp).clip(CircleShape)
        .background(if (muted) WR.surface3 else WR.accent)
        .border(3.dp, WR.accentBright, CircleShape)
    val m = if (ptt) {
        base.pointerInput(Unit) {
            detectTapGestures(onPress = {
                unmute()
                tryAwaitRelease()
                session.setMuted(true)
            })
        }
    } else base.clickable { if (muted) unmute() else session.setMuted(true) }
    Box(Modifier.size((d + 16).dp), contentAlignment = Alignment.Center) {
        if (muted) Box(Modifier.size((d + 16 * halo).dp).clip(CircleShape).border(2.dp, WR.accent.copy(alpha = 0.55f * (1f - halo)), CircleShape))
        else Box(Modifier.size((d + 14).dp).graphicsLayer { scaleX = 0.92f + ring * 0.12f; scaleY = 0.92f + ring * 0.12f; alpha = 0.25f + ring * 0.6f }.clip(CircleShape).background(WR.accent.copy(alpha = 0.5f)))
        Box(m, contentAlignment = Alignment.Center) {
            WrIcon(if (muted) "mic-off" else "mic", if (compact) 28.dp else 32.dp, if (muted) WR.accentBright else Color.White)
        }
    }
}

@Composable
private fun FloatingReaction(f: Floating, onDone: () -> Unit) {
    val p = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        p.animateTo(1f, tween(2300, easing = LinearEasing))
        onDone()
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val h = constraints.maxHeight
        val w = constraints.maxWidth
        Column(
            Modifier.offset { IntOffset((w * f.x).roundToInt(), (h * (0.92f - 0.8f * p.value)).roundToInt()) }.alpha(1f - p.value * p.value),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(f.emoji, fontSize = 34.sp)
            Text(f.name, color = WR.muted, fontSize = 10.sp, maxLines = 1)
        }
    }
}

/* ---------------- share sheet ---------------- */

@Composable
fun ShareSheet(onDismiss: () -> Unit) {
    val st by AppCore.settings.state.collectAsState()
    var res by remember { mutableStateOf(st.shareRes) }
    var fps by remember { mutableStateOf(st.shareFps) }
    WrSheet(onDismiss, "Share your screen") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Muted("Everyone in the room can watch. Your screen is sent directly to each person, so lower the quality in big rooms.")
            Spacer(Modifier.height(14.dp))
            Tiny("Resolution")
            Spacer(Modifier.height(8.dp))
            app.waveroom.ui.components.Segmented(listOf("240" to "240p", "480" to "480p", "720" to "720p", "1080" to "1080p"), res.toString(), { res = it.toInt() })
            Spacer(Modifier.height(14.dp))
            Tiny("Frame rate")
            Spacer(Modifier.height(8.dp))
            app.waveroom.ui.components.Segmented(listOf("5" to "5", "15" to "15", "30" to "30", "60" to "60"), fps.toString(), { fps = it.toInt() })
            Spacer(Modifier.height(10.dp))
            Muted("Android can't capture other apps' audio for sharing — only your microphone is heard.", size = 12)
            Spacer(Modifier.height(16.dp))
            WrButton("Start sharing", {
                AppCore.settings.update { it.copy(shareRes = res, shareFps = fps) }
                onDismiss()
                AppCore.platform?.requestProjection { data ->
                    if (data == null) {
                        AppCore.toast("Screen sharing was cancelled")
                    } else {
                        AppCore.session.announceShare(res, fps) { r ->
                            if (r.optBoolean("ok")) {
                                AppCore.screen.start(data, res, fps) { err ->
                                    AppCore.toast(err)
                                    AppCore.session.announceStopShare()
                                }
                            } else AppCore.toast(r.optString("error", "Could not start sharing"))
                        }
                    }
                }
            }, Modifier.fillMaxWidth(), icon = "monitor")
        }
    }
}
