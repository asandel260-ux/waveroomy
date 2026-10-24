package app.waveroom

import android.util.Base64
import android.util.Log
import app.waveroom.core.jsonOf
import app.waveroom.core.later
import app.waveroom.core.mainHandler
import app.waveroom.core.nowMs
import app.waveroom.core.str
import app.waveroom.net.Sdp
import app.waveroom.core.lng
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Lets saved friends find each other again from anywhere, without any account or server of ours.
 *
 * Every pair of friends shares a random secret (swapped over their first, verified link). From it both
 * derive a private "mailbox" name on a public message board (ntfy.sh unless changed) and an AES key.
 * Only encrypted notes ("knock", "offer", "answer") ever pass through it; the audio and chat still go
 * phone to phone. The lower user id always makes the offer, the higher one answers, so two phones that
 * knock at the same moment never collide.
 */
object Rendezvous {
    val status = MutableStateFlow("starting")

    private const val DEFAULT_URL = "https://ntfy.sh"
    private val rnd = SecureRandom()

    private class Peer(val uid: String, val key: ByteArray, val topic: String)

    private var peers: Map<String, Peer> = emptyMap()      // topic -> peer
    private var subKey = ""
    @Volatile private var sub: Thread? = null
    @Volatile private var subGen = 0
    @Volatile private var lastEventTime = 0L
    private val seenIds = LinkedHashSet<String>()
    private val partial = HashMap<String, Array<String?>>()
    private val handled = LinkedHashSet<String>()
    private val tries = HashMap<String, Int>()
    private val lastOffer = HashMap<String, Long>()
    private var started = false

    fun start() {
        if (started) return
        started = true
        later(4000) { tick() }
    }

    /** Call when a link to a friend ends so we start looking for them again. */
    fun lost(uid: String) {
        tries.remove(uid)
        later(2500) { tick(once = true) }
    }

    fun kick() { tries.clear(); later(500) { tick(once = true) } }

    /** The app came back to the screen: look for everyone again. */
    fun resume() = kick()

