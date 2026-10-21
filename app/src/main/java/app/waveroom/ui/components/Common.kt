package app.waveroom.ui.components

import android.graphics.BitmapFactory
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.Avatar
import app.waveroom.ui.theme.WR
import kotlinx.coroutines.delay

/* ---------------------------------------------------------------------------
 * Text helpers
 * ------------------------------------------------------------------------- */

@Composable
fun Tiny(text: String, modifier: Modifier = Modifier, color: Color = WR.faint) {
    Text(text.uppercase(), modifier = modifier, color = color, fontFamily = WR.mono, fontSize = 10.sp, letterSpacing = 1.sp)
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier, size: Int = 13, maxLines: Int = Int.MAX_VALUE, align: TextAlign? = null) {
    Text(text, modifier = modifier, color = WR.muted, fontSize = size.sp, maxLines = maxLines, overflow = TextOverflow.Ellipsis, textAlign = align)
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(WR.border))
}

/* ---------------------------------------------------------------------------
 * Avatar
 * ------------------------------------------------------------------------- */

@Composable
fun rememberPhoto(uid: String, mine: Boolean = false): androidx.compose.ui.graphics.ImageBitmap? {
    val v1 by AppCore.social.photoVersion.collectAsState()
    val v2 by AppCore.profile.photoVersion.collectAsState()
    return remember(uid, v1, v2, mine) {
        try {
            val bytes = if (mine) AppCore.profile.photo else AppCore.social.photoOf(uid)
            if (bytes == null) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
        } catch (e: Throwable) {
            null
        }
    }
}

@Composable
fun AvatarView(avatar: Avatar, size: Dp = 40.dp, uid: String = "", mine: Boolean = false, ring: Color? = null, modifier: Modifier = Modifier) {
    val photo = if (uid.isNotEmpty() || mine) rememberPhoto(uid, mine) else null
    val base = Modifier.size(size).clip(CircleShape)
    val m = if (ring != null) modifier.then(Modifier.border(2.dp, ring, CircleShape)).padding(3.dp) else modifier
    Box(m.then(base), contentAlignment = Alignment.Center) {
        if (photo != null) {
            Image(photo, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Box(
                Modifier.fillMaxSize().background(
                    Brush.linearGradient(listOf(WR.hue(avatar.hue, 0.5f, 0.38f), WR.hue(avatar.hue + 40, 0.5f, 0.22f)))
                ),
                contentAlignment = Alignment.Center
            ) {
                Text(avatar.emoji, fontSize = (size.value * 0.46f).sp)
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Buttons
 * ------------------------------------------------------------------------- */

enum class BtnKind { Primary, Secondary, Danger, Ghost, Ok }

@Composable
fun WrButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: BtnKind = BtnKind.Primary,
    icon: String? = null,
    enabled: Boolean = true,
    small: Boolean = false
) {
    val (bg, fg, bd) = when (kind) {
        BtnKind.Primary -> Triple(WR.accent, Color.White, WR.accent)
        BtnKind.Secondary -> Triple(WR.surface2, WR.text, WR.borderStrong)
        BtnKind.Danger -> Triple(WR.dangerSoft, WR.danger, WR.danger.copy(alpha = 0.5f))
        BtnKind.Ghost -> Triple(Color.Transparent, WR.muted, Color.Transparent)
        BtnKind.Ok -> Triple(WR.ok, Color(0xFF04140B), WR.ok)
    }
    val a = if (enabled) 1f else 0.4f
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(bg.copy(alpha = bg.alpha * a))
            .border(1.dp, bd.copy(alpha = bd.alpha * a), RoundedCornerShape(10.dp))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 18.dp, vertical = if (small) 8.dp else 13.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) {
            WrIcon(icon, if (small) 15.dp else 18.dp, fg.copy(alpha = a))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = fg.copy(alpha = a), fontSize = if (small) 13.sp else 15.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

@Composable
fun WrIconButton(
    icon: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    tint: Color = WR.text,
    bg: Color = Color.Transparent,
    border: Color = Color.Transparent,
    enabled: Boolean = true,
    badge: Int = 0
) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).border(1.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        WrIcon(icon, iconSize, if (enabled) tint else tint.copy(alpha = 0.35f))
        if (badge > 0) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(top = 4.dp, end = 4.dp).size(16.dp).clip(CircleShape).background(WR.accent),
                contentAlignment = Alignment.Center
            ) {
                Text(if (badge > 9) "9+" else badge.toString(), color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun WrChip(text: String, selected: Boolean = false, onClick: (() -> Unit)? = null, icon: String? = null, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(50)
    var m = modifier.clip(shape)
        .background(if (selected) WR.accentSoft else WR.surface2)
        .border(1.dp, if (selected) WR.accent else WR.border, shape)
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            WrIcon(icon, 14.dp, if (selected) WR.accentBright else WR.muted)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, color = if (selected) WR.accentBright else WR.muted, fontSize = 12.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}

/* ---------------------------------------------------------------------------
 * Inputs
 * ------------------------------------------------------------------------- */

@Composable
fun WrField(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String = "",
    singleLine: Boolean = true,
    minLines: Int = 1,
    maxLines: Int = if (singleLine) 1 else 6,
    mono: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    ime: ImeAction = ImeAction.Default,
    onDone: (() -> Unit)? = null,
    password: Boolean = false,
    leadingIcon: String? = null,
    maxLength: Int = Int.MAX_VALUE
) {
    Column(modifier) {
        if (label != null) {
            Tiny(label)
            Spacer(Modifier.height(6.dp))
        }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WR.surface2)
                .border(1.dp, WR.border, RoundedCornerShape(10.dp)).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingIcon != null) {
                WrIcon(leadingIcon, 18.dp, WR.faint)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, color = WR.faint, fontSize = 15.sp, fontFamily = if (mono) WR.mono else null, maxLines = 1)
                }
                BasicTextField(
                    value = value,
                    onValueChange = { if (it.length <= maxLength) onChange(it) },
                    singleLine = singleLine,
                    minLines = minLines,
                    maxLines = maxLines,
                    textStyle = TextStyle(color = WR.text, fontSize = 15.sp, fontFamily = if (mono) WR.mono else null),
                    cursorBrush = SolidColor(WR.accentBright),
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = ime),
                    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onSend = { onDone?.invoke() }, onGo = { onDone?.invoke() }, onSearch = { onDone?.invoke() }),
                    visualTransformation = if (password) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
fun SwitchRow(title: String, subtitle: String? = null, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().clickable(enabled = enabled) { onChange(!checked) }.padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = WR.text, fontSize = 15.sp)
            if (subtitle != null) Muted(subtitle, size = 12)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White, checkedTrackColor = WR.accent,
                uncheckedThumbColor = WR.muted, uncheckedTrackColor = WR.surface3, uncheckedBorderColor = WR.borderStrong
            )
        )
    }
}

