package app.waveroom.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.Brush
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.animation.togetherWith
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.CodeResult
import app.waveroom.core.NearbyRoom
import app.waveroom.core.SavedRoom
import app.waveroom.core.jsonOf
import app.waveroom.core.timeAgo
import app.waveroom.ui.Nav
import app.waveroom.ui.Route
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.EmptyState
import app.waveroom.ui.components.MenuEntry
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Row2
import app.waveroom.ui.components.SectionTitle
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrChip
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrMenu
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.components.Hairline
import app.waveroom.ui.theme.WR

@Composable
fun HomeScreen(nav: Nav) {
    var tab by remember { mutableStateOf(nav.homeTab) }
    val convs by AppCore.dms.convs.collectAsState()
    val unread = convs.values.sumOf { it.unread }
    val st by AppCore.settings.state.collectAsState()
    androidx.compose.runtime.LaunchedEffect(st.nearby) {
        if (st.nearby) AppCore.lan.startListening() else AppCore.lan.stopListening()
    }
    Column(Modifier.fillMaxSize().background(WR.bg)) {
        var drag by remember { mutableStateOf(0f) }
        Box(
            Modifier.weight(1f).tabSwipe(
                onStart = { drag = 0f },
                onDrag = { drag += it },
                onEnd = {
                    val t = if (drag < -110f) minOf(2, tab + 1) else if (drag > 110f) maxOf(0, tab - 1) else tab
                    if (t != tab) { tab = t; nav.homeTab = t }
                }
            )
        ) {
            androidx.compose.animation.AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    if (!WR.animations) androidx.compose.animation.EnterTransition.None togetherWith androidx.compose.animation.ExitTransition.None
                    else (androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(220)) { it / 8 * dir } + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220))) togetherWith
                        (androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(160)) { -it / 8 * dir } + androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120)))
                },
                label = "hometab"
            ) { t ->
                when (t) {
                    0 -> RoomsTab(nav)
                    1 -> MessagesTab(nav)
                    else -> YouTab(nav)
                }
            }
        }
        Hairline()
        Row(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(WR.surface2, WR.surface))).navigationBarsPadding().padding(vertical = 6.dp)) {
            TabItem("Rooms", "waveform", tab == 0, 0, Modifier.weight(1f)) { tab = 0; nav.homeTab = 0 }
            TabItem("Messages", "message", tab == 1, unread, Modifier.weight(1f)) { tab = 1; nav.homeTab = 1 }
            TabItem("You", "user", tab == 2, 0, Modifier.weight(1f)) { tab = 2; nav.homeTab = 2 }
        }
    }
}

