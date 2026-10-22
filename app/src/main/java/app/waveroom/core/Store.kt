package app.waveroom.core

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.Executors

/* ===========================================================================
 * Disk persistence. Small JSON documents under filesDir/data, written
 * atomically on a background thread.
 * ========================================================================= */

object Docs {
    private lateinit var dir: File
    private val io = Executors.newSingleThreadExecutor()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "data")
        dir.mkdirs()
        File(dir, "photos").mkdirs()
        File(dir, "rooms").mkdirs()
    }

    private fun file(name: String) = File(dir, name)

    fun readText(name: String): String? = try {
        val f = file(name)
        if (f.exists()) f.readText(Charsets.UTF_8) else null
    } catch (e: Exception) {
        null
    }

    fun read(name: String): JSONObject? {
        val t = readText(name) ?: return null
        return parseJsonOrNull(t)
    }

    fun write(name: String, json: JSONObject) {
        val text = json.toString()
        io.execute {
            try {
                val f = file(name)
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                tmp.writeText(text, Charsets.UTF_8)
                if (!tmp.renameTo(f)) {
                    f.delete()
                    tmp.renameTo(f)
                }
            } catch (e: Exception) {
                android.util.Log.w("Docs", "write failed: $name", e)
            }
        }
    }

    fun readBytes(name: String): ByteArray? = try {
        val f = file(name)
        if (f.exists()) f.readBytes() else null
    } catch (e: Exception) {
        null
    }

    fun writeBytes(name: String, bytes: ByteArray) {
        io.execute {
            try {
                val f = file(name)
                f.parentFile?.mkdirs()
                f.writeBytes(bytes)
            } catch (e: Exception) {
                android.util.Log.w("Docs", "writeBytes failed: $name", e)
            }
        }
    }

    fun delete(name: String) {
        io.execute {
            try {
                file(name).delete()
            } catch (e: Exception) {
            }
        }
    }

    fun wipeAll() {
        io.execute {
            try {
                dir.deleteRecursively()
                dir.mkdirs()
                File(dir, "photos").mkdirs()
                File(dir, "rooms").mkdirs()
            } catch (e: Exception) {
            }
        }
    }
}

/** Coalesces bursts of changes into one write. */
class Debounced(private val ms: Long, private val action: () -> Unit) {
    private var pending: Runnable? = null

    fun poke() {
        if (pending != null) return
        pending = later(ms) {
            pending = null
            action()
        }
    }

    fun flush() {
        cancelLater(pending)
        pending = null
        action()
    }
}

/* ===========================================================================
 * Cryptographic identity. No accounts, no server: a P-256 key pair *is* the
 * account. The uid is a hash of the public key, so nobody can claim another
 * person's uid without their private key.
 * ========================================================================= */

class Identity(val uid: String, val pubB64: String, private val privB64: String) {

    fun sign(data: ByteArray): String {
        val kf = KeyFactory.getInstance("EC")
        val priv = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(privB64, Base64.NO_WRAP)))
        val s = Signature.getInstance("SHA256withECDSA")
        s.initSign(priv)
        s.update(data)
        return Base64.encodeToString(s.sign(), Base64.NO_WRAP)
    }

    fun toJson(): JSONObject = JSONObject().put("uid", uid).put("pub", pubB64).put("priv", privB64)

    companion object {
        private const val B32 = "abcdefghijklmnopqrstuvwxyz234567"

        fun uidOf(pubB64: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(Base64.decode(pubB64, Base64.NO_WRAP))
            val sb = StringBuilder()
            var buffer = 0
            var bits = 0
            for (i in 0 until 10) {
                buffer = (buffer shl 8) or (digest[i].toInt() and 0xFF)
                bits += 8
                while (bits >= 5) {
                    sb.append(B32[(buffer shr (bits - 5)) and 31])
                    bits -= 5
                }
                buffer = buffer and 0xFFFF
            }
            return sb.toString()
        }

        fun generate(): Identity {
            val g = KeyPairGenerator.getInstance("EC")
            g.initialize(ECGenParameterSpec("secp256r1"))
            val kp = g.generateKeyPair()
            val pub = Base64.encodeToString(kp.public.encoded, Base64.NO_WRAP)
            val priv = Base64.encodeToString(kp.private.encoded, Base64.NO_WRAP)
            return Identity(uidOf(pub), pub, priv)
        }

        fun from(j: JSONObject): Identity? {
            val pub = j.str("pub")
            val priv = j.str("priv")
            if (pub.isEmpty() || priv.isEmpty()) return null
            return Identity(uidOf(pub), pub, priv)
        }

        fun verify(pubB64: String, data: ByteArray, sigB64: String): Boolean = try {
            val kf = KeyFactory.getInstance("EC")
            val pub = kf.generatePublic(X509EncodedKeySpec(Base64.decode(pubB64, Base64.NO_WRAP)))
            val s = Signature.getInstance("SHA256withECDSA")
            s.initVerify(pub)
            s.update(data)
            s.verify(Base64.decode(sigB64, Base64.NO_WRAP))
        } catch (e: Exception) {
            false
        }
    }
}

