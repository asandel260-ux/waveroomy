package app.waveroom.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/* ===========================================================================
 * Plain data models. Everything round-trips through org.json because the same
 * structures travel over WebRTC data channels and are persisted to disk.
 * ========================================================================= */

data class Avatar(val emoji: String = "🎧", val hue: Int = 265) {
    fun toJson(): JSONObject = JSONObject().put("e", emoji).put("h", hue)

    companion object {
        fun from(j: JSONObject?): Avatar =
            if (j == null) Avatar() else Avatar(j.str("e", "🎧"), j.int("h", 265))
    }
}

val AVATAR_EMOJIS = listOf("🎧", "🎙️", "🎹", "🎸", "🥁", "🎷", "📻", "🎤", "🌙", "⚡", "🔥", "🌊", "🪐", "🦊", "🐳", "🌵")
val REACTIONS = listOf("👏", "🔥", "❤️", "😂", "🎉", "👍", "💯", "🤯")

fun canSpeak(role: String): Boolean = role == "host" || role == "speaker"

/* ----------------------------------------------------------------------------
 * Room
 * -------------------------------------------------------------------------- */

data class RoomMeta(
    val id: String,
    val title: String,
    val topic: String = "",
    val tags: List<String> = emptyList(),
    val ownerUid: String,
    val ownerName: String = "",
    val unlisted: Boolean = false,
    val locked: Boolean = false,
    val maxSpeakers: Int = 8,
    val createdAt: Long = nowMs(),
    /** uid -> banned until (epoch ms); 0 means permanent */
    val bans: Map<String, Long> = emptyMap()
) {
    fun isBanned(uid: String): Boolean {
        val until = bans[uid] ?: return false
        return until == 0L || until > nowMs()
    }

    fun toJson(): JSONObject {
        val b = JSONObject()
        for ((k, v) in bans) b.put(k, v)
        return JSONObject()
            .put("id", id).put("title", title).put("topic", topic)
            .put("tags", jsonArrayOf(tags))
            .put("owner", ownerUid).put("ownerName", ownerName)
            .put("unlisted", unlisted).put("locked", locked)
            .put("maxSpeakers", maxSpeakers).put("createdAt", createdAt)
            .put("bans", b)
    }

    companion object {
        fun from(j: JSONObject): RoomMeta {
            val bans = HashMap<String, Long>()
            val bj = j.obj("bans")
            if (bj != null) {
                val it = bj.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    bans[k] = bj.optLong(k, 0L)
                }
            }
            return RoomMeta(
                id = j.str("id"),
                title = j.str("title", "Untitled room"),
                topic = j.str("topic"),
                tags = j.arr("tags")?.strings() ?: emptyList(),
                ownerUid = j.str("owner"),
                ownerName = j.str("ownerName"),
                unlisted = j.bool("unlisted"),
                locked = j.bool("locked"),
                maxSpeakers = j.int("maxSpeakers", 8),
                createdAt = j.lng("createdAt", nowMs()),
                bans = bans
            )
        }
    }
}

data class Sharing(val res: Int, val fps: Int, val at: Long) {
    fun toJson(): JSONObject = JSONObject().put("res", res).put("fps", fps).put("at", at)

    companion object {
        fun from(j: JSONObject?): Sharing? = if (j == null) null else Sharing(j.int("res", 720), j.int("fps", 30), j.lng("at"))
    }
}

data class Participant(
    val sid: String,
    val uid: String,
    val username: String,
    val displayName: String,
    val avatar: Avatar,
    val role: String = "listener",
    val muted: Boolean = true,
    val hand: Boolean = false,
    val sharing: Sharing? = null,
    val browsing: Boolean = false,
    val joinedAt: Long = nowMs(),
    /** sid of the member that introduced this participant (signalling relay hint) */
    val via: String = ""
) {
    fun toJson(): JSONObject {
        val j = JSONObject()
            .put("sid", sid).put("uid", uid).put("username", username).put("displayName", displayName)
            .put("avatar", avatar.toJson()).put("role", role).put("muted", muted).put("hand", hand)
            .put("browsing", browsing).put("joinedAt", joinedAt).put("via", via)
        if (sharing != null) j.put("sharing", sharing.toJson())
        return j
    }

    companion object {
        fun from(j: JSONObject): Participant = Participant(
            sid = j.str("sid"), uid = j.str("uid"), username = j.str("username"),
            displayName = j.str("displayName"), avatar = Avatar.from(j.obj("avatar")),
            role = j.str("role", "listener"), muted = j.bool("muted", true), hand = j.bool("hand"),
            sharing = Sharing.from(j.obj("sharing")), browsing = j.bool("browsing"),
            joinedAt = j.lng("joinedAt", nowMs()), via = j.str("via")
        )
    }
}

