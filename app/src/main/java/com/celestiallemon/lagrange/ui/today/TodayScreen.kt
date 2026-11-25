package com.celestiallemon.lagrange.ui.today

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.HourglassEmpty
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.data.RecomputeProgress
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.system.PermissionHealth
import com.celestiallemon.lagrange.ui.CollectorStats
import com.celestiallemon.lagrange.ui.EpisodeUi
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.TodayState
import com.celestiallemon.lagrange.ui.TodayViewModel
import com.celestiallemon.lagrange.ui.components.AppIcon
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.BarRow
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.CountUpText
import com.celestiallemon.lagrange.ui.components.Dot
import com.celestiallemon.lagrange.ui.components.EmptyState
import com.celestiallemon.lagrange.ui.components.Info
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.OrbitGauge
import com.celestiallemon.lagrange.ui.components.Panel
import com.celestiallemon.lagrange.ui.components.SectionHeader
import com.celestiallemon.lagrange.ui.components.Segment
import com.celestiallemon.lagrange.ui.components.SplitMeter
import com.celestiallemon.lagrange.ui.components.Stat
import com.celestiallemon.lagrange.ui.components.StatStrip
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.formatClock
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.friction.label
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Motion
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.rise

internal enum class TimelineFilter(val label: String) { ALL("All"), RABBIT("Rabbit holes"), NOTIFIED("From notifications") }

/** What Today can do; the screen itself holds no business logic. */
class TodayActions(
    val onEditBudget: () -> Unit = {},
    val onEditNote: () -> Unit = {},
    val onOpenSession: (EpisodeUi) -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

@Composable
fun TodayScreen(
    main: MainViewModel,
    padding: PaddingValues,
    onOpenSettings: () -> Unit,
    today: TodayViewModel = viewModel(),
) {
    val stats by main.stats.collectAsStateWithLifecycle()
    val health by main.health.collectAsStateWithLifecycle()
    val state by today.state.collectAsStateWithLifecycle()
    val progress by today.progress.collectAsStateWithLifecycle()
    val detail by today.detail.collectAsStateWithLifecycle()

    // Keyed by episode key, not row id: a rebuild replaces rows (new ids) but keeps keys, so the sheet stays open.
    var openKey by rememberSaveable { mutableStateOf<String?>(null) }
    var editingBudget by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf(false) }
    val s = state
    fun open(key: String) {
        openKey = key
        today.loadDetail(key)
    }

    TodayContent(
        state = s,
        stats = stats,
        health = health,
        progress = progress,
        now = System.currentTimeMillis(),
        padding = padding,
        actions = TodayActions(
            onEditBudget = { editingBudget = true },
            onEditNote = { editingNote = true },
            onOpenSession = { open(it.session.episodeKey) },
            onOpenSettings = onOpenSettings,
        ),
    )

    val opened = openKey?.let { key -> s?.episodes?.firstOrNull { it.session.episodeKey == key } }
    if (opened != null && s != null) {
        SessionDetailSheet(
            episode = opened,
            packages = s.packages,
            detail = detail?.takeIf { it.episodeKey == opened.session.episodeKey },
            onDismiss = {
                openKey = null
                today.loadDetail(null)
            },
        )
    }
    if (editingBudget && s != null) {
        BudgetSheet(current = s.budgetMs, onDismiss = { editingBudget = false }, onSave = {
            today.setBudget(it)
            editingBudget = false
        })
    }
    if (editingNote && s != null) {
        NoteSheet(initial = s.note?.noteText.orEmpty(), date = s.date, onDismiss = { editingNote = false }, onSave = {
            today.saveNote(s.date, it)
            editingNote = false
        })
    }
}

