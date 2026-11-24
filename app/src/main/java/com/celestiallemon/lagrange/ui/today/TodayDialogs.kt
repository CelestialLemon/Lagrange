package com.celestiallemon.lagrange.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.ui.TodayViewModel
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.EditSheet
import com.celestiallemon.lagrange.ui.components.Stepper
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.Spacing
import java.time.LocalDate

private const val MIN = 60_000L
private val presets = listOf(30 * MIN, 45 * MIN, 60 * MIN, 90 * MIN, 120 * MIN, 180 * MIN)

/** Daily distracting budget: presets plus ±5 minute steps. Past days keep the budget that applied then. */
@Composable
fun BudgetSheet(current: Long, onDismiss: () -> Unit, onSave: (Long) -> Unit) {
    var value by rememberSaveable { mutableLongStateOf(current) }
    var increasing by rememberSaveable { mutableStateOf(true) }
    val range = UserPrefs.BUDGET_RANGE_MS
    EditSheet(
        title = "Daily distracting budget",
        supporting = "Applies from today. Earlier days keep the budget they had, so history stays honest.",
        onDismiss = onDismiss,
        onConfirm = { onSave(value) },
    ) {
        Stepper(
            value = formatDurationShort(value),
            onMinus = { increasing = false; value = (value - 5 * MIN).coerceIn(range) },
            onPlus = { increasing = true; value = (value + 5 * MIN).coerceIn(range) },
            minusEnabled = value > range.first,
            plusEnabled = value < range.last,
            minusDescription = "Five minutes less",
            plusDescription = "Five minutes more",
            increasing = increasing,
        )
        Choice(presets, value.takeIf { it in presets }, { increasing = it > value; value = it }, ::formatDurationShort, forceChips = true)
    }
}

/** Day note: one line of context (travel, sick, deadline) shown next to the day in Trends and the Lab. */
@Composable
fun NoteSheet(initial: String, date: LocalDate, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    EditSheet(
        title = "Note for ${formatDay(date)}",
        supporting = "A line of context makes unusual days easier to read later.",
        onDismiss = onDismiss,
        onConfirm = { onSave(text) },
        confirmLabel = if (text.isBlank() && initial.isNotBlank()) "Remove" else "Save",
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(TodayViewModel.NOTE_MAX_CHARS) },
            placeholder = { Text("Travel day, sick, launch deadline…") },
            supportingText = { Text("${text.length}/${TodayViewModel.NOTE_MAX_CHARS}") },
            minLines = 2,
            maxLines = 4,
            modifier = Modifier.fillMaxWidth(),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            listOf("Travel", "Sick", "Deadline", "Day off", "Bad sleep").forEach { tag ->
                AssistChip(
                    onClick = { text = (if (text.isBlank()) tag else "$text · $tag").take(TodayViewModel.NOTE_MAX_CHARS) },
                    label = { Text(tag, style = MaterialTheme.typography.labelLarge) },
                )
            }
        }
    }
}
