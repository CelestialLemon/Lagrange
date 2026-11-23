package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.HourBin
import com.celestiallemon.lagrange.domain.MetricUnit
import com.celestiallemon.lagrange.domain.TrendPoint
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private val axisDate = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
private val axisMonth = DateTimeFormatter.ofPattern("MMM yy", Locale.getDefault())

/** Colours a trend chart draws with; resolved from the theme by [rememberTrendColors]. */
data class TrendColors(
    val bar: Color,
    val secondary: Color,
    val negative: Color,
    val muted: Color,
    val ma7: Color,
    val ma28: Color,
    val reference: Color,
    val grid: Color,
    val label: Color,
    val highlight: Color,
    val spike: Color,
    val good: Color,
)

@Composable
fun rememberTrendColors(bar: Color = MaterialTheme.colorScheme.primary): TrendColors {
    val scheme = MaterialTheme.colorScheme
    val status = LocalStatusColors.current
    return TrendColors(
        bar = bar,
        secondary = scheme.secondary,
        negative = status.bad,
        muted = scheme.outline,
        ma7 = scheme.tertiary,
        ma28 = scheme.secondary,
        reference = status.warn,
        grid = scheme.outlineVariant.copy(alpha = 0.6f),
        label = scheme.onSurfaceVariant,
        highlight = scheme.onSurface.copy(alpha = 0.07f),
        spike = status.bad,
        good = status.good,
    )
}

/**
 * Daily bars with optional 7- and 28-day moving averages and a dashed reference line (budget or goal).
 *
 * Coverage is drawn, never hidden: covered days are solid; backfilled days are lighter; partial days sit on a hatched
 * column (visible even when nearly empty) with an outlined, hatched bar; days still in progress are translucent; days
 * with no data at all get a faint hatched column, so they can't be mistaken for a zero-use day (which simply has no
 * bar). Spike and good days carry a dot above the bar. Tapping a day selects it.
 */
