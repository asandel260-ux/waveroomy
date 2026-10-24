package app.waveroom

import android.net.Uri
import app.waveroom.core.Avatar
import app.waveroom.core.Contact
import app.waveroom.core.Docs
import app.waveroom.core.Identity
import app.waveroom.core.arr
import app.waveroom.core.jsonOf
import app.waveroom.core.lng
import app.waveroom.core.later
import app.waveroom.core.mainHandler
import app.waveroom.core.nowMs
import app.waveroom.core.obj
import app.waveroom.core.objects
import app.waveroom.core.str
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** A person's public business card: who they are and the key that proves it. */
data class Card(val uid: String, val pub: String, val username: String, val name: String, val avatar: Avatar, val ts: Long, val signed: Boolean)

/**
 * Finding people without scanning anything and without a server of ours.
 *
 * - Everybody can post a signed "card" in a mailbox named after their @username.
 * - Everybody has an inbox mailbox named after their id. A friend request is a signed card dropped there.
 * - When a request is accepted both phones derive the friendship secret from their keys (ECDH), and the
 *   normal reconnect machinery (Rendezvous) brings the two phones together.
 * The mailboxes live on a public message board (see Rendezvous); the board never sees anything but public cards.
 */
object Discovery {
    data class FriendRequest(val card: Card, val at: Long)

    val requests = MutableStateFlow<List<FriendRequest>>(emptyList())
    val results = MutableStateFlow<List<Card>>(emptyList())
    val searching = MutableStateFlow(false)
    val searchNote = MutableStateFlow("")

    private fun sha(s: String) = MessageDigest.getInstance("SHA-256").digest(s.toByteArray())
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    fun userTopic(username: String) = "homit-u-" + hex(sha("homit-user:" + username.trim().trimStart('@').lowercase())).take(32)
    fun inboxTopic(uid: String) = "homit-i-" + hex(sha("homit-inbox:$uid")).take(32)

    fun init() {
        Docs.read("requests.json")?.arr("r")?.objects()?.forEach { j ->
            cardOf(j.obj("card"), true)?.let { requests.value = requests.value + FriendRequest(it, j.lng("at")) }
        }
        later(8000) { publishLoop() }
    }

    private fun saveRequests() {
        val a = JSONArray()
        for (r in requests.value) a.put(jsonOf("card" to cardJson(r.card), "at" to r.at))
        Docs.write("requests.json", JSONObject().put("r", a))
    }

    /* ------------------------------ cards ------------------------------ */

    private fun payload(uid: String, user: String, name: String, ts: Long) = "homit-card|$uid|$user|$name|$ts".toByteArray()

    private fun cardJson(c: Card) = jsonOf("uid" to c.uid, "pub" to c.pub, "user" to c.username, "name" to c.name, "av" to c.avatar.toJson(), "ts" to c.ts)
        .also { if (c.signed) it.put("sig", c.sigOrEmpty()) }

    private val sigCache = HashMap<String, String>()
    private fun Card.sigOrEmpty() = sigCache[uid + ts] ?: ""

    /** My own card, freshly signed. */
    fun myCard(): JSONObject? {
        val p = AppCore.profile.profile.value ?: return null
        val ts = nowMs()
        val user = p.username
        val name = p.displayName.take(60)
        val sig = AppCore.profile.identity.sign(payload(AppCore.profile.uid, user, name, ts))
        return jsonOf("uid" to AppCore.profile.uid, "pub" to AppCore.profile.identity.pubB64, "user" to user, "name" to name, "av" to p.avatar.toJson(), "ts" to ts, "sig" to sig)
    }

    /** Parse and verify a card. With [needSig] false (a pasted link) the key is taken on trust. */
    private fun cardOf(j: JSONObject?, needSig: Boolean): Card? {
        j ?: return null
        val pub = j.str("pub")
        val uid = j.str("uid")
        if (pub.length !in 40..300 || uid != Identity.uidOf(pub)) return null
        val user = j.str("user").take(40)
        val name = j.str("name").take(60)
        val ts = j.lng("ts")
        val sig = j.str("sig")
        if (needSig) {
            if (!Identity.verify(pub, payload(uid, user, name, ts), sig)) return null
            sigCache[uid + ts] = sig
        }
        return Card(uid, pub, user, name, Avatar.from(j.obj("av")), ts, needSig)
    }

    /* ------------------------------ publishing ------------------------------ */

    private fun publishLoop() {
        try {
            publishCard()
        } catch (e: Exception) {
        }
        later(25 * 60_000L) { publishLoop() }
    }

    fun publishCard() {
        val st = AppCore.settings.state.value
        if (!st.reconnect || st.findMe != "everyone") return
        val c = myCard() ?: return
        Rendezvous.post(userTopic(c.str("user")), c.toString()) { }
    }

    /** Called when the profile changes so searches see the new name quickly. */
    fun profileChanged() = later(1500) { publishCard() }

