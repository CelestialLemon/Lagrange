package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.CallMade
import androidx.compose.material.icons.automirrored.outlined.CallReceived
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
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
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatRow
import com.celestiallemon.lagrange.ui.components.StatTile
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.formatHour
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.friction.label
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import com.celestiallemon.lagrange.ui.today.badge
import com.celestiallemon.lagrange.ui.today.isNotificationLed
import java.time.LocalDate
import java.util.Locale
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
    var ma28 by rememberSaveable { mutableStateOf(true) }

    LagrangeScreen(
        eyebrow = "App deep dive",
        title = name,
        subtitle = row?.packageName,
        contentPadding = padding,
        onBack = onBack,
    ) {
        item("identity") {
            IdentityCard(
                category,
                goal,
                onCategory = { vm.setCategory(pkgId, it) },
                onGoal = { vm.setGoal(pkgId, it) },
            )
        }
        item("window") {
            WindowChips(window, d?.today, d?.earliest, onWindow = { window = it; selected = null }, range = d?.range)
        }
        if (loading && d != null) {
            item("updating") {
                LinearProgressIndicator(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.s)
                        .semantics { contentDescription = "Updating" },
                )
            }
        }
        val error = ui.error?.takeIf { current }
        if (error != null) {
            item("error") {
                SectionCard(title = "This app's history couldn't update", eyebrow = "Something went wrong") {
                    Text(
                        if (d != null) "Still showing ${rangeLabel(d.range)}. $error" else error,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = vm::retry) { Text("Try again") }
                }
            }
        }
        if (d == null) {
            if (error == null) {
                item("loading") {
                    SectionCard(title = if (loading) "Reading this app's history" else "No history for this app yet", eyebrow = "Deep dive") {
                        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                }
            }
            return@LagrangeScreen
        }
        val selectedDate = selected?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        item("trend") { UsageCard(d, goal, ma7, ma28, { ma7 = !ma7 }, { ma28 = !ma28 }, selectedDate) { selected = it?.toString() } }
        item("profile") { ProfileCard(d) }
        item("shape") { ShapeCard(d) }
        item("flows") { FlowsCard(d) }
        item("triggers") { TriggerCard(d) }
        item("value") { FrictionValueCard(d) }
    }
}

// -- Header: category + goal ---------------------------------------------------------------------------------------

@Composable
private fun IdentityCard(category: AppCategory?, goalMs: Long?, onCategory: (AppCategory) -> Unit, onGoal: (Long?) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    SectionCard(title = null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Category", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            if (category != null) {
                Box {
                    val color = categoryColor(category)
                    AssistChip(
                        onClick = { if (category != AppCategory.SELF_APP) menu = true },
                        label = { Text(category.label) },
                        leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(color)) },
                        trailingIcon = if (category != AppCategory.SELF_APP) ({ Icon(Icons.Outlined.ArrowDropDown, "Change category") }) else null,
                        colors = AssistChipDefaults.assistChipColors(labelColor = color),
                    )
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        choosableCategories.forEach { c ->
                            DropdownMenuItem(
                                text = { Text(c.label) },
                                leadingIcon = { Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(c))) },
                                onClick = {
                                    menu = false
                                    if (c != category) onCategory(c)
                                },
                            )
                        }
                    }
                }
            }
        }
        Text(
            "Changing the category re-colours this app's whole history (categories apply retroactively).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Flag, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Text("Daily goal", style = MaterialTheme.typography.labelLarge)
                Text(goalMs?.let { formatDurationShort(it) + " a day" } ?: "No goal", style = MaterialTheme.typography.headlineSmall)
            }
            val step = 5 * MINUTE_MS
            FilledTonalIconButton(
                enabled = goalMs != null && goalMs > TrendsRepository.GOAL_RANGE_MS.first,
                onClick = { goalMs?.let { onGoal((it - step).coerceAtLeast(TrendsRepository.GOAL_RANGE_MS.first)) } },
            ) { Icon(Icons.Outlined.Remove, "Lower goal by 5 minutes") }
            Spacer(Modifier.width(Space.s))
            FilledTonalIconButton(
                enabled = (goalMs ?: 0) < TrendsRepository.GOAL_RANGE_MS.last,
                onClick = { onGoal(((goalMs ?: 0) + step).coerceIn(TrendsRepository.GOAL_RANGE_MS)) },
            ) { Icon(Icons.Outlined.Add, "Raise goal by 5 minutes") }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FilterChip(selected = goalMs == null, onClick = { onGoal(null) }, label = { Text("Off") })
            goalPresetsMin.forEach { m ->
                val ms = m * MINUTE_MS
                FilterChip(selected = goalMs == ms, onClick = { onGoal(ms) }, label = { Text(formatDurationShort(ms)) })
            }
        }
    }
}

