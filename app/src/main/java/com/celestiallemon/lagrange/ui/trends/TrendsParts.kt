package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingDown
import androidx.compose.material.icons.automirrored.outlined.TrendingFlat
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.DayAverage
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.MetricUnit
import com.celestiallemon.lagrange.domain.PeriodDelta
import com.celestiallemon.lagrange.domain.TrendPoint
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.WeekSplit
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.ui.components.Stat
import com.celestiallemon.lagrange.ui.components.StatStrip
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.today.dayStatusOf
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong

// -- Formatting ----------------------------------------------------------------------------------------------------

/** A metric value for display: durations as "1h 5m" (signed when negative), counts with one decimal below ten. */
fun formatMetric(v: Double?, unit: MetricUnit): String {
    if (v == null || !v.isFinite()) return "–"
    // A sign only appears when the magnitude survives rounding, so there is never a "−0m" or "−0".
    return when (unit) {
        MetricUnit.DURATION -> {
            val ms = abs(v).roundToLong()
            val sign = if (v < 0 && ms > 0) "−" else ""
            sign + formatDurationShort(ms)
        }
        MetricUnit.COUNT -> {
            val tenths = (abs(v) * 10.0).roundToLong()
            val sign = if (v < 0 && tenths > 0) "−" else ""
            when {
                tenths == 0L -> "0"
                tenths >= 100L -> "$sign${abs(v).roundToLong()}"
                tenths % 10L == 0L -> "$sign${tenths / 10L}"
                else -> sign + String.format(Locale.getDefault(), "%.1f", tenths / 10.0)
            }
        }
    }
}

fun formatPct(p: Double): String = "${(abs(p) * 100).roundToInt()}%"

fun rangeLabel(r: DateRange): String = if (r.start == r.end) formatDay(r.start) else "${formatDay(r.start)} – ${formatDay(r.end)}"

val DayQuality.label: String
    get() = when (this) {
        DayQuality.COVERED -> "Covered"
        DayQuality.BACKFILL -> "Backfilled history"
        DayQuality.PARTIAL -> "Partial coverage"
        DayQuality.IN_PROGRESS -> "In progress"
        DayQuality.NO_DATA -> "No data"
    }

val DayQuality.tone: Tone
    get() = when (this) {
        DayQuality.COVERED -> Tone.Good
        DayQuality.BACKFILL -> Tone.Neutral
        DayQuality.PARTIAL -> Tone.Caution
        DayQuality.IN_PROGRESS -> Tone.Brand
        DayQuality.NO_DATA -> Tone.Neutral
    }

private fun LocalDate.utcMillis(): Long = atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.utcDate(): LocalDate = Instant.ofEpochMilli(this).atZone(ZoneOffset.UTC).toLocalDate()


// -- Window selector -----------------------------------------------------------------------------------------------

/** `7D · 30D · 90D · 1Y · All · Custom`; Custom opens a Material date-range picker limited to [today]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WindowChips(window: TrendWindow, today: LocalDate?, earliest: LocalDate?, onWindow: (TrendWindow) -> Unit, range: DateRange?, modifier: Modifier = Modifier) {
    var picking by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
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
            // Wide enough for the history, an earlier custom start, and at least last year.
            yearRange = listOfNotNull(earliest?.year, (window.customStart ?: range?.start)?.year, max.minusYears(1).year).min()..max.year,
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

/**
 * The change against the previous period as one tag ("▼ 14% vs previous 30 days"), toned by whether that's good
 * for this metric; or, in plain words, why there is no fair comparison.
 */
