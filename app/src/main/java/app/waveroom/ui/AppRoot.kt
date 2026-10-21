package app.waveroom.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import app.waveroom.AppCore
import app.waveroom.ui.components.ConfirmDialog
import app.waveroom.ui.components.ToastHost
import app.waveroom.ui.screens.AboutScreen
import app.waveroom.ui.screens.ChatScreen
import app.waveroom.ui.screens.CreateRoomScreen
import app.waveroom.ui.screens.EditProfileScreen
import app.waveroom.ui.screens.HelpScreen
import app.waveroom.ui.screens.HomeScreen
import app.waveroom.ui.screens.InviteScreen
import app.waveroom.ui.screens.JoinScreen
import app.waveroom.ui.screens.LegalScreen
import app.waveroom.ui.screens.ModerationScreen
import app.waveroom.ui.screens.Onboarding
import app.waveroom.ui.screens.PeopleScreen
import app.waveroom.ui.screens.ProfileScreen
import app.waveroom.ui.screens.RoomScreen
import app.waveroom.ui.screens.SettingsScreen
import app.waveroom.ui.theme.WR

@Composable
fun AppRoot() {
    val profile by AppCore.profile.profile.collectAsState()
    Box(Modifier.fillMaxSize().background(WR.bg)) {
        if (profile == null) Onboarding() else MainShell()
        ToastHost(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun MainShell() {
    val nav = remember { Nav() }
    val session = AppCore.session
    val phase by session.phase.collectAsState()
    val ended by session.endedReason.collectAsState()
    val joinError by session.joinError.collectAsState()
    val pending by AppCore.pendingCode.collectAsState()

    LaunchedEffect(phase) {
        if (phase != "idle") {
            nav.resetTo(Route.Home)
            nav.push(Route.Room)
        } else if (nav.top == Route.Room) {
            nav.pop()
        }
    }
    LaunchedEffect(pending) {
        val p = pending
        if (p != null && session.phase.value == "idle") {
            nav.push(Route.Join(p))
            AppCore.pendingCode.value = null
        }
    }

    val top = nav.top
    BackHandler(enabled = nav.stack.size > 1 && top != Route.Room) { nav.pop() }

    when (top) {
        Route.Home -> HomeScreen(nav)
        Route.Room -> RoomScreen(nav)
        Route.Create -> CreateRoomScreen(nav)
        is Route.Join -> JoinScreen(nav, top.code)
        is Route.Invite -> InviteScreen(nav, top.purpose)
        is Route.Chat -> ChatScreen(nav, top.uid)
        is Route.Profile -> ProfileScreen(nav, top.uid)
        Route.People -> PeopleScreen(nav)
        Route.EditProfile -> EditProfileScreen(nav)
        Route.Settings -> SettingsScreen(nav)
        Route.Moderation -> ModerationScreen(nav)
        Route.Help -> HelpScreen(nav)
        Route.Legal -> LegalScreen(nav)
        Route.About -> AboutScreen(nav)
    }

    if (ended != null) {
        ConfirmDialog("Room closed", ended ?: "", "OK", { session.endedReason.value = null }, { session.endedReason.value = null }, cancel = "Dismiss")
    }
    if (joinError != null) {
        ConfirmDialog("Couldn't join", joinError ?: "", "OK", { session.joinError.value = null }, { session.joinError.value = null }, cancel = "Dismiss")
    }
}
