package com.celestiallemon.lagrange.ui.friction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.CatalogApp
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.domain.friction.AppFrictionOverride
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import com.celestiallemon.lagrange.domain.friction.FrictionSettings
import com.celestiallemon.lagrange.ui.components.AppIcon
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.EditSheet
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.SwitchRow
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import java.util.Locale

private val dayLabels = listOf(
    "Mon" to FrictionSchedule.MASK_MON,
    "Tue" to FrictionSchedule.MASK_TUE,
    "Wed" to FrictionSchedule.MASK_WED,
    "Thu" to FrictionSchedule.MASK_THU,
    "Fri" to FrictionSchedule.MASK_FRI,
    "Sat" to FrictionSchedule.MASK_SAT,
    "Sun" to FrictionSchedule.MASK_SUN,
)

internal fun formatMinuteOfDay(minute: Int): String {
    val m = minute.coerceIn(0, 1439)
    return String.format(Locale.US, "%02d:%02d", m / 60, m % 60)
}

internal fun formatDaysMask(mask: Int): String = when (mask) {
    FrictionSchedule.MASK_EVERY_DAY -> "Every day"
    FrictionSchedule.MASK_WEEKDAYS -> "Mon–Fri"
    FrictionSchedule.MASK_SAT or FrictionSchedule.MASK_SUN -> "Weekends"
    else -> dayLabels.filter { (mask and it.second) != 0 }.joinToString(", ") { it.first }.ifEmpty { "No days" }
}

@Composable
private fun FieldLabel(text: String) = Text(text, style = MaterialTheme.typography.titleSmall)

/** Create or edit a schedule (windows may wrap past midnight, like 22:30–04:00). Delete lives here too. */
@Composable
fun ScheduleEditorDialog(
    initial: FrictionScheduleEntity?,
    onDismiss: () -> Unit,
    onSave: (FrictionScheduleEntity) -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var daysMask by rememberSaveable { mutableIntStateOf(initial?.daysOfWeekMask ?: FrictionSchedule.MASK_WEEKDAYS) }
    var startMin by rememberSaveable { mutableIntStateOf(initial?.startMinuteOfDay ?: (9 * 60)) }
    var endMin by rememberSaveable { mutableIntStateOf(initial?.endMinuteOfDay ?: (17 * 60 + 30)) }
    var boost by rememberSaveable { mutableIntStateOf(initial?.escalationBoost ?: 1) }
    var requireTimer by rememberSaveable { mutableStateOf(initial?.requireTimerPrompt ?: false) }
    var nudgeOverride by rememberSaveable { mutableIntStateOf(initial?.nudgeIntervalMin ?: 0) }
    val wraps = startMin > endMin

    EditSheet(
        title = if (initial == null) "New schedule" else "Edit schedule",
        supporting = "Firmer rules during part of the day.",
        onDismiss = onDismiss,
        confirmEnabled = name.isNotBlank() && daysMask != 0,
        onConfirm = {
            onSave(
                FrictionScheduleEntity(
                    id = initial?.id ?: 0L,
                    name = name.trim(),
                    enabled = initial?.enabled ?: true,
                    daysOfWeekMask = daysMask,
                    startMinuteOfDay = startMin,
                    endMinuteOfDay = endMin,
                    escalationBoost = boost,
                    requireTimerPrompt = requireTimer,
                    nudgeIntervalMin = nudgeOverride.takeIf { it > 0 },
                ),
            )
        },
    ) {
        OutlinedTextField(
            value = name,
            onValueChange = { name = it.take(40) },
            label = { Text("Name") },
            placeholder = { Text("Work hours, Wind-down…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FieldLabel("Days")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                dayLabels.forEach { (label, bit) ->
                    val selected = (daysMask and bit) != 0
                    FilterChip(selected = selected, onClick = { daysMask = if (selected) daysMask and bit.inv() else daysMask or bit }, label = { Text(label) })
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            TimeStepperRow("Starts", startMin) { startMin = (startMin + it + 1440) % 1440 }
            TimeStepperRow("Ends", endMin) { endMin = (endMin + it + 1440) % 1440 }
            when {
                startMin == endMin -> Tag("All day", tone = Tone.Brand)
                wraps -> Tag("Runs past midnight", tone = Tone.Brand)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FieldLabel("Pauses during it")
            Choice(listOf(0, 1, 2), boost, { boost = it }, { if (it == 0) "As usual" else "+$it tier" + if (it > 1) "s" else "" })
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FieldLabel("Time nudges")
            Choice(listOf(0, 5, 10, 15, 20), nudgeOverride, { nudgeOverride = it }, { if (it == 0) "Usual" else "${it}m" })
        }
        RowGroup {
            SwitchRow("Always offer a timer", checked = requireTimer, onCheckedChange = { requireTimer = it }, supporting = "Even on the first, shortest pause")
        }
        if (initial != null && onDelete != null) {
            TextButton(onClick = onDelete) { Text("Delete schedule", color = MaterialTheme.colorScheme.error) }
        }
    }
}

/** A time of day with 15-minute steps either side. */
@Composable
internal fun TimeStepperRow(label: String, minuteOfDay: Int, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            FilledTonalIconButton(onClick = { onStep(-15) }) { Icon(Icons.Outlined.Remove, "15 minutes earlier") }
            Text(formatMinuteOfDay(minuteOfDay), style = NumberStyles.stat)
            FilledTonalIconButton(onClick = { onStep(15) }) { Icon(Icons.Outlined.Add, "15 minutes later") }
        }
    }
}

/** The four pause lengths, one per tier. */
@Composable
fun TierPausesDialog(
    tier0: Int,
    tier1: Int,
    tier2: Int,
    tier3: Int,
    onDismiss: () -> Unit,
    onSave: (Int, Int, Int, Int) -> Unit,
) {
    var t0 by rememberSaveable { mutableIntStateOf(tier0) }
    var t1 by rememberSaveable { mutableIntStateOf(tier1) }
    var t2 by rememberSaveable { mutableIntStateOf(tier2) }
    var t3 by rememberSaveable { mutableIntStateOf(tier3) }
    val range = FrictionSettings.MIN_PAUSE_SEC..FrictionSettings.MAX_PAUSE_SEC
    EditSheet("Pause lengths", supporting = "In seconds, from the first opens of the day to the heaviest.", onDismiss = onDismiss, onConfirm = { onSave(t0, t1, t2, t3) }) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
            SecStepperRow("First opens", "1–2 opens, under 15m", t0, range) { t0 = it }
            SecStepperRow("Getting frequent", "3–5 opens or 15m+", t1, range) { t1 = it }
            SecStepperRow("Habit forming", "6–10 opens or 45m+", t2, range) { t2 = it }
            SecStepperRow("Heavy day", "11+ opens or 90m+", t3, range) { t3 = it }
        }
        TextButton(onClick = { t0 = 3; t1 = 5; t2 = 8; t3 = 12 }) { Text("Reset to defaults") }
    }
}