data class ChatMsg(
    val id: String,
    val uid: String,
    val username: String,
    val displayName: String,
    val avatar: Avatar,
    val text: String,
    val at: Long,
    val deleted: Boolean = false,
    val reactions: Map<String, List<String>> = emptyMap()
) {
    fun toJson(): JSONObject {
        val r = JSONObject()
        for ((k, v) in reactions) r.put(k, jsonArrayOf(v))
        return JSONObject()
            .put("id", id).put("uid", uid).put("username", username).put("displayName", displayName)
            .put("avatar", avatar.toJson()).put("text", text).put("at", at)
            .put("deleted", deleted).put("reactions", r)
    }

    companion object {
        fun reactionsFrom(j: JSONObject?): Map<String, List<String>> {
            val out = LinkedHashMap<String, List<String>>()
            if (j != null) {
                val it = j.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    out[k] = j.optJSONArray(k)?.strings() ?: emptyList()
                }
            }
            return out
        }

        fun from(j: JSONObject): ChatMsg = ChatMsg(
            id = j.str("id"), uid = j.str("uid"), username = j.str("username"),
            displayName = j.str("displayName"), avatar = Avatar.from(j.obj("avatar")),
            text = j.str("text"), at = j.lng("at"), deleted = j.bool("deleted"),
            reactions = reactionsFrom(j.obj("reactions"))
        )
    }
}

/* ----------------------------------------------------------------------------
 * Shared blackboard
 * -------------------------------------------------------------------------- */

val BOARD_TYPES = setOf("stroke", "line", "arrow", "rect", "ellipse", "text", "image")

fun rgbToHex(c: Int): String = "#%06x".format(c and 0xFFFFFF)

fun hexToRgb(s: String): Int = try {
    java.lang.Long.parseLong(s.removePrefix("#"), 16).toInt() and 0xFFFFFF
} catch (e: Exception) {
    0xEDEDEF
}

class BoardObj(
    val id: String,
    val type: String
) {
    var points: FloatArray? = null          // x0,y0,x1,y1,...
    var x = 0f
    var y = 0f
    var w = 0f
    var h = 0f
    var x1 = 0f
    var y1 = 0f
    var x2 = 0f
    var y2 = 0f
    var text = ""
    var size = 18f
    var src: String? = null                 // base64 (no data: prefix), PNG/JPEG/WebP bytes
    var color = 0xEDEDEF
    var width = 3f
    var opacity = 1f
    var dash = false
    var rot = 0f
    var z = 0
    var locked = false
    var group: String? = null
    var by = ""

    fun deepCopy(): BoardObj {
        val o = BoardObj(id, type)
        o.points = points?.copyOf()
        o.x = x; o.y = y; o.w = w; o.h = h
        o.x1 = x1; o.y1 = y1; o.x2 = x2; o.y2 = y2
        o.text = text; o.size = size; o.src = src; o.color = color; o.width = width
        o.opacity = opacity; o.dash = dash; o.rot = rot; o.z = z; o.locked = locked
        o.group = group; o.by = by
        return o
    }

    fun toJson(withSrc: Boolean = true): JSONObject {
        val j = JSONObject().put("id", id).put("type", type)
        when (type) {
            "stroke" -> {
                val a = JSONArray()
                val p = points
                if (p != null) for (v in p) a.put(round1(v))
                j.put("points", a)
            }
            "line", "arrow" -> j.put("x1", round1(x1)).put("y1", round1(y1)).put("x2", round1(x2)).put("y2", round1(y2))
            "text" -> j.put("x", round1(x)).put("y", round1(y)).put("text", text).put("size", size)
            else -> j.put("x", round1(x)).put("y", round1(y)).put("w", round1(w)).put("h", round1(h))
        }
        if (type == "image" && withSrc) j.put("src", src ?: "")
        j.put("color", rgbToHex(color)).put("width", width).put("opacity", opacity.toDouble())
            .put("dash", if (dash) 1 else 0).put("rot", rot.toDouble()).put("z", z)
            .put("locked", if (locked) 1 else 0).put("by", by)
        if (group != null) j.put("group", group)
        return j
    }

    /** Apply a (partial) patch exactly like the web client's whitelist-merge. */
    fun applyPatch(p: JSONObject) {
        if (p.has("points") && !p.isNull("points")) {
            val a = p.optJSONArray("points")
            if (a != null) {
                val f = FloatArray(a.length())
                for (i in 0 until a.length()) f[i] = a.optDouble(i, 0.0).toFloat()
                points = f
            }
        }
        if (p.has("x")) x = p.flt("x")
        if (p.has("y")) y = p.flt("y")
        if (p.has("w")) w = p.flt("w")
        if (p.has("h")) h = p.flt("h")
        if (p.has("x1")) x1 = p.flt("x1")
        if (p.has("y1")) y1 = p.flt("y1")
        if (p.has("x2")) x2 = p.flt("x2")
        if (p.has("y2")) y2 = p.flt("y2")
        if (p.has("text")) text = p.str("text").take(2000)
        if (p.has("size")) size = p.flt("size", 18f)
        if (p.has("color")) color = hexToRgb(p.str("color"))
        if (p.has("width")) width = p.flt("width", 3f)
        if (p.has("opacity")) opacity = p.flt("opacity", 1f)
        if (p.has("dash")) dash = p.int("dash") != 0
        if (p.has("rot")) rot = p.flt("rot")
        if (p.has("z")) z = p.int("z")
        if (p.has("locked")) locked = p.int("locked") != 0
        if (p.has("group")) group = if (p.isNull("group")) null else p.str("group")
    }

    companion object {
        private fun round1(v: Float): Double = Math.round(v * 10.0) / 10.0

        fun from(j: JSONObject): BoardObj? {
            val type = j.str("type")
            if (type !in BOARD_TYPES) return null
            val o = BoardObj(j.str("id"), type)
            if (j.has("src")) o.src = j.str("src")
            if (j.has("by")) o.by = j.str("by")
            o.applyPatch(j)
            return o
        }
    }
}

