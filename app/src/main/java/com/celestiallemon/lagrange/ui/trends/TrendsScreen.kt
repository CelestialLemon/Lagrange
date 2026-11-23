package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.domain.AppRow
import com.celestiallemon.lagrange.domain.AppSort
import com.celestiallemon.lagrange.domain.TrendMetric
import com.celestiallemon.lagrange.domain.TrendReport
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.friction.label
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import java.time.LocalDate
import kotlin.math.roundToInt

/** The metric chips. Pickups and episodes share one chip with a toggle inside the chart card. */
private enum class MetricChip(val label: String, val metrics: List<TrendMetric>) {
    DISTRACTING("Distracting", listOf(TrendMetric.DISTRACTING)),
    ACTIVE("Active", listOf(TrendMetric.ACTIVE_USAGE)),
    SCREEN("Screen on", listOf(TrendMetric.TOTAL_SCREEN)),
    PICKUPS("Pickups & episodes", listOf(TrendMetric.PHYSICAL_PICKUPS, TrendMetric.EPISODES)),
    GLANCES("Glances", listOf(TrendMetric.GLANCES)),
    RABBIT("Rabbit holes", listOf(TrendMetric.RABBIT_HOLES)),
    WORTH("Valued vs regretted", listOf(TrendMetric.VALUED_VS_REGRETTED)),
    ;

    companion object {
        fun of(m: TrendMetric) = entries.first { m in it.metrics }
    }
}

private const val APPS_PAGE = 25

