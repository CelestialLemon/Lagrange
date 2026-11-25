package com.celestiallemon.lagrange.ui.system

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.DataObject
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.TableChart
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.backup.BackupFormat
import com.celestiallemon.lagrange.backup.CsvExporter
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.harvest.HarvestResult
import com.celestiallemon.lagrange.ui.DataViewModel
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.DrillIn
import com.celestiallemon.lagrange.ui.components.EditSheet
import com.celestiallemon.lagrange.ui.components.Info
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.LeadingIcon
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.Panel
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.SectionHeader
import com.celestiallemon.lagrange.ui.components.Stat
import com.celestiallemon.lagrange.ui.components.StatStrip
import com.celestiallemon.lagrange.ui.components.Stepper
import com.celestiallemon.lagrange.ui.components.SwitchRow
import com.celestiallemon.lagrange.ui.components.ValueRow
import com.celestiallemon.lagrange.ui.formatAgo
import com.celestiallemon.lagrange.ui.formatCount
import com.celestiallemon.lagrange.ui.formatDate
import com.celestiallemon.lagrange.ui.formatDateTime
import com.celestiallemon.lagrange.ui.formatHour
import com.celestiallemon.lagrange.ui.friction.DigestSettingsCard
import com.celestiallemon.lagrange.ui.friction.FrictionViewModel
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.Transitions
import com.celestiallemon.lagrange.ui.theme.rise

/**
 * Housekeeping, in the order it matters: is Lagrange able to record, what it has recorded, your data, then
 * preferences, then the tools for when something looks wrong.
 */
@Composable
fun SettingsScreen(
    main: MainViewModel,
    padding: PaddingValues = PaddingValues(),
    inspecting: Boolean = false,
    onInspectingChange: (Boolean) -> Unit = {},
    scrollToTopToken: Int = 0,
    data: DataViewModel = viewModel(),
) {
    SettingsHost(
        inspecting = inspecting,
        onInspectingChange = onInspectingChange,
        scrollToTopToken = scrollToTopToken,
        base = { listState ->
            SettingsContent(main, padding, data, listState = listState, onOpenInspector = { onInspectingChange(true) })
        },
        inspector = { TimelineInspector(padding, onClose = { onInspectingChange(false) }) },
    )
}

@Composable
internal fun SettingsHost(
    inspecting: Boolean,
    onInspectingChange: (Boolean) -> Unit,
    scrollToTopToken: Int,
    base: @Composable (LazyListState) -> Unit,
    inspector: @Composable () -> Unit,
) {
    val listState = rememberLazyListState()
    var handledScrollToken by rememberSaveable { mutableIntStateOf(scrollToTopToken) }
    LaunchedEffect(scrollToTopToken, inspecting) {
        if (!inspecting && scrollToTopToken != handledScrollToken) {
            listState.scrollToItem(0)
            handledScrollToken = scrollToTopToken
        }
    }
    DrillIn(
        top = if (inspecting) Unit else null,
        onBack = { onInspectingChange(false) },
        base = { base(listState) },
        detail = { inspector() },
    )
}

