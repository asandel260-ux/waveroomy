package app.waveroom.room

import app.waveroom.core.Avatar
import app.waveroom.core.BOARD_TYPES
import app.waveroom.core.BoardObj
import app.waveroom.core.BrowseState
import app.waveroom.core.ChatMsg
import app.waveroom.core.Ids
import app.waveroom.core.NavRequest
import app.waveroom.core.Participant
import app.waveroom.core.REACTIONS
import app.waveroom.core.RateLimiter
import app.waveroom.core.Report
import app.waveroom.core.RoomMeta
import app.waveroom.core.RoomState
import app.waveroom.core.Sharing
import app.waveroom.core.WatchState
import app.waveroom.core.WatchVideo
import app.waveroom.core.arr
import app.waveroom.core.bool
import app.waveroom.core.canSpeak
import app.waveroom.core.dbl
import app.waveroom.core.int
import app.waveroom.core.jsonArrayOf
import app.waveroom.core.jsonOf
import app.waveroom.core.lng
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.str
import app.waveroom.core.strings
import org.json.JSONObject

/** Everything the engine needs from the device it runs on. */
interface EngineIO {
    val state: RoomState

    /**
     * Apply [ev] to the local mirror and deliver it to every other participant
     * (except [except]); if [only] is set, deliver just to that sid.
     */
    fun emit(ev: String, data: JSONObject, only: String? = null, except: Set<String> = emptySet())

    fun audit(action: String, target: String, detail: String)
    fun storeReport(r: Report)
    fun bump(counter: String)
    fun noteJoin(count: Int)
}

/**
 * The room's rules, ported from the web server's signaling.js. It runs on exactly
 * one device at a time: the acting host. Everyone else sends it requests and applies
 * the events it emits, so there is still one authority, just not a server.
 */
class RoomEngine(private val io: EngineIO) {

    private val st: RoomState get() = io.state
    private val limiter = RateLimiter()

    private fun ok(vararg extra: Pair<String, Any?>): JSONObject {
        val j = JSONObject().put("ok", true)
        for ((k, v) in extra) j.put(k, v)
        return j
    }

    private fun err(msg: String): JSONObject = JSONObject().put("ok", false).put("error", msg)

    private fun isMod(sid: String) = st.participants[sid]?.role == "host"

    private fun broadcastState(except: Set<String> = emptySet()) {
        val ps = org.json.JSONArray()
        for (p in st.participants.values) ps.put(p.toJson())
        io.emit("room:state", JSONObject().put("meta", st.meta.toJson()).put("participants", ps), null, except)
    }

    /** Re-send the full participant list (used after a host hand-over). */
    fun reconcile() = broadcastState()

    private fun setP(sid: String, f: (Participant) -> Participant) {
        val p = st.participants[sid] ?: return
        val m = LinkedHashMap(st.participants)
        m[sid] = f(p)
        st.participants = m
    }

    /* ======================================================================
     * Membership
     * ==================================================================== */

    fun handleJoin(sid: String, uid: String, username: String, displayName: String, avatar: Avatar, via: String): JSONObject {
        if (st.meta.isBanned(uid)) return err("You were removed from this room.")
        val existing = st.participants.values.firstOrNull { it.uid == uid }
        if (existing != null && existing.sid != sid) {
            // same person re-joining from a new session: drop the stale entry
            handleLeave(existing.sid)
        }
        if (st.participants.size >= 24) return err("Room is full (24 max).")
        val isOwner = uid == st.meta.ownerUid
        if (st.meta.locked && !isOwner) return err("This room is locked by the host.")
        val hasHost = st.participants.values.any { it.role == "host" }
        val role = if (isOwner || !hasHost) "host" else "listener"
        val p = Participant(
            sid = sid, uid = uid, username = username, displayName = displayName, avatar = avatar,
            role = role, muted = true, joinedAt = nowMs(), via = via
        )
        val m = LinkedHashMap(st.participants)
        m[sid] = p
        st.participants = m
        io.noteJoin(m.size)
        io.bump("joins")
        val snapshot = st.snapshot()
        broadcastState(except = setOf(sid))
        io.emit("room:peer-joined", JSONObject().put("participant", p.toJson()), null, setOf(sid))
        return ok("you" to jsonOf("sid" to sid, "role" to role), "snapshot" to snapshot)
    }

