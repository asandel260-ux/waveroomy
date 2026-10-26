package app.waveroom.ui

import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.WindowInsets
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.mutableStateOf
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import kotlin.math.abs
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import app.waveroom.ui.screens.NewGroupScreen
import app.waveroom.ui.screens.GroupInfoScreen
import app.waveroom.ui.screens.WelcomeScreen
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
        var welcomed by rememberSaveable { mutableStateOf(false) }
        if (profile == null) {
            AnimatedContent(welcomed, transitionSpec = { fadeIn(tween(WR.ms(300))) togetherWith fadeOut(tween(WR.ms(200))) }, label = "welcome") { w ->
                if (!w) WelcomeScreen { welcomed = true } else Onboarding()
            }
        } else MainShell()
        IncomingCallOverlay()
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

    var lastPhase by remember { mutableStateOf("idle") }
    LaunchedEffect(phase) {
        val was = lastPhase
        lastPhase = phase
        if (phase != "idle" && was == "idle") {
            nav.resetTo(Route.Home)
            nav.push(Route.Room)
        } else if (phase == "idle") {
            // the room is over: take it out of the stack wherever it is
            if (nav.stack.contains(Route.Room)) {
                nav.stack.removeAll { it == Route.Room }
                if (nav.stack.isEmpty()) nav.stack.add(Route.Home)
            }
        }
    }
    var crash by remember { mutableStateOf(app.waveroom.Diag.pending()) }
    crash?.let { rep ->
        app.waveroom.ui.components.ConfirmDialog(
            "HoMit closed unexpectedly", "It crashed last time. Sending the report helps me fix the exact cause. It contains no messages.",
            "Share report", { AppCore.platform?.shareText(rep); app.waveroom.Diag.clear(); crash = null }, { app.waveroom.Diag.clear(); crash = null }, cancel = "Dismiss"
        )
    }
    val adding by AppCore.pendingAdd.collectAsState()
    adding?.let { c ->
        app.waveroom.ui.components.ConfirmDialog(
            "Add ${c.name}?", "@${c.username} · id ${c.uid.take(5)}\nThey'll get a request and, once they accept, you'll connect automatically.",
            "Send request", { app.waveroom.Discovery.sendRequest(c); AppCore.pendingAdd.value = null }, { AppCore.pendingAdd.value = null }
        )
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

    val d = nav.stack.size - nav.prevSize
    androidx.compose.runtime.SideEffect { nav.prevSize = nav.stack.size }
    val d = nav.stack.size - nav.prevSize
    androidx.compose.runtime.SideEffect { nav.prevSize = nav.stack.size }
    val scope = rememberCoroutineScope()
    val swipe = remember { Animatable(0f) }
    val haptic = LocalHapticFeedback.current
    val canSwipe = nav.stack.size > 1 && top != Route.Room
    var widthPx by remember { mutableStateOf(1f) }
    androidx.compose.runtime.SideEffect { nav.skipAnim = false }
    Box(
        Modifier.fillMaxSize()
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(canSwipe, nav.stack.size) {
                if (!canSwipe) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val slop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var dx = 0f
                    var dy = 0f
                    var active = false
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) break
                        if (ev.changes.count { it.pressed } > 1) { if (active) break else return@awaitEachGesture }
                        val d = ch.positionChange()
                        if (!active) {
                            if (ch.isConsumed) return@awaitEachGesture
                            dx += d.x; dy += d.y
                            if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                            if (abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.8f) active = true
                        } else dx += d.x
                        if (active) {
                            ch.consume()
                            tracker.addPosition(ch.uptimeMillis, ch.position)
                            val v = dx
                            scope.launch { swipe.snapTo(v) }
                        }
                    }
                    if (active) {
                        val vx = tracker.calculateVelocity().x
                        val w = size.width.toFloat()
                        val commit = abs(dx) > w * 0.35f || (abs(vx) > 1400f && vx * dx > 0f && abs(dx) > 60f)
                        scope.launch {
                            if (commit) {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                swipe.animateTo(if (dx > 0) w else -w, androidx.compose.animation.core.tween(150))
                                nav.skipAnim = true
                                nav.pop()
                                swipe.snapTo(0f)
                            } else swipe.animateTo(0f, spring(stiffness = 500f))
                        }
                    }
                }
            }
    ) {
    Box(Modifier.fillMaxSize().graphicsLayer { translationX = swipe.value; alpha = 1f - 0.35f * (abs(swipe.value) / widthPx).coerceIn(0f, 1f) }) {
    AnimatedContent(
        targetState = top,
        transitionSpec = {
            if (!WR.animations || nav.skipAnim) EnterTransition.None togetherWith ExitTransition.None
            else if (d >= 0) (slideInHorizontally(tween(260)) { it / 4 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { -it / 6 } + fadeOut(tween(160)))
            else (slideInHorizontally(tween(260)) { -it / 6 } + fadeIn(tween(260))) togetherWith (slideOutHorizontally(tween(260)) { it / 4 } + fadeOut(tween(160)))
        },
        label = "nav"
    ) { r ->
      Box(if (r == Route.Room) Modifier else Modifier.windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
        when (r) {
            Route.Home -> HomeScreen(nav)
            Route.Room -> RoomScreen(nav)
            Route.Create -> CreateRoomScreen(nav)
            is Route.Join -> JoinScreen(nav, r.code)
            is Route.Invite -> InviteScreen(nav, r.purpose)
            is Route.Chat -> ChatScreen(nav, r.uid)
            is Route.Profile -> ProfileScreen(nav, r.uid)
            Route.People -> PeopleScreen(nav)
            Route.EditProfile -> EditProfileScreen(nav)
            Route.Settings -> SettingsScreen(nav)
            Route.Moderation -> ModerationScreen(nav)
            Route.Help -> HelpScreen(nav)
            Route.Legal -> LegalScreen(nav)
            Route.About -> AboutScreen(nav)
            Route.NewGroup -> NewGroupScreen(nav)
            is Route.GroupInfo -> GroupInfoScreen(nav, r.gid)
        }
      }
    }
    }
    }

    if (ended != null) {
        ConfirmDialog("Room closed", ended ?: "", "OK", { session.endedReason.value = null }, { session.endedReason.value = null }, cancel = "Dismiss")
    }
    if (joinError != null) {
        ConfirmDialog("Couldn't join", joinError ?: "", "OK", { session.joinError.value = null }, { session.joinError.value = null }, cancel = "Dismiss")
    }
}

@Composable
private fun IncomingCallOverlay() {
    val call by AppCore.dmHub.incomingCall.collectAsState()
    val c = call ?: return
    val name = AppCore.contactName(c.uid)
    androidx.compose.material3.AlertDialog(
        onDismissRequest = {},
        containerColor = WR.surface2,
        title = { androidx.compose.material3.Text(if (c.group) c.title else "$name is calling", color = WR.text, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) },
        text = { androidx.compose.material3.Text(if (c.group) "$name started a group voice call." else "Voice call over a direct connection.", color = WR.muted) },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { AppCore.dmHub.acceptCall() }) { androidx.compose.material3.Text("Answer", color = WR.ok, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = { AppCore.dmHub.declineCall() }) { androidx.compose.material3.Text("Decline", color = WR.danger) } }
    )
}
