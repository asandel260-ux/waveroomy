package app.waveroom.ui.screens

import app.waveroom.ui.components.EmptyState
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.itemsIndexed
import android.os.Build
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.waveroom.AppCore
import app.waveroom.core.Avatar
import app.waveroom.core.DmMessage
import app.waveroom.core.Media
import app.waveroom.core.REACTIONS
import app.waveroom.core.fmtClock
import app.waveroom.room.DmHub
import app.waveroom.ui.Nav
import app.waveroom.ui.Route
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.InputDialog
import app.waveroom.ui.components.MenuEntry
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.RichText
import app.waveroom.ui.components.Row2
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrIconButton
import app.waveroom.ui.components.WrMenu
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.components.firstUrl
import app.waveroom.ui.components.wrapSelection
import app.waveroom.ui.theme.WR
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder

@Composable
fun rememberImageLoader(): ImageLoader {
    val ctx = LocalContext.current
    return remember {
        ImageLoader.Builder(ctx).components {
            if (Build.VERSION.SDK_INT >= 28) add(ImageDecoderDecoder.Factory()) else add(GifDecoder.Factory())
        }.build()
    }
}

fun authorColor(uid: String): Color = WR.hsl(((uid.hashCode() and 0x7fffffff) % 360).toFloat(), 0.65f, if (WR.isLight) 0.35f else 0.7f)

fun fmtSize(n: Long): String = when {
    n >= 1024 * 1024 -> String.format("%.1f MB", n / 1048576.0)
    n >= 1024 -> "${n / 1024} KB"
    else -> "$n B"
}