class BoardState {
    val objects = LinkedHashMap<String, BoardObj>()
    var z = 1

    /** Bumped on any change so the canvas knows to redraw. */
    val version = MutableStateFlow(0)

    fun touch() {
        version.value = version.value + 1
    }

    fun clear() {
        objects.clear()
        z = 1
        touch()
    }

    fun add(o: BoardObj) {
        objects[o.id] = o
        if (o.z >= z) z = o.z + 1
        touch()
    }

    fun remove(ids: Collection<String>) {
        for (id in ids) objects.remove(id)
        touch()
    }

    fun patch(id: String, p: JSONObject) {
        val o = objects[id] ?: return
        o.applyPatch(p)
        if (o.z >= z) z = o.z + 1
        touch()
    }

    fun bytes(): Int {
        var n = 0
        for (o in objects.values) {
            n += if (o.type == "image") (o.src?.length ?: 0) + 200
            else if (o.type == "stroke") (o.points?.size ?: 0) * 7 + 200
            else 260
        }
        return n
    }

    fun sortedByZ(): List<BoardObj> = objects.values.sortedBy { it.z }

    fun toJson(): JSONArray {
        val a = JSONArray()
        for (o in objects.values) a.put(o.toJson())
        return a
    }

    fun load(a: JSONArray?) {
        objects.clear()
        z = 1
        if (a != null) for (j in a.objects()) {
            val o = BoardObj.from(j) ?: continue
            objects[o.id] = o
            if (o.z >= z) z = o.z + 1
        }
        touch()
    }
}

/* ----------------------------------------------------------------------------
 * Watch Together
 * -------------------------------------------------------------------------- */

data class WatchVideo(
    val id: String,
    val title: String,
    val author: String = "",
    val duration: Int = 0,
    val qid: String = "",
    val by: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("title", title).put("author", author)
        .put("duration", duration).put("qid", qid).put("by", by)

    companion object {
        fun from(j: JSONObject): WatchVideo = WatchVideo(
            j.str("id"), j.str("title", "YouTube video"), j.str("author"),
            j.int("duration"), j.str("qid"), j.str("by")
        )
    }
}

