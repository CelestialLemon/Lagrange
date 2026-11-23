package com.celestiallemon.lagrange.ui.system

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.ui.InspectorRow
import com.celestiallemon.lagrange.ui.InspectorViewModel
import com.celestiallemon.lagrange.ui.formatClockSeconds
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.theme.EyebrowStyle
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import com.celestiallemon.lagrange.ui.today.archetypeOf
import com.celestiallemon.lagrange.ui.today.label
import com.celestiallemon.lagrange.ui.today.triggerOf
import com.celestiallemon.lagrange.ui.today.badge

/**
 * Debug Timeline Inspector: one logical day of the raw ledger (usage events and notifications, left) next to what
 * the reconstructor made of it (sessions and segments, right), in time order. "Export Day JSON" writes a replayable
 * fixture for JVM regression tests.
 */
@Composable
fun TimelineInspector(padding: PaddingValues, onClose: () -> Unit, vm: InspectorViewModel = viewModel()) {
    BackHandler(onBack = onClose)
    val state by vm.state.collectAsStateWithLifecycle()
    val op by vm.op.collectAsStateWithLifecycle()
    val allTypes by vm.allTypes.collectAsStateWithLifecycle()
    val zone = state.window?.zone ?: java.time.ZoneId.systemDefault()
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportFixture)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Space.m,
            end = Space.m,
            top = padding.calculateTopPadding() + Space.m,
            bottom = padding.calculateBottomPadding() + Space.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item("header") {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s), modifier = Modifier.padding(bottom = Space.m)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to System & Data") }
                    Column(Modifier.weight(1f)) {
                        Text("DEBUG", style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
                        Text("Timeline Inspector", style = MaterialTheme.typography.headlineSmall)
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { vm.shiftDay(-1) }) { Icon(Icons.Outlined.ChevronLeft, "Previous day") }
                    Text(
                        formatDay(state.date),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { vm.shiftDay(1) }) { Icon(Icons.Outlined.ChevronRight, "Next day") }
                }
                Text(
                    "${state.rawEventCount} events · ${state.notificationCount} notifications  →  ${state.sessionCount} sessions · ${state.segmentCount} segments",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    FilledTonalButton(
                        onClick = { export.launch(vm.suggestedFileName()) },
                        enabled = !op.busy && state.window != null,
                    ) {
                        Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.s))
                        Text("Export Day JSON")
                    }
                    FilterChip(selected = allTypes, onClick = { vm.setAllTypes(!allTypes) }, label = { Text("All event types") })
                }
                op.message?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = if (op.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.loading || op.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth().padding(top = Space.s)) {
                    Text("RAW LEDGER", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 64.dp))
                    Text("RECONSTRUCTED", style = EyebrowStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                }
            }
        }
        items(state.rows, key = { rowKey(it) }) { row ->
            InspectorLine(row, zone)
        }
    }
}

private fun rowKey(r: InspectorRow): String = when (r) {
    is InspectorRow.RawEvent -> "e${r.event.id}"
    is InspectorRow.Notification -> "n${r.n.id}"
    is InspectorRow.SessionStart -> "s${r.episode.id}"
    is InspectorRow.SegmentStart -> "g${r.segment.id}"
}

@Composable
private fun InspectorLine(row: InspectorRow, zone: java.time.ZoneId) {
    val mono = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(formatClockSeconds(row.atMs, zone), style = mono, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(64.dp).padding(top = 4.dp))
        Box(Modifier.weight(1f).padding(end = 4.dp)) {
            when (row) {
                is InspectorRow.RawEvent -> Cell(eventLabel(row.event.eventType), shortName(row.event.packageName), eventColor(row.event.eventType))
                is InspectorRow.Notification -> Cell(
                    "NOTIF" + if (row.n.isOngoing || row.n.isForegroundService) " (ongoing)" else "",
                    shortName(row.packageName) + (row.n.removedAtMs?.let { " ✕${formatClockSeconds(it, zone)} ${row.n.removalReason.lowercase()}" } ?: ""),
                    MaterialTheme.colorScheme.secondary,
                )
                else -> Unit
            }
        }
        Box(Modifier.weight(1f)) {
            when (row) {
                is InspectorRow.SessionStart -> {
                    val s = row.episode.session
                    Cell(
                        (archetypeOf(s.archetype)?.label ?: s.archetype).uppercase() + " · ${formatDuration(s.activeDurationMs)}",
                        "${triggerOf(s.triggerEvidence)?.badge ?: s.triggerEvidence} · ${s.physicalPickupCount}p · ${s.episodeKey}" +
                            (s.contextFlags?.let { " · $it" } ?: "") + (s.worthItRating?.let { " · ★$it" } ?: ""),
                        if (s.isRabbitHole) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    )
                }
                is InspectorRow.SegmentStart -> Cell(
                    "  ↳ ${shortName(row.packageName)}",
                    formatDuration(row.segment.durationMs) + (if (row.segment.isBlockedAttempt) " · blocked" else "") +
                        (row.segment.segmentTriggerEvidence?.let { " · ${triggerOf(it)?.badge ?: it}" } ?: ""),
                    MaterialTheme.colorScheme.onSurface,
                )
                else -> Unit
            }
        }
    }
}

@Composable
private fun Cell(title: String, detail: String, color: Color) {
    Surface(shape = RoundedCornerShape(8.dp), color = color.copy(alpha = 0.08f), modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 6.dp, vertical = 3.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.padding(top = 5.dp).size(6.dp).background(color, RoundedCornerShape(3.dp)))
            Spacer(Modifier.width(4.dp))
            Column {
                Text(title, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private fun shortName(pkg: String): String = pkg.removePrefix("com.").removePrefix("android.")

private fun eventLabel(type: Int): String = when (type) {
    1 -> "RESUMED"
    2 -> "PAUSED"
    12 -> "NOTIF_INTERRUPT"
    15 -> "SCREEN_ON"
    16 -> "SCREEN_OFF"
    17 -> "KEYGUARD_SHOWN"
    18 -> "KEYGUARD_HIDDEN"
    23 -> "STOPPED"
    26 -> "SHUTDOWN"
    27 -> "STARTUP"
    else -> "TYPE $type"
}

@Composable
private fun eventColor(type: Int): Color {
    val s = LocalStatusColors.current
    return when (type) {
        1 -> s.good
        2, 23 -> s.neutral
        15, 18 -> MaterialTheme.colorScheme.primary
        16, 17 -> s.warn
        26, 27 -> s.bad
        else -> s.neutral
    }
}