/* ===========================================================================
 * My profile
 * ========================================================================= */

data class MyProfile(
    val username: String,
    val displayName: String,
    val bio: String = "",
    val pronouns: String = "",
    val avatar: Avatar = Avatar(),
    val createdAt: Long = nowMs()
) {
    fun toJson(): JSONObject = JSONObject().put("username", username).put("displayName", displayName)
        .put("bio", bio).put("pronouns", pronouns).put("avatar", avatar.toJson()).put("createdAt", createdAt)

    companion object {
        fun from(j: JSONObject): MyProfile = MyProfile(
            j.str("username"), j.str("displayName"), j.str("bio"), j.str("pronouns"),
            Avatar.from(j.obj("avatar")), j.lng("createdAt", nowMs())
        )
    }
}

class ProfileStore {
    lateinit var identity: Identity
        private set
    val profile = MutableStateFlow<MyProfile?>(null)
    val photoVersion = MutableStateFlow(0)
    var photo: ByteArray? = null
        private set

    val uid: String get() = identity.uid

    fun load() {
        val idJson = Docs.read("identity.json")
        val id = if (idJson != null) Identity.from(idJson) else null
        if (id != null) {
            identity = id
        } else {
            identity = Identity.generate()
            Docs.write("identity.json", identity.toJson())
        }
        val pj = Docs.read("profile.json")
        profile.value = if (pj != null) MyProfile.from(pj) else null
        photo = Docs.readBytes("photo.jpg")
        photoVersion.value = photoVersion.value + 1
    }

    fun save(p: MyProfile) {
        profile.value = p
        Docs.write("profile.json", p.toJson())
    }

    fun setPhoto(bytes: ByteArray?) {
        photo = bytes
        if (bytes != null) Docs.writeBytes("photo.jpg", bytes) else Docs.delete("photo.jpg")
        photoVersion.value = photoVersion.value + 1
    }

    /** Whole identity + profile as a backup file. */
    fun exportBackup(): JSONObject {
        val j = JSONObject().put("kind", "waveroom-identity").put("v", 1)
            .put("identity", identity.toJson())
        profile.value?.let { j.put("profile", it.toJson()) }
        photo?.let { j.put("photo", Base64.encodeToString(it, Base64.NO_WRAP)) }
        return j
    }

    fun importBackup(j: JSONObject): Boolean {
        if (j.str("kind") != "waveroom-identity") return false
        val id = Identity.from(j.obj("identity") ?: return false) ?: return false
        identity = id
        Docs.write("identity.json", id.toJson())
        j.obj("profile")?.let { save(MyProfile.from(it)) }
        val ph = j.str("photo")
        setPhoto(if (ph.isNotEmpty()) Base64.decode(ph, Base64.NO_WRAP) else null)
        return true
    }
}

/* ===========================================================================
 * Settings
 * ========================================================================= */

