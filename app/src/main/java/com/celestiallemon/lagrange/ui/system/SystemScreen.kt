package com.celestiallemon.lagrange.ui.system

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.backup.BackupFormat
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.harvest.HarvestResult
import com.celestiallemon.lagrange.ui.DataViewModel
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatRow
import com.celestiallemon.lagrange.ui.components.StatTile
import com.celestiallemon.lagrange.ui.formatAgo
import com.celestiallemon.lagrange.ui.formatCount
import com.celestiallemon.lagrange.ui.formatDate
import com.celestiallemon.lagrange.ui.formatDateTime
import com.celestiallemon.lagrange.ui.formatHour
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space

@Composable
fun SystemScreen(main: MainViewModel, padding: PaddingValues, data: DataViewModel = viewModel()) {
    val health by main.health.collectAsStateWithLifecycle()
    val stats by main.stats.collectAsStateWithLifecycle()
    val prefs by main.prefs.collectAsStateWithLifecycle()
    val harvesting by main.harvesting.collectAsStateWithLifecycle()
    val lastHarvest by main.lastHarvest.collectAsStateWithLifecycle()
    val op by data.op.collectAsStateWithLifecycle()
    val staged by data.restoreStaged.collectAsStateWithLifecycle()
    val probe by data.probe.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()
    val status = LocalStatusColors.current

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        uri?.let(data::backUpTo)
    }
    val openRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(data::stageRestore)
    }
    val createSqlite = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri ->
        uri?.let(data::exportSqlite)
    }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(data::setBackupFolder)
    }

    LagrangeScreen(
        eyebrow = "System & Data",
        title = "Under the hood",
        subtitle = "Permissions, collection health and your data: all of it on this device, none of it online.",
        contentPadding = padding,
    ) {
        item("health") {
            SectionCard(title = "Permission Health", eyebrow = "Collectors", icon = Icons.Outlined.HealthAndSafety) {
                PermissionList(health, compact = false, onChanged = main::refreshHealth)
            }
        }

        item("collection") {
            SectionCard(title = "Data collection", eyebrow = "Raw ledger", icon = Icons.Outlined.Storage) {
                StatRow {
                    StatTile("Usage events", formatCount(stats.rawEventsTotal), Modifier.weight(1f), caption = "${formatCount(stats.rawEventsToday)} today")
                    StatTile("Notifications", formatCount(stats.notificationsTotal), Modifier.weight(1f), caption = "${formatCount(stats.notificationsToday)} today")
                }
                StatRow {
                    StatTile("Last harvest", formatAgo(stats.lastHarvestAtMs, now), Modifier.weight(1f))
                    StatTile("History from", stats.earliestEventMs?.let(::formatDate) ?: "—", Modifier.weight(1f))
                }
                StatRow {
                    StatTile("Listener", if (stats.listenerActive) "Connected" else "Offline", Modifier.weight(1f))
                    StatTile("Harvest gaps", stats.gapCount.toString(), Modifier.weight(1f), caption = "silences over 48 h")
                }
                stats.backfill?.let { b ->
                    Text(
                        "Initial backfill reached back to ${formatDate(b.startMs)}. Backfilled days have no notification coverage and are marked as such.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (stats.harvestOverdue(now)) {
                    Text(
                        "No harvest for over 48 hours. Android keeps only about a week of events, so open Lagrange regularly or allow unrestricted battery.",
                        style = MaterialTheme.typography.bodySmall,
                        color = status.warn,
                    )
                }
                lastHarvest?.let { Text(describe(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                if (harvesting) LinearProgressIndicator(Modifier.fillMaxWidth())
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Button(onClick = main::harvestNow, enabled = !harvesting && health.usageAccess) { Text("Harvest now") }
                    OutlinedButton(onClick = data::runProbe, enabled = !op.busy && health.usageAccess) { Text("Probe usage history") }
                }
                probe?.let { p ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text("Platform probe (last 30 days)", style = MaterialTheme.typography.titleSmall)
                            Text("Events visible: ${formatCount(p.totalEvents)}", style = MaterialTheme.typography.bodySmall)
                            Text(
                                "Retention: " + (p.retentionDays(p.scannedToMs)?.let { String.format(java.util.Locale.US, "%.1f days", it) } ?: "none"),
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "NOTIFICATION_INTERRUPTION (type 12): " +
                                    if (p.notificationInterruptionSeen) "seen ×${p.notificationInterruptionCount}" else "not returned",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        item("rollover") {
            SectionCard(title = "Logical day", eyebrow = "Time model", icon = Icons.Outlined.WbTwilight) {
                Text(
                    "Your day rolls over at this hour, so a late-night session stays on the night it started. Changes are kept in settings history.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val hour = prefs?.rolloverHour ?: 4
                Row(verticalAlignment = Alignment.CenterVertically) {
                    FilledTonalIconButton(onClick = { main.setRolloverHour((hour + 23) % 24) }) { Text("−") }
                    Spacer(Modifier.width(Space.l))
                    Text(formatHour(hour), style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(Space.l))
                    FilledTonalIconButton(onClick = { main.setRolloverHour((hour + 1) % 24) }) { Text("+") }
                }
            }
        }

        item("appearance") {
            SectionCard(title = "Appearance", eyebrow = "Theme", icon = Icons.Outlined.Palette) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Wallpaper colours", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Use Material You dynamic colour instead of Lagrange's cosmic palette.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = prefs?.useDynamicColor == true, onCheckedChange = main::setDynamicColor)
                }
            }
        }

        item("backups") {
            SectionCard(title = "Backups", eyebrow = "Keep history safe", icon = Icons.Outlined.Backup) {
                Text(
                    "Backups are verified ZIPs (database snapshot, preferences and checksums). Local storage or Syncthing stays fully self-hosted; a cloud provider will sync the files under its own rules.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val p = prefs
                Text(
                    when {
                        p?.backupTreeUri != null -> "Weekly backups on · newest 8 kept"
                        else -> "Weekly backups off"
                    } + " · last backup ${formatAgo(p?.lastBackupAtMs, now)}",
                    style = MaterialTheme.typography.titleSmall,
                )
                p?.lastBackupStatus?.takeIf { it != LagrangePreferences.STATUS_OK }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = status.bad)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    FilledTonalButton(onClick = { pickFolder.launch(null) }, enabled = !op.busy) {
                        Text(if (p?.backupTreeUri == null) "Choose weekly folder" else "Change folder")
                    }
                    if (p?.backupTreeUri != null) {
                        OutlinedButton(onClick = data::backUpToFolderNow, enabled = !op.busy) { Text("Back up to folder now") }
                        TextButton(onClick = data::clearBackupFolder, enabled = !op.busy) { Text("Turn off") }
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    OutlinedButton(onClick = { createBackup.launch(BackupFormat.fileName(System.currentTimeMillis())) }, enabled = !op.busy) {
                        Text("Save backup…")
                    }
                    OutlinedButton(onClick = { openRestore.launch(BackupFormat.ZIP_MIME_TYPES) }, enabled = !op.busy) {
                        Text("Restore…")
                    }
                    OutlinedButton(onClick = { createSqlite.launch("lagrange-${System.currentTimeMillis() / 1000}.sqlite") }, enabled = !op.busy) {
                        Text("Export .sqlite")
                    }
                }
                if (staged) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                            Text(
                                "A verified backup is staged. Restarting swaps it in before the database opens; your current data is kept as a rollback copy.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                            Row {
                                Button(onClick = data::restartToApplyRestore, enabled = !op.busy) { Text("Restart & restore") }
                                Spacer(Modifier.width(Space.s))
                                TextButton(onClick = { data.cancelRestore() }, enabled = !op.busy) { Text("Discard") }
                            }
                        }
                    }
                }
                if (op.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                op.message?.let { msg ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (op.isError) status.bad else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (!op.busy) TextButton(onClick = data::clearMessage) { Text("Dismiss") }
                    }
                }
            }
        }

        item("notes") {
            SectionCard(title = "Blind spots", eyebrow = "Platform limits", icon = Icons.Outlined.Info) {
                Text(
                    "Apps inside Private Space or a work profile are invisible to Usage Access, so they never appear here. Android also keeps only about a week of raw events, which is why Lagrange harvests continuously.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item("danger") {
            SectionCard(
                title = "Delete all data",
                eyebrow = "Danger zone",
                icon = Icons.Outlined.DeleteForever,
                container = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.45f),
            ) {
                Text(
                    "Erases the ledger, categories, preferences and staged restores on this device. Backups you saved elsewhere are not touched.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                OutlinedButton(
                    onClick = { confirmReset = true },
                    enabled = !op.busy,
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete everything…") }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            icon = { Icon(Icons.Outlined.DeleteForever, contentDescription = null) },
            title = { Text("Delete all Lagrange data?") },
            text = { Text("This can't be undone. Consider saving a backup first. Lagrange restarts afterwards.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    data.resetAll()
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
}

private fun describe(result: HarvestResult): String = when (result) {
    HarvestResult.NoAccess -> "Last run: Usage Access is not granted."
    is HarvestResult.Unavailable -> "Last run: nothing available (${result.reason}). The cursor did not move."
    is HarvestResult.Success ->
        "Last run: ${formatCount(result.eventsSeen)} events read, ${formatCount(result.eventsInserted)} new" +
            (if (result.wasBackfill) " · initial backfill" else "") +
            (if (result.gapDetected) " · gap recorded" else "") +
            " · up to ${formatDateTime(result.toMs)}"
}
