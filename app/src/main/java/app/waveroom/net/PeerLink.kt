package app.waveroom.net

import android.util.Log
import app.waveroom.core.Ids
import app.waveroom.core.cancelLater
import app.waveroom.core.later
import app.waveroom.core.onMain
import app.waveroom.media.RtcEngine
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack
import java.nio.ByteBuffer

interface LinkListener {
    /** Trickle mode only: local description is set and ready to be sent. */
    fun onLocalSdp(link: PeerLink, type: String, sdp: String)
    fun onLocalCandidate(link: PeerLink, mid: String, index: Int, sdp: String)
    fun onChannelOpen(link: PeerLink)
    fun onMessage(link: PeerLink, text: String, reliable: Boolean)
    /** "connecting" | "connected" | "disconnected" | "failed" | "closed" */
    fun onState(link: PeerLink, state: String)
    fun onDiag(link: PeerLink)
}

private open class PcObserver : PeerConnection.Observer {
    override fun onSignalingChange(p0: PeerConnection.SignalingState?) {}
    override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {}
    override fun onIceConnectionReceivingChange(p0: Boolean) {}
    override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {}
    override fun onIceCandidate(p0: IceCandidate?) {}
    override fun onIceCandidatesRemoved(p0: Array<out IceCandidate>?) {}
    override fun onAddStream(p0: MediaStream?) {}
    override fun onRemoveStream(p0: MediaStream?) {}
    override fun onDataChannel(p0: DataChannel?) {}
    override fun onRenegotiationNeeded() {}
    override fun onAddTrack(p0: RtpReceiver?, p1: Array<out MediaStream>?) {}
}