data class Settings(
    val ptt: Boolean = false,
    val sounds: Boolean = true,
    val dmPolicy: String = "everyone",       // everyone | following | nobody
    val showOnline: Boolean = true,
    val nearby: Boolean = true,              // listen for rooms on the local network
    val useStun: Boolean = true,
    val customStun: String = "",
    val turnUrl: String = "",
    val turnUser: String = "",
    val turnPass: String = "",
    val fxEnabled: Boolean = false,
    val fxPreset: String = "studio",
    val fxIntensity: Float = 0.7f,
    val shareRes: Int = 720,
    val shareFps: Int = 30,
    val micDeviceId: Int = -1,
    val outputDeviceId: Int = -1,
    val chatSlidesOpen: Boolean = false,
    val accentColor: Int = 0,                // ARGB, 0 = default
    val bgColor: Int = 0                     // ARGB, 0 = default
    ,val animations: Boolean = true
    ,val useRelay: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ptt", ptt).put("sounds", sounds).put("dmPolicy", dmPolicy).put("showOnline", showOnline)
        .put("nearby", nearby).put("useStun", useStun).put("customStun", customStun)
        .put("turnUrl", turnUrl).put("turnUser", turnUser).put("turnPass", turnPass)
        .put("fxEnabled", fxEnabled).put("fxPreset", fxPreset).put("fxIntensity", fxIntensity.toDouble())
        .put("shareRes", shareRes).put("shareFps", shareFps)
        .put("micDeviceId", micDeviceId).put("outputDeviceId", outputDeviceId)
        .put("chatSlidesOpen", chatSlidesOpen)
        .put("accentColor", accentColor).put("bgColor", bgColor).put("animations", animations).put("useRelay", useRelay)

    companion object {
        fun from(j: JSONObject): Settings {
            val d = Settings()
            return Settings(
                ptt = j.bool("ptt", d.ptt), sounds = j.bool("sounds", d.sounds),
                dmPolicy = j.str("dmPolicy", d.dmPolicy), showOnline = j.bool("showOnline", d.showOnline),
                nearby = j.bool("nearby", d.nearby), useStun = j.bool("useStun", d.useStun),
                customStun = j.str("customStun"), turnUrl = j.str("turnUrl"), turnUser = j.str("turnUser"),
                turnPass = j.str("turnPass"), fxEnabled = j.bool("fxEnabled", d.fxEnabled),
                fxPreset = j.str("fxPreset", d.fxPreset), fxIntensity = j.flt("fxIntensity", d.fxIntensity),
                shareRes = j.int("shareRes", d.shareRes), shareFps = j.int("shareFps", d.shareFps),
                micDeviceId = j.int("micDeviceId", -1), outputDeviceId = j.int("outputDeviceId", -1),
                chatSlidesOpen = j.bool("chatSlidesOpen", d.chatSlidesOpen),
                accentColor = j.int("accentColor", 0), bgColor = j.int("bgColor", 0), animations = j.bool("animations", true), useRelay = j.bool("useRelay", true)
            )
        }
    }
}

class SettingsStore {
    val state = MutableStateFlow(Settings())
    private val saver = Debounced(300) { Docs.write("settings.json", state.value.toJson()) }

    fun load() {
        val j = Docs.read("settings.json")
        if (j != null) state.value = Settings.from(j)
    }

    fun update(block: (Settings) -> Settings) {
        state.value = block(state.value)
        saver.poke()
    }
}

/* ===========================================================================
 * People I have met: contacts, follows, blocks, cached photos
 * ========================================================================= */

class SocialStore {
    val contacts = MutableStateFlow<Map<String, Contact>>(emptyMap())
    val following = MutableStateFlow<Set<String>>(emptySet())
    /** People who told me (while connected) that they follow me. */
    val followers = MutableStateFlow<Set<String>>(emptySet())
    val blocked = MutableStateFlow<Set<String>>(emptySet())
    val photoVersion = MutableStateFlow(0)
    private val photos = HashMap<String, ByteArray>()
    private val saver = Debounced(600) { persist() }

    fun load() {
        val j = Docs.read("social.json") ?: return
        val m = LinkedHashMap<String, Contact>()
        j.arr("contacts")?.objects()?.forEach { val c = Contact.from(it); m[c.uid] = c }
        contacts.value = m
        following.value = j.arr("following")?.strings()?.toSet() ?: emptySet()
        followers.value = j.arr("followers")?.strings()?.toSet() ?: emptySet()
        blocked.value = j.arr("blocked")?.strings()?.toSet() ?: emptySet()
    }

    private fun persist() {
        val a = JSONArray()
        for (c in contacts.value.values) a.put(c.toJson())
        Docs.write(
            "social.json",
            JSONObject().put("contacts", a).put("following", jsonArrayOf(following.value))
                .put("followers", jsonArrayOf(followers.value)).put("blocked", jsonArrayOf(blocked.value))
        )
    }

    fun contact(uid: String): Contact? = contacts.value[uid]

    fun upsert(c: Contact) {
        val m = LinkedHashMap(contacts.value)
        val old = m[c.uid]
        m[c.uid] = if (old != null) c.copy(firstSeen = old.firstSeen) else c
        contacts.value = m
        saver.poke()
    }

    fun touchSeen(uid: String) {
        val c = contacts.value[uid] ?: return
        val m = LinkedHashMap(contacts.value)
        m[uid] = c.copy(lastSeen = nowMs())
        contacts.value = m
        saver.poke()
    }