/* ===================================================================== */

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun ChatScreen(nav: Nav, chat: String) {
    val hub = AppCore.dmHub
    val convs by AppCore.dms.convs.collectAsState()
    val groups by AppCore.groups.groups.collectAsState()
    val contacts by AppCore.social.contacts.collectAsState()
    val blocked by AppCore.social.blocked.collectAsState()
    val connected by AppCore.connected.collectAsState()
    val me = AppCore.profile.uid
    val isGroup = DmHub.isGroup(chat)
    val isSaved = chat == DmHub.SAVED
    val group = if (isGroup) groups[DmHub.gid(chat)] else null
    val conv = convs[chat]
    val msgs = conv?.messages ?: emptyList()
    val loader = rememberImageLoader()
    val list = rememberLazyListState()

    var tf by remember { mutableStateOf(TextFieldValue("")) }
    var replyTo by remember { mutableStateOf<DmMessage?>(null) }
    var editing by remember { mutableStateOf<DmMessage?>(null) }
    var actionOn by remember { mutableStateOf<DmMessage?>(null) }
    var forwarding by remember { mutableStateOf<DmMessage?>(null) }
    var attach by remember { mutableStateOf(false) }
    var gifDialog by remember { mutableStateOf(false) }
    var fmtBar by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf<DmMessage?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }

    DisposableEffect(chat) {
        hub.openChat.value = chat
        AppCore.dms.markRead(chat)
        onDispose { hub.openChat.value = null }
    }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var firstLoad by remember { mutableStateOf(true) }
    var newBelow by remember { mutableStateOf(0) }
    val atBottom by remember { androidx.compose.runtime.derivedStateOf { val li = list.layoutInfo; li.totalItemsCount == 0 || (li.visibleItemsInfo.lastOrNull()?.index ?: 0) >= li.totalItemsCount - 2 } }
    LaunchedEffect(msgs.lastOrNull()?.id, msgs.size) {
        AppCore.dms.markRead(chat)
        if (msgs.isEmpty()) return@LaunchedEffect
        if (firstLoad) { list.scrollToItem(msgs.size - 1); firstLoad = false }
        else if (atBottom || msgs.last().from == me) list.animateScrollToItem(msgs.size - 1)
        else newBelow++
    }
    LaunchedEffect(atBottom) { if (atBottom) newBelow = 0 }

    val title = hub.titleOf(chat)
    val online = if (isGroup || isSaved) false else connected.contains(chat)
    val readOnly = group?.left == true
    val dmAvatar = contacts[chat]?.avatar

    Column(Modifier.fillMaxSize().background(WR.bg).imePadding()) {
        /* ---------- header ---------- */
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            WrIconButton("chevron-left", { nav.pop() })
            Row(
                Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable {
                    if (isGroup) nav.push(Route.GroupInfo(DmHub.gid(chat))) else if (!isSaved) nav.push(Route.Profile(chat))
                }.padding(4.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                when {
                    isSaved -> Box(Modifier.size(40.dp).clip(CircleShape).background(WR.accent), contentAlignment = Alignment.Center) { WrIcon("bookmark", 20.dp, Color.White) }
                    isGroup -> GroupAvatar(group?.emoji ?: "👥", group?.hue ?: 265, 40)
                    else -> AvatarView(dmAvatar ?: Avatar(), 40.dp, uid = chat)
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text(title, color = WR.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Muted(
                        when {
                            isSaved -> "Notes, links and files for yourself"
                            isGroup -> "${group?.members?.size ?: 0} members · ${hub.connectedCount(chat)} connected"
                            online -> "connected"
                            else -> "not connected"
                        }, size = 12, maxLines = 1
                    )
                }
            }
            if (!isSaved && !readOnly) WrIconButton("phone", { hub.startCall(chat) })
            Box {
                WrIconButton("more-v", { menu = true })
                WrMenu(menu, { menu = false }, buildList {
                    if (isGroup) add(MenuEntry("Group info", "users") { nav.push(Route.GroupInfo(DmHub.gid(chat))) })
                    if (!isGroup && !isSaved) {
                        add(MenuEntry("View profile", "user") { nav.push(Route.Profile(chat)) })
                        add(MenuEntry(if (blocked.contains(chat)) "Unblock" else "Block", "ban", danger = true) { AppCore.block(chat, !blocked.contains(chat)) })
                    }
                    if (!isSaved) add(MenuEntry(if (conv?.muted == true) "Unmute" else "Mute", if (conv?.muted == true) "bell" else "bell-off") { AppCore.dms.setMuted(chat, conv?.muted != true) })
                    add(MenuEntry(if (isGroup) "Leave and delete" else "Delete chat", "trash", danger = true) { confirmDelete = true })
                })
            }
        }
        val pinned = conv?.pinned?.let { id -> msgs.firstOrNull { it.id == id } }
        if (pinned != null) {
            Row(
                Modifier.fillMaxWidth().background(WR.surface).clickable { val i = msgs.indexOf(pinned); if (i >= 0) scope.launch { list.animateScrollToItem(i) } }
                    .padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                WrIcon("pin", 14.dp, WR.accentBright)
                Spacer(Modifier.width(8.dp))
                Text(hub.preview(pinned), color = WR.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                WrIconButton("x", { hub.togglePin(chat, pinned.id) }, size = 36.dp, iconSize = 16.dp, tint = WR.muted, desc = "Unpin")
            }
        }
        if (!isGroup && !isSaved && !online) {
            Row(Modifier.fillMaxWidth().background(WR.surface).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Muted("$title isn't connected. They'll get your messages as soon as you're both online.", size = 12, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                WrButton("Try now", { app.waveroom.Rendezvous.kick(); AppCore.toast("Looking for $title…") }, small = true, kind = BtnKind.Secondary)
            }
        }

        /* ---------- messages ---------- */
        if (msgs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                EmptyState(
                    if (isSaved) "bookmark" else if (isGroup) "users" else "message",
                    if (isSaved) "Your notes" else "No messages yet",
                    if (isSaved) "Save notes, links, photos and files here. Forward any message to keep it." else "Say hello 👋"
                )
            }
        } else {
          Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(Modifier.fillMaxSize(), state = list, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(msgs, key = { _, m -> m.id }) { idx, m ->
                    val before = msgs.getOrNull(idx - 1)
                    if (before == null || dayKey(before.at) != dayKey(m.at)) {
                        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                            Text(dayLabel(m.at), color = WR.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium,
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(WR.surface2).padding(horizontal = 12.dp, vertical = 4.dp))
                        }
                    }
                    if (m.kind == "call") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 6.dp), contentAlignment = Alignment.Center) {
                            Muted(m.text, size = 12, align = TextAlign.Center)
                        }
                    } else {
                        val prev = before
                        val showAuthor = isGroup && m.from != me && (prev == null || prev.from != m.from || prev.kind == "call" || dayKey(prev.at) != dayKey(m.at))
                        Bubble(
                            chat, m, msgs, showAuthor, isGroup, loader,
                            onLong = { actionOn = m },
                            onView = { viewer = m },
                            onRetry = { hub.retry(chat, m.id) }
                        )
                    }
                }
            }
            if (newBelow > 0 || !atBottom) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).clip(RoundedCornerShape(50)).background(WR.accent)
                        .clickable { scope.launch { list.animateScrollToItem(msgs.size - 1) } }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    WrIcon("arrow-down", 14.dp, Color.White)
                    Spacer(Modifier.width(6.dp))
                    Text(if (newBelow > 0) "$newBelow new" else "Latest", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                }
            }
          }
        }

        /* ---------- composer ---------- */
        if (readOnly) {
            Box(Modifier.fillMaxWidth().background(WR.surface).navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.Center) {
                Muted("You are no longer in this group.")
            }
        } else {
            val rep = replyTo
            val ed = editing
            if (rep != null || ed != null) {
                Row(Modifier.fillMaxWidth().background(WR.surface).padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    WrIcon(if (ed != null) "edit" else "reply", 16.dp, WR.accentBright)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (ed != null) "Edit message" else "Reply to ${nameOf(rep!!.from, rep.authorName)}", color = WR.accentBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(hub.preview((ed ?: rep)!!), color = WR.muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    WrIconButton("x", { replyTo = null; if (editing != null) { editing = null; tf = TextFieldValue("") } }, size = 36.dp, iconSize = 16.dp, tint = WR.muted)
                }
            }
            if (fmtBar) {
                Row(Modifier.fillMaxWidth().background(WR.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    fun wrap(o: String, c: String = o) {
                        val (t, r) = wrapSelection(tf.text, tf.selection.start, tf.selection.end, o, c)
                        tf = TextFieldValue(t, TextRange(r.first, r.last))
                    }
                    WrIconButton("bold", { wrap("**") }, size = 40.dp, iconSize = 18.dp)
                    WrIconButton("italic", { wrap("__") }, size = 40.dp, iconSize = 18.dp)
                    WrIconButton("strike", { wrap("~~") }, size = 40.dp, iconSize = 18.dp)
                    WrIconButton("code", { wrap("`") }, size = 40.dp, iconSize = 18.dp)
                    WrIconButton("eye-off", { wrap("||") }, size = 40.dp, iconSize = 18.dp)
                    WrIconButton("quote", {
                        val s = tf.selection.start.coerceIn(0, tf.text.length)
                        val ls = tf.text.lastIndexOf('\n', s - 1) + 1
                        tf = TextFieldValue(tf.text.substring(0, ls) + "> " + tf.text.substring(ls), TextRange(tf.selection.end + 2))
                    }, size = 40.dp, iconSize = 18.dp)
                    WrIconButton("file", { val (t, r) = wrapSelection(tf.text, tf.selection.start, tf.selection.end, "```\n", "\n```"); tf = TextFieldValue(t, TextRange(r.first, r.last)) }, size = 40.dp, iconSize = 18.dp)
                    WrIconButton("link", { val (t, r) = wrapSelection(tf.text, tf.selection.start, tf.selection.end, "[", "](https://)"); tf = TextFieldValue(t, TextRange(r.first, r.last)) }, size = 40.dp, iconSize = 18.dp)
                }
            }
            Row(Modifier.fillMaxWidth().background(WR.surface).navigationBarsPadding().padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom) {
                WrIconButton("paperclip", { attach = true }, tint = WR.muted)
                Box(
                    Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(22.dp)).background(WR.surface2).border(1.dp, WR.border, RoundedCornerShape(22.dp))
                        .padding(start = 16.dp, end = 6.dp, top = 11.dp, bottom = 11.dp)
                ) {
                    if (tf.text.isEmpty()) Text(if (isSaved) "Write a note" else "Message", color = WR.faint, fontSize = 15.sp)
                    BasicTextField(
                        value = tf, onValueChange = { if (it.text.length <= 4000) tf = it },
                        textStyle = TextStyle(color = WR.text, fontSize = 15.sp), cursorBrush = SolidColor(WR.accentBright), maxLines = 6,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                WrIconButton("type", { fmtBar = !fmtBar }, tint = if (fmtBar) WR.accentBright else WR.muted)
                val canSend = tf.text.isNotBlank()
                WrIconButton("send", {
                    val ed2 = editing
                    if (ed2 != null) { hub.edit(chat, ed2.id, tf.text); editing = null }
                    else hub.send(chat, tf.text, replyTo?.id ?: "")
                    tf = TextFieldValue(""); replyTo = null
                }, bg = if (canSend) WR.accent else WR.surface3, tint = if (canSend) Color.White else WR.faint, enabled = canSend)
            }
        }
    }

    /* ---------- overlays ---------- */
    if (attach) {
        WrSheet({ attach = false }, "Attach") {
            Row2("Photo", "From your gallery", "image", onClick = {
                attach = false
                AppCore.platform?.pickImage { u -> if (u != null) hub.sendUri(chat, u, "image", replyTo?.id ?: "", tf.text.also { tf = TextFieldValue("") }) }
            })
            Row2("GIF from gallery", "Animated GIFs stay animated", "gif", onClick = {
                attach = false
                AppCore.platform?.pickImage { u -> if (u != null) hub.sendUri(chat, u, "image", replyTo?.id ?: "") }
            })
            Row2("GIF from a link", "Paste any direct .gif / image link", "link", onClick = { attach = false; gifDialog = true })
            Row2("File", "Any file, up to 16 MB", "file", onClick = {
                attach = false
                AppCore.platform?.pickFile { u -> if (u != null) hub.sendUri(chat, u, "file", replyTo?.id ?: "") }
            })
        }
    }
    if (gifDialog) InputDialog("GIF link", "", "https://…/funny.gif", "Send", onConfirm = { hub.sendGifUrl(chat, it, replyTo?.id ?: ""); gifDialog = false }, onDismiss = { gifDialog = false })

    val act = actionOn
    if (act != null) {
        val mine = act.from == me
        val canAdmin = group?.isAdmin(me) == true
        WrSheet({ actionOn = null }, null) {
            FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!isSaved && !act.deleted) for (e in REACTIONS) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(WR.surface2).clickable { hub.react(chat, act.id, e); actionOn = null }, contentAlignment = Alignment.Center) { Text(e, fontSize = 22.sp) }
                }
            }
            if (!act.deleted) {
                if (!readOnly) Row2("Reply", null, "reply", onClick = { replyTo = act; editing = null; actionOn = null })
                if (act.text.isNotEmpty()) Row2("Copy text", null, "copy", onClick = { AppCore.platform?.copy("message", act.text); actionOn = null })
                if (mine && act.kind == "text" && !readOnly) Row2("Edit", null, "edit", onClick = { editing = act; replyTo = null; tf = TextFieldValue(act.text, TextRange(act.text.length)); actionOn = null })
                Row2("Forward", null, "forward", onClick = { forwarding = act; actionOn = null })
                if (!isSaved) Row2("Save to Saved messages", null, "bookmark", onClick = { hub.saveToSaved(act); actionOn = null })
                Row2(if (conv?.pinned == act.id) "Unpin" else "Pin", null, "pin", onClick = { hub.togglePin(chat, act.id); actionOn = null })
            }
            Row2("Delete for me", null, "trash", danger = true, onClick = { hub.delete(chat, act.id, false); actionOn = null })
            if (!isSaved && !act.deleted && (mine || canAdmin)) Row2("Delete for everyone", null, "trash", danger = true, onClick = { hub.delete(chat, act.id, true); actionOn = null })
        }
    }
    val fw = forwarding
    if (fw != null) ChatPicker("Forward to", { forwarding = null }) { target -> hub.forward(target, fw); forwarding = null; AppCore.toast("Forwarded") }
    val v = viewer
    if (v != null) ImageViewer(v, loader) { viewer = null }
    if (confirmDelete) {
        ConfirmDialog(
            if (isGroup) "Leave and delete?" else "Delete chat?",
            if (isGroup) "You will leave the group and the history on this phone is erased." else "The history on this phone is erased. The other person keeps their copy.",
            "Delete", { confirmDelete = false; hub.deleteChat(chat); nav.pop() }, { confirmDelete = false }, danger = true
        )
    }
}