@Composable
private fun SecStepperRow(label: String, rule: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(rule, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FilledTonalIconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) { Icon(Icons.Outlined.Remove, "One second less") }
            Text("${value}s", style = NumberStyles.stat)
            FilledTonalIconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) { Icon(Icons.Outlined.Add, "One second more") }
        }
    }
}

/** Picks the "open this instead" app offered on pauses: any installed app that isn't distracting or paused. */
@Composable
fun RedirectAppPickerDialog(
    apps: List<CatalogApp>,
    currentPackage: String?,
    onDismiss: () -> Unit,
    onSelect: (String?) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val candidates = remember(apps, query) {
        val q = query.trim()
        apps.filter { it.category != AppCategory.SELF_APP && it.category != AppCategory.DISTRACTING && !it.isFrictionEligible }
            .filter { q.isEmpty() || it.displayName.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .sortedBy { it.displayName.lowercase() }
    }
    EditSheet("Instead, open…", supporting = "Offered as a better option on every pause: a reader, notes, a language app.", onDismiss = onDismiss, onConfirm = null) {
        OutlinedTextField(value = query, onValueChange = { query = it }, placeholder = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
            item("none") {
                ListRow("Nothing", supporting = "Only Go home", tonal = false, onClick = { onSelect(null) }, trailing = { RadioButton(selected = currentPackage == null, onClick = null) })
            }
            items(candidates, key = { it.packageName }) { app ->
                ListRow(
                    app.displayName,
                    supporting = app.category.label,
                    tonal = false,
                    leading = { AppIcon(app.packageName, app.displayName, categoryColor(app.category)) },
                    onClick = { onSelect(app.packageName) },
                    trailing = { RadioButton(selected = currentPackage == app.packageName, onClick = null) },
                )
            }
        }
    }
}

/** One app's own pause settings, overriding the defaults (stored in `packages.friction_override_json`). */
@Composable
fun AppFrictionOverrideDialog(
    app: CatalogApp,
    onDismiss: () -> Unit,
    onSave: (AppFrictionOverride?) -> Unit,
) {
    val initial = app.frictionOverride
    var mode by rememberSaveable { mutableStateOf(when (initial?.enabled) { true -> "ON"; false -> "OFF"; null -> "DEFAULT" }) }
    var customPause by rememberSaveable { mutableIntStateOf(initial?.fixedPauseSec ?: 0) }
    var timerMode by rememberSaveable { mutableStateOf(when (initial?.requireTimerPrompt) { true -> "ALWAYS"; false -> "NEVER"; null -> "DEFAULT" }) }
    val defaultOn = app.category == AppCategory.DISTRACTING && app.confirmed
    EditSheet(
        app.displayName,
        supporting = "Settings for this app only.",
        onDismiss = onDismiss,
        onConfirm = {
            val override = AppFrictionOverride(
                enabled = when (mode) { "ON" -> true; "OFF" -> false; else -> null },
                fixedPauseSec = customPause.takeIf { it > 0 },
                requireTimerPrompt = when (timerMode) { "ALWAYS" -> true; "NEVER" -> false; else -> null },
            )
            onSave(override.takeUnless { it.isDefault })
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FieldLabel("Launch pause")
            Choice(listOf("DEFAULT", "ON", "OFF"), mode, { mode = it }, { when (it) { "ON" -> "Always"; "OFF" -> "Never"; else -> if (defaultOn) "Default (on)" else "Default (off)" } }, forceChips = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FieldLabel("Length")
            Choice(listOf(0, 3, 5, 8, 12, 20), customPause, { customPause = it }, { if (it == 0) "Grows" else "${it}s" }, forceChips = true)
        }
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            FieldLabel("Timer offer")
            Choice(listOf("DEFAULT", "ALWAYS", "NEVER"), timerMode, { timerMode = it }, { when (it) { "ALWAYS" -> "Always"; "NEVER" -> "Never"; else -> "By tier" } })
        }
    }
}