@Composable
fun TrendChart(
    points: List<TrendPoint>,
    unit: MetricUnit,
    diverging: Boolean,
    showMa7: Boolean,
    showMa28: Boolean,
    referenceLabel: String?,
    selected: LocalDate?,
    onSelect: (LocalDate?) -> Unit,
    description: String,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp,
    colors: TrendColors = rememberTrendColors(),
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = colors.label, fontSize = 10.sp)
    val refStyle = labelStyle.copy(color = colors.reference)
    val geometry = remember(points, diverging, showMa7, showMa28) { ChartScale.of(points, diverging, showMa7, showMa28, unit) }
    // The tap handler outlives recompositions (keyed on the data only), so it reads the latest selection.
    val currentSelected by rememberUpdatedState(selected)
    val currentOnSelect by rememberUpdatedState(onSelect)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description }
            .pointerInput(points) {
                detectTapGestures { offset ->
                    val axis = AXIS_WIDTH.toPx()
                    if (points.isEmpty() || offset.x < axis) return@detectTapGestures currentOnSelect(null)
                    val slot = (size.width - axis) / points.size
                    val i = ((offset.x - axis) / slot).toInt().coerceIn(0, points.lastIndex)
                    val date = points[i].date
                    currentOnSelect(if (date == currentSelected) null else date)
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val axis = AXIS_WIDTH.toPx()
        val bottomPad = 18.dp.toPx()
        val topPad = 8.dp.toPx()
        val plot = Rect(axis, topPad, size.width, size.height - bottomPad)
        val g = geometry
        fun y(v: Double): Float = (plot.bottom - ((v - g.min) / (g.max - g.min)) * plot.height).toFloat()
        val zeroY = y(0.0)

        // Grid and axis labels.
        for (tick in g.ticks) {
            val ty = y(tick)
            drawLine(colors.grid, Offset(plot.left, ty), Offset(plot.right, ty), strokeWidth = 1f)
            drawLabel(measurer, formatAxis(tick, unit), Offset(0f, ty), labelStyle, alignRight = axis - 6.dp.toPx())
        }

        val slot = plot.width / points.size
        val barW = (slot * if (points.size > 120) 0.9f else 0.68f).coerceIn(1f, MAX_BAR_WIDTH.toPx())
        val radius = CornerRadius(minOf(barW / 2, 6.dp.toPx()))
        selected?.let { d ->
            val i = points.indexOfFirst { it.date == d }
            if (i >= 0) drawRect(colors.highlight, Offset(plot.left + slot * i, plot.top), Size(slot, plot.height))
        }

        // Days with no data: one faint hatched block per contiguous run (a year of gaps is a few rects, not 365).
        var gapStart = -1
        fun flushGap(end: Int) {
            if (gapStart < 0) return
            val l = plot.left + slot * gapStart + (slot - barW) / 2
            val r = plot.left + slot * (end - 1) + (slot + barW) / 2
            hatch(Rect(l, plot.top, r, plot.bottom), colors.muted.copy(alpha = 0.18f), spacing = 6.dp.toPx())
            gapStart = -1
        }
        points.forEachIndexed { i, p ->
            val gap = p.sample.quality == DayQuality.NO_DATA || p.sample.value == null
            if (gap && gapStart < 0) gapStart = i
            if (!gap) flushGap(i)
        }
        flushGap(points.size)

        val markerR = minOf(barW / 2, 3.dp.toPx()).coerceAtLeast(1.5.dp.toPx())
        points.forEachIndexed { i, p ->
            val s = p.sample
            val value = s.value ?: return@forEachIndexed
            if (s.quality == DayQuality.NO_DATA) return@forEachIndexed
            val cx = plot.left + slot * i + slot / 2
            val left = cx - barW / 2
            val partial = s.quality == DayQuality.PARTIAL
            if (partial) {
                // A partial day stays visible even when what was observed rounds to nothing.
                hatch(Rect(left, plot.top, left + barW, plot.bottom), colors.muted.copy(alpha = 0.22f), spacing = 4.dp.toPx())
            }
            val fill = when (s.quality) {
                DayQuality.COVERED -> colors.bar
                DayQuality.BACKFILL -> colors.bar.copy(alpha = 0.55f)
                DayQuality.PARTIAL -> colors.muted.copy(alpha = 0.35f)
                DayQuality.IN_PROGRESS -> colors.bar.copy(alpha = 0.38f)
                DayQuality.NO_DATA -> colors.muted
            }
            val top = y(value)
            if (abs(zeroY - top) >= 0.5f) {
                val r = Rect(left, minOf(top, zeroY), left + barW, maxOf(top, zeroY))
                drawRoundRect(fill, r.topLeft, r.size, radius)
                if (partial) {
                    hatch(r, colors.muted.copy(alpha = 0.7f), spacing = 4.dp.toPx())
                    drawRoundRect(colors.muted, r.topLeft, r.size, radius, style = Stroke(1.dp.toPx()))
                }
            }
            val second = s.secondary
            if (second != null && second > 0) {
                if (diverging) {
                    val by = y(-second)
                    val color = if (s.quality.countsAsCovered) colors.negative else colors.negative.copy(alpha = 0.4f)
                    val r = Rect(left, zeroY, left + barW, by)
                    drawRoundRect(color, r.topLeft, r.size, radius)
                    if (partial) {
                        hatch(r, colors.muted.copy(alpha = 0.7f), spacing = 4.dp.toPx())
                        drawRoundRect(colors.muted, r.topLeft, r.size, radius, style = Stroke(1.dp.toPx()))
                    }
                } else {
                    val w = barW * 0.45f
                    val sy = y(second)
                    drawRoundRect(colors.secondary.copy(alpha = if (s.quality.countsAsCovered) 0.9f else 0.45f), Offset(cx - w / 2, sy), Size(w, zeroY - sy), CornerRadius(w / 2))
                }
            }
            // Spike and good days (whole-phone baselines) get a dot above the bar.
            val mark = when (s.dayStatus) {
                DayStatus.SPIKE -> colors.spike
                DayStatus.GOOD_DAY -> colors.good
                else -> null
            }
            if (mark != null) {
                val barTop = minOf(top, zeroY)
                drawCircle(mark, markerR, Offset(cx, (barTop - markerR - 2.dp.toPx()).coerceAtLeast(plot.top + markerR)))
            }
        }
        if (diverging) drawLine(colors.label.copy(alpha = 0.5f), Offset(plot.left, zeroY), Offset(plot.right, zeroY), strokeWidth = 1.dp.toPx())

        // Reference line (budget / goal): dashed, one stroke per run of equal values so the dash pattern is unbroken.
        val refs = points.mapNotNull { it.reference }
        if (refs.isNotEmpty()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
            var runStart = -1
            var runValue = 0.0
            fun flushRef(end: Int) {
                if (runStart < 0) return
                val ry = y(runValue)
                drawLine(colors.reference, Offset(plot.left + slot * runStart, ry), Offset(plot.left + slot * end, ry), strokeWidth = 1.5.dp.toPx(), pathEffect = dash)
                runStart = -1
            }
            points.forEachIndexed { i, p ->
                val r = p.reference
                if (r == null || (runStart >= 0 && abs(y(r) - y(runValue)) >= 0.5f)) flushRef(i)
                if (r != null && runStart < 0) {
                    runStart = i
                    runValue = r
                }
            }
            flushRef(points.size)
            if (referenceLabel != null) {
                val ry = y(refs.last())
                val layout = measurer.measure(referenceLabel, refStyle)
                drawText(layout, topLeft = Offset(plot.right - layout.size.width, (ry - layout.size.height - 2.dp.toPx()).coerceAtLeast(0f)))
            }
        }

        if (showMa28) smoothLine(points.map { it.ma28 }, plot, slot, ::y, colors.ma28, 2.dp.toPx())
        if (showMa7) smoothLine(points.map { it.ma7 }, plot, slot, ::y, colors.ma7, 2.5.dp.toPx())

        // X labels: first, middle and last day.
        val idx = listOf(0, points.size / 2, points.lastIndex).distinct()
        val fmt = if (points.size > 200) axisMonth else axisDate
        idx.forEachIndexed { k, i ->
            val text = fmt.format(points[i].date)
            val layout = measurer.measure(text, labelStyle)
            val cx = plot.left + slot * i + slot / 2
            val x = when (k) {
                0 -> plot.left
                idx.lastIndex -> plot.right - layout.size.width
                else -> cx - layout.size.width / 2
            }
            drawText(layout, topLeft = Offset(x, plot.bottom + 4.dp.toPx()))
        }
    }
}

