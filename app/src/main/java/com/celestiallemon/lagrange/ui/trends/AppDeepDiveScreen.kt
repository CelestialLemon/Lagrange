package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.data.AppDeepDive
import com.celestiallemon.lagrange.data.TrendsRepository
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.FlowEdge
import com.celestiallemon.lagrange.domain.FlowNode
import com.celestiallemon.lagrange.domain.LengthBucket
import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.MetricUnit
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.BarRow
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.Dot
import com.celestiallemon.lagrange.ui.components.EditSheet
import com.celestiallemon.lagrange.ui.components.EmptyState
import com.celestiallemon.lagrange.ui.components.Info
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.LeadingIcon
import com.celestiallemon.lagrange.ui.components.Panel
import com.celestiallemon.lagrange.ui.components.RollingText
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.SectionHeader
import com.celestiallemon.lagrange.ui.components.Stat
import com.celestiallemon.lagrange.ui.components.StatStrip
import com.celestiallemon.lagrange.ui.components.Stepper
import com.celestiallemon.lagrange.ui.components.ValueRow
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.formatHour
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.friction.description
import com.celestiallemon.lagrange.ui.friction.label
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.rise
import com.celestiallemon.lagrange.ui.today.badge
import com.celestiallemon.lagrange.ui.today.isNotificationLed
import java.time.LocalDate
import kotlin.math.roundToInt

/** Saves a [TrendWindow] as "KIND|start|end" (dates ISO, blank when unset). */
internal val TrendWindowSaver: Saver<TrendWindow, String> = Saver(
    save = { w -> listOf(w.kind.name, w.customStart?.toString().orEmpty(), w.customEnd?.toString().orEmpty()).joinToString("|") },
    restore = { s ->
        val parts = s.split("|")
        val kind = WindowKind.entries.firstOrNull { it.name == parts.getOrNull(0) } ?: return@Saver TrendWindow.DEFAULT
        fun date(i: Int) = parts.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        TrendWindow(kind, date(1), date(2))
    },
)

private val goalPresetsMin = listOf(15, 30, 45, 60, 90, 120)
private val choosableCategories = listOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY, AppCategory.EXCLUDED)

/**
 * One app over time: how much, when, how it's used, and what leads in and out.
 * Setting its category and daily goal sits at the top, because that's what you came to change.
 */