data class WatchState(
    val video: WatchVideo? = null,
    val playing: Boolean = false,
    /** playback position (seconds) at [anchorMs] */
    val position: Double = 0.0,
    val anchorMs: Long = nowMs(),
    val rate: Double = 1.0,
    val controllerName: String = "",
    val perm: String = "host",
    val mods: List<String> = emptyList(),
    val queue: List<WatchVideo> = emptyList()
) {
    /** Position now, extrapolated from the anchor on this device's own clock. */
    fun expectedPosition(): Double {
        var p = position
        if (playing && video != null) p += (nowMs() - anchorMs) / 1000.0 * rate
        val d = video?.duration ?: 0
        if (d > 0) p = minOf(p, d.toDouble())
        return maxOf(0.0, p)
    }

    /** Snapshot for the wire: position is already extrapolated to "now". */
    fun toSnapshot(): JSONObject {
        val q = JSONArray()
        for (v in queue) q.put(v.toJson())
        return JSONObject()
            .put("video", video?.toJson() ?: JSONObject.NULL)
            .put("playing", playing).put("position", expectedPosition()).put("rate", rate)
            .put("controller", controllerName).put("perm", perm)
            .put("mods", jsonArrayOf(mods)).put("queue", q)
    }

    companion object {
        fun fromSnapshot(j: JSONObject): WatchState = WatchState(
            video = j.obj("video")?.let { WatchVideo.from(it) },
            playing = j.bool("playing"),
            position = j.dbl("position"),
            anchorMs = nowMs(),
            rate = j.dbl("rate", 1.0),
            controllerName = j.str("controller"),
            perm = j.str("perm", "host"),
            mods = j.arr("mods")?.strings() ?: emptyList(),
            queue = j.arr("queue")?.objects()?.map { WatchVideo.from(it) } ?: emptyList()
        )
    }
}

/* ----------------------------------------------------------------------------
 * Shared browser
 * -------------------------------------------------------------------------- */

data class NavRequest(val uid: String, val name: String, val at: Long)

data class BrowseState(
    val url: String? = null,
    val title: String = "",
    val host: String = "",
    val nonce: Int = 0,
    val navigatorUid: String = "",
    val navigatorName: String = "",
    val perm: String = "host",
    val mods: List<String> = emptyList(),
    val history: List<String> = emptyList(),
    val idx: Int = -1,
    val requests: List<NavRequest> = emptyList()
) {
    val canBack: Boolean get() = idx > 0
    val canForward: Boolean get() = idx >= 0 && idx < history.size - 1

    fun toJson(): JSONObject {
        val rq = JSONArray()
        for (r in requests) rq.put(JSONObject().put("uid", r.uid).put("name", r.name).put("at", r.at))
        return JSONObject()
            .put("url", url ?: JSONObject.NULL).put("title", title).put("host", host).put("nonce", nonce)
            .put("navUid", navigatorUid).put("navName", navigatorName).put("perm", perm)
            .put("mods", jsonArrayOf(mods)).put("history", jsonArrayOf(history)).put("idx", idx)
            .put("requests", rq)
    }

    companion object {
        fun from(j: JSONObject): BrowseState = BrowseState(
            url = j.strOrNull("url"), title = j.str("title"), host = j.str("host"), nonce = j.int("nonce"),
            navigatorUid = j.str("navUid"), navigatorName = j.str("navName"), perm = j.str("perm", "host"),
            mods = j.arr("mods")?.strings() ?: emptyList(),
            history = j.arr("history")?.strings() ?: emptyList(),
            idx = j.int("idx", -1),
            requests = j.arr("requests")?.objects()?.map { NavRequest(it.str("uid"), it.str("name"), it.lng("at")) } ?: emptyList()
        )
    }
}

/* ----------------------------------------------------------------------------
 * The replicated room: every member keeps a mirror; the acting host's mirror is
 * authoritative. `apply` is the single reducer that mutates it.
 * -------------------------------------------------------------------------- */

class RoomState(var meta: RoomMeta) {
    var participants = LinkedHashMap<String, Participant>()
    val chat = ArrayList<ChatMsg>()
    val board = BoardState()
    var watch = WatchState()
    var browse = BrowseState()

    /** The member who runs the room logic: earliest-joined participant holding the host role. */
    fun engineSid(): String? =
        participants.values.filter { it.role == "host" }
            .sortedWith(compareBy<Participant> { it.joinedAt }.thenBy { it.sid })
            .firstOrNull()?.sid

    fun snapshot(): JSONObject {
        val ps = JSONArray()
        for (p in participants.values) ps.put(p.toJson())
        val ch = JSONArray()
        for (m in chat.takeLast(300)) ch.put(m.toJson())
        return JSONObject()
            .put("meta", meta.toJson()).put("participants", ps).put("chat", ch)
            .put("board", board.toJson()).put("watch", watch.toSnapshot()).put("browse", browse.toJson())
    }

