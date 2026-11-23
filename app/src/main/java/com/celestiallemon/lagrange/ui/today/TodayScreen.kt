package com.celestiallemon.lagrange.ui.today

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Nightlight
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.ui.EpisodeUi
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.TodayState
import com.celestiallemon.lagrange.ui.TodayViewModel
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatRow
import com.celestiallemon.lagrange.ui.components.StatTile
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatAgo
import com.celestiallemon.lagrange.ui.formatClock
import com.celestiallemon.lagrange.ui.formatCount
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.friction.label
import com.celestiallemon.lagrange.ui.theme.EyebrowStyle
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import kotlin.math.cos
import kotlin.math.sin

private enum class TimelineFilter(val label: String) { ALL("All"), RABBIT("Rabbit holes"), NOTIFIED("Notification-led"), RATED("Rated") }

@Composable
fun TodayScreen(main: MainViewModel, padding: PaddingValues, onOpenSystem: () -> Unit, today: TodayViewModel = viewModel()) {
    val stats by main.stats.collectAsStateWithLifecycle()
    val health by main.health.collectAsStateWithLifecycle()
    val state by today.state.collectAsStateWithLifecycle()
    val progress by today.progress.collectAsStateWithLifecycle()
    val detail by today.detail.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    val now = System.currentTimeMillis()

    var filter by rememberSaveable { mutableStateOf(TimelineFilter.ALL) }
    var showGlances by rememberSaveable { mutableStateOf(false) }
    var openSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var editingBudget by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }

    val s = state
    val rollup = s?.rollup
    val dayStatus = dayStatusOf(rollup?.dayStatus)

    LagrangeScreen(
        eyebrow = "Today" + (s?.date?.let { " · ${formatDay(it)}" } ?: ""),
        title = headline(s, dayStatus),
        subtitle = subtitle(s, dayStatus),
        contentPadding = padding,
    ) {
        if (progress.running) {
            item("recompute") {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = Space.m, vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Sync, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Spacer(Modifier.width(Space.s))
                        Text(
                            "Rebuilding timeline · ${progress.doneDays}/${progress.totalDays} days",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                }
            }
        }
        if (!health.coreReady) {
            item("setup") {
                SectionCard(
                    title = "Finish setting up",
                    eyebrow = "Action needed",
                    icon = Icons.Outlined.WarningAmber,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        "Usage Access and Notification Access let Lagrange collect. Until both are on, days are marked as partial coverage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    FilledTonalButton(onClick = onOpenSystem) { Text("Open Permission Health") }
                }
            }
        }

        if (s != null) {
            item("budget") { BudgetOrbitCard(s, onEdit = { editingBudget = true }) }
            item("note") { DayNoteChip(s, onEdit = { editingNote = true }) }
            if (rollup != null) {
                item("baseline") { BaselineCard(s, dayStatus) }
                if (dayStatus == DayStatus.SPIKE || dayStatus == DayStatus.GOOD_DAY) {
                    item("highlight") { HighlightCard(s, dayStatus) }
                }
                item("focus") { FocusCard(s, now) }
                item("categories") { CategoryCard(s) }
                item("metrics") { MetricsCard(s) }
            } else {
                item("empty") {
                    SectionCard(title = "Your first timeline is on its way", eyebrow = "Reconstructing", icon = Icons.Outlined.AutoAwesome) {
                        Text(
                            "As soon as a harvest lands, Lagrange turns raw usage events into honest episodes: glances, micro-checks, focused stretches and rabbit holes.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            val visible = s.episodes.filter { ep ->
                (showGlances || !ep.session.isGlanceOnly) && when (filter) {
                    TimelineFilter.ALL -> true
                    TimelineFilter.RABBIT -> ep.session.isRabbitHole
                    TimelineFilter.NOTIFIED -> triggerOf(ep.session.triggerEvidence)?.isNotificationLed == true
                    TimelineFilter.RATED -> ep.session.worthItRating != null
                }
            }.asReversed()
            item("timeline-header") {
                TimelineHeader(
                    total = s.episodes.count { !it.session.isGlanceOnly },
                    glances = s.episodes.count { it.session.isGlanceOnly },
                    filter = filter,
                    onFilter = { filter = it },
                    showGlances = showGlances,
                    onShowGlances = { showGlances = it },
                )
            }
            if (visible.isEmpty()) {
                item("timeline-empty") {
                    Text(
                        if (s.episodes.isEmpty()) "No sessions yet today." else "Nothing matches this filter.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Space.s),
                    )
                }
            }
            items(visible, key = { "ep-${it.id}" }) { ep ->
                EpisodeCard(ep, s.packages, onClick = {
                    openSessionId = ep.id
                    today.loadDetail(ep.id)
                })
            }
        }

        item("collected") {
            SectionCard(title = "Collection health", eyebrow = "Since your day began", icon = Icons.Outlined.RadioButtonChecked) {
                StatRow {
                    StatTile("Usage events", formatCount(stats.rawEventsToday), Modifier.weight(1f))
                    StatTile("Notifications", formatCount(stats.notificationsToday), Modifier.weight(1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    CoverageLine(
                        "Usage harvest",
                        if (stats.lastHarvestAtMs == null) "Waiting for first harvest" else "Last harvest ${formatAgo(stats.lastHarvestAtMs, now)}",
                        if (stats.harvestOverdue(now) || stats.lastHarvestAtMs == null) status.warn else status.good,
                    )
                    CoverageLine(
                        "Notification listener",
                        if (stats.listenerActive) "Recording metadata" else "Not connected",
                        if (stats.listenerActive) status.good else status.warn,
                    )
                }
            }
        }
    }

    val open = openSessionId?.let { id -> s?.episodes?.firstOrNull { it.id == id } }
    if (open != null && s != null) {
        SessionDetailSheet(
            episode = open,
            packages = s.packages,
            detail = detail?.takeIf { it.sessionId == open.id },
            onRate = { today.rate(open.id, it) },
            onDismiss = {
                openSessionId = null
                today.loadDetail(null)
            },
        )
    }
    if (editingBudget && s != null) {
        BudgetDialog(current = s.budgetMs, onDismiss = { editingBudget = false }, onSave = {
            today.setBudget(it)
            editingBudget = false
        })
    }
    if (editingNote && s != null) {
        NoteDialog(initial = s.note?.noteText.orEmpty(), date = s.date, onDismiss = { editingNote = false }, onSave = {
            today.saveNote(s.date, it)
            editingNote = false
        })
    }
}

private fun headline(s: TodayState?, status: DayStatus?): String = when {
    s?.rollup == null -> "Building your baseline"
    status == DayStatus.SPIKE -> "A heavier day than usual"
    status == DayStatus.GOOD_DAY -> "A lighter day so far"
    s.budgetMs > 0 && s.rollup.distractingMs > s.budgetMs -> "Past today's budget"
    status == DayStatus.NORMAL -> "A typical day so far"
    else -> "Your day, reconstructed"
}

private fun subtitle(s: TodayState?, status: DayStatus?): String = when {
    s?.rollup == null -> "Lagrange is quietly recording. Friction stays off for the first week or two so you get an honest before-and-after."
    status == DayStatus.UNCERTAIN_COVERAGE -> "Collection had a gap today, so comparisons are paused rather than guessed."
    status == DayStatus.INSUFFICIENT_BASELINE -> "Comparisons appear once a week of fully covered days exists."
    else -> "Every pickup, reconstructed from what your phone actually logged."
}

// -- Budget orbit ---------------------------------------------------------------------------------------------------

@Composable
private fun BudgetOrbitCard(s: TodayState, onEdit: () -> Unit) {
    val used = s.rollup?.distractingMs ?: 0L
    val budget = s.budgetMs.coerceAtLeast(1)
    val ratio = used.toFloat() / budget
    val over = used > budget
    val colors = LocalStatusColors.current
    val arcColor = if (over) colors.bad else MaterialTheme.colorScheme.tertiary
    val animated by animateFloatAsState(ratio.coerceAtMost(2f), animationSpec = tween(900), label = "budget")

    SectionCard(title = null, container = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OrbitRing(
                fraction = animated,
                color = arcColor,
                modifier = Modifier
                    .size(148.dp)
                    .semantics { contentDescription = "${formatDurationShort(used)} of ${formatDurationShort(budget)} distracting budget used" },
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(formatDurationShort(used), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("of ${formatDurationShort(budget)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.width(Space.l))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Text("DISTRACTING TIME", style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (over) "${formatDurationShort(used - budget)} over" else "${formatDurationShort(budget - used)} left",
                    style = MaterialTheme.typography.titleLarge,
                    color = if (over) colors.bad else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "Time in apps you marked distracting. Glances and blocked launch pauses don't count.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Icon(Icons.Outlined.Tune, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.s))
                    Text("Edit budget")
                }
            }
        }
    }
}

/** A progress orbit: a faint track, the used arc, and a small body riding at its tip. Past 100% a second lap glows. */
@Composable
private fun OrbitRing(fraction: Float, color: Color, modifier: Modifier = Modifier, center: @Composable () -> Unit) {
    val track = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)
    val glow = color.copy(alpha = 0.18f)
    val body = MaterialTheme.colorScheme.primary
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = size.minDimension * 0.075f
            val r = size.minDimension / 2 - stroke
            val c = this.center
            drawCircle(Brush.radialGradient(listOf(glow, Color.Transparent), c, r * 1.1f), radius = r * 1.1f, center = c)
            drawCircle(track, radius = r, center = c, style = Stroke(stroke))
            val first = fraction.coerceIn(0f, 1f)
            val topLeft = Offset(c.x - r, c.y - r)
            val arcSize = androidx.compose.ui.geometry.Size(r * 2, r * 2)
            if (first > 0f) drawArc(color, -90f, 360f * first, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (fraction > 1f) {
                drawArc(color.copy(alpha = 0.55f), -90f, 360f * (fraction - 1f).coerceAtMost(1f), false,
                    Offset(c.x - r * 0.78f, c.y - r * 0.78f), androidx.compose.ui.geometry.Size(r * 1.56f, r * 1.56f),
                    style = Stroke(stroke * 0.6f, cap = StrokeCap.Round))
            }
            val a = Math.toRadians((-90.0 + 360.0 * first))
            drawCircle(body, radius = stroke * 0.55f, center = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        }
        center()
    }
}

// -- Baseline --------------------------------------------------------------------------------------------------------

@Composable
private fun BaselineCard(s: TodayState, status: DayStatus?) {
    val rollup = s.rollup ?: return
    val cmp = s.comparison
    val slice = cmp?.slice
    val weekend = s.date.dayOfWeek.value >= 6
    SectionCard(title = "Active phone time", eyebrow = "Against your usual", icon = Icons.Outlined.Insights) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(formatDurationShort(rollup.activeUsageMs), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(Space.m))
            if (status != null) StatusPill(status.chip, dayStatusColor(status))
        }
        val line = when {
            status == DayStatus.UNCERTAIN_COVERAGE ->
                "A collection gap today means this number may be low. It won't be compared or used in baselines."
            slice?.medianMs != null && cmp.dataEndMs > 0 ->
                "Usually ${formatDurationShort(slice.medianMs)} by ${formatClock(cmp.dataEndMs)} on ${if (weekend) "weekends" else "weekdays"} (median of ${slice.days} covered days)."
            cmp != null && cmp.coveredDays < cmp.coveredDaysNeeded ->
                "Your baseline unlocks after ${cmp.coveredDaysNeeded} fully covered days. ${cmp.coveredDays} so far."
            else -> "Not enough comparable ${if (weekend) "weekend" else "weekday"} history yet for a fair same-time comparison."
        }
        Text(line, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (cmp != null && cmp.coveredDays < cmp.coveredDaysNeeded && status != DayStatus.UNCERTAIN_COVERAGE) {
            LinearProgressIndicator(
                progress = { cmp.coveredDays.toFloat() / cmp.coveredDaysNeeded },
                modifier = Modifier.fillMaxWidth().clip(CircleShape),
            )
        }
        if (cmp?.fullDayMedianMs != null) {
            Text(
                "A full ${if (weekend) "weekend" else "weekday"} usually lands around ${formatDurationShort(cmp.fullDayMedianMs)}.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun HighlightCard(s: TodayState, status: DayStatus) {
    val colors = LocalStatusColors.current
    val spike = status == DayStatus.SPIKE
    val accent = if (spike) colors.bad else colors.good
    val top = s.apps.firstOrNull { s.packages[it.pkgId]?.category == AppCategory.DISTRACTING.name && it.totalMs > 0 }
    val median = s.comparison?.slice?.medianMs
    val active = s.rollup?.activeUsageMs ?: 0
    SectionCard(
        title = if (spike) "Spike" else "Good day",
        eyebrow = "Highlight",
        icon = if (spike) Icons.Outlined.Whatshot else Icons.Outlined.Spa,
        container = accent.copy(alpha = 0.10f),
    ) {
        val delta = median?.let { active - it }
        val body = buildString {
            if (delta != null) {
                append(if (spike) "${formatDurationShort(delta)} above" else "${formatDurationShort(-delta)} below")
                append(" what you usually reach by now.")
            } else {
                append(if (spike) "Well above your usual." else "Within budget and at or below your usual.")
            }
            if (top != null) {
                val name = s.packages[top.pkgId]?.displayName ?: "One app"
                append(if (spike) " Most of it: $name, ${formatDurationShort(top.totalMs)}." else " Top distracting app: $name, ${formatDurationShort(top.totalMs)}.")
            }
        }
        Text(body, style = MaterialTheme.typography.bodyLarge)
    }
}

// -- Focus ----------------------------------------------------------------------------------------------------------

@Composable
private fun FocusCard(s: TodayState, now: Long) {
    val rollup = s.rollup ?: return
    val unlocked = s.episodes.filter { !it.session.isGlanceOnly }
    val inProgress = unlocked.lastOrNull()?.takeIf { it.isInProgress }
    val lastEnded = unlocked.lastOrNull { !it.isInProgress }
    // While Lagrange itself is open there is always an in-progress episode; the honest "current" streak is the
    // phone-free stretch that ended with this pickup.
    val current = when {
        inProgress != null && lastEnded != null -> inProgress.session.startMs - lastEnded.session.endMs
        lastEnded != null -> now - lastEnded.session.endMs
        else -> null
    }
    SectionCard(title = "Phone-free stretches", eyebrow = "Focus", icon = Icons.Outlined.Nightlight) {
        StatRow {
            StatTile(
                if (inProgress != null) "Before this pickup" else "Current",
                current?.let { formatDurationShort(it) } ?: "—",
                Modifier.weight(1f),
            )
            StatTile("Longest today", if (rollup.longestFocusGapMs > 0) formatDurationShort(rollup.longestFocusGapMs) else "—", Modifier.weight(1f))
        }
        StatRow {
            StatTile("Overnight gap", rollup.overnightGapMs?.let { formatDurationShort(it) } ?: "—", Modifier.weight(1f), caption = "Sleep proxy")
            StatTile("First pickup", rollup.firstPickupMs?.let { formatClock(it) } ?: "—", Modifier.weight(1f))
        }
    }
}

// -- Day note -------------------------------------------------------------------------------------------------------

@Composable
private fun DayNoteChip(s: TodayState, onEdit: () -> Unit) {
    val text = s.note?.noteText
    AssistChip(
        onClick = onEdit,
        label = {
            Text(
                text ?: "Add a day note: travel, sick, deadline…",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        },
        leadingIcon = { Icon(Icons.Outlined.EditNote, null, Modifier.size(AssistChipDefaults.IconSize)) },
        shape = CircleShape,
    )
}

// -- Categories & metrics -------------------------------------------------------------------------------------------

@Composable
private fun CategoryCard(s: TodayState) {
    val r = s.rollup ?: return
    val parts = listOf(
        AppCategory.DISTRACTING to r.distractingMs,
        AppCategory.NEUTRAL to r.neutralMs,
        AppCategory.UTILITY to r.utilityMs,
        AppCategory.EXCLUDED to r.excludedMs,
    ).filter { it.second > 0 }
    val total = parts.sumOf { it.second }.coerceAtLeast(1)
    SectionCard(title = "Where the time went", eyebrow = "Categories", icon = Icons.Outlined.Timeline) {
        if (parts.isEmpty()) {
            Text("No app time yet today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Row(
                Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(CircleShape),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                parts.forEach { (cat, ms) ->
                    Box(
                        Modifier
                            .weight((ms.toFloat() / total).coerceAtLeast(0.01f))
                            .height(14.dp)
                            .background(categoryColor(cat)),
                    )
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                parts.forEach { (cat, ms) ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(cat)))
                        Spacer(Modifier.width(6.dp))
                        Text("${cat.label} ${formatDurationShort(ms)}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }
        val top = s.apps.filter { it.totalMs > 0 }.take(5)
        if (top.isNotEmpty()) {
            val max = top.first().totalMs.coerceAtLeast(1)
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                top.forEach { a ->
                    val pkg = s.packages[a.pkgId]
                    val cat = pkg?.category?.let { c -> AppCategory.entries.firstOrNull { it.name == c } } ?: AppCategory.NEUTRAL
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(pkg?.displayName ?: "Unknown app", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            Text("${formatDurationShort(a.totalMs)} · ${a.openCount}×", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier
                                .fillMaxWidth((a.totalMs.toFloat() / max).coerceIn(0.02f, 1f))
                                .height(6.dp)
                                .clip(CircleShape)
                                .background(categoryColor(cat).copy(alpha = 0.75f)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MetricsCard(s: TodayState) {
    val r = s.rollup ?: return
    SectionCard(title = "Pickups & patterns", eyebrow = "Today's numbers", icon = Icons.Outlined.AutoAwesome) {
        StatRow {
            StatTile("Pickups", r.physicalPickupCount.toString(), Modifier.weight(1f))
            StatTile("Sessions", r.episodeCount.toString(), Modifier.weight(1f))
            StatTile("Glances", r.glanceCount.toString(), Modifier.weight(1f))
        }
        StatRow {
            StatTile("Micro-checks", r.microCheckCount.toString(), Modifier.weight(1f))
            StatTile("Rabbit holes", r.rabbitHoleCount.toString(), Modifier.weight(1f))
            StatTile("Notifications", r.notificationCount.toString(), Modifier.weight(1f))
        }
        StatRow {
            StatTile("Screen on", formatDurationShort(r.totalScreenMs), Modifier.weight(1f))
            StatTile("Blocked", r.blockedAttemptCount.toString(), Modifier.weight(1f))
            StatTile("Last active", r.lastActivityMs?.let { formatClock(it) } ?: "—", Modifier.weight(1f))
        }
    }
}

// -- Timeline -------------------------------------------------------------------------------------------------------

@Composable
private fun TimelineHeader(
    total: Int,
    glances: Int,
    filter: TimelineFilter,
    onFilter: (TimelineFilter) -> Unit,
    showGlances: Boolean,
    onShowGlances: (Boolean) -> Unit,
) {
    Column(Modifier.padding(top = Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text("TIMELINE", style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
        Text("$total sessions · $glances glances", style = MaterialTheme.typography.titleLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            TimelineFilter.entries.forEach { f ->
                FilterChip(selected = filter == f, onClick = { onFilter(f) }, label = { Text(f.label) })
            }
            FilterChip(selected = showGlances, onClick = { onShowGlances(!showGlances) }, label = { Text("Show glances") })
        }
    }
}

@Composable
fun EpisodeCard(ep: EpisodeUi, packages: Map<Long, PackageEntity>, onClick: () -> Unit) {
    val s = ep.session
    val archetype = archetypeOf(s.archetype)
    val trigger = triggerOf(s.triggerEvidence)
    val rabbit = s.isRabbitHole
    val colors = LocalStatusColors.current
    val accent = if (rabbit) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(24.dp)
    Surface(
        shape = shape,
        color = if (rabbit) MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (rabbit) Modifier.border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.5f), shape) else Modifier)
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Row(Modifier.padding(Space.m), verticalAlignment = Alignment.Top) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(52.dp)) {
                Text(formatClock(s.startMs), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(Space.xs))
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accent.copy(alpha = 0.16f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(archetype?.icon ?: unknownIcon, null, Modifier.size(16.dp), tint = accent)
                }
            }
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        archetype?.label ?: s.archetype,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatDuration(s.activeDurationMs) + if (ep.isInProgress) " · now" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val chain = hopChain(ep, packages)
                if (chain.isNotEmpty()) {
                    Text(chain, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (trigger != null) MiniBadge(trigger.badge, if (trigger.isNotificationLed) MaterialTheme.colorScheme.secondary else colors.neutral)
                    if (s.physicalPickupCount > 1) MiniBadge("${s.physicalPickupCount} pickups", colors.neutral)
                    if (s.blockedAttemptCount > 0) MiniBadge("${s.blockedAttemptCount} blocked", colors.good)
                    if (ep.hasUncertainTail) MiniBadge("End uncertain", colors.warn)
                    s.worthItRating?.let { RatingBadge(it) }
                }
            }
        }
    }
}

/** "Instagram → Chrome → YouTube", collapsing repeats and leaving out system surfaces. */
fun hopChain(ep: EpisodeUi, packages: Map<Long, PackageEntity>): String {
    val names = ArrayList<String>()
    for (seg in ep.segments) {
        val p = packages[seg.pkgId]
        if (p?.isLauncherOrSystemUi == true) continue
        val name = p?.displayName ?: continue
        if (names.lastOrNull() != name) names += name
    }
    return names.joinToString(" → ")
}

@Composable
fun MiniBadge(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.13f)) {
        Text(text, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@Composable
fun RatingBadge(rating: Int) {
    val color = when {
        rating >= 4 -> LocalStatusColors.current.good
        rating <= 2 -> LocalStatusColors.current.bad
        else -> LocalStatusColors.current.neutral
    }
    Surface(shape = CircleShape, color = color.copy(alpha = 0.13f)) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Star, null, Modifier.size(12.dp), tint = color)
            Spacer(Modifier.width(2.dp))
            Text("$rating/5", style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun CoverageLine(label: String, detail: String, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        StatusPill(detail, color)
    }
}