@Composable
fun DeltaLine(delta: PeriodDelta, unit: MetricUnit, higherIsBetter: Boolean, allTime: Boolean, modifier: Modifier = Modifier) {
    val prev = delta.previous
    when {
        allTime || prev == null -> Text(
            "All of history: nothing earlier to compare with.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        !delta.sufficient -> Text(
            "Not compared yet: ${delta.current.coveredDays} covered days now, ${prev.coveredDays} before (each side needs ${delta.minCoveredDays}).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )
        else -> {
            val d = delta.deltaMean!!
            val pct = delta.pctChange
            val magnitude = if (pct != null) formatPct(pct) else formatMetric(abs(d), unit)
            val flat = abs(d) < 1e-9 || (pct != null && abs(pct) < 0.02) || (pct == null && (magnitude == "0" || magnitude == "0m"))
            val better = if (higherIsBetter) d > 0 else d < 0
            val tone = when {
                flat -> Tone.Neutral
                better -> Tone.Good
                else -> Tone.Ember
            }
            val icon = when {
                flat -> Icons.AutoMirrored.Outlined.TrendingFlat
                d > 0 -> Icons.AutoMirrored.Outlined.TrendingUp
                else -> Icons.AutoMirrored.Outlined.TrendingDown
            }
            Tag(
                if (flat) "Same as previous ${prev.range.days} days" else "$magnitude ${if (d > 0) "higher" else "lower"} than previous ${prev.range.days} days",
                tone = tone,
                icon = icon,
                modifier = modifier,
            )
        }
    }
}

/** The numbers behind a delta, for an info sheet. */
fun deltaDetail(delta: PeriodDelta, unit: MetricUnit): String? {
    val prev = delta.previous ?: return null
    return "Daily mean ${formatMetric(delta.current.mean, unit)} vs ${formatMetric(prev.mean, unit)}; median " +
        "${formatMetric(delta.current.median, unit)} vs ${formatMetric(prev.median, unit)}; " +
        "${delta.current.coveredDays} vs ${prev.coveredDays} covered days."
}

/**
 * How many days each bar style stands for, using the same marks as the chart: solid for covered, hatched for
 * partial, faint for no data. Reading the legend teaches the chart.
 */
@Composable
fun CoverageLegend(covered: Int, partial: Int, noData: Int, inProgress: Int, bar: Color, modifier: Modifier = Modifier) {
    val hatch = Lagrange.colors.viz.hatch
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        LegendItem("$covered covered") { drawRoundRect(bar, cornerRadius = CornerRadius(3.dp.toPx())) }
        if (partial > 0) LegendItem("$partial partial") { hatchRect(Rect(Offset.Zero, size), hatch.copy(alpha = 0.8f), 3.dp.toPx()); drawRoundRect(hatch, style = Stroke(1.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx())) }
        if (noData > 0) LegendItem("$noData no data") { hatchRect(Rect(Offset.Zero, size), hatch.copy(alpha = 0.3f), 3.dp.toPx()) }
        if (inProgress > 0) LegendItem(if (inProgress == 1) "today so far" else "$inProgress in progress") { drawRoundRect(bar.copy(alpha = 0.38f), cornerRadius = CornerRadius(3.dp.toPx())) }
    }
}

@Composable
private fun LegendItem(text: String, swatch: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(width = 10.dp, height = 14.dp), onDraw = swatch)
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Legend for the trend chart: the averages (tap to show or hide) and the reference line. */
@Composable
fun ChartLegend(ma7: Boolean, ma28: Boolean, reference: String?, colors: TrendColors, onToggle7: () -> Unit, onToggle28: () -> Unit, dayMarkers: Boolean = false) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xs), itemVerticalAlignment = Alignment.CenterVertically) {
        FilterChip(selected = ma7, onClick = onToggle7, label = { Text("7-day avg") }, leadingIcon = { LineSwatch(colors.ma7, dashed = false) })
        FilterChip(selected = ma28, onClick = onToggle28, label = { Text("28-day avg") }, leadingIcon = { LineSwatch(colors.ma28, dashed = true) })
        if (reference != null) {
            Row(Modifier.padding(start = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
                LineSwatch(colors.reference, dashed = true)
                Spacer(Modifier.width(6.dp))
                Text(reference, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (dayMarkers) {
            Text("● spike  ● good day".let { it }, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = Spacing.xs))
        }
    }
}

@Composable
private fun LineSwatch(color: Color, dashed: Boolean) {
    Canvas(Modifier.size(width = 16.dp, height = 4.dp)) {
        if (dashed) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width * 0.4f, size.height / 2), strokeWidth = size.height)
            drawLine(color, Offset(size.width * 0.6f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height)
        } else {
            drawRoundRect(color, cornerRadius = CornerRadius(size.height / 2))
        }
    }
}

