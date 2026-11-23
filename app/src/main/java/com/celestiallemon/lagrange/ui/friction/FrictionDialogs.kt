package com.celestiallemon.lagrange.ui.friction

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.CatalogApp
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.domain.friction.AppFrictionOverride
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import com.celestiallemon.lagrange.domain.friction.FrictionSettings
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.theme.Space
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

/** Create or edit a contextual schedule (supports midnight-wrapping windows like 22:30–04:00). */
@Composable
fun ScheduleEditorDialog(
    initial: FrictionScheduleEntity?,
    onDismiss: () -> Unit,
    onSave: (FrictionScheduleEntity) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name ?: "") }
    var daysMask by rememberSaveable { mutableIntStateOf(initial?.daysOfWeekMask ?: FrictionSchedule.MASK_WEEKDAYS) }
    var startMin by rememberSaveable { mutableIntStateOf(initial?.startMinuteOfDay ?: (9 * 60)) }
    var endMin by rememberSaveable { mutableIntStateOf(initial?.endMinuteOfDay ?: (17 * 60 + 30)) }
    var boost by rememberSaveable { mutableIntStateOf(initial?.escalationBoost ?: 1) }
    var requireTimer by rememberSaveable { mutableStateOf(initial?.requireTimerPrompt ?: false) }
    var nudgeOverride by rememberSaveable { mutableIntStateOf(initial?.nudgeIntervalMin ?: 0) }

    val wraps = startMin > endMin
    val canSave = name.isNotBlank() && daysMask != 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "New contextual schedule" else "Edit schedule") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Name") },
                    placeholder = { Text("Work Hours, Wind-Down…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        dayLabels.forEach { (label, bit) ->
                            val selected = (daysMask and bit) != 0
                            FilterChip(
                                selected = selected,
                                onClick = { daysMask = if (selected) daysMask and bit.inv() else daysMask or bit },
                                label = { Text(label) },
                            )
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    TimeStepperRow("Starts", startMin) { startMin = (startMin + it + 1440) % 1440 }
                    TimeStepperRow("Ends", endMin) { endMin = (endMin + it + 1440) % 1440 }
                    if (wraps) {
                        StatusPill("Wraps past midnight", MaterialTheme.colorScheme.primary)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Escalation boost", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        listOf(0 to "None", 1 to "+1 tier", 2 to "+2 tiers").forEach { (b, label) ->
                            FilterChip(selected = boost == b, onClick = { boost = b }, label = { Text(label) })
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Encourage soft timer", style = MaterialTheme.typography.titleSmall)
                        Text("Show session timer chips even on Tier 0.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = requireTimer, onCheckedChange = { requireTimer = it })
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Nudge interval override", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        listOf(0 to "Default", 5 to "5m", 10 to "10m", 15 to "15m", 20 to "20m").forEach { (m, label) ->
                            FilterChip(selected = nudgeOverride == m, onClick = { nudgeOverride = m }, label = { Text(label) })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = canSave,
                onClick = {
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
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TimeStepperRow(label: String, minuteOfDay: Int, onStep: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            FilledTonalIconButton(onClick = { onStep(-15) }) { Icon(Icons.Outlined.Remove, "15 minutes earlier") }
            Text(formatMinuteOfDay(minuteOfDay), style = MaterialTheme.typography.titleLarge)
            FilledTonalIconButton(onClick = { onStep(15) }) { Icon(Icons.Outlined.Add, "15 minutes later") }
        }
    }
}

/** Customizes the 4 escalation tier pause durations in seconds. */
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escalation pause durations") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                SecStepperRow("Tier 0 (1–2 opens, <15m)", t0, range) { t0 = it }
                SecStepperRow("Tier 1 (3–5 opens or 15m+)", t1, range) { t1 = it }
                SecStepperRow("Tier 2 (6–10 opens or 45m+)", t2, range) { t2 = it }
                SecStepperRow("Tier 3 (11+ opens or 90m+)", t3, range) { t3 = it }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(t0, t1, t2, t3) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { t0 = 3; t1 = 5; t2 = 8; t3 = 12 }) { Text("Defaults") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun SecStepperRow(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
            FilledTonalIconButton(onClick = { onChange((value - 1).coerceIn(range)) }, enabled = value > range.first) {
                Icon(Icons.Outlined.Remove, "One second less")
            }
            Text("${value}s", style = MaterialTheme.typography.titleMedium)
            FilledTonalIconButton(onClick = { onChange((value + 1).coerceIn(range)) }, enabled = value < range.last) {
                Icon(Icons.Outlined.Add, "One second more")
            }
        }
    }
}

/** Picks an installed non-distracting app as the optional Healthy Redirect target on launch pauses. */
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
        apps.filter { it.category != AppCategory.SELF_APP && it.category != AppCategory.DISTRACTING }
            .filter { q.isEmpty() || it.displayName.contains(q, ignoreCase = true) || it.packageName.contains(q, ignoreCase = true) }
            .sortedBy { it.displayName.lowercase() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Healthy redirect app") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(
                    "Shown as a 1-tap alternative on launch pauses (e.g., Kindle, Notes, Duolingo).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text("Filter apps…") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp)) {
                    item("none") {
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(null) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = currentPackage == null, onClick = { onSelect(null) })
                            Text("None (Go Home only)", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    items(candidates, key = { it.packageName }) { app ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onSelect(app.packageName) }.padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = currentPackage == app.packageName, onClick = { onSelect(app.packageName) })
                            Column(Modifier.weight(1f)) {
                                Text(app.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(app.category.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** Per-app friction override editor stored in `packages.friction_override_json`. */
@Composable
fun AppFrictionOverrideDialog(
    app: CatalogApp,
    onDismiss: () -> Unit,
    onSave: (AppFrictionOverride?) -> Unit,
) {
    val initial = app.frictionOverride
    var mode by rememberSaveable {
        mutableStateOf(
            when (initial?.enabled) {
                true -> "ON"
                false -> "OFF"
                null -> "DEFAULT"
            },
        )
    }
    var customPause by rememberSaveable { mutableIntStateOf(initial?.fixedPauseSec ?: 0) }
    var timerMode by rememberSaveable {
        mutableStateOf(
            when (initial?.requireTimerPrompt) {
                true -> "ALWAYS"
                false -> "NEVER"
                null -> "DEFAULT"
            },
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${app.displayName} friction") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Launch pause for this app", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        val defaultLabel = if (app.category == AppCategory.DISTRACTING) "Default (On)" else "Default (Off)"
                        FilterChip(selected = mode == "DEFAULT", onClick = { mode = "DEFAULT" }, label = { Text(defaultLabel) })
                        FilterChip(selected = mode == "ON", onClick = { mode = "ON" }, label = { Text("Always on") })
                        FilterChip(selected = mode == "OFF", onClick = { mode = "OFF" }, label = { Text("Off") })
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Pause duration", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        listOf(0 to "Escalating", 3 to "3s", 5 to "5s", 8 to "8s", 12 to "12s", 20 to "20s").forEach { (sec, label) ->
                            FilterChip(selected = customPause == sec, onClick = { customPause = sec }, label = { Text(label) })
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    Text("Soft timer prompt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                        FilterChip(selected = timerMode == "DEFAULT", onClick = { timerMode = "DEFAULT" }, label = { Text("By tier") })
                        FilterChip(selected = timerMode == "ALWAYS", onClick = { timerMode = "ALWAYS" }, label = { Text("Always show") })
                        FilterChip(selected = timerMode == "NEVER", onClick = { timerMode = "NEVER" }, label = { Text("Hide") })
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val override = AppFrictionOverride(
                        enabled = when (mode) {
                            "ON" -> true
                            "OFF" -> false
                            else -> null
                        },
                        fixedPauseSec = customPause.takeIf { it > 0 },
                        requireTimerPrompt = when (timerMode) {
                            "ALWAYS" -> true
                            "NEVER" -> false
                            else -> null
                        },
                    )
                    onSave(override.takeUnless { it.isDefault })
                },
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