private open class SdpObs : SdpObserver {
    override fun onCreateSuccess(p0: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(p0: String?) {}
    override fun onSetFailure(p0: String?) {}
}

/**
 * One WebRTC peer connection to one remote member: an Opus audio transceiver, a
 * video transceiver (used only while someone shares their screen), and two
 * pre-negotiated data channels (reliable "ctl", unreliable "fast"). Everything is
 * negotiated once, so starting/stopping a screen share never renegotiates.
 */
class PeerLink(
    private val rtc: RtcEngine,
    var remoteSid: String,
    val offerer: Boolean,
    val trickle: Boolean,
    private val listener: LinkListener
) {
    // --- identity / protocol state, managed by Mesh ---
    var uid: String = ""
    var authed = false
    var purpose = "room"            // "room" | "dm"
    var joinRoomId: String = ""
    var myNonce: String = Ids.nonce()
    var remoteNonce: String = ""
    var remotePub: String = ""
    var helloSent = false
    var inviteId: String = ""
    var createdAt = System.currentTimeMillis()
    var state = "new"
    var lastRoomSig = 0L

    private var pc: PeerConnection? = null
    private var ctl: DataChannel? = null
    private var fast: DataChannel? = null
    private var audioTx: RtpTransceiver? = null
    private var videoTx: RtpTransceiver? = null
    private var remoteSet = false
    private val pendingCandidates = ArrayList<IceCandidate>()
    private var gatherCb: (() -> Unit)? = null
    private var gatherTimeout: Runnable? = null
    private var closed = false
    val log = ArrayList<String>()
    private val localTypes = HashMap<String, Int>()
    private var remoteTypes = HashMap<String, Int>()
    private var remoteMdns = false
    var iceState = "new"

    private fun note(s: String) {
        log.add(s)
        if (log.size > 40) log.removeAt(0)
    }

    private fun countTypes(sdp: String): HashMap<String, Int> {
        val m = HashMap<String, Int>()
        for (l in sdp.split("\r\n", "\n")) if (l.startsWith("a=candidate:")) {
            val i = l.indexOf(" typ ")
            if (i > 0) { val t = l.substring(i + 5).substringBefore(' '); m[t] = (m[t] ?: 0) + 1 }
            if (l.contains(".local ")) remoteMdns = true
        }
        return m
    }

    /** Human readable summary of how this link's connection attempt is going. */
    fun report(): String {
        fun fmt(m: Map<String, Int>) = if (m.isEmpty()) "none" else m.entries.joinToString(", ") { "${it.value} ${it.key}" }
        val sb = StringBuilder()
        sb.append("Connection: $state · ICE: $iceState\n")
        sb.append("My candidates: ${fmt(localTypes)}\n")
        sb.append("Their candidates: ${fmt(remoteTypes)}\n")
        if (remoteMdns) sb.append("Their address is hidden behind a .local name\n")
        val local = localTypes.keys; val remote = remoteTypes.keys
        if (state != "connected") {
            val hint = when {
                remote.isEmpty() && remoteTypes.isEmpty() && log.none { it.startsWith("remote set") } -> "Waiting for their reply to be applied."
                localTypes.isEmpty() -> "This phone found no network address. Is it online?"
                remote.isNotEmpty() && remote.all { it == "host" } && local.all { it == "host" } -> "Both phones only shared local addresses. They must be on the same Wi-Fi (or one on the other's hotspot), with no VPN and no 'AP isolation'. Turn on the relay helper in Settings to connect across networks."
                !remote.contains("relay") && !local.contains("relay") -> "Phones on different networks (mobile data / strict routers) usually need the relay helper. Enable it in Settings → Network on both phones."
                else -> "Still negotiating…"
            }
            sb.append(hint)
        }
        return sb.toString().trim()
    }
    private val partial = HashMap<String, Array<String?>>()

    val isOpen: Boolean get() = ctl?.state() == DataChannel.State.OPEN

    /* ------------------------------------------------------------------ */

    fun start(): Boolean {
        val cfg = PeerConnection.RTCConfiguration(rtc.iceServers())
        cfg.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
        cfg.bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
        cfg.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        cfg.tcpCandidatePolicy = PeerConnection.TcpCandidatePolicy.DISABLED
        cfg.continualGatheringPolicy =
            if (trickle) PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            else PeerConnection.ContinualGatheringPolicy.GATHER_ONCE

        val obs = object : PcObserver() {
            override fun onIceConnectionChange(p0: PeerConnection.IceConnectionState?) {
                onMain { iceState = p0?.name?.lowercase() ?: "?"; note("ice $iceState"); if (!closed) listener.onDiag(this@PeerLink) }
            }

            override fun onIceCandidate(p0: IceCandidate?) {
                val c = p0 ?: return
                val ty = Regex(" typ (\\w+)").find(c.sdp)?.groupValues?.get(1) ?: "?"
                onMain { localTypes[ty] = (localTypes[ty] ?: 0) + 1 }
                if (trickle) onMain { if (!closed) listener.onLocalCandidate(this@PeerLink, c.sdpMid ?: "", c.sdpMLineIndex, c.sdp) }
            }

            override fun onIceGatheringChange(p0: PeerConnection.IceGatheringState?) {
                if (p0 == PeerConnection.IceGatheringState.COMPLETE) onMain { fireGather() }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
                val s = when (newState) {
                    PeerConnection.PeerConnectionState.CONNECTED -> "connected"
                    PeerConnection.PeerConnectionState.CONNECTING -> "connecting"
                    PeerConnection.PeerConnectionState.DISCONNECTED -> "disconnected"
                    PeerConnection.PeerConnectionState.FAILED -> "failed"
                    PeerConnection.PeerConnectionState.CLOSED -> "closed"
                    else -> return
                }
                onMain {
                    if (!closed) {
                        state = s
                        listener.onState(this@PeerLink, s)
                    }
                }
            }
        }
        val p = rtc.factory.createPeerConnection(cfg, obs) ?: return false
        pc = p

        val ctlInit = DataChannel.Init()
        ctlInit.negotiated = true
        ctlInit.id = 0
        ctlInit.ordered = true
        val fastInit = DataChannel.Init()
        fastInit.negotiated = true
        fastInit.id = 1
        fastInit.ordered = false
        fastInit.maxRetransmits = 0
        ctl = p.createDataChannel("ctl", ctlInit)
        fast = p.createDataChannel("fast", fastInit)
        watchChannel(ctl, true)
        watchChannel(fast, false)

        if (offerer) {
            val init = RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            audioTx = p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO, init)
            videoTx = p.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
            )
        }
        return true
    }

    private fun watchChannel(dc: DataChannel?, reliable: Boolean) {
        val c = dc ?: return
        c.registerObserver(object : DataChannel.Observer {
            override fun onBufferedAmountChange(p0: Long) {}

            override fun onStateChange() {
                if (reliable && c.state() == DataChannel.State.OPEN) onMain { if (!closed) listener.onChannelOpen(this@PeerLink) }
            }

            override fun onMessage(buffer: DataChannel.Buffer?) {
                val b = buffer ?: return
                val bytes = ByteArray(b.data.remaining())
                b.data.get(bytes)
                val text = String(bytes, Charsets.UTF_8)
                onMain { if (!closed) receiveFrame(text, reliable) }
            }
        })
    }

    /* ---- framing: long messages are split into chunks ---- */

    private fun receiveFrame(text: String, reliable: Boolean) {
        if (text.isNotEmpty() && text[0] == '\u0001') {
            val p1 = text.indexOf('|', 1)
            val p2 = text.indexOf('|', p1 + 1)
            val p3 = text.indexOf('|', p2 + 1)
            if (p1 < 0 || p2 < 0 || p3 < 0) return
            val id = text.substring(1, p1)
            val i = text.substring(p1 + 1, p2).toIntOrNull() ?: return
            val n = text.substring(p2 + 1, p3).toIntOrNull() ?: return
            if (n <= 0 || n > 400 || i < 0 || i >= n) return
            val arr = partial.getOrPut(id) { arrayOfNulls<String>(n) }
            arr[i] = text.substring(p3 + 1)
            if (arr.all { it != null }) {
                partial.remove(id)
                val sb = StringBuilder()
                for (s in arr) sb.append(s)
                listener.onMessage(this, sb.toString(), reliable)
            }
            if (partial.size > 20) partial.clear()
            return
        }
        listener.onMessage(this, text, reliable)
    }

    fun buffered(): Long = try { ctl?.bufferedAmount() ?: 0L } catch (e: Exception) { 0L }

    /** Sends one JSON text. Returns false if the channel is not open. */
    fun send(text: String, reliable: Boolean = true): Boolean {
        val dc = (if (reliable) ctl else fast) ?: return false
        if (dc.state() != DataChannel.State.OPEN) return false
        return try {
            if (text.length <= CHUNK) {
                dc.send(DataChannel.Buffer(ByteBuffer.wrap(text.toByteArray(Charsets.UTF_8)), false))
            } else if (!reliable) {
                false
            } else {
                val id = Ids.rand(6)
                val n = (text.length + CHUNK - 1) / CHUNK
                for (i in 0 until n) {
                    val part = text.substring(i * CHUNK, minOf(text.length, (i + 1) * CHUNK))
                    val frame = "\u0001$id|$i|$n|$part"
                    dc.send(DataChannel.Buffer(ByteBuffer.wrap(frame.toByteArray(Charsets.UTF_8)), false))
                }
                true
            }
        } catch (e: Exception) {
            Log.w("PeerLink", "send failed", e)
            false
        }
    }

    /* ------------------------------------------------------------------ */
    /* Signalling                                                          */
    /* ------------------------------------------------------------------ */

    fun createOffer(iceRestart: Boolean, done: (Boolean) -> Unit) {
        val p = pc ?: return done(false)
        val mc = MediaConstraints()
        if (iceRestart) mc.mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
        p.createOffer(object : SdpObs() {
            override fun onCreateSuccess(p0: SessionDescription?) {
                val d = p0 ?: return done(false)
                val munged = SessionDescription(d.type, Sdp.forOffer(d.description))
                p.setLocalDescription(object : SdpObs() {
                    override fun onSetSuccess() {
                        onMain { if (trickle && !closed) listener.onLocalSdp(this@PeerLink, "offer", munged.description); done(true) }
                    }

                    override fun onSetFailure(p0: String?) {
                        Log.w("PeerLink", "setLocal(offer) failed: $p0"); onMain { done(false) }
                    }
                }, munged)
            }

            override fun onCreateFailure(p0: String?) {
                Log.w("PeerLink", "createOffer failed: $p0"); onMain { done(false) }
            }
        }, mc)
    }

    /** Apply a remote offer and produce our answer. */
    fun acceptOffer(sdp: String, done: (Boolean) -> Unit) {
        val p = pc ?: return done(false)
        remoteTypes = countTypes(sdp); note("remote set (offer): $remoteTypes")
        p.setRemoteDescription(object : SdpObs() {
            override fun onSetSuccess() {
                onMain {
                    if (closed) return@onMain
                    remoteSet = true
                    for (tx in p.transceivers) {
                        try {
                            tx.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV)
                        } catch (e: Exception) {
                        }
                        if (tx.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO) audioTx = tx
                        if (tx.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO) videoTx = tx
                    }
                    flushCandidates()
                    p.createAnswer(object : SdpObs() {
                        override fun onCreateSuccess(p0: SessionDescription?) {
                            val d = p0 ?: return done(false)
                            p.setLocalDescription(object : SdpObs() {
                                override fun onSetSuccess() {
                                    onMain { if (trickle && !closed) listener.onLocalSdp(this@PeerLink, "answer", d.description); done(true) }
                                }

                                override fun onSetFailure(p0: String?) {
                                    Log.w("PeerLink", "setLocal(answer) failed: $p0"); onMain { done(false) }
                                }
                            }, d)
                        }

                        override fun onCreateFailure(p0: String?) {
                            Log.w("PeerLink", "createAnswer failed: $p0"); onMain { done(false) }
                        }
                    }, MediaConstraints())
                }
            }

            override fun onSetFailure(p0: String?) {
                Log.w("PeerLink", "setRemote(offer) failed: $p0"); onMain { done(false) }
            }
        }, SessionDescription(SessionDescription.Type.OFFER, sdp))
    }

    fun acceptAnswer(sdp: String, done: (Boolean) -> Unit) {
        val p = pc ?: return done(false)
        remoteTypes = countTypes(sdp); note("remote set (answer): $remoteTypes")
        p.setRemoteDescription(object : SdpObs() {
            override fun onSetSuccess() {
                onMain { remoteSet = true; flushCandidates(); done(true) }
            }

            override fun onSetFailure(p0: String?) {
                Log.w("PeerLink", "setRemote(answer) failed: $p0"); onMain { done(false) }
            }
        }, SessionDescription(SessionDescription.Type.ANSWER, sdp))
    }

    fun addRemoteCandidate(mid: String, index: Int, sdp: String) {
        val c = IceCandidate(mid, index, sdp)
        if (!remoteSet) pendingCandidates.add(c) else try {
            pc?.addIceCandidate(c)
        } catch (e: Exception) {
        }
    }

    private fun flushCandidates() {
        for (c in pendingCandidates) try {
            pc?.addIceCandidate(c)
        } catch (e: Exception) {
        }
        pendingCandidates.clear()
    }

    /** Non-trickle: invoke [cb] once candidate gathering is complete (or after [timeoutMs]). */
    fun whenGathered(timeoutMs: Long, cb: () -> Unit) {
        if (pc?.iceGatheringState() == PeerConnection.IceGatheringState.COMPLETE) return cb()
        gatherCb = cb
        gatherTimeout = later(timeoutMs) { fireGather() }
    }

    private fun fireGather() {
        val cb = gatherCb ?: return
        gatherCb = null
        cancelLater(gatherTimeout)
        gatherTimeout = null
        cb()
    }

    fun localSdp(): String? = pc?.localDescription?.description

    fun restartIce(done: (Boolean) -> Unit) {
        if (!trickle) { done(false); return }
        if (!offerer) return done(false)
        createOffer(true, done)
    }

    /* ------------------------------------------------------------------ */
    /* Media                                                               */
    /* ------------------------------------------------------------------ */

    fun setSendAudio(track: AudioTrack?) {
        try {
            audioTx?.sender?.setTrack(track, false)
        } catch (e: Exception) {
            Log.w("PeerLink", "setSendAudio", e)
        }
    }

    fun setSendVideo(track: VideoTrack?) {
        try {
            videoTx?.sender?.setTrack(track, false)
        } catch (e: Exception) {
            Log.w("PeerLink", "setSendVideo", e)
        }
    }

    fun tuneVideo(bitrateBps: Int, fps: Int) {
        try {
            val s = videoTx?.sender ?: return
            val params = s.parameters
            if (params.encodings.isNotEmpty()) {
                params.encodings[0].maxBitrateBps = bitrateBps
                params.encodings[0].maxFramerate = fps
            }
            params.degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            s.setParameters(params)
        } catch (e: Throwable) {
            Log.w("PeerLink", "tuneVideo", e)
        }
    }

    fun remoteAudio(): AudioTrack? = try {
        audioTx?.receiver?.track() as? AudioTrack
    } catch (e: Exception) {
        null
    }

    fun remoteVideo(): VideoTrack? = try {
        videoTx?.receiver?.track() as? VideoTrack
    } catch (e: Exception) {
        null
    }

    fun close() {
        if (closed) return
        closed = true
        cancelLater(gatherTimeout)
        try {
            ctl?.unregisterObserver()
            fast?.unregisterObserver()
            ctl?.close(); fast?.close()
            ctl?.dispose(); fast?.dispose()
        } catch (e: Throwable) {
        }
        try {
            pc?.close()
            pc?.dispose()
        } catch (e: Throwable) {
        }
        pc = null; ctl = null; fast = null
        state = "closed"
    }

    companion object {
        private const val CHUNK = 12000
    }
}
