package app.waveroom.room

import app.waveroom.AppCore
import app.waveroom.core.Avatar
import app.waveroom.core.BoardObj
import app.waveroom.core.Debounced
import app.waveroom.core.Ids
import app.waveroom.core.Participant
import app.waveroom.core.Report
import app.waveroom.core.RoomMeta
import app.waveroom.core.RoomState
import app.waveroom.core.SavedRoom
import app.waveroom.core.WatchVideo
import app.waveroom.core.arr
import app.waveroom.core.bool
import app.waveroom.core.canSpeak
import app.waveroom.core.cancelLater
import app.waveroom.core.dbl
import app.waveroom.core.int
import app.waveroom.core.jsonArrayOf
import app.waveroom.core.jsonOf
import app.waveroom.core.later
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.str
import app.waveroom.net.LanHost
import app.waveroom.net.PeerLink
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

data class ReactionFx(val sid: String, val emoji: String, val name: String, val id: Long = nowMs() + (Math.random() * 1000).toLong())
data class Cursor(val x: Float, val y: Float, val laser: Boolean, val name: String, val hue: Int, val at: Long)

/**
 * Client-side orchestration of one room. Every member runs this. Exactly one member
 * (the "engine", the earliest host) additionally runs [RoomEngine] and is the authority.
 */
class RoomSession(private val core: AppCore) : EngineIO, LanHost {

    private var st: RoomState? = null
    private var engine: RoomEngine? = null
    override val state: RoomState get() = st ?: throw IllegalStateException("no room")
    val room: RoomState? get() = st

