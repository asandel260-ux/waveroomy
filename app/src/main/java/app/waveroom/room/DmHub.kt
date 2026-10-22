package app.waveroom.room

import android.net.Uri
import android.util.Base64
import app.waveroom.AppCore
import app.waveroom.core.DmMessage
import app.waveroom.core.Group
import app.waveroom.core.GroupMember
import app.waveroom.core.Ids
import app.waveroom.core.Media
import app.waveroom.core.arr
import app.waveroom.core.bool
import app.waveroom.core.int
import app.waveroom.core.jsonArrayOf
import app.waveroom.core.jsonOf
import app.waveroom.core.later
import app.waveroom.core.lng
import app.waveroom.core.cancelLater
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.objects
import app.waveroom.core.onMain
import app.waveroom.core.str
import app.waveroom.net.PeerLink
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.Executors

data class IncomingCall(val cid: String, val uid: String, val chat: String, val title: String, val group: Boolean)

/**
 * Everything messenger-like. With no server there is no mailbox: a message is pushed to whoever is
 * connected right now, and phones of the same chat catch up with each other whenever two of them
 * connect (digest exchange). Attachments are fetched peer to peer in chunks.
 */
class DmHub(private val core: AppCore) {

    /** chat id currently on screen (so it is not counted as unread) */
    val openChat = MutableStateFlow<String?>(null)
    val mediaTick = MutableStateFlow(0)
    val mediaProgress = MutableStateFlow<Map<String, Float>>(emptyMap())
    val incomingCall = MutableStateFlow<IncomingCall?>(null)

    private val io = Executors.newSingleThreadExecutor()
    private val myUid get() = core.profile.uid
    private val myName get() = core.profile.profile.value?.displayName ?: ""
    private var ringTimer: Runnable? = null
    private var outgoing: Triple<String, String, List<String>>? = null   // roomId, chat, uids rung

    companion object {
        const val SAVED = "saved"
        fun isGroup(chat: String) = chat.startsWith("g:")
        fun gid(chat: String) = chat.removePrefix("g:")
        const val MAX_FILE = 16L * 1024 * 1024
        const val AUTO_MEDIA = 8L * 1024 * 1024
    }

    /* ------------------------------------------------------------------ */
    /* Addressing                                                          */
    /* ------------------------------------------------------------------ */

    fun recipients(chat: String): List<String> = when {
        chat == SAVED -> emptyList()
        isGroup(chat) -> core.groups.get(gid(chat))?.takeIf { !it.left }?.members?.keys?.filter { it != myUid } ?: emptyList()
        else -> listOf(chat)
    }

    fun titleOf(chat: String): String = when {
        chat == SAVED -> "Saved messages"
        isGroup(chat) -> core.groups.get(gid(chat))?.name ?: "Group"
        else -> core.contactName(chat)
    }

    private fun wireChat(chat: String) = if (isGroup(chat)) gid(chat) else "dm"
    private fun chatFor(link: PeerLink, c: String) = if (c.isEmpty() || c == "dm") link.uid else "g:$c"

    private fun wire(chat: String, m: DmMessage, backfill: Boolean = false): String {
        val j = m.toJson()
        j.remove("status"); j.remove("error")
        j.put("t", "m").put("c", wireChat(chat))
        if (backfill) j.put("bf", true)
        return j.toString()
    }

    private fun push(chat: String, text: String) {
        for (u in recipients(chat)) core.mesh.linkForUid(u)?.send(text)
    }

    fun connectedCount(chat: String): Int = recipients(chat).count { core.connected.value.contains(it) }

    /* ------------------------------------------------------------------ */
    /* Sending                                                             */
    /* ------------------------------------------------------------------ */