    fun loadSnapshot(j: JSONObject) {
        j.obj("meta")?.let { meta = RoomMeta.from(it) }
        val ps = LinkedHashMap<String, Participant>()
        j.arr("participants")?.objects()?.forEach { val p = Participant.from(it); ps[p.sid] = p }
        participants = ps
        chat.clear()
        j.arr("chat")?.objects()?.forEach { chat.add(ChatMsg.from(it)) }
        board.load(j.arr("board"))
        watch = j.obj("watch")?.let { WatchState.fromSnapshot(it) } ?: WatchState()
        browse = j.obj("browse")?.let { BrowseState.from(it) } ?: BrowseState()
    }

    /** Persistable form: no participants (a saved room starts empty). */
    fun toSavedJson(): JSONObject {
        val ch = JSONArray()
        for (m in chat.takeLast(300)) ch.put(m.toJson())
        return JSONObject().put("meta", meta.toJson()).put("chat", ch).put("board", board.toJson())
    }

    fun applyState(d: JSONObject) {
        d.obj("meta")?.let { meta = RoomMeta.from(it) }
        val ps = LinkedHashMap<String, Participant>()
        d.arr("participants")?.objects()?.forEach { val p = Participant.from(it); ps[p.sid] = p }
        participants = ps
    }

    fun apply(ev: String, d: JSONObject) {
        when (ev) {
            "room:state" -> applyState(d)
            "room:mute" -> {
                val p = participants[d.str("sid")]
                if (p != null) participants[p.sid] = p.copy(muted = d.bool("muted"))
            }
            "chat:new" -> {
                val m = ChatMsg.from(d)
                if (chat.none { it.id == m.id }) chat.add(m)
                while (chat.size > 300) chat.removeAt(0)
            }
            "chat:update" -> {
                val id = d.str("id")
                val i = chat.indexOfFirst { it.id == id }
                if (i >= 0) {
                    val m = chat[i]
                    chat[i] = m.copy(
                        deleted = d.bool("deleted", m.deleted),
                        reactions = ChatMsg.reactionsFrom(d.obj("reactions"))
                    )
                }
            }
            "board:add" -> BoardObj.from(d.obj("obj") ?: JSONObject())?.let { board.add(it) }
            "board:update" -> board.patch(d.str("id"), d.obj("patch") ?: JSONObject())
            "board:delete" -> board.remove(d.arr("ids")?.strings() ?: emptyList())
            "board:cleared" -> board.clear()
            "watch:load", "watch:state" -> watch = WatchState.fromSnapshot(d)
            "watch:meta" -> {
                val v = watch.video
                if (v != null) watch = watch.copy(video = v.copy(duration = d.int("duration")))
            }
            "watch:queue" -> watch = watch.copy(queue = d.arr("queue")?.objects()?.map { WatchVideo.from(it) } ?: emptyList())
            "watch:perm" -> watch = watch.copy(perm = d.str("perm", "host"))
            "watch:mods" -> watch = watch.copy(mods = d.arr("mods")?.strings() ?: emptyList())
            "watch:ended" -> watch = WatchState(perm = watch.perm, mods = watch.mods)
            "browse:load", "browse:state", "browse:reload" -> browse = BrowseState.from(d)
            "browse:ended" -> browse = BrowseState(perm = browse.perm, mods = browse.mods)
        }
    }
}

/* ----------------------------------------------------------------------------
 * Local-only records
 * -------------------------------------------------------------------------- */

/** What we remember about another person (learned from the handshake). */
data class Contact(
    val uid: String,
    val username: String,
    val displayName: String,
    val avatar: Avatar,
    val bio: String = "",
    val pronouns: String = "",
    val pub: String = "",
    val lastSeen: Long = 0L,
    val firstSeen: Long = nowMs()
) {
    fun toJson(): JSONObject = JSONObject().put("uid", uid).put("username", username)
        .put("displayName", displayName).put("avatar", avatar.toJson()).put("bio", bio)
        .put("pronouns", pronouns).put("pub", pub).put("lastSeen", lastSeen).put("firstSeen", firstSeen)

    companion object {
        fun from(j: JSONObject): Contact = Contact(
            j.str("uid"), j.str("username"), j.str("displayName"), Avatar.from(j.obj("avatar")),
            j.str("bio"), j.str("pronouns"), j.str("pub"), j.lng("lastSeen"), j.lng("firstSeen", nowMs())
        )
    }
}