    fun photoOf(uid: String): ByteArray? {
        val cached = photos[uid]
        if (cached != null) return cached
        val b = Docs.readBytes("photos/$uid.jpg")
        if (b != null) photos[uid] = b
        return b
    }

    fun setPhoto(uid: String, bytes: ByteArray?) {
        if (bytes == null || bytes.isEmpty()) return
        photos[uid] = bytes
        Docs.writeBytes("photos/$uid.jpg", bytes)
        photoVersion.value = photoVersion.value + 1
    }

    fun follow(uid: String, on: Boolean) {
        following.value = if (on) following.value + uid else following.value - uid
        saver.poke()
    }

    fun setFollower(uid: String, on: Boolean) {
        followers.value = if (on) followers.value + uid else followers.value - uid
        saver.poke()
    }

    fun block(uid: String, on: Boolean) {
        blocked.value = if (on) blocked.value + uid else blocked.value - uid
        if (on) following.value = following.value - uid
        saver.poke()
    }
}

/* ===========================================================================
 * Direct messages (delivered over live peer links; queued until then)
 * ========================================================================= */

class DmStore {
    val convs = MutableStateFlow<Map<String, Conversation>>(emptyMap())
    private val saver = Debounced(500) { persist() }

    fun load() {
        val j = Docs.read("dms.json") ?: return
        val m = LinkedHashMap<String, Conversation>()
        j.arr("convs")?.objects()?.forEach { val c = Conversation.from(it); m[c.uid] = c }
        convs.value = m
    }

    private fun persist() {
        val a = JSONArray()
        for (c in convs.value.values) a.put(c.toJson())
        Docs.write("dms.json", JSONObject().put("convs", a))
    }

    fun unreadTotal(): Int = convs.value.values.filter { !it.muted }.sumOf { it.unread }

    fun find(chat: String, id: String): DmMessage? = convs.value[chat]?.messages?.firstOrNull { it.id == id }

    /** Insert or replace a message (by id). */
    fun put(chat: String, msg: DmMessage, unreadBump: Boolean = false) {
        mutate(chat) { c ->
            val i = c.messages.indexOfFirst { it.id == msg.id }
            val list = if (i >= 0) c.messages.toMutableList().also { it[i] = msg } else (c.messages + msg).sortedBy { it.at }
            c.copy(messages = list, lastAt = maxOf(c.lastAt, msg.at), unread = c.unread + (if (unreadBump && i < 0) 1 else 0))
        }
    }

    fun edit(chat: String, id: String, block: (DmMessage) -> DmMessage) {
        val cur = find(chat, id) ?: return
        put(chat, block(cur))
    }

    fun setPinned(chat: String, id: String) = mutate(chat) { it.copy(pinned = id) }
    fun setMuted(chat: String, on: Boolean) = mutate(chat) { it.copy(muted = on) }
    fun ensure(chat: String) {
        if (convs.value.containsKey(chat)) return
        mutate(chat) { it }
    }

    fun removeMessage(chat: String, id: String) {
        mutate(chat) { c -> c.copy(messages = c.messages.filter { it.id != id }, pinned = if (c.pinned == id) "" else c.pinned) }
    }

    fun deleteChat(chat: String) {
        val m = LinkedHashMap(convs.value)
        m.remove(chat)
        convs.value = m
        saver.poke()
    }

    private fun mutate(uid: String, block: (Conversation) -> Conversation) {
        val m = LinkedHashMap(convs.value)
        val cur = m[uid] ?: Conversation(uid)
        m[uid] = block(cur)
        convs.value = m
        saver.poke()
    }

    fun addIncoming(uid: String, msg: DmMessage, markUnread: Boolean) {
        mutate(uid) { c ->
            if (c.messages.any { it.id == msg.id }) c
            else c.copy(messages = c.messages + msg, unread = c.unread + (if (markUnread) 1 else 0), lastAt = msg.at)
        }
    }

    fun addOutgoing(uid: String, msg: DmMessage) {
        mutate(uid) { c -> c.copy(messages = c.messages + msg, lastAt = msg.at) }
    }

    fun setStatus(uid: String, id: String, status: String, error: String = "") {
        mutate(uid) { c ->
            c.copy(messages = c.messages.map { if (it.id == id) it.copy(status = status, error = error) else it })
        }
    }

    fun markRead(uid: String) {
        val c = convs.value[uid] ?: return
        if (c.unread == 0) return
        mutate(uid) { it.copy(unread = 0) }
    }

