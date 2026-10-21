package app.waveroom.media

import android.util.Log
import org.webrtc.AudioProcessingFactory
import org.webrtc.PeerConnectionFactory
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/* ===========================================================================
 * Real-time voice effects: the Android port of the web app's Web Audio graph.
 * Same eight presets, same DSP building blocks (RBJ biquads, compressor, soft
 * clip, ring modulation, dual-head modulated-delay pitch shifter), running on
 * the microphone frames between capture and the WebRTC encoder, so everyone in
 * the room hears the processed voice.
 *
 * The hook into libwebrtc's capture path is the "external audio processing"
 * facility of the io.github.webrtc-sdk build. It is reached by reflection so
 * the app still compiles and runs (without effects) on other WebRTC builds.
 * ========================================================================= */

data class FxPreset(val id: String, val name: String, val desc: String)

val FX_PRESETS = listOf(
    FxPreset("natural", "Natural", "Your voice with a gentle clean-up."),
    FxPreset("studio", "Studio", "Compressed, de-rumbled, presence lift."),
    FxPreset("deep", "Deep", "Lower pitch with added chest resonance."),
    FxPreset("bright", "Bright", "Lifted clarity, a touch of air."),
    FxPreset("radio", "Radio", "Broadcast band-pass and drive."),
    FxPreset("character", "Character", "Raised pitch, animated tone."),
    FxPreset("robotic", "Robotic", "Ring-modulated synthetic tone."),
    FxPreset("vintage", "Vintage", "Narrow band, softly saturated, aged.")
)

/* ---- DSP primitives (per channel, per sample) ---- */

private fun dbToLin(db: Double): Float = 10.0.pow(db / 20.0).toFloat()
private fun lerp(a: Double, b: Double, k: Double): Double = a + (b - a) * k

private abstract class Stage {
    abstract fun p(x: Float): Float
}

private class Biquad(private val sr: Float, private var type: String, freq: Double, private var q: Double = 0.707, private var gainDb: Double = 0.0) : Stage() {
    private var b0 = 1.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0
    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0
    private var f = freq

    init {
        calc()
    }

    fun set(freq: Double = f, gainDb: Double = this.gainDb) {
        f = freq
        this.gainDb = gainDb
        calc()
    }

    private fun calc() {
        val fc = f.coerceIn(10.0, sr * 0.45)
        val w0 = 2.0 * PI * fc / sr
        val cw = cos(w0)
        val sw = sin(w0)
        val alpha = sw / (2.0 * q)
        val a = 10.0.pow(gainDb / 40.0)
        var nb0: Double
        var nb1: Double
        var nb2: Double
        val na0: Double
        val na1: Double
        val na2: Double
        when (type) {
            "lowpass" -> {
                nb0 = (1 - cw) / 2; nb1 = 1 - cw; nb2 = (1 - cw) / 2
                na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha
            }
            "highpass" -> {
                nb0 = (1 + cw) / 2; nb1 = -(1 + cw); nb2 = (1 + cw) / 2
                na0 = 1 + alpha; na1 = -2 * cw; na2 = 1 - alpha
            }
            "peaking" -> {
                nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a
                na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a
            }
            "lowshelf" -> {
                val sq = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) - (a - 1) * cw + sq)
                nb1 = 2 * a * ((a - 1) - (a + 1) * cw)
                nb2 = a * ((a + 1) - (a - 1) * cw - sq)
                na0 = (a + 1) + (a - 1) * cw + sq
                na1 = -2 * ((a - 1) + (a + 1) * cw)
                na2 = (a + 1) + (a - 1) * cw - sq
            }
            else -> { // highshelf
                val sq = 2 * sqrt(a) * alpha
                nb0 = a * ((a + 1) + (a - 1) * cw + sq)
                nb1 = -2 * a * ((a - 1) + (a + 1) * cw)
                nb2 = a * ((a + 1) + (a - 1) * cw - sq)
                na0 = (a + 1) - (a - 1) * cw + sq
                na1 = 2 * ((a - 1) - (a + 1) * cw)
                na2 = (a + 1) - (a - 1) * cw - sq
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0
        a1 = na1 / na0; a2 = na2 / na0
    }

    override fun p(x: Float): Float {
        val xd = x.toDouble()
        val y = b0 * xd + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = xd
        y2 = y1; y1 = y
        return y.toFloat()
    }
}

