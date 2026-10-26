package app.waveroom.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.waveroom.AppCore
import app.waveroom.core.Report
import app.waveroom.core.timeAgo
import app.waveroom.ui.Nav
import app.waveroom.ui.components.BtnKind
import app.waveroom.ui.components.EmptyState
import app.waveroom.ui.components.InputDialog
import app.waveroom.ui.components.Muted
import app.waveroom.ui.components.ScreenHeader
import app.waveroom.ui.components.Segmented
import app.waveroom.ui.components.Tiny
import app.waveroom.ui.components.WrButton
import app.waveroom.ui.components.WrChip
import app.waveroom.ui.theme.WR
import app.waveroom.ui.components.screenBackdrop

@Composable
fun ModerationScreen(nav: Nav) {
    var tab by remember { mutableStateOf("reports") }
    Column(Modifier.fillMaxSize().screenBackdrop()) {
        ScreenHeader("Moderation desk", { nav.pop() }, subtitle = "For rooms you host")
        Box(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
            Segmented(listOf("reports" to "Reports", "audit" to "Audit", "stats" to "Activity"), tab, { tab = it })
        }
        when (tab) {
            "reports" -> ReportsTab()
            "audit" -> AuditTab()
            else -> StatsTab()
        }
    }
}

@Composable
private fun ReportsTab() {
    val reports by AppCore.mod.reports.collectAsState()
    var filter by remember { mutableStateOf("open") }
    var resolving by remember { mutableStateOf<Pair<Report, String>?>(null) }
    val list = reports.filter {
        when (filter) {
            "open" -> it.status == "open" && !it.outgoing
            "done" -> it.status != "open" && !it.outgoing
            else -> it.outgoing
        }
    }
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        WrChip("Open", filter == "open", { filter = "open" })
        WrChip("Handled", filter == "done", { filter = "done" })
        WrChip("Filed by me", filter == "mine", { filter = "mine" })
    }
    if (list.isEmpty()) {
        EmptyState("shield-alert", "Nothing here", "Reports from people in rooms you host land here. There is no central moderator — you decide.")
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(list, key = { it.id }) { r ->
                Column(Modifier.fillMaxWidth().background(WR.surface, RoundedCornerShape(14.dp)).border(1.dp, WR.border, RoundedCornerShape(14.dp)).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Tiny("${r.targetType} · ${r.category}", Modifier.weight(1f))
                        Tiny(timeAgo(r.createdAt))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(r.targetLabel, color = WR.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Muted("in “${r.roomTitle}” · from ${if (r.outgoing) "you" else r.reporterName}", size = 12)
                    if (r.excerpt.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Box(Modifier.fillMaxWidth().background(WR.surface2, RoundedCornerShape(8.dp)).padding(10.dp)) { Text("“${r.excerpt}”", color = WR.muted, fontSize = 13.sp) }
                    }
                    if (r.details.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(r.details, color = WR.text, fontSize = 14.sp)
                    }
                    if (r.status != "open") {
                        Spacer(Modifier.height(8.dp))
                        Muted("${r.status}${if (r.note.isNotEmpty()) " — ${r.note}" else ""}", size = 12)
                    } else if (!r.outgoing) {
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WrButton("Resolve", { resolving = r to "resolved" }, small = true)
                            WrButton("Dismiss", { resolving = r to "dismissed" }, small = true, kind = BtnKind.Secondary)
                        }
                    }
                }
            }
        }
    }
    val rs = resolving
    if (rs != null) {
        InputDialog("Note (optional)", placeholder = "What did you do?", confirm = rs.second.replaceFirstChar { it.uppercase() }, onConfirm = { note ->
            AppCore.mod.resolve(rs.first.id, rs.second, note)
        }, onDismiss = { resolving = null })
    }
}

@Composable
private fun AuditTab() {
    val audit by AppCore.mod.audit.collectAsState()
    if (audit.isEmpty()) {
        EmptyState("book", "No actions yet", "Kicks, bans, locks and other moderation actions you take are recorded here, on this phone only.")
    } else {
        LazyColumn(Modifier.fillMaxSize()) {
            items(audit, key = { it.id }) { a ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
                    Row {
                        Text(a.action, color = WR.accentBright, fontSize = 13.sp, fontFamily = WR.mono, modifier = Modifier.weight(1f))
                        Tiny(timeAgo(a.at))
                    }
                    Text(a.targetLabel.ifEmpty { a.roomTitle }, color = WR.text, fontSize = 14.sp)
                    if (a.detail.isNotEmpty()) Muted(a.detail, size = 12)
                    Muted(a.roomTitle, size = 11)
                }
            }
        }
    }
}

@Composable
private fun StatsTab() {
    val stats by AppCore.mod.stats.collectAsState()
    stats.length()
    val joins = AppCore.mod.series("joins", 14)
    val msgs = AppCore.mod.series("msgs", 14)
    val rooms by AppCore.rooms.rooms.collectAsState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile("Rooms", rooms.size.toString(), Modifier.weight(1f))
            StatTile("Joins · 14d", joins.sum().toString(), Modifier.weight(1f))
            StatTile("Messages · 14d", msgs.sum().toString(), Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        Tiny("Joins per day")
        Spacer(Modifier.height(8.dp))
        Bars(joins, WR.accent)
        Spacer(Modifier.height(18.dp))
        Tiny("Messages per day")
        Spacer(Modifier.height(8.dp))
        Bars(msgs, WR.ok)
        Spacer(Modifier.height(10.dp))
        Muted("Counted only in rooms you host, stored only on this phone.", size = 12)
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier) {
    Column(modifier.background(WR.surface, RoundedCornerShape(12.dp)).border(1.dp, WR.border, RoundedCornerShape(12.dp)).padding(12.dp)) {
        Text(value, color = WR.text, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        Tiny(label)
    }
}

@Composable
private fun Bars(values: List<Int>, color: Color) {
    val mx = (values.maxOrNull() ?: 0).coerceAtLeast(1)
    Canvas(Modifier.fillMaxWidth().height(90.dp)) {
        val n = values.size
        val gap = 6.dp.toPx()
        val bw = (size.width - gap * (n - 1)) / n
        for ((i, v) in values.withIndex()) {
            val h = (v.toFloat() / mx) * size.height
            drawRect(color = if (v == 0) WR.surface3 else color, topLeft = Offset(i * (bw + gap), size.height - maxOf(h, 3f)), size = Size(bw, maxOf(h, 3f)))
        }
    }
}
