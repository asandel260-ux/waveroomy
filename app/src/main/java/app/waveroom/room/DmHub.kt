package app.waveroom.room

import app.waveroom.AppCore
import app.waveroom.core.DmMessage
import app.waveroom.core.Ids
import app.waveroom.core.bool
import app.waveroom.core.jsonOf
import app.waveroom.core.lng
import app.waveroom.core.nowMs
import app.waveroom.core.str
import app.waveroom.net.PeerLink
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject

/**
 * Direct messages travel over live peer links. With no server there is no mailbox:
 * messages to somebody who is not connected wait on this device and are delivered
 * the next time the two of you are linked (same room, or a fresh invite code).
 */
class DmHub(private val core: AppCore) {

    /** uid of the conversation currently on screen (so it is not counted as unread) */
    val openChat = MutableStateFlow<String?>(null)

    fun send(uid: String, text: String) {
        val t = text.trim().take(2000)
        if (t.isEmpty()) return
        val m = DmMessage(Ids.id("d_"), core.profile.uid, t, nowMs(), "pending")
        core.dms.addOutgoing(uid, m)
        flush(uid)
    }

    fun retry(uid: String, id: String) {
        core.dms.setStatus(uid, id, "pending")
        flush(uid)
    }

    fun flush(uid: String) {
        val link = core.mesh.linkForUid(uid) ?: return
        for (m in core.dms.pending(uid, core.profile.uid)) {
            link.send(jsonOf("t" to "dm", "id" to m.id, "text" to m.text, "at" to m.at).toString())
        }
    }

    fun onLinkAuthed(link: PeerLink) {
        flush(link.uid)
        if (core.social.following.value.contains(link.uid)) {
            link.send(jsonOf("t" to "fol", "on" to true).toString())
        }
    }

    fun sendFollow(uid: String, on: Boolean) {
        core.mesh.linkForUid(uid)?.send(jsonOf("t" to "fol", "on" to on).toString())
    }

    fun onMessage(link: PeerLink, m: JSONObject) {
        val uid = link.uid
        when (m.str("t")) {
            "fol" -> {
                core.social.setFollower(uid, m.bool("on"))
                if (m.bool("on")) core.toast("${core.contactName(uid)} followed you")
            }
            "dmack" -> {
                if (m.bool("ok")) core.dms.setStatus(uid, m.str("id"), "sent")
                else core.dms.setStatus(uid, m.str("id"), "failed", m.str("err", "Not delivered"))
            }
            "dm" -> {
                val id = m.str("id")
                val settings = core.settings.state.value
                val refusal = when {
                    core.social.blocked.value.contains(uid) -> "Not delivered"
                    settings.dmPolicy == "nobody" -> "${core.profile.profile.value?.displayName ?: "This person"} isn't accepting messages"
                    settings.dmPolicy == "following" && !core.social.following.value.contains(uid) ->
                        "${core.profile.profile.value?.displayName ?: "This person"} only accepts messages from people they follow"
                    else -> null
                }
                if (refusal != null) {
                    link.send(jsonOf("t" to "dmack", "id" to id, "ok" to false, "err" to refusal).toString())
                    return
                }
                val msg = DmMessage(id, uid, m.str("text").take(2000), m.lng("at", nowMs()), "recv")
                val watching = core.dms.convs.value[uid]?.messages?.any { it.id == id } == true
                core.dms.addIncoming(uid, msg, markUnread = openChat.value != uid)
                link.send(jsonOf("t" to "dmack", "id" to id, "ok" to true).toString())
                if (!watching && openChat.value != uid) {
                    core.sfx.play("message")
                    core.toast("${core.contactName(uid)}: ${msg.text.take(60)}")
                }
            }
        }
    }
}