    fun pending(uid: String, myUid: String): List<DmMessage> =
        convs.value[uid]?.messages?.filter { it.from == myUid && it.status == "pending" } ?: emptyList()

    fun wipe() {
        convs.value = emptyMap()
        saver.poke()
    }
}

/* ===========================================================================
 * Saved rooms
 * ========================================================================= */

class RoomsStore {
    val rooms = MutableStateFlow<List<SavedRoom>>(emptyList())
    private val saver = Debounced(400) { persist() }

    fun load() {
        val j = Docs.read("rooms.json") ?: return
        rooms.value = j.arr("rooms")?.objects()?.map { SavedRoom.from(it) } ?: emptyList()
    }

    private fun persist() {
        val a = JSONArray()
        for (r in rooms.value) a.put(r.toJson())
        Docs.write("rooms.json", JSONObject().put("rooms", a))
    }

    fun get(id: String): SavedRoom? = rooms.value.firstOrNull { it.meta.id == id }

    fun upsert(r: SavedRoom) {
        val list = rooms.value.toMutableList()
        val i = list.indexOfFirst { it.meta.id == r.meta.id }
        if (i >= 0) list[i] = r else list.add(0, r)
        rooms.value = list.sortedByDescending { it.lastActiveAt }
        saver.poke()
    }

    fun delete(id: String) {
        rooms.value = rooms.value.filter { it.meta.id != id }
        Docs.delete("rooms/$id.json")
        saver.poke()
    }

    /** Chat + board of a room, for restarting it later. */
    fun saveData(state: RoomState) {
        Docs.write("rooms/${state.meta.id}.json", state.toSavedJson())
    }

    fun loadData(id: String): JSONObject? = Docs.read("rooms/$id.json")
}

/* ===========================================================================
 * Moderation desk (local): reports, audit trail, counters
 * ========================================================================= */

class ModStore {
    val reports = MutableStateFlow<List<Report>>(emptyList())
    val audit = MutableStateFlow<List<AuditEntry>>(emptyList())
    val stats = MutableStateFlow(JSONObject())
    private val saver = Debounced(500) { persist() }

    fun load() {
        val j = Docs.read("mod.json") ?: return
        reports.value = j.arr("reports")?.objects()?.map { Report.from(it) } ?: emptyList()
        audit.value = j.arr("audit")?.objects()?.map { AuditEntry.from(it) } ?: emptyList()
        stats.value = j.obj("stats") ?: JSONObject()
    }

    private fun persist() {
        val r = JSONArray()
        for (x in reports.value.take(500)) r.put(x.toJson())
        val a = JSONArray()
        for (x in audit.value.take(1000)) a.put(x.toJson())
        Docs.write("mod.json", JSONObject().put("reports", r).put("audit", a).put("stats", stats.value))
    }

    fun addReport(r: Report) {
        reports.value = listOf(r) + reports.value
        saver.poke()
    }

    fun resolve(id: String, status: String, note: String) {
        reports.value = reports.value.map {
            if (it.id == id) it.copy(status = status, note = note, resolvedAt = nowMs()) else it
        }
        saver.poke()
    }

    fun log(action: String, room: RoomMeta, target: String, detail: String) {
        audit.value = listOf(AuditEntry(Ids.id("a_"), action, room.id, room.title, target, detail)) + audit.value
        saver.poke()
    }

    /** Count something per day, e.g. "joins" or "msgs"; kept for 30 days. */
    fun bump(counter: String, by: Int = 1) {
        val day = (nowMs() / 86_400_000L).toString()
        val s = JSONObject(stats.value.toString())
        val c = s.obj(counter) ?: JSONObject()
        c.put(day, c.optInt(day, 0) + by)
        val cutoff = nowMs() / 86_400_000L - 30
        val keys = ArrayList<String>()
        val it = c.keys()
        while (it.hasNext()) keys.add(it.next())
        for (k in keys) if ((k.toLongOrNull() ?: 0L) < cutoff) c.remove(k)
        s.put(counter, c)
        stats.value = s
        saver.poke()
    }

    fun series(counter: String, days: Int): List<Int> {
        val today = nowMs() / 86_400_000L
        val c = stats.value.obj(counter)
        val out = ArrayList<Int>()
        for (i in days - 1 downTo 0) out.add(c?.optInt((today - i).toString(), 0) ?: 0)
        return out
    }

    fun wipe() {
        reports.value = emptyList()
        audit.value = emptyList()
        stats.value = JSONObject()
        saver.poke()
    }
}
