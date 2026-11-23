package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.TrendingDown
import androidx.compose.material.icons.outlined.TrendingFlat
import androidx.compose.material.icons.outlined.TrendingUp
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.DayAverage
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.MetricUnit
import com.celestiallemon.lagrange.domain.PeriodDelta
import com.celestiallemon.lagrange.domain.TrendPoint
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.WeekSplit
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.EyebrowStyle
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import com.celestiallemon.lagrange.ui.today.dayStatusOf
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// -- Formatting ----------------------------------------------------------------------------------------------------

/** A metric value for display: durations as "1h 5m" (signed when negative), counts with one decimal below ten. */
fun formatMetric(v: Double?, unit: MetricUnit): String {
    if (v == null) return "–"
    return when (unit) {
        MetricUnit.DURATION -> (if (v < 0) "−" else "") + formatDurationShort(abs(v).toLong())
        MetricUnit.COUNT -> if (abs(v) >= 10 || v == v.roundToInt().toDouble()) "${v.roundToInt()}" else String.format(Locale.getDefault(), "%.1f", v)
    }
}

fun formatPct(p: Double): String = "${(abs(p) * 100).roundToInt()}%"

fun rangeLabel(r: DateRange): String = if (r.start == r.end) formatDay(r.start) else "${formatDay(r.start)} – ${formatDay(r.end)}"

val DayQuality.label: String
    get() = when (this) {
        DayQuality.COVERED -> "Covered"
        DayQuality.BACKFILL -> "Backfilled history"
        DayQuality.PARTIAL -> "Partial coverage"
        DayQuality.IN_PROGRESS -> "Today, in progress"
        DayQuality.NO_DATA -> "No data"
    }

@Composable
fun qualityColor(q: DayQuality): Color {
    val status = LocalStatusColors.current
    return when (q) {
        DayQuality.COVERED -> status.good
        DayQuality.BACKFILL -> MaterialTheme.colorScheme.secondary
        DayQuality.PARTIAL -> status.warn
        DayQuality.IN_PROGRESS -> MaterialTheme.colorScheme.primary
        DayQuality.NO_DATA -> status.neutral
    }
}

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()

// -- Window selector -----------------------------------------------------------------------------------------------

