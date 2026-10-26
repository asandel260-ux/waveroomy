package app.waveroom.ui.screens

import android.media.AudioDeviceInfo
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import app.waveroom.core.parseJsonOrNull
import app.waveroom.media.FX_PRESETS
import app.waveroom.ui.Nav
import app.waveroom.ui.components.ACCENT_PRESETS
import app.waveroom.ui.components.BG_PRESETS
import app.waveroom.ui.components.ColorPickerCard
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.ScreenHeader
import app.waveroom.ui.components.Segmented
import app.waveroom.ui.components.SectionTitle
import app.waveroom.ui.components.SwitchRow
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrField
import app.waveroom.ui.components.WrSheet
import app.waveroom.ui.theme.WR
import app.waveroom.ui.components.screenBackdrop

private fun deviceLabel(d: AudioDeviceInfo): String {
    val kind = when (d.type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "Built-in microphone"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Phone speaker"
        AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired headset"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired headphones"
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB audio"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth"
        AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE"
        else -> "Audio device"
    }
    val n = d.productName?.toString().orEmpty()
    return if (n.isNotEmpty() && !kind.contains(n)) "$kind · $n" else kind
}

/** Voice effects controls, shared by Settings and the in-room sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceFxPanel() {
    val s by AppCore.settings.state.collectAsState()
    val available = AppCore.rtc.fx.available
    Column {
        if (!available) {
            Muted("Voice effects are not supported by this WebRTC build, so they stay off. (The default build supports them.)", size = 12)
            Spacer(Modifier.height(8.dp))
        }
        SwitchRow("Enable voice effects", "Processed on your phone before your voice is sent.", s.fxEnabled, {
            AppCore.settings.update { st -> st.copy(fxEnabled = it) }; AppCore.rtc.applyFx()
        }, enabled = available)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (p in FX_PRESETS) {
                val on = s.fxPreset == p.id
                Column(
                    Modifier.width(150.dp).clip(RoundedCornerShape(12.dp)).background(if (on) WR.accentSoft else WR.surface2)
                        .border(1.dp, if (on) WR.accent else WR.border, RoundedCornerShape(12.dp))
                        .clickable { AppCore.settings.update { st -> st.copy(fxPreset = p.id, fxEnabled = available) }; AppCore.rtc.applyFx() }
                        .padding(12.dp)
                ) {
                    Text(p.name, color = if (on) WR.accentBright else WR.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Muted(p.desc, size = 11)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Tiny("Intensity · ${(s.fxIntensity * 100).toInt()}%")
        Slider(
            value = s.fxIntensity, onValueChange = { AppCore.settings.update { st -> st.copy(fxIntensity = it) }; AppCore.rtc.applyFx() },
            valueRange = 0f..1f,
            colors = SliderDefaults.colors(thumbColor = WR.accentBright, activeTrackColor = WR.accent, inactiveTrackColor = WR.surface3)
        )
        Muted("Tip: use headphones so the processed sound doesn't loop back into your microphone.", size = 12)
    }
}

@Composable
fun SettingsScreen(nav: Nav) {
    val s by AppCore.settings.state.collectAsState()
    var micSheet by remember { mutableStateOf(false) }
    var outSheet by remember { mutableStateOf(false) }
    var wipe by remember { mutableStateOf(false) }
    var dmSheet by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().screenBackdrop().imePadding()) {
        ScreenHeader("Settings", { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
            SectionTitle("Appearance")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SwitchRow("Animations", "Screen transitions, pulses and other motion. Turn off for a calmer, faster app.", s.animations, { AppCore.settings.update { st -> st.copy(animations = it) } })
                ColorPickerCard(
                    "Buttons & accents", "Buttons, highlights, switches and sliders.",
                    s.accentColor, androidx.compose.ui.graphics.Color(0xFF7C3AED), ACCENT_PRESETS
                ) { c -> AppCore.settings.update { st -> st.copy(accentColor = c) } }
                ColorPickerCard(
                    "Background", "The dark base of the app. Panels and borders follow it automatically.",
                    s.bgColor, androidx.compose.ui.graphics.Color(0xFF0A0710), BG_PRESETS
                ) { c -> AppCore.settings.update { st -> st.copy(bgColor = c) } }
            }
            SectionTitle("Voice")
            Column(Modifier.padding(horizontal = 20.dp)) {
                SwitchRow("Push to talk", "Hold the mic button to speak instead of toggling it.", s.ptt, { AppCore.settings.update { st -> st.copy(ptt = it) } })
                SwitchRow("Interface sounds", "Small tones when people join, leave or message.", s.sounds, { AppCore.settings.update { st -> st.copy(sounds = it) } })
            }
            SectionTitle("Voice effects")
            Column(Modifier.padding(horizontal = 20.dp)) { VoiceFxPanel() }

            SectionTitle("Audio devices")
            app.waveroom.ui.components.Row2(
                "Microphone",
                AppCore.audio.inputDevices().firstOrNull { it.id == s.micDeviceId }?.let { deviceLabel(it) } ?: "Automatic",
                "mic", onClick = { micSheet = true }
            )
            app.waveroom.ui.components.Row2(
                "Output",
                AppCore.audio.outputDevices().firstOrNull { it.id == s.outputDeviceId }?.let { deviceLabel(it) } ?: "Automatic (headset if connected)",
                "volume", onClick = { outSheet = true }
            )

            SectionTitle("Privacy")
            app.waveroom.ui.components.Row2(
                "Who can message me",
                when (s.dmPolicy) { "nobody" -> "Nobody"; "following" -> "People I follow"; else -> "Anyone I'm connected to" },
                "message", onClick = { dmSheet = true }
            )

            Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                Text("Who can find me", color = WR.text, fontSize = 15.sp)
                Muted("Everyone: people can search your @username. Link only: they need your link. Nobody: no new requests.", size = 12)
                Spacer(Modifier.height(8.dp))
                app.waveroom.ui.components.Segmented(
                    listOf("everyone" to "Everyone", "link" to "Link only", "nobody" to "Nobody"), s.findMe,
                    { v -> AppCore.settings.update { st -> st.copy(findMe = v) }; app.waveroom.Rendezvous.kick(); app.waveroom.Discovery.profileChanged() }
                )
            }

            app.waveroom.ui.components.Row2("Send diagnostics", "Share a technical report if something misbehaves", "info", onClick = { AppCore.platform?.shareText(app.waveroom.Diag.report()) })

            SectionTitle("Network")
            Column(Modifier.padding(horizontal = 20.dp)) {
                SwitchRow("Show rooms on this Wi-Fi", "Listen for rooms hosted on the same network and list them under Rooms.", s.nearby, { AppCore.settings.update { st -> st.copy(nearby = it) } })
                SwitchRow(
                    "Use public STUN servers",
                    "Helps two phones on different networks find a direct path. It only reveals your IP address to the STUN server — no audio or content. Turn off to stay strictly on the local network.",
                    s.useStun, { AppCore.settings.update { st -> st.copy(useStun = it) } }
                )
                SwitchRow(
                    "Relay helper (recommended)",
                    "Lets phones on different networks (mobile data, strict Wi-Fi) connect when a direct path is impossible. A public relay carries only encrypted packets and is used as a last resort. Turn off to never touch any outside server.",
                    s.useRelay, { AppCore.settings.update { st -> st.copy(useRelay = it) } }
                )
                SwitchRow(
                    "Reconnect helper (recommended)",
                    "Lets saved friends find each other again over the internet without scanning anything. Only tiny encrypted notes, readable only by the two of you, pass through a public message board (ntfy.sh by default). Turn off to reconnect on the same Wi-Fi only.",
                    s.reconnect, { AppCore.settings.update { st -> st.copy(reconnect = it) } }
                )
                if (s.reconnect) {
                    val rvs by AppCore.rendezvous.status.collectAsState()
                    Muted("Status: $rvs", size = 12)
                    Spacer(Modifier.height(6.dp))
                    WrField(s.rvUrl, { AppCore.settings.update { st -> st.copy(rvUrl = it) } }, label = "Message board address (optional)", placeholder = "https://ntfy.sh", mono = true)
                }
                Spacer(Modifier.height(8.dp))
                WrField(s.customStun, { AppCore.settings.update { st -> st.copy(customStun = it) } }, label = "Extra STUN servers (comma separated)", placeholder = "stun.example.com:3478", mono = true)
                Spacer(Modifier.height(14.dp))
                Tiny("Optional relay (TURN) for strict networks")
                Spacer(Modifier.height(6.dp))
                Muted("If two people can't connect (symmetric NAT, corporate Wi-Fi), a TURN relay you control makes it work. HoMit never ships with one.", size = 12)
                Spacer(Modifier.height(8.dp))
                WrField(s.turnUrl, { AppCore.settings.update { st -> st.copy(turnUrl = it) } }, placeholder = "turn:turn.example.com:3478", mono = true)
                Spacer(Modifier.height(8.dp))
                WrField(s.turnUser, { AppCore.settings.update { st -> st.copy(turnUser = it) } }, placeholder = "Username")
                Spacer(Modifier.height(8.dp))
                WrField(s.turnPass, { AppCore.settings.update { st -> st.copy(turnPass = it) } }, placeholder = "Password", password = true)
                Spacer(Modifier.height(6.dp))
                Muted("Network changes apply to the next connection you make.", size = 12)
            }

            SectionTitle("Identity & data")
            Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Muted("Your identity is a key stored only on this phone. Export a backup to keep your name, handle and followers across devices.", size = 12)
                WrButton("Export identity backup", {
                    val bytes = AppCore.profile.exportBackup().toString(2).toByteArray(Charsets.UTF_8)
                    AppCore.platform?.saveFile("waveroom-identity.json", "application/json", bytes) { ok -> AppCore.toast(if (ok) "Backup saved — keep it private" else "Not saved") }
                }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary, icon = "download")
                WrButton("Restore from backup", {
                    AppCore.platform?.pickFile { uri ->
                        if (uri != null) {
                            try {
                                val text = AppCore.app.contentResolver.openInputStream(uri)?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
                                val j = parseJsonOrNull(text)
                                AppCore.toast(if (j != null && AppCore.profile.importBackup(j)) "Identity restored" else "That isn't a HoMit backup")
                            } catch (e: Exception) {
                                AppCore.toast("Could not read that file")
                            }
                        }
                    }
                }, Modifier.fillMaxWidth(), kind = BtnKind.Secondary, icon = "copy")
                WrButton("Delete everything on this phone", { wipe = true }, Modifier.fillMaxWidth(), kind = BtnKind.Danger, icon = "trash")
            }
        }
    }

    if (micSheet) {
        WrSheet({ micSheet = false }, "Microphone") {
            DeviceRow("Automatic", s.micDeviceId < 0) { AppCore.settings.update { st -> st.copy(micDeviceId = -1) }; AppCore.rtc.applyPreferredInput(-1); micSheet = false }
            for (d in AppCore.audio.inputDevices()) {
                DeviceRow(deviceLabel(d), s.micDeviceId == d.id) {
                    AppCore.settings.update { st -> st.copy(micDeviceId = d.id) }; AppCore.rtc.applyPreferredInput(d.id); micSheet = false
                }
            }
        }
    }
    if (outSheet) {
        WrSheet({ outSheet = false }, "Output") {
            DeviceRow("Automatic", s.outputDeviceId < 0) { AppCore.settings.update { st -> st.copy(outputDeviceId = -1) }; AppCore.audio.route(); outSheet = false }
            for (d in AppCore.audio.outputDevices()) {
                DeviceRow(deviceLabel(d), s.outputDeviceId == d.id) {
                    AppCore.settings.update { st -> st.copy(outputDeviceId = d.id) }; AppCore.audio.route(); outSheet = false
                }
            }
        }
    }
    if (dmSheet) {
        WrSheet({ dmSheet = false }, "Who can message me") {
            for ((k, label) in listOf("everyone" to "Anyone I'm connected to", "following" to "Only people I follow", "nobody" to "Nobody")) {
                DeviceRow(label, s.dmPolicy == k) { AppCore.settings.update { st -> st.copy(dmPolicy = k) }; dmSheet = false }
            }
        }
    }
    if (wipe) {
        ConfirmDialog(
            "Delete everything?", "Your identity, followers, messages, saved rooms and settings are erased from this phone. This can't be undone unless you have a backup.",
            "Delete", { AppCore.wipeAll(); nav.resetTo(app.waveroom.ui.Route.Home) }, { wipe = false }, danger = true
        )
    }
}

@Composable
private fun DeviceRow(label: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (on) WR.accentBright else WR.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        if (on) app.waveroom.ui.components.WrIcon("check", 18.dp, WR.accentBright)
    }
}
