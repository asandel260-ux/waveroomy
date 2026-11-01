package app.waveroom

import android.util.Base64
import app.waveroom.core.*
import app.waveroom.net.PeerLink
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps my own devices (this phone and the Windows app signed in with the same identity) in step over a
 * "sync" link: profile (newest edit wins), contacts, people I follow / who follow me, blocks.
 */
class DeviceSync(private val c0: AppCore) {
    private val links = HashSet<PeerLink>()
    private var applying = false
    private var ts: Long = Docs.read("sync.json")?.lng("ts") ?: 0L

    private fun stamp(t: Long) {
        ts = t
        Docs.write("sync.json", JSONObject().put("ts", t))
    }

    fun profileChanged() {
        if (applying) return
        stamp(nowMs())
        pushAll()
    }

    private fun snapshot(): JSONObject {
        val a = JSONArray()
        for (c in c0.social.contacts.value.values) a.put(c.toJson())
        val j = JSONObject().put("t", "sy").put("ts", ts).put("contacts", a)
            .put("following", jsonArrayOf(c0.social.following.value))
            .put("followers", jsonArrayOf(c0.social.followers.value))
            .put("blocked", jsonArrayOf(c0.social.blocked.value))
        c0.profile.profile.value?.let { j.put("profile", it.toJson()) }
        c0.profile.photo?.let { j.put("photo", Base64.encodeToString(it, Base64.NO_WRAP)) }
        return j
    }

    private fun pushAll() {
        if (links.isEmpty()) return
        val s = snapshot().toString()
        for (l in links) l.send(s)
    }

    fun onLinkAuthed(link: PeerLink) {
        links.add(link)
        link.send(snapshot().toString())
        c0.toast("Synced with your other device")
    }

    fun onLinkGone(link: PeerLink) { links.remove(link) }

    fun onMessage(m: JSONObject) {
        if (m.str("t") != "sy") return
        applying = true
        try {
            val rts = m.lng("ts")
            val pj = m.obj("profile")
            if (pj != null && rts > ts) {
                c0.profile.save(MyProfile.from(pj), fromSync = true)
                val ph = m.str("photo")
                c0.profile.setPhoto(if (ph.isNotEmpty()) Base64.decode(ph, Base64.NO_WRAP) else null)
                stamp(rts)
            }
            m.arr("contacts")?.objects()?.forEach {
                val c = Contact.from(it)
                if (c.uid.isNotEmpty() && c.uid != c0.profile.uid && c0.social.contact(c.uid) == null) c0.social.upsert(c)
            }
            c0.social.following.value = c0.social.following.value + (m.arr("following")?.strings() ?: emptyList())
            c0.social.followers.value = c0.social.followers.value + (m.arr("followers")?.strings() ?: emptyList())
            c0.social.blocked.value = c0.social.blocked.value + (m.arr("blocked")?.strings() ?: emptyList())
            c0.social.flush()
        } finally { applying = false }
    }
}
