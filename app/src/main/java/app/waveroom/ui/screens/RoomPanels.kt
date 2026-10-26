package app.waveroom.ui.screens

import androidx.compose.foundation.layout.fillMaxHeight
import app.waveroom.ui.components.RichText
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.ChatMsg
import app.waveroom.core.Participant
import app.waveroom.core.REACTIONS
import app.waveroom.core.canSpeak
import app.waveroom.core.fmtClock
import app.waveroom.ui.Nav
import app.waveroom.ui.Route
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.MenuEntry
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Row2
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

/* ============================== Chat ============================== */

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChatSheet(nav: Nav, onOpenChess: () -> Unit = {}, onDismiss: () -> Unit) {
    val session = AppCore.session
    val tick by session.tick.collectAsState()
    tick.let { }
    val s = session.room
    val msgs = s?.chat?.toList() ?: emptyList()
    var text by remember { mutableStateOf("") }
    var actionFor by remember { mutableStateOf<ChatMsg?>(null) }
    var reportMsg by remember { mutableStateOf<ChatMsg?>(null) }
    val listState = rememberLazyListState()
    val blocked by AppCore.social.blocked.collectAsState()

    LaunchedEffect(msgs.lastOrNull()?.id) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
        session.unreadChat.value = 0
    }
    fun send() {
        val t = text.trim()
        if (t.isEmpty()) return
        session.sendChat(t)
        text = ""
    }

    WrSheet(onDismiss, "Room chat", fullHeight = true) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.92f).imePadding()) {
            if (msgs.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { app.waveroom.ui.components.EmptyState("message", "No messages yet", "Say hi 👋") }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(msgs, key = { it.id }) { m ->
                        val mine = m.uid == session.myUid
                        val hidden = blocked.contains(m.uid) && !mine
                        Row(Modifier.fillMaxWidth().clickable(enabled = !m.deleted && !hidden && !m.text.startsWith(app.waveroom.chess.CHESS_MARK)) { actionFor = m }) {
                            AvatarView(m.avatar, 34.dp, uid = m.uid, mine = mine)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(if (mine) "You" else m.displayName, color = if (mine) WR.accentBright else WR.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Spacer(Modifier.width(8.dp))
                                    Tiny(fmtClock(m.at))
                                }
                                when {
                                    m.deleted -> Text("message removed", color = WR.faint, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                    hidden -> Text("message from a blocked person", color = WR.faint, fontSize = 13.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                                    m.text.startsWith(app.waveroom.chess.CHESS_MARK) -> {
                                        val rest = m.text.removePrefix(app.waveroom.chess.CHESS_MARK)
                                        ChessChallengeCard(rest.substringBefore('\u001F'), rest.substringAfter('\u001F', ""), onOpenChess)
                                    }
                                    else -> RichText(m.text, WR.text, WR.accentBright)
                                }
                                if (!m.deleted && m.reactions.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                        for ((e, who) in m.reactions) {
                                            WrChip("$e ${who.size}", selected = who.contains(session.myUid), onClick = { session.reactChat(m.id, e) })
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                WrField(text, { text = it }, Modifier.weight(1f), placeholder = "Message the room", maxLength = 500, ime = androidx.compose.ui.text.input.ImeAction.Send, onDone = { send() })
                Spacer(Modifier.width(8.dp))
                WrIconButton("send", { send() }, bg = WR.accent, tint = Color.White, enabled = text.isNotBlank())
            }
        }
    }

    val a = actionFor
    if (a != null) {
        WrSheet({ actionFor = null }) {
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in REACTIONS) {
                    Box(Modifier.size(46.dp).clip(CircleShape).background(WR.surface2).clickable { session.reactChat(a.id, e); actionFor = null }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (a.uid != session.myUid) {
                Row2("Message privately", null, "message", onClick = { actionFor = null; onDismiss(); nav.push(Route.Chat(a.uid)) })
                Row2("Report message", null, "flag", onClick = { reportMsg = a; actionFor = null })
            }
            if (a.uid == session.myUid || session.isHost) {
                Row2("Delete message", null, "trash", danger = true, onClick = { session.deleteChat(a.id); actionFor = null })
            }
        }
    }
    val rm = reportMsg
    if (rm != null) {
        ReportDialog("message", rm.id, "@${rm.username}", rm.text, { reportMsg = null })
    }
}

/* ============================== Person ============================== */

@Composable
fun PersonSheet(p: Participant, nav: Nav, onDismiss: () -> Unit) {
    val session = AppCore.session
    val me = session.me
    val mine = p.sid == session.mySid
    val following by AppCore.social.following.collectAsState()
    val blocked by AppCore.social.blocked.collectAsState()
    val connected by AppCore.connected.collectAsState()
    var vol by remember { mutableStateOf(AppCore.audio.userVolume(p.uid).toFloat()) }
    var banDialog by remember { mutableStateOf(false) }
    var kickConfirm by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf(false) }
    val host = me?.role == "host"

    WrSheet(onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AvatarView(p.avatar, 56.dp, uid = p.uid, mine = mine)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.displayName + if (mine) " (you)" else "", color = WR.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Muted("@${p.username} · " + if (p.role == "host") "host" else "member" + if (!mine && !connected.contains(p.uid)) " · connecting…" else "")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!mine && canSpeak(p.role)) {
            Column(Modifier.padding(horizontal = 20.dp, vertical = 4.dp)) {
                Tiny("Their volume")
                Slider(
                    value = vol, onValueChange = { vol = it; AppCore.audio.setUserVolume(p.uid, it.toDouble()) }, valueRange = 0f..2f,
                    colors = SliderDefaults.colors(thumbColor = WR.accentBright, activeTrackColor = WR.accent, inactiveTrackColor = WR.surface3)
                )
            }
        }
        Row2("View profile", null, "user", onClick = { onDismiss(); nav.push(Route.Profile(p.uid)) })
        if (!mine) {
            Row2("Message", if (connected.contains(p.uid)) "Delivered instantly" else "Queued until you're connected", "message", onClick = { onDismiss(); nav.push(Route.Chat(p.uid)) })
            Row2(if (following.contains(p.uid)) "Unfollow" else "Follow", null, "heart", onClick = { AppCore.follow(p.uid, !following.contains(p.uid)); onDismiss() })
        }
        if (host && !mine) {
            if (!p.muted) Row2("Mute microphone", null, "mic-off", onClick = { session.muteUser(p.sid); onDismiss() })
            Row2("Remove from room", null, "log-out", danger = true, onClick = { kickConfirm = true })
            Row2("Ban…", null, "ban", danger = true, onClick = { banDialog = true })
        }
        if (!mine) {
            Row2("Report", null, "flag", onClick = { report = true })
            Row2(if (blocked.contains(p.uid)) "Unblock" else "Block", "Blocked people can't connect to you", "ban", danger = true, onClick = {
                AppCore.block(p.uid, !blocked.contains(p.uid)); onDismiss()
            })
        }
    }
    if (kickConfirm) ConfirmDialog("Remove ${p.displayName}?", "They can rejoin with a new invite unless you ban them.", "Remove", { session.kick(p.sid); onDismiss() }, { kickConfirm = false }, danger = true)
    if (banDialog) {
        AlertDialog(
            onDismissRequest = { banDialog = false }, containerColor = WR.surface2,
            title = { Text("Ban ${p.displayName}", color = WR.text) },
            text = { Muted("They are removed now and can't rejoin this room for the chosen time.") },
            confirmButton = {
                Column {
                    TextButton(onClick = { session.ban(p.sid, 3_600_000L); banDialog = false; onDismiss() }) { Text("1 hour", color = WR.danger) }
                    TextButton(onClick = { session.ban(p.sid, 86_400_000L); banDialog = false; onDismiss() }) { Text("24 hours", color = WR.danger) }
                    TextButton(onClick = { session.ban(p.sid, 0L); banDialog = false; onDismiss() }) { Text("Permanently", color = WR.danger) }
                }
            },
            dismissButton = { TextButton(onClick = { banDialog = false }) { Text("Cancel", color = WR.muted) } }
        )
    }
    if (report) ReportDialog("user", p.uid, "@${p.username}", "", { report = false })
}

/* ============================== Report ============================== */

private val REPORT_CATEGORIES = listOf(
    "spam" to "Spam", "harassment" to "Harassment", "hate" to "Hate", "sexual" to "Sexual content",
    "violence" to "Violence", "other" to "Other"
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReportDialog(type: String, id: String, label: String, excerpt: String, onDismiss: () -> Unit) {
    var cat by remember { mutableStateOf("spam") }
    var details by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = WR.surface2,
        title = { Text("Report $label", color = WR.text, fontWeight = FontWeight.SemiBold) },
        text = {
            Column {
                Muted("Goes to the room's host. There is no central moderator — the host decides.", size = 12)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for ((k, v) in REPORT_CATEGORIES) WrChip(v, selected = cat == k, onClick = { cat = k })
                }
                Spacer(Modifier.height(10.dp))
                WrField(details, { details = it }, placeholder = "Details (optional)", singleLine = false, maxLines = 4, maxLength = 1000)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                AppCore.session.fileReport(type, id, label, cat, details, excerpt) { ok ->
                    AppCore.toast(if (ok) "Report sent to the host" else "Could not send the report")
                }
                onDismiss()
            }) { Text("Send report", color = WR.accentBright, fontWeight = FontWeight.SemiBold) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WR.muted) } }
    )
}

