package com.celestiallemon.lagrange.ui.friction

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GppMaybe
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.NotificationsPaused
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.data.CatalogApp
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.domain.friction.AppFrictionOverride
import com.celestiallemon.lagrange.domain.friction.FrictionLiveStatus
import com.celestiallemon.lagrange.system.Check
import com.celestiallemon.lagrange.system.PermissionHealth
import com.celestiallemon.lagrange.system.PermissionHealthChecker
import com.celestiallemon.lagrange.ui.CatalogViewModel
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.DrillIn
import com.celestiallemon.lagrange.ui.components.EditSheet
import com.celestiallemon.lagrange.ui.components.Info
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.LeadingIcon
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.Meter
import com.celestiallemon.lagrange.ui.components.Panel
import com.celestiallemon.lagrange.ui.components.RollingText
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.SectionHeader
import com.celestiallemon.lagrange.ui.components.Stat
import com.celestiallemon.lagrange.ui.components.StatStrip
import com.celestiallemon.lagrange.ui.components.ValueRow
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Motion
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.Transitions
import com.celestiallemon.lagrange.ui.theme.pressScale
import com.celestiallemon.lagrange.ui.theme.rise
import com.celestiallemon.lagrange.ui.today.BudgetSheet
import kotlinx.coroutines.delay

private enum class AppFilter(val label: String) { ALL("All"), DISTRACTING("Distracting"), FRICTION_ON("Paused") }

private const val HOUR_MS = 3_600_000L

/** Which rule's editor sheet is open. */
private enum class RuleSheet { Budget, Pauses, Grace, Nudges, Timers, Cap, Redirect, AddSchedule }

/** Everything the Friction screen shows. [nowElapsed] drives the live countdowns. */
class FrictionUiModel(
    val prefs: UserPrefs?,
    val health: PermissionHealth,
    val schedules: List<FrictionScheduleEntity>,
    val live: FrictionLiveStatus,
    val blockedToday: Int,
    val error: String?,
    val apps: List<CatalogApp>?,
    val nowElapsed: Long,
)

@Composable
fun FrictionScreen(
    main: MainViewModel,
    padding: PaddingValues,
    actions: @Composable RowScope.() -> Unit = {},
    onOpenSettings: () -> Unit = {},
    catalog: CatalogViewModel = viewModel(),
    vm: FrictionViewModel = viewModel(),
) {
    val health by main.health.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val schedules by vm.schedules.collectAsStateWithLifecycle()
    val live by vm.liveStatus.collectAsStateWithLifecycle()
    val blockedToday by vm.blockedToday.collectAsStateWithLifecycle()
    val error by vm.errorMessage.collectAsStateWithLifecycle()
    val apps by catalog.apps.collectAsStateWithLifecycle()

    // Countdowns tick every second while something is counting; otherwise every 15 s is plenty.
    var nowElapsed by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(live.snoozeUntilElapsedMs, live.focusBoostUntilElapsedMs, live.activeTimerRemainingMs) {
        while (true) {
            nowElapsed = SystemClock.elapsedRealtime()
            delay(if (live.isSnoozed(nowElapsed) || live.isFocusBoostActive(nowElapsed)) 1_000L else 15_000L)
        }
    }
    var appsOpen by rememberSaveable { mutableStateOf(false) }
    val model = FrictionUiModel(prefs, health, schedules, live, blockedToday, error, apps, nowElapsed)

    DrillIn(
        top = if (appsOpen) Unit else null,
        onBack = { appsOpen = false },
        base = { FrictionContent(model, padding, vm, actions, onOpenApps = { appsOpen = true }) },
        detail = { AppsScreen(apps, padding, catalog, onBack = { appsOpen = false }) },
    )
}