private class Gain(var g: Float = 1f) : Stage() {
    override fun p(x: Float): Float = x * g
}

private class SoftClip : Stage() {
    private var k = 0.0001f
    private var norm = tanh(k)

    fun drive(d: Double) {
        k = maxOf(0.0001, d).toFloat()
        norm = tanh(k)
    }

    override fun p(x: Float): Float = tanh(k * x.coerceIn(-1.5f, 1.5f)) / norm
}

private class Compressor(private val sr: Float, var thresholdDb: Double, private val ratio: Double, attackS: Double = 0.004, releaseS: Double = 0.16) : Stage() {
    private val atk = exp(-1.0 / (attackS * sr)).toFloat()
    private val rel = exp(-1.0 / (releaseS * sr)).toFloat()
    private var env = 0f

    override fun p(x: Float): Float {
        val a = abs(x)
        env = if (a > env) atk * env + (1 - atk) * a else rel * env + (1 - rel) * a
        val db = 20.0 * ln(maxOf(env, 1e-6f).toDouble()) / ln(10.0)
        if (db <= thresholdDb) return x
        val over = db - thresholdDb
        val reduction = over - over / ratio
        return x * dbToLin(-reduction)
    }
}

private class RingMod(private val sr: Float, private val freq: Double) : Stage() {
    private var ph = 0.0
    override fun p(x: Float): Float {
        ph += 2.0 * PI * freq / sr
        if (ph > 2.0 * PI) ph -= 2.0 * PI
        return x * sin(ph).toFloat()
    }
}

/** Dual-head modulated-delay pitch shifter (see voicefx.js). */
private class PitchShifter(private val sr: Float) : Stage() {
    private val period = 0.16
    private val fade = 0.03
    private val base = 0.008
    private val line = FloatArray((sr * 0.5f).toInt() + 8)
    private var w = 0
    private var phase = 0.0
    private var g = 0.0
    private var intrinsic = base

    fun setRatio(r0: Double) {
        val r = r0.coerceIn(0.6, 1.7)
        g = period * (1 - r)
        intrinsic = base + (if (g < 0) -g else 0.0)
    }

    private fun env(ph: Double): Double {
        val s = ph * period
        return when {
            s < fade -> s / fade
            s < period / 2 -> 1.0
            s < period / 2 + fade -> 1 - (s - period / 2) / fade
            else -> 0.0
        }
    }

    private fun read(delaySamples: Double): Float {
        var pos = w - delaySamples
        while (pos < 0) pos += line.size
        val i0 = pos.toInt() % line.size
        val i1 = (i0 + 1) % line.size
        val fr = (pos - pos.toInt()).toFloat()
        return line[i0] * (1 - fr) + line[i1] * fr
    }

    override fun p(x: Float): Float {
        line[w] = x
        var out = 0.0
        for (head in 0..1) {
            val ph = (phase + head * 0.5) % 1.0
            val d = (intrinsic + g * ph) * sr
            out += env(ph) * read(d.coerceIn(1.0, line.size - 2.0))
        }
        phase += 1.0 / (period * sr)
        if (phase >= 1.0) phase -= 1.0
        w = (w + 1) % line.size
        return out.toFloat()
    }
}

/** A preset instance for one channel. */
private abstract class Chain {
    abstract fun process(x: Float): Float
    abstract fun set(k: Double)
}

private class Noise(private val sr: Float) {
    private var last = 0f
    var level = 0f
    fun next(): Float {
        val white = (Math.random() * 2 - 1).toFloat()
        last = (last + 0.02f * white) / 1.02f
        return last * 3.5f * level
    }
}