@Composable
fun TodayContent(
    state: TodayState?,
    stats: CollectorStats,
    health: PermissionHealth,
    progress: RecomputeProgress,
    now: Long,
    padding: PaddingValues,
    actions: TodayActions = TodayActions(),
) {
    var filter by rememberSaveable { mutableStateOf(TimelineFilter.ALL) }
    var detailsOpen by rememberSaveable { mutableStateOf(false) }
    val s = state
    val dayStatus = dayStatusOf(s?.rollup?.dayStatus)

    LagrangeScreen(title = "Today", contentPadding = padding) {
        // One banner at most, the most important first.
        val banner = todayBanner(progress, health, stats, dayStatus, now)
        if (banner != null) item("banner") { TodayBannerView(banner, actions.onOpenSettings, Modifier.rise(0)) }

        if (s == null) return@LagrangeScreen
        if (s.rollup == null) {
            item("empty") {
                EmptyState(
                    title = "Your first timeline is on its way",
                    body = "Lagrange is quietly recording. As soon as a harvest lands, raw events become honest sessions: " +
                        "glances, quick checks, focused stretches and rabbit holes.",
                    modifier = Modifier.rise(1),
                )
            }
            return@LagrangeScreen
        }

        item("hero") { TodayHero(s, dayStatus, actions, Modifier.rise(1)) }
        item("glance") { GlanceStrip(s, dayStatus, now, Modifier.rise(2)) }
        item("where") { WhereItWent(s, Modifier.rise(3)) }

        val unlocked = s.episodes.filter { !it.session.isGlanceOnly }
        val visible = timelineRows(s.episodes, filter)
        item("timeline-header") {
            Column(Modifier.rise(4)) {
                SectionHeader(
                    "Sessions",
                    supporting = "${unlocked.size} sessions · ${s.episodes.size - unlocked.size} glances",
                    info = Info(
                        "How sessions are rebuilt",
                        "A session runs from unlocking the phone to locking it. Locks shorter than 15 seconds are merged in, " +
                            "so a quick screen-off doesn't split one sitting into two.\n\nA glance is the screen coming on " +
                            "without an unlock: a lock-screen check. Glances don't count toward active time.\n\nWhat started a " +
                            "session is inferred from notification timing and is labelled modestly: \"after an alert\" means one " +
                            "arrived in the minute before, not that it caused the pickup.",
                    ),
                )
                Choice(TimelineFilter.entries, filter, { filter = it }, { it.label }, forceChips = true)
            }
        }
        if (visible.isEmpty()) {
            item("timeline-empty") {
                Text(
                    if (s.episodes.isEmpty()) "No sessions yet today." else "Nothing matches this filter.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(Spacing.l),
                )
            }
        }
        items(visible, key = { it.key }) { row ->
            when (row) {
                is TimelineRow.Session -> TimelineSession(row.ep, s.packages, first = row === visible.first(), onClick = { actions.onOpenSession(row.ep) })
                is TimelineRow.Glances -> TimelineGlances(row.count)
            }
        }

        item("details") {
            DayDetails(s, expanded = detailsOpen, onToggle = { detailsOpen = !detailsOpen }, modifier = Modifier.padding(top = Spacing.xl))
        }
    }
}

// -- Banner -----------------------------------------------------------------------------------------------------------

private enum class TodayBanner { Rebuilding, Setup, Overdue, Gap }

private fun todayBanner(progress: RecomputeProgress, health: PermissionHealth, stats: CollectorStats, status: DayStatus?, now: Long): Pair<TodayBanner, RecomputeProgress>? = when {
    progress.running -> TodayBanner.Rebuilding
    !health.coreReady -> TodayBanner.Setup
    stats.harvestOverdue(now) -> TodayBanner.Overdue
    status == DayStatus.UNCERTAIN_COVERAGE -> TodayBanner.Gap
    else -> null
}?.let { it to progress }

@Composable
private fun TodayBannerView(b: Pair<TodayBanner, RecomputeProgress>, onOpenSettings: () -> Unit, modifier: Modifier) {
    val (kind, p) = b
    when (kind) {
        TodayBanner.Rebuilding -> Banner(
            "Rebuilding your timeline",
            Tone.Brand,
            Icons.Outlined.Sync,
            body = "${p.doneDays} of ${p.totalDays} days done. Figures may shift a little until it finishes.",
            modifier = modifier,
        )
        TodayBanner.Setup -> Banner(
            "Finish setting up",
            Tone.Caution,
            Icons.Outlined.WarningAmber,
            body = "Usage Access and Notification Access let Lagrange record. Until both are on, days count as partial.",
            action = "Open permissions" to onOpenSettings,
            modifier = modifier,
        )
        TodayBanner.Overdue -> Banner(
            "Nothing recorded for over two days",
            Tone.Caution,
            Icons.Outlined.HourglassEmpty,
            body = "Android keeps only about a week of usage history. Check battery settings so recording keeps up.",
            action = "Check settings" to onOpenSettings,
            modifier = modifier,
        )
        TodayBanner.Gap -> Banner(
            "Recording had a gap today",
            Tone.Caution,
            Icons.Outlined.WarningAmber,
            body = "Numbers are a lower bound, so today isn't compared with your usual.",
            modifier = modifier,
        )
    }
}

// -- Hero -------------------------------------------------------------------------------------------------------------

