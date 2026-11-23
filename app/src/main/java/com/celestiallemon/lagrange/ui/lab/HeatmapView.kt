package com.celestiallemon.lagrange.ui.lab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.domain.lab.CircadianHeatmap
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.formatHour
import com.celestiallemon.lagrange.ui.theme.Space
import java.time.DayOfWeek
import java.time.format.TextStyle
import java.util.Locale

private val ROW_HEIGHT = 22.dp

enum class HeatMetric(val label: String) { DISTRACTING("Distracting time"), PICKUPS("Pickups") }

private fun dayName(row: Int, style: TextStyle = TextStyle.SHORT): String = DayOfWeek.of(row + 1).getDisplayName(style, Locale.getDefault())

private fun cellText(map: CircadianHeatmap, metric: HeatMetric, row: Int, slot: Int): String {
    val cell = map.cells[row][slot]
    val days = map.daysPerRow[row]
    val h = map.wallHour(slot)
    val hours = "${formatHour(h)}–${formatHour((h + 1) % 24)}"
    if (days == 0) return "${dayName(row, TextStyle.FULL)} $hours: no covered ${dayName(row, TextStyle.FULL)}s yet"
    val value = when (metric) {
        HeatMetric.DISTRACTING -> "${formatDuration(cell.distractingMs)} distracting"
        HeatMetric.PICKUPS -> "${"%.1f".format(Locale.getDefault(), cell.pickups)} pickups"
    }
    return "${dayName(row, TextStyle.FULL)} $hours: $value on average ($days covered ${if (days == 1) "day" else "days"})"
}

/** Spoken summary for TalkBack: the busiest hours, since a 168-cell grid can't be read cell by cell. */
private fun summary(map: CircadianHeatmap, metric: HeatMetric): String {
    val cells = (0 until 7).flatMap { r -> (0 until 24).map { s -> Triple(r, s, map.cells[r][s]) } }.filter { map.daysPerRow[it.first] > 0 }
    val top = cells.sortedByDescending { if (metric == HeatMetric.DISTRACTING) it.third.distractingMs.toDouble() else it.third.pickups }
        .filter { if (metric == HeatMetric.DISTRACTING) it.third.distractingMs > 0 else it.third.pickups > 0 }
        .take(3)
    if (top.isEmpty()) return "Heatmap of ${metric.label.lowercase()} by weekday and hour: nothing recorded yet."
    return "Heatmap of ${metric.label.lowercase()} by weekday and hour. Busiest: " + top.joinToString("; ") { cellText(map, metric, it.first, it.second) } +
        ". Tap a cell to inspect it."
}

/** Mon–Sun × the 24 hours of the logical day (starting at the rollover hour); tap a cell to read it. */
@Composable
fun CircadianHeatmapView(map: CircadianHeatmap, metric: HeatMetric, modifier: Modifier = Modifier) {
    var selected by remember(map, metric) { mutableStateOf<Pair<Int, Int>?>(null) }
    val base = if (metric == HeatMetric.DISTRACTING) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val empty = MaterialTheme.colorScheme.surfaceContainerHighest
    val outline = MaterialTheme.colorScheme.onSurface
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val max = if (metric == HeatMetric.DISTRACTING) map.maxDistractingMs.toDouble() else map.maxPickups
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row {
            Column(Modifier.width(36.dp)) {
                (0 until 7).forEach { r ->
                    Box(Modifier.height(ROW_HEIGHT), contentAlignment = Alignment.CenterStart) {
                        Text(dayName(r).take(3), style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
                    }
                }
            }
            Canvas(
                Modifier
                    .weight(1f)
                    .height(ROW_HEIGHT * 7)
                    .semantics { contentDescription = summary(map, metric) }
                    .pointerInput(map, metric) {
                        detectTapGestures { p ->
                            val col = (p.x / size.width * 24).toInt().coerceIn(0, 23)
                            val row = (p.y / size.height * 7).toInt().coerceIn(0, 6)
                            selected = if (selected == row to col) null else row to col
                        }
                    },
            ) {
                val cw = size.width / 24f
                val ch = size.height / 7f
                val gap = 1.5.dp.toPx()
                for (r in 0 until 7) for (s in 0 until 24) {
                    val cell = map.cells[r][s]
                    val v = if (metric == HeatMetric.DISTRACTING) cell.distractingMs.toDouble() else cell.pickups
                    val t = if (max <= 0.0 || map.daysPerRow[r] == 0) 0.0 else v / max
                    val color = if (t <= 0.0) empty else base.copy(alpha = (0.12 + 0.88 * t).toFloat())
                    drawRoundRect(color, Offset(s * cw + gap / 2, r * ch + gap / 2), Size(cw - gap, ch - gap), CornerRadius(3.dp.toPx()))
                }
                selected?.let { (r, s) ->
                    drawRoundRect(outline, Offset(s * cw, r * ch), Size(cw, ch), CornerRadius(3.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp)) {
            listOf(0, 6, 12, 18).forEach { s ->
                Text(formatHour(map.wallHour(s)), style = MaterialTheme.typography.labelSmall, color = labelColor, modifier = Modifier.weight(1f), textAlign = TextAlign.Start)
            }
        }
        val sel = selected
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
            Text(
                if (sel == null) "Tap a cell to see that hour." else cellText(map, metric, sel.first, sel.second),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .padding(Space.m)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}
