package app.waveroom

import android.app.Application
import android.content.Intent
import android.net.Uri
import app.waveroom.core.Contact
import app.waveroom.core.Docs
import app.waveroom.core.DmStore
import app.waveroom.core.ModStore
import app.waveroom.core.ProfileStore
import app.waveroom.core.RoomsStore
import app.waveroom.core.SettingsStore
import app.waveroom.core.SocialStore
import app.waveroom.core.jsonOf
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
    data class Answer(val code: String, val from: String, val title: String, val purpose: String) : CodeResult()
    /** We applied an answer to one of our invites: the link is coming up. */
    object Applied : CodeResult()
    data class Error(val msg: String) : CodeResult()
}

object AppCore : MeshHandler {
    lateinit var app: Application
        private set
    private var inited = false

    val profile = ProfileStore()
    val settings = SettingsStore()
    val social = SocialStore()
    val dms = DmStore()
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
        settings.load(); profile.load(); social.load(); dms.load(); rooms.load(); mod.load()
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
        session = RoomSession(this)
        audio = AudioController(app, this)
        sfx = Sfx(settings)
        screen = ScreenShare(this)
        dmHub = DmHub(this)
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
        if (purpose == "room") {
            val s = session.room
            if (s == null || !session.live) return cb("", null)
            mesh.createInvite("room", s.meta.id, session.previewJson(), cb)
        } else {
            val p = profile.profile.value
            mesh.createInvite("dm", "", jsonOf("t" to (p?.displayName ?: ""), "h" to (p?.username ?: "")), cb)
        }
    }

    fun previewOfCode(text: String): JSONObject? = Sdp.unpack(text)?.obj("v")

    fun handleCode(text: String, cb: (CodeResult) -> Unit) {
        val j = Sdp.unpack(text) ?: return cb(CodeResult.Error("That doesn't look like a Waveroom code."))
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
                        cb(CodeResult.Answer(answer, remoteSid, j.obj("v")?.str("t") ?: j.str("n"), purpose))
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
        dms.wipe(); mod.wipe()
        social.contacts.value = emptyMap(); social.following.value = emptySet()
        social.followers.value = emptySet(); social.blocked.value = emptySet()
        rooms.rooms.value = emptyList()
        profile.load()
    }

    /* ---------------- MeshHandler ---------------- */

    private fun refreshConnected() {
        connected.value = mesh.authedLinks().map { it.uid }.toSet()
    }

    override fun onLinkAuthed(link: PeerLink) {
        refreshConnected()
        dmHub.onLinkAuthed(link)
        session.onLinkAuthed(link)
        audio.attach(link)
        screen.attach(link)
        if (link.purpose == "dm" && !offerer(link)) toast("Connected to ${contactName(link.uid)}")
        else if (link.purpose == "dm") toast("${contactName(link.uid)} connected")
    }

    private fun offerer(link: PeerLink) = link.offerer

    override fun onLinkGone(link: PeerLink, reason: String) {
        refreshConnected()
        session.onLinkGone(link, reason)
    }

    override fun onLinkState(link: PeerLink, state: String) {
        if (state == "connected" && link.authed) audio.attach(link)
        refreshConnected()
    }

    override fun onMessage(fromSid: String, msg: JSONObject, link: PeerLink?) {
        when (msg.str("t")) {
            "dm", "dmack", "fol" -> if (link != null) dmHub.onMessage(link, msg)
            else -> session.onMessage(fromSid, msg, link)
        }
    }
}