@Composable
fun FrictionContent(
    model: FrictionUiModel,
    padding: PaddingValues,
    vm: FrictionViewModel?,
    topActions: @Composable RowScope.() -> Unit = {},
    onOpenApps: () -> Unit = {},
) {
    val context = LocalContext.current
    var sheet by rememberSaveable { mutableStateOf<RuleSheet?>(null) }
    var editingSchedule by remember { mutableStateOf<FrictionScheduleEntity?>(null) }
    var creatingSchedule by remember { mutableStateOf(false) }
    val p = model.prefs
    val appList = model.apps.orEmpty()

    LagrangeScreen(title = "Friction", contentPadding = padding, actions = topActions) {
        if (!model.health.accessibilityConnected) {
            item("setup") {
                Banner(
                    if (model.health.accessibilityEnabled) "Waiting for the friction service" else "Friction needs Accessibility",
                    Tone.Caution,
                    Icons.Outlined.WarningAmber,
                    body = if (model.health.accessibilityEnabled) {
                        "It's enabled in Settings and should connect in a moment."
                    } else {
                        "Lagrange only watches which app is in front, never what's on screen. Sideloaded? Allow restricted settings first."
                    },
                    action = "Open Accessibility" to { context.launchSafe(PermissionHealthChecker.accessibilityIntent()) },
                    modifier = Modifier.rise(0),
                )
            }
        }
        if (model.health.advancedProtection == Check.YES) {
            item("advanced") {
                Banner(
                    "Advanced Protection is on",
                    Tone.Caution,
                    Icons.Outlined.GppMaybe,
                    body = "Android may block accessibility for apps that aren't accessibility tools, so pauses may not appear. Analytics are unaffected.",
                )
            }
        }
        model.error?.let { err ->
            item("error") { Banner(err, Tone.Critical, Icons.Outlined.ErrorOutline, onDismiss = { vm?.clearError() }) }
        }
        if (p == null) return@LagrangeScreen

        item("mode") { ModePanel(model, p, vm, Modifier.rise(1)) }

        item("rules-header") {
            SectionHeader(
                "Rules",
                supporting = "How a pause behaves",
                info = Info(
                    "How gentle friction works",
                    "Opening a paused app shows a short countdown first. Each open today makes the next pause a little longer " +
                        "(if escalation is on); only real opens and distracting time count, never the opens you abandoned at a pause." +
                        "\n\nA soft timer, if you set one, counts your time across all distracting apps and checks in when it's up. " +
                        "Nudges are small banners that mark how long you've been scrolling.",
                ),
            )
        }
        item("rules") {
            RowGroup {
                ValueRow("Daily budget", formatDurationShort(p.dailyBudgetMs), onClick = { sheet = RuleSheet.Budget }, leading = { LeadingIcon(Icons.Outlined.HourglassTop, Tone.Ember) }, supporting = "Shown on pauses and Today")
                ValueRow(
                    "Launch pause",
                    if (p.escalationEnabled) "${p.tier0PauseSec}–${p.tier3PauseSec}s" else "${p.tier0PauseSec}s",
                    onClick = { sheet = RuleSheet.Pauses },
                    leading = { LeadingIcon(Icons.Outlined.PauseCircleOutline) },
                    supporting = if (p.escalationEnabled) "Grows with each open" else "Same every time",
                )
                ValueRow("Return grace", if (p.graceWindowSec == 0) "Off" else formatDurationShort(p.graceWindowSec * 1_000L), onClick = { sheet = RuleSheet.Grace }, leading = { LeadingIcon(Icons.Outlined.Repeat) }, supporting = "Hop out for a code and back, no pause")
                ValueRow("Time nudges", if (p.nudgeIntervalMin == 0) "Off" else "Every ${p.nudgeIntervalMin}m", onClick = { sheet = RuleSheet.Nudges }, leading = { LeadingIcon(Icons.Outlined.NotificationsPaused) })
                ValueRow("Timer choices", p.timerPresetsMin.sorted().joinToString(" · ") { "${it}m" }.ifEmpty { "None" }, onClick = { sheet = RuleSheet.Timers }, leading = { LeadingIcon(Icons.Outlined.Timer) })
                ValueRow("Pauses per day", p.dailyPromptCap?.let { "Up to $it" } ?: "No limit", onClick = { sheet = RuleSheet.Cap }, leading = { LeadingIcon(Icons.Outlined.TouchApp) })
                val redirectApp = p.redirectPackageName?.let { pkg -> appList.firstOrNull { it.packageName == pkg } }
                // Same eligibility rule the pause card applies (EscalationPolicy): otherwise the button is hidden.
                val redirectInactive = model.apps != null && p.redirectPackageName != null && (
                    redirectApp == null || redirectApp.isFrictionEligible ||
                        redirectApp.category == AppCategory.DISTRACTING || redirectApp.category == AppCategory.SELF_APP
                    )
                ValueRow(
                    "Instead, open…",
                    p.redirectPackageName?.let { redirectApp?.displayName ?: it } ?: "Nothing",
                    onClick = { sheet = RuleSheet.Redirect },
                    leading = { LeadingIcon(Icons.Outlined.OpenInNew, Tone.Good) },
                    supporting = if (redirectInactive) "Not shown: pick an app that isn't paused or distracting" else "A better option on every pause",
                )
            }
        }

        item("schedules-header") {
            SectionHeader(
                "Schedules",
                supporting = "Firmer rules at certain times",
                action = "Add" to { sheet = RuleSheet.AddSchedule },
            )
        }
        if (model.schedules.isEmpty()) {
            item("schedules-empty") {
                Text(
                    "None yet. Try Work hours or a late-night Wind-down.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.xs),
                )
            }
        } else {
            item("schedules") {
                RowGroup {
                    model.schedules.forEach { s ->
                        ScheduleRow(s, onToggle = { vm?.toggleScheduleEnabled(s.id, it) }, onEdit = { editingSchedule = s })
                    }
                }
            }
        }

        item("apps-header") { SectionHeader("Apps") }
        item("apps") {
            val paused = appList.count { it.isFrictionEligible }
            val distracting = appList.count { it.category == AppCategory.DISTRACTING }
            RowGroup {
                ValueRow(
                    "Apps and categories",
                    if (model.apps == null) "…" else "$paused paused",
                    onClick = onOpenApps,
                    leading = { LeadingIcon(Icons.Outlined.Apps) },
                    supporting = if (model.apps == null) null else "$distracting distracting · ${appList.size} in total",
                )
            }
        }
    }

    when (sheet) {
        null -> Unit
        RuleSheet.Budget -> BudgetSheet(current = p?.dailyBudgetMs ?: 0, onDismiss = { sheet = null }, onSave = { sheet = null; vm?.setDailyBudgetMs(it) })
        RuleSheet.Pauses -> p?.let { PausesSheet(it, vm, onDismiss = { sheet = null }) }
        RuleSheet.Grace -> p?.let { prefs ->
            val options = listOf(0, 60, 120, 300).let { if (prefs.graceWindowSec in it) it else it + prefs.graceWindowSec }
            ChoiceSheet(
                "Return grace",
                "After you continue past a pause, leaving and coming back within this window won't pause you again: handy for copying a code.",
                options, prefs.graceWindowSec, { if (it == 0) "Off" else formatDurationShort(it * 1_000L) },
                onDismiss = { sheet = null },
            ) { vm?.setGraceWindowSec(it) }
        }
        RuleSheet.Nudges -> p?.let { prefs ->
            val options = listOf(0, 10, 15, 20, 30).let { if (prefs.nudgeIntervalMin in it) it else it + prefs.nudgeIntervalMin }
            ChoiceSheet(
                "Time nudges",
                "A small banner at the top when continuous distracting time passes each mark. It never blocks.",
                options, prefs.nudgeIntervalMin, { if (it == 0) "Off" else "${it}m" },
                onDismiss = { sheet = null },
            ) { vm?.setNudgeIntervalMin(it) }
        }
        RuleSheet.Timers -> p?.let { prefs ->
            EditSheet(
                "Timer choices",
                supporting = "Offered on a pause. A timer counts across all distracting apps and checks in when it's up.",
                onDismiss = { sheet = null },
                onConfirm = null,
            ) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                    (listOf(2, 5, 10, 15, 20, 30) + prefs.timerPresetsMin).distinct().sorted().forEach { min ->
                        androidx.compose.material3.FilterChip(selected = min in prefs.timerPresetsMin, onClick = { vm?.toggleTimerPreset(min) }, label = { Text("${min}m") })
                    }
                }
                Button(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
            }
        }
        RuleSheet.Cap -> p?.let { prefs ->
            val options = listOf<Int?>(null, 10, 20, 30).let { if (prefs.dailyPromptCap in it) it else it + prefs.dailyPromptCap }
            ChoiceSheet(
                "Pauses per day",
                "After this many pauses in a day, apps open straight away. A cap keeps friction from becoming noise.",
                options, prefs.dailyPromptCap, { it?.let { n -> "$n" } ?: "No limit" },
                onDismiss = { sheet = null },
            ) { vm?.setDailyPromptCap(it) }
        }
        RuleSheet.Redirect -> p?.let { prefs ->
            RedirectAppPickerDialog(apps = appList, currentPackage = prefs.redirectPackageName, onDismiss = { sheet = null }, onSelect = { sheet = null; vm?.setRedirectPackage(it) })
        }
        RuleSheet.AddSchedule -> EditSheet("Add a schedule", supporting = "Make pauses firmer at certain times of day.", onDismiss = { sheet = null }, onConfirm = null) {
            RowGroup {
                if (model.schedules.none { it.name.equals("Work Hours", ignoreCase = true) }) {
                    ListRow("Work hours", supporting = "Mon–Fri, 09:00–17:30 · one tier firmer", leading = { LeadingIcon(Icons.Outlined.Schedule) }, onClick = { sheet = null; vm?.addWorkHoursPreset() })
                }
                if (model.schedules.none { it.name.equals("Wind-Down", ignoreCase = true) }) {
                    ListRow("Wind-down", supporting = "Every day, 22:30–04:00 · asks for a timer", leading = { LeadingIcon(Icons.Outlined.Schedule) }, onClick = { sheet = null; vm?.addWindDownPreset() })
                }
                ListRow("Custom…", leading = { LeadingIcon(Icons.Outlined.Add) }, onClick = { sheet = null; creatingSchedule = true })
            }
        }
    }
    if (creatingSchedule || editingSchedule != null) {
        ScheduleEditorDialog(
            initial = editingSchedule,
            onDismiss = {
                creatingSchedule = false
                editingSchedule = null
            },
            onSave = { saved ->
                vm?.saveSchedule(saved) {
                    creatingSchedule = false
                    editingSchedule = null
                }
            },
            onDelete = editingSchedule?.let { s ->
                {
                    vm?.deleteSchedule(s.id)
                    editingSchedule = null
                }
            },
        )
    }
}

