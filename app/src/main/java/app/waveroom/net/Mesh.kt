package app.waveroom.net

import android.util.Base64
import android.util.Log
import app.waveroom.core.Avatar
import app.waveroom.core.Contact
import app.waveroom.core.Identity
import app.waveroom.core.Ids
import app.waveroom.core.ProfileStore
import app.waveroom.core.SocialStore
import app.waveroom.core.arr
import app.waveroom.core.bool
import app.waveroom.core.cancelLater
import app.waveroom.core.int
import app.waveroom.core.jsonOf
import app.waveroom.core.jsonArrayOf
import app.waveroom.core.later
import app.waveroom.core.lng
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.parseJsonOrNull
import app.waveroom.core.str
import app.waveroom.media.RtcEngine
import org.json.JSONObject

interface MeshHandler {
    /** A link finished the signed handshake: [link.uid] is now cryptographically verified. */
    fun onLinkAuthed(link: PeerLink)
    fun onLinkGone(link: PeerLink, reason: String)
    fun onLinkState(link: PeerLink, state: String)
    /** [link] is null when the message was relayed through another member. */
    fun onMessage(fromSid: String, msg: JSONObject, link: PeerLink?)
}

/**
 * The serverless network: a set of direct WebRTC links to other people. There is
 * no signalling server. The first link to anyone is created by exchanging a code
 * (QR / text / LAN socket); every further link is negotiated *through existing
 * links* (trickle ICE relayed by a member both sides already trust).
 */