@Composable
fun TrendsScreen(padding: PaddingValues, onOpenApp: (Long) -> Unit, vm: TrendsViewModel = viewModel()) {
    val loaded by vm.loaded.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val window by vm.window.collectAsStateWithLifecycle()
    val metric by vm.metric.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()

    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var ma7 by rememberSaveable { mutableStateOf(true) }
    var ma28 by rememberSaveable { mutableStateOf(true) }
    var appsShown by rememberSaveable { mutableStateOf(APPS_PAGE) }

    val r = report
    LagrangeScreen(
        eyebrow = "Trends",
        title = "The long view",
        subtitle = "Every day drawn as it was seen. Partial and missing days are shaded, never counted as zero.",
        contentPadding = padding,
    ) {
        item("window") {
            WindowChips(window, loaded?.data?.today, loaded?.data?.earliest, onWindow = { vm.setWindow(it); selected = null }, range = loaded?.data?.range)
        }
        item("metric") {
            val chip = MetricChip.of(metric)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                MetricChip.entries.forEach { c ->
                    FilterChip(selected = c == chip, onClick = { vm.setMetric(c.metrics.first()) }, label = { Text(c.label) })
                }
            }
        }
        if (loading && r == null) {
            item("loading") {
                SectionCard(title = "Gathering your history", eyebrow = "Trends", icon = Icons.Outlined.Sync) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
        }
        if (error != null && r == null) {
            item("error") {
                SectionCard(title = "Trends couldn't load", eyebrow = "Something went wrong") {
                    Text(error.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (r != null) {
            val selectedDate = selected?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            item("chart") {
                ChartCard(r, metric, ma7, ma28, { ma7 = !ma7 }, { ma28 = !ma28 }, selectedDate, { selected = it?.toString() }, onMetric = vm::setMetric)
            }
            item("summary") { SummaryCard(r, window.kind == WindowKind.ALL) }
            item("split") {
                SectionCard(title = "Weekdays vs weekends", eyebrow = metric.label, icon = Icons.Outlined.CalendarMonth) {
                    WeekSplitContent(r.split, metric.unit)
                }
            }
        }

        val all = loaded?.apps
        if (all != null) {
            item("apps-header") {
                AppsHeader(
                    count = apps?.size ?: 0,
                    query = query,
                    onQuery = { vm.setQuery(it); appsShown = APPS_PAGE },
                    category = category,
                    onCategory = { vm.setCategory(it); appsShown = APPS_PAGE },
                    sort = sort,
                    onSort = vm::setSort,
                    categories = all.map { it.meta.category }.distinct(),
                )
            }
            val list = apps.orEmpty()
            if (list.isEmpty()) {
                item("apps-empty") {
                    Text(
                        if (all.isEmpty()) "No app usage in this window yet." else "No apps match.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.s),
                    )
                }
            }
            items(list.take(appsShown), key = { "app-${it.meta.pkgId}" }) { row ->
                AppRowCard(row, onClick = { onOpenApp(row.meta.pkgId) })
            }
            if (list.size > appsShown) {
                item("apps-more") {
                    TextButton(onClick = { appsShown += APPS_PAGE }, modifier = Modifier.fillMaxWidth()) {
                        Text("Show more (${list.size - appsShown} left)")
                    }
                }
            }
        }
    }
}

@Composable
private fun ChartCard(
    r: TrendReport,
    metric: TrendMetric,
    ma7: Boolean,
    ma28: Boolean,
    toggle7: () -> Unit,
    toggle28: () -> Unit,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    onMetric: (TrendMetric) -> Unit,
) {
    val status = LocalStatusColors.current
    val colors = rememberTrendColors(
        bar = when (metric) {
            TrendMetric.DISTRACTING -> categoryColor(AppCategory.DISTRACTING)
            TrendMetric.VALUED_VS_REGRETTED -> status.good
            else -> MaterialTheme.colorScheme.primary
        },
    )
    val budget = r.points.lastOrNull { it.reference != null }?.reference
    val refLabel = budget?.let { "Budget ${formatDurationShort(it.toLong())}" }
    SectionCard(title = headlineFor(r), eyebrow = "${metric.label} · ${r.range.days} days", icon = Icons.AutoMirrored.Outlined.ShowChart) {
        if (metric == TrendMetric.PHYSICAL_PICKUPS || metric == TrendMetric.EPISODES) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(TrendMetric.PHYSICAL_PICKUPS, TrendMetric.EPISODES).forEachIndexed { i, m ->
                    SegmentedButton(
                        selected = metric == m,
                        onClick = { onMetric(m) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(m.label) }
                }
            }
            Text(
                if (metric == TrendMetric.PHYSICAL_PICKUPS) "Bars are physical pickups (micro-locks included); the inner bar is behavioural episodes."
                else "Episodes: unlocked stretches of use, micro-locks merged.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (metric == TrendMetric.VALUED_VS_REGRETTED) {
            Text(
                "Valued time rises above the line and regretted time falls below it. Averages follow the net (valued minus regretted).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TrendChart(
            points = r.points,
            unit = metric.unit,
            diverging = metric.diverging,
            showMa7 = ma7,
            showMa28 = ma28,
            referenceLabel = refLabel,
            selected = selected,
            onSelect = onSelect,
            description = "${metric.label} over ${r.range.days} days, averaging ${formatMetric(r.coveredMean, metric.unit)} per covered day",
            colors = colors,
        )
        ChartLegend(ma7, ma28, refLabel?.let { "Daily budget" }, colors, toggle7, toggle28)
        val point = selected?.let { d -> r.points.firstOrNull { it.date == d } }
        if (point != null) {
            DayCallout(
                point,
                metric.unit,
                valueLabel = if (metric == TrendMetric.VALUED_VS_REGRETTED) "Valued" else metric.label,
                secondaryLabel = when (metric) {
                    TrendMetric.PHYSICAL_PICKUPS -> "episodes"
                    TrendMetric.VALUED_VS_REGRETTED -> "regretted"
                    else -> null
                },
            )
        } else {
            Text("Tap a day to inspect it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun headlineFor(r: TrendReport): String {
    val mean = r.coveredMean ?: return "No covered days yet"
    val v = formatMetric(mean, r.metric.unit)
    return when (r.metric) {
        TrendMetric.VALUED_VS_REGRETTED -> "$v net valued / day"
        else -> "$v / covered day"
    }
}

@Composable
private fun SummaryCard(r: TrendReport, allTime: Boolean) {
    SectionCard(title = "Change & coverage", eyebrow = "Compared fairly", icon = Icons.Outlined.Insights) {
        DeltaLine(r.delta, r.metric.unit, r.metric.higherIsBetter, allTime)
        CoveragePills(r.coveredDays, r.partialDays, r.noDataDays, r.inProgressDays)
        val unit = r.metric.unit
        Text(
            "Observed in total: ${formatMetric(r.observedTotal, unit)}" +
                (if (r.partialDays + r.inProgressDays > 0) " (partial days included; averages use covered days only)." else "."),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (r.metric == TrendMetric.VALUED_VS_REGRETTED) {
            val w = r.worth
            val status = LocalStatusColors.current
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                StatusPill("Valued ${formatDurationShort(w.valuedMs)} · ${w.valuedCount}", status.good)
                StatusPill("Neutral ${formatDurationShort(w.neutralMs)} · ${w.neutralCount}", status.neutral)
                StatusPill("Regretted ${formatDurationShort(w.regrettedMs)} · ${w.regrettedCount}", status.bad)
            }
            val total = w.ratedCount + w.unratedCount
            Text(
                if (total == 0) "No episodes in this window yet."
                else "${w.ratedCount} of $total episodes rated; ${formatDurationShort(w.unratedMs)} of unrated time is left out rather than guessed.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun AppsHeader(
    count: Int,
    query: String,
    onQuery: (String) -> Unit,
    category: AppCategory?,
    onCategory: (AppCategory?) -> Unit,
    sort: AppSort,
    onSort: (AppSort) -> Unit,
    categories: List<AppCategory>,
) {
    Column(Modifier.padding(top = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Apps, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(Space.s))
            Text("Apps", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            Text("$count", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedTextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") } }) else null,
            shape = CircleShape,
        )
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FilterChip(selected = category == null, onClick = { onCategory(null) }, label = { Text("All") })
            listOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY, AppCategory.EXCLUDED, AppCategory.SELF_APP)
                .filter { it in categories }
                .forEach { c -> FilterChip(selected = category == c, onClick = { onCategory(if (category == c) null else c) }, label = { Text(c.label) }) }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            AppSort.entries.forEachIndexed { i, s ->
                SegmentedButton(selected = sort == s, onClick = { onSort(s) }, shape = SegmentedButtonDefaults.itemShape(i, AppSort.entries.size)) { Text(s.label) }
            }
        }
    }
}

@Composable
private fun AppRowCard(row: AppRow, onClick: () -> Unit) {
    val status = LocalStatusColors.current
    val catColor = categoryColor(row.meta.category)
    Surface(
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(catColor),
                )
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(row.meta.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${formatDurationShort(row.totalMs)} · ${row.opens} opens" +
                            (row.dailyAvgMs?.let { " · ${formatDurationShort(it.toLong())}/day" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                row.trendPct?.let { pct ->
                    val up = pct > 0.02
                    val down = pct < -0.02
                    // More time is a warning only for distracting apps; elsewhere it is just a change.
                    val color = when {
                        up && row.meta.category == AppCategory.DISTRACTING -> status.warn
                        down && row.meta.category == AppCategory.DISTRACTING -> status.good
                        else -> status.neutral
                    }
                    StatusPill((if (up) "▲ " else if (down) "▼ " else "") + formatPct(pct), color)
                }
                Spacer(Modifier.width(Space.xs))
                Icon(Icons.Outlined.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val goal = row.meta.dailyGoalMs
            val frac = row.goalFraction
            if (goal != null && frac != null) {
                val over = frac > 1.0
                LinearProgressIndicator(
                    progress = { frac.toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = if (over) status.warn else status.good,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                    drawStopIndicator = {},
                )
                Text(
                    "Goal ${formatDurationShort(goal)}/day · averaging ${(frac * 100).roundToInt()}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