    /* ------------------------------ link ------------------------------ */

    fun myLink(): String? {
        val p = AppCore.profile.profile.value ?: return null
        return "waveroom://add?k=" + Uri.encode(AppCore.profile.identity.pubB64) + "&u=" + Uri.encode(p.username) + "&n=" + Uri.encode(p.displayName)
    }

    fun cardFromLink(text: String): Card? = try {
        val i = text.indexOf("waveroom://add")
        if (i < 0) null else {
            val u = Uri.parse(text.substring(i).takeWhile { !it.isWhitespace() })
            val pub = u.getQueryParameter("k") ?: ""
            cardOf(jsonOf("uid" to Identity.uidOf(pub), "pub" to pub, "user" to (u.getQueryParameter("u") ?: ""), "name" to (u.getQueryParameter("n") ?: "")), false)
        }
    } catch (e: Exception) {
        null
    }

    /* ------------------------------ search ------------------------------ */

    fun search(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        results.value = emptyList()
        searchNote.value = ""
        cardFromLink(q)?.let { results.value = listOf(it); return }
        val name = q.trimStart('@').lowercase()
        if (name.length < 3) { searchNote.value = "Type at least 3 letters of the @username."; return }
        searching.value = true
        Thread {
            val found = LinkedHashMap<String, Card>()
            var failed = false
            try {
                val lines = Rendezvous.poll(userTopic(name))
                for (line in lines) {
                    val c = cardOf(parse(line.optString("message")), true) ?: continue
                    if (c.username.lowercase() != name || c.uid == AppCore.profile.uid) continue
                    val old = found[c.uid]
                    if (old == null || old.ts < c.ts) found[c.uid] = c
                }
            } catch (e: Exception) {
                failed = true
            }
            mainHandler.post {
                searching.value = false
                results.value = found.values.sortedByDescending { it.ts }
                searchNote.value = when {
                    failed -> "Couldn't reach the message board. Check your connection, or ask them for their link."
                    found.isEmpty() -> "Nobody found. They may be offline for a while, or hidden. Ask for their link."
                    else -> ""
                }
            }
        }.also { it.isDaemon = true; it.start() }
    }

    private fun parse(s: String): JSONObject? = try { JSONObject(s) } catch (e: Exception) { null } catch (e: StackOverflowError) { null }

    /* ------------------------------ requests ------------------------------ */

    fun sendRequest(card: Card) {
        val core = AppCore
        if (card.uid == core.profile.uid) return
        if (core.social.blocked.value.contains(card.uid)) { core.toast("You blocked this person."); return }
        if (core.social.contact(card.uid) == null) {
            core.social.upsert(Contact(card.uid, card.username, card.name, card.avatar, "", "", card.pub, 0L))
        }
        core.pairFor(card.uid, card.pub)?.let { core.social.setPair(card.uid, it) }
        val me = myCard() ?: return
        val note = jsonOf("t" to "req", "to" to card.uid, "card" to me, "ts" to nowMs())
        Rendezvous.post(inboxTopic(card.uid), note.toString()) { ok ->
            core.toast(if (ok) "Request sent to ${card.name}" else "Couldn't reach the message board. Try again in a moment.")
        }
        Rendezvous.kick()
    }

    /** A message arrived in my inbox. */
    fun onInbox(message: String) {
        if (AppCore.settings.state.value.findMe == "nobody") return
        val j = parse(message) ?: return
        if (j.str("t") != "req" || j.str("to") != AppCore.profile.uid) return
        if (kotlin.math.abs(nowMs() - j.lng("ts")) > 13 * 3600_000L) return
        val c = cardOf(j.obj("card"), true) ?: return
        if (c.uid == AppCore.profile.uid || AppCore.social.blocked.value.contains(c.uid)) return
        val known = AppCore.social.contact(c.uid)
        if (known != null && known.pair.isNotEmpty()) {
            // they asked, and I had already asked them (or we are friends): that is a yes
            if (AppCore.pairFor(c.uid, c.pub) != null) Rendezvous.kick()
            return
        }
        if (requests.value.any { it.card.uid == c.uid }) return
        requests.value = (requests.value + FriendRequest(c, nowMs())).takeLast(30)
        saveRequests()
        AppCore.toast("${c.name} wants to connect")
        AppCore.sfx.play("join")
    }

    fun accept(r: FriendRequest) {
        val c = r.card
        AppCore.social.upsert(Contact(c.uid, c.username, c.name, c.avatar, "", "", c.pub, 0L))
        AppCore.pairFor(c.uid, c.pub)?.let { AppCore.social.setPair(c.uid, it) }
        requests.value = requests.value.filter { it.card.uid != c.uid }
        saveRequests()
        AppCore.toast("${c.name} added. Connecting…")
        Rendezvous.kick()
    }

    fun decline(r: FriendRequest) {
        requests.value = requests.value.filter { it.card.uid != r.card.uid }
        saveRequests()
    }
}
