package app.waveroom.media

import android.content.Context
import android.media.AudioDeviceInfo
import android.util.Log
import app.waveroom.core.SettingsStore
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.MediaConstraints
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.audio.JavaAudioDeviceModule
import kotlin.math.sqrt

/**
 * Owns the native WebRTC factory, the audio device module and the single local
 * microphone track that is attached to every peer connection.
 */
class RtcEngine(private val ctx: Context, private val settings: SettingsStore) {

    val egl: EglBase = EglBase.create()
    val fx = VoiceFx()

    lateinit var factory: PeerConnectionFactory
        private set
    private var adm: JavaAudioDeviceModule? = null
    private var audioSource: AudioSource? = null
    var micTrack: AudioTrack? = null
        private set

    /** RMS (0..1) of the most recent captured frame; polled by the UI/meter. */
    @Volatile var micLevel = 0f

    fun init() {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(ctx.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val module = JavaAudioDeviceModule.builder(ctx.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .setSamplesReadyCallback { samples ->
                val d = samples.data
                var sum = 0.0
                var n = 0
                var i = 0
                while (i + 1 < d.size) {
                    val v = ((d[i + 1].toInt() shl 8) or (d[i].toInt() and 0xFF)).toShort().toInt() / 32768.0
                    sum += v * v
                    n++
                    i += 2
                }
                micLevel = if (n > 0) sqrt(sum / n).toFloat() else 0f
            }
            .createAudioDeviceModule()
        adm = module

        val builder = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(egl.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(egl.eglBaseContext))
            .setAudioDeviceModule(module)
            .setOptions(PeerConnectionFactory.Options())
        fx.install(builder)
        factory = builder.createPeerConnectionFactory()
        val s = settings.state.value
        fx.configure(s.fxEnabled, s.fxPreset, s.fxIntensity)
    }

    /** Creates (once) the local mic track. Recording only really starts once a sender uses it. */
    fun ensureMic(): AudioTrack {
        val existing = micTrack
        if (existing != null) return existing
        val src = factory.createAudioSource(MediaConstraints())
        audioSource = src
        val t = factory.createAudioTrack("wr-mic", src)
        t.setEnabled(false)
        micTrack = t
        applyPreferredInput(settings.state.value.micDeviceId)
        return t
    }

    fun releaseMic() {
        try {
            micTrack?.dispose()
        } catch (e: Throwable) {
        }
        try {
            audioSource?.dispose()
        } catch (e: Throwable) {
        }
        micTrack = null
        audioSource = null
        micLevel = 0f
    }

    /** Select a specific input (built-in, headset, USB...) when the WebRTC build supports it. */
    fun applyPreferredInput(deviceId: Int) {
        val module = adm ?: return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val info: AudioDeviceInfo? =
                if (deviceId < 0) null
                else am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.id == deviceId }
            val m = module.javaClass.getMethod("setPreferredInputDevice", AudioDeviceInfo::class.java)
            m.invoke(module, info)
        } catch (t: Throwable) {
            Log.i("RtcEngine", "preferred input unsupported: $t")
        }
    }

    fun iceServers(): List<PeerConnection.IceServer> {
        val s = settings.state.value
        val out = ArrayList<PeerConnection.IceServer>()
        val stun = ArrayList<String>()
        if (s.useStun) {
            stun.add("stun:stun.l.google.com:19302")
            stun.add("stun:stun1.l.google.com:19302")
            stun.add("stun:stun.cloudflare.com:3478")
            stun.add("stun:stun.nextcloud.com:443")
        }
        for (u in s.customStun.split(',', '\n', ' ')) {
            val t = u.trim()
            if (t.isNotEmpty()) stun.add(if (t.startsWith("stun:") || t.startsWith("stuns:")) t else "stun:$t")
        }
        if (stun.isNotEmpty()) out.add(PeerConnection.IceServer.builder(stun).createIceServer())
        if (s.useRelay && s.turnUrl.isBlank()) {
            // last-resort relay so phones on different networks can still connect; it only sees encrypted packets
            out.add(
                PeerConnection.IceServer.builder(
                    listOf("turn:openrelay.metered.ca:80", "turn:openrelay.metered.ca:443", "turn:openrelay.metered.ca:443?transport=tcp")
                ).setUsername("openrelayproject").setPassword("openrelayproject").createIceServer()
            )
        }
        val turn = s.turnUrl.trim()
        if (turn.isNotEmpty()) {
            val urls = turn.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            out.add(
                PeerConnection.IceServer.builder(urls)
                    .setUsername(s.turnUser).setPassword(s.turnPass).createIceServer()
            )
        }
        return out
    }

    fun turnConfigured(): Boolean = settings.state.value.turnUrl.isNotBlank()

    fun applyFx() {
        val s = settings.state.value
        fx.configure(s.fxEnabled, s.fxPreset, s.fxIntensity)
    }
}