    /** Fire-and-forget POST of a small text to a mailbox. */
    fun post(topic: String, body: String, done: (Boolean) -> Unit) {
        val base = baseUrl()
        Thread {
            var ok = false
            try {
                val c = URL("$base/$topic").openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.doOutput = true
                c.connectTimeout = 8000
                c.readTimeout = 8000
                c.outputStream.use { it.write(body.toByteArray()) }
                val code = c.responseCode
                c.disconnect()
                ok = code in 200..299
                if (code == 429) mainHandler.post { status.value = "the message board is limiting us, will retry later" }
            } catch (e: Exception) {
            }
            mainHandler.post { done(ok) }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Read what a mailbox still holds (blocking: call from a thread). */
    fun poll(topic: String): List<JSONObject> {
        val c = URL("${baseUrl()}/$topic/json?poll=1&since=13h").openConnection() as HttpURLConnection
        c.connectTimeout = 8000
        c.readTimeout = 10000
        if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
        val out = ArrayList<JSONObject>()
        BufferedReader(InputStreamReader(c.inputStream)).use { r ->
            var n = 0
            while (n++ < 200) {
                val line = r.readLine() ?: break
                try { out.add(JSONObject(line)) } catch (e: Exception) {}
            }
        }
        return out
    }

    private fun enabled() = AppCore.settings.state.value.reconnect

    private fun baseUrl(): String {
        val u = AppCore.settings.state.value.rvUrl.trim().trimEnd('/')
        return if (u.startsWith("http://") || u.startsWith("https://")) u else DEFAULT_URL
    }

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private fun friends(): List<Peer> {
        val out = ArrayList<Peer>()
        for (c in AppCore.social.contacts.value.values) {
            if (c.pair.isEmpty() || AppCore.social.blocked.value.contains(c.uid)) continue
            out.add(Peer(c.uid, sha("homit-rv-key" + c.pair), "homit-" + hex(sha("homit-rv-topic" + c.pair)).take(40)))
        }
        return out
    }

    private fun tick(once: Boolean = false) {
        try {
            if (!enabled()) {
                status.value = "off"
                stopSub()
            } else {
                val fl = friends()
                ensureSub(fl)
                val me = AppCore.profile.uid
                for (p in fl) {
                    if (AppCore.connected.value.contains(p.uid)) { tries.remove(p.uid); continue }
                    val n = tries[p.uid] ?: 0
                    // back off: every tick for a while, then every few minutes
                    if (n >= 12) continue   // stop knocking after ~10 minutes until the app is used again (saves the board's quota)
                    tries[p.uid] = n + 1
                    if (me > p.uid) send(p, jsonOf("t" to "knock"))
                    else if (n < 3 || nowMs() - (lastOffer[p.uid] ?: 0L) > 240_000) offerTo(p)
                }
            }
        } catch (e: Exception) {
            Log.w("Rv", "tick: $e")
        }
        if (!once) later(45_000) { tick() }
    }

    private fun offerTo(p: Peer) {
        if (nowMs() - (lastOffer[p.uid] ?: 0L) < 60_000) return
        lastOffer[p.uid] = nowMs()
        AppCore.createInviteCode("dm") { _, code ->
            if (code != null) send(p, jsonOf("t" to "offer", "code" to code))
        }
    }

    /* ------------------------------ crypto ------------------------------ */

    private fun seal(key: ByteArray, plain: String): String {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        return Base64.encodeToString(iv + c.doFinal(plain.toByteArray()), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    private fun open(key: ByteArray, b64: String): String? = try {
        val raw = Base64.decode(b64, Base64.NO_WRAP or Base64.URL_SAFE)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        String(c.doFinal(raw, 12, raw.size - 12))
    } catch (e: Exception) {
        null
    }

    /* ------------------------------ publish ------------------------------ */

    private fun send(p: Peer, note: JSONObject) {
        note.put("from", AppCore.profile.uid).put("ts", nowMs())
        val sealed = seal(p.key, note.toString())
        val id = Base64.encodeToString(ByteArray(6).also { rnd.nextBytes(it) }, Base64.NO_WRAP or Base64.URL_SAFE)
        val chunks = sealed.chunked(2800)
        val base = baseUrl()
        Thread {
            try {
                for ((i, d) in chunks.withIndex()) {
                    val body = jsonOf("m" to id, "n" to chunks.size, "i" to i, "d" to d).toString()
                    val c = URL("$base/${p.topic}").openConnection() as HttpURLConnection
                    c.requestMethod = "POST"
                    c.doOutput = true
                    c.connectTimeout = 8000
                    c.readTimeout = 8000
                    c.setRequestProperty("Cache", "yes")
                    c.outputStream.use { it.write(body.toByteArray()) }
                    val code = c.responseCode
                    c.disconnect()
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                }
            } catch (e: Exception) {
                mainHandler.post { status.value = "can't reach the message board" }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /* ------------------------------ subscribe ------------------------------ */

    private fun stopSub() {
        subGen++
        sub = null
        subKey = ""
    }

    private var inbox = ""

    private fun ensureSub(fl: List<Peer>) {
        peers = fl.associateBy { it.topic }
        inbox = if (AppCore.settings.state.value.findMe != "nobody") Discovery.inboxTopic(AppCore.profile.uid) else ""
        val all = fl.map { it.topic } + (if (inbox.isNotEmpty()) listOf(inbox) else emptyList())
        if (all.isEmpty()) {
            status.value = "nobody to look for yet"
            stopSub(); return
        }
        val key = baseUrl() + "|" + all.sorted().joinToString(",")
        if (key == subKey && sub?.isAlive == true) return
        subKey = key
        val gen = ++subGen
        val base = baseUrl()
        val topics = all.sorted().joinToString(",")
        val t = Thread {
            var wait = 3000L
            while (gen == subGen) {
                try {
                    val since = if (lastEventTime > 0) lastEventTime.toString() else "4m"
                    val c = URL("$base/$topics/sse?since=$since").openConnection() as HttpURLConnection
                    c.connectTimeout = 10000
                    c.readTimeout = 100_000
                    if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                    mainHandler.post { if (gen == subGen) status.value = if (peers.isEmpty()) "listening for friend requests" else "listening for ${peers.size} friend(s)" }
                    wait = 3000L
                    BufferedReader(InputStreamReader(c.inputStream)).use { r ->
                        while (gen == subGen) {
                            val line = r.readLine() ?: break
                            if (!line.startsWith("data:")) continue
                            val j = try { JSONObject(line.removePrefix("data:").trim()) } catch (e: Exception) { continue }
                            if (j.optString("event") != "message") continue
                            val tm = j.optLong("time", 0L)
                            if (tm > lastEventTime) lastEventTime = tm
                            mainHandler.post { onEvent(j.optString("id"), j.optString("topic"), j.optString("message")) }
                        }
                    }
                } catch (e: Exception) {
                    mainHandler.post { if (gen == subGen) status.value = "can't reach the message board" }
                }
                if (gen != subGen) break
                try { Thread.sleep(wait) } catch (e: Exception) { break }
                wait = minOf(wait * 2, 60_000L)
            }
        }
        t.isDaemon = true
        sub = t
        t.start()
    }

    /* ------------------------------ receive ------------------------------ */

    private fun onEvent(evId: String, topic: String, message: String) {
        if (evId.isNotEmpty() && !seenIds.add(evId)) return
        if (seenIds.size > 400) seenIds.remove(seenIds.first())
        if (topic == inbox && inbox.isNotEmpty()) { Discovery.onInbox(message); return }
        val p = peers[topic] ?: return
        val w = try { JSONObject(message) } catch (e: Exception) { return }
        val m = w.optString("m"); val n = w.optInt("n", 1); val i = w.optInt("i", 0)
        if (n < 1 || n > 8 || i < 0 || i >= n) return
        val arr = partial.getOrPut(m) { arrayOfNulls(n) }
        if (arr.size != n) return
        arr[i] = w.optString("d")
        if (arr.any { it == null }) { if (partial.size > 40) partial.remove(partial.keys.first()); return }
        partial.remove(m)
        val plain = open(p.key, arr.joinToString("")) ?: return
        val note = try { JSONObject(plain) } catch (e: Exception) { return }
        if (note.str("from") != p.uid) return
        if (kotlin.math.abs(nowMs() - note.lng("ts")) > 4 * 60_000L) return
        if (AppCore.connected.value.contains(p.uid)) return
        val me = AppCore.profile.uid
        when (note.str("t")) {
            "knock" -> if (me < p.uid) offerTo(p)
            "offer" -> if (me > p.uid && Sdp.unpack(note.str("code"))?.str("p") == "dm" && handled.add(note.str("code").takeLast(40))) {
                AppCore.handleCode(note.str("code"), autoDeliver = false) { res ->
                    if (res is CodeResult.Answer) send(p, jsonOf("t" to "answer", "code" to res.code))
                }
            }
            "answer" -> if (me < p.uid && handled.add(note.str("code").takeLast(40))) {
                AppCore.handleCode(note.str("code")) { }
            }
        }
        if (handled.size > 200) handled.remove(handled.first())
    }
}
