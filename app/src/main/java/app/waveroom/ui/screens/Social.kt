package app.waveroom.ui.screens

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.AVATAR_EMOJIS
import app.waveroom.core.Avatar
import app.waveroom.core.Contact
import app.waveroom.core.MyProfile
import app.waveroom.core.fmtClock
import app.waveroom.core.timeAgo
import app.waveroom.ui.Nav
import app.waveroom.ui.Route
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.EmptyState
import app.waveroom.ui.components.ImageUtil
import app.waveroom.ui.components.MenuEntry
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.ScreenHeader
import app.waveroom.ui.components.Segmented
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrMenu
import app.waveroom.ui.theme.WR

/* ============================== Direct messages ============================== */

@Composable
fun ChatScreen(nav: Nav, uid: String) {
    val convs by AppCore.dms.convs.collectAsState()
    val contacts by AppCore.social.contacts.collectAsState()
    val connected by AppCore.connected.collectAsState()
    val blocked by AppCore.social.blocked.collectAsState()
    val ct = contacts[uid]
    val conv = convs[uid]
    val msgs = conv?.messages ?: emptyList()
    var text by remember { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    val online = connected.contains(uid)

    DisposableEffect(uid) {
        AppCore.dmHub.openChat.value = uid
        AppCore.dms.markRead(uid)
        onDispose { AppCore.dmHub.openChat.value = null }
    }
    LaunchedEffect(msgs.size) {
        AppCore.dms.markRead(uid)
        if (msgs.isNotEmpty()) list.animateScrollToItem(msgs.size - 1)
    }

    Column(Modifier.fillMaxSize().background(WR.bg).imePadding()) {
        ScreenHeader(ct?.displayName ?: "Chat", { nav.pop() }, subtitle = if (online) "connected now" else "offline — messages wait for you to reconnect") {
            Box {
                WrIconButton("more-v", { menu = true })
                WrMenu(menu, { menu = false }, listOf(
                    MenuEntry("View profile", "user") { nav.push(Route.Profile(uid)) },
                    MenuEntry(if (blocked.contains(uid)) "Unblock" else "Block", "ban", danger = true) { AppCore.block(uid, !blocked.contains(uid)) }
                ))
            }
        }
        if (!online) {
            Row(
                Modifier.fillMaxWidth().background(WR.surface).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Muted("Not connected right now.", size = 12, modifier = Modifier.weight(1f))
                WrButton("Reconnect", { nav.push(Route.Invite("dm")) }, small = true, kind = BtnKind.Secondary)
            }
        }
        if (msgs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Muted("Say hello 👋") }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(msgs, key = { it.id }) { m ->
                    val mine = m.from == AppCore.profile.uid
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
                        Box(
                            Modifier.clip(RoundedCornerShape(16.dp)).background(if (mine) WR.accent else WR.surface2)
                                .clickable(enabled = m.status == "failed") { AppCore.dmHub.retry(uid, m.id) }
                                .padding(horizontal = 14.dp, vertical = 9.dp)
                        ) { Text(m.text, color = if (mine) Color.White else WR.text, fontSize = 15.sp) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Tiny(fmtClock(m.at))
                            if (mine) {
                                Spacer(Modifier.width(6.dp))
                                when (m.status) {
                                    "pending" -> Tiny("waiting to deliver")
                                    "failed" -> Tiny("${m.error.ifEmpty { "not delivered" }} · tap to retry", color = WR.danger)
                                    else -> WrIcon("check", 11.dp, WR.ok)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            WrField(text, { text = it }, Modifier.weight(1f), placeholder = "Message", maxLength = 2000, ime = androidx.compose.ui.text.input.ImeAction.Send, onDone = {
                if (text.isNotBlank()) { AppCore.dmHub.send(uid, text); text = "" }
            })
            Spacer(Modifier.width(8.dp))
            WrIconButton("send", { if (text.isNotBlank()) { AppCore.dmHub.send(uid, text); text = "" } }, bg = WR.accent, tint = Color.White, enabled = text.isNotBlank())
        }
    }
}

/* ============================== Profile of somebody else ============================== */

@Composable
fun ProfileScreen(nav: Nav, uid: String) {
    val contacts by AppCore.social.contacts.collectAsState()
    val following by AppCore.social.following.collectAsState()
    val followers by AppCore.social.followers.collectAsState()
    val blocked by AppCore.social.blocked.collectAsState()
    val connected by AppCore.connected.collectAsState()
    val mine = uid == AppCore.profile.uid
    val me by AppCore.profile.profile.collectAsState()
    val c: Contact? = contacts[uid]
    var confirmBlock by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().background(WR.bg)) {
        ScreenHeader("Profile", { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val av = if (mine) me?.avatar else c?.avatar
            AvatarView(av ?: Avatar(), 96.dp, uid = uid, mine = mine)
            Spacer(Modifier.height(14.dp))
            Text((if (mine) me?.displayName else c?.displayName) ?: "Unknown", color = WR.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
            Muted("@" + ((if (mine) me?.username else c?.username) ?: ""))
            val pron = if (mine) me?.pronouns else c?.pronouns
            if (!pron.isNullOrEmpty()) Muted(pron, size = 12)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!mine && followers.contains(uid)) app.waveroom.ui.components.WrChip("Follows you", selected = true)
                if (!mine && connected.contains(uid)) app.waveroom.ui.components.WrChip("Connected", icon = "wifi")
            }
            val bio = if (mine) me?.bio else c?.bio
            if (!bio.isNullOrEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(bio, color = WR.text, fontSize = 15.sp, textAlign = TextAlign.Center)
            }
            if (!mine && c != null && c.lastSeen > 0) {
                Spacer(Modifier.height(10.dp))
                Tiny("Last connected ${timeAgo(c.lastSeen)}")
            }
            Spacer(Modifier.height(24.dp))
            if (mine) {
                WrButton("Edit profile", { nav.push(Route.EditProfile) }, Modifier.fillMaxWidth(), icon = "pencil")
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WrButton(if (following.contains(uid)) "Following" else "Follow", { AppCore.follow(uid, !following.contains(uid)) }, Modifier.weight(1f), kind = if (following.contains(uid)) BtnKind.Secondary else BtnKind.Primary, icon = "heart")
                    WrButton("Message", { nav.push(Route.Chat(uid)) }, Modifier.weight(1f), kind = BtnKind.Secondary, icon = "message")
                }
                Spacer(Modifier.height(10.dp))
                WrButton(if (blocked.contains(uid)) "Unblock" else "Block", { if (blocked.contains(uid)) AppCore.block(uid, false) else confirmBlock = true }, Modifier.fillMaxWidth(), kind = BtnKind.Danger, icon = "ban")
            }
        }
    }
    if (confirmBlock) ConfirmDialog("Block this person?", "They can't connect to you and you won't see their messages.", "Block", { AppCore.block(uid, true) }, { confirmBlock = false }, danger = true)
}

/* ============================== People ============================== */

@Composable
fun PeopleScreen(nav: Nav) {
    val contacts by AppCore.social.contacts.collectAsState()
    val following by AppCore.social.following.collectAsState()
    val followers by AppCore.social.followers.collectAsState()
    val blocked by AppCore.social.blocked.collectAsState()
    val connected by AppCore.connected.collectAsState()
    var tab by remember { mutableStateOf("met") }
    val uids: List<String> = when (tab) {
        "following" -> following.toList()
        "followers" -> followers.toList()
        "blocked" -> blocked.toList()
        else -> contacts.values.sortedByDescending { it.lastSeen }.map { it.uid }
    }
    Column(Modifier.fillMaxSize().background(WR.bg)) {
        ScreenHeader("People", { nav.pop() })
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Segmented(listOf("met" to "Met", "following" to "Following", "followers" to "Followers", "blocked" to "Blocked"), tab, { tab = it })
        }
        if (uids.isEmpty()) {
            EmptyState("users", "Nobody here yet", "People appear after you've connected with them in a room or with a QR code.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(uids, key = { it }) { uid ->
                    val c = contacts[uid]
                    Row(Modifier.fillMaxWidth().clickable { nav.push(Route.Profile(uid)) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(c?.avatar ?: Avatar(), 44.dp, uid = uid)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c?.displayName ?: "Unknown", color = WR.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            Muted("@${c?.username ?: ""}" + if (connected.contains(uid)) " · connected" else "", size = 12)
                        }
                        if (tab == "blocked") WrButton("Unblock", { AppCore.block(uid, false) }, small = true, kind = BtnKind.Secondary)
                        else WrButton(if (following.contains(uid)) "Following" else "Follow", { AppCore.follow(uid, !following.contains(uid)) }, small = true, kind = if (following.contains(uid)) BtnKind.Secondary else BtnKind.Primary)
                    }
                }
            }
        }
    }
}

