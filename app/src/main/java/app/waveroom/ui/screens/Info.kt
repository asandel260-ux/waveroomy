package app.waveroom.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.ui.Nav
import app.waveroom.ui.components.ScreenHeader
import app.waveroom.ui.theme.WR
import app.waveroom.ui.components.screenBackdrop

@Composable
private fun Page(title: String, nav: Nav, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().screenBackdrop(4)) {
        ScreenHeader(title, { nav.pop() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            content()
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun H(t: String) {
    Spacer(Modifier.height(18.dp))
    Text(t, color = WR.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun P(t: String) {
    Text(t, color = WR.muted, fontSize = 14.sp, lineHeight = 21.sp, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp))
}

@Composable
fun HelpScreen(nav: Nav) = Page("Help", nav) {
    H("How does it work without a server?")
    P("HoMit connects phones directly to each other (peer to peer). There is no account system and no server relaying your voice, chat or screen. A room is simply the group of phones that are connected to each other.")
    H("Starting and joining")
    P("Start a room, then tap the + button to invite someone. Friends in your list can join straight away; anyone else can scan the QR code (on different networks they may also show you a reply code). The two phones link up directly. After that, anyone already in the room can invite more people — new people connect to everyone through the people already there.")
    P("On the same Wi-Fi or hotspot, rooms appear under “Nearby” and you can join with a single tap — no codes.")
    H("The host")
    P("The creator is the host. If the host leaves or loses connection, the person who has been in the room longest takes over automatically and the room carries on. Hosts can move people to and from the stage, mute, remove and ban people, lock the room and end it.")
    H("Different networks")
    P("Two phones on different networks usually connect through public STUN servers (enabled by default in Settings → Network). Some strict networks block direct connections; in that case a TURN relay you run yourself can be added in Settings.")
    H("Size of a room")
    P("Everyone connects to everyone, so each extra person adds load. Voice works well up to about 8–10 people; screen sharing is best with a few viewers. Up to 24 people can join.")
    H("Finding and adding people")
    P("Tap the add-person button in Messages. Search a friend's @username, or paste their link, and send a request. When they accept, your phones are friends and reconnect on their own whenever you are both online — on the same Wi-Fi or across the internet — with no codes. Nearby people on the same Wi-Fi are listed too. You can limit who can find you in Settings → Privacy.")
    P("Reconnecting across networks uses a public message board (ntfy.sh by default) for tiny encrypted notes that only you and your friend can read. You can turn this off or point it at another board in Settings → Network.")
    H("Messages")
    P("Chats are saved on your phone. When your friend is online the message is delivered at once; otherwise it waits and is sent as soon as you are both online. Groups catch up whenever two members connect, so a message written while you were away appears in its right place afterwards.")
    H("Shared browser and Watch Together")
    P("Everyone in the room loads the same page or YouTube video on their own phone; only the page address and playback position are shared. Your logins and cookies never leave your phone.")
    H("Screen sharing")
    P("Your screen is sent live to everyone in the room. Android doesn't let apps capture other apps' sound, so viewers hear your microphone only.")
    H("Backups")
    P("Your identity is a key that exists only on this phone. Export a backup in Settings → Identity & data. Anyone with the file can act as you, so keep it private.")
}

@Composable
fun LegalScreen(nav: Nav) = Page("Privacy & terms", nav) {
    H("Privacy")
    P("HoMit has no servers and collects nothing. Your profile, contacts, messages and room history are stored only on your phone. When you connect to someone, they receive your display name, username, avatar, bio and photo — that is how they see you.")
    P("Voice, chat, screen and board content flows directly between participants and is encrypted in transit by WebRTC. People in a room you join can record or screenshot what they receive; only share what you are comfortable sharing with them.")
    P("If STUN is enabled, your phone contacts public STUN servers (Google and Cloudflare by default) to discover its public network address. They learn that address and nothing else. Disable it in Settings → Network to stay on the local network only.")
    P("The shared browser and Watch Together load websites and YouTube directly on your phone, subject to those sites' own terms and privacy policies.")
    H("Use")
    P("Be kind. Don't use HoMit to harass people, share illegal content, or violate others' privacy. Room hosts moderate their own rooms: they can mute, remove and ban people. There is no central authority that can see or remove content.")
    P("HoMit is provided as is, without warranty. You are responsible for what you share.")
}

@Composable
fun AboutScreen(nav: Nav) = Page("About", nav) {
    H("HoMit")
    P("Server-free voice rooms for Android: stage and audience, hand-raising, room chat and reactions, direct messages, screen sharing, Watch Together, a shared browser, a shared infinite blackboard and voice effects — all peer to peer.")
    H("Built with")
    P("Kotlin and Jetpack Compose, native WebRTC (libwebrtc), ZXing for QR codes, CameraX and ML Kit for scanning, and the Lucide icon set.")
    H("Design")
    P("Dark, typographic, one accent colour. Hairlines instead of shadows.")
}