@Composable
private fun TabItem(label: String, icon: String, on: Boolean, badge: Int, modifier: Modifier, onClick: () -> Unit) {
    Column(modifier.clickable(onClick = onClick).padding(vertical = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.height(30.dp).width(58.dp).clip(RoundedCornerShape(50))
                .then(if (on) Modifier.background(Brush.horizontalGradient(listOf(WR.accent.copy(alpha = 0.55f), WR.accentBright.copy(alpha = 0.28f)))).border(1.dp, WR.accentBright.copy(alpha = 0.35f), RoundedCornerShape(50)) else Modifier),
            contentAlignment = Alignment.Center
        ) {
            WrIcon(icon, 22.dp, if (on) Color.White else WR.faint)
            if (badge > 0) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 3.dp, end = 10.dp).size(9.dp).clip(CircleShape).background(WR.neon))
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(label, color = if (on) WR.text else WR.faint, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

/* ============================== Rooms ============================== */

@Composable
private fun RoomsTab(nav: Nav) {
    val saved by AppCore.rooms.rooms.collectAsState()
    val nearby by AppCore.lan.nearby.collectAsState()
    val settings by AppCore.settings.state.collectAsState()
    var joining by remember { mutableStateOf<String?>(null) }
    var restart by remember { mutableStateOf<SavedRoom?>(null) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var deleteRoom by remember { mutableStateOf<SavedRoom?>(null) }


    fun joinNearby(r: NearbyRoom) {
        val p = AppCore.profile.profile.value ?: return
        joining = r.roomId + r.ip
        AppCore.platform?.requestMic { }
        AppCore.lan.join(
            r, jsonOf("uid" to AppCore.profile.uid, "name" to p.displayName),
            accept = { code, cb -> AppCore.handleCode(code, autoDeliver = false) { res -> cb((res as? CodeResult.Answer)?.code) } },
            done = { err ->
                joining = null
                if (err != null) AppCore.toast(err)
            }
        )
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
        item {
            Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
                Text("Rooms", color = WR.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, fontFamily = WR.display, letterSpacing = (-0.5).sp, modifier = Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(12.dp))
                app.waveroom.ui.components.HeroCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Talk. Just talk.", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = WR.display)
                        Spacer(Modifier.height(4.dp))
                        Text("Start a room, or connect to one with a QR code or on the same Wi-Fi.", color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, lineHeight = 20.sp)
                        app.waveroom.ui.components.NeonWave(Modifier.fillMaxWidth().height(56.dp).padding(vertical = 6.dp), amplitude = 16.dp, stroke = 2.5.dp)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            WrButton("New room", { nav.push(Route.Create) }, Modifier.weight(1f), icon = "plus")
                            WrButton("Join", { nav.push(Route.Join("")) }, Modifier.weight(1f), kind = BtnKind.Secondary, icon = "scan")
                        }
                    }
                }
            }
        }
        if (settings.nearby) {
            item { SectionTitle("Nearby · same network") }
            if (nearby.isEmpty()) {
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        WrIcon("wifi", 18.dp, WR.faint)
                        Spacer(Modifier.width(10.dp))
                        Muted("Listening for rooms on this Wi-Fi… none yet.", size = 13)
                    }
                }
            } else {
                items(nearby, key = { it.roomId + it.ip }) { r ->
                    NearbyRow(r, joining == r.roomId + r.ip) { joinNearby(r) }
                }
            }
        }
        item { SectionTitle("Your rooms") }
        if (saved.isEmpty()) {
            item {
                EmptyState("waveform", "No rooms yet", "Rooms you start are remembered here, with their chat and blackboard, so you can open them again.")
            }
        } else {
            items(saved, key = { it.meta.id }) { r ->
                Box {
                    SavedRow(r, onClick = { restart = r }, onMore = { menuFor = r.meta.id })
                    WrMenu(menuFor == r.meta.id, { menuFor = null }, listOf(
                        MenuEntry("Open again", "play") { restart = r },
                        MenuEntry("Forget this room", "trash", danger = true) { deleteRoom = r }
                    ))
                }
            }
        }
    }

    val r = restart
    if (r != null) {
        ConfirmDialog("Open “${r.meta.title}”?", "You'll be the host. People can join with a QR code or from the Nearby list.", "Open room", {
            AppCore.platform?.requestMic { }
            AppCore.session.createRoom(
                r.meta.title, r.meta.topic, r.meta.tags, r.meta.unlisted, r.meta.maxSpeakers,
                restore = AppCore.rooms.loadData(r.meta.id), id = r.meta.id
            )
        }, { restart = null })
    }
    val d = deleteRoom
    if (d != null) {
        ConfirmDialog("Forget this room?", "Its saved chat and blackboard will be deleted from this phone.", "Forget", {
            AppCore.rooms.delete(d.meta.id)
        }, { deleteRoom = null }, danger = true)
    }
}

@Composable
private fun NearbyRow(r: NearbyRoom, busy: Boolean, onJoin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onJoin).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(WR.accentSoft), contentAlignment = Alignment.Center) {
            WrIcon("wifi", 20.dp, WR.accentBright)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.title, color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.locked) {
                    Spacer(Modifier.width(6.dp))
                    WrIcon("lock", 13.dp, WR.faint)
                }
            }
            Muted("${r.hostName} · ${r.count} here" + (if (r.topic.isNotEmpty()) " · ${r.topic}" else ""), size = 12, maxLines = 1)
        }
        WrChip(if (busy) "Connecting…" else "Join", selected = !busy)
    }
}

@Composable
private fun SavedRow(r: SavedRoom, onClick: () -> Unit, onMore: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(WR.surface2), contentAlignment = Alignment.Center) {
            WrIcon(if (r.meta.unlisted) "lock" else "waveform", 20.dp, WR.muted)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(r.meta.title, color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Muted("${r.totalJoins} joins · peak ${r.peak} · ${timeAgo(r.lastActiveAt)}", size = 12, maxLines = 1)
        }
        WrIconButton("more-v", onMore, tint = WR.muted)
    }
}

/* ============================== Messages ============================== */

