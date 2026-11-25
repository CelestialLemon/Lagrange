package com.celestiallemon.lagrange.ui.trends

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.domain.AppRow
import com.celestiallemon.lagrange.domain.AppSort
import com.celestiallemon.lagrange.domain.TrendMetric
import com.celestiallemon.lagrange.domain.TrendReport
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.ui.components.AppIcon
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.EmptyState
import com.celestiallemon.lagrange.ui.components.Info
import com.celestiallemon.lagrange.ui.components.InfoButton
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.Meter
import com.celestiallemon.lagrange.ui.components.Panel
import com.celestiallemon.lagrange.ui.components.RollingText
import com.celestiallemon.lagrange.ui.components.SectionHeader
import com.celestiallemon.lagrange.ui.components.Stat
import com.celestiallemon.lagrange.ui.components.StatStrip
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.friction.label
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.Transitions
import com.celestiallemon.lagrange.ui.theme.rise
import java.time.LocalDate
import kotlin.math.roundToInt

/** Pickups and episodes share one entry in the metric menu, with a toggle in the chart. */
private enum class MetricChoice(val label: String, val metrics: List<TrendMetric>) {
    DISTRACTING("Distracting time", listOf(TrendMetric.DISTRACTING)),
    ACTIVE("Active time", listOf(TrendMetric.ACTIVE_USAGE)),
    SCREEN("Screen on", listOf(TrendMetric.TOTAL_SCREEN)),
    PICKUPS("Pickups & sessions", listOf(TrendMetric.PHYSICAL_PICKUPS, TrendMetric.EPISODES)),
    GLANCES("Glances", listOf(TrendMetric.GLANCES)),
    RABBIT("Rabbit holes", listOf(TrendMetric.RABBIT_HOLES)),
    WORTH("Valued vs regretted", listOf(TrendMetric.VALUED_VS_REGRETTED)),
    ;

    companion object {
        fun of(m: TrendMetric) = entries.first { m in it.metrics }
    }
}

private const val APPS_PAGE = 25

/** Everything Trends shows, from the view model. */
class TrendsUiModel(
    val ui: TrendsUiState,
    val report: TrendReport?,
    val apps: List<AppRow>?,
    val metric: TrendMetric,
    val query: String,
    val category: AppCategory?,
    val sort: AppSort,
)

class TrendsActions(
    val onWindow: (com.celestiallemon.lagrange.domain.TrendWindow) -> Unit = {},
    val onMetric: (TrendMetric) -> Unit = {},
    val onQuery: (String) -> Unit = {},
    val onCategory: (AppCategory?) -> Unit = {},
    val onSort: (AppSort) -> Unit = {},
    val onRetry: () -> Unit = {},
    val onOpenApp: (Long) -> Unit = {},
)

@Composable
fun TrendsScreen(padding: PaddingValues, onOpenApp: (Long) -> Unit, vm: TrendsViewModel = viewModel(), actions: @Composable RowScope.() -> Unit = {}) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val report by vm.report.collectAsStateWithLifecycle()
    val apps by vm.apps.collectAsStateWithLifecycle()
    val metric by vm.metric.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val category by vm.category.collectAsStateWithLifecycle()
    val sort by vm.sort.collectAsStateWithLifecycle()
    TrendsContent(
        TrendsUiModel(ui, report, apps, metric, query, category, sort),
        padding,
        TrendsActions(vm::setWindow, vm::setMetric, vm::setQuery, vm::setCategory, vm::setSort, vm::retry, onOpenApp),
        actions,
    )
}