/* ============================== Room settings (host) ============================== */

@Composable
fun RoomSettingsSheet(onDismiss: () -> Unit) {
    val session = AppCore.session
    val tick by session.tick.collectAsState()
    tick.let { }
    val s = session.room ?: return
    var title by remember { mutableStateOf(s.meta.title) }
    var topic by remember { mutableStateOf(s.meta.topic) }
    var tags by remember { mutableStateOf(s.meta.tags.joinToString(", ")) }
    var error by remember { mutableStateOf<String?>(null) }
    var endConfirm by remember { mutableStateOf(false) }
    val contacts by AppCore.social.contacts.collectAsState()

    WrSheet(onDismiss, "Room settings", fullHeight = true) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).imePadding()) {
            WrField(title, { title = it }, label = "Title", maxLength = 60)
            Spacer(Modifier.height(12.dp))
            WrField(topic, { topic = it }, label = "Topic", singleLine = false, maxLines = 3, maxLength = 140)
            Spacer(Modifier.height(12.dp))
            WrField(tags, { tags = it }, label = "Tags")
            if (error != null) Text(error!!, color = WR.danger, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Spacer(Modifier.height(12.dp))
            WrButton("Save changes", {
                val tg = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                session.editRoom(title, topic, tg) { r -> if (r.optBoolean("ok")) { error = null; AppCore.toast("Saved") } else error = r.optString("error") }
            }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary, small = true)
            Spacer(Modifier.height(10.dp))
            SwitchRow("Lock room", "Nobody new can join while locked.", s.meta.locked, { session.setLocked(it) })
            val bans = s.meta.bans.entries.toList()
            if (bans.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Tiny("Banned")
                for ((uid, until) in bans) {
                    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(contacts[uid]?.displayName ?: uid.take(10), color = WR.text, fontSize = 14.sp)
                            Muted(if (until == 0L) "permanent" else "until " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until)), size = 12)
                        }
                        WrButton("Unban", { session.unban(uid) }, kind = BtnKind.Secondary, small = true)
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            WrButton("Clear blackboard", { session.boardClear() }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary, icon = "eraser")
            Spacer(Modifier.height(10.dp))
            WrButton("End room for everyone", { endConfirm = true }, Modifier.fillMaxWidth(), kind = BtnKind.Danger, icon = "log-out")
            Spacer(Modifier.height(20.dp))
        }
    }
    if (endConfirm) ConfirmDialog("End this room?", "Everyone is disconnected. The chat and blackboard stay saved on this phone.", "End room", { onDismiss(); session.endRoom() }, { endConfirm = false }, danger = true)
}