class Mesh(
    private val rtc: RtcEngine,
    private val profile: ProfileStore,
    private val social: SocialStore
) : LinkListener {

    val mySid: String = Ids.sid()
    var handler: MeshHandler? = null

    private val links = HashMap<String, PeerLink>()
    private val pending = HashMap<String, PeerLink>()          // invites we issued, by invite id
    private val route = HashMap<String, String>()              // far sid -> neighbour sid that can reach it
    private val lastRx = HashMap<String, Long>()
    private val restarts = HashMap<String, Int>()
    private val dropTimers = HashMap<String, Runnable>()
    private var beat: Runnable? = null
    var hintProvider: ((String) -> String?)? = null            // introducer sid for a participant

    /* ---- queries ---- */

    var lanHint: (() -> List<String>)? = null
    /** Plain-language status of the most recent first-contact attempt (shown when things go wrong). */
    val report = kotlinx.coroutines.flow.MutableStateFlow("")

    override fun onDiag(link: PeerLink) {
        if (link.authed && link.state == "connected") return
        report.value = link.report()
    }

    fun link(sid: String): PeerLink? = links[sid]
    fun authedLinks(): List<PeerLink> = links.values.filter { it.authed }
    fun linkForUid(uid: String): PeerLink? = links.values.firstOrNull { it.authed && it.uid == uid }
    fun isConnectedTo(uid: String): Boolean = linkForUid(uid) != null

    fun start() {
        if (beat != null) return
        beat = later(4000) { heartbeat() }
    }

    fun stop() {
        cancelLater(beat); beat = null
        for (l in links.values.toList()) l.close()
        for (l in pending.values.toList()) l.close()
        links.clear(); pending.clear(); route.clear(); lastRx.clear(); restarts.clear()
        for (r in dropTimers.values) cancelLater(r)
        dropTimers.clear()
    }

    /* ======================================================================
     * Codes (first contact)
     * ==================================================================== */

    /**
     * Issue an invite code. [preview] is shown to whoever opens it (room name etc.).
     * The callback receives the packed code once ICE gathering finished, or null.
     */
    fun createInvite(purpose: String, roomId: String, preview: JSONObject?, cb: (inviteId: String, code: String?) -> Unit) {
        val id = Ids.rand(8)
        val l = PeerLink(rtc, "pending-$id", true, false, this)
        l.purpose = purpose
        l.joinRoomId = roomId
        l.inviteId = id
        if (!l.start()) return cb(id, null)
        pending[id] = l
        l.createOffer(false) { ok ->
            if (!ok) {
                pending.remove(id); l.close(); cb(id, null)
            } else {
                l.whenGathered(6000) {
                    val sdp = l.localSdp()
                    if (sdp == null) {
                        pending.remove(id); l.close(); cb(id, null)
                    } else {
                        val j = jsonOf(
                            "k" to "o", "i" to id, "s" to mySid, "u" to profile.uid,
                            "n" to (profile.profile.value?.displayName ?: ""),
                            "p" to purpose, "r" to roomId,
                            "d" to Sdp.filterCandidates(sdp)
                        )
                        val hint = lanHint?.invoke() ?: emptyList()
                        if (hint.isNotEmpty()) j.put("l", jsonArrayOf(hint))
                        if (preview != null) j.put("v", preview)
                        cb(id, Sdp.pack(j))
                    }
                }
                // an unanswered invite expires after 10 minutes
                later(600_000) { expireInvite(id) }
            }
        }
    }

    private fun expireInvite(id: String) {
        val l = pending.remove(id) ?: return
        if (l.state != "connected") l.close() else links[l.remoteSid] = l
    }

    fun cancelInvite(id: String) {
        val l = pending.remove(id) ?: return
        if (l.remoteSid.startsWith("pending-")) l.close()
    }

    /** Peek at what an invite is for without accepting it. */
    fun previewOf(code: JSONObject): JSONObject? = code.obj("v")

    /** Accept somebody's invite; callback gets our answer code (to hand back) or null. */
    fun acceptInvite(code: JSONObject, cb: (answerCode: String?, remoteSid: String) -> Unit) {
        if (code.str("k") != "o") return cb(null, "")
        val remote = code.str("s")
        if (remote.isEmpty() || remote == mySid) return cb(null, "")
        val old = links[remote]
        if (old != null && old.authed) return cb(null, remote)
        old?.close()
        val l = PeerLink(rtc, remote, false, false, this)
        l.purpose = code.str("p", "room")
        l.joinRoomId = code.str("r")
        l.inviteId = code.str("i")
        if (!l.start()) return cb(null, remote)
        links[remote] = l
        l.acceptOffer(code.str("d")) { ok ->
            if (!ok) {
                links.remove(remote); l.close(); cb(null, remote)
            } else {
                l.whenGathered(6000) {
                    val sdp = l.localSdp()
                    if (sdp == null) {
                        links.remove(remote); l.close(); cb(null, remote)
                    } else {
                        val j = jsonOf(
                            "k" to "a", "i" to l.inviteId, "s" to mySid, "u" to profile.uid,
                            "n" to (profile.profile.value?.displayName ?: ""),
                            "d" to Sdp.filterCandidates(sdp)
                        )
                        cb(Sdp.pack(j), remote)
                        armWatchdog(l, 11 * 60_000L)
                    }
                }
            }
        }
    }

    /** The inviter applies the answer code. Returns false if the invite is unknown/expired. */
    fun applyAnswer(code: JSONObject, cb: (Boolean) -> Unit) {
        if (code.str("k") != "a") return cb(false)
        val l = pending[code.str("i")] ?: return cb(false)
        val sid = code.str("s")
        if (sid.isEmpty() || sid == mySid) return cb(false)
        if (links[sid]?.authed == true) return cb(false)
        l.acceptAnswer(code.str("d")) { ok ->
            if (!ok) {
                cb(false)
            } else {
                pending.remove(l.inviteId)
                l.remoteSid = sid
                links[sid]?.let { old -> links.remove(sid); old.close() }
                links[sid] = l
                armWatchdog(l, 60_000)
                cb(true)
            }
        }
    }

    /* ======================================================================
     * Relayed (trickle) links
     * ==================================================================== */

    /** Make sure a direct link to [sid] exists or is being negotiated through the mesh. */
    fun connectTo(sid: String) {
        if (sid == mySid || links.containsKey(sid)) return
        val offerer = mySid < sid
        val l = PeerLink(rtc, sid, offerer, true, this)
        if (!l.start()) return
        links[sid] = l
        armWatchdog(l)
        if (offerer) l.createOffer(false) { ok -> if (!ok) dropLink(l, "offer failed") }
    }

    private fun onSig(from: String, d: JSONObject) {
        val desc = d.obj("desc")
        val cand = d.obj("cand")
        var l = links[from]
        if (desc != null) {
            val type = desc.str("type")
            val sdp = desc.str("sdp")
            if (type == "offer") {
                if (l != null && l.offerer && !l.authed && l.state != "connected") {
                    // glare: both sides offered. The smaller sid keeps offering, the other yields.
                    if (mySid < from) return
                    l.close(); links.remove(from); l = null
                } else if (l != null && !l.offerer) {
                    // renegotiation on an existing answerer link (ICE restart)
                }
                if (l == null) {
                    val nl = PeerLink(rtc, from, false, true, this)
                    if (!nl.start()) return
                    links[from] = nl
                    armWatchdog(nl)
                    l = nl
                }
                val link = l
                link.acceptOffer(sdp) { ok -> if (!ok) dropLink(link, "answer failed") }
            } else if (type == "answer") {
                l?.acceptAnswer(sdp) { ok -> if (!ok) Log.w("Mesh", "bad answer from $from") }
            }
        } else if (cand != null) {
            val link = l ?: run {
                // candidate raced ahead of its offer
                val nl = PeerLink(rtc, from, false, true, this)
                if (!nl.start()) return
                links[from] = nl
                armWatchdog(nl)
                nl
            }
            link.addRemoteCandidate(cand.str("mid"), cand.int("idx"), cand.str("sdp"))
        }
    }

    /* ======================================================================
     * LinkListener
     * ==================================================================== */

    override fun onLocalSdp(link: PeerLink, type: String, sdp: String) {
        sendTo(link.remoteSid, jsonOf("t" to "sig", "d" to jsonOf("desc" to jsonOf("type" to type, "sdp" to sdp))))
    }

    override fun onLocalCandidate(link: PeerLink, mid: String, index: Int, sdp: String) {
        if (sdp.contains(" fe80:")) return
        sendTo(link.remoteSid, jsonOf("t" to "sig", "d" to jsonOf("cand" to jsonOf("mid" to mid, "idx" to index, "sdp" to sdp))))
    }

    override fun onChannelOpen(link: PeerLink) {
        lastRx[link.remoteSid] = nowMs()
        if (!link.helloSent) {
            link.helloSent = true
            sendHello(link)
        }
    }

    override fun onState(link: PeerLink, state: String) {
        handler?.onLinkState(link, state)
        if (state != "connected") report.value = link.report()
        when (state) {
            "connected" -> {
                cancelLater(dropTimers.remove(link.remoteSid))
                restarts.remove(link.remoteSid)
            }
            "disconnected" -> scheduleDrop(link, 7000)
            "failed" -> {
                val n = restarts[link.remoteSid] ?: 0
                if (link.offerer && n < 2) {
                    restarts[link.remoteSid] = n + 1
                    link.restartIce { }
                    scheduleDrop(link, 10000)
                } else dropLink(link, "ice failed")
            }
            "closed" -> {
            }
        }
    }

    private fun scheduleDrop(link: PeerLink, ms: Long) {
        if (dropTimers.containsKey(link.remoteSid)) return
        dropTimers[link.remoteSid] = later(ms) {
            dropTimers.remove(link.remoteSid)
            if (links[link.remoteSid] === link && link.state != "connected") {
                if (link.offerer && (restarts[link.remoteSid] ?: 0) < 2 && link.state == "disconnected") {
                    restarts[link.remoteSid] = (restarts[link.remoteSid] ?: 0) + 1
                    link.restartIce { }
                    scheduleDrop(link, 8000)
                } else dropLink(link, "connection lost")
            }
        }
    }

    private fun armWatchdog(link: PeerLink, ms: Long = 25_000) {
        later(ms) {
            if (links[link.remoteSid] === link && (link.state != "connected" || !link.authed)) dropLink(link, if (link.state != "connected") "no path" else "no handshake")
        }
    }

    fun dropLink(link: PeerLink, reason: String) {
        if (!link.authed && !link.trickle) report.value = link.report() + "\nStopped: $reason"
        if (links[link.remoteSid] === link) links.remove(link.remoteSid)
        pending.remove(link.inviteId)
        cancelLater(dropTimers.remove(link.remoteSid))
        val wasAuthed = link.authed
        link.close()
        lastRx.remove(link.remoteSid)
        route.entries.removeAll { it.value == link.remoteSid }
        handler?.onLinkGone(link, reason)
        if (wasAuthed) Log.i("Mesh", "link to ${link.remoteSid} gone: $reason")
    }

    fun closeLink(sid: String, reason: String = "closed") {
        val l = links[sid] ?: return
        dropLink(l, reason)
    }

    /* ======================================================================
     * Handshake: hello + signed auth
     * ==================================================================== */

    private fun sendHello(link: PeerLink) {
        val p = profile.profile.value ?: return
        val h = jsonOf(
            "t" to "hello", "v" to 1, "sid" to mySid, "uid" to profile.uid, "pub" to profile.identity.pubB64,
            "name" to p.displayName, "user" to p.username, "av" to p.avatar.toJson(),
            "bio" to p.bio, "pron" to p.pronouns, "nonce" to link.myNonce,
            "purpose" to link.purpose, "room" to link.joinRoomId
        )
        val photo = profile.photo
        if (photo != null) h.put("photo", Base64.encodeToString(photo, Base64.NO_WRAP))
        link.send(h.toString())
    }

    private fun authPayload(nonce: String, signerSid: String, verifierSid: String): ByteArray =
        "wr-auth|$nonce|$signerSid|$verifierSid".toByteArray(Charsets.UTF_8)

    private fun onHello(link: PeerLink, m: JSONObject) {
        if (link.authed || link.remotePub.isNotEmpty()) return
        val pub = m.str("pub")
        val uid = m.str("uid")
        val sid = m.str("sid")
        if (pub.isEmpty() || uid != Identity.uidOf(pub) || sid.isEmpty()) {
            dropLink(link, "bad hello"); return
        }
        if (link.remoteSid != sid && !link.remoteSid.startsWith("pending-")) {
            dropLink(link, "sid mismatch"); return
        }
        if (uid == profile.uid) {
            dropLink(link, "self"); return
        }
        if (social.blocked.value.contains(uid)) {
            dropLink(link, "blocked"); return
        }
        link.uid = uid
        link.remotePub = pub
        link.remoteNonce = m.str("nonce")
        if (m.str("purpose").isNotEmpty()) link.purpose = m.str("purpose")
        if (m.str("room").isNotEmpty()) link.joinRoomId = m.str("room")
        link.hello = m   // the profile is only saved once the signature checks out
        val sig = profile.identity.sign(authPayload(link.remoteNonce, mySid, link.remoteSid))
        link.send(jsonOf("t" to "auth", "sig" to sig).toString())
    }

    private fun onAuth(link: PeerLink, m: JSONObject) {
        if (link.remotePub.isEmpty() || link.authed) return
        val ok = Identity.verify(link.remotePub, authPayload(link.myNonce, link.remoteSid, mySid), m.str("sig"))
        if (!ok) {
            dropLink(link, "auth failed"); return
        }
        link.authed = true
        link.hello?.let { h ->
            val c = Contact(
                link.uid, h.str("user").take(40), h.str("name").ifEmpty { h.str("user") }.take(60), Avatar.from(h.obj("av")),
                h.str("bio").take(400), h.str("pron").take(40), link.remotePub, nowMs()
            )
            social.upsert(c)
            val ph = h.str("photo")
            if (ph.isNotEmpty() && ph.length < 700_000) try {
                social.setPhoto(link.uid, Base64.decode(ph, Base64.NO_WRAP))
            } catch (e: Exception) {
            }
        }
        link.hello = null
        social.touchSeen(link.uid)
        // two links to the same person: both phones keep the one whose offerer has the lower id
        for (other in links.values.toList()) {
            if (other !== link && other.uid == link.uid && other.authed) {
                val mine = if (link.offerer) profile.uid else link.uid
                val theirs = if (other.offerer) profile.uid else other.uid
                val keepNew = if (mine != theirs) mine < theirs else other.createdAt < link.createdAt
                if (keepNew) {
                    links.remove(other.remoteSid); other.close()
                } else {
                    links.remove(link.remoteSid); pending.remove(link.inviteId); link.close()
                    return
                }
            }
        }
        handler?.onLinkAuthed(link)
    }

    /* ======================================================================
     * Messaging
     * ==================================================================== */

    override fun onMessage(link: PeerLink, text: String, reliable: Boolean) {
        lastRx[link.remoteSid] = nowMs()
        val m = parseJsonOrNull(text) ?: return
        when (m.str("t")) {
            "hello" -> onHello(link, m)
            "auth" -> onAuth(link, m)
            "p" -> {
            }
            "fwd" -> if (link.authed) onForward(link, m)
            else -> if (link.authed) {
                if (m.str("t") == "sig") onSig(link.remoteSid, m.obj("d") ?: return)
                else handler?.onMessage(link.remoteSid, m, link)
            }
        }
    }

    private fun onForward(via: PeerLink, m: JSONObject) {
        val from = m.str("from")
        val to = m.str("to")
        val inner = m.obj("m") ?: return
        if (from.isEmpty() || from == mySid) return
        // someone I am directly linked to talks to me directly; a relayed copy claiming to be them is a forgery
        if (from != via.remoteSid && links[from]?.authed == true) return
        if (from != via.remoteSid) route[from] = via.remoteSid
        if (to == mySid) {
            if (inner.str("t") == "sig") onSig(from, inner.obj("d") ?: return)
            else handler?.onMessage(from, inner, links[from]?.takeIf { it.authed })
            return
        }
        val ttl = minOf(m.int("ttl", 0), 3)
        if (ttl <= 0) return
        val direct = links[to]
        if (direct != null && direct.authed) {
            direct.send(m.put("ttl", ttl - 1).toString())
        } else {
            for (l in authedLinks()) if (l !== via) l.send(m.put("ttl", ttl - 1).toString())
        }
    }

    /** Send to a specific member: directly if linked, otherwise relayed through the mesh. */
    fun sendTo(sid: String, msg: JSONObject, reliable: Boolean = true): Boolean {
        val direct = links[sid]
        if (direct != null && (direct.authed || msg.str("t") == "sig") && direct.isOpen) {
            return direct.send(msg.toString(), reliable)
        }
        val env = jsonOf("t" to "fwd", "from" to mySid, "to" to sid, "ttl" to 3, "m" to msg).toString()
        val hop = route[sid]?.let { links[it] }?.takeIf { it.authed }
            ?: hintProvider?.invoke(sid)?.let { links[it] }?.takeIf { it.authed }
        if (hop != null) return hop.send(env)
        var sent = false
        for (l in authedLinks()) sent = l.send(env) || sent
        return sent
    }

    fun broadcast(msg: JSONObject, reliable: Boolean = true, except: Set<String> = emptySet()) {
        val s = msg.toString()
        for (l in authedLinks()) if (l.remoteSid !in except) l.send(s, reliable)
    }

    /** Called by the room layer so relayed negotiation knows the route to far members. */
    fun noteRoute(farSid: String, viaSid: String) {
        if (farSid != viaSid) route[farSid] = viaSid
    }

    private fun heartbeat() {
        val t = nowMs()
        val ping = "{\"t\":\"p\"}"
        for (l in links.values.toList()) {
            if (!l.authed) {
                continue
            }
            l.send(ping)
            val last = lastRx[l.remoteSid] ?: t
            if (t - last > 15_000) dropLink(l, "timeout")
        }
        beat = later(4000) { heartbeat() }
    }
}
