package app.waveroom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.AVATAR_EMOJIS
import app.waveroom.core.Avatar
import app.waveroom.core.MyProfile
import app.waveroom.core.parseJsonOrNull
import app.waveroom.ui.components.AvatarView
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrIcon
import app.waveroom.ui.theme.WR

fun cleanHandle(s: String): String = s.lowercase().filter { it.isLetterOrDigit() && it.code < 128 || it == '_' }.take(20)

val HUES = listOf(265, 200, 160, 120, 40, 10, 330, 290)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun Onboarding() {
    var name by remember { mutableStateOf("") }
    var handle by remember { mutableStateOf("") }
    var handleEdited by remember { mutableStateOf(false) }
    var emoji by remember { mutableStateOf(AVATAR_EMOJIS.first()) }
    var hue by remember { mutableStateOf(HUES.first()) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier.fillMaxSize().background(WR.bg).statusBarsPadding().navigationBarsPadding().imePadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(WR.accent), contentAlignment = Alignment.Center) {
                WrIcon("waveform", 20.dp, androidx.compose.ui.graphics.Color.White)
            }
            Spacer(Modifier.size(10.dp))
            Text("HoMit", color = WR.text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(36.dp))
        Text("Voice rooms\nwith no server.", color = WR.text, fontSize = 38.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-1.4).sp)
        Spacer(Modifier.height(12.dp))
        Muted("Talk, chat, share your screen, watch together and draw — device to device. Your identity is a key that lives only on this phone.", size = 15)
        Spacer(Modifier.height(32.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            AvatarView(Avatar(emoji, hue), 64.dp)
            Spacer(Modifier.size(16.dp))
            Column {
                Tiny("Avatar")
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (h in HUES) {
                        Box(
                            Modifier.size(22.dp).clip(CircleShape).background(WR.hue(h, 0.55f, 0.42f))
                                .border(if (h == hue) 2.dp else 0.dp, WR.text, CircleShape).clickable { hue = h }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (e in AVATAR_EMOJIS) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(if (e == emoji) WR.accentSoft else WR.surface2)
                        .border(1.dp, if (e == emoji) WR.accent else WR.border, CircleShape).clickable { emoji = e },
                    contentAlignment = Alignment.Center
                ) { Text(e, fontSize = 20.sp) }
            }
        }
        Spacer(Modifier.height(24.dp))
        WrField(name, {
            name = it.take(30)
            if (!handleEdited) handle = cleanHandle(it.replace(" ", ""))
        }, label = "Display name", placeholder = "Ada Lovelace")
        Spacer(Modifier.height(14.dp))
        WrField(handle, { handle = cleanHandle(it); handleEdited = true }, label = "Username", placeholder = "ada", leadingIcon = "user")
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error!!, color = WR.danger, fontSize = 13.sp)
        }
        Spacer(Modifier.height(24.dp))
        WrButton("Create my identity", {
            val n = name.trim()
            if (n.length < 2) { error = "Pick a display name (2+ characters)."; return@WrButton }
            if (handle.length < 3) { error = "Usernames need 3+ letters, numbers or underscores."; return@WrButton }
            AppCore.profile.save(MyProfile(handle, n, avatar = Avatar(emoji, hue)))
        }, Modifier.fillMaxWidth())
        Spacer(Modifier.height(10.dp))
        WrButton("Restore from a backup file", {
            val p = AppCore.platform ?: return@WrButton
            p.pickFile { uri ->
                if (uri == null) return@pickFile
                try {
                    val text = AppCore.app.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                    val j = parseJsonOrNull(text)
                    if (j == null || !AppCore.profile.importBackup(j)) error = "That file is not a HoMit identity backup."
                } catch (e: Exception) {
                    error = "Could not read that file."
                }
            }
        }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary)
        Spacer(Modifier.height(20.dp))
        Muted("No account, no email, no server. Back up your identity from Settings to keep it if you change phones.", size = 12)
        Spacer(Modifier.height(40.dp))
    }
}