@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WR.surface2).border(1.dp, WR.border, RoundedCornerShape(10.dp)).padding(3.dp)
    ) {
        for ((key, label) in options) {
            val on = key == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (on) WR.accent else Color.Transparent)
                    .clickable { onSelect(key) }.padding(vertical = 9.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(label, color = if (on) Color.White else WR.muted, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Layout pieces
 * ------------------------------------------------------------------------- */

@Composable
fun ScreenHeader(title: String, onBack: (() -> Unit)? = null, subtitle: String? = null, actions: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (onBack != null) WrIconButton("chevron-left", onBack) else Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).padding(horizontal = 4.dp)) {
            Text(title, color = WR.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Muted(subtitle, size = 12, maxLines = 1)
        }
        actions()
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Tiny(text, modifier.padding(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 8.dp))
}

@Composable
fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    var m = modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(WR.surface).border(1.dp, WR.border, RoundedCornerShape(14.dp))
    if (onClick != null) m = m.clickable(onClick = onClick)
    Box(m) { content() }
}

@Composable
fun EmptyState(icon: String, title: String, text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(WR.surface2), contentAlignment = Alignment.Center) {
            WrIcon(icon, 24.dp, WR.faint)
        }
        Spacer(Modifier.height(14.dp))
        Text(title, color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(4.dp))
        Muted(text, align = TextAlign.Center)
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

@Composable
fun Row2(title: String, subtitle: String? = null, icon: String? = null, trailing: (@Composable () -> Unit)? = null, onClick: (() -> Unit)? = null, danger: Boolean = false) {
    var m = Modifier.fillMaxWidth()
    if (onClick != null) m = m.clickable(onClick = onClick)
    Row(m.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            WrIcon(icon, 20.dp, if (danger) WR.danger else WR.muted)
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = if (danger) WR.danger else WR.text, fontSize = 15.sp)
            if (subtitle != null) Muted(subtitle, size = 12)
        }
        if (trailing != null) trailing()
        else if (onClick != null) WrIcon("chevron-right", 18.dp, WR.faint)
    }
}

