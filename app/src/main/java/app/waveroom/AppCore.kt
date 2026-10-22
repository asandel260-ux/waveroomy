package app.waveroom

import android.app.Application
import android.content.Intent
import android.net.Uri
import app.waveroom.core.Contact
import app.waveroom.core.Docs
import app.waveroom.core.DmStore
import app.waveroom.core.GroupStore
import app.waveroom.core.Media
import app.waveroom.core.ModStore
import app.waveroom.core.ProfileStore
import app.waveroom.core.RoomsStore
import app.waveroom.core.SettingsStore
import app.waveroom.core.SocialStore
import app.waveroom.core.jsonOf
import app.waveroom.core.later
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.str
import app.waveroom.media.AudioController
import app.waveroom.media.RoomService
import app.waveroom.media.RtcEngine
import app.waveroom.media.ScreenShare
import app.waveroom.media.Sfx
import app.waveroom.net.LanService
import app.waveroom.net.Mesh
import app.waveroom.net.MeshHandler
import app.waveroom.net.PeerLink
import app.waveroom.net.Sdp
import app.waveroom.room.DmHub
import app.waveroom.room.RoomSession
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import app.waveroom.core.arr
import app.waveroom.core.strings

/** Things only an Activity can do. Implemented by MainActivity. */
interface Platform {
    fun requestMic(cb: (Boolean) -> Unit)
    fun requestCamera(cb: (Boolean) -> Unit)
    fun requestNotifications()
    fun requestProjection(cb: (Intent?) -> Unit)
    fun pickImage(cb: (Uri?) -> Unit)
    fun pickFile(cb: (Uri?) -> Unit)
    fun saveFile(name: String, mime: String, bytes: ByteArray, cb: (Boolean) -> Unit)
    fun shareText(text: String)
    fun shareFile(name: String, mime: String, bytes: ByteArray)
    fun copy(label: String, text: String)
    fun openUrl(url: String)
}

sealed class CodeResult {
    /** We accepted an invite: show [code] to the inviter so they can finish the link. */
    data class Answer(val code: String, val from: String, val title: String, val purpose: String, val auto: Boolean = false) : CodeResult()
    /** We applied an answer to one of our invites: the link is coming up. */
    object Applied : CodeResult()
    data class Error(val msg: String) : CodeResult()
}

object AppCore : MeshHandler, app.waveroom.net.LanApp {
    lateinit var app: Application
        private set
    private var inited = false

    val profile = ProfileStore()
    val settings = SettingsStore()
    val social = SocialStore()
    val dms = DmStore()
    val groups = GroupStore()
    val rooms = RoomsStore()
    val mod = ModStore()

    lateinit var rtc: RtcEngine
    lateinit var mesh: Mesh
    lateinit var lan: LanService
    lateinit var session: RoomSession
    lateinit var audio: AudioController
    lateinit var sfx: Sfx
    lateinit var screen: ScreenShare
    lateinit var dmHub: DmHub
    val rendezvous get() = Rendezvous
    private val lanTries = HashMap<String, Long>()

    var platform: Platform? = null

    val toasts = MutableSharedFlow<String>(extraBufferCapacity = 32)
    /** uids we currently hold a verified live link to */
    val connected = MutableStateFlow<Set<String>>(emptySet())
    val pendingCode = MutableStateFlow<String?>(null)
    val rtcError = MutableStateFlow<String?>(null)

    fun init(app: Application) {
        if (inited) return
        inited = true
        this.app = app
        Docs.init(app)
        Media.init(app)
        settings.load(); profile.load(); social.load(); dms.load(); groups.load(); rooms.load(); mod.load()
        try {
            rtc = RtcEngine(app, settings)
            rtc.init()
        } catch (t: Throwable) {
            rtcError.value = "Audio engine failed to start: ${t.message}"
            throw t
        }
        mesh = Mesh(rtc, profile, social)
        mesh.handler = this
        mesh.start()
        lan = LanService(app) { mesh.mySid }
        mesh.lanHint = { lan.addresses() }
        lan.startServer(this)
        session = RoomSession(this)
        audio = AudioController(app, this)
        sfx = Sfx(settings)
        screen = ScreenShare(this)
        dmHub = DmHub(this)
        if (settings.state.value.nearby) lan.startListening()
        Rendezvous.start()
        later(3000) { lanReconnectTick() }
    }