@Composable
fun TrendsContent(model: TrendsUiModel, padding: PaddingValues, actions: TrendsActions, topActions: @Composable RowScope.() -> Unit = {}) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var ma7 by rememberSaveable { mutableStateOf(true) }
    var ma28 by rememberSaveable { mutableStateOf(false) }
    var appsShown by rememberSaveable { mutableIntStateOf(APPS_PAGE) }
    val ui = model.ui
    val loaded = ui.overview
    val r = model.report

    LagrangeScreen(title = "Trends", contentPadding = padding, actions = topActions) {
        item("window") {
            // The chips show the window asked for; the range line under them is the one the figures below are for.
            WindowChips(
                ui.requested, loaded?.data?.today, loaded?.data?.earliest,
                onWindow = { actions.onWindow(it); selected = null },
                range = loaded?.data?.range,
                modifier = Modifier.rise(0),
            )
        }
        item("status") {
            Column {
                AnimatedVisibility(ui.loading && r != null, enter = Transitions.reveal, exit = Transitions.conceal) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Spacing.xs).semantics { contentDescription = "Updating trends" })
                }
                ui.error?.let { error ->
                    Banner(
                        "Trends couldn't update",
                        Tone.Critical,
                        Icons.Outlined.ErrorOutline,
                        body = if (r != null) "Still showing ${rangeLabel(r.range)}. $error" else error,
                        action = "Try again" to actions.onRetry,
                    )
                }
            }
        }
        if (r == null) {
            if (ui.loading) item("loading") { EmptyState("Gathering your history", "Reading every recorded day in this window.") }
            return@LagrangeScreen
        }

        item("hero") { TrendsHero(r, model.metric, loaded?.data?.window?.kind == WindowKind.ALL, actions.onMetric, Modifier.rise(1)) }
        item("chart") {
            val bar = barColor(r.metric)
            val selectedDate = selected?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ChartPanel(r, bar, ma7, ma28, { ma7 = !ma7 }, { ma28 = !ma28 }, selectedDate, { selected = it?.toString() }, actions.onMetric, Modifier.rise(2))
        }
        item("coverage") {
            Row(Modifier.rise(3).padding(horizontal = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                CoverageLegend(r.coveredDays, r.partialDays, r.noDataDays, r.inProgressDays, barColor(r.metric), Modifier.weight(1f))
                InfoButton(
                    Info(
                        "Covered, partial and missing days",
                        "A covered day was recorded from start to end. A partial day had a gap in recording (the phone was " +
                            "off, or Lagrange was stopped): its bar shows what was seen, hatched, and it never enters an average " +
                            "or comparison. A day with no data is left empty rather than drawn as zero.\n\n" +
                            "Averages and the change against the previous period only use covered days, and only when each " +
                            "side has enough of them." + (deltaDetail(r.delta, r.metric.unit)?.let { "\n\n$it" } ?: ""),
                    ),
                )
            }
        }
        if (r.metric == TrendMetric.VALUED_VS_REGRETTED) item("worth") { WorthBreakdown(r) }
        item("split") {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                SectionHeader("Weekdays and weekends", supporting = "${r.metric.label}, average per covered day")
                WeekSplitContent(r.split, r.metric.unit, barColor(r.metric))
            }
        }

        val all = loaded?.apps ?: return@LagrangeScreen
        val list = model.apps.orEmpty()
        item("apps-header") {
            AppsHeader(
                count = list.size,
                query = model.query,
                onQuery = { actions.onQuery(it); appsShown = APPS_PAGE },
                category = model.category,
                onCategory = { actions.onCategory(it); appsShown = APPS_PAGE },
                sort = model.sort,
                onSort = actions.onSort,
                categories = all.map { it.meta.category }.distinct(),
            )
        }
        if (list.isEmpty()) {
            item("apps-empty") {
                Text(
                    if (all.isEmpty()) "No app usage in this window yet." else "No apps match.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.l),
                )
            }
        }
        items(list.take(appsShown), key = { "app-${it.meta.pkgId}" }) { row -> AppRowItem(row, onClick = { actions.onOpenApp(row.meta.pkgId) }) }
        if (list.size > appsShown) {
            item("apps-more") {
                TextButton(onClick = { appsShown += APPS_PAGE }, modifier = Modifier.fillMaxWidth()) { Text("Show ${minOf(APPS_PAGE, list.size - appsShown)} more") }
            }
        }
    }
}

@Composable
private fun barColor(metric: TrendMetric): Color = when (metric) {
    TrendMetric.DISTRACTING -> categoryColor(AppCategory.DISTRACTING)
    TrendMetric.VALUED_VS_REGRETTED -> Lagrange.colors.good.accent
    TrendMetric.RABBIT_HOLES -> Lagrange.colors.ember.accent
    else -> MaterialTheme.colorScheme.primary
}