/** The tapped day, shown under the chart: date, value, averages and what kind of day it was. */
@Composable
fun DayCallout(p: TrendPoint, unit: MetricUnit, valueLabel: String, secondaryLabel: String?) {
    val s = p.sample
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatDay(p.date), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                when (dayStatusOf(s.dayStatus?.name)) {
                    DayStatus.SPIKE -> Tag("Spike", tone = Tone.Ember)
                    DayStatus.GOOD_DAY -> Tag("Good day", tone = Tone.Good)
                    else -> Unit
                }
                Spacer(Modifier.width(Spacing.xs))
                Tag(s.quality.label, tone = s.quality.tone)
            }
            if (s.value != null) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(formatMetric(s.value, unit), style = NumberStyles.stat)
                    Spacer(Modifier.width(Spacing.s))
                    Text(
                        valueLabel.lowercase() + (secondaryLabel?.let { l -> s.secondary?.let { " · ${formatMetric(it, unit)} $l" } } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 4.dp),
                    )
                }
            } else {
                Text("Nothing was recorded this day, so it is left out rather than counted as zero.", style = MaterialTheme.typography.bodyMedium)
            }
            val avgs = listOfNotNull(p.ma7?.let { "7-day ${formatMetric(it, unit)}" }, p.ma28?.let { "28-day ${formatMetric(it, unit)}" }, p.reference?.let { "line ${formatMetric(it, unit)}" })
            if (avgs.isNotEmpty()) Text(avgs.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!s.quality.countsAsCovered && s.value != null) {
                Text("Shown as observed, but left out of averages and comparisons.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

// -- Weekday vs weekend --------------------------------------------------------------------------------------------

@Composable
fun WeekSplitContent(split: WeekSplit, unit: MetricUnit, bar: Color) {
    if (!split.visible) {
        Text(
            "Unlocks after ${split.minCoveredDays} covered days in this window (${split.coveredDays} so far). Partial and missing days never count.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    StatStrip {
        Stat("Weekdays", formatMetric(split.weekday.mean, unit), Modifier.weight(1f), caption = "${split.weekday.days} covered days")
        Stat("Weekends", formatMetric(split.weekend.mean, unit), Modifier.weight(1f), caption = "${split.weekend.days} covered days")
    }
    val means = split.byDayOfWeek.values.mapNotNull { it.mean }
    val maxAbs = means.maxOfOrNull { abs(it) }?.takeIf { it > 0 } ?: 1.0
    // Net metrics (valued minus regretted) can be negative: then bars grow both ways from a centre line.
    val diverging = means.any { it < 0 }
    val axis = MaterialTheme.colorScheme.outlineVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(Modifier.fillMaxWidth().padding(top = Spacing.s), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        DayOfWeek.entries.forEach { dow ->
            val a = split.byDayOfWeek[dow] ?: DayAverage(null, 0)
            val weekend = dow == DayOfWeek.SATURDAY || dow == DayOfWeek.SUNDAY
            val name = dow.getDisplayName(TextStyle.FULL, Locale.getDefault())
            Column(
                Modifier
                    .weight(1f)
                    .clearAndSetSemantics {
                        contentDescription = if (a.mean == null) "$name: no covered days" else "$name: ${formatMetric(a.mean, unit)} on average over ${a.days} covered days"
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(formatMetric(a.mean, unit), style = NumberStyles.axis, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Clip)
                Spacer(Modifier.height(4.dp))
                val frac = ((a.mean ?: 0.0) / maxAbs).toFloat().coerceIn(-1f, 1f)
                val color = bar.copy(alpha = if (a.days == 0) 0.2f else if (weekend) 1f else 0.7f)
                Canvas(Modifier.fillMaxWidth(0.62f).height(72.dp)) {
                    val r = CornerRadius(6.dp.toPx())
                    if (!diverging) drawRoundRect(track, cornerRadius = r)
                    val base = if (diverging) size.height / 2f else size.height
                    val room = if (diverging) size.height / 2f else size.height
                    val h = (abs(frac) * room).coerceAtLeast(2.dp.toPx())
                    val top = if (frac >= 0f) base - h else base
                    drawRoundRect(color, topLeft = Offset(0f, top), size = Size(size.width, h), cornerRadius = CornerRadius(minOf(r.x, h / 2f)))
                    if (diverging) drawLine(axis, Offset(-size.width * 0.3f, base), Offset(size.width * 1.3f, base), strokeWidth = 1.dp.toPx())
                }
                Spacer(Modifier.height(4.dp))
                Text(dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()), style = MaterialTheme.typography.labelMedium, color = if (weekend) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