/**
 * 24 bars along the logical day (the rollover hour first). The busiest hour is drawn in the accent colour.
 */
@Composable
fun HourProfileChart(bins: List<HourBin>, peakSlot: Int?, description: String, modifier: Modifier = Modifier, height: Dp = 150.dp) {
    val measurer = rememberTextMeasurer()
    val scheme = MaterialTheme.colorScheme
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = scheme.onSurfaceVariant, fontSize = 10.sp)
    val bar = scheme.primary.copy(alpha = 0.55f)
    val peak = scheme.tertiary
    val grid = scheme.outlineVariant.copy(alpha = 0.6f)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .semantics { contentDescription = description },
    ) {
        if (bins.isEmpty()) return@Canvas
        val bottomPad = 18.dp.toPx()
        val plot = Rect(0f, 4.dp.toPx(), size.width, size.height - bottomPad)
        val max = bins.maxOf { it.avgMs }.takeIf { it > 0 } ?: 1.0
        val slot = plot.width / bins.size
        val w = slot * 0.66f
        drawLine(grid, Offset(plot.left, plot.bottom), Offset(plot.right, plot.bottom), strokeWidth = 1f)
        bins.forEachIndexed { i, b ->
            val h = (b.avgMs / max * plot.height).toFloat()
            if (h > 0.5f) {
                drawRoundRect(
                    if (b.slot == peakSlot) peak else bar,
                    Offset(plot.left + slot * i + (slot - w) / 2, plot.bottom - h),
                    Size(w, h),
                    CornerRadius(minOf(w / 2, 4.dp.toPx())),
                )
            }
            if (i % 6 == 0) {
                val layout = measurer.measure(String.format(Locale.US, "%02d", b.wallHour), labelStyle)
                val x = (plot.left + slot * i + (slot - layout.size.width) / 2).coerceIn(plot.left, plot.right - layout.size.width)
                drawText(layout, topLeft = Offset(x, plot.bottom + 4.dp.toPx()))
            }
        }
    }
}

private val AXIS_WIDTH = 40.dp
private val MAX_BAR_WIDTH = 40.dp