// -- Usage trend ---------------------------------------------------------------------------------------------------

@Composable
private fun UsageCard(d: AppDeepDive, goal: Long?, ma7: Boolean, ma28: Boolean, toggle7: () -> Unit, toggle28: () -> Unit, selected: LocalDate?, onSelect: (LocalDate?) -> Unit) {
    val colors = rememberTrendColors(bar = categoryColor(d.meta.category))
    val mean = d.delta.current.mean
    val points = remember(d.points, goal) { d.points.map { it.copy(reference = goal?.toDouble()) } }
    SectionCard(
        title = mean?.let { "${formatDurationShort(it.toLong())} / covered day" } ?: "No covered days yet",
        eyebrow = "Usage · ${d.range.days} days",
        icon = Icons.AutoMirrored.Outlined.ShowChart,
    ) {
        Text(
            "${formatDurationShort(d.observedTotalMs)} in ${d.observedOpens} opens observed in this window.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        DeltaLine(d.delta, MetricUnit.DURATION, higherIsBetter = false, allTime = !d.window.hasPrevious)
        CoveragePills(d.coveredDays, d.partialDays, d.noDataDays, d.inProgressDays)
    }
}

// -- Time of day ---------------------------------------------------------------------------------------------------

@Composable
private fun ProfileCard(d: AppDeepDive) {
    val p = d.profile
    SectionCard(title = "Time of day", eyebrow = "Average per covered day", icon = Icons.Outlined.Schedule) {
        if (p.coveredDays == 0 || p.isEmpty) {
            Text(
                if (p.coveredDays == 0) "No covered days in this window yet." else "No use on covered days in this window.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@SectionCard
        }
        val peak = p.peak
        if (peak != null) {
            Text(
                "Busiest around ${formatHour(peak.wallHour)}–${formatHour((peak.wallHour + 1) % 24)}: " +
                    "${formatDuration(peak.avgMs.toLong())} a day on average.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        HourProfileChart(p.bins, peak?.slot, description = "Average minutes per hour of the logical day, starting ${formatHour(d.rolloverHour)}")
        Text(
            "Hours run from your day rollover (${formatHour(d.rolloverHour)}) round to the next. Averaged over ${p.coveredDays} covered days." +
                if (p.unplacedRepeatedMs > 0) " ${formatDurationShort(p.unplacedRepeatedMs)} from a repeated DST hour couldn't be placed and is left out." else "",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Session shape -------------------------------------------------------------------------------------------------

@Composable
private fun ShapeCard(d: AppDeepDive) {
    val s = d.insights.shape
    SectionCard(title = "Session shape", eyebrow = "${s.runs} real opens", icon = Icons.Outlined.Repeat) {
        if (s.runs == 0) {
            Text("No real opens in this window. Blocked attempts at a launch pause never count.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val max = s.counts.values.maxOrNull()?.takeIf { it > 0 } ?: 1
        LengthBucket.entries.forEach { b ->
            val n = s.counts[b] ?: 0
            ShareBar(b.label, n, n.toFloat() / max, "${percent(n, s.runs)}", MaterialTheme.colorScheme.primary)
        }
        StatRow {
            StatTile("Median open", s.medianRunMs?.let { formatDuration(it) } ?: "–", Modifier.weight(1f))
            StatTile(
                "Re-open loops",
                "${s.reopenLoops}",
                Modifier.weight(1f),
                caption = s.loopRate?.let { "${(it * 100).roundToInt()}% of opens" },
            )
        }
        Text(
            "A loop is reopening the app within 60 seconds of leaving it, across pickups too. Launch-pause blocked attempts are left out.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Sequence flows ------------------------------------------------------------------------------------------------

@Composable
private fun FlowsCard(d: AppDeepDive) {
    val f = d.insights.flows
    SectionCard(title = "Observed sequences", eyebrow = "How you arrive & where you go", icon = Icons.AutoMirrored.Outlined.CallMade) {
        if (f.runs == 0) {
            Text("No opens to trace yet.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        Text(
            "What came right before and after each of ${f.runs} opens within the same pickup. These are sequences, not causes.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        FlowList("Inflow", Icons.AutoMirrored.Outlined.CallReceived, f.inflow, d)
        FlowList("Outflow", Icons.AutoMirrored.Outlined.CallMade, f.outflow, d)
    }
}

@Composable
private fun FlowList(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, edges: List<FlowEdge>, d: AppDeepDive) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.s))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
        val max = edges.maxOfOrNull { it.count }?.takeIf { it > 0 } ?: 1
        edges.forEach { e ->
            ShareBar(
                flowLabel(e.node, d),
                e.count,
                e.count.toFloat() / max,
                "${(e.share * 100).roundToInt()}%",
                if (e.node is FlowNode.App) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.tertiary,
            )
        }
    }
}

private fun flowLabel(n: FlowNode, d: AppDeepDive): String = when (n) {
    is FlowNode.App -> d.names[n.pkgId] ?: "App #${n.pkgId}"
    FlowNode.SessionStart -> "Session start (idle / direct)"
    FlowNode.NotificationStart -> "Notification-triggered start"
    FlowNode.SameApp -> "Same app again"
    FlowNode.SessionEnd -> "Locked phone / ended session"
}

// -- Trigger evidence ----------------------------------------------------------------------------------------------

@Composable
private fun TriggerCard(d: AppDeepDive) {
    val t = d.insights.triggers
    val status = LocalStatusColors.current
    SectionCard(title = "Trigger evidence", eyebrow = "${t.total} arrivals", icon = Icons.Outlined.NotificationsActive) {
        if (t.total == 0) {
            Text("No arrivals into this app in this window.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@SectionCard
        }
        val combined = t.combined
        val max = combined.values.maxOrNull()?.takeIf { it > 0 } ?: 1
        TriggerEvidence.entries.filter { (combined[it] ?: 0) > 0 }.forEach { ev ->
            val n = combined[ev] ?: 0
            val color = when {
                ev.isNotificationLed -> MaterialTheme.colorScheme.tertiary
                ev == TriggerEvidence.NO_OBSERVED_TRIGGER -> MaterialTheme.colorScheme.primary
                else -> status.neutral
            }
            val starts = t.episodeStarts[ev] ?: 0
            val mid = t.midSession[ev] ?: 0
            ShareBar("${ev.badge} · $starts start / $mid mid", n, n.toFloat() / max, percent(n, t.total), color)
        }
        Text(
            "Episode starts carry the pickup's trigger; mid-session switches carry their own (catching shade taps). " +
                "${t.observable} of ${t.total} arrivals happened while the notification listener was recording; " +
                "${d.notifCoveredDays} of ${d.dataDays} days had full notification coverage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// -- Friction & value ----------------------------------------------------------------------------------------------

@Composable
private fun FrictionValueCard(d: AppDeepDive) {
    val f = d.insights.friction
    val v = d.insights.value
    val status = LocalStatusColors.current
    SectionCard(title = "Friction & value", eyebrow = "Outcomes", icon = Icons.Outlined.SelfImprovement) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Star, null, tint = MaterialTheme.colorScheme.tertiary)
            Spacer(Modifier.width(Space.s))
            Column(Modifier.weight(1f)) {
                Text(
                    v.averageRating?.let { String.format(Locale.getDefault(), "%.1f / 5 worth it", it) } ?: "Not rated yet",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${v.rated} of ${v.episodes} episodes with this app rated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (v.rated > 0) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                StatusPill("${v.worth.valuedCount} valued", status.good)
                StatusPill("${v.worth.neutralCount} neutral", status.neutral)
                StatusPill("${v.worth.regrettedCount} regretted", status.bad)
            }
        }
        if (!f.hasData) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No launch pauses or timers for this app yet. Once gentle friction is switched on, immediate exits, quick reopens and timer extensions show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Space.m),
                )
            }
        } else {
            StatRow {
                StatTile("Immediate exits", "${f.immediateExits}", Modifier.weight(1f), caption = f.exitRate?.let { "${(it * 100).roundToInt()}% of ${f.launchPauses} pauses" })
                StatTile("Quick reopens", "${f.quickReopens}", Modifier.weight(1f), caption = f.quickReopenRate?.let { "${(it * 100).roundToInt()}% within 10m" })
            }
            StatRow {
                StatTile("Timer check-ins", "${f.timerCheckIns}", Modifier.weight(1f))
                StatTile("+5 min extensions", "${f.extensions}", Modifier.weight(1f), caption = f.extensionRate?.let { "${(it * 100).roundToInt()}% of check-ins" })
            }
        }
    }
}

// -- Bits ----------------------------------------------------------------------------------------------------------

@Composable
private fun ShareBar(label: String, count: Int, fraction: Float, trailing: String, color: Color) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$count · $trailing", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .height(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

private fun percent(n: Int, total: Int): String = if (total <= 0) "0%" else "${(n * 100.0 / total).roundToInt()}%"