@Composable
private fun MessagesTab(nav: Nav) {
    val convs by AppCore.dms.convs.collectAsState()
    val contacts by AppCore.social.contacts.collectAsState()
    val connected by AppCore.connected.collectAsState()
    var sheet by remember { mutableStateOf(false) }
    val groups by AppCore.groups.groups.collectAsState()
    val list = convs.values.filter { it.uid != "saved" && it.messages.isNotEmpty() && (!it.uid.startsWith("g:") || groups.containsKey(it.uid.removePrefix("g:"))) }
        .sortedByDescending { it.lastAt }
    val saved = convs["saved"]

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.statusBarsPadding().padding(start = 20.dp, end = 8.dp, top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Messages", color = WR.text, fontSize = 32.sp, fontWeight = FontWeight.SemiBold, fontFamily = WR.display, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f))
            WrIconButton("users", { nav.push(Route.NewGroup) }, tint = WR.muted)
            WrIconButton("user-plus", { sheet = true }, tint = WR.muted)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            item(key = "requests") { FriendRequests() }
            item(key = "nearby") { NearbyPeople(nav) }
            item(key = "saved") {
                Row(Modifier.fillMaxWidth().clickable { nav.push(Route.Chat("saved")) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(48.dp).clip(CircleShape).background(WR.accent), contentAlignment = Alignment.Center) { WrIcon("bookmark", 22.dp, Color.White) }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Saved messages", color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Muted(saved?.messages?.lastOrNull()?.let { AppCore.dmHub.preview(it) } ?: "Notes, links and files for yourself", size = 13, maxLines = 1)
                    }
                }
            }
            if (list.isEmpty()) {
                item(key = "empty") {
                    EmptyState("message", "No conversations yet", "Start a group, or add a friend with a QR code.") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WrButton("New group", { nav.push(Route.NewGroup) }, small = true, icon = "users")
                            WrButton("Add a friend", { sheet = true }, small = true, kind = BtnKind.Secondary, icon = "user-plus")
                        }
                    }
                }
            }
            items(list, key = { it.uid }) { c ->
                val isG = c.uid.startsWith("g:")
                val grp = if (isG) groups[c.uid.removePrefix("g:")] else null
                val ct = contacts[c.uid]
                val last = c.messages.lastOrNull { it.kind != "call" } ?: c.messages.lastOrNull()
                Row(Modifier.fillMaxWidth().clickable { nav.push(Route.Chat(c.uid)) }.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (isG) GroupAvatar(grp?.emoji ?: "💬", grp?.hue ?: 265, 48)
                    else AvatarView(ct?.avatar ?: app.waveroom.core.Avatar(), 48.dp, uid = c.uid)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(if (isG) (grp?.name ?: "Group") else (ct?.displayName ?: "Unknown"), color = WR.text, fontSize = 16.sp, fontWeight = if (c.unread > 0) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1, modifier = Modifier.weight(1f, fill = false))
                            if (!isG && connected.contains(c.uid)) {
                                Spacer(Modifier.width(6.dp))
                                Muted("connected", size = 11)
                            }
                            if (c.muted) { Spacer(Modifier.width(6.dp)); WrIcon("bell-off", 12.dp, WR.faint) }
                        }
                        val who = when {
                            last == null -> ""
                            last.from == AppCore.profile.uid -> "You: "
                            isG && last.from.isNotEmpty() -> (last.authorName.ifEmpty { AppCore.contactName(last.from) }) + ": "
                            else -> ""
                        }
                        Muted(who + (last?.let { AppCore.dmHub.preview(it) } ?: ""), size = 13, maxLines = 1)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Tiny(timeAgo(c.lastAt))
                        if (c.unread > 0) {
                            Spacer(Modifier.height(4.dp))
                            Box(Modifier.clip(CircleShape).background(if (c.muted) WR.surface3 else WR.accent).padding(horizontal = 7.dp, vertical = 2.dp)) {
                                Text(c.unread.toString(), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
    if (sheet) AddFriendSheet(nav) { sheet = false }
}

@Composable
fun AddFriendSheet(nav: Nav, onDismiss: () -> Unit) {
    var q by remember { mutableStateOf("") }
    val results by app.waveroom.Discovery.results.collectAsState()
    val searching by app.waveroom.Discovery.searching.collectAsState()
    val note by app.waveroom.Discovery.searchNote.collectAsState()
    val contacts by AppCore.social.contacts.collectAsState()
    androidx.compose.runtime.DisposableEffect(Unit) {
        app.waveroom.Discovery.results.value = emptyList(); app.waveroom.Discovery.searchNote.value = ""
        onDispose { }
    }
    WrSheet(onDismiss, "Add someone") {
        Column(Modifier.padding(horizontal = 20.dp)) {
            WrField(q, { q = it }, label = "Find by @username, or paste a link", placeholder = "@name")
            Spacer(Modifier.height(10.dp))
            WrButton(if (searching) "Searching…" else "Search", { app.waveroom.Discovery.search(q) }, Modifier.fillMaxWidth(), enabled = q.isNotBlank() && !searching)
            if (note.isNotEmpty()) { Spacer(Modifier.height(8.dp)); Muted(note, size = 13) }
        }
        for (c in results) {
            val already = contacts[c.uid]?.pair?.isNotEmpty() == true
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                AvatarView(c.avatar, 46.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(c.name, color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted("@${c.username} · id ${c.uid.take(5)}", size = 12, maxLines = 1)
                }
                if (already) WrChip("Added") else WrButton("Add", { app.waveroom.Discovery.sendRequest(c) }, small = true)
            }
        }
        Hairline()
        Row2("Share my link", "Send it in any messenger", "share", onClick = {
            val l = app.waveroom.Discovery.myLink()
            if (l != null) AppCore.platform?.shareText("Add me on HoMit: $l")
        })
        Row2("Show my QR code", "They scan it with HoMit", "qr", onClick = { onDismiss(); nav.push(Route.Invite("dm")) })
        Row2("Scan or paste a code", null, "scan", onClick = { onDismiss(); nav.push(Route.Join("")) })
        NearbyPeople(nav, compact = true)
    }
}

/** Friend requests waiting for a yes or a no. */
@Composable
fun FriendRequests() {
    val reqs by app.waveroom.Discovery.requests.collectAsState()
    if (reqs.isEmpty()) return
    SectionTitle("Friend requests")
    for (r in reqs) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AvatarView(r.card.avatar, 46.dp)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(r.card.name, color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Muted("@${r.card.username} · wants to connect", size = 12, maxLines = 1)
            }
            WrButton("Accept", { app.waveroom.Discovery.accept(r) }, small = true)
            Spacer(Modifier.width(6.dp))
            WrIconButton("x", { app.waveroom.Discovery.decline(r) }, tint = WR.muted)
        }
    }
}

/* ============================== You ============================== */

@Composable
private fun YouTab(nav: Nav) {
    val p by AppCore.profile.profile.collectAsState()
    val reports by AppCore.mod.reports.collectAsState()
    val open = reports.count { it.status == "open" && !it.outgoing }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        val me = p
        Row(Modifier.statusBarsPadding().padding(20.dp).clickable { nav.push(Route.EditProfile) }, verticalAlignment = Alignment.CenterVertically) {
            if (me != null) AvatarView(me.avatar, 64.dp, mine = true)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(me?.displayName ?: "", color = WR.text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Muted("@${me?.username ?: ""}")
            }
            WrIcon("chevron-right", 20.dp, WR.faint)
        }
        Hairline()
        Row2("Edit profile", null, "pencil", onClick = { nav.push(Route.EditProfile) })
        Row2("People", "Following, followers, blocked", "users", onClick = { nav.push(Route.People) })
        Row2("Moderation desk", if (open > 0) "$open open report(s)" else "Reports and audit trail for your rooms", "shield-alert", onClick = { nav.push(Route.Moderation) })
        Row2("Settings", "Voice effects, devices, network, privacy", "settings", onClick = { nav.push(Route.Settings) })
        Hairline()
        Row2("Help", "How connecting works, tips", "book", onClick = { nav.push(Route.Help) })
        Row2("Privacy & terms", null, "shield", onClick = { nav.push(Route.Legal) })
        Row2("About HoMit", null, "info", onClick = { nav.push(Route.About) })
    }
}