    fun handleLeave(sid: String) {
        val p = st.participants[sid] ?: return
        val m = LinkedHashMap(st.participants)
        m.remove(sid)
        st.participants = m
        if (p.sharing != null) io.emit("screen:stopped", JSONObject().put("sid", sid))
        if (m.isEmpty()) return
        if (p.role == "host" && m.values.none { it.role == "host" }) {
            val next = m.values.sortedWith(compareBy<Participant> { it.joinedAt }.thenBy { it.sid }).first()
            m[next.sid] = next.copy(role = "host")
            st.participants = m
            io.emit("room:role", jsonOf("role" to "host", "reason" to "The host left — you are now hosting."), next.sid)
        }
        broadcastState()
        io.emit("room:peer-left", JSONObject().put("sid", sid))
    }

    /* ======================================================================
     * Requests
     * ==================================================================== */

    fun handle(from: String, kind: String, d: JSONObject): JSONObject {
        val me = st.participants[from] ?: return err("Join a room first.")
        return when (kind) {
            "room:mute" -> {
                val muted = d.bool("muted")
                setP(from) { it.copy(muted = muted) }
                io.emit("room:mute", jsonOf("sid" to from, "muted" to muted))
                ok()
            }
            "room:hand" -> {
                setP(from) { it.copy(hand = d.bool("raised")) }
                broadcastState()
                ok()
            }
            "room:reaction" -> {
                val emoji = d.str("emoji")
                if (emoji !in REACTIONS) return err("Unknown reaction.")
                if (!limiter.allow("react:$from", 20, 10_000)) return err("Slow down a little.")
                io.emit("room:reaction", jsonOf("sid" to from, "emoji" to emoji, "displayName" to me.displayName))
                ok()
            }
            "room:promote" -> promote(from, d.str("sid"))
            "room:demote" -> demote(from, d.str("sid"))
            "room:mute-user" -> {
                if (!isMod(from)) return err("Only the host can do that.")
                val t = st.participants[d.str("sid")] ?: return err("Not in the room.")
                setP(t.sid) { it.copy(muted = true) }
                io.emit("room:force-muted", JSONObject(), t.sid)
                io.emit("room:mute", jsonOf("sid" to t.sid, "muted" to true))
                io.audit("room.mute", "@${t.username}", "microphone muted by host")
                ok()
            }
            "room:kick" -> kick(from, d.str("sid"), 0L, false)
            "room:ban" -> kick(from, d.str("sid"), d.lng("ms"), true)
            "room:unban" -> {
                if (!isMod(from)) return err("Only the host can do that.")
                val uid = d.str("uid")
                val b = HashMap(st.meta.bans)
                b.remove(uid)
                st.meta = st.meta.copy(bans = b)
                broadcastState()
                io.audit("room.unban", uid, "ban lifted")
                ok()
            }
            "room:lock" -> {
                if (!isMod(from)) return err("Only the host can do that.")
                st.meta = st.meta.copy(locked = d.bool("locked"))
                broadcastState()
                io.audit(if (st.meta.locked) "room.lock" else "room.unlock", st.meta.title, "")
                ok()
            }
            "room:edit" -> {
                if (!isMod(from)) return err("Only the host can do that.")
                val title = d.str("title").trim().take(60)
                if (title.length < 3) return err("Give the room a title (3+ characters).")
                val tags = (d.arr("tags")?.strings() ?: emptyList()).map { it.trim().lowercase().take(20) }
                    .filter { it.isNotEmpty() }.distinct().take(6)
                st.meta = st.meta.copy(title = title, topic = d.str("topic").trim().take(140), tags = tags)
                broadcastState()
                ok()
            }
            "room:end" -> {
                if (!isMod(from)) return err("Only the host can do that.")
                io.audit("room.end", st.meta.title, "ended for everyone")
                io.emit("room:ended", jsonOf("reason" to "The host ended this room."), null, setOf(from))
                ok()
            }
            "chat:send" -> chatSend(from, me, d.str("text"))
            "chat:react" -> chatReact(from, me, d)
            "chat:delete" -> chatDelete(from, me, d.str("id"))
            "screen:start" -> screenStart(from, me, d.obj("quality"))
            "screen:quality" -> {
                if (me.sharing == null) return ok()
                val q = cleanQuality(d.obj("quality"))
                setP(from) { it.copy(sharing = it.sharing?.copy(res = q.first, fps = q.second)) }
                broadcastState()
                ok()
            }
            "screen:stop" -> {
                if (me.sharing != null) {
                    setP(from) { it.copy(sharing = null) }
                    broadcastState()
                    io.emit("screen:stopped", JSONObject().put("sid", from))
                }
                ok()
            }
            "report:file" -> {
                val r = Report(
                    id = Ids.id("rep_"), reporterUid = me.uid, reporterName = me.displayName,
                    targetType = d.str("targetType"), targetId = d.str("targetId"), targetLabel = d.str("targetLabel"),
                    roomId = st.meta.id, roomTitle = st.meta.title, category = d.str("category", "other"),
                    details = d.str("details").take(1000), excerpt = d.str("excerpt").take(300)
                )
                io.storeReport(r)
                ok()
            }
            else -> when {
                kind.startsWith("watch:") -> watch(from, me, kind, d)
                kind.startsWith("board:") -> board(from, me, kind, d)
                kind.startsWith("browse:") -> browse(from, me, kind, d)
                else -> err("Unknown request.")
            }
        }
    }