/** The y range and grid ticks of a trend chart. */
internal data class ChartScale(val min: Double, val max: Double, val ticks: List<Double>) {
    companion object {
        fun of(points: List<TrendPoint>, diverging: Boolean, ma7: Boolean, ma28: Boolean, unit: MetricUnit): ChartScale {
            val highs = points.flatMap { p ->
                listOfNotNull(
                    p.sample.value,
                    if (diverging) null else p.sample.secondary,
                    if (ma7) p.ma7 else null,
                    if (ma28) p.ma28 else null,
                    p.reference,
                )
            }
            val lows = points.flatMap { p ->
                listOfNotNull(
                    if (diverging) p.sample.secondary?.let { -it } else null,
                    if (ma7) p.ma7 else null,
                    if (ma28) p.ma28 else null,
                )
            }
            val hi = (highs.maxOrNull() ?: 0.0).coerceAtLeast(0.0)
            val lo = (lows.minOrNull() ?: 0.0).coerceAtMost(0.0)
            val step = niceStep(maxOf(hi, -lo).coerceAtLeast(if (unit == MetricUnit.DURATION) 60_000.0 else 1.0), unit)
            val top = (kotlin.math.ceil(hi / step) * step).coerceAtLeast(step)
            val bottom = kotlin.math.floor(lo / step) * step
            val ticks = generateSequence(bottom) { it + step }.takeWhile { it <= top + step / 2 }.toList()
            return ChartScale(bottom, top, ticks)
        }

        /** About three grid steps across [span], on round minutes/hours or round counts. */
        fun niceStep(span: Double, unit: MetricUnit): Double {
            val target = span / 3
            return if (unit == MetricUnit.DURATION) {
                val minutes = listOf(1, 2, 5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440)
                (minutes.firstOrNull { it * 60_000.0 >= target } ?: 1440) * 60_000.0
            } else {
                val steps = listOf(1, 2, 5, 10, 20, 25, 50, 100, 200, 250, 500, 1000)
                (steps.firstOrNull { it >= target } ?: 1000).toDouble()
            }
        }
    }
}

private fun formatAxis(v: Double, unit: MetricUnit): String = when (unit) {
    MetricUnit.COUNT -> String.format(Locale.US, "%.0f", v)
    MetricUnit.DURATION -> {
        val minutes = Math.round(abs(v) / 60_000.0)
        val sign = if (v < 0) "−" else ""
        when {
            minutes == 0L -> "0"
            minutes % 60 == 0L -> "$sign${minutes / 60}h"
            minutes > 60 -> "$sign${minutes / 60}h${minutes % 60}"
            else -> "$sign${minutes}m"
        }
    }
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, at: Offset, style: TextStyle, alignRight: Float) {
    val layout = measurer.measure(text, style)
    drawText(layout, topLeft = Offset((alignRight - layout.size.width).coerceAtLeast(0f), (at.y - layout.size.height / 2f).coerceAtLeast(0f)))
}

/** Diagonal hatching clipped to [r]. */
private fun DrawScope.hatch(r: Rect, color: Color, spacing: Float) {
    clipRect(r.left, r.top, r.right, r.bottom) {
        var x = r.left - r.height
        while (x < r.right) {
            drawLine(color, Offset(x, r.bottom), Offset(x + r.height, r.top), strokeWidth = 1f)
            x += spacing
        }
    }
}

/** A smooth (monotone-ish cubic) line through the non-null values; gaps break the line. */
private fun DrawScope.smoothLine(values: List<Double?>, plot: Rect, slot: Float, y: (Double) -> Float, color: Color, width: Float) {
    var run = ArrayList<Offset>()
    fun flush() {
        if (run.size >= 2) {
            val path = Path().apply {
                moveTo(run[0].x, run[0].y)
                for (k in 1 until run.size) {
                    val p0 = run[k - 1]
                    val p1 = run[k]
                    val mx = (p0.x + p1.x) / 2
                    cubicTo(mx, p0.y, mx, p1.y, p1.x, p1.y)
                }
            }
            drawPath(path, color, style = Stroke(width = width, cap = StrokeCap.Round))
        } else if (run.size == 1) {
            drawCircle(color, width, run[0])
        }
        run = ArrayList()
    }
    values.forEachIndexed { i, v ->
        if (v == null) flush() else run += Offset(plot.left + slot * i + slot / 2, y(v))
    }
    flush()
}