private fun buildChain(id: String, sr: Float): Chain = when (id) {
    "natural" -> object : Chain() {
        val hp = Biquad(sr, "highpass", 70.0, 0.7)
        val pres = Biquad(sr, "peaking", 2800.0, 0.9, 0.0)
        override fun process(x: Float) = pres.p(hp.p(x))
        override fun set(k: Double) { pres.set(gainDb = 1.5 * k) }
    }
    "studio" -> object : Chain() {
        val hp = Biquad(sr, "highpass", 85.0, 0.8)
        val body = Biquad(sr, "lowshelf", 180.0, 0.707, 0.0)
        val pres = Biquad(sr, "peaking", 3200.0, 0.9, 0.0)
        val comp = Compressor(sr, -18.0, 4.0)
        val makeup = Gain()
        override fun process(x: Float) = makeup.p(comp.p(pres.p(body.p(hp.p(x)))))
        override fun set(k: Double) {
            body.set(gainDb = 1.5 * k)
            pres.set(gainDb = 3 * k)
            comp.thresholdDb = lerp(-6.0, -20.0, k)
            makeup.g = dbToLin(2.5 * k)
        }
    }
    "deep" -> object : Chain() {
        val ps = PitchShifter(sr)
        val shelf = Biquad(sr, "lowshelf", 160.0, 0.707, 0.0)
        val lift = Gain()
        override fun process(x: Float) = lift.p(shelf.p(ps.p(x)))
        override fun set(k: Double) {
            ps.setRatio(lerp(1.0, 0.8, k))
            shelf.set(gainDb = 4.5 * k)
            lift.g = dbToLin(1.5 * k)
        }
    }
    "bright" -> object : Chain() {
        val ps = PitchShifter(sr)
        val hp = Biquad(sr, "highpass", 110.0, 0.8)
        val air = Biquad(sr, "highshelf", 5500.0, 0.707, 0.0)
        override fun process(x: Float) = air.p(hp.p(ps.p(x)))
        override fun set(k: Double) {
            ps.setRatio(lerp(1.0, 1.12, k))
            air.set(gainDb = 4.5 * k)
        }
    }
    "radio" -> object : Chain() {
        val hp = Biquad(sr, "highpass", 90.0, 0.7)
        val lp = Biquad(sr, "lowpass", 12000.0, 0.7)
        val ws = SoftClip()
        val comp = Compressor(sr, -24.0, 8.0, 0.003, 0.12)
        val makeup = Gain()
        override fun process(x: Float) = makeup.p(comp.p(ws.p(lp.p(hp.p(x)))))
        override fun set(k: Double) {
            hp.set(freq = lerp(90.0, 300.0, k))
            lp.set(freq = lerp(12000.0, 3400.0, k))
            ws.drive(0.0001 + 1.6 * k)
            makeup.g = dbToLin(3.5 * k)
        }
    }
    "character" -> object : Chain() {
        val ps = PitchShifter(sr)
        val peak = Biquad(sr, "peaking", 1800.0, 1.2, 0.0)
        override fun process(x: Float) = peak.p(ps.p(x))
        override fun set(k: Double) {
            ps.setRatio(lerp(1.0, 1.3, k))
            peak.set(gainDb = 5 * k)
        }
    }
    "robotic" -> object : Chain() {
        val rm = RingMod(sr, 72.0)
        val hp = Biquad(sr, "highpass", 200.0, 0.8)
        val lp = Biquad(sr, "lowpass", 4200.0, 0.8)
        var dry = 1f
        var wet = 0f
        override fun process(x: Float) = x * dry + lp.p(hp.p(rm.p(x))) * wet
        override fun set(k: Double) {
            wet = (0.95 * k).toFloat()
            dry = (1 - 0.75 * k).toFloat()
        }
    }
    else -> object : Chain() { // vintage
        val hp = Biquad(sr, "highpass", 100.0, 1.0)
        val lp = Biquad(sr, "lowpass", 10000.0, 1.0)
        val lp2 = Biquad(sr, "lowpass", 12000.0, 0.7)
        val ws = SoftClip()
        val level = Gain()
        val noise = Noise(sr)
        override fun process(x: Float) = level.p(ws.p(lp2.p(lp.p(hp.p(x))))) + noise.next()
        override fun set(k: Double) {
            hp.set(freq = lerp(100.0, 420.0, k))
            lp.set(freq = lerp(10000.0, 2900.0, k))
            lp2.set(freq = lerp(12000.0, 3600.0, k))
            ws.drive(0.0001 + 2.4 * k)
            level.g = dbToLin(2 * k)
            noise.level = (0.006 * k).toFloat()
        }
    }
}

/* ---- the processor handed to libwebrtc ---- */

class VoiceFx {
    @Volatile var enabled = false
    @Volatile var presetId = "studio"
    @Volatile var intensity = 0.7f

