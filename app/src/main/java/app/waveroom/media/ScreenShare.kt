package app.waveroom.media

import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.view.WindowManager
import app.waveroom.AppCore
import app.waveroom.core.later
import app.waveroom.core.onMain
import app.waveroom.net.PeerLink
import kotlinx.coroutines.flow.MutableStateFlow
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack

/** Captures the device screen with MediaProjection and sends it to every connected member. */
class ScreenShare(private val core: AppCore) {

    val active = MutableStateFlow(false)

    private var capturer: ScreenCapturerAndroid? = null
    private var helper: SurfaceTextureHelper? = null
    private var source: VideoSource? = null
    private var track: VideoTrack? = null
    private var res = 720
    private var fps = 30

    private fun bitrate(res: Int, fps: Int): Int {
        val base = when (res) {
            240 -> 400_000
            480 -> 1_000_000
            720 -> 2_500_000
            else -> 5_000_000
        }
        return (base * (0.55 + 0.45 * fps / 30.0)).toInt()
    }

    private fun dims(res: Int): Pair<Int, Int> {
        val wm = core.app.getSystemService(android.content.Context.WINDOW_SERVICE) as WindowManager
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val w = dm.widthPixels
        val h = dm.heightPixels
        val shortSide = minOf(w, h)
        val scale = minOf(1.0, res.toDouble() / shortSide)
        fun even(v: Double) = (v.toInt() / 2) * 2
        return Pair(even(w * scale), even(h * scale))
    }

    /** [data] is the Intent returned by the system's screen capture consent dialog. */
    fun start(data: Intent, res: Int, fps: Int, onFailed: (String) -> Unit) {
        if (active.value) return
        this.res = res; this.fps = fps
        // the foreground service must declare the projection type before the capture begins
        core.startService(projection = true)
        later(350) {
            try {
                val rtc = core.rtc
                val h = SurfaceTextureHelper.create("wr-screen", rtc.egl.eglBaseContext)
                val src = rtc.factory.createVideoSource(true)
                val cap = ScreenCapturerAndroid(data, object : MediaProjection.Callback() {
                    override fun onStop() {
                        onMain { stop(true) }
                    }
                })
                cap.initialize(h, core.app, src.capturerObserver)
                val (w, hh) = dims(res)
                cap.startCapture(w, hh, fps)
                val t = rtc.factory.createVideoTrack("wr-screen", src)
                t.setEnabled(true)
                helper = h; source = src; capturer = cap; track = t
                active.value = true
                for (l in core.mesh.authedLinks()) attach(l)
            } catch (e: Throwable) {
                stop(false)
                onFailed(e.message ?: "Screen capture failed")
            }
        }
    }

    /** Room membership changed: start sending to newcomers. */
    fun refresh() {
        if (active.value) for (l in core.mesh.authedLinks()) attach(l)
    }

    fun attach(link: PeerLink) {
        val t = track ?: return
        if (!active.value || !link.authed) return
        // only people in the room get to see the screen, not DM friends who happen to be linked
        if (core.session.room?.participants?.containsKey(link.remoteSid) != true) return
        link.setSendVideo(t)
        link.tuneVideo(bitrate(res, fps), fps)
    }

    fun setQuality(res: Int, fps: Int) {
        this.res = res; this.fps = fps
        val cap = capturer ?: return
        try {
            val (w, h) = dims(res)
            cap.changeCaptureFormat(w, h, fps)
        } catch (e: Throwable) {
        }
        for (l in core.mesh.authedLinks()) l.tuneVideo(bitrate(res, fps), fps)
    }

    fun stop(announce: Boolean) {
        val was = active.value
        active.value = false
        for (l in core.mesh.authedLinks()) l.setSendVideo(null)
        try {
            capturer?.stopCapture()
        } catch (e: Throwable) {
        }
        try {
            capturer?.dispose()
        } catch (e: Throwable) {
        }
        try {
            track?.dispose()
        } catch (e: Throwable) {
        }
        try {
            source?.dispose()
        } catch (e: Throwable) {
        }
        try {
            helper?.dispose()
        } catch (e: Throwable) {
        }
        capturer = null; track = null; source = null; helper = null
        if (was) {
            if (announce) core.session.announceStopShare()
            if (core.session.live) core.startService(projection = false)
        }
    }
}