    private fun dispatch(chat: String, msg: DmMessage) {
        val m = if (chat == SAVED) msg.copy(status = "sent") else msg
        core.dms.put(chat, m)
        if (chat == SAVED) return
        val sent = recipients(chat).count { core.mesh.linkForUid(it)?.send(wire(chat, m)) == true }
        if (sent == 0 && recipients(chat).isNotEmpty()) core.dms.edit(chat, m.id) { it.copy(status = "pending") }
    }

    private fun base(chat: String, text: String, reply: String) = DmMessage(
        Ids.id("m_"), myUid, text, nowMs(), "pending", reply = reply, authorName = myName
    )

    fun send(chat: String, text: String, reply: String = "") {
        val t = text.trim().take(4000)
        if (t.isEmpty()) return
        dispatch(chat, base(chat, t, reply))
    }

    fun sendGifUrl(chat: String, url: String, reply: String = "") {
        val u = url.trim()
        if (!u.startsWith("http")) return
        dispatch(chat, base(chat, "", reply).copy(kind = "gif", url = u, mime = "image/gif"))
    }

    /** Copy a picked attachment into private storage and send it. */
    fun sendUri(chat: String, uri: Uri, kind: String, reply: String = "", caption: String = "") {
        io.execute {
            try {
                val cr = core.app.contentResolver
                val mime = cr.getType(uri) ?: "application/octet-stream"
                var name = "file"
                cr.query(uri, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (i >= 0 && c.moveToFirst()) name = c.getString(i) ?: name
                }
                val id = Ids.id("m_")
                val dest = Media.file(id)
                var total = 0L
                cr.openInputStream(uri)?.use { ins ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = ins.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_FILE) throw IllegalStateException("too big")
                            out.write(buf, 0, n)
                        }
                    }
                } ?: throw IllegalStateException("unreadable")
                val k = when {
                    mime == "image/gif" -> "gif"
                    kind == "image" && mime.startsWith("image/") -> "image"
                    else -> "file"
                }
                val msg = DmMessage(id, myUid, caption.trim(), nowMs(), "pending", kind = k, name = name, mime = mime, size = total, reply = reply, authorName = myName)
                onMain { dispatch(chat, msg); mediaTick.value++ }
            } catch (e: Exception) {
                onMain { core.toast(if (e.message == "too big") "Files over 16 MB can't be sent" else "Couldn't read that file") }
            }
        }
    }

    fun retry(chat: String, id: String) {
        val m = core.dms.find(chat, id) ?: return
        core.dms.edit(chat, id) { it.copy(status = "pending") }
        push(chat, wire(chat, m))
    }

    fun forward(toChat: String, m: DmMessage) {
        val id = Ids.id("m_")
        val orig = if (m.authorName.isNotEmpty()) m.authorName else core.contactName(m.from)
        val copy = m.copy(
            id = id, from = myUid, at = nowMs(), status = "pending", reply = "", edited = false, ver = 0, reactions = emptyMap(),
            fwd = if (m.fwd.isNotEmpty()) m.fwd else orig, authorName = myName
        )
        if (m.kind == "image" || m.kind == "file" || m.kind == "gif") {
            if (m.url.isEmpty()) {
                val src = Media.file(m.id)
                if (!src.exists()) { core.toast("Download it first"); return }
                io.execute { try { src.copyTo(Media.file(id), true) } catch (e: Exception) {}; onMain { dispatch(toChat, copy) } }
                return
            }
        }
        dispatch(toChat, copy)
    }

    fun edit(chat: String, id: String, text: String) {
        val t = text.trim().take(4000)
        val m = core.dms.find(chat, id) ?: return
        if (m.from != myUid || m.deleted) return
        if (t.isEmpty() && m.kind == "text") return
        val n = m.copy(text = t, edited = true, ver = m.ver + 1)
        core.dms.put(chat, n)
        push(chat, wire(chat, n))
    }

    fun delete(chat: String, id: String, everyone: Boolean) {
        val m = core.dms.find(chat, id) ?: return
        if (!everyone || chat == SAVED) {
            core.dms.removeMessage(chat, id)
            return
        }
        val g = if (isGroup(chat)) core.groups.get(gid(chat)) else null
        if (m.from != myUid && g?.isAdmin(myUid) != true) return
        val n = m.copy(text = "", kind = "text", url = "", name = "", size = 0, mime = "", deleted = true, ver = m.ver + 1, reactions = emptyMap(), reply = "")
        core.dms.put(chat, n)
        Media.file(id).delete()
        push(chat, wire(chat, n))
    }

    fun react(chat: String, id: String, emoji: String) {
        val m = core.dms.find(chat, id) ?: return
        val had = m.reactions[emoji]?.contains(myUid) == true
        applyReaction(chat, id, emoji, myUid, !had)
        push(chat, jsonOf("t" to "mr", "c" to wireChat(chat), "id" to id, "e" to emoji, "on" to !had).toString())
    }

    private fun applyReaction(chat: String, id: String, emoji: String, uid: String, on: Boolean) {
        core.dms.edit(chat, id) { m ->
            val r = LinkedHashMap(m.reactions)
            val cur = (r[emoji] ?: emptyList()).filter { it != uid }
            val next = if (on) cur + uid else cur
            if (next.isEmpty()) r.remove(emoji) else r[emoji] = next
            m.copy(reactions = r)
        }
    }

    fun saveToSaved(m: DmMessage) {
        forward(SAVED, m.copy(fwd = m.fwd))
        core.toast("Saved")
    }

    fun togglePin(chat: String, id: String) {
        val cur = core.dms.convs.value[chat]?.pinned ?: ""
        core.dms.setPinned(chat, if (cur == id) "" else id)
    }

    /* ------------------------------------------------------------------ */
    /* Link events                                                         */
    /* ------------------------------------------------------------------ */

    fun onLinkAuthed(link: PeerLink) {
        val uid = link.uid
        // 1:1 messages waiting for this person
        for (m in core.dms.pending(uid, myUid)) link.send(wire(uid, m))
        if (core.social.following.value.contains(uid)) link.send(jsonOf("t" to "fol", "on" to true).toString())
        // invited into a group through a link / QR we handed out
        val jr = link.joinRoomId
        if (jr.startsWith("group:") && link.offerer) {
            val g = core.groups.get(jr.removePrefix("group:"))
            if (g != null && g.canAdd(myUid) && !g.left && !g.members.containsKey(uid)) addMember(g.id, uid)
        }
        // catch up on groups we share
        for (g in core.groups.groups.value.values) {
            if (g.left || !g.members.containsKey(uid)) continue
            sendDigest(link, g)
        }
    }

    private fun sendDigest(link: PeerLink, g: Group) {
        val ids = core.dms.convs.value[g.chatId]?.messages?.takeLast(150)?.map { it.id } ?: emptyList()
        link.send(jsonOf("t" to "gh", "g" to g.id, "rev" to g.rev, "ids" to jsonArrayOf(ids)).toString())
    }

    fun sendFollow(uid: String, on: Boolean) {
        core.mesh.linkForUid(uid)?.send(jsonOf("t" to "fol", "on" to on).toString())
    }

    fun onMessage(link: PeerLink, m: JSONObject) {
        val uid = link.uid
        when (m.str("t")) {
            "fol" -> {
                core.social.setFollower(uid, m.bool("on"))
                if (m.bool("on")) core.toast("${core.contactName(uid)} followed you")
            }
            "dmack" -> {
                val chat = chatFor(link, m.str("c"))
                val cur = core.dms.find(chat, m.str("id")) ?: return
                if (m.bool("ok")) { if (cur.status != "recv") core.dms.setStatus(chat, cur.id, "sent") }
                else if (!isGroup(chat)) core.dms.setStatus(chat, cur.id, "failed", m.str("err", "Not delivered"))
            }
            "m" -> onChatMessage(link, m)
            "mr" -> {
                val chat = chatFor(link, m.str("c"))
                if (isGroup(chat) && core.groups.get(gid(chat))?.members?.containsKey(uid) != true) return
                applyReaction(chat, m.str("id"), m.str("e"), uid, m.bool("on"))
            }
            "mq" -> onMediaRequest(link, m)
            "mb" -> onMediaChunk(m)
            "gh" -> onDigest(link, m)
            "gr" -> {
                val g = core.groups.get(m.str("g")) ?: return
                if (g.members.containsKey(uid) && !g.left) sendGroupInfo(link, g)
            }
            "gi" -> onGroupInfo(link, m)
            "gu" -> onGroupUpdate(link, m)
            "gl" -> onGroupLeave(link, m)
            "call" -> onCall(link, m)
            "callr" -> {
                val why = if (m.str("r") == "busy") "is busy" else "declined the call"
                core.toast("${core.contactName(uid)} $why")
                addSystem(outgoing?.second ?: uid, "${core.contactName(uid)} $why")
            }
            "callx" -> {
                val c = incomingCall.value
                if (c != null && c.cid == m.str("cid")) {
                    clearRing()
                    addSystem(c.chat, "Missed call from ${core.contactName(c.uid)}", from = c.uid)
                }
            }
        }
    }

    private fun refuse(link: PeerLink): String? {
        val uid = link.uid
        val st = core.settings.state.value
        return when {
            core.social.blocked.value.contains(uid) -> "Not delivered"
            st.dmPolicy == "nobody" -> "${myName.ifEmpty { "This person" }} isn't accepting messages"
            st.dmPolicy == "following" && !core.social.following.value.contains(uid) ->
                "${myName.ifEmpty { "This person" }} only accepts messages from people they follow"
            else -> null
        }
    }

    private fun onChatMessage(link: PeerLink, j: JSONObject) {
        val uid = link.uid
        val c = j.str("c")
        val chat = chatFor(link, c)
        val backfill = j.bool("bf")
        var msg = DmMessage.from(j).copy(status = "recv")
        if (msg.id.isEmpty()) return
        val group = isGroup(chat)
        val g = if (group) core.groups.get(gid(chat)) else null
        fun ack(ok: Boolean, err: String = "") {
            link.send(jsonOf("t" to "dmack", "id" to msg.id, "ok" to ok, "err" to err, "c" to (if (group) gid(chat) else "dm")).toString())
        }
        if (group) {
            if (g == null || g.left || !g.members.containsKey(uid) || !g.members.containsKey(msg.from)) { ack(false, "Not a member"); return }
        } else {
            if (msg.from != uid) return
        }
        val existing = core.dms.find(chat, msg.id)
        if (existing != null) {
            val allowed = existing.from == uid || (msg.deleted && g?.isAdmin(uid) == true) || (msg.from == uid)
            if (msg.ver > existing.ver && allowed) {
                if (msg.deleted) Media.file(msg.id).delete()
                core.dms.put(chat, msg.copy(status = existing.status, reactions = if (msg.deleted) emptyMap() else existing.reactions))
            }
            ack(true)
            return
        }
        if (msg.from != uid && !group) return
        if (!group) {
            val refusal = refuse(link)
            if (refusal != null) { ack(false, refusal); return }
        }
        val mine = msg.from == myUid
        if (mine) msg = msg.copy(status = "sent")
        val muted = core.dms.convs.value[chat]?.muted == true
        core.dms.put(chat, msg, unreadBump = !mine && !backfill && openChat.value != chat && !muted)
        ack(true)
        if (!mine && !backfill && openChat.value != chat && !muted) {
            core.sfx.play("message")
            val who = if (group) "${g?.name ?: "Group"} · ${msg.authorName.ifEmpty { core.contactName(msg.from) }}" else core.contactName(uid)
            core.toast("$who: ${preview(msg)}")
        }
        if ((msg.kind == "image" || msg.kind == "gif") && msg.url.isEmpty() && msg.size <= AUTO_MEDIA) requestMedia(link, msg.id)
    }

    fun preview(m: DmMessage): String = when {
        m.deleted -> "Message deleted"
        m.kind == "image" -> "📷 Photo" + if (m.text.isNotEmpty()) " · ${m.text.take(40)}" else ""
        m.kind == "gif" -> "GIF"
        m.kind == "file" -> "📎 ${m.name}"
        else -> m.text.replace('\n', ' ').take(80)
    }

    /* ------------------------------------------------------------------ */
    /* Attachments                                                         */
    /* ------------------------------------------------------------------ */

    fun download(chat: String, m: DmMessage) {
        val from = (if (isGroup(chat)) listOf(m.from) + recipients(chat) else listOf(chat)).distinct()
        val link = from.firstNotNullOfOrNull { core.mesh.linkForUid(it) }
        if (link == null) { core.toast("Not connected to anyone who has it"); return }
        requestMedia(link, m.id)
    }

    private fun requestMedia(link: PeerLink, id: String) {
        if (Media.has(id) || mediaProgress.value.containsKey(id)) return
        setProgress(id, 0.001f)
        link.send(jsonOf("t" to "mq", "id" to id).toString())
    }

    private fun setProgress(id: String, p: Float?) {
        val m = LinkedHashMap(mediaProgress.value)
        if (p == null) m.remove(id) else m[id] = p
        mediaProgress.value = m
    }

    private fun canAccess(uid: String, id: String): Boolean {
        for ((chat, conv) in core.dms.convs.value) {
            if (chat == SAVED) continue
            if (conv.messages.none { it.id == id }) continue
            if (chat == uid) return true
            if (isGroup(chat) && core.groups.get(gid(chat))?.members?.containsKey(uid) == true) return true
        }
        return false
    }

    private fun onMediaRequest(link: PeerLink, m: JSONObject) {
        val id = m.str("id")
        val f = Media.file(id)
        if (!f.exists() || f.length() > MAX_FILE || !canAccess(link.uid, id)) {
            link.send(jsonOf("t" to "mb", "id" to id, "i" to 0, "n" to 0).toString())
            return
        }
        io.execute {
            try {
                val total = f.length()
                val chunk = 24_000
                val n = ((total + chunk - 1) / chunk).toInt().coerceAtLeast(1)
                RandomAccessFile(f, "r").use { raf ->
                    val buf = ByteArray(chunk)
                    for (i in 0 until n) {
                        val len = raf.read(buf)
                        var waited = 0
                        while (link.buffered() > 256_000 && waited < 3000) { Thread.sleep(8); waited++ }
                        val d = Base64.encodeToString(buf, 0, maxOf(len, 0), Base64.NO_WRAP)
                        if (!link.send(jsonOf("t" to "mb", "id" to id, "i" to i, "n" to n, "d" to d).toString())) return@execute
                    }
                }
            } catch (e: Exception) {
            }
        }
    }

    private fun onMediaChunk(m: JSONObject) {
        val id = m.str("id")
        val n = m.int("n")
        if (n == 0) { onMain { setProgress(id, null) }; return }
        val i = m.int("i")
        io.execute {
            try {
                val part = Media.part(id)
                if (i == 0) part.delete()
                val bytes = Base64.decode(m.str("d"), Base64.NO_WRAP)
                part.parentFile?.mkdirs()
                java.io.FileOutputStream(part, true).use { it.write(bytes) }
                if (i == n - 1) {
                    val f = Media.file(id)
                    f.delete()
                    part.renameTo(f)
                    onMain { setProgress(id, null); mediaTick.value++ }
                } else if (i % 4 == 0) {
                    onMain { setProgress(id, (i + 1f) / n) }
                }
            } catch (e: Exception) {
                onMain { setProgress(id, null) }
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Groups                                                              */
    /* ------------------------------------------------------------------ */

    private fun memberOf(uid: String, role: String = "member"): GroupMember? {
        if (uid == myUid) {
            val p = core.profile.profile.value ?: return null
            return GroupMember(uid, p.displayName, p.username, p.avatar, role)
        }
        val c = core.social.contact(uid) ?: return null
        return GroupMember(uid, c.displayName, c.username, c.avatar, role)
    }

    fun createGroup(name: String, about: String, uids: List<String>, emoji: String, hue: Int): String {
        val id = Ids.rand(10)
        val members = LinkedHashMap<String, GroupMember>()
        memberOf(myUid, "owner")?.let { members[myUid] = it }
        for (u in uids) memberOf(u)?.let { members[u] = it }
        val g = Group(id, name.trim().take(60), about.trim().take(200), myUid, members, rev = 1, emoji = emoji, hue = hue)
        core.groups.put(g)
        core.dms.ensure(g.chatId)
        addSystem(g.chatId, "You created the group")
        for (u in uids) core.mesh.linkForUid(u)?.let { sendGroupInfo(it, g) }
        return g.chatId
    }

    private fun sendGroupInfo(link: PeerLink, g: Group) {
        val recent = core.dms.convs.value[g.chatId]?.messages?.takeLast(60)?.filter { it.from.isNotEmpty() }?.map { JSONObject(wire(g.chatId, it, true)) } ?: emptyList()
        val j = jsonOf("t" to "gi", "g" to g.toJson())
        j.put("msgs", org.json.JSONArray().also { a -> recent.forEach { a.put(it) } })
        link.send(j.toString())
    }

    private fun onDigest(link: PeerLink, m: JSONObject) {
        val gidv = m.str("g")
        val g = core.groups.get(gidv)
        if (g == null || g.left) {
            // they say we share a group we don't know yet: ask for details
            link.send(jsonOf("t" to "gr", "g" to gidv).toString())
            return
        }
        if (!g.members.containsKey(link.uid)) return
        val have = m.arr("ids")?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        if (m.lng("rev") < g.rev) link.send(jsonOf("t" to "gu", "g" to g.toJson()).toString())
        val missing = core.dms.convs.value[g.chatId]?.messages?.takeLast(150)?.filter { it.id !in have && it.from.isNotEmpty() }?.takeLast(100) ?: emptyList()
        for (x in missing) link.send(wire(g.chatId, x, true))
    }

    private fun onGroupInfo(link: PeerLink, m: JSONObject) {
        val gj = m.obj("g") ?: return
        val g = Group.from(gj)
        if (g.id.isEmpty() || !g.members.containsKey(myUid) || !g.members.containsKey(link.uid)) return
        if (core.social.blocked.value.contains(link.uid)) return
        val cur = core.groups.get(g.id)
        if (cur != null && cur.rev >= g.rev && !cur.left) {
            // already know it; still merge messages
        } else {
            if (cur == null && core.settings.state.value.dmPolicy == "nobody") return
            core.groups.put(g.copy(left = false))
            core.dms.ensure(g.chatId)
            if (cur == null) {
                addSystem(g.chatId, "You joined “${g.name}”")
                core.sfx.play("join")
                core.toast("Added to group: ${g.name}")
            }
        }
        m.arr("msgs")?.objects()?.forEach { onChatMessage(link, it) }
    }

    private fun onGroupUpdate(link: PeerLink, m: JSONObject) {
        val g = Group.from(m.obj("g") ?: return)
        val cur = core.groups.get(g.id) ?: return
        if (!cur.members.containsKey(link.uid) || g.rev <= cur.rev) return
        if (!g.members.containsKey(myUid)) {
            core.groups.put(cur.copy(left = true, rev = g.rev))
            addSystem(cur.chatId, "You were removed from this group")
            return
        }
        val added = g.members.keys - cur.members.keys
        val gone = cur.members.keys - g.members.keys
        core.groups.put(g.copy(left = false))
        for (u in added) addSystem(g.chatId, "${g.members[u]?.name ?: "Someone"} joined")
        for (u in gone) addSystem(g.chatId, "${cur.members[u]?.name ?: "Someone"} left")
        if (cur.name != g.name) addSystem(g.chatId, "Group renamed to “${g.name}”")
    }

    private fun onGroupLeave(link: PeerLink, m: JSONObject) {
        val g = core.groups.get(m.str("g")) ?: return
        val who = g.members[link.uid] ?: return
        val members = LinkedHashMap(g.members)
        members.remove(link.uid)
        var owner = g.ownerUid
        if (owner == link.uid) {
            val next = members.values.firstOrNull { it.role == "admin" } ?: members.values.minByOrNull { it.addedAt }
            if (next != null) { owner = next.uid; members[next.uid] = next.copy(role = "owner") }
        }
        core.groups.put(g.copy(members = members, rev = maxOf(g.rev, m.lng("rev")) + 0, ownerUid = owner))
        addSystem(g.chatId, "${who.name} left")
    }

    private fun broadcastGroup(g: Group) {
        val s = jsonOf("t" to "gu", "g" to g.toJson()).toString()
        for (u in g.members.keys) if (u != myUid) core.mesh.linkForUid(u)?.send(s)
    }

    fun addMember(gidv: String, uid: String): Boolean {
        val g = core.groups.get(gidv) ?: return false
        if (!g.canAdd(myUid) || g.members.containsKey(uid)) return false
        val mem = memberOf(uid) ?: return false
        val ng = g.copy(members = g.members + (uid to mem), rev = g.rev + 1)
        core.groups.put(ng)
        addSystem(ng.chatId, "${mem.name} was added")
        broadcastGroup(ng)
        core.mesh.linkForUid(uid)?.let { sendGroupInfo(it, ng) }
        return true
    }

    fun removeMember(gidv: String, uid: String) {
        val g = core.groups.get(gidv) ?: return
        if (!g.isAdmin(myUid) || uid == g.ownerUid || uid == myUid) return
        val gone = g.members[uid] ?: return
        val ng = g.copy(members = g.members - uid, rev = g.rev + 1)
        core.groups.put(ng)
        addSystem(ng.chatId, "${gone.name} was removed")
        broadcastGroup(ng)
        core.mesh.linkForUid(uid)?.send(jsonOf("t" to "gu", "g" to ng.toJson()).toString())
    }

    fun setAdmin(gidv: String, uid: String, on: Boolean) {
        val g = core.groups.get(gidv) ?: return
        if (g.ownerUid != myUid) return
        val m = g.members[uid] ?: return
        if (m.role == "owner") return
        val ng = g.copy(members = g.members + (uid to m.copy(role = if (on) "admin" else "member")), rev = g.rev + 1)
        core.groups.put(ng)
        broadcastGroup(ng)
    }

    fun updateGroup(gidv: String, name: String, about: String, membersCanAdd: Boolean, emoji: String, hue: Int) {
        val g = core.groups.get(gidv) ?: return
        if (!g.isAdmin(myUid)) return
        val ng = g.copy(name = name.trim().take(60).ifEmpty { g.name }, about = about.trim().take(200), membersCanAdd = membersCanAdd, emoji = emoji, hue = hue, rev = g.rev + 1)
        core.groups.put(ng)
        if (ng.name != g.name) addSystem(ng.chatId, "You renamed the group to “${ng.name}”")
        broadcastGroup(ng)
    }

    fun leaveGroup(gidv: String) {
        val g = core.groups.get(gidv) ?: return
        val s = jsonOf("t" to "gl", "g" to g.id, "rev" to g.rev + 1).toString()
        for (u in g.members.keys) if (u != myUid) core.mesh.linkForUid(u)?.send(s)
        core.groups.put(g.copy(left = true, members = g.members - myUid, rev = g.rev + 1))
        addSystem(g.chatId, "You left the group")
    }

    fun deleteChat(chat: String) {
        if (isGroup(chat)) {
            val g = core.groups.get(gid(chat))
            if (g != null && !g.left) leaveGroup(g.id)
            core.groups.remove(gid(chat))
        }
        core.dms.deleteChat(chat)
    }

    private fun addSystem(chat: String, text: String, from: String = "") {
        core.dms.put(chat, DmMessage(Ids.id("s_"), from, text, nowMs(), "sent", kind = "call"))
    }

    /* ------------------------------------------------------------------ */
    /* Calls: a private voice room that the other side is rung into         */
    /* ------------------------------------------------------------------ */

    fun startCall(chat: String): Boolean {
        if (core.session.phase.value != "idle") { core.toast("Leave your current room first"); return false }
        val rec = recipients(chat).filter { core.connected.value.contains(it) }
        if (rec.isEmpty()) {
            core.toast("Nobody from this chat is connected right now. Connect over Wi-Fi or with a code first.")
            return false
        }
        core.platform?.requestMic { }
        val title = if (isGroup(chat)) "Call · ${titleOf(chat)}" else "Call with ${titleOf(chat)}"
        if (!core.session.createRoom(title, "", emptyList(), true, 24)) { core.toast("Couldn't start the call"); return false }
        val rid = core.session.room?.meta?.id ?: return false
        val s = jsonOf("t" to "call", "cid" to rid, "c" to wireChat(chat), "title" to title).toString()
        outgoing = Triple(rid, chat, rec)
        for (u in rec) core.mesh.linkForUid(u)?.send(s)
        addSystem(chat, "You started a call")
        return true
    }

    fun onRoomClosed(roomId: String?) {
        val o = outgoing ?: return
        if (roomId == null || o.first != roomId) return
        outgoing = null
        val s = jsonOf("t" to "callx", "cid" to roomId).toString()
        for (u in o.third) core.mesh.linkForUid(u)?.send(s)
    }

    private fun onCall(link: PeerLink, m: JSONObject) {
        val uid = link.uid
        val chat = chatFor(link, m.str("c"))
        if (core.social.blocked.value.contains(uid)) return
        if (isGroup(chat) && core.groups.get(gid(chat))?.members?.containsKey(uid) != true) return
        if (core.session.phase.value != "idle" || incomingCall.value != null) {
            link.send(jsonOf("t" to "callr", "cid" to m.str("cid"), "r" to "busy").toString())
            return
        }
        incomingCall.value = IncomingCall(m.str("cid"), uid, chat, m.str("title"), isGroup(chat))
        ring()
    }

    private fun ring() {
        cancelLater(ringTimer)
        val c = incomingCall.value ?: return
        core.sfx.play("join")
        var rounds = 0
        fun tick() {
            if (incomingCall.value == null) return
            rounds++
            if (rounds > 18) {
                addSystem(c.chat, "Missed call from ${core.contactName(c.uid)}", from = c.uid)
                clearRing()
                return
            }
            core.sfx.play("join")
            ringTimer = later(2400) { tick() }
        }
        ringTimer = later(2400) { tick() }
    }

    private fun clearRing() {
        cancelLater(ringTimer)
        ringTimer = null
        incomingCall.value = null
    }

    fun acceptCall() {
        val c = incomingCall.value ?: return
        val link = core.mesh.linkForUid(c.uid)
        clearRing()
        if (link == null) { core.toast("The caller is no longer connected"); return }
        if (core.session.phase.value != "idle") return
        core.platform?.requestMic { }
        link.joinRoomId = c.cid
        core.session.beginJoin(link)
    }

    fun declineCall() {
        val c = incomingCall.value ?: return
        core.mesh.linkForUid(c.uid)?.send(jsonOf("t" to "callr", "cid" to c.cid, "r" to "decline").toString())
        clearRing()
    }
}