data class DmMessage(
    val id: String,
    val from: String,        // uid
    val text: String,
    val at: Long,
    /** "sent" | "pending" | "failed" for my messages, "recv" for theirs */
    val status: String = "recv",
    val error: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("from", from).put("text", text)
        .put("at", at).put("status", status).put("error", error)

    companion object {
        fun from(j: JSONObject): DmMessage = DmMessage(
            j.str("id"), j.str("from"), j.str("text"), j.lng("at"), j.str("status", "recv"), j.str("error")
        )
    }
}

data class Conversation(
    val uid: String,
    val messages: List<DmMessage> = emptyList(),
    val unread: Int = 0,
    val lastAt: Long = 0L
) {
    fun toJson(): JSONObject {
        val a = JSONArray()
        for (m in messages.takeLast(500)) a.put(m.toJson())
        return JSONObject().put("uid", uid).put("unread", unread).put("lastAt", lastAt).put("messages", a)
    }

    companion object {
        fun from(j: JSONObject): Conversation = Conversation(
            j.str("uid"),
            j.arr("messages")?.objects()?.map { DmMessage.from(it) } ?: emptyList(),
            j.int("unread"), j.lng("lastAt")
        )
    }
}

data class Report(
    val id: String,
    val reporterUid: String,
    val reporterName: String,
    val targetType: String,
    val targetId: String,
    val targetLabel: String,
    val roomId: String,
    val roomTitle: String,
    val category: String,
    val details: String,
    val status: String = "open",     // open | resolved | dismissed
    val createdAt: Long = nowMs(),
    val note: String = "",
    val resolvedAt: Long = 0L,
    /** true when I filed it, false when it was filed to me as the host */
    val outgoing: Boolean = false,
    val excerpt: String = ""
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("reporterUid", reporterUid)
        .put("reporterName", reporterName).put("targetType", targetType).put("targetId", targetId)
        .put("targetLabel", targetLabel).put("roomId", roomId).put("roomTitle", roomTitle)
        .put("category", category).put("details", details).put("status", status)
        .put("createdAt", createdAt).put("note", note).put("resolvedAt", resolvedAt)
        .put("outgoing", outgoing).put("excerpt", excerpt)

    companion object {
        fun from(j: JSONObject): Report = Report(
            j.str("id"), j.str("reporterUid"), j.str("reporterName"), j.str("targetType"), j.str("targetId"),
            j.str("targetLabel"), j.str("roomId"), j.str("roomTitle"), j.str("category"), j.str("details"),
            j.str("status", "open"), j.lng("createdAt"), j.str("note"), j.lng("resolvedAt"),
            j.bool("outgoing"), j.str("excerpt")
        )
    }
}

data class AuditEntry(
    val id: String,
    val action: String,
    val roomId: String,
    val roomTitle: String,
    val targetLabel: String,
    val detail: String,
    val at: Long = nowMs()
) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("action", action).put("roomId", roomId)
        .put("roomTitle", roomTitle).put("target", targetLabel).put("detail", detail).put("at", at)

    companion object {
        fun from(j: JSONObject): AuditEntry = AuditEntry(
            j.str("id"), j.str("action"), j.str("roomId"), j.str("roomTitle"), j.str("target"),
            j.str("detail"), j.lng("at")
        )
    }
}

/** A room remembered on this device (created or joined). Board and chat live in a side file. */
data class SavedRoom(
    val meta: RoomMeta,
    val lastActiveAt: Long = nowMs(),
    val totalJoins: Int = 0,
    val peak: Int = 0,
    val isOwner: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject().put("meta", meta.toJson()).put("lastActiveAt", lastActiveAt)
        .put("totalJoins", totalJoins).put("peak", peak).put("isOwner", isOwner)

    companion object {
        fun from(j: JSONObject): SavedRoom = SavedRoom(
            RoomMeta.from(j.obj("meta") ?: JSONObject()), j.lng("lastActiveAt"), j.int("totalJoins"),
            j.int("peak"), j.bool("isOwner")
        )
    }
}

/** A room announced on the local network. */
data class NearbyRoom(
    val roomId: String,
    val title: String,
    val topic: String,
    val tags: List<String>,
    val hostName: String,
    val count: Int,
    val speakers: Int,
    val locked: Boolean,
    val ip: String,
    val port: Int,
    val seenAt: Long
)
