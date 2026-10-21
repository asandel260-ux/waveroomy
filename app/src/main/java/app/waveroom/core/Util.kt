package app.waveroom.core

import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom

/* ---------------------------------------------------------------------------
 * Threading: all app logic runs on the main thread. WebRTC callbacks arrive on
 * native threads and hop over with onMain { }.
 * ------------------------------------------------------------------------- */

val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }

fun onMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
}

/** Schedule [block] on the main thread; the returned Runnable can be passed to [cancelLater]. */
fun later(ms: Long, block: () -> Unit): Runnable {
    val r = Runnable { block() }
    mainHandler.postDelayed(r, ms)
    return r
}

fun cancelLater(r: Runnable?) {
    if (r != null) mainHandler.removeCallbacks(r)
}

fun nowMs(): Long = System.currentTimeMillis()

/* ---------------------------------------------------------------------------
 * Ids
 * ------------------------------------------------------------------------- */

object Ids {
    private val rnd = SecureRandom()
    private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

    fun rand(n: Int): String {
        val sb = StringBuilder(n)
        for (i in 0 until n) sb.append(ALPHABET[rnd.nextInt(ALPHABET.length)])
        return sb.toString()
    }

    fun sid(): String = rand(10)
    fun id(prefix: String): String = prefix + rand(10)
    fun nonce(): String = rand(24)
}

/* ---------------------------------------------------------------------------
 * org.json helpers
 * ------------------------------------------------------------------------- */

fun JSONObject.str(k: String, d: String = ""): String =
    if (has(k) && !isNull(k)) optString(k, d) else d

fun JSONObject.int(k: String, d: Int = 0): Int =
    if (has(k) && !isNull(k)) optInt(k, d) else d

fun JSONObject.lng(k: String, d: Long = 0L): Long =
    if (has(k) && !isNull(k)) optLong(k, d) else d

fun JSONObject.dbl(k: String, d: Double = 0.0): Double =
    if (has(k) && !isNull(k)) optDouble(k, d) else d

fun JSONObject.flt(k: String, d: Float = 0f): Float =
    if (has(k) && !isNull(k)) optDouble(k, d.toDouble()).toFloat() else d

fun JSONObject.bool(k: String, d: Boolean = false): Boolean =
    if (has(k) && !isNull(k)) optBoolean(k, d) else d

fun JSONObject.obj(k: String): JSONObject? =
    if (has(k) && !isNull(k)) optJSONObject(k) else null

fun JSONObject.arr(k: String): JSONArray? =
    if (has(k) && !isNull(k)) optJSONArray(k) else null

fun JSONObject.strOrNull(k: String): String? =
    if (has(k) && !isNull(k)) optString(k) else null

fun JSONArray.strings(): List<String> {
    val out = ArrayList<String>(length())
    for (i in 0 until length()) out.add(optString(i))
    return out
}

fun JSONArray.objects(): List<JSONObject> {
    val out = ArrayList<JSONObject>(length())
    for (i in 0 until length()) {
        val o = optJSONObject(i)
        if (o != null) out.add(o)
    }
    return out
}

fun jsonArrayOf(items: Collection<String>): JSONArray {
    val a = JSONArray()
    for (s in items) a.put(s)
    return a
}

fun jsonOf(vararg pairs: Pair<String, Any?>): JSONObject {
    val o = JSONObject()
    for ((k, v) in pairs) {
        o.put(k, v ?: JSONObject.NULL)
    }
    return o
}

fun parseJsonOrNull(s: String): JSONObject? = try {
    JSONObject(s)
} catch (e: Exception) {
    null
}

/* ---------------------------------------------------------------------------
 * Rate limiter (fixed window, per key)
 * ------------------------------------------------------------------------- */

class RateLimiter {
    private class Bucket(var start: Long, var count: Int)

    private val buckets = HashMap<String, Bucket>()

    fun allow(key: String, max: Int, windowMs: Long): Boolean {
        val t = nowMs()
        val b = buckets[key]
        if (b == null || t - b.start > windowMs) {
            buckets[key] = Bucket(t, 1)
            return true
        }
        b.count++
        return b.count <= max
    }
}

/* ---------------------------------------------------------------------------
 * Formatting
 * ------------------------------------------------------------------------- */

fun timeAgo(ts: Long): String {
    val s = maxOf(1L, (nowMs() - ts) / 1000)
    if (s < 60) return "just now"
    val m = s / 60
    if (m < 60) return "${m}m ago"
    val h = m / 60
    if (h < 24) return "${h}h ago"
    val d = h / 24
    if (d < 30) return "${d}d ago"
    return java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault()).format(java.util.Date(ts))
}

fun fmtClock(ts: Long): String =
    java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

fun fmtDuration(totalSeconds: Double): String {
    val t = maxOf(0, totalSeconds.toInt())
    val h = t / 3600
    val m = (t % 3600) / 60
    val s = t % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