@Composable
fun AppDeepDiveScreen(
    pkgId: Long,
    initialWindow: TrendWindow,
    padding: PaddingValues,
    onBack: () -> Unit,
    vm: AppDeepDiveViewModel = viewModel(),
) {
    // The screen owns its window: each visit starts from Trends' window, and it survives rotation and process death.
    var window by rememberSaveable(stateSaver = TrendWindowSaver) { mutableStateOf(initialWindow) }
    LaunchedEffect(pkgId, window) { vm.show(pkgId, window) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val pkg by vm.pkgRow.collectAsStateWithLifecycle()
    // Until the view model has taken this app's request, whatever it holds belongs to another visit.
    val current = ui.pkgId == pkgId
    val loading = !current || ui.loading
    val d = ui.dive?.takeIf { current && it.meta.pkgId == pkgId }
    val row = pkg?.takeIf { it.id == pkgId }
    val name = row?.displayName?.ifBlank { row.packageName } ?: d?.meta?.displayName ?: "App"
    val category = row?.category?.let { c -> AppCategory.entries.firstOrNull { it.name == c } } ?: d?.meta?.category
    // The goal line follows the saved goal at once, without waiting for a reload.
    val goal = if (row != null) row.dailyGoalMs else d?.meta?.dailyGoalMs
    var selected by rememberSaveable(pkgId) { mutableStateOf<String?>(null) }
    var ma7 by rememberSaveable { mutableStateOf(true) }
    var ma28 by rememberSaveable { mutableStateOf(false) }
    var editingCategory by remember { mutableStateOf(false) }
    var editingGoal by remember { mutableStateOf(false) }

    LagrangeScreen(title = name, contentPadding = padding, onBack = onBack) {
        item("identity") {
            RowGroup(Modifier.rise(0)) {
                ValueRow(
                    "Category",
                    category?.label ?: "–",
                    onClick = { if (category != null && category != AppCategory.SELF_APP) editingCategory = true },
                    leading = { LeadingIcon(Icons.Outlined.Category) },
                    valueColor = category?.let { categoryColor(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                ValueRow(
                    "Daily goal",
                    goal?.let { formatDurationShort(it) } ?: "None",
                    onClick = { editingGoal = true },
                    leading = { LeadingIcon(Icons.Outlined.Flag) },
                )
            }
        }
        item("window") {
            WindowChips(window, d?.today, d?.earliest, onWindow = { window = it; selected = null }, range = d?.range, modifier = Modifier.padding(top = Spacing.l).rise(1))
        }
        if (loading && d != null) {
            item("updating") { LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Updating" }) }
        }
        val error = ui.error?.takeIf { current }
        if (error != null) {
            item("error") {
                Banner(
                    "This app's history couldn't update",
                    Tone.Critical,
                    Icons.Outlined.ErrorOutline,
                    body = if (d != null) "Still showing ${rangeLabel(d.range)}. $error" else error,
                    action = "Try again" to vm::retry,
                )
            }
        }
        if (d == null) {
            if (error == null) {
                item("loading") {
                    EmptyState(
                        if (loading) "Reading this app's history" else "No history for this app yet",
                        if (loading) "One moment." else "It hasn't been used in this window.",
                    )
                }
            }
            return@LagrangeScreen
        }
        item("hero") { DiveHero(d, Modifier.rise(2)) }
        item("trend") {
            val selectedDate = selected?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            UsagePanel(d, goal, ma7, ma28, { ma7 = !ma7 }, { ma28 = !ma28 }, selectedDate, { selected = it?.toString() }, Modifier.rise(3))
        }
        item("profile") { TimeOfDay(d) }
        item("shape") { SessionShape(d) }
        item("flows") { Flows(d) }
        item("triggers") { Triggers(d) }
        item("pauses") { LaunchPauses(d) }
    }

    if (editingCategory && category != null) {
        CategorySheet(category, onDismiss = { editingCategory = false }) {
            editingCategory = false
            // Saved even when unchanged: saving is an explicit choice, and it confirms a suggested category.
            vm.setCategory(pkgId, it)
        }
    }
    if (editingGoal) {
        GoalSheet(goal, onDismiss = { editingGoal = false }) {
            editingGoal = false
            vm.setGoal(pkgId, it)
        }
    }
}

// -- Editors ---------------------------------------------------------------------------------------------------------

@Composable
private fun CategorySheet(current: AppCategory, onDismiss: () -> Unit, onSave: (AppCategory) -> Unit) {
    var choice by rememberSaveable { mutableStateOf(current) }
    EditSheet(
        title = "Category",
        supporting = "Changing it re-colours this app's whole history: categories apply to the past too.",
        onDismiss = onDismiss,
        onConfirm = { onSave(choice) },
    ) {
        RowGroup {
            choosableCategories.forEach { c ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .selectable(selected = c == choice, role = Role.RadioButton, onClick = { choice = c })
                        .padding(horizontal = Spacing.l, vertical = Spacing.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Dot(categoryColor(c), size = 12.dp)
                    Spacer(Modifier.width(Spacing.l))
                    Column(Modifier.weight(1f)) {
                        Text(c.label, style = MaterialTheme.typography.bodyLarge)
                        Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    RadioButton(selected = c == choice, onClick = null)
                }
            }
        }
    }
}

@Composable
private fun GoalSheet(current: Long?, onDismiss: () -> Unit, onSave: (Long?) -> Unit) {
    var value by rememberSaveable { mutableLongStateOf(current ?: 30 * MINUTE_MS) }
    var off by rememberSaveable { mutableStateOf(current == null) }
    val range = TrendsRepository.GOAL_RANGE_MS
    val step = 5 * MINUTE_MS
    EditSheet(
        title = "Daily goal",
        supporting = "Drawn as a line on this app's chart and shown in the app list. A goal is a guide, not a lock.",
        onDismiss = onDismiss,
        onConfirm = { onSave(if (off) null else value) },
    ) {
        Stepper(
            value = if (off) "No goal" else formatDurationShort(value),
            onMinus = { off = false; value = (value - step).coerceIn(range) },
            onPlus = { off = false; value = (value + step).coerceIn(range) },
            minusEnabled = off || value > range.first,
            plusEnabled = off || value < range.last,
            minusDescription = "Lower goal by 5 minutes",
            plusDescription = "Raise goal by 5 minutes",
        )
        val presets = listOf<Long?>(null) + goalPresetsMin.map { it * MINUTE_MS }
        Choice(presets, if (off) null else value.takeIf { it in presets }, { p -> if (p == null) off = true else { off = false; value = p } }, { it?.let(::formatDurationShort) ?: "Off" }, forceChips = true)
    }
}

// -- Usage ------------------------------------------------------------------------------------------------------------

@Composable
private fun DiveHero(d: AppDeepDive, modifier: Modifier) {
    Column(modifier.padding(top = Spacing.l, bottom = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.Bottom) {
            RollingText(d.delta.current.mean?.let { formatDurationShort(it.toLong()) } ?: "–", NumberStyles.hero)
            Spacer(Modifier.width(Spacing.s))
            Text("a day, on average", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 10.dp))
        }
        DeltaLine(d.delta, MetricUnit.DURATION, higherIsBetter = false, allTime = !d.window.hasPrevious)
        Text(
            "${formatDurationShort(d.observedTotalMs)} across ${d.observedOpens} opens in this window.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun UsagePanel(d: AppDeepDive, goal: Long?, ma7: Boolean, ma28: Boolean, toggle7: () -> Unit, toggle28: () -> Unit, selected: LocalDate?, onSelect: (LocalDate?) -> Unit, modifier: Modifier) {
    val bar = categoryColor(d.meta.category)
    val colors = rememberTrendColors(bar = bar)
    val points = remember(d.points, goal) { d.points.map { it.copy(reference = goal?.toDouble()) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Panel(spacing = Spacing.m) {
            TrendChart(
                points = points,
                unit = MetricUnit.DURATION,
                diverging = false,
                showMa7 = ma7,
                showMa28 = ma28,
                referenceLabel = goal?.let { "Goal ${formatDurationShort(it)}" },
                selected = selected,
                onSelect = onSelect,
                description = "${d.meta.displayName} daily usage over ${d.range.days} days",
                colors = colors,
            )
            ChartLegend(ma7, ma28, goal?.let { "Daily goal" }, colors, toggle7, toggle28)
            val point = selected?.let { s -> points.firstOrNull { it.date == s } }
            if (point != null) DayCallout(point, MetricUnit.DURATION, "Used", null)
        }
        CoverageLegend(d.coveredDays, d.partialDays, d.noDataDays, d.inProgressDays, bar, Modifier.padding(horizontal = Spacing.xs))
    }
}

// -- Time of day ------------------------------------------------------------------------------------------------------

@Composable
private fun TimeOfDay(d: AppDeepDive) {
    val p = d.profile
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            "Time of day",
            info = Info(
                "How the hours are drawn",
                "Average minutes in each hour, over the covered days in this window (${p.coveredDays}). Hours run from your " +
                    "day rollover (${formatHour(d.rolloverHour)}) round to the next." +
                    if (p.unplacedRepeatedMs > 0) "\n\n${formatDurationShort(p.unplacedRepeatedMs)} from a repeated daylight-saving hour couldn't be placed and is left out." else "",
            ),
        )
        if (p.coveredDays == 0 || p.isEmpty) {
            Text(
                if (p.coveredDays == 0) "No covered days in this window yet." else "Not used on covered days in this window.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }
        p.peak?.let { peak ->
            Text(
                "Busiest ${formatHour(peak.wallHour)}–${formatHour((peak.wallHour + 1) % 24)}, about ${formatDuration(peak.avgMs.toLong())} a day.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HourProfileChart(
            p.bins,
            p.peak?.slot,
            description = "Average minutes per hour of the logical day, starting ${formatHour(d.rolloverHour)}",
            barColor = categoryColor(d.meta.category),
        )
    }
}

// -- Session shape ----------------------------------------------------------------------------------------------------

@Composable
private fun SessionShape(d: AppDeepDive) {
    val s = d.insights.shape
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            "How long you stay",
            supporting = "${s.runs} opens",
            info = Info(
                "Opens and loops",
                "Each real open of the app, by how long you stayed. Opens stopped at a launch pause never count.\n\nA loop " +
                    "is coming back to the app within 60 seconds of leaving it, across pickups too.",
            ),
        )
        if (s.runs == 0) {
            Text("No real opens in this window.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        StatStrip {
            Stat("Typical open", s.medianRunMs?.let { formatDuration(it) } ?: "–", Modifier.weight(1f), caption = "median")
            Stat("Loops", "${s.reopenLoops}", Modifier.weight(1f), caption = s.loopRate?.let { "${(it * 100).roundToInt()}% of opens" })
        }
        val max = s.counts.values.maxOrNull()?.takeIf { it > 0 } ?: 1
        val color = categoryColor(d.meta.category)
        LengthBucket.entries.forEach { b ->
            val n = s.counts[b] ?: 0
            BarRow(b.label, "$n · ${percent(n, s.runs)}", n.toFloat() / max, color = color)
        }
    }
}

// -- Sequences --------------------------------------------------------------------------------------------------------

@Composable
private fun Flows(d: AppDeepDive) {
    val f = d.insights.flows
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            "Before and after",
            info = Info(
                "Sequences, not causes",
                "What came right before and right after each of the ${f.runs} opens, within the same pickup. These show order, " +
                    "not cause: opening Chrome after Instagram doesn't mean Instagram sent you there.",
            ),
        )
        if (f.runs == 0) {
            Text("No opens to trace yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        FlowList("Came from", f.inflow, d)
        FlowList("Went to", f.outflow, d)
    }
}

@Composable
private fun FlowList(title: String, edges: List<FlowEdge>, d: AppDeepDive) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = Spacing.s))
    val max = edges.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: 1
    edges.forEach { e ->
        BarRow(
            flowLabel(e.node, d),
            "${e.count} · ${(e.share * 100).roundToInt()}%",
            e.count.toFloat() / max,
            color = if (e.node is FlowNode.App) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        )
    }
}

private fun flowLabel(n: FlowNode, d: AppDeepDive): String = when (n) {
    is FlowNode.App -> d.names[n.pkgId] ?: "App #${n.pkgId}"
    FlowNode.SessionStart -> "Unlocking the phone"
    FlowNode.NotificationStart -> "A notification"
    FlowNode.SameApp -> "Itself, again"
    FlowNode.SessionEnd -> "Locking the phone"
}

// -- Triggers ---------------------------------------------------------------------------------------------------------

@Composable
private fun Triggers(d: AppDeepDive) {
    val t = d.insights.triggers
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            "What brings you here",
            supporting = "${t.total} arrivals",
            info = Info(
                "Trigger evidence",
                "Session starts carry the pickup's trigger; switching to the app mid-session carries its own (a tap in the " +
                    "notification shade, say).\n\n${t.observable} of ${t.total} arrivals happened while the notification listener " +
                    "was recording; ${d.notifCoveredDays} of ${d.dataDays} days had full notification coverage.",
            ),
        )
        if (t.total == 0) {
            Text("No arrivals into this app in this window.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val combined = t.combined
        val max = combined.values.maxOrNull()?.takeIf { it > 0 } ?: 1
        TriggerEvidence.entries.filter { (combined[it] ?: 0) > 0 }.forEach { ev ->
            val n = combined[ev] ?: 0
            BarRow(
                ev.badge,
                "$n · ${percent(n, t.total)}",
                n.toFloat() / max,
                color = if (ev.isNotificationLed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                caption = "${t.episodeStarts[ev] ?: 0} at pickup · ${t.midSession[ev] ?: 0} mid-session",
            )
        }
    }
}

// -- Launch pauses ----------------------------------------------------------------------------------------------------

@Composable
private fun LaunchPauses(d: AppDeepDive) {
    val f = d.insights.friction
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader("Launch pauses")
        if (!f.hasData) {
            Text(
                "No pauses or timers for this app yet. With gentle friction on, exits, quick returns and timer extensions show up here.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            StatStrip {
                Stat("Left at the pause", "${f.immediateExits}", Modifier.weight(1f), caption = f.exitRate?.let { "${(it * 100).roundToInt()}% of ${f.launchPauses}" }, captionTone = Tone.Good)
                Stat("Back within 10m", "${f.quickReopens}", Modifier.weight(1f), caption = f.quickReopenRate?.let { "${(it * 100).roundToInt()}%" })
            }
            StatStrip {
                Stat("Timer check-ins", "${f.timerCheckIns}", Modifier.weight(1f))
                Stat("Took +5 min", "${f.extensions}", Modifier.weight(1f), caption = f.extensionRate?.let { "${(it * 100).roundToInt()}% of check-ins" })
            }
        }
    }
}

private fun percent(n: Int, total: Int): String = if (total <= 0) "0%" else "${(n * 100.0 / total).roundToInt()}%"
