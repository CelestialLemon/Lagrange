package com.celestiallemon.lagrange.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.ui.TodayViewModel
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.Space
import java.time.LocalDate

private const val MIN = 60_000L
private val presets = listOf(30 * MIN, 45 * MIN, 60 * MIN, 90 * MIN, 120 * MIN, 180 * MIN)

/** Quick budget editor: presets plus ±5 minute steps. Past days keep the budget that applied then. */
@Composable
fun BudgetDialog(current: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by rememberSaveable { mutableLongStateOf(current) }
    val range = UserPrefs.BUDGET_RANGE_MS
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Daily distracting budget") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    FilledTonalIconButton(onClick = { value = (value - 5 * MIN).coerceIn(range) }, enabled = value > range.first) {
                        Icon(Icons.Outlined.Remove, "Five minutes less")
                    }
                    Text(formatDurationShort(value), style = MaterialTheme.typography.displaySmall)
                    FilledTonalIconButton(onClick = { value = (value + 5 * MIN).coerceIn(range) }, enabled = value < range.last) {
                        Icon(Icons.Outlined.Add, "Five minutes more")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    presets.forEach { p -> FilterChip(selected = value == p, onClick = { value = p }, label = { Text(formatDurationShort(p)) }) }
                }
                Text(
                    "Applies from today. Earlier days keep the budget you had then, so history stays honest.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Day note: one line of context (travel, sick, deadline) shown next to the day in Trends. */
@Composable
fun NoteDialog(initial: String, date: LocalDate, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note for ${formatDay(date)}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(TodayViewModel.NOTE_MAX_CHARS) },
                    placeholder = { Text("Travel day, sick, launch deadline…") },
                    minLines = 2,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf("Travel", "Sick", "Deadline", "Day off", "Bad sleep").forEach { tag ->
                        FilterChip(selected = false, onClick = { text = if (text.isBlank()) tag else "$text · $tag" }, label = { Text(tag) })
                    }
                }
                Text("${text.length}/${TodayViewModel.NOTE_MAX_CHARS}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text(if (text.isBlank() && initial.isNotBlank()) "Remove" else "Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