    /** Saved friends who show up on the same network are reconnected automatically. */
    private fun lanReconnectTick() {
        try {
            val st = settings.state.value
            val me = profile.uid
            if (st.nearby && profile.profile.value != null) {
                for (p in lan.people.value) {
                    val c = social.contact(p.uid) ?: continue
                    if (c.pair.isEmpty() || social.blocked.value.contains(p.uid) || connected.value.contains(p.uid)) continue
                    val last = lanTries[p.uid] ?: 0L
                    if (nowMs() - last < 25_000) continue
                    // lower id goes first; the other waits a moment in case the first try already works
                    if (me > p.uid && nowMs() - last < 33_000 && last != 0L) continue
                    lanTries[p.uid] = nowMs()
                    if (me > p.uid) {
                        later(6000) { if (!connected.value.contains(p.uid)) connectNearby(p) { } }
                    } else connectNearby(p) { }
                }
            }
        } catch (e: Exception) {
        }
        later(4000) { lanReconnectTick() }
    }

    fun toast(t: String) {
        toasts.tryEmit(t)
    }

    /* ---------------- foreground service ---------------- */

    fun startService(projection: Boolean = false) {
        try {
            RoomService.start(app, projection)
        } catch (e: Exception) {
        }
    }

    fun stopService() {
        try {
            RoomService.stop(app)
        } catch (e: Exception) {
        }
    }

    /* ---------------- first contact: codes ---------------- */

    fun createInviteCode(purpose: String, cb: (inviteId: String, code: String?) -> Unit) {
        if (purpose.startsWith("group:")) {
            val g = groups.get(purpose.removePrefix("group:"))
            if (g == null || g.left || !g.canAdd(profile.uid)) return cb("", null)
            mesh.createInvite("dm", purpose, jsonOf("t" to g.name, "h" to "group"), cb)
        } else if (purpose == "room") {
            val s = session.room
            if (s == null || !session.live) return cb("", null)
            mesh.createInvite("room", s.meta.id, session.previewJson(), cb)
        } else {
            val p = profile.profile.value
            mesh.createInvite("dm", "", jsonOf("t" to (p?.displayName ?: ""), "h" to (p?.username ?: "")), cb)
        }
    }

    fun previewOfCode(text: String): JSONObject? = Sdp.unpack(text)?.obj("v")

    fun handleCode(text: String, autoDeliver: Boolean = true, cb: (CodeResult) -> Unit) {
        val j = Sdp.unpack(text) ?: return cb(CodeResult.Error("That doesn't look like a HoMit code."))
        when (j.str("k")) {
            "o" -> {
                val purpose = j.str("p", "room")
                if (purpose == "room" && session.phase.value != "idle") {
                    return cb(CodeResult.Error("Leave your current room before joining another."))
                }
                val uid = j.str("u")
                if (social.blocked.value.contains(uid)) return cb(CodeResult.Error("You blocked this person."))
                mesh.acceptInvite(j) { answer, remoteSid ->
                    if (answer == null) {
                        if (remoteSid.isNotEmpty() && mesh.link(remoteSid)?.authed == true) cb(CodeResult.Error("You are already connected to this person."))
                        else cb(CodeResult.Error("Could not prepare a connection. Try again."))
                    } else {
                        if (purpose == "room") session.expectJoin = remoteSid
                        val res = CodeResult.Answer(answer, remoteSid, j.obj("v")?.str("t") ?: j.str("n"), purpose)
                        val addrs = j.arr("l")?.strings() ?: emptyList()
                        if (autoDeliver && addrs.isNotEmpty()) lan.deliverAnswer(addrs, answer) { ok -> cb(res.copy(auto = ok)) }
                        else cb(res)
                    }
                }
            }
            "a" -> mesh.applyAnswer(j) { ok ->
                cb(if (ok) CodeResult.Applied else CodeResult.Error("That answer doesn't match an open invite. It may have expired — create a new invite."))
            }
            else -> cb(CodeResult.Error("Unknown code."))
        }
    }

    /* ---------------- social ---------------- */

    fun follow(uid: String, on: Boolean) {
        social.follow(uid, on)
        dmHub.sendFollow(uid, on)
    }

    fun block(uid: String, on: Boolean) {
        social.block(uid, on)
        if (on) {
            dmHub.sendFollow(uid, false)
            mesh.linkForUid(uid)?.let { mesh.closeLink(it.remoteSid, "blocked") }
        }
    }