/** The answer for this window: the metric (a menu), its daily average, and how that compares. */
@Composable
private fun TrendsHero(r: TrendReport, metric: TrendMetric, allTime: Boolean, onMetric: (TrendMetric) -> Unit, modifier: Modifier) {
    var menu by remember { mutableStateOf(false) }
    val choice = MetricChoice.of(metric)
    Column(modifier.padding(top = Spacing.l, bottom = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Box {
            TextButton(onClick = { menu = true }, contentPadding = PaddingValues(start = 0.dp, end = Spacing.s)) {
                Text(choice.label, style = MaterialTheme.typography.titleMedium)
                Icon(Icons.Outlined.ArrowDropDown, contentDescription = "Change metric")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                MetricChoice.entries.forEach { c ->
                    DropdownMenuItem(text = { Text(c.label) }, onClick = { menu = false; onMetric(c.metrics.first()) })
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            RollingText(r.coveredMean?.let { formatMetric(it, r.metric.unit) } ?: "–", NumberStyles.hero)
            Spacer(Modifier.width(Spacing.s))
            Text(
                if (r.metric == TrendMetric.VALUED_VS_REGRETTED) "net valued a day" else "a day, on average",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        DeltaLine(r.delta, r.metric.unit, r.metric.higherIsBetter, allTime)
    }
}

@Composable
private fun ChartPanel(
    r: TrendReport,
    bar: Color,
    ma7: Boolean,
    ma28: Boolean,
    toggle7: () -> Unit,
    toggle28: () -> Unit,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    onMetric: (TrendMetric) -> Unit,
    modifier: Modifier,
) {
    val metric = r.metric
    val colors = rememberTrendColors(bar = bar)
    val budget = r.points.lastOrNull { it.reference != null }?.reference
    val refLabel = budget?.let { "Budget ${formatDurationShort(it.toLong())}" }
    Panel(modifier, spacing = Spacing.m) {
        if (metric == TrendMetric.PHYSICAL_PICKUPS || metric == TrendMetric.EPISODES) {
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf(TrendMetric.PHYSICAL_PICKUPS, TrendMetric.EPISODES).forEachIndexed { i, m ->
                    SegmentedButton(selected = metric == m, onClick = { onMetric(m) }, shape = SegmentedButtonDefaults.itemShape(i, 2)) {
                        Text(if (m == TrendMetric.EPISODES) "Sessions" else m.label)
                    }
                }
            }
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
                    TrendMetric.PHYSICAL_PICKUPS -> "sessions"
                    TrendMetric.VALUED_VS_REGRETTED -> "regretted"
                    else -> null
                },
            )
        } else {
            Text(
                when (metric) {
                    TrendMetric.VALUED_VS_REGRETTED -> "Valued time rises above the line, regretted time falls below. Tap a day for details."
                    TrendMetric.PHYSICAL_PICKUPS -> "The inner bar is sessions (unlocks, quick locks merged). Tap a day for details."
                    else -> "Tap a day for details."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun WorthBreakdown(r: TrendReport) {
    val w = r.worth
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader("How time was judged", supporting = "${w.ratedCount} of ${w.ratedCount + w.unratedCount} sessions rated")
        StatStrip {
            Stat("Valued", formatDurationShort(w.valuedMs), Modifier.weight(1f), caption = "${w.valuedCount} sessions", captionTone = Tone.Good)
            Stat("Neutral", formatDurationShort(w.neutralMs), Modifier.weight(1f), caption = "${w.neutralCount} sessions")
            Stat("Regretted", formatDurationShort(w.regrettedMs), Modifier.weight(1f), caption = "${w.regrettedCount} sessions", captionTone = Tone.Critical)
        }
        if (w.unratedMs > 0) {
            Text(
                "${formatDurationShort(w.unratedMs)} of unrated time is left out rather than guessed.",
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
    var sortMenu by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionHeader("Apps", supporting = "$count in this window", modifier = Modifier.weight(1f))
            Box(Modifier.padding(top = Spacing.xl)) {
                TextButton(onClick = { sortMenu = true }) {
                    Icon(Icons.Outlined.Sort, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.xs))
                    Text("By ${sort.label.lowercase()}")
                }
                DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    AppSort.entries.forEach { s -> DropdownMenuItem(text = { Text("By ${s.label.lowercase()}") }, onClick = { sortMenu = false; onSort(s) }) }
                }
            }
        }
        TextField(
            value = query,
            onValueChange = onQuery,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = { Text("Search apps") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = if (query.isNotEmpty()) ({ IconButton(onClick = { onQuery("") }) { Icon(Icons.Outlined.Close, "Clear search") } }) else null,
            shape = CircleShape,
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            ),
        )
        val options = listOf<AppCategory?>(null) + listOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY, AppCategory.EXCLUDED, AppCategory.SELF_APP)
            .filter { it in categories || it == category }
        Choice(options, category, onCategory, { it?.label ?: "All" }, forceChips = true)
    }
}

/** One app in the window: its icon (or letter in its category colour), time and opens, change vs before, and its goal if any. */
@Composable
private fun AppRowItem(row: AppRow, onClick: () -> Unit) {
    val color = categoryColor(row.meta.category)
    val goal = row.meta.dailyGoalMs
    val frac = row.goalFraction
    Column(
        Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        ListRow(
            headline = row.meta.displayName,
            supporting = "${formatDurationShort(row.totalMs)} · ${row.opens} opens" + (row.dailyAvgMs?.let { " · ${formatDurationShort(it.toLong())} a day" } ?: ""),
            supportingLines = 1,
            leading = { AppIcon(row.meta.packageName, row.meta.displayName, color) },
            trailing = {
                row.trendPct?.let { pct ->
                    val up = pct > 0.02
                    val down = pct < -0.02
                    // More time is a warning only for distracting apps; elsewhere it is just a change.
                    val tone = when {
                        up && row.meta.category == AppCategory.DISTRACTING -> Tone.Ember
                        down && row.meta.category == AppCategory.DISTRACTING -> Tone.Good
                        else -> Tone.Neutral
                    }
                    Tag((if (up) "▲ " else if (down) "▼ " else "") + formatPct(pct), tone = tone)
                }
            },
            onClick = onClick,
            tonal = false,
        )
        if (goal != null && frac != null) {
            Column(Modifier.padding(start = 72.dp, end = Spacing.l, bottom = Spacing.m), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Meter(frac.toFloat(), if (frac > 1.0) Lagrange.colors.caution.accent else Lagrange.colors.good.accent, height = 4.dp)
                Text("Goal ${formatDurationShort(goal)} a day · ${(frac * 100).roundToInt()}%", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