    /** Bumped on every change of the room mirror; Compose observes it. */
    val tick = MutableStateFlow(0)
    val phase = MutableStateFlow("idle")           // idle | joining | live
    val endedReason = MutableStateFlow<String?>(null)
    val joinError = MutableStateFlow<String?>(null)
    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 32)
    val reactions = MutableSharedFlow<ReactionFx>(extraBufferCapacity = 32)
    val speaking = MutableStateFlow<Set<String>>(emptySet())
    val cursors = MutableStateFlow<Map<String, Cursor>>(emptyMap())
    val unreadChat = MutableStateFlow(0)
    val deafened = MutableStateFlow(false)

    val mySid: String get() = core.mesh.mySid
    val myUid: String get() = core.profile.uid
    val me: Participant? get() = st?.participants?.get(mySid)
    val isEngine: Boolean get() = st?.engineSid() == mySid && engine != null
    val isHost: Boolean get() = me?.role == "host"
    val live: Boolean get() = phase.value == "live"

    /** Link we expect to receive a join answer through (set when we accept an invite). */
    var expectJoin: String = ""

    private var joinTarget = ""
    private var joinTimeout: Runnable? = null
    private val waiting = HashMap<String, (JSONObject) -> Unit>()
    private val timeouts = HashMap<String, Runnable>()
    private var lastCursor = 0L
    private var reconnect = HashMap<String, Int>()
    private val saver = Debounced(2500) { st?.let { if (it.meta.ownerUid == myUid) core.rooms.saveData(it) } }

    private fun bump() {
        tick.value = tick.value + 1
        saver.poke()
    }

    private fun toast(t: String) {
        toasts.tryEmit(t)
    }

    /* ======================================================================
     * Creating / joining / leaving
     * ==================================================================== */

    fun createRoom(title: String, topic: String, tags: List<String>, unlisted: Boolean, maxSpeakers: Int, restore: JSONObject? = null, id: String? = null): Boolean {
        if (st != null) return false
        val p = core.profile.profile.value ?: return false
        val meta = RoomMeta(
            id = id ?: Ids.id("r_"), title = title.trim().take(60), topic = topic.trim().take(140), tags = tags,
            ownerUid = myUid, ownerName = p.displayName, unlisted = unlisted, maxSpeakers = maxSpeakers.coerceIn(2, 24)
        )
        val s = RoomState(meta)
        if (restore != null) {
            val keep = restore.obj("meta")
            if (keep != null) s.meta = RoomMeta.from(keep).copy(title = meta.title, topic = meta.topic, tags = meta.tags, unlisted = unlisted, maxSpeakers = meta.maxSpeakers, locked = false)
            restore.arr("chat")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { s.chat.add(app.waveroom.core.ChatMsg.from(it)) } }
            s.board.load(restore.arr("board"))
        }
        st = s
        engine = RoomEngine(this)
        val r = engine!!.handleJoin(mySid, myUid, p.username, p.displayName, p.avatar, "")
        if (!r.bool("ok")) {
            st = null; engine = null
            return false
        }
        becameLive()
        return true
    }

    /** Called by the app when an invite we accepted finished its handshake. */
    fun beginJoin(link: PeerLink) {
        if (st != null) return
        val rid = link.joinRoomId
        if (rid.isEmpty()) return
        endedReason.value = null
        joinError.value = null
        st = RoomState(RoomMeta(id = rid, title = "Joining…", ownerUid = ""))
        phase.value = "joining"
        joinTarget = link.remoteSid
        expectJoin = ""
        core.mesh.sendTo(link.remoteSid, jsonOf("t" to "rjoin", "room" to rid))
        cancelLater(joinTimeout)
        joinTimeout = later(20_000) { failJoin("The room did not answer. Check the code and try again.") }
        bump()
    }

    private fun failJoin(msg: String) {
        cancelLater(joinTimeout)
        if (phase.value != "joining") return
        joinError.value = msg
        val t = joinTarget
        resetLocal()
        if (t.isNotEmpty()) core.mesh.closeLink(t, "join failed")
    }

    private fun becameLive() {
        cancelLater(joinTimeout)
        phase.value = "live"
        val s = st ?: return
        core.mesh.hintProvider = { sid -> s.participants[sid]?.via?.takeIf { it.isNotEmpty() } }
        core.startService()
        core.audio.start()
        deafened.value = false
        if (s.meta.ownerUid == myUid) {
            val old = core.rooms.get(s.meta.id)
            core.rooms.upsert(SavedRoom(s.meta, nowMs(), (old?.totalJoins ?: 0) + 0, old?.peak ?: 0, true))
        }
        if (isEngine) startLan()
        ensureLinks()
        core.audio.refresh()
        core.sfx.play("join")
        bump()
    }

    private fun startLan() {
        val s = st ?: return
        if (!s.meta.unlisted) core.lan.startHosting(this)
    }

    /** Leave politely, telling everybody. */
    fun leave() {
        val s = st
        if (s != null && phase.value == "live") {
            core.mesh.broadcast(jsonOf("t" to "bye"))
            if (s.meta.ownerUid == myUid) core.rooms.saveData(s)
        }
        resetLocal()
    }

    /** Host ends the room for everyone, then leaves. */
    fun endRoom() {
        request("room:end", JSONObject()) { leave() }
    }

    private fun resetLocal(reason: String? = null) {
        cancelLater(joinTimeout)
        val s = st
        core.dmHub.onRoomClosed(s?.meta?.id)
        if (s != null && s.meta.ownerUid == myUid && phase.value == "live") core.rooms.saveData(s)
        core.screen.stop(false)
        core.audio.stop()
        core.lan.stopHosting()
        for (r in timeouts.values) cancelLater(r)
        timeouts.clear(); waiting.clear()
        val toClose = core.mesh.authedLinks().filter { it.purpose != "dm" }.map { it.remoteSid }
        st = null; engine = null
        phase.value = "idle"
        speaking.value = emptySet()
        cursors.value = emptyMap()
        unreadChat.value = 0
        joinTarget = ""; expectJoin = ""
        // give the last "ended / kicked" packets a moment to leave before the links close
        later(350) { for (sid in toClose) core.mesh.closeLink(sid, "left room") }
        reconnect.clear()
        core.mesh.hintProvider = null
        core.stopService()
        if (reason != null) endedReason.value = reason
        bump()
    }

    /* ======================================================================
     * Mesh events
     * ==================================================================== */

    fun onLinkAuthed(link: PeerLink) {
        reconnect.remove(link.remoteSid)
        if (link.remoteSid == expectJoin && link.purpose == "room") {
            beginJoin(link)
            return
        }
        val s = st ?: return
        if (phase.value == "live") {
            val p = s.participants[link.remoteSid]
            if (p != null) core.audio.attach(link)
        }
    }

    fun onLinkGone(link: PeerLink, reason: String) {
        val s = st ?: return
        val sid = link.remoteSid
        if (!link.authed && !(phase.value == "joining" && sid == joinTarget)) return
        if (phase.value == "joining" && sid == joinTarget) {
            failJoin("The connection to the room was lost.")
            return
        }
        if (phase.value != "live") return
        if (s.participants[sid] == null) return
        participantGone(sid, reason)
    }

    private fun participantGone(sid: String, reason: String) {
        val s = st ?: return
        val gone = s.participants[sid] ?: return
        if (isEngine) {
            engine?.handleLeave(sid)
            return
        }
        if (sid == s.engineSid()) {
            // one dropped link is not proof the host is gone: try to reach them again before taking over
            core.mesh.connectTo(sid)
            later(7000) {
                val cur = st
                if (cur != null && phase.value == "live" && cur.engineSid() == sid && cur.participants.containsKey(sid) && core.mesh.link(sid)?.authed != true) takeover(sid)
            }
            return
        }
        // someone else's link dropped; the host will announce it if they really left.
        val n = reconnect[sid] ?: 0
        if (n < 3) {
            reconnect[sid] = n + 1
            later(3000L * (n + 1)) {
                val cur = st
                if (cur != null && cur.participants.containsKey(sid) && core.mesh.link(sid)?.authed != true) core.mesh.connectTo(sid)
            }
        }
        if (gone.sharing != null) bump()
    }

    /** The acting host disappeared. Everyone computes the same successor, no messages needed. */
    private fun takeover(goneSid: String) {
        val s = st ?: return
        val m = LinkedHashMap(s.participants)
        val gone = m.remove(goneSid) ?: return
        if (m.isEmpty()) {
            s.participants = m
            leave(); return
        }
        if (m.values.none { it.role == "host" }) {
            val next = m.values.sortedWith(compareBy<Participant> { it.joinedAt }.thenBy { it.sid }).first()
            m[next.sid] = next.copy(role = "host")
        }
        s.participants = m
        speaking.value = speaking.value - goneSid
        core.audio.refresh()
        if (gone.sharing != null) toast("${gone.displayName} stopped sharing")
        if (s.engineSid() == mySid) {
            engine = RoomEngine(this)
            toast("The host left — you are hosting now")
            core.mesh.hintProvider = { sid -> s.participants[sid]?.via?.takeIf { it.isNotEmpty() } }
            startLan()
            engine?.reconcile()
        } else {
            toast("${gone.displayName} (host) left")
        }
        bump()
    }

    fun onMessage(from: String, m: JSONObject, link: PeerLink?) {
        when (m.str("t")) {
            "rjoin" -> if (link != null) onRJoin(link, m)
            "rfwd" -> if (link != null) onRFwd(from, m)
            "rjoined" -> onRJoined(from, m)
            "req" -> if (link != null) onReq(from, link, m)
            "res" -> waiting.remove(m.str("id"))?.let { cb ->
                cancelLater(timeouts.remove(m.str("id")))
                cb(m.obj("r") ?: JSONObject().put("ok", false).put("error", "No answer."))
            }
            "ev" -> onEv(from, m)
            "bye" -> if (link != null && phase.value == "live" && st?.participants?.containsKey(from) == true) participantGone(from, "left")
            "spk" -> if (link != null) {
                val s = st
                if (s != null && s.participants.containsKey(from)) {
                    speaking.value = if (m.bool("v")) speaking.value + from else speaking.value - from
                }
            }
            "cur" -> if (link != null) onCursor(from, m)
        }
    }

    /* ---- joining on the receiving side ---- */

    private fun whoOf(link: PeerLink): JSONObject? {
        val c = core.social.contact(link.uid) ?: return null
        return jsonOf(
            "sid" to link.remoteSid, "uid" to c.uid, "user" to c.username, "name" to c.displayName, "av" to c.avatar.toJson()
        )
    }

    private fun onRJoin(link: PeerLink, m: JSONObject) {
        val s = st
        if (s == null || phase.value != "live" || s.meta.id != m.str("room")) {
            core.mesh.sendTo(link.remoteSid, jsonOf("t" to "rjoined", "r" to jsonOf("ok" to false, "error" to "That room is not open on this device any more.")))
            return
        }
        val who = whoOf(link) ?: return
        if (isEngine) {
            doJoin(who, mySid)
        } else {
            val eng = s.engineSid()
            if (eng == null || !core.mesh.sendTo(eng, jsonOf("t" to "rfwd", "who" to who, "intro" to mySid))) {
                core.mesh.sendTo(link.remoteSid, jsonOf("t" to "rjoined", "r" to jsonOf("ok" to false, "error" to "The host cannot be reached right now.")))
            }
        }
    }

    private fun onRFwd(from: String, m: JSONObject) {
        val s = st ?: return
        if (!isEngine || !s.participants.containsKey(from)) return
        val who = m.obj("who") ?: return
        val wsid = who.str("sid")
        if (wsid.isEmpty() || wsid == mySid || wsid.length > 64 || who.str("uid").length > 64) return
        s.participants[wsid]?.let { if (it.uid != who.str("uid")) return }
        core.mesh.noteRoute(who.str("sid"), from)
        doJoin(who, from)
    }

    private fun doJoin(who: JSONObject, via: String) {
        val eng = engine ?: return
        val sid = who.str("sid")
        val r = try {
            eng.handleJoin(
                sid, who.str("uid").take(64), who.str("user").take(40), who.str("name").ifEmpty { who.str("user") }.take(60),
                Avatar.from(who.obj("av")), via
            )
        } catch (e: Exception) {
            jsonOf("ok" to false, "error" to "Could not join.")
        }
        core.mesh.sendTo(sid, jsonOf("t" to "rjoined", "r" to r, "engine" to mySid))
        if (r.bool("ok")) {
            core.sfx.play("join")
            toast("${who.str("name")} joined")
            core.audio.refresh()
        }
        bump()
    }

    private fun onRJoined(from: String, m: JSONObject) {
        if (phase.value != "joining") return
        if (from != joinTarget && from != m.str("engine")) return
        val r = m.obj("r") ?: return
        if (!r.bool("ok")) {
            failJoin(r.str("error", "Could not join."))
            return
        }
        val s = st ?: return
        val snap = r.obj("snapshot") ?: return
        s.loadSnapshot(snap)
        becameLive()
        // direct links to everybody else, negotiated through the mesh
        ensureLinks()
        bump()
    }

    private fun ensureLinks() {
        val s = st ?: return
        val intro = joinTarget
        for (p in s.participants.values) {
            if (p.sid == mySid) continue
            if (intro.isNotEmpty() && p.sid != intro) core.mesh.noteRoute(p.sid, intro)
            if (core.mesh.link(p.sid) == null) core.mesh.connectTo(p.sid)
        }
    }

    /* ======================================================================
     * Requests / events
     * ==================================================================== */

    fun request(kind: String, data: JSONObject, cb: ((JSONObject) -> Unit)? = null) {
        val s = st
        if (s == null) {
            cb?.invoke(JSONObject().put("ok", false).put("error", "Not in a room."))
            return
        }
        val eng = engine
        if (eng != null && isEngine) {
            val r = try {
                eng.handle(mySid, kind, data)
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "Something went wrong.")
            }
            cb?.invoke(r)
            bump()
            return
        }
        val target = s.engineSid()
        val id = Ids.id("q_")
        if (target == null || !core.mesh.sendTo(target, jsonOf("t" to "req", "id" to id, "k" to kind, "d" to data))) {
            cb?.invoke(JSONObject().put("ok", false).put("error", "The host cannot be reached right now."))
            return
        }
        waiting[id] = { r ->
            if (!r.bool("ok") && r.str("error") == "Join a room first.") {
                toast("You lost the connection to this room")
                resetLocal("You lost the connection to the room.")
            } else cb?.invoke(r)
        }
        timeouts[id] = later(9000) {
            timeouts.remove(id)
            waiting.remove(id)?.let { f -> f(JSONObject().put("ok", false).put("error", "The host did not answer in time.")) }
        }
    }

    private fun onReq(from: String, link: PeerLink, m: JSONObject) {
        val s = st ?: return
        val eng = engine
        val p = s.participants[from]
        val res: JSONObject = if (eng == null || !isEngine) {
            JSONObject().put("ok", false).put("error", "Not the host any more.")
        } else if (p == null || p.uid != link.uid) {
            JSONObject().put("ok", false).put("error", "Join a room first.")
        } else {
            try {
                eng.handle(from, m.str("k"), m.obj("d") ?: JSONObject())
            } catch (e: Exception) {
                JSONObject().put("ok", false).put("error", "Something went wrong.")
            }
        }
        core.mesh.sendTo(from, jsonOf("t" to "res", "id" to m.str("id"), "r" to res))
        bump()
    }

    override fun emit(ev: String, data: JSONObject, only: String?, except: Set<String>) {
        val s = st ?: return
        val msg = jsonOf("t" to "ev", "e" to ev, "d" to data)
        if (only != null) {
            if (only == mySid) sideEffects(ev, data) else core.mesh.sendTo(only, msg)
            return
        }
        s.apply(ev, data)
        for (p in s.participants.values.toList()) {
            if (p.sid == mySid || p.sid in except) continue
            core.mesh.sendTo(p.sid, msg)
        }
        if (mySid !in except) sideEffects(ev, data)
        bump()
    }

    private fun onEv(from: String, m: JSONObject) {
        val s = st ?: return
        if (phase.value != "live") return
        if (isEngine || from != s.engineSid()) return
        val e = m.str("e")
        val d = m.obj("d") ?: JSONObject()
        s.apply(e, d)
        sideEffects(e, d)
        bump()
    }

    private fun sideEffects(e: String, d: JSONObject) {
        val s = st ?: return
        when (e) {
            "room:state" -> {
                if (!s.participants.containsKey(mySid) && phase.value == "live") {
                    toast("You are no longer in the room")
                    resetLocal("You were removed from the room.")
                    return
                }
                speaking.value = speaking.value.filter { s.participants.containsKey(it) }.toSet()
                ensureLinks()
                core.audio.refresh()
                core.screen.refresh()
            }
            "room:peer-joined" -> {
                val p = d.obj("participant")?.let { Participant.from(it) }
                if (p != null && p.sid != mySid) {
                    toast("${p.displayName} joined")
                    core.sfx.play("join")
                    if (core.mesh.link(p.sid) == null) core.mesh.connectTo(p.sid)
                    later(1500) { core.screen.refresh() }
                }
            }
            "room:peer-left" -> {
                val sid = d.str("sid")
                speaking.value = speaking.value - sid
                cursors.value = cursors.value - sid
                core.mesh.closeLink(sid, "left")
                core.sfx.play("leave")
            }
            "room:role" -> {
                toast(d.str("reason"))
                core.audio.refresh()
                core.sfx.play("hand")
            }
            "room:force-muted" -> {
                toast("The host muted your microphone")
                core.audio.refresh()
            }
            "room:mute" -> core.audio.refresh()
            "room:kicked" -> {
                toast(d.str("reason"))
                resetLocal(d.str("reason"))
            }
            "room:ended" -> resetLocal(d.str("reason", "The room has ended."))
            "room:reaction" -> reactions.tryEmit(ReactionFx(d.str("sid"), d.str("emoji"), d.str("displayName")))
            "chat:new" -> if (d.str("uid") != myUid) {
                unreadChat.value = unreadChat.value + 1
                core.sfx.play("message")
            }
            "screen:started", "screen:stopped" -> core.audio.refresh()
            "notify" -> toast(d.str("text"))
        }
    }

    override fun audit(action: String, target: String, detail: String) {
        val s = st ?: return
        core.mod.log(action, s.meta, target, detail)
    }

    override fun storeReport(r: Report) {
        core.mod.addReport(r)
        toast("A report was filed to you")
    }

    override fun bump(counter: String) {
        core.mod.bump(counter)
    }

    override fun noteJoin(count: Int) {
        val s = st ?: return
        val sr = core.rooms.get(s.meta.id) ?: return
        core.rooms.upsert(sr.copy(meta = s.meta, lastActiveAt = nowMs(), totalJoins = sr.totalJoins + 1, peak = maxOf(sr.peak, count)))
    }

    /* ======================================================================
     * Local-network hosting
     * ==================================================================== */

    override fun beacon(): JSONObject? {
        val s = st ?: return null
        if (!isEngine || s.meta.unlisted || phase.value != "live") return null
        val host = s.participants.values.firstOrNull { it.role == "host" }?.displayName ?: s.meta.ownerName
        return jsonOf(
            "room" to s.meta.id, "title" to s.meta.title, "topic" to s.meta.topic, "tags" to jsonArrayOf(s.meta.tags),
            "host" to host, "n" to s.participants.size, "spk" to s.participants.values.count { canSpeak(it.role) },
            "lock" to s.meta.locked
        )
    }

    override fun admit(hello: JSONObject): String? {
        val s = st ?: return "This room is closed."
        if (!isEngine) return "This device is not hosting."
        val uid = hello.str("uid")
        if (s.meta.isBanned(uid)) return "You were removed from this room."
        if (s.meta.locked && uid != s.meta.ownerUid) return "This room is locked by the host."
        if (s.participants.size >= 24) return "Room is full."
        return null
    }

    override fun issueInvite(roomId: String, cb: (inviteId: String, code: String?) -> Unit) {
        val s = st ?: return cb("", null)
        core.mesh.createInvite("room", s.meta.id, previewJson(), cb)
    }

    override fun acceptAnswer(answer: JSONObject, cb: (Boolean) -> Unit) {
        core.mesh.applyAnswer(answer, cb)
    }

    fun previewJson(): JSONObject {
        val s = st ?: return JSONObject()
        return jsonOf("t" to s.meta.title, "h" to s.meta.ownerName, "n" to s.participants.size, "l" to s.meta.locked)
    }

    /* ======================================================================
     * Actions used by the UI
     * ==================================================================== */

    fun setMuted(muted: Boolean) {
        val s = st ?: return
        val p = me ?: return
        if (!canSpeak(p.role) && !muted) {
            toast("Ask the host for the stage first")
            return
        }
        s.participants[mySid] = p.copy(muted = muted)
        core.audio.refresh()
        core.sfx.play(if (muted) "mute" else "unmute")
        bump()
        request("room:mute", jsonOf("muted" to muted))
    }

    fun toggleDeafen() {
        deafened.value = !deafened.value
        core.audio.refresh()
        toast(if (deafened.value) "Deafened — you can't hear the room" else "Listening again")
    }

    fun raiseHand(on: Boolean) = request("room:hand", jsonOf("raised" to on))
    fun react(emoji: String) = request("room:reaction", jsonOf("emoji" to emoji))
    fun promote(sid: String) = request("room:promote", jsonOf("sid" to sid)) { if (!it.bool("ok")) toast(it.str("error")) }
    fun demote(sid: String) = request("room:demote", jsonOf("sid" to sid)) { if (!it.bool("ok")) toast(it.str("error")) }
    fun muteUser(sid: String) = request("room:mute-user", jsonOf("sid" to sid)) { if (!it.bool("ok")) toast(it.str("error")) }
    fun kick(sid: String) = request("room:kick", jsonOf("sid" to sid)) { if (!it.bool("ok")) toast(it.str("error")) }
    fun ban(sid: String, ms: Long) = request("room:ban", jsonOf("sid" to sid, "ms" to ms)) { if (!it.bool("ok")) toast(it.str("error")) }
    fun unban(uid: String) = request("room:unban", jsonOf("uid" to uid))
    fun setLocked(on: Boolean) = request("room:lock", jsonOf("locked" to on)) { if (!it.bool("ok")) toast(it.str("error")) }
    fun editRoom(title: String, topic: String, tags: List<String>, cb: (JSONObject) -> Unit) =
        request("room:edit", jsonOf("title" to title, "topic" to topic, "tags" to jsonArrayOf(tags)), cb)

    fun sendChat(text: String) = request("chat:send", jsonOf("text" to text)) { if (!it.bool("ok")) toast(it.str("error")) }
    fun reactChat(id: String, emoji: String) = request("chat:react", jsonOf("id" to id, "emoji" to emoji))
    fun deleteChat(id: String) = request("chat:delete", jsonOf("id" to id)) { if (!it.bool("ok")) toast(it.str("error")) }

    fun fileReport(targetType: String, targetId: String, targetLabel: String, category: String, details: String, excerpt: String, cb: (Boolean) -> Unit) {
        val s = st ?: return cb(false)
        val mine = Report(
            Ids.id("rep_"), myUid, me?.displayName ?: "", targetType, targetId, targetLabel, s.meta.id, s.meta.title,
            category, details.take(1000), "open", nowMs(), "", 0L, true, excerpt.take(300)
        )
        request("report:file", jsonOf(
            "targetType" to targetType, "targetId" to targetId, "targetLabel" to targetLabel,
            "category" to category, "details" to details, "excerpt" to excerpt
        )) {
            if (it.bool("ok")) core.mod.addReport(mine)
            cb(it.bool("ok"))
        }
    }

    /* ---- screen share ---- */

    fun announceShare(res: Int, fps: Int, cb: (JSONObject) -> Unit) =
        request("screen:start", jsonOf("quality" to jsonOf("res" to res, "fps" to fps)), cb)

    fun shareQuality(res: Int, fps: Int) = request("screen:quality", jsonOf("quality" to jsonOf("res" to res, "fps" to fps)))
    fun announceStopShare() = request("screen:stop", JSONObject())

    /* ---- Watch Together ---- */

    private fun err(r: JSONObject) {
        if (!r.bool("ok")) toast(r.str("error"))
    }

    fun watchStart(v: WatchVideo) = request("watch:start", jsonOf("video" to v.toJson()), this::err)
    fun watchControl(action: String, value: Double = 0.0) = request("watch:control", jsonOf("action" to action, "value" to value), this::err)
    fun watchMeta(duration: Int) = request("watch:meta", jsonOf("duration" to duration))
    fun watchQueueAdd(v: WatchVideo) = request("watch:queue", jsonOf("action" to "add", "item" to v.toJson()), this::err)
    fun watchQueueRemove(qid: String) = request("watch:queue", jsonOf("action" to "remove", "item" to jsonOf("qid" to qid)), this::err)
    fun watchQueueClear() = request("watch:queue", jsonOf("action" to "clear"), this::err)
    fun watchPerm(mode: String) = request("watch:perm", jsonOf("mode" to mode), this::err)
    fun watchMod(uid: String, on: Boolean) = request("watch:mod", jsonOf("uid" to uid, "on" to on), this::err)
    fun watchEnd() = request("watch:end", JSONObject(), this::err)

    /* ---- blackboard ---- */

    fun boardAdd(o: BoardObj, cb: ((Boolean) -> Unit)? = null) {
        val s = st ?: return
        o.by = myUid
        if (!isEngine) {
            o.z = s.board.z
            s.board.add(o)
        }
        request("board:add", jsonOf("obj" to o.toJson())) {
            if (!it.bool("ok")) {
                if (!isEngine) s.board.remove(listOf(o.id))
                toast(it.str("error"))
            }
            cb?.invoke(it.bool("ok"))
            bump()
        }
    }

    fun boardUpdate(id: String, patch: JSONObject) {
        val s = st ?: return
        if (!isEngine) s.board.patch(id, patch)
        request("board:update", jsonOf("id" to id, "patch" to patch)) { if (!it.bool("ok")) toast(it.str("error")) }
        bump()
    }

    fun boardDelete(ids: List<String>) {
        val s = st ?: return
        if (!isEngine) s.board.remove(ids)
        request("board:delete", jsonOf("ids" to jsonArrayOf(ids)))
        bump()
    }

    fun boardClear() = request("board:clear", JSONObject(), this::err)

    fun sendCursor(x: Float, y: Float, laser: Boolean) {
        val t = nowMs()
        if (t - lastCursor < 40) return
        lastCursor = t
        core.mesh.broadcast(jsonOf("t" to "cur", "x" to x.toDouble(), "y" to y.toDouble(), "l" to laser), false)
    }

    private fun onCursor(from: String, m: JSONObject) {
        val s = st ?: return
        val p = s.participants[from] ?: return
        val map = HashMap(cursors.value)
        if (m.has("off")) map.remove(from)
        else map[from] = Cursor(m.dbl("x").toFloat(), m.dbl("y").toFloat(), m.bool("l"), p.displayName, p.avatar.hue, nowMs())
        cursors.value = map
    }

    /** Our own speaking state, from the mic meter; shared directly with everyone. */
    fun setSpeakingSelf(on: Boolean) {
        speaking.value = if (on) speaking.value + mySid else speaking.value - mySid
        core.mesh.broadcast(jsonOf("t" to "spk", "v" to on), false)
    }

    fun hideCursor() {
        core.mesh.broadcast(jsonOf("t" to "cur", "off" to true), false)
    }

    /* ---- shared browser ---- */

    fun browsePresence(open: Boolean) = request("browse:presence", jsonOf("open" to open))
    fun browseNavigate(url: String, title: String, host: String) =
        request("browse:navigate", jsonOf("url" to url, "title" to title, "host" to host), this::browseErr)
    fun browseStep(forward: Boolean) = request("browse:step", jsonOf("dir" to (if (forward) "forward" else "back")), this::browseErr)
    fun browseReload() = request("browse:reload", JSONObject(), this::browseErr)
    fun browseHome() = request("browse:home", JSONObject(), this::browseErr)
    fun browseTitle(url: String, title: String) = request("browse:title", jsonOf("url" to url, "title" to title))
    fun browsePerm(mode: String) = request("browse:perm", jsonOf("mode" to mode), this::err)
    fun browseControl(action: String, uid: String = "") = request("browse:control", jsonOf("action" to action, "uid" to uid), this::err)
    fun browseEnd() = request("browse:end", JSONObject(), this::err)

    private fun browseErr(r: JSONObject) {
        if (!r.bool("ok")) toast(r.str("error"))
    }

    /* ---- helpers for the UI ---- */

    fun canBrowseControl(): Boolean {
        val s = st ?: return false
        val p = me ?: return false
        val b = s.browse
        return when (b.perm) {
            "everyone" -> true
            "mods" -> p.role == "host" || b.mods.contains(p.uid)
            else -> p.role == "host"
        }
    }

    fun canWatchControl(): Boolean {
        val s = st ?: return false
        val p = me ?: return false
        val w = s.watch
        return when (w.perm) {
            "everyone" -> true
            "mods" -> p.role == "host" || w.mods.contains(p.uid)
            else -> p.role == "host"
        }
    }

    fun participantByUid(uid: String): Participant? = st?.participants?.values?.firstOrNull { it.uid == uid }
    fun linkTo(sid: String): PeerLink? = core.mesh.link(sid)?.takeIf { it.authed }
}