    fun contactName(uid: String): String = social.contact(uid)?.displayName ?: "Someone"

    fun wipeAll() {
        try {
            session.leave()
        } catch (e: Exception) {
        }
        Docs.wipeAll()
        dms.wipe(); mod.wipe(); groups.wipe(); Media.wipe()
        social.contacts.value = emptyMap(); social.following.value = emptySet()
        social.followers.value = emptySet(); social.blocked.value = emptySet()
        rooms.rooms.value = emptyList()
        profile.load()
    }

    /* ---------------- LanApp ---------------- */

    override fun presence(): JSONObject? {
        val st = settings.state.value
        val p = profile.profile.value ?: return null
        if (!st.nearby || !st.showOnline) return null
        return jsonOf("uid" to profile.uid, "name" to p.displayName, "user" to p.username, "av" to p.avatar.toJson())
    }

    override fun admitFriend(hello: JSONObject): String? =
        if (social.blocked.value.contains(hello.str("uid"))) "Not available." else null

    override fun issueFriendInvite(cb: (code: String?) -> Unit) = createInviteCode("dm") { _, c -> cb(c) }

    override fun acceptAnswer(answer: JSONObject, cb: (Boolean) -> Unit) = mesh.applyAnswer(answer, cb)

    /** Connect to somebody found on the same Wi-Fi, no codes involved. */
    fun connectNearby(p: app.waveroom.net.NearbyPerson, done: (String?) -> Unit) {
        val me = profile.profile.value ?: return done("Profile missing")
        if (social.blocked.value.contains(p.uid)) return done("You blocked this person.")
        lan.connectPerson(
            p, jsonOf("uid" to profile.uid, "name" to me.displayName),
            accept = { code, cb -> handleCode(code, autoDeliver = false) { res -> cb((res as? CodeResult.Answer)?.code) } },
            done = done
        )
    }

    /* ---------------- MeshHandler ---------------- */

    private fun refreshConnected() {
        connected.value = mesh.authedLinks().map { it.uid }.toSet()
    }

    override fun onLinkAuthed(link: PeerLink) {
        refreshConnected()
        if (link.purpose == "dm") shareFriendSecret(link)
        dmHub.onLinkAuthed(link)
        session.onLinkAuthed(link)
        audio.attach(link)
        screen.attach(link)
        if (link.purpose == "dm" && !offerer(link)) toast("Connected to ${contactName(link.uid)}")
        else if (link.purpose == "dm") toast("${contactName(link.uid)} connected")
    }

    private fun offerer(link: PeerLink) = link.offerer

    /** The lower user id invents the shared secret for a friendship; both phones keep it. */
    private fun shareFriendSecret(link: PeerLink) {
        val uid = link.uid
        val c = social.contact(uid) ?: return
        if (profile.uid < uid) {
            var s = c.pair
            if (s.isEmpty()) {
                s = ByteArray(24).also { java.security.SecureRandom().nextBytes(it) }
                    .joinToString("") { "%02x".format(it) }
                social.setPair(uid, s)
            }
            link.send(jsonOf("t" to "pk", "s" to s).toString())
        }
        Rendezvous.kick()
    }

    override fun onLinkGone(link: PeerLink, reason: String) {
        refreshConnected()
        if (link.authed && link.purpose == "dm") Rendezvous.lost(link.uid)
        session.onLinkGone(link, reason)
    }

    override fun onLinkState(link: PeerLink, state: String) {
        if (state == "connected" && link.authed) audio.attach(link)
        refreshConnected()
    }

    override fun onMessage(fromSid: String, msg: JSONObject, link: PeerLink?) {
        when (msg.str("t")) {
            "m", "mr", "mq", "mb", "dmack", "fol", "gh", "gr", "gi", "gu", "gl", "call", "callr", "callx" -> if (link != null) dmHub.onMessage(link, msg)
            "pk" -> if (link != null && link.authed && link.purpose == "dm" && link.uid < profile.uid) {
                val s = msg.str("s")
                if (s.length in 16..128 && s.all { it.isLetterOrDigit() }) { social.setPair(link.uid, s); Rendezvous.kick() }
            }
            else -> session.onMessage(fromSid, msg, link)
        }
    }
}