// -- Mode -------------------------------------------------------------------------------------------------------------

private enum class Mode { Off, On, Snoozed, Boost }

/**
 * The state of friction right now, and the two ways to change it for an hour. The panel's colour is the state, so
 * a glance answers "is it on?" before any text is read.
 */
@Composable
private fun ModePanel(model: FrictionUiModel, p: UserPrefs, vm: FrictionViewModel?, modifier: Modifier) {
    val live = model.live
    val now = model.nowElapsed
    val mode = when {
        !p.frictionEnabled -> Mode.Off
        live.isSnoozed(now) -> Mode.Snoozed
        live.isFocusBoostActive(now) -> Mode.Boost
        else -> Mode.On
    }
    val paused = model.apps?.count { it.isFrictionEligible }
    val c = Lagrange.colors
    val container by animateColorAsState(
        when (mode) {
            Mode.Off -> MaterialTheme.colorScheme.surfaceContainerLow
            Mode.On -> MaterialTheme.colorScheme.primaryContainer
            Mode.Snoozed -> c.caution.container
            Mode.Boost -> MaterialTheme.colorScheme.tertiaryContainer
        },
        Motion.effectsSlow(),
        label = "mode",
    )
    val onContainer = when (mode) {
        Mode.Off -> MaterialTheme.colorScheme.onSurface
        Mode.On -> MaterialTheme.colorScheme.onPrimaryContainer
        Mode.Snoozed -> c.caution.onContainer
        Mode.Boost -> MaterialTheme.colorScheme.onTertiaryContainer
    }
    Panel(modifier, color = container, contentColor = onContainer, shape = MaterialTheme.shapes.extraLarge, spacing = Spacing.l) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(mode, transitionSpec = { Transitions.roll(targetState.ordinal > initialState.ordinal) }, modifier = Modifier.weight(1f), label = "mode-title") { m ->
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                    Text(
                        when (m) {
                            Mode.Off -> "Friction is off"
                            Mode.On -> "Friction is on"
                            Mode.Snoozed -> "Snoozed"
                            Mode.Boost -> "Focus Boost"
                        },
                        style = MaterialTheme.typography.headlineMedium,
                    )
                    Text(
                        when (m) {
                            Mode.Off -> "Turn it on when your baseline is in. Start with one or two apps."
                            Mode.On -> paused?.let { "Pausing $it app${if (it == 1) "" else "s"}" } ?: "Pausing your chosen apps"
                            Mode.Snoozed -> "No pauses for a while. They come back on their own."
                            Mode.Boost -> "Every pause is one step firmer for now."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.width(Spacing.m))
            Switch(checked = p.frictionEnabled, onCheckedChange = { vm?.setFrictionEnabled(it) })
        }
        if (p.frictionEnabled) {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
                HourToggle(
                    "Snooze 1h",
                    "Pause friction",
                    Icons.Outlined.PauseCircleOutline,
                    active = mode == Mode.Snoozed,
                    remainingMs = live.snoozeRemainingMs(now),
                    accent = c.caution.accent,
                    onClick = { if (mode == Mode.Snoozed) vm?.cancelSnooze() else vm?.activateSnooze1h() },
                    modifier = Modifier.weight(1f),
                )
                HourToggle(
                    "Focus Boost",
                    "One tier firmer",
                    Icons.Outlined.Bolt,
                    active = mode == Mode.Boost,
                    remainingMs = live.focusBoostRemainingMs(now),
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = { if (mode == Mode.Boost) vm?.cancelFocusBoost() else vm?.activateFocusBoost1h() },
                    modifier = Modifier.weight(1f),
                )
            }
            StatStrip {
                Stat("Paused today", model.blockedToday.toString(), Modifier.weight(1f), caption = "left or redirected")
                Stat(
                    "Soft timer",
                    when (val rem = live.activeTimerRemainingMs) {
                        null -> "–"
                        0L -> "Due"
                        else -> formatDurationShort(rem)
                    },
                    Modifier.weight(1f),
                    caption = live.activeTimerInitialMin?.let { "of ${it}m" } ?: "none running",
                )
            }
        }
    }
}

