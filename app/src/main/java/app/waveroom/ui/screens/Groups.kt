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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.AVATAR_EMOJIS
import app.waveroom.room.DmHub
import app.waveroom.ui.Nav
import app.waveroom.ui.Route
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Row2
import app.waveroom.ui.components.ScreenHeader
import app.waveroom.ui.components.SectionTitle
import app.waveroom.ui.components.SwitchRow
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.theme.WR
import app.waveroom.ui.components.screenBackdrop

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewGroupScreen(nav: Nav) {
    val contacts by AppCore.social.contacts.collectAsState()
    var name by remember { mutableStateOf("") }
    var about by remember { mutableStateOf("") }
    var emoji by remember { mutableStateOf("💬") }
    var hue by remember { mutableStateOf(HUES.first()) }
    val picked = remember { mutableStateListOf<String>() }
    var error by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    val people = contacts.values.filter { it.uid != AppCore.profile.uid }.sortedBy { it.displayName.lowercase() }

    Column(Modifier.fillMaxSize().screenBackdrop(4).imePadding()) {
        ScreenHeader("New group", { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                GroupAvatar(emoji, hue, 64)
                Spacer(Modifier.width(14.dp))
                WrField(name, { name = it }, Modifier.weight(1f), label = "Group name", placeholder = "Weekend plans", maxLength = 60)
            }
            Spacer(Modifier.height(12.dp))
            WrField(about, { about = it }, label = "About (optional)", placeholder = "What is this group for?", maxLength = 200)
            Spacer(Modifier.height(14.dp))
            Tiny("Icon")
            Spacer(Modifier.height(8.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (e in listOf("💬", "🎧", "🔥", "🌙", "🎮", "📚", "🍕", "✈️", "💬", "🎨")) {
                    Box(Modifier.size(44.dp).clip(CircleShape).background(if (e == emoji) WR.accentSoft else WR.surface2).border(1.dp, if (e == emoji) WR.accent else WR.border, CircleShape).clickable { emoji = e }, contentAlignment = Alignment.Center) { Text(e, fontSize = 20.sp) }
                }
            }
            Spacer(Modifier.height(10.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (h in HUES) Box(Modifier.size(44.dp).clickable { hue = h }, contentAlignment = Alignment.Center) { Box(Modifier.size(30.dp).clip(CircleShape).background(WR.hue(h, 0.5f, 0.4f)).border(if (h == hue) 2.dp else 0.dp, WR.text, CircleShape)) }
            }
            Spacer(Modifier.height(18.dp))
            Tiny("Add people · ${picked.size} selected")
            Spacer(Modifier.height(6.dp))
            if (people.isEmpty()) Muted("People you've connected with appear here. You can also create the group now and add people later with a link or QR code.", size = 13)
            for (c in people) {
                val on = picked.contains(c.uid)
                Row(Modifier.fillMaxWidth().clickable { if (on) picked.remove(c.uid) else picked.add(c.uid) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    AvatarView(c.avatar, 40.dp, uid = c.uid)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(c.displayName, color = WR.text, fontSize = 15.sp)
                        Muted("@${c.username}", size = 12)
                    }
                    Box(Modifier.size(24.dp).clip(CircleShape).background(if (on) WR.accent else Color.Transparent).border(1.5.dp, if (on) WR.accent else WR.borderStrong, CircleShape), contentAlignment = Alignment.Center) {
                        if (on) WrIcon("check", 14.dp, Color.White)
                    }
                }
            }
        }
        Column(Modifier.padding(20.dp).navigationBarsPadding()) {
            if (error != null) Text(error!!, color = WR.danger, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
            WrButton(if (picked.isEmpty()) "Create group" else "Create with ${picked.size} ${if (picked.size == 1) "person" else "people"}", {
                if (creating) return@WrButton
                if (name.trim().length < 2) { error = "Give the group a name first."; return@WrButton }
                creating = true
                val id = AppCore.dmHub.createGroup(name, about, picked.toList(), emoji, hue)
                nav.pop(); nav.push(Route.Chat(id))
            }, Modifier.fillMaxWidth(), icon = "users")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GroupInfoScreen(nav: Nav, gid: String) {
    val groups by AppCore.groups.groups.collectAsState()
    val convs by AppCore.dms.convs.collectAsState()
    val connected by AppCore.connected.collectAsState()
    val g = groups[gid]
    val me = AppCore.profile.uid
    androidx.compose.runtime.LaunchedEffect(g == null) { if (g == null) nav.pop() }
    if (g == null) return
    val admin = g.isAdmin(me)
    var edit by remember { mutableStateOf(false) }
    var add by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var memberMenu by remember { mutableStateOf<String?>(null) }
    val chat = g.chatId

    Column(Modifier.fillMaxSize().screenBackdrop()) {
        ScreenHeader("Group info", { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 30.dp)) {
            Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                GroupAvatar(g.emoji, g.hue, 88)
                Spacer(Modifier.height(12.dp))
                Text(g.name, color = WR.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold, textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 3, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                if (g.about.isNotEmpty()) Muted(g.about, modifier = Modifier.padding(top = 4.dp), align = androidx.compose.ui.text.style.TextAlign.Center)
                Muted(if (g.members.size == 1) "1 member" else "${g.members.size} members", size = 12, modifier = Modifier.padding(top = 6.dp))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WrButton("Message", { nav.popTo(Route.Chat(chat)); if (nav.top != Route.Chat(chat)) nav.replaceTop(Route.Chat(chat)) }, kind = BtnKind.Secondary, small = true, icon = "message")
                    WrButton("Group call", { AppCore.dmHub.startCall(chat) }, kind = BtnKind.Secondary, small = true, icon = "phone", enabled = !g.left)
                }
            }
            if (!g.left) {
                if (g.canAdd(me)) {
                    Row2("Add people", "From people you've met", "user-plus", onClick = { add = true })
                    Row2("Invite with link or QR", "Anyone who scans joins the group", "qr", onClick = { nav.push(Route.Invite("group:${g.id}")) })
                }
                if (admin) Row2("Edit group", null, "pencil", onClick = { edit = true })
                Row2(if (convs[chat]?.muted == true) "Unmute notifications" else "Mute notifications", null, if (convs[chat]?.muted == true) "bell" else "bell-off", onClick = { AppCore.dms.setMuted(chat, convs[chat]?.muted != true) })
            }
            SectionTitle("Members")
            for (m in g.members.values.sortedWith(compareBy({ when (it.role) { "owner" -> 0; "admin" -> 1; else -> 2 } }, { it.name.lowercase() }))) {
                Row(
                    Modifier.fillMaxWidth().clickable { if (m.uid != me) memberMenu = m.uid }.padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AvatarView(m.avatar, 42.dp, uid = m.uid, mine = m.uid == me)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(if (m.uid == me) "${m.name} (you)" else m.name, color = WR.text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                        Muted("@${m.username}" + if (m.uid != me && connected.contains(m.uid)) " · connected" else "", size = 12)
                    }
                    if (m.role != "member") Text(m.role, color = WR.accentBright, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(10.dp))
            if (!g.left) Row2("Leave group", null, "log-out", danger = true, onClick = { leave = true })
        }
    }

    if (edit) {
        var n by remember { mutableStateOf(g.name) }
        var a by remember { mutableStateOf(g.about) }
        var mca by remember { mutableStateOf(g.membersCanAdd) }
        var e by remember { mutableStateOf(g.emoji) }
        WrSheet({ edit = false }, "Edit group") {
            Column(Modifier.padding(horizontal = 20.dp)) {
                WrField(n, { n = it }, label = "Name", maxLength = 60)
                Spacer(Modifier.height(10.dp))
                WrField(a, { a = it }, label = "About", maxLength = 200)
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (x in listOf("💬", "🎧", "🔥", "🌙", "🎮", "📚", "🍕", "✈️", "💬", "🎨")) {
                        Box(Modifier.size(40.dp).clip(CircleShape).background(if (x == e) WR.accentSoft else WR.surface2).clickable { e = x }, contentAlignment = Alignment.Center) { Text(x, fontSize = 20.sp) }
                    }
                }
                SwitchRow("Members can add people", "Otherwise only admins can.", mca, { mca = it })
                Spacer(Modifier.height(10.dp))
                WrButton("Save", { AppCore.dmHub.updateGroup(g.id, n, a, mca, e, g.hue); edit = false }, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (add) {
        val contacts by AppCore.social.contacts.collectAsState()
        val rest = contacts.values.filter { !g.members.containsKey(it.uid) }
        WrSheet({ add = false }, "Add people") {
            Column(Modifier.heightIn(max = 460.dp)) {
                if (rest.isEmpty()) Muted("Everyone you know is already here. Use “Invite with link or QR” for new people.", modifier = Modifier.padding(20.dp))
                LazyColumn {
                    items(rest) { c ->
                        Row(Modifier.fillMaxWidth().clickable { AppCore.dmHub.addMember(g.id, c.uid); add = false }.padding(horizontal = 20.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            AvatarView(c.avatar, 40.dp, uid = c.uid)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(c.displayName, color = WR.text, fontSize = 15.sp)
                                Muted(if (connected.contains(c.uid)) "connected — added instantly" else "added when you next connect", size = 12)
                            }
                        }
                    }
                }
            }
        }
    }
    val mu = memberMenu
    if (mu != null) {
        val m = g.members[mu]
        if (m == null) memberMenu = null else WrSheet({ memberMenu = null }, m.name) {
            Row2("Message", null, "message", onClick = { memberMenu = null; nav.push(Route.Chat(m.uid)) })
            Row2("View profile", null, "user", onClick = { memberMenu = null; nav.push(Route.Profile(m.uid)) })
            if (g.ownerUid == me && m.role != "owner") Row2(if (m.role == "admin") "Remove admin" else "Make admin", null, "shield", onClick = { AppCore.dmHub.setAdmin(g.id, m.uid, m.role != "admin"); memberMenu = null })
            if (admin && m.role != "owner") Row2("Remove from group", null, "log-out", danger = true, onClick = { AppCore.dmHub.removeMember(g.id, m.uid); memberMenu = null })
        }
    }
    if (leave) ConfirmDialog("Leave group?", "You will stop receiving messages. The history stays on this phone until you delete the chat.", "Leave", { leave = false; AppCore.dmHub.leaveGroup(g.id) }, { leave = false }, danger = true)
}