/** `7D · 30D · 90D · 1Y · All · Custom`; Custom opens a Material date-range picker limited to [today]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowChips(window: TrendWindow, today: LocalDate?, earliest: LocalDate?, onWindow: (TrendWindow) -> Unit, range: DateRange?) {
    var picking by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            WindowKind.entries.forEach { k ->
                FilterChip(
                    selected = window.kind == k,
                    onClick = { if (k == WindowKind.CUSTOM) picking = true else onWindow(TrendWindow(k)) },
                    label = { Text(k.label) },
                    leadingIcon = if (k == WindowKind.CUSTOM) ({ Icon(Icons.Outlined.DateRange, null, Modifier.size(18.dp)) }) else null,
                )
            }
        }
        if (range != null) {
            Text(rangeLabel(range) + " · ${range.days} days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (picking) {
        val max = today ?: LocalDate.now()
        val state = rememberDateRangePickerState(
            initialSelectedStartDateMillis = (window.customStart ?: range?.start)?.utcMillis(),
            initialSelectedEndDateMillis = (window.customEnd ?: range?.end)?.utcMillis(),
            yearRange = (earliest ?: max.minusYears(1)).year..max.year,
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = !utcTimeMillis.utcDate().isAfter(max)
                override fun isSelectableYear(year: Int): Boolean = year <= max.year
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    enabled = state.selectedStartDateMillis != null,
                    onClick = {
                        val start = state.selectedStartDateMillis?.utcDate()
                        if (start != null) onWindow(TrendWindow.custom(start, state.selectedEndDateMillis?.utcDate() ?: start))
                        picking = false
                    },
                ) { Text("Apply") }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = state, modifier = Modifier.height(480.dp), showModeToggle = true)
        }
    }
}

// -- Delta + coverage ----------------------------------------------------------------------------------------------

/** "▼ 14% vs previous 30 days", coloured by whether that is good for this metric; or why there's no comparison. */
@Composable
fun DeltaLine(delta: PeriodDelta, unit: MetricUnit, higherIsBetter: Boolean, allTime: Boolean) {
    val status = LocalStatusColors.current
    val prev = delta.previous
    when {
        allTime || prev == null -> Text(
            "All-time has no earlier period to compare against.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        !delta.sufficient -> Text(
            "Not enough covered days to compare yet: ${delta.current.coveredDays} now and ${prev.coveredDays} before; " +
                "each side needs ${delta.minCoveredDays}.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        else -> {
            val d = delta.deltaMean!!
            val flat = abs(d) < 1e-9 || (delta.pctChange?.let { abs(it) < 0.02 } ?: false)
            val better = if (higherIsBetter) d > 0 else d < 0
            val color = when {
                flat -> status.neutral
                better -> status.good
                else -> status.warn
            }
            val icon = when {
                flat -> Icons.Outlined.TrendingFlat
                d > 0 -> Icons.Outlined.TrendingUp
                else -> Icons.Outlined.TrendingDown
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(Space.s))
                val pct = delta.pctChange
                val magnitude = if (pct != null) formatPct(pct) else formatMetric(abs(d), unit)
                Text(
                    when {
                        flat -> "About the same as the previous ${prev.range.days} days"
                        else -> "$magnitude ${if (d > 0) "higher" else "lower"} than the previous ${prev.range.days} days"
                    },
                    style = MaterialTheme.typography.titleSmall,
                    color = color,
                )
            }
            Text(
                "Daily mean ${formatMetric(delta.current.mean, unit)} vs ${formatMetric(prev.mean, unit)} · median " +
                    "${formatMetric(delta.current.median, unit)} vs ${formatMetric(prev.median, unit)} · " +
                    "${delta.current.coveredDays} vs ${prev.coveredDays} covered days",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun CoveragePills(covered: Int, partial: Int, noData: Int, inProgress: Int) {
    val status = LocalStatusColors.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        StatusPill("$covered covered", status.good)
        if (partial > 0) StatusPill("$partial partial", status.warn)
        if (noData > 0) StatusPill("$noData no data", status.neutral)
        if (inProgress > 0) StatusPill("Today in progress", MaterialTheme.colorScheme.primary)
    }
}

/** Legend for the trend chart: the bars, the averages that are switched on, and the reference line. */
@Composable
fun ChartLegend(ma7: Boolean, ma28: Boolean, reference: String?, colors: TrendColors, onToggle7: () -> Unit, onToggle28: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        FilterChip(
            selected = ma7,
            onClick = onToggle7,
            label = { Text("7-day avg") },
            leadingIcon = { LegendSwatch(colors.ma7) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.ma7.copy(alpha = 0.16f)),
        )
        FilterChip(
            selected = ma28,
            onClick = onToggle28,
            label = { Text("28-day avg") },
            leadingIcon = { LegendSwatch(colors.ma28) },
            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = colors.ma28.copy(alpha = 0.16f)),
        )
        if (reference != null) {
            Row(Modifier.padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                LegendSwatch(colors.reference)
                Spacer(Modifier.width(6.dp))
                Text(reference, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun LegendSwatch(color: Color) {
    Box(
        Modifier
            .size(width = 14.dp, height = 4.dp)
            .clip(CircleShape)
            .background(color),
    )
}

/** The tapped day: date, value, averages, coverage and day-status badges. */
@Composable
fun DayCallout(p: TrendPoint, unit: MetricUnit, valueLabel: String, secondaryLabel: String?) {
    val s = p.sample
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDay(p.date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusPill(s.quality.label, qualityColor(s.quality))
            }
            if (s.value != null) {
                Text("$valueLabel ${formatMetric(s.value, unit)}" + (secondaryLabel?.let { l -> s.secondary?.let { " · $l ${formatMetric(it, unit)}" } } ?: ""), style = MaterialTheme.typography.bodyLarge)
            } else {
                Text("Nothing was recorded this day, so it is left out rather than counted as zero.", style = MaterialTheme.typography.bodyMedium)
            }
            val avgs = listOfNotNull(p.ma7?.let { "7-day ${formatMetric(it, unit)}" }, p.ma28?.let { "28-day ${formatMetric(it, unit)}" }, p.reference?.let { "line ${formatMetric(it, unit)}" })
            if (avgs.isNotEmpty()) {
                Text(avgs.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            dayStatusOf(s.dayStatus?.name)?.let { st ->
                val text = when (st) {
                    com.celestiallemon.lagrange.data.db.DayStatus.SPIKE -> "Spike day vs its baseline"
                    com.celestiallemon.lagrange.data.db.DayStatus.GOOD_DAY -> "Good day vs its baseline"
                    else -> null
                }
                if (text != null) {
                    val c = if (st == com.celestiallemon.lagrange.data.db.DayStatus.SPIKE) LocalStatusColors.current.warn else LocalStatusColors.current.good
                    StatusPill(text, c)
                }
            }
            if (!s.quality.countsAsCovered && s.value != null) {
                Text(
                    "Shown as observed, but left out of averages and comparisons.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

// -- Weekday vs weekend --------------------------------------------------------------------------------------------

@Composable
fun WeekSplitContent(split: WeekSplit, unit: MetricUnit) {
    if (!split.visible) {
        Text(
            "Weekday and weekend averages unlock after ${split.minCoveredDays} covered days in this window (${split.coveredDays} so far). " +
                "Partial and missing days never count.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        SplitTile("Weekdays", split.weekday, unit, Modifier.weight(1f))
        SplitTile("Weekends", split.weekend, unit, Modifier.weight(1f))
    }
    val max = split.byDayOfWeek.values.mapNotNull { it.mean }.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    Row(
        Modifier
            .fillMaxWidth()
            .height(120.dp),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
        verticalAlignment = Alignment.Bottom,
    ) {
        DayOfWeek.entries.forEach { dow ->
            val a = split.byDayOfWeek[dow] ?: DayAverage(null, 0)
            val weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Bottom) {
                Text(
                    formatMetric(a.mean, unit),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                )
                Spacer(Modifier.height(2.dp))
                val frac = ((a.mean ?: 0.0) / max).toFloat().coerceIn(0f, 1f)
                Box(
                    Modifier
                        .fillMaxWidth(0.7f)
                        .fillMaxHeight(0.62f * frac + 0.02f)
                        .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                        .background(if (weekend) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary.copy(alpha = if (a.days > 0) 0.75f else 0.2f)),
                )
                Spacer(Modifier.height(4.dp))
                Text(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelMedium)
                Text("n=${a.days}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SplitTile(label: String, a: DayAverage, unit: MetricUnit, modifier: Modifier) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(Space.m)) {
            Text(label.uppercase(), style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Space.xs))
            Text(formatMetric(a.mean, unit), style = MaterialTheme.typography.headlineSmall)
            Text("${a.days} covered days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