/** A one-hour mode. Active, it shows the time left and a draining meter; tapping again ends it early. */
@Composable
private fun HourToggle(
    title: String,
    subtitle: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    remainingMs: Long,
    accent: Color,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val interactions = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val bg by animateColorAsState(if (active) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surface.copy(alpha = 0.55f), Motion.effects(), label = "toggle")
    androidx.compose.material3.Surface(
        onClick = onClick,
        modifier = modifier.pressScale(interactions),
        interactionSource = interactions,
        shape = MaterialTheme.shapes.large,
        color = bg,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (active) Icons.Outlined.Close else icon, null, tint = accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Spacing.s))
                Text(if (active) "End" else title, style = MaterialTheme.typography.titleSmall)
            }
            if (active) {
                RollingText("${formatDurationShort(remainingMs.coerceAtLeast(60_000L))} left", NumberStyles.inline.copy(fontSize = NumberStyles.stat.fontSize * 0.7f), increasing = false)
                Meter(remainingMs.toFloat() / HOUR_MS, accent, height = 4.dp)
            } else {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

// -- Editors ----------------------------------------------------------------------------------------------------------

/** A single choice that applies the moment it's picked: no Save, the sheet closes itself. */
@Composable
private fun <T> ChoiceSheet(title: String, supporting: String, options: List<T>, selected: T, label: (T) -> String, onDismiss: () -> Unit, onSelect: (T) -> Unit) {
    EditSheet(title, supporting = supporting, onDismiss = onDismiss, onConfirm = null) {
        Choice(options, selected, { onSelect(it); onDismiss() }, label)
        Spacer(Modifier.height(Spacing.s))
    }
}

@Composable
private fun PausesSheet(p: UserPrefs, vm: FrictionViewModel?, onDismiss: () -> Unit) {
    var tiers by remember { mutableStateOf(false) }
    EditSheet("Launch pause", supporting = "A short countdown before a paused app opens.", onDismiss = onDismiss, onConfirm = null) {
        RowGroup {
            com.celestiallemon.lagrange.ui.components.SwitchRow(
                "Grow with each open",
                checked = p.escalationEnabled,
                onCheckedChange = { vm?.setEscalationEnabled(it) },
                supporting = "Only real opens and distracting time count",
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TierLine("First opens", "1–2 opens, under 15m", p.tier0PauseSec, "")
            if (p.escalationEnabled) {
                TierLine("Getting frequent", "3–5 opens or 15m+", p.tier1PauseSec, "offers a timer")
                TierLine("Habit forming", "6–10 opens or 45m+", p.tier2PauseSec, "asks why")
                TierLine("Heavy day", "11+ opens or 90m+", p.tier3PauseSec, "shows budget")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.End)) {
            OutlinedButton(onClick = { tiers = true }) { Text("Change lengths") }
            Button(onClick = onDismiss) { Text("Done") }
        }
    }
    if (tiers) {
        TierPausesDialog(
            tier0 = p.tier0PauseSec, tier1 = p.tier1PauseSec, tier2 = p.tier2PauseSec, tier3 = p.tier3PauseSec,
            onDismiss = { tiers = false },
            onSave = { t0, t1, t2, t3 -> tiers = false; vm?.setTierPauses(t0, t1, t2, t3) },
        )
    }
}

@Composable
private fun TierLine(name: String, rule: String, seconds: Int, extra: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge)
            Text(rule + if (extra.isNotEmpty()) " · $extra" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("${seconds}s", style = NumberStyles.stat)
    }
}

// -- Schedules --------------------------------------------------------------------------------------------------------

@Composable
private fun ScheduleRow(schedule: FrictionScheduleEntity, onToggle: (Boolean) -> Unit, onEdit: () -> Unit) {
    val allDay = schedule.startMinuteOfDay == schedule.endMinuteOfDay
    val time = if (allDay) "All day" else "${formatMinuteOfDay(schedule.startMinuteOfDay)}–${formatMinuteOfDay(schedule.endMinuteOfDay)}"
    val traits = buildList {
        if (schedule.escalationBoost > 0) add("+${schedule.escalationBoost} tier")
        if (schedule.requireTimerPrompt) add("asks for a timer")
        schedule.nudgeIntervalMin?.let { add("nudge every ${it}m") }
    }
    ListRow(
        headline = schedule.name,
        supporting = "${formatDaysMask(schedule.daysOfWeekMask)} · $time" + if (traits.isNotEmpty()) "\n" + traits.joinToString(" · ") else "",
        leading = { LeadingIcon(Icons.Outlined.Schedule, if (schedule.enabled) Tone.Brand else Tone.Neutral) },
        onClick = onEdit,
        trailing = {
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
        },
    )
}

// -- Apps -------------------------------------------------------------------------------------------------------------

/** Every app, its category and whether it's paused. Reached from Friction (and the same rows power onboarding). */
@Composable
private fun AppsScreen(apps: List<CatalogApp>?, padding: PaddingValues, catalog: CatalogViewModel, onBack: () -> Unit) {
    var filter by rememberSaveable { mutableStateOf(AppFilter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var customizing by remember { mutableStateOf<CatalogApp?>(null) }
    val list = apps.orEmpty()
    val shown = remember(list, filter, query) {
        val q = query.trim()
        list.filter { app ->
            val f = when (filter) {
                AppFilter.ALL -> true
                AppFilter.DISTRACTING -> app.category == AppCategory.DISTRACTING
                AppFilter.FRICTION_ON -> app.isFrictionEligible
            }
            f && (q.isEmpty() || app.displayName.contains(q, ignoreCase = true) || app.packageName.contains(q, ignoreCase = true))
        }
    }
    LagrangeScreen(title = "Apps", contentPadding = padding, onBack = onBack) {
        item("intro") {
            Text(
                "Recategorizing an app recolours its history too. Start with a pause on just one or two apps.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item("search") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s), modifier = Modifier.padding(vertical = Spacing.s)) {
                TextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    singleLine = true,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    colors = TextFieldDefaults.colors(
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Choice(AppFilter.entries, filter, { filter = it }, { it.label })
            }
        }
        if (apps == null) {
            item("loading") { CircularProgressIndicator(Modifier.padding(Spacing.xl)) }
        } else {
            items(shown, key = { it.packageName }) { app ->
                AppCategoryRow(
                    app = app,
                    showFrictionControls = true,
                    onToggleFriction = { enabled ->
                        val matchesDefault = (app.category == AppCategory.DISTRACTING && enabled && app.confirmed) ||
                            (app.category == AppCategory.NEUTRAL && !enabled)
                        val next = (app.frictionOverride ?: AppFrictionOverride())
                            .copy(enabled = if (matchesDefault) null else enabled)
                            .takeUnless { it.isDefault }
                        catalog.setFrictionOverride(app.packageName, next)
                    },
                    onCustomizeFriction = { customizing = app },
                    onChange = { catalog.setCategory(app.packageName, it) },
                )
            }
        }
    }
    customizing?.let { target ->
        AppFrictionOverrideDialog(
            app = target,
            onDismiss = { customizing = null },
            onSave = { override ->
                customizing = null
                catalog.setFrictionOverride(target.packageName, override)
            },
        )
    }
}

internal fun Context.launchSafe(vararg intents: Intent) {
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