internal fun headline(s: TodayState, status: DayStatus?): String = when {
    status == DayStatus.SPIKE -> "A heavier day than usual"
    status == DayStatus.GOOD_DAY -> "A lighter day so far"
    s.budgetMs > 0 && (s.rollup?.distractingMs ?: 0) > s.budgetMs -> "Past today's budget"
    status == DayStatus.NORMAL -> "A typical day so far"
    status == DayStatus.UNCERTAIN_COVERAGE -> "A partly recorded day"
    else -> "Your day so far"
}

/**
 * The answer to "how am I doing today?": distracting time against the budget, drawn as an orbit, and one sentence.
 * Tapping the gauge edits the budget; the note lives right under it.
 */
@Composable
private fun TodayHero(s: TodayState, status: DayStatus?, actions: TodayActions, modifier: Modifier) {
    val used = s.rollup?.distractingMs ?: 0L
    val budget = s.budgetMs.coerceAtLeast(1)
    val over = used > budget
    val colors = Lagrange.colors
    val arc = if (over) colors.critical.accent else colors.category.distracting
    val lowerBound = status == DayStatus.UNCERTAIN_COVERAGE
    Column(
        modifier
            .fillMaxWidth()
            .padding(top = Spacing.s, bottom = Spacing.l),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        Text(formatDay(s.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        OrbitGauge(
            fraction = used.toFloat() / budget,
            color = arc,
            modifier = Modifier
                .clickable(onClickLabel = "Edit budget", onClick = actions.onEditBudget)
                .semantics { contentDescription = "${formatDurationShort(used)} of ${formatDurationShort(budget)} distracting budget used" },
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (lowerBound) Text("≥", style = NumberStyles.stat, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    CountUpText(used, ::formatDurationShort, NumberStyles.hero.copy(fontSize = NumberStyles.hero.fontSize * 0.66f))
                }
                Text("of ${formatDurationShort(budget)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(headline(s, status), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(
            when {
                over -> "${formatDurationShort(used - budget)} over your distracting budget"
                else -> "${formatDurationShort(budget - used)} left in your distracting budget"
            },
            style = MaterialTheme.typography.bodyLarge,
            color = if (over) colors.critical.accent else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            if (status != null && status != DayStatus.NORMAL) Tag(status.chip, tone = status.tone, modifier = Modifier.align(Alignment.CenterVertically))
            AssistChip(
                onClick = actions.onEditNote,
                label = { Text(s.note?.noteText ?: "Add a note", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp)) },
                leadingIcon = { Icon(Icons.Outlined.EditNote, null, Modifier.size(AssistChipDefaults.IconSize)) },
            )
        }
    }
}

// -- Glance strip -----------------------------------------------------------------------------------------------------

@Composable
private fun GlanceStrip(s: TodayState, status: DayStatus?, now: Long, modifier: Modifier) {
    val r = s.rollup ?: return
    val cmp = s.comparison
    val median = cmp?.slice?.medianMs?.takeIf { status != DayStatus.UNCERTAIN_COVERAGE }
    val activeCaption = when {
        median != null -> "usually ${formatDurationShort(median)}"
        cmp != null && cmp.coveredDays < cmp.coveredDaysNeeded -> "baseline ${cmp.coveredDays}/${cmp.coveredDaysNeeded} days"
        else -> null
    }
    val activeTone = when {
        median == null -> Tone.Neutral
        r.activeUsageMs > median * 1.2 -> Tone.Ember
        r.activeUsageMs < median * 0.8 -> Tone.Good
        else -> Tone.Neutral
    }
    val unlocked = s.episodes.filter { !it.session.isGlanceOnly }
    val inProgress = unlocked.lastOrNull()?.takeIf { it.isInProgress }
    val lastEnded = unlocked.lastOrNull { !it.isInProgress }
    // While Lagrange itself is open there is always an in-progress session; the honest "current" stretch is the one
    // that ended with this pickup.
    val current = when {
        inProgress != null && lastEnded != null -> inProgress.session.startMs - lastEnded.session.endMs
        lastEnded != null -> now - lastEnded.session.endMs
        else -> null
    }
    Panel(modifier, spacing = Spacing.s) {
        StatStrip {
            Stat("Active", formatDurationShort(r.activeUsageMs), Modifier.weight(1f), caption = activeCaption, captionTone = activeTone)
            Stat("Pickups", r.physicalPickupCount.toString(), Modifier.weight(1f), caption = "${r.episodeCount} sessions")
            Stat(
                "Phone-free",
                if (r.longestFocusGapMs > 0) formatDurationShort(r.longestFocusGapMs) else "–",
                Modifier.weight(1f),
                caption = current?.let { (if (inProgress != null) "before this: " else "now: ") + formatDurationShort(it) } ?: "longest today",
            )
        }
    }
}

// -- Where it went ----------------------------------------------------------------------------------------------------

@Composable
private fun WhereItWent(s: TodayState, modifier: Modifier) {
    val r = s.rollup ?: return
    val parts = listOf(
        AppCategory.DISTRACTING to r.distractingMs,
        AppCategory.NEUTRAL to r.neutralMs,
        AppCategory.UTILITY to r.utilityMs,
        AppCategory.EXCLUDED to r.excludedMs,
    ).filter { it.second > 0 }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        SectionHeader(
            "Where it went",
            info = Info(
                "Categories",
                "Distracting apps count toward your budget and can have a launch pause. Neutral apps (messaging, the " +
                    "browser) are tracked normally. Utility apps (maps, banking, 2FA) are never paused. Excluded apps are left " +
                    "out of active time.\n\nChanging an app's category re-colours its whole history.",
            ),
        )
        if (parts.isEmpty()) {
            Text("No app time yet today.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        SplitMeter(parts.map { (c, ms) -> Segment(ms.toFloat(), categoryColor(c)) })
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            parts.forEach { (c, ms) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(categoryColor(c), size = 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(c.label, style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.width(Spacing.xs))
                    Text(formatDurationShort(ms), style = NumberStyles.inline, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        val top = s.apps.filter { it.totalMs > 0 }.take(5)
        if (top.isNotEmpty()) {
            Spacer(Modifier.height(Spacing.xs))
            val max = top.first().totalMs.coerceAtLeast(1)
            top.forEach { a ->
                val pkg = s.packages[a.pkgId]
                val color = categoryColor(pkg.categoryOrNeutral())
                BarRow(
                    label = pkg?.displayName ?: "Unknown app",
                    value = "${formatDurationShort(a.totalMs)} · ${a.openCount}×",
                    fraction = a.totalMs.toFloat() / max,
                    color = color,
                    leading = { AppIcon(pkg?.packageName, pkg?.displayName ?: "?", color) },
                )
            }
        }
    }
}

internal fun PackageEntity?.categoryOrNeutral(): AppCategory =
    this?.category?.let { c -> AppCategory.entries.firstOrNull { it.name == c } } ?: AppCategory.NEUTRAL

// -- Timeline ---------------------------------------------------------------------------------------------------------

internal sealed interface TimelineRow {
    val key: String

    data class Session(val ep: EpisodeUi) : TimelineRow {
        override val key get() = "ep-${ep.session.episodeKey}"
    }

    data class Glances(val count: Int, val after: String) : TimelineRow {
        override val key get() = "gl-$after"
    }
}

/**
 * Newest first. Glances are not hidden behind a toggle: in "All" each run of glances between two sessions shows as
 * one quiet line on the rail, so the rhythm of the day stays visible without the noise.
 */
internal fun timelineRows(episodes: List<EpisodeUi>, filter: TimelineFilter): List<TimelineRow> {
    val rows = ArrayList<TimelineRow>()
    var glances = 0
    var lastKey = "start"
    for (ep in episodes) {
        val s = ep.session
        if (s.isGlanceOnly) {
            glances++
            continue
        }
        val keep = when (filter) {
            TimelineFilter.ALL -> true
            TimelineFilter.RABBIT -> s.isRabbitHole
            TimelineFilter.NOTIFIED -> triggerOf(s.triggerEvidence)?.isNotificationLed == true
        }
        if (filter == TimelineFilter.ALL && glances > 0) rows += TimelineRow.Glances(glances, lastKey)
        glances = 0
        if (keep) rows += TimelineRow.Session(ep)
        lastKey = s.episodeKey
    }
    if (filter == TimelineFilter.ALL && glances > 0) rows += TimelineRow.Glances(glances, lastKey)
    return rows.asReversed()
}

private val TimeColumn = 64.dp
private val RailColumn = 24.dp

@Composable
private fun TimelineSession(ep: EpisodeUi, packages: Map<Long, PackageEntity>, first: Boolean, onClick: () -> Unit) {
    val s = ep.session
    val archetype = archetypeOf(s.archetype)
    val c = Lagrange.colors
    val node = when {
        s.isRabbitHole -> c.ember.accent
        s.totalDistractingMs > 0 -> c.category.distracting.copy(alpha = 0.55f)
        else -> MaterialTheme.colorScheme.outline
    }
    Row(Modifier.fillMaxWidth().rail(node, filled = s.isRabbitHole || s.totalDistractingMs > 0, live = ep.isInProgress, first = first, nodeY = Spacing.l + 10.dp)) {
        Column(Modifier.width(TimeColumn).padding(top = Spacing.l)) {
            Text(formatClock(s.startMs), style = NumberStyles.inline, maxLines = 1, softWrap = false)
        }
        Spacer(Modifier.width(RailColumn))
        Panel(
            modifier = Modifier.weight(1f),
            onClick = onClick,
            onClickLabel = "Open session",
            shape = MaterialTheme.shapes.medium,
            color = if (s.isRabbitHole) c.ember.container.copy(alpha = 0.6f) else MaterialTheme.colorScheme.surfaceContainerLow,
            padding = PaddingValues(Spacing.l),
            spacing = Spacing.xs,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(archetype?.label ?: s.archetype, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    formatDuration(s.activeDurationMs) + if (ep.isInProgress) " · now" else "",
                    style = NumberStyles.inline,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val chain = hopChain(ep, packages)
            if (chain.isNotEmpty()) Text(chain, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            SessionTags(ep, Modifier.padding(top = Spacing.xs))
        }
    }
}

@Composable
private fun TimelineGlances(count: Int) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .rail(MaterialTheme.colorScheme.outlineVariant, filled = true, live = false, first = false, nodeY = 18.dp, small = true),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(TimeColumn + RailColumn))
        Text(
            if (count == 1) "1 glance" else "$count glances",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * The vertical line the day hangs on, drawn behind a whole timeline row at the rail column. It extends past the row
 * so rows separated by list spacing join into one continuous line. The live session's node breathes gently
 * (unless motion is reduced).
 */
private fun Modifier.rail(color: Color, filled: Boolean, live: Boolean, first: Boolean, nodeY: Dp, small: Boolean = false): Modifier = composed {
    val line = MaterialTheme.colorScheme.outlineVariant
    val surface = MaterialTheme.colorScheme.surface
    val pulse = if (live && !Lagrange.reducedMotion) {
        rememberInfiniteTransition(label = "live").animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = Motion.Emphasized), RepeatMode.Reverse), label = "live-pulse").value
    } else {
        0f
    }
    drawBehind {
        val x = (TimeColumn + RailColumn / 2).toPx()
        val y = nodeY.toPx()
        val gap = Spacing.s.toPx()
        drawLine(line, Offset(x, if (first) y else -gap), Offset(x, size.height + gap), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        val r = (if (small) 3.dp else 6.dp).toPx()
        if (live) drawCircle(color.copy(alpha = 0.25f * (1f - pulse)), radius = r * (1.8f + 1.4f * pulse), center = Offset(x, y))
        drawCircle(surface, radius = r + 3.dp.toPx(), center = Offset(x, y))
        if (filled) drawCircle(color, radius = r, center = Offset(x, y)) else drawCircle(color, radius = r - 1.dp.toPx(), center = Offset(x, y), style = Stroke(2.dp.toPx()))
    }
}

// -- Day details ------------------------------------------------------------------------------------------------------

/** Everything else about the day, folded away: useful, but not what you open the app for. */
@Composable
private fun DayDetails(s: TodayState, expanded: Boolean, onToggle: () -> Unit, modifier: Modifier) {
    val r = s.rollup ?: return
    val chevron by animateFloatAsState(if (expanded) 180f else 0f, Motion.spatialFast(), label = "chevron")
    Panel(modifier.animateContentSize(Motion.spatial()), onClick = onToggle, onClickLabel = if (expanded) "Hide day details" else "Show day details") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Day details", style = MaterialTheme.typography.titleMedium)
                if (!expanded) {
                    Text("Glances, quick checks, notifications, screen time…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Icon(Icons.Outlined.ExpandMore, contentDescription = null, modifier = Modifier.rotate(chevron))
        }
        if (expanded) {
            val cells = listOf(
                "Screen on" to formatDurationShort(r.totalScreenMs),
                "Glances" to r.glanceCount.toString(),
                "Quick checks" to r.microCheckCount.toString(),
                "Rabbit holes" to r.rabbitHoleCount.toString(),
                "Notifications" to r.notificationCount.toString(),
                "Paused opens" to r.blockedAttemptCount.toString(),
                "First pickup" to (r.firstPickupMs?.let { formatClock(it) } ?: "–"),
                "Last active" to (r.lastActivityMs?.let { formatClock(it) } ?: "–"),
                "Overnight" to (r.overnightGapMs?.let { formatDurationShort(it) } ?: "–"),
            )
            cells.chunked(3).forEach { row ->
                StatStrip {
                    row.forEach { (label, value) -> Stat(label, value, Modifier.weight(1f), valueStyle = NumberStyles.stat.copy(fontSize = NumberStyles.stat.fontSize * 0.8f)) }
                }
            }
        }
    }
}