/* ---------------------------------------------------------------------------
 * Sheets, dialogs, menus
 * ------------------------------------------------------------------------- */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WrSheet(onDismiss: () -> Unit, title: String? = null, fullHeight: Boolean = false, content: @Composable () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = fullHeight)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = state,
        containerColor = WR.surface,
        contentColor = WR.text,
        scrimColor = Color(0xAA000000),
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        dragHandle = {
            Box(Modifier.padding(top = 10.dp, bottom = 6.dp).width(36.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(WR.borderStrong))
        }
    ) {
        Column(Modifier.navigationBarsPadding()) {
            if (title != null) {
                Text(title, color = WR.text, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            }
            content()
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
fun ConfirmDialog(
    title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit,
    danger: Boolean = false, cancel: String = "Cancel"
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WR.surface2,
        titleContentColor = WR.text,
        textContentColor = WR.muted,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = { onConfirm(); onDismiss() }) {
                Text(confirm, color = if (danger) WR.danger else WR.accentBright, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(cancel, color = WR.muted) } }
    )
}

@Composable
fun InputDialog(
    title: String, initial: String = "", placeholder: String = "", confirm: String = "OK",
    keyboard: KeyboardType = KeyboardType.Text, onConfirm: (String) -> Unit, onDismiss: () -> Unit
) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = WR.surface2,
        title = { Text(title, color = WR.text, fontWeight = FontWeight.SemiBold) },
        text = { WrField(v, { v = it }, placeholder = placeholder, keyboard = keyboard) },
        confirmButton = { TextButton(onClick = { onConfirm(v.trim()); onDismiss() }) { Text(confirm, color = WR.accentBright, fontWeight = FontWeight.SemiBold) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = WR.muted) } }
    )
}

data class MenuEntry(val label: String, val icon: String? = null, val danger: Boolean = false, val onClick: () -> Unit)

@Composable
fun WrMenu(expanded: Boolean, onDismiss: () -> Unit, entries: List<MenuEntry>) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss, containerColor = WR.surface2, shape = RoundedCornerShape(12.dp)) {
        for (e in entries) {
            DropdownMenuItem(
                text = { Text(e.label, color = if (e.danger) WR.danger else WR.text, fontSize = 14.sp) },
                leadingIcon = if (e.icon != null) ({ WrIcon(e.icon, 18.dp, if (e.danger) WR.danger else WR.muted) }) else null,
                onClick = { onDismiss(); e.onClick() }
            )
        }
    }
}

/* ---------------------------------------------------------------------------
 * Toasts
 * ------------------------------------------------------------------------- */

@Composable
fun ToastHost(modifier: Modifier = Modifier) {
    val items = remember { mutableStateListOf<Pair<Long, String>>() }
    LaunchedEffect(Unit) {
        AppCore.toasts.collect { t ->
            val id = System.nanoTime()
            items.add(id to t)
            if (items.size > 3) items.removeAt(0)
        }
    }
    LaunchedEffect(Unit) {
        AppCore.session.toasts.collect { t ->
            if (t.isNotBlank()) {
                val id = System.nanoTime()
                items.add(id to t)
                if (items.size > 3) items.removeAt(0)
            }
        }
    }
    Column(modifier.fillMaxWidth().statusBarsPadding().padding(top = 8.dp, start = 16.dp, end = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        for ((id, text) in items.toList()) {
            key(id) {
                var shown by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    shown = true
                    delay(3200)
                    shown = false
                    delay(250)
                    items.removeAll { it.first == id }
                }
                AnimatedVisibility(shown, enter = fadeIn() + slideInVertically { -it / 2 }, exit = fadeOut() + slideOutVertically { -it / 2 }) {
                    Box(
                        Modifier.padding(bottom = 6.dp).clip(RoundedCornerShape(12.dp)).background(WR.surface3)
                            .border(1.dp, WR.borderStrong, RoundedCornerShape(12.dp)).padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(text, color = WR.text, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}