/** People on the same Wi-Fi who are running HoMit: one tap connects, no codes. */
@Composable
fun NearbyPeople(nav: Nav, compact: Boolean = false) {
    val people by AppCore.lan.people.collectAsState()
    val connected by AppCore.connected.collectAsState()
    val settings by AppCore.settings.state.collectAsState()
    var busy by remember { mutableStateOf<String?>(null) }
    if (!settings.nearby) return
    val list = people.filter { it.uid != AppCore.profile.uid }
    if (list.isEmpty() && compact) {
        Row(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            WrIcon("wifi", 18.dp, WR.faint)
            Spacer(Modifier.width(10.dp))
            Muted("Looking for people on this Wi-Fi… make sure both phones are on the same network.", size = 13)
        }
        return
    }
    if (list.isEmpty()) return
    SectionTitle("On this Wi-Fi")
    for (p in list) {
        val on = connected.contains(p.uid)
        Row(
            Modifier.fillMaxWidth().clickable(enabled = busy == null) {
                if (on) nav.push(Route.Chat(p.uid))
                else {
                    busy = p.uid
                    AppCore.connectNearby(p) { err ->
                        busy = null
                        AppCore.toast(err ?: "Connecting to ${p.name}…")
                    }
                }
            }.padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AvatarView(app.waveroom.core.Avatar.from(p.avatar), 44.dp, uid = p.uid)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(p.name, color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                Muted("@${p.username}", size = 12)
            }
            if (busy == p.uid) androidx.compose.material3.CircularProgressIndicator(color = WR.accentBright, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            else WrChip(if (on) "Open chat" else "Connect", selected = !on)
        }
    }
}

/** Horizontal swipe between the home tabs; ignored when a child (a scrolling row, a slider) takes the drag. */
private fun Modifier.tabSwipe(onStart: () -> Unit, onDrag: (Float) -> Unit, onEnd: () -> Unit): Modifier =
    this.pointerInput(Unit) {
        detectHorizontalDragGestures(
            onDragStart = { onStart() },
            onDragEnd = { onEnd() },
            onDragCancel = { },
            onHorizontalDrag = { _, d -> onDrag(d) }
        )
    }