    /** True once the capture hook is installed in this WebRTC build. */
    @Volatile var available = false
        private set

    @Volatile private var sampleRate = 48000
    @Volatile private var channels = 1
    private var chains: Array<Chain?> = arrayOfNulls(2)
    private var builtFor = ""
    private var builtIntensity = -1f
    private var builtRate = 0

    fun configure(enabled: Boolean, preset: String, intensity: Float) {
        this.enabled = enabled
        this.presetId = preset
        this.intensity = intensity
    }

    // ---- called by libwebrtc (through the reflection proxy) ----
    fun initialize(rate: Int, ch: Int) {
        sampleRate = if (rate > 0) rate else 48000
        channels = ch.coerceIn(1, 2)
        builtFor = ""
    }

    fun reset(rate: Int) {
        sampleRate = if (rate > 0) rate else 48000
        builtFor = ""
    }

    fun process(numBands: Int, numFrames: Int, buffer: ByteBuffer) {
        if (!enabled || numBands != 1) return
        try {
            val ch = channels
            val total = numFrames * ch
            if (total <= 0) return
            val bytes = buffer.capacity()
            val bo = buffer.order()
            buffer.order(ByteOrder.nativeOrder())
            ensureChains()
            val k = intensity.toDouble()
            when {
                bytes >= total * 4 && bytes < total * 8 -> {
                    var peak = 0f
                    val fb = buffer.asFloatBuffer()
                    for (i in 0 until total) peak = maxOf(peak, abs(fb.get(i)))
                    val scale = if (peak > 2f) 1f / 32768f else 1f
                    for (i in 0 until total) {
                        val c = i % ch
                        val chain = chains[c] ?: continue
                        val y = chain.process(fb.get(i) * scale)
                        fb.put(i, y.coerceIn(-1f, 1f) / scale)
                    }
                }
                bytes >= total * 2 -> {
                    val sb = buffer.asShortBuffer()
                    for (i in 0 until total) {
                        val c = i % ch
                        val chain = chains[c] ?: continue
                        val y = chain.process(sb.get(i) / 32768f)
                        sb.put(i, (y.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
                    }
                }
            }
            buffer.order(bo)
            if (k < 0) return
        } catch (e: Throwable) {
            Log.w("VoiceFx", "process failed", e)
            enabled = false
        }
    }

    private fun ensureChains() {
        val id = presetId
        val k = intensity
        if (id != builtFor || sampleRate != builtRate) {
            for (c in 0 until 2) chains[c] = buildChain(id, sampleRate.toFloat())
            builtFor = id
            builtRate = sampleRate
            builtIntensity = -1f
        }
        if (k != builtIntensity) {
            for (c in 0 until 2) chains[c]?.set(k.toDouble())
            builtIntensity = k
        }
    }

    /**
     * Installs this processor into a PeerConnectionFactory builder through
     * org.webrtc.ExternalAudioProcessingFactory (webrtc-sdk builds). Safe to call on any build.
     */
    fun install(builder: PeerConnectionFactory.Builder): Boolean {
        try {
            val factoryCls = Class.forName("org.webrtc.ExternalAudioProcessingFactory")
            val ifaceCls = Class.forName("org.webrtc.ExternalAudioProcessingFactory\$AudioProcessing")
            val factoryObj = factoryCls.getConstructor().newInstance()
            val me = this
            val proxy = Proxy.newProxyInstance(ifaceCls.classLoader, arrayOf(ifaceCls)) { p, m, args ->
                when (m.name) {
                    "initialize" -> me.initialize(args[0] as Int, args[1] as Int)
                    "reset" -> me.reset(args[0] as Int)
                    "process" -> me.process(args[0] as Int, args[1] as Int, args[2] as ByteBuffer)
                    "hashCode" -> return@newProxyInstance System.identityHashCode(p)
                    "equals" -> return@newProxyInstance (p === args[0])
                    "toString" -> return@newProxyInstance "VoiceFxProxy"
                }
                null
            }
            factoryCls.getMethod("setCapturePostProcessing", ifaceCls).invoke(factoryObj, proxy)
            builder.setAudioProcessingFactory(factoryObj as AudioProcessingFactory)
            available = true
        } catch (t: Throwable) {
            Log.i("VoiceFx", "external audio processing not available: $t")
            available = false
        }
        return available
    }
}