    /* ---------------- stage ---------------- */

    private fun promote(from: String, sid: String): JSONObject {
        if (!isMod(from)) return err("Only the host can do that.")
        val t = st.participants[sid] ?: return err("Not in the room.")
        val speakers = st.participants.values.count { canSpeak(it.role) }
        if (speakers >= st.meta.maxSpeakers) return err("All speaker seats are taken.")
        if (t.role == "listener") {
            setP(sid) { it.copy(role = "speaker", hand = false) }
            io.emit("room:role", jsonOf("role" to "speaker", "reason" to "You were invited to speak."), sid)
            broadcastState()
        }
        return ok()
    }

    private fun demote(from: String, sid: String): JSONObject {
        if (!isMod(from)) return err("Only the host can do that.")
        val t = st.participants[sid] ?: return err("Not in the room.")
        if (t.role != "speaker") return ok()
        val wasSharing = t.sharing != null
        setP(sid) { it.copy(role = "listener", muted = true, sharing = null) }
        if (wasSharing) io.emit("screen:stopped", JSONObject().put("sid", sid))
        io.emit("room:role", jsonOf("role" to "listener", "reason" to "You were moved to the audience."), sid)
        broadcastState()
        return ok()
    }

    private fun kick(from: String, sid: String, banMs: Long, ban: Boolean): JSONObject {
        if (!isMod(from)) return err("Only the host can do that.")
        val t = st.participants[sid] ?: return err("Not in the room.")
        if (sid == from) return err("You can't remove yourself.")
        if (ban) {
            val until = if (banMs <= 0L) 0L else nowMs() + banMs
            val b = HashMap(st.meta.bans)
            b[t.uid] = until
            st.meta = st.meta.copy(bans = b)
            io.audit(
                "room.ban", "@${t.username}",
                if (until == 0L) "permanent" else "until " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(until))
            )
        } else {
            io.audit("room.kick", "@${t.username}", "removed from room")
        }
        io.emit(
            "room:kicked",
            jsonOf("reason" to (if (ban) "You were banned from this room by the host." else "You were removed from the room by the host.")),
            sid
        )
        handleLeave(sid)
        return ok()
    }

    /* ---------------- chat ---------------- */

    private fun chatSend(from: String, me: Participant, text0: String): JSONObject {
        if (!limiter.allow("chat:" + me.uid, 15, 10_000)) return err("Slow down a little.")
        val t = text0.trim().take(500)
        if (t.isEmpty()) return err("Empty message.")
        val msg = ChatMsg(Ids.id("m_"), me.uid, me.username, me.displayName, me.avatar, t, nowMs())
        io.emit("chat:new", msg.toJson())
        io.bump("msgs")
        return ok("id" to msg.id)
    }

    private fun chatReact(from: String, me: Participant, d: JSONObject): JSONObject {
        val emoji = d.str("emoji")
        if (emoji !in REACTIONS) return ok()
        val m = st.chat.firstOrNull { it.id == d.str("id") } ?: return ok()
        if (m.deleted) return ok()
        val r = LinkedHashMap<String, List<String>>(m.reactions)
        val cur = (r[emoji] ?: emptyList()).toMutableList()
        if (cur.contains(me.uid)) cur.remove(me.uid) else cur.add(me.uid)
        if (cur.isEmpty()) r.remove(emoji) else r[emoji] = cur
        val rj = JSONObject()
        for ((k, v) in r) rj.put(k, jsonArrayOf(v))
        io.emit("chat:update", jsonOf("id" to m.id, "deleted" to m.deleted, "reactions" to rj))
        return ok()
    }

    private fun chatDelete(from: String, me: Participant, id: String): JSONObject {
        val m = st.chat.firstOrNull { it.id == id } ?: return ok()
        val mine = m.uid == me.uid
        if (!mine && !isMod(from)) return err("Only the author or the host can delete this.")
        val rj = JSONObject()
        for ((k, v) in m.reactions) rj.put(k, jsonArrayOf(v))
        io.emit("chat:update", jsonOf("id" to id, "deleted" to true, "reactions" to rj))
        if (!mine) io.audit("message.delete", "@${m.username}", "message removed in-room")
        return ok()
    }

    /* ---------------- screen share ---------------- */

    private fun cleanQuality(q: JSONObject?): Pair<Int, Int> {
        val res = q?.int("res", 720) ?: 720
        val fps = q?.int("fps", 30) ?: 30
        return Pair(if (res in listOf(240, 480, 720, 1080)) res else 720, if (fps in listOf(5, 15, 30, 60)) fps else 30)
    }

    private fun screenStart(from: String, me: Participant, q: JSONObject?): JSONObject {
        if (!canSpeak(me.role)) return err("Take the stage to share your screen.")
        val other = st.participants.values.firstOrNull { it.sharing != null && it.sid != from }
        if (other != null) return err("${other.displayName} is already sharing.")
        val cq = cleanQuality(q)
        val sh = Sharing(cq.first, cq.second, nowMs())
        setP(from) { it.copy(sharing = sh) }
        broadcastState()
        io.emit("screen:started", jsonOf("sid" to from, "displayName" to me.displayName, "quality" to sh.toJson()))
        return ok()
    }

    /* ---------------- Watch Together ---------------- */

    private val ytId = Regex("^[A-Za-z0-9_-]{11}$")

    private fun watchCanControl(me: Participant): Boolean {
        val w = st.watch
        return when (w.perm) {
            "everyone" -> true
            "mods" -> me.role == "host" || w.mods.contains(me.uid)
            else -> me.role == "host"
        }
    }

    private fun denyWatch(): JSONObject =
        err(if (st.watch.perm == "mods") "Only playback moderators can control this video." else "Playback is currently controlled by the room host.")

    private fun cleanVideo(v: JSONObject?): WatchVideo? {
        if (v == null || !ytId.matches(v.str("id"))) return null
        return WatchVideo(
            v.str("id"), v.str("title", "YouTube video").take(200), v.str("author").take(120),
            v.int("duration").coerceIn(0, 86400)
        )
    }

    private fun loadVideo(v: WatchVideo, me: Participant) {
        st.watch = st.watch.copy(
            video = v, playing = true, position = 0.0, anchorMs = nowMs(), rate = 1.0, controllerName = me.displayName
        )
        io.emit("watch:load", st.watch.toSnapshot())
    }

    private fun watch(from: String, me: Participant, kind: String, d: JSONObject): JSONObject {
        val w = st.watch
        when (kind) {
            "watch:start" -> {
                if (!watchCanControl(me)) return denyWatch()
                val v = cleanVideo(d.obj("video")) ?: return err("Invalid YouTube link.")
                loadVideo(v, me)
                return ok()
            }
            "watch:control" -> {
                val v = w.video ?: return err("Nothing is playing.")
                if (!watchCanControl(me)) return denyWatch()
                val nowPos = w.expectedPosition()
                val dur = v.duration
                var nw = w
                when (d.str("action")) {
                    "play" -> nw = w.copy(position = nowPos, playing = true)
                    "pause" -> nw = w.copy(position = nowPos, playing = false)
                    "seek" -> {
                        val target = d.dbl("value")
                        nw = w.copy(position = maxOf(0.0, if (dur > 0) minOf(target, dur.toDouble()) else target))
                    }
                    "rate" -> nw = w.copy(position = nowPos, rate = d.dbl("value", 1.0).coerceIn(0.25, 2.0))
                    "restart" -> nw = w.copy(position = 0.0, playing = true)
                    "skip" -> {
                        if (w.queue.isEmpty()) {
                            st.watch = w.copy(playing = false, position = nowPos, anchorMs = nowMs())
                            io.emit("watch:state", st.watch.toSnapshot().put("controller", me.displayName))
                            return ok()
                        }
                        val next = w.queue.first()
                        st.watch = w.copy(queue = w.queue.drop(1))
                        loadVideo(next, me)
                        return ok()
                    }
                    else -> return err("Unknown action.")
                }
                st.watch = nw.copy(anchorMs = nowMs(), controllerName = me.displayName)
                io.emit("watch:state", st.watch.toSnapshot())
                return ok()
            }
            "watch:meta" -> {
                val v = w.video
                val dur = d.int("duration")
                if (v != null && dur > 0 && v.duration == 0) {
                    io.emit("watch:meta", jsonOf("duration" to minOf(86400, dur)))
                }
                return ok()
            }
            "watch:queue" -> {
                if (!watchCanControl(me)) return denyWatch()
                when (d.str("action")) {
                    "add" -> {
                        val v = cleanVideo(d.obj("item")) ?: return err("Invalid YouTube link.")
                        if (w.queue.size >= 50) return err("Queue is full.")
                        val item = v.copy(qid = Ids.id("q_"), by = me.displayName)
                        if (w.video == null) {
                            loadVideo(item, me)
                            return ok()
                        }
                        st.watch = w.copy(queue = w.queue + item)
                    }
                    "remove" -> {
                        val qid = d.obj("item")?.str("qid") ?: ""
                        var i = -1
                        if (qid.isNotEmpty()) i = w.queue.indexOfFirst { it.qid == qid }
                        else if (d.has("index")) {
                            val idx = d.int("index", -1)
                            if (idx in w.queue.indices) i = idx
                        }
                        if (i < 0) return err("That item is no longer in the queue.")
                        st.watch = w.copy(queue = w.queue.filterIndexed { n, _ -> n != i })
                    }
                    "clear" -> st.watch = w.copy(queue = emptyList())
                }
                io.emit("watch:queue", jsonOf("queue" to org.json.JSONArray().also { a -> st.watch.queue.forEach { a.put(it.toJson()) } }))
                return ok()
            }
            "watch:perm" -> {
                if (me.role != "host") return err("Only the host can change playback permissions.")
                val mode = d.str("mode")
                if (mode !in listOf("host", "mods", "everyone")) return err("Bad mode.")
                io.emit("watch:perm", jsonOf("perm" to mode))
                return ok()
            }
            "watch:mod" -> {
                if (me.role != "host") return err("Only the host can manage playback moderators.")
                val uid = d.str("uid")
                val mods = if (d.bool("on")) (if (w.mods.contains(uid)) w.mods else w.mods + uid) else w.mods.filter { it != uid }
                io.emit("watch:mods", jsonOf("mods" to jsonArrayOf(mods)))
                return ok()
            }
            "watch:end" -> {
                if (me.role != "host") return err("Only the host can end Watch Together.")
                io.emit("watch:ended", JSONObject())
                return ok()
            }
        }
        return err("Unknown request.")
    }

    /* ---------------- shared blackboard ---------------- */

    private fun board(from: String, me: Participant, kind: String, d: JSONObject): JSONObject {
        val b = st.board
        when (kind) {
            "board:add" -> {
                val raw = d.obj("obj") ?: return err("Invalid object.")
                if (raw.str("type") !in BOARD_TYPES) return err("Invalid object.")
                val len = raw.toString().length
                if (len > 900_000) return err("Object too large (max ~600 KB image).")
                val o = BoardObj.from(raw) ?: return err("Invalid object.")
                if ((o.points?.size ?: 0) > 12_000) return err("Stroke too long.")
                if (b.objects.size >= 4000) return err("Board is full (4000 objects).")
                if (b.bytes() + len > 8_000_000) return err("Board storage limit reached.")
                if (o.id.isEmpty() || b.objects.containsKey(o.id)) return err("Duplicate id.")
                o.z = b.z
                o.by = me.uid
                io.emit("board:add", JSONObject().put("obj", o.toJson()), null, setOf(from))
                return ok("id" to o.id, "z" to o.z)
            }
            "board:update" -> {
                val id = d.str("id")
                val patch = d.obj("patch") ?: return err("No patch.")
                val o = b.objects[id] ?: return err("Object not found.")
                val clean = JSONObject(patch.toString())
                clean.remove("id"); clean.remove("type"); clean.remove("src"); clean.remove("by")
                if (clean.has("text")) clean.put("text", clean.str("text").take(2000))
                if (clean.has("z")) clean.put("z", clean.int("z").coerceIn(0, 1_000_000_000))
                o.applyPatch(clean)
                if (o.z >= b.z) b.z = o.z + 1
                b.touch()
                io.emit("board:update", jsonOf("id" to id, "patch" to clean), null, setOf(from))
                return ok()
            }
            "board:delete" -> {
                val ids = (d.arr("ids")?.strings() ?: emptyList()).take(500)
                val removed = ids.filter { b.objects.containsKey(it) }
                if (removed.isNotEmpty()) {
                    b.remove(removed)
                    io.emit("board:delete", jsonOf("ids" to jsonArrayOf(removed)), null, setOf(from))
                }
                return ok("removed" to jsonArrayOf(removed))
            }
            "board:clear" -> {
                if (me.role != "host") return err("Only the host can clear the board.")
                io.emit("board:cleared", JSONObject())
                io.audit("board.clear", st.meta.title, "blackboard cleared")
                return ok()
            }
        }
        return err("Unknown request.")
    }

    /* ---------------- shared browser ---------------- */

    private fun browseCan(me: Participant): Boolean {
        val b = st.browse
        return when (b.perm) {
            "everyone" -> true
            "mods" -> me.role == "host" || b.mods.contains(me.uid)
            else -> me.role == "host"
        }
    }

    private fun browseDeny(): JSONObject =
        err(if (st.browse.perm == "mods") "Only navigators can browse for the room." else "The room host is driving this session.")
            .put("needsControl", true)

    private fun browse(from: String, me: Participant, kind: String, d: JSONObject): JSONObject {
        val b = st.browse
        when (kind) {
            "browse:presence" -> {
                val open = d.bool("open")
                if (me.browsing != open) {
                    setP(from) { it.copy(browsing = open) }
                    broadcastState()
                }
                return ok()
            }
            "browse:navigate" -> {
                if (!browseCan(me)) return browseDeny()
                if (!limiter.allow("nav:$from", 30, 10_000)) return err("Slow down a little.")
                val url = d.str("url")
                if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(url) || url.length > 2048) {
                    return err("That address could not be opened.")
                }
                var hist = b.history.take(b.idx + 1)
                if (hist.lastOrNull() != url) hist = hist + url
                if (hist.size > 60) hist = hist.takeLast(60)
                val nb = b.copy(
                    url = url, title = d.str("title").take(200), host = d.str("host").take(120),
                    history = hist, idx = hist.size - 1, navigatorUid = me.uid, navigatorName = me.displayName,
                    nonce = b.nonce + 1
                )
                io.emit("browse:load", nb.toJson())
                return ok()
            }
            "browse:step" -> {
                if (!browseCan(me)) return browseDeny()
                val next = b.idx + (if (d.str("dir") == "forward") 1 else -1)
                if (next < 0 || next >= b.history.size) return err("No more history.")
                val url = b.history[next]
                val host = try {
                    java.net.URI(url).host ?: ""
                } catch (e: Exception) {
                    ""
                }
                val nb = b.copy(url = url, host = host, title = host, idx = next, navigatorUid = me.uid, navigatorName = me.displayName, nonce = b.nonce + 1)
                io.emit("browse:load", nb.toJson())
                return ok()
            }
            "browse:reload" -> {
                if (!browseCan(me)) return browseDeny()
                if (b.url == null) return err("Nothing to reload.")
                io.emit("browse:reload", b.copy(nonce = b.nonce + 1, navigatorUid = me.uid, navigatorName = me.displayName).toJson())
                return ok()
            }
            "browse:title" -> {
                if (b.url != null && d.str("url") == b.url && browseCan(me)) {
                    io.emit("browse:state", b.copy(title = d.str("title").take(200)).toJson())
                }
                return ok()
            }
            "browse:home" -> {
                if (!browseCan(me)) return browseDeny()
                io.emit("browse:load", b.copy(url = null, title = "", host = "", history = emptyList(), idx = -1, nonce = b.nonce + 1).toJson())
                return ok()
            }
            "browse:perm" -> {
                if (me.role != "host") return err("Only the host can change browsing permissions.")
                val mode = d.str("mode")
                if (mode !in listOf("host", "mods", "everyone")) return err("Bad mode.")
                io.emit("browse:state", b.copy(perm = mode, requests = if (mode != "host") emptyList() else b.requests).toJson())
                return ok()
            }
            "browse:control" -> return browseControl(from, me, d)
            "browse:end" -> {
                if (me.role != "host") return err("Only the host can end the browsing session.")
                io.emit("browse:ended", BrowseState(perm = b.perm, mods = b.mods, nonce = b.nonce + 1).toJson())
                return ok()
            }
        }
        return err("Unknown request.")
    }

    private fun browseControl(from: String, me: Participant, d: JSONObject): JSONObject {
        val b = st.browse
        val uid = d.str("uid")
        when (d.str("action")) {
            "request" -> {
                if (browseCan(me)) return ok("already" to true)
                if (!limiter.allow("navreq:" + me.uid, 5, 60_000)) return err("You already asked — give it a moment.")
                val rq = if (b.requests.any { it.uid == me.uid }) b.requests else b.requests + NavRequest(me.uid, me.displayName, nowMs())
                io.emit("browse:state", b.copy(requests = rq).toJson())
                for (h in st.participants.values.filter { it.role == "host" }) {
                    io.emit("notify", jsonOf("text" to "${me.displayName} asked to control the browser."), h.sid)
                }
                return ok()
            }
            "grant" -> {
                if (me.role != "host") return err("Only the host can hand over control.")
                if (uid.isEmpty()) return err("Missing person.")
                val mods = if (b.mods.contains(uid)) b.mods else b.mods + uid
                io.emit(
                    "browse:state",
                    b.copy(mods = mods, perm = if (b.perm == "host") "mods" else b.perm, requests = b.requests.filter { it.uid != uid }).toJson()
                )
                for (p in st.participants.values.filter { it.uid == uid }) {
                    io.emit("notify", jsonOf("text" to "You can now navigate the shared browser."), p.sid)
                }
                return ok()
            }
            "revoke" -> {
                if (me.role != "host") return err("Only the host can change navigators.")
                io.emit("browse:state", b.copy(mods = b.mods.filter { it != uid }, requests = b.requests.filter { it.uid != uid }).toJson())
                return ok()
            }
            "release" -> {
                io.emit("browse:state", b.copy(mods = b.mods.filter { it != me.uid }, requests = b.requests.filter { it.uid != me.uid }).toJson())
                return ok()
            }
            "dismiss" -> {
                if (me.role != "host") return err("Only the host can do that.")
                io.emit("browse:state", b.copy(requests = b.requests.filter { it.uid != uid }).toJson())
                return ok()
            }
        }
        return err("Unknown action.")
    }
}
