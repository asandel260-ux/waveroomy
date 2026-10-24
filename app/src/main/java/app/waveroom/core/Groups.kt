package app.waveroom.core

import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject

/* ===========================================================================
 * Group chats. A group exists only as a copy on every member's phone; whoever
 * is connected pushes changes to the others, and phones catch up with each
 * other whenever two members connect (see DmHub).
 * ========================================================================= */

data class GroupMember(
    val uid: String,
    val name: String,
    val username: String,
    val avatar: Avatar,
    val role: String = "member",      // owner | admin | member
    val addedAt: Long = nowMs()
) {
    fun toJson(): JSONObject = JSONObject().put("uid", uid).put("name", name).put("user", username)
        .put("av", avatar.toJson()).put("role", role).put("at", addedAt)

    companion object {
        fun from(j: JSONObject) = GroupMember(j.str("uid"), j.str("name"), j.str("user"), Avatar.from(j.obj("av")), j.str("role", "member"), j.lng("at"))
    }
}

data class Group(
    val id: String,
    val name: String,
    val about: String = "",
    val ownerUid: String,
    val members: Map<String, GroupMember> = emptyMap(),
    val createdAt: Long = nowMs(),
    val rev: Long = 0L,
    val membersCanAdd: Boolean = true,
    val emoji: String = "👥",
    val hue: Int = 265,
    val left: Boolean = false
) {
    val chatId: String get() = "g:$id"

    fun roleOf(uid: String): String = members[uid]?.role ?: ""
    fun isAdmin(uid: String): Boolean = roleOf(uid).let { it == "owner" || it == "admin" }
    fun canAdd(uid: String): Boolean = members.containsKey(uid) && (membersCanAdd || isAdmin(uid))

    fun toJson(): JSONObject {
        val a = JSONArray()
        for (m in members.values) a.put(m.toJson())
        return JSONObject().put("id", id).put("name", name).put("about", about).put("owner", ownerUid)
            .put("members", a).put("at", createdAt).put("rev", rev).put("mca", membersCanAdd)
            .put("emoji", emoji).put("hue", hue).put("left", left)
    }

    companion object {
        fun from(j: JSONObject): Group {
            val m = LinkedHashMap<String, GroupMember>()
            j.arr("members")?.objects()?.forEach { val gm = GroupMember.from(it); m[gm.uid] = gm }
            return Group(
                j.str("id"), j.str("name"), j.str("about"), j.str("owner"), m, j.lng("at"), j.lng("rev"),
                j.bool("mca", true), j.str("emoji", "👥"), j.int("hue", 265), j.bool("left")
            )
        }
    }
}

class GroupStore {
    fun flush() = saver.flush()
    val groups = MutableStateFlow<Map<String, Group>>(emptyMap())
    private val saver = Debounced(400) { persist() }

    fun load() {
        val j = Docs.read("groups.json") ?: return
        val m = LinkedHashMap<String, Group>()
        j.arr("groups")?.objects()?.forEach { val g = Group.from(it); m[g.id] = g }
        groups.value = m
    }

    private fun persist() {
        val a = JSONArray()
        for (g in groups.value.values) a.put(g.toJson())
        Docs.write("groups.json", JSONObject().put("groups", a))
    }

    fun get(id: String): Group? = groups.value[id]

    fun put(g: Group) {
        val m = LinkedHashMap(groups.value)
        m[g.id] = g
        groups.value = m
        saver.poke()
    }

    fun remove(id: String) {
        val m = LinkedHashMap(groups.value)
        m.remove(id)
        groups.value = m
        saver.poke()
    }

    fun wipe() {
        groups.value = emptyMap()
        saver.poke()
    }
}

/** Where attachments live on this phone, one file per message id. */
object Media {
    private lateinit var dir: java.io.File

    fun init(ctx: android.content.Context) {
        dir = java.io.File(ctx.filesDir, "media")
        dir.mkdirs()
    }

    fun file(id: String): java.io.File = java.io.File(dir, id.filter { it.isLetterOrDigit() || it == '_' })
    fun has(id: String): Boolean = file(id).let { it.exists() && it.length() > 0 }
    fun part(id: String): java.io.File = java.io.File(dir, "$id.part".filter { it.isLetterOrDigit() || it == '_' || it == '.' })
    fun wipe() {
        dir.listFiles()?.forEach { it.delete() }
    }
}
