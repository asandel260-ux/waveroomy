package app.waveroom.media

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import app.waveroom.core.SettingsStore
import kotlin.math.PI
import kotlin.math.sin

/** Tiny synthesized interface sounds (no asset files). */
class Sfx(private val settings: SettingsStore) {

    private val rate = 22050
    private val cache = HashMap<String, ShortArray>()

    private fun tone(parts: List<Pair<Double, Double>>): ShortArray {
        val out = ArrayList<Short>()
        for ((f, d) in parts) {
            val n = (rate * d).toInt()
            for (i in 0 until n) {
                val env = minOf(1.0, i / (rate * 0.008)) * minOf(1.0, (n - i) / (rate * 0.03))
                val v = sin(2.0 * PI * f * i / rate) * 0.28 * env
                out.add((v * 32767).toInt().toShort())
            }
        }
        return out.toShortArray()
    }

    private fun data(name: String): ShortArray = cache.getOrPut(name) {
        when (name) {
            "join" -> tone(listOf(523.0 to 0.07, 784.0 to 0.10))
            "leave" -> tone(listOf(659.0 to 0.07, 440.0 to 0.10))
            "message" -> tone(listOf(880.0 to 0.06))
            "hand" -> tone(listOf(698.0 to 0.05, 698.0 to 0.05, 932.0 to 0.08))
            "mute" -> tone(listOf(330.0 to 0.07))
            "unmute" -> tone(listOf(494.0 to 0.07))
            else -> tone(listOf(600.0 to 0.05))
        }
    }

    fun play(name: String) {
        if (!settings.state.value.sounds) return
        Thread {
            try {
                val d = data(name)
                val t = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder().setSampleRate(rate).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build()
                    )
                    .setBufferSizeInBytes(d.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t.write(d, 0, d.size)
                t.play()
                Thread.sleep((d.size * 1000L / rate) + 80)
                t.release()
            } catch (e: Throwable) {
            }
        }.also { it.isDaemon = true; it.start() }
    }
}
