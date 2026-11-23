package com.celestiallemon.lagrange.ui.friction

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.data.CatalogApp
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.domain.friction.AppFrictionOverride
import com.celestiallemon.lagrange.system.Check
import com.celestiallemon.lagrange.system.PermissionHealthChecker
import com.celestiallemon.lagrange.ui.CatalogViewModel
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatRow
import com.celestiallemon.lagrange.ui.components.StatTile
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import com.celestiallemon.lagrange.ui.today.BudgetDialog
import kotlinx.coroutines.delay

private enum class AppFilter(val label: String) {
    ALL("All"),
    DISTRACTING("Distracting"),
    FRICTION_ON("Friction on"),
}

@Composable
fun FrictionScreen(
    main: MainViewModel,
    padding: PaddingValues,
    catalog: CatalogViewModel = viewModel(),
    vm: FrictionViewModel = viewModel(),
) {
    val context = LocalContext.current
    val statusColors = LocalStatusColors.current
    val health by main.health.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val liveStatus by vm.liveStatus.collectAsStateWithLifecycle()
    val blockedToday by vm.blockedToday.collectAsStateWithLifecycle()
    val apps by catalog.apps.collectAsStateWithLifecycle()

    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(liveStatus.snoozeUntilElapsedMs, liveStatus.focusBoostUntilElapsedMs, liveStatus.activeTimerRemainingMs) {
        while (true) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(15_000L)
        }
    }

    var showBudgetDialog by remember { mutableStateOf(false) }
    var showTierDialog by remember { mutableStateOf(false) }
    var showRedirectPicker by remember { mutableStateOf(false) }
    var editingSchedule by remember { mutableStateOf<FrictionScheduleEntity?>(null) }
    var creatingSchedule by remember { mutableStateOf(false) }
    var customizingApp by remember { mutableStateOf<CatalogApp?>(null) }
    var appFilter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var appSearch by rememberSaveable { mutableStateOf("") }

    val loadedPrefs = prefs
    val appList = apps.orEmpty()
    val filteredApps = remember(appList, appFilter, appSearch) {
        val q = appSearch.trim()
        appList.filter { app ->
            val matchesFilter = when (appFilter) {
                AppFilter.ALL -> true
                AppFilter.DISTRACTING -> app.category == AppCategory.DISTRACTING
                AppFilter.FRICTION_ON -> app.isFrictionEligible
            }
            val matchesQuery = q.isEmpty() ||
                app.displayName.contains(q, ignoreCase = true) ||
                app.packageName.contains(q, ignoreCase = true)
            matchesFilter && matchesQuery
        }
    }

    val snoozed = liveStatus.isSnoozed(nowElapsed)
    val focusBoost = liveStatus.isFocusBoostActive(nowElapsed)

    LagrangeScreen(
        eyebrow = "Friction",
        title = "Gentle by design",
        subtitle = "Mindful pauses, cross-app soft timers and time-blindness nudges—on just the apps you choose.",
        contentPadding = padding,
    ) {
        item("live-status") {
            SectionCard(title = "Friction engine", eyebrow = "Live status", icon = Icons.Outlined.Spa) {
                // Accessibility service status + 1-tap settings jump
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text("Accessibility service", style = MaterialTheme.typography.titleSmall)
                        Text(
                            when {
                                health.accessibilityConnected -> "Running · watches foreground switches only, never screen content."
                                health.accessibilityEnabled -> "Enabled in Settings, waiting to connect."
                                else -> "Turn on Lagrange in Accessibility Settings to draw calm overlays."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(Space.s))
                    StatusPill(
                        text = when {
                            health.accessibilityConnected -> "Connected"
                            health.accessibilityEnabled -> "Enabled"
                            else -> "Off"
                        },
                        color = when {
                            health.accessibilityConnected -> statusColors.good
                            health.accessibilityEnabled -> statusColors.warn
                            else -> statusColors.neutral
                        },
                    )
                }

                if (!health.accessibilityConnected) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Button(onClick = { context.launchSafe(PermissionHealthChecker.accessibilityIntent()) }) {
                            Text("Open Accessibility Settings")
                        }
                        TextButton(onClick = { context.launchSafe(PermissionHealthChecker.appInfoIntent(context)) }) {
                            Text("Allow restricted settings")
                        }
                    }
                }

                if (health.advancedProtection == Check.YES) {
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.GppMaybe, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(Space.s))
                            Text(
                                "Android Advanced Protection is on. Sideloaded accessibility services may be restricted by Android until Advanced Protection is turned off. Analytics are unaffected.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                            )
                        }
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                // Master Friction Enabled switch
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Enable gentle friction", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Leave off during your initial baseline, or enable for just 1–2 apps below.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(Space.s))
                    Switch(
                        checked = loadedPrefs?.frictionEnabled == true,
                        onCheckedChange = vm::setFrictionEnabled,
                    )
                }

                StatRow {
                    StatTile(
                        label = "Paused today",
                        value = blockedToday.toString(),
                        modifier = Modifier.weight(1f),
                        caption = "chose to exit",
                    )
                    StatTile(
                        label = "Active timer",
                        value = liveStatus.activeTimerRemainingMs?.takeIf { it > 0 }?.let(::formatDurationShort) ?: "None",
                        modifier = Modifier.weight(1f),
                        caption = liveStatus.activeTimerInitialMin?.let { "${it}m session" } ?: "cross-app",
                    )
                }

                // 1-Hour Snooze & Focus Boost 1h quick actions
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (snoozed) {
                        FilledTonalButton(onClick = vm::cancelSnooze) {
                            Icon(Icons.Outlined.PauseCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Snoozed · ${formatDurationShort(liveStatus.snoozeRemainingMs(nowElapsed))} left · Resume")
                        }
                    } else {
                        OutlinedButton(onClick = vm::activateSnooze1h) {
                            Icon(Icons.Outlined.PauseCircleOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Snooze 1h")
                        }
                    }
                    if (focusBoost) {
                        Button(onClick = vm::cancelFocusBoost) {
                            Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Focus Boost · ${formatDurationShort(liveStatus.focusBoostRemainingMs(nowElapsed))} left")
                        }
                    } else {
                        FilledTonalButton(onClick = vm::activateFocusBoost1h) {
                            Icon(Icons.Outlined.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Focus Boost 1h (+1 tier)")
                        }
                    }
                }
            }
        }

        if (loadedPrefs != null) {
            item("controls") {
                SectionCard(title = "Pause, timers & nudges", eyebrow = "Gentle rules", icon = Icons.Outlined.HourglassTop) {
                    // Daily distracting budget link
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Daily distracting budget", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Shown on launch pauses and as the reference line on Today & Trends.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        FilledTonalButton(onClick = { showBudgetDialog = true }) {
                            Text(formatDurationShort(loadedPrefs.dailyBudgetMs))
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Escalation toggle & tier preview
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Escalating launch pauses", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "Driven only by real opens and distracting time—blocked attempts never escalate you.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(Space.s))
                        Switch(checked = loadedPrefs.escalationEnabled, onCheckedChange = vm::setEscalationEnabled)
                    }

                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Pause tiers", style = MaterialTheme.typography.labelLarge)
                                TextButton(onClick = { showTierDialog = true }, contentPadding = PaddingValues(horizontal = 8.dp)) {
                                    Text("Customize")
                                }
                            }
                            Text("Tier 0 · 1–2 opens & <15m → ${loadedPrefs.tier0PauseSec}s", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tier 1 · 3–5 opens or 15m+ → ${loadedPrefs.tier1PauseSec}s + timers", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tier 2 · 6–10 opens or 45m+ → ${loadedPrefs.tier2PauseSec}s + intention", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("Tier 3 · 11+ opens or 90m+ → ${loadedPrefs.tier3PauseSec}s + budget", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Switch-back grace window
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("Switch-back grace window", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Briefly checking a link or OTP and returning won't re-trigger the launch pause.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            listOf(0 to "Off", 60 to "1m", 120 to "2m", 300 to "5m").forEach { (sec, label) ->
                                FilterChip(
                                    selected = loadedPrefs.graceWindowSec == sec,
                                    onClick = { vm.setGraceWindowSec(sec) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    // Time-blindness nudges
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("Time-blindness nudges", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Non-blocking top banner when continuous distracting time crosses each milestone.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            listOf(0 to "Off", 10 to "10m", 15 to "15m", 20 to "20m", 30 to "30m").forEach { (min, label) ->
                                FilterChip(
                                    selected = loadedPrefs.nudgeIntervalMin == min,
                                    onClick = { vm.setNudgeIntervalMin(min) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    // Soft session timer presets
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("Soft session timer presets", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Counts down monotonically across all distracting apps; pauses on utility and neutral apps.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            listOf(2, 5, 10, 15, 20, 30).forEach { min ->
                                FilterChip(
                                    selected = min in loadedPrefs.timerPresetsMin,
                                    onClick = { vm.toggleTimerPreset(min) },
                                    label = { Text("${min}m") },
                                )
                            }
                        }
                    }

                    // Optional daily prompt cap
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("Daily prompt cap", style = MaterialTheme.typography.titleSmall)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            listOf<Pair<Int?, String>>(null to "No cap", 10 to "10/day", 20 to "20/day", 30 to "30/day").forEach { (cap, label) ->
                                FilterChip(
                                    selected = loadedPrefs.dailyPromptCap == cap,
                                    onClick = { vm.setDailyPromptCap(cap) },
                                    label = { Text(label) },
                                )
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    // Healthy redirect app picker
                    val redirectName = loadedPrefs.redirectPackageName?.let { pkg ->
                        appList.firstOrNull { it.packageName == pkg }?.displayName ?: pkg
                    } ?: "None"
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("Healthy redirect app", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "1-tap replacement on launch pauses (e.g., Kindle, Notes).",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(Space.s))
                        OutlinedButton(onClick = { showRedirectPicker = true }) {
                            Text(redirectName, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item("schedules") {
            SectionCard(title = "Contextual schedules", eyebrow = "Time windows", icon = Icons.Outlined.Schedule) {
                Text(
                    "Boost escalation, encourage session timers, or tighten nudges during work hours or late-night wind-down.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (schedules.isEmpty()) {
                    Text(
                        "No schedules yet. Add a preset or create a custom window below.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        schedules.forEach { schedule ->
                            ScheduleRow(
                                schedule = schedule,
                                onToggle = { vm.toggleScheduleEnabled(schedule.id, it) },
                                onEdit = { editingSchedule = schedule },
                                onDelete = { vm.deleteSchedule(schedule.id) },
                            )
                        }
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    if (schedules.none { it.name.equals("Work Hours", ignoreCase = true) }) {
                        FilledTonalButton(onClick = vm::addWorkHoursPreset) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Work Hours")
                        }
                    }
                    if (schedules.none { it.name.equals("Wind-Down", ignoreCase = true) }) {
                        FilledTonalButton(onClick = vm::addWindDownPreset) {
                            Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(Space.xs))
                            Text("Wind-Down (22:30–04:00)")
                        }
                    }
                    OutlinedButton(onClick = { creatingSchedule = true }) {
                        Icon(Icons.Outlined.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Space.xs))
                        Text("Custom schedule")
                    }
                }
            }
        }

        item("categories") {
            SectionCard(title = "App categories & per-app friction", eyebrow = "Applies to all history", icon = Icons.Outlined.Category) {
                Text(
                    "Recategorizing an app updates past trends too. Toggle launch pauses per app so you can start on just 1–2 apps.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY, AppCategory.EXCLUDED).forEach { c ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.label, style = MaterialTheme.typography.titleSmall)
                                Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill("${appList.count { it.category == c }}", categoryColor(c))
                        }
                    }
                }
                Spacer(Modifier.height(Space.xs))
                OutlinedTextField(
                    value = appSearch,
                    onValueChange = { appSearch = it },
                    placeholder = { Text("Search installed apps…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    AppFilter.entries.forEach { f ->
                        FilterChip(
                            selected = appFilter == f,
                            onClick = { appFilter = f },
                            label = { Text(f.label) },
                        )
                    }
                }
            }
        }

        if (apps == null) {
            item("loading") { CircularProgressIndicator() }
        } else {
            items(filteredApps, key = { it.packageName }) { app ->
                AppCategoryRow(
                    app = app,
                    showFrictionControls = true,
                    onToggleFriction = { enabled ->
                        val next = (app.frictionOverride ?: AppFrictionOverride()).copy(enabled = enabled)
                        catalog.setFrictionOverride(app.packageName, next)
                    },
                    onCustomizeFriction = { customizingApp = app },
                    onChange = { catalog.setCategory(app.packageName, it) },
                )
            }
        }
    }

    if (showBudgetDialog && loadedPrefs != null) {
        BudgetDialog(
            current = loadedPrefs.dailyBudgetMs,
            onDismiss = { showBudgetDialog = false },
            onSave = {
                showBudgetDialog = false
                vm.setDailyBudgetMs(it)
            },
        )
    }

    if (showTierDialog && loadedPrefs != null) {
        TierPausesDialog(
            tier0 = loadedPrefs.tier0PauseSec,
            tier1 = loadedPrefs.tier1PauseSec,
            tier2 = loadedPrefs.tier2PauseSec,
            tier3 = loadedPrefs.tier3PauseSec,
            onDismiss = { showTierDialog = false },
            onSave = { t0, t1, t2, t3 ->
                showTierDialog = false
                vm.setTierPauses(t0, t1, t2, t3)
            },
        )
    }

    if (showRedirectPicker && loadedPrefs != null) {
        RedirectAppPickerDialog(
            apps = appList,
            currentPackage = loadedPrefs.redirectPackageName,
            onDismiss = { showRedirectPicker = false },
            onSelect = { pkg ->
                showRedirectPicker = false
                vm.setRedirectPackage(pkg)
            },
        )
    }

    if (creatingSchedule || editingSchedule != null) {
        ScheduleEditorDialog(
            initial = editingSchedule,
            onDismiss = {
                creatingSchedule = false
                editingSchedule = null
            },
            onSave = { saved ->
                creatingSchedule = false
                editingSchedule = null
                vm.saveSchedule(saved)
            },
        )
    }

    customizingApp?.let { target ->
        AppFrictionOverrideDialog(
            app = target,
            onDismiss = { customizingApp = null },
            onSave = { override ->
                customizingApp = null
                catalog.setFrictionOverride(target.packageName, override)
            },
        )
    }
}

@Composable
private fun ScheduleRow(
    schedule: FrictionScheduleEntity,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val wraps = schedule.startMinuteOfDay > schedule.endMinuteOfDay
    val timeSummary = "${formatDaysMask(schedule.daysOfWeekMask)} · ${formatMinuteOfDay(schedule.startMinuteOfDay)}–${formatMinuteOfDay(schedule.endMinuteOfDay)}" +
        if (wraps) " (overnight)" else ""
    val traits = buildList {
        if (schedule.escalationBoost > 0) add("+${schedule.escalationBoost} tier")
        if (schedule.requireTimerPrompt) add("timer prompt")
        schedule.nudgeIntervalMin?.let { add("${it}m nudge") }
    }.joinToString(" · ")

    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.m, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(schedule.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(timeSummary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (traits.isNotEmpty()) {
                        Text(traits, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
                Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("Edit")
                }
                TextButton(onClick = onDelete, contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Icon(Icons.Outlined.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(Space.xs))
                    Text("Delete")
                }
            }
        }
    }
}

private fun Context.launchSafe(vararg intents: Intent) {
    for (intent in intents.toList() + PermissionHealthChecker.appInfoIntent(this)) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // next
        } catch (_: SecurityException) {
            // next
        }
    }
}
