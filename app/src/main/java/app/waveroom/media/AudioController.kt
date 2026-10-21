package app.waveroom.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import app.waveroom.AppCore
import app.waveroom.core.canSpeak
import app.waveroom.core.cancelLater
import app.waveroom.core.later
import app.waveroom.core.nowMs
import app.waveroom.net.PeerLink
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Everything about sound in a room: whether the mic is live, who we hear, per-person
 * volume, deafen, speaking detection and routing to speaker / earpiece / headset.
 */
class AudioController(private val ctx: Context, private val core: AppCore) {

    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val level = MutableStateFlow(0f)
    val speakingSelf = MutableStateFlow(false)
    /** 0 = speaker, 1 = earpiece-like default chosen by the system */
    val speakerOn = MutableStateFlow(true)

    private var running = false
    private var poll: Runnable? = null
    private var lastVoice = 0L
    private val volumes = HashMap<String, Double>()
    private var focus: AudioFocusRequest? = null
    private var prevMode = AudioManager.MODE_NORMAL

    fun start() {
        if (running) return
        running = true
        prevMode = am.mode
        try {
            am.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (e: Exception) {
        }
        requestFocus()
        route()
        poll = later(80) { tick() }
    }

    fun stop() {
        running = false
        cancelLater(poll); poll = null
        core.rtc.releaseMic()
        for (l in core.mesh.authedLinks()) l.setSendAudio(null)
        level.value = 0f
        speakingSelf.value = false
        try {
            if (Build.VERSION.SDK_INT >= 31) am.clearCommunicationDevice() else am.isSpeakerphoneOn = false
        } catch (e: Exception) {
        }
        try {
            am.mode = prevMode
        } catch (e: Exception) {
        }
        val f = focus
        if (f != null && Build.VERSION.SDK_INT >= 26) am.abandonAudioFocusRequest(f)
        focus = null
    }

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
                )
                .setOnAudioFocusChangeListener { }
                .build()
            am.requestAudioFocus(req)
            focus = req
        }
    }

    /* ---------------- routing ---------------- */

    fun outputDevices(): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= 31) am.availableCommunicationDevices
        else am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    fun inputDevices(): List<AudioDeviceInfo> = am.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()

    fun route() {
        val want = core.settings.state.value.outputDeviceId
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = am.availableCommunicationDevices
                val chosen: AudioDeviceInfo? = when {
                    want >= 0 -> devs.firstOrNull { it.id == want }
                    else -> devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
                        ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
                        ?: devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                }
                if (chosen != null) {
                    am.setCommunicationDevice(chosen)
                    speakerOn.value = chosen.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                }
            } else {
                val headset = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                am.isSpeakerphoneOn = speakerOn.value && !headset
            }
        } catch (e: Exception) {
        }
    }

    fun setSpeaker(on: Boolean) {
        speakerOn.value = on
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                val devs = am.availableCommunicationDevices
                val d = if (on) devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                else devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_EARPIECE }
                if (d != null) am.setCommunicationDevice(d)
            } else am.isSpeakerphoneOn = on
        } catch (e: Exception) {
        }
    }

    /* ---------------- volume ---------------- */

    fun userVolume(uid: String): Double = volumes[uid] ?: 1.0

    fun setUserVolume(uid: String, v: Double) {
        volumes[uid] = v.coerceIn(0.0, 2.0)
        refresh()
    }

    /* ---------------- mic + remote tracks ---------------- */

    private fun wantsMic(): Boolean {
        val me = core.session.me ?: return false
        return canSpeak(me.role)
    }

    private fun micLive(): Boolean {
        val me = core.session.me ?: return false
        return canSpeak(me.role) && !me.muted
    }

    /** Recompute everything from the room state. Cheap; call after any relevant change. */
    fun refresh() {
        if (!running) return
        val wants = wantsMic()
        if (wants) {
            val t = core.rtc.ensureMic()
            t.setEnabled(micLive())
            for (l in core.mesh.authedLinks()) l.setSendAudio(t)
        } else {
            for (l in core.mesh.authedLinks()) l.setSendAudio(null)
            core.rtc.releaseMic()
            if (speakingSelf.value) setSpeaking(false)
        }
        for (l in core.mesh.authedLinks()) applyRemote(l)
    }

    fun attach(link: PeerLink) {
        if (!running || !link.authed) return
        if (wantsMic()) link.setSendAudio(core.rtc.ensureMic()) else link.setSendAudio(null)
        applyRemote(link)
    }

    private fun applyRemote(link: PeerLink) {
        val s = core.session.room ?: return
        val track = link.remoteAudio() ?: return
        val p = s.participants[link.remoteSid]
        val allowed = p != null && canSpeak(p.role) && !core.session.deafened.value
        try {
            track.setEnabled(allowed)
            track.setVolume(if (allowed) 2.0 * volumes.getOrDefault(link.uid, 1.0) else 0.0)
        } catch (e: Exception) {
        }
    }

    private fun tick() {
        if (!running) return
        val lvl = core.rtc.micLevel
        level.value = if (micLive()) lvl else 0f
        if (micLive() && lvl > 0.018f) {
            lastVoice = nowMs()
            if (!speakingSelf.value) setSpeaking(true)
        } else if (speakingSelf.value && nowMs() - lastVoice > 450) {
            setSpeaking(false)
        }
        poll = later(80) { tick() }
    }

    private fun setSpeaking(on: Boolean) {
        speakingSelf.value = on
        core.session.setSpeakingSelf(on)
    }
}