private fun nameOf(uid: String, snap: String): String =
    if (uid == AppCore.profile.uid) "You" else AppCore.social.contact(uid)?.displayName ?: snap.ifEmpty { "Someone" }

@Composable
fun GroupAvatar(emoji: String, hue: Int, size: Int) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(WR.hue(hue, 0.5f, 0.32f)), contentAlignment = Alignment.Center) {
        Text(emoji, fontSize = (size * 0.5f).sp)
    }
}

/* ===================================================================== */

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun Bubble(
    chat: String, m: DmMessage, all: List<DmMessage>, showAuthor: Boolean, isGroup: Boolean, loader: ImageLoader,
    onLong: () -> Unit, onView: () -> Unit, onRetry: () -> Unit
) {
    val me = AppCore.profile.uid
    val mine = m.from == me
    val bg = if (mine) WR.accent else WR.surface2
    val fg = if (mine) Color.White else WR.text
    val link = if (mine) Color.White else WR.accentBright
    val shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp, bottomStart = if (mine) 16.dp else 4.dp, bottomEnd = if (mine) 4.dp else 16.dp)
    val maxW = (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp * 0.8f).dp.coerceIn(220.dp, 440.dp)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (mine) Alignment.End else Alignment.Start) {
        Column(
            Modifier.widthIn(max = maxW).clip(shape).background(bg)
                .combinedClickable(onClick = { if (m.status == "failed") onRetry() else if (m.kind == "image" || m.kind == "gif") onView() }, onLongClick = onLong)
                .padding(horizontal = 10.dp, vertical = 7.dp)
        ) {
            if (showAuthor) Text(nameOf(m.from, m.authorName), color = authorColor(m.from), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            if (m.fwd.isNotEmpty() && !m.deleted) Text("Forwarded from ${m.fwd}", color = fg.copy(alpha = 0.75f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
            if (m.reply.isNotEmpty() && !m.deleted) {
                val r = all.firstOrNull { it.id == m.reply }
                Row(Modifier.padding(vertical = 3.dp).clip(RoundedCornerShape(6.dp)).background(fg.copy(alpha = 0.12f)).padding(6.dp)) {
                    Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(if (mine) Color.White else WR.accentBright))
                    Column(Modifier.padding(start = 6.dp)) {
                        Text(if (r == null) "Message" else nameOf(r.from, r.authorName), color = if (mine) Color.White else WR.accentBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (r == null) "not available" else AppCore.dmHub.preview(r), color = fg.copy(alpha = 0.8f), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            when {
                m.deleted -> Text("This message was deleted", color = fg.copy(alpha = 0.6f), fontSize = 14.sp, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                m.kind == "image" || m.kind == "gif" -> MediaView(m, loader)
                m.kind == "file" -> FileCard(chat, m, fg)
            }
            if (!m.deleted && m.text.isNotEmpty()) RichText(m.text, fg, link, Modifier.padding(top = if (m.kind == "text") 0.dp else 4.dp))
            Row(Modifier.align(Alignment.End).padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                if (m.edited && !m.deleted) Text("edited ", color = fg.copy(alpha = 0.8f), fontSize = 11.sp)
                Text(fmtClock(m.at), color = fg.copy(alpha = 0.8f), fontSize = 11.sp)
                if (mine) {
                    Spacer(Modifier.width(4.dp))
                    when (m.status) {
                        "pending" -> Text("· waiting", color = fg.copy(alpha = 0.8f), fontSize = 11.sp)
                        "failed" -> Text("· ${m.error.ifEmpty { "not delivered" }} · tap to retry", color = if (mine) Color(0xFFFFD0D0) else WR.danger, fontSize = 10.sp)
                        else -> WrIcon("check", 11.dp, fg.copy(alpha = 0.75f))
                    }
                }
            }
        }
        if (m.reactions.isNotEmpty()) {
            FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for ((e, who) in m.reactions) {
                    val on = who.contains(me)
                    Row(
                        Modifier.clip(RoundedCornerShape(50)).background(if (on) WR.accentSoft else WR.surface2).border(1.dp, if (on) WR.accent else WR.border, RoundedCornerShape(50))
                            .clickable { AppCore.dmHub.react(chat, m.id, e) }.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(e, fontSize = 13.sp)
                        if (who.size > 1) Text(" ${who.size}", color = WR.muted, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaView(m: DmMessage, loader: ImageLoader) {
    val tick by AppCore.dmHub.mediaTick.collectAsState()
    val prog by AppCore.dmHub.mediaProgress.collectAsState()
    val has = remember(m.id, tick) { Media.has(m.id) }
    val shape = RoundedCornerShape(10.dp)
    when {
        m.url.isNotEmpty() -> AsyncImage(m.url, null, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.width(240.dp).heightIn(min = 80.dp, max = 300.dp).clip(shape))
        has -> AsyncImage(Media.file(m.id), null, imageLoader = loader, contentScale = ContentScale.Crop, modifier = Modifier.width(240.dp).heightIn(min = 80.dp, max = 300.dp).clip(shape))
        else -> Box(Modifier.size(200.dp, 140.dp).clip(shape).background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            val p = prog[m.id]
            if (p != null) CircularProgressIndicator(progress = { p }, color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(32.dp))
            else Muted("${if (m.kind == "gif") "GIF" else "Photo"} · ${fmtSize(m.size)}\nwaiting for sender", size = 12, align = TextAlign.Center)
        }
    }
}

@Composable
private fun FileCard(chat: String, m: DmMessage, fg: Color) {
    val tick by AppCore.dmHub.mediaTick.collectAsState()
    val prog by AppCore.dmHub.mediaProgress.collectAsState()
    val has = remember(m.id, tick) { Media.has(m.id) }
    val p = prog[m.id]
    Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(42.dp).clip(CircleShape).background(fg.copy(alpha = 0.18f)).clickable {
                if (has) {
                    val f = Media.file(m.id)
                    AppCore.platform?.saveFile(m.name.ifEmpty { "file" }, m.mime, f.readBytes()) { ok -> if (ok) AppCore.toast("Saved") }
                } else AppCore.dmHub.download(chat, m)
            }, contentAlignment = Alignment.Center
        ) {
            if (p != null) CircularProgressIndicator(progress = { p }, color = fg, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
            else WrIcon(if (has) "download" else "arrow-down", 20.dp, fg)
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text(m.name.ifEmpty { "file" }, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 190.dp))
            Text(fmtSize(m.size) + if (has) " · tap to save" else " · tap to download", color = fg.copy(alpha = 0.7f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ImageViewer(m: DmMessage, loader: ImageLoader, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black)) {
            val model: Any = if (m.url.isNotEmpty()) m.url else Media.file(m.id)
            AsyncImage(model, null, imageLoader = loader, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp, max = 560.dp))
            Row(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
                if (m.url.isEmpty() && Media.has(m.id)) WrIconButton("download", {
                    AppCore.platform?.saveFile(m.name.ifEmpty { if (m.kind == "gif") "image.gif" else "image.jpg" }, m.mime, Media.file(m.id).readBytes()) { ok -> if (ok) AppCore.toast("Saved") }
                }, bg = Color(0x99000000), tint = Color.White)
                Spacer(Modifier.width(6.dp))
                WrIconButton("x", onClose, bg = Color(0x99000000), tint = Color.White)
            }
        }
    }
}

/** Pick one of my chats (Saved messages, groups, people I've messaged / met). */
@Composable
fun ChatPicker(title: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val groups by AppCore.groups.groups.collectAsState()
    val contacts by AppCore.social.contacts.collectAsState()
    WrSheet(onDismiss, title) {
        Column(Modifier.heightIn(max = 480.dp)) {
            LazyColumn {
                item { Row2("Saved messages", "Just for you", "bookmark", onClick = { onPick(DmHub.SAVED) }) }
                items(groups.values.filter { !it.left }.toList()) { g ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(g.chatId) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        GroupAvatar(g.emoji, g.hue, 36)
                        Spacer(Modifier.width(12.dp))
                        Text(g.name, color = WR.text, fontSize = 15.sp)
                    }
                }
                items(contacts.values.toList()) { c ->
                    Row(Modifier.fillMaxWidth().clickable { onPick(c.uid) }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        AvatarView(c.avatar, 36.dp, uid = c.uid)
                        Spacer(Modifier.width(12.dp))
                        Text(c.displayName, color = WR.text, fontSize = 15.sp)
                    }
                }
            }
        }
    }
}


private fun dayKey(ts: Long): Long = java.util.Calendar.getInstance().apply { timeInMillis = ts; set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0); set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }.timeInMillis

private fun dayLabel(ts: Long): String {
    val d = dayKey(ts)
    val today = dayKey(System.currentTimeMillis())
    return when {
        d == today -> "Today"
        d == today - 86_400_000L || d == dayKey(today - 1) -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE, d MMM", java.util.Locale.getDefault()).format(java.util.Date(ts))
    }
}