@Composable
private fun SettingsContent(
    main: MainViewModel,
    padding: PaddingValues,
    data: DataViewModel,
    listState: LazyListState,
    onOpenInspector: () -> Unit,
) {
    val health by main.health.collectAsStateWithLifecycle()
    val stats by main.stats.collectAsStateWithLifecycle()
    val prefs by main.prefs.collectAsStateWithLifecycle()
    val harvesting by main.harvesting.collectAsStateWithLifecycle()
    val lastHarvest by main.lastHarvest.collectAsStateWithLifecycle()
    val op by data.op.collectAsStateWithLifecycle()
    val staged by data.restoreStaged.collectAsStateWithLifecycle()
    val probe by data.probe.collectAsStateWithLifecycle()
    val friction: FrictionViewModel = viewModel()
    val frictionPrefs by friction.prefs.collectAsStateWithLifecycle()
    var confirmReset by remember { mutableStateOf(false) }
    var editingRollover by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()

    val createBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(data::backUpTo) }
    val openRestore = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(data::stageRestore) }
    val createSqlite = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/vnd.sqlite3")) { uri -> uri?.let(data::exportSqlite) }
    val createCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri -> uri?.let(data::exportCsv) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(data::setBackupFolder) }

    LagrangeScreen(title = "Settings", contentPadding = padding, listState = listState) {
        // -- Health summary -------------------------------------------------------------------------------------------
        item("summary") {
            val issues = listOf(!health.usageAccess, !health.notificationAccess, stats.harvestOverdue(now)).count { it }
            val c = Lagrange.colors
            Panel(
                Modifier.rise(0),
                color = if (issues == 0) c.good.container else c.caution.container,
                contentColor = if (issues == 0) c.good.onContainer else c.caution.onContainer,
                spacing = Spacing.xs,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (issues == 0) Icons.Outlined.CheckCircle else Icons.Outlined.WarningAmber, null)
                    Text(
                        if (issues == 0) "  Recording normally" else "  $issues thing${if (issues == 1) "" else "s"} need${if (issues == 1) "s" else ""} attention",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Text(
                    "Last harvest ${formatAgo(stats.lastHarvestAtMs, now)} · notification listener ${if (stats.listenerActive) "connected" else "offline"}",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }

        item("perm-header") { SectionHeader("Permissions", supporting = "What lets Lagrange record and pause") }
        item("perms") { PermissionList(health, compact = false, onChanged = main::refreshHealth) }

        // -- Recording ------------------------------------------------------------------------------------------------
        item("rec-header") {
            SectionHeader(
                "Recording",
                info = Info(
                    "How recording works",
                    "Android keeps only about a week of raw usage events, so Lagrange copies them into its own database as " +
                        "they happen: when the screen turns off, when you open the app, and every hour or two in the background." +
                        "\n\nApps inside Private Space or a work profile are invisible to Usage Access, so they never appear here.",
                ),
            )
        }
        item("rec") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Panel(spacing = Spacing.m) {
                    StatStrip {
                        Stat("Usage events", formatCount(stats.rawEventsTotal), Modifier.weight(1f), caption = "${formatCount(stats.rawEventsToday)} today")
                        Stat("Notifications", formatCount(stats.notificationsTotal), Modifier.weight(1f), caption = "${formatCount(stats.notificationsToday)} today")
                    }
                    StatStrip {
                        Stat("History since", stats.earliestEventMs?.let(::formatDate) ?: "–", Modifier.weight(1f))
                        Stat("Gaps", stats.gapCount.toString(), Modifier.weight(1f), caption = "silences over 48 h", captionTone = if (stats.gapCount > 0) Tone.Caution else Tone.Neutral)
                    }
                    lastHarvest?.let { Text(describe(it), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    AnimatedVisibility(harvesting, enter = Transitions.reveal, exit = Transitions.conceal) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                }
                if (stats.harvestOverdue(now)) {
                    Banner("Nothing recorded for over 48 hours", Tone.Caution, Icons.Outlined.WarningAmber, body = "Open Lagrange regularly or allow unrestricted battery so recording keeps up.")
                }
                RowGroup {
                    ListRow(
                        "Record now",
                        supporting = "Copy the latest events from Android",
                        leading = { LeadingIcon(Icons.Outlined.Sync) },
                        onClick = if (!harvesting && health.usageAccess) main::harvestNow else null,
                    )
                    stats.backfill?.let { b ->
                        ListRow("Imported history", supporting = "Reaches back to ${formatDate(b.startMs)}. These days have no notification data and are marked as such.", leading = { LeadingIcon(Icons.Outlined.Download) })
                    }
                }
            }
        }

        // -- Data -----------------------------------------------------------------------------------------------------
        item("data-header") {
            SectionHeader(
                "Backups and exports",
                info = Info(
                    "Where your data goes",
                    "Nowhere, unless you save a file somewhere yourself. Backups are verified ZIPs (a database snapshot, your " +
                        "preferences and checksums). A local folder or Syncthing stays fully self-hosted; a cloud provider syncs " +
                        "the files under its own rules.\n\nThe CSV bundle holds daily summaries, per-app days, sessions, session " +
                        "segments, notification metadata and friction events, with local and UTC timestamps.",
                ),
            )
        }
        item("data") {
            val p = prefs
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                if (staged) {
                    Banner(
                        "A verified backup is ready to restore",
                        Tone.Brand,
                        Icons.Outlined.Restore,
                        body = "Restarting swaps it in before the database opens. Your current data is kept as a rollback copy.",
                        action = "Restart and restore" to data::restartToApplyRestore,
                        onDismiss = { data.cancelRestore() },
                    )
                }
                RowGroup {
                    ValueRow(
                        "Weekly backups",
                        if (p?.backupTreeUri == null) "Off" else "On",
                        onClick = { if (!op.busy) pickFolder.launch(null) },
                        leading = { LeadingIcon(Icons.Outlined.FolderOpen, if (p?.backupTreeUri != null) Tone.Good else Tone.Neutral) },
                        supporting = if (p?.backupTreeUri == null) "Choose a folder; the newest 8 are kept" else "Last ${formatAgo(p.lastBackupAtMs, now)} · tap to change folder",
                    )
                    p?.lastBackupStatus?.takeIf { it != LagrangePreferences.STATUS_OK }?.let {
                        ListRow("Last backup failed", supporting = it, leading = { LeadingIcon(Icons.Outlined.WarningAmber, Tone.Critical) })
                    }
                    if (p?.backupTreeUri != null) {
                        ListRow("Back up now", leading = { LeadingIcon(Icons.Outlined.Backup) }, onClick = if (!op.busy) data::backUpToFolderNow else null)
                        ListRow("Turn off weekly backups", leading = { LeadingIcon(Icons.Outlined.CloudOff) }, onClick = if (!op.busy) data::clearBackupFolder else null)
                    }
                    ListRow("Save a backup…", supporting = "A single .zip, anywhere you choose", leading = { LeadingIcon(Icons.Outlined.Save) }, onClick = if (!op.busy) ({ createBackup.launch(BackupFormat.fileName(System.currentTimeMillis())) }) else null)
                    ListRow("Restore from a backup…", leading = { LeadingIcon(Icons.Outlined.Restore) }, onClick = if (!op.busy) ({ openRestore.launch(BackupFormat.ZIP_MIME_TYPES) }) else null)
                    ListRow("Export database", supporting = ".sqlite for DuckDB, Python or DB Browser", leading = { LeadingIcon(Icons.Outlined.DataObject) }, onClick = if (!op.busy) ({ createSqlite.launch("lagrange-${System.currentTimeMillis() / 1000}.sqlite") }) else null)
                    ListRow("Export spreadsheets", supporting = "Six CSV files in a .zip", leading = { LeadingIcon(Icons.Outlined.TableChart) }, onClick = if (!op.busy) ({ createCsv.launch(CsvExporter.fileName(System.currentTimeMillis())) }) else null)
                }
                AnimatedVisibility(op.busy, enter = Transitions.reveal, exit = Transitions.conceal) { LinearProgressIndicator(Modifier.fillMaxWidth()) }
                op.message?.let { msg ->
                    Banner(
                        msg,
                        if (op.isError) Tone.Critical else Tone.Neutral,
                        if (op.isError) Icons.Outlined.WarningAmber else Icons.Outlined.CheckCircle,
                        onDismiss = if (!op.busy) data::clearMessage else null,
                    )
                }
            }
        }

        // -- Preferences ----------------------------------------------------------------------------------------------
        frictionPrefs?.let { fp ->
            item("digest-header") { SectionHeader("Reflection digests", supporting = "A quiet local notification about your day or week") }
            item("digest") { DigestSettingsCard(fp, friction) }
        }
        item("prefs-header") { SectionHeader("Preferences") }
        item("prefs") {
            RowGroup {
                ValueRow(
                    "Day starts at",
                    formatHour(prefs?.rolloverHour ?: 4),
                    onClick = { editingRollover = true },
                    leading = { LeadingIcon(Icons.Outlined.WbTwilight) },
                    supporting = "A late night stays on the day it started",
                )
                SwitchRow(
                    "Wallpaper colours",
                    checked = prefs?.useDynamicColor == true,
                    onCheckedChange = main::setDynamicColor,
                    supporting = "Buttons and surfaces follow your wallpaper. Data colours never change.",
                    leading = { LeadingIcon(Icons.Outlined.Palette) },
                )
            }
        }

        // -- Tools ----------------------------------------------------------------------------------------------------
        item("tools-header") { SectionHeader("Tools") }
        item("tools") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                RowGroup {
                    ListRow("Timeline inspector", supporting = "Raw events beside the sessions rebuilt from them; export a day as a test fixture", leading = { LeadingIcon(Icons.Outlined.BugReport) }, onClick = onOpenInspector)
                    ListRow("Check usage history", supporting = "How far back Android's own history reaches on this phone", leading = { LeadingIcon(Icons.Outlined.Radar) }, onClick = if (!op.busy && health.usageAccess) data::runProbe else null)
                }
                probe?.let { pr ->
                    Panel(spacing = Spacing.xs) {
                        Text("Last 30 days, as Android reports them", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${formatCount(pr.totalEvents)} events · history reaches " +
                                (pr.retentionDays(pr.scannedToMs)?.let { String.format(java.util.Locale.US, "%.1f days", it) } ?: "nothing") +
                                " back · interruption events " + if (pr.notificationInterruptionSeen) "seen ×${pr.notificationInterruptionCount}" else "not returned",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        item("danger") {
            RowGroup(Modifier.padding(top = Spacing.xl)) {
                ListRow(
                    "Delete all data",
                    supporting = "Erases everything on this phone. Backups you saved elsewhere are untouched.",
                    leading = { LeadingIcon(Icons.Outlined.DeleteForever, Tone.Critical) },
                    onClick = if (!op.busy) ({ confirmReset = true }) else null,
                    supportingColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (editingRollover) {
        var hour by remember { mutableIntStateOf(prefs?.rolloverHour ?: 4) }
        EditSheet(
            "Day starts at",
            supporting = "Sessions before this hour count toward the previous day. Changes are kept in settings history.",
            onDismiss = { editingRollover = false },
            onConfirm = {
                main.setRolloverHour(hour)
                editingRollover = false
            },
        ) {
            Stepper(formatHour(hour), onMinus = { hour = (hour + 23) % 24 }, onPlus = { hour = (hour + 1) % 24 }, minusDescription = "An hour earlier", plusDescription = "An hour later")
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