/* ============================== Edit profile ============================== */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditProfileScreen(nav: Nav) {
    val me by AppCore.profile.profile.collectAsState()
    val ctx = LocalContext.current
    val p0 = me ?: return
    var name by remember { mutableStateOf(p0.displayName) }
    var handle by remember { mutableStateOf(p0.username) }
    var bio by remember { mutableStateOf(p0.bio) }
    var pron by remember { mutableStateOf(p0.pronouns) }
    var emoji by remember { mutableStateOf(p0.avatar.emoji) }
    var hue by remember { mutableStateOf(p0.avatar.hue) }
    var error by remember { mutableStateOf<String?>(null) }
    val photoVer by AppCore.profile.photoVersion.collectAsState()

    Column(Modifier.fillMaxSize().background(WR.bg).imePadding()) {
        ScreenHeader("Edit profile", { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarView(Avatar(emoji, hue), 80.dp, mine = true)
                Spacer(Modifier.width(16.dp))
                Column {
                    WrButton("Choose photo", {
                        AppCore.platform?.pickImage { uri ->
                            if (uri != null) {
                                val b = ImageUtil.squareJpeg(ctx, uri)
                                if (b != null) AppCore.profile.setPhoto(b) else AppCore.toast("Could not read that image")
                            }
                        }
                    }, small = true, kind = BtnKind.Secondary, icon = "image")
                    if (AppCore.profile.photo != null && photoVer >= 0) {
                        Spacer(Modifier.height(6.dp))
                        WrButton("Remove photo", { AppCore.profile.setPhoto(null) }, small = true, kind = BtnKind.Ghost)
                    }
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in HUES) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).background(WR.hue(h, 0.55f, 0.42f))
                            .border(if (h == hue) 2.dp else 0.dp, WR.text, CircleShape).clickable { hue = h }
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in AVATAR_EMOJIS) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (e == emoji) WR.accentSoft else WR.surface2)
                            .border(1.dp, if (e == emoji) WR.accent else WR.border, CircleShape).clickable { emoji = e },
                        contentAlignment = Alignment.Center
                    ) { Text(e, fontSize = 19.sp) }
                }
            }
            Spacer(Modifier.height(20.dp))
            WrField(name, { name = it.take(30) }, label = "Display name")
            Spacer(Modifier.height(12.dp))
            WrField(handle, { handle = cleanHandle(it) }, label = "Username", leadingIcon = "user")
            Spacer(Modifier.height(12.dp))
            WrField(pron, { pron = it.take(20) }, label = "Pronouns (optional)")
            Spacer(Modifier.height(12.dp))
            WrField(bio, { bio = it.take(160) }, label = "Bio", singleLine = false, maxLines = 4, minLines = 2)
            if (error != null) Text(error!!, color = WR.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
            Spacer(Modifier.height(8.dp))
            Muted("Others see your updated profile the next time you connect.", size = 12)
        }
        Box(Modifier.padding(20.dp).navigationBarsPadding()) {
            WrButton("Save", {
                if (name.trim().length < 2) { error = "Display name needs 2+ characters."; return@WrButton }
                if (handle.length < 3) { error = "Username needs 3+ characters."; return@WrButton }
                AppCore.profile.save(MyProfile(handle, name.trim(), bio.trim(), pron.trim(), Avatar(emoji, hue), p0.createdAt))
                AppCore.toast("Profile saved")
                nav.pop()
            }, Modifier.fillMaxWidth())
        }
    }
}
