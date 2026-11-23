package com.celestiallemon.lagrange.ui.lab

import android.widget.Toast
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.FrictionData
import com.celestiallemon.lagrange.data.GatewayData
import com.celestiallemon.lagrange.data.LabScope
import com.celestiallemon.lagrange.data.NotificationCostData
import com.celestiallemon.lagrange.data.RhythmData
import com.celestiallemon.lagrange.data.SpikesReport
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.domain.lab.DayHighlight
import com.celestiallemon.lagrange.domain.lab.GapBucket
import com.celestiallemon.lagrange.domain.lab.GatewayRow
import com.celestiallemon.lagrange.domain.lab.LabApps
import com.celestiallemon.lagrange.domain.lab.NotificationCostRow
import com.celestiallemon.lagrange.domain.lab.NotificationSettingsTarget
import com.celestiallemon.lagrange.domain.lab.NotificationSort
import com.celestiallemon.lagrange.domain.lab.PickupLift
import com.celestiallemon.lagrange.domain.lab.RabbitFilter
import com.celestiallemon.lagrange.domain.lab.RabbitHoleFeed
import com.celestiallemon.lagrange.domain.lab.RatingFilter
import com.celestiallemon.lagrange.domain.lab.RecentVsEarlier
import com.celestiallemon.lagrange.domain.lab.SpikeAutopsy
import com.celestiallemon.lagrange.system.NotificationSettingsIntents
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatRow
import com.celestiallemon.lagrange.ui.components.StatTile
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatClock
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import com.celestiallemon.lagrange.ui.today.badge
import java.util.Locale
import kotlin.math.roundToInt

private fun pct(p: Double): String = "${(p * 100).roundToInt()}%"

private fun scopeLine(scope: LabScope): String =
    "${formatDay(scope.range.start)} – ${formatDay(scope.range.end)} · ${scope.coveredDates.size} of ${scope.range.days} days fully recorded"

// -- Spikes ---------------------------------------------------------------------------------------------------------------

fun LazyListScope.spikesSection(
    report: SpikesReport,
    autopsy: AutopsyState?,
    onToggle: (DayHighlight) -> Unit,
    onOpenSession: (String) -> Unit,
) {
    if (report.baselineDays < report.baselineDaysNeeded && report.history.isEmpty()) {
        item("spikes-wait") {
            SectionCard("Learning your usual", eyebrow = "Spikes & good days", icon = Icons.Outlined.Whatshot) {
                Text(
                    "Days are judged against comparable days (weekdays with weekdays, weekends with weekends). That needs " +
                        "${report.baselineDaysNeeded} fully recorded days; there are ${report.baselineDays} so far.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { report.baselineDays.toFloat() / report.baselineDaysNeeded },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        return
    }
    report.live?.let { live ->
        item("spike-live") { HighlightCard(live, autopsy?.takeIf { it.date == live.date }, { onToggle(live) }, onOpenSession) }
    }
    if (report.history.isEmpty() && report.live == null) {
        item("spikes-none") {
            SectionCard("Nothing unusual", eyebrow = "Spikes & good days", icon = Icons.Outlined.Whatshot) {
                Text("No day in this window was far from your usual. ${scopeLine(report.scope)}.", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    items(report.history, key = { "spike-${it.date}" }) { h ->
        HighlightCard(h, autopsy?.takeIf { it.date == h.date }, { onToggle(h) }, onOpenSession)
    }
    item("spikes-foot") {
        Muted(
            "A spike is at least 40% and 30 minutes above the median of comparable days in the 28 before it. A good day is at " +
                "least 25% and 20 minutes below it, or within your distracting budget with no more screen time than usual. " +
                "Days with gaps in recording are never judged. ${scopeLine(report.scope)}.",
            Modifier.padding(horizontal = Space.s),
        )
    }
}

@Composable
private fun HighlightCard(h: DayHighlight, autopsy: AutopsyState?, onToggle: () -> Unit, onOpenSession: (String) -> Unit) {
    val colors = LocalStatusColors.current
    val spike = h.status == DayStatus.SPIKE
    val color = if (spike) colors.warn else colors.good
    val title = when {
        h.live && spike -> "Today is running high"
        h.live -> "Today is running light"
        spike -> "Spike"
        else -> "Good day"
    }
    SectionCard(
        title = null,
        modifier = Modifier.animateContentSize(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .clickable(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(title, color)
                    Spacer(Modifier.width(Space.s))
                    Text(if (h.live) "so far" else formatDay(h.date), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(
                    "${formatDurationShort(h.actualMs)} vs usual ${formatDurationShort(h.baselineMs)}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Muted(
                    "${signedDuration(h.deltaMs)} against ${h.matchedDays.size} comparable ${if (h.live) "days at this time" else "days"}",
                )
            }
            Icon(
                if (autopsy != null) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = if (autopsy != null) "Hide autopsy" else "Show autopsy",
            )
        }
        if (autopsy != null) {
            when {
                autopsy.loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
                autopsy.autopsy == null -> Muted("This day changed after a rebuild; it no longer stands out.")
                else -> AutopsyBody(autopsy.autopsy, onOpenSession)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AutopsyBody(a: SpikeAutopsy, onOpenSession: (String) -> Unit) {
    val colors = LocalStatusColors.current
    val apps = LocalLabApps.current
    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        if (a.longSessions.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SubHeading("Longest sessions")
                a.longSessions.forEach { s ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(
                            Modifier
                                .clickable { onOpenSession(s.episodeKey) }
                                .padding(Space.m),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Row {
                                Text(formatClock(s.startMs), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                                Text(formatDuration(s.activeMs) + if (s.inProgress) " · now" else "", style = MaterialTheme.typography.labelLarge)
                            }
                            Text(s.hopChain.joinToString(" → ") { apps.name(it) }, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Muted("${formatDuration(s.distractingMs)} distracting · ${s.trigger.badge}" + if (s.isRabbitHole) " · rabbit hole" else "")
                        }
                    }
                }
            }
        }
        if (a.appDeltas.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SubHeading(if (a.highlight.status == DayStatus.SPIKE) "Apps above their usual" else "Apps that still ran above usual")
                val maxDelta = a.appDeltas.maxOf { it.deltaMs }.coerceAtLeast(1)
                a.appDeltas.forEach { d ->
                    BarRow(
                        apps.name(d.pkgId),
                        signedDuration(d.deltaMs),
                        d.deltaMs.toFloat() / maxDelta,
                        color = MaterialTheme.colorScheme.tertiary,
                        caption = "${formatDuration(d.actualMs)} vs usual ${formatDuration(d.medianMs)}",
                    )
                }
                Muted("Each app is compared with its own usual, so these don't add up to the day's total difference.")
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            SubHeading("Time of day")
            val maxBlock = a.blocks.maxOfOrNull { maxOf(it.actualMs, it.medianMs ?: 0) }?.coerceAtLeast(1) ?: 1
            a.blocks.forEach { b ->
                val (from, to) = b.block.wallHours(LocalLabRollover.current)
                BarRow(
                    "${b.block.label} ${"%02d".format(Locale.US, from)}–${"%02d".format(Locale.US, to)}" + if (!b.complete) " (so far)" else "",
                    b.deltaMs?.let { signedDuration(it) } ?: formatDuration(b.actualMs),
                    b.actualMs.toFloat() / maxBlock,
                    color = if ((b.deltaMs ?: 0) > 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                    caption = "${formatDuration(b.actualMs)}" + (b.medianMs?.let { " vs usual ${formatDuration(it)}" } ?: ""),
                )
            }
        }
        if (a.triggers.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                SubHeading("What started the ${a.episodeCount} sessions")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    a.triggers.entries.sortedByDescending { it.value }.forEach { (t, n) -> StatusPill("${t.badge} · $n", colors.neutral) }
                }
            }
        }
        a.note?.let { note ->
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(Space.m)) {
                    SubHeading("Your note that day")
                    Text(note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

// -- Rabbit holes ---------------------------------------------------------------------------------------------------------

fun LazyListScope.rabbitHolesSection(
    feed: RabbitHoleFeed?,
    apps: LabApps,
    scope: LabScope,
    filter: RabbitFilter,
    onFilter: (RabbitFilter) -> Unit,
    onOpen: (String) -> Unit,
    onRate: (String, Long, Int?) -> Unit,
) {
    item("rh-filters") { RabbitFilters(feed, apps, filter, onFilter) }
    if (feed == null) return
    item("rh-summary") {
        val w = feed.worth
        Muted(
            "${feed.episodes.size} of ${feed.total} rabbit holes · ${formatDuration(feed.distractingMs)} distracting" +
                (if (w.ratedCount > 0) " · ${w.valuedCount} valued, ${w.regrettedCount} regretted" else "") + " · ${scopeLine(scope)}",
            Modifier.padding(horizontal = Space.s),
        )
    }
    if (feed.episodes.isEmpty()) {
        item("rh-empty") {
            Muted(if (feed.total == 0) "No rabbit holes in this window." else "Nothing matches these filters.", Modifier.padding(horizontal = Space.s))
        }
    }
    items(feed.episodes, key = { "rh-${it.id}" }) { e ->
        RabbitHoleCard(e, apps, onOpen = { onOpen(e.episodeKey) }, onRate = { onRate(e.episodeKey, e.startMs, it) })
    }
}

@Composable
private fun RabbitFilters(feed: RabbitHoleFeed?, apps: LabApps, f: RabbitFilter, onFilter: (RabbitFilter) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        ChipRow {
            // A filter carried over from another window stays visible (and clearable) even when nothing there matches it.
            if (f != RabbitFilter()) {
                AssistChip(onClick = { onFilter(RabbitFilter()) }, label = { Text("Clear filters") })
            }
            RatingFilter.entries.forEach { r ->
                FilterChip(selected = f.rating == r, onClick = { onFilter(f.copy(rating = r)) }, label = { Text(r.label) })
            }
        }
        val facets = feed?.facets ?: return@Column
        val reasons = withSelected(facets.reasons, f.reason)
        if (reasons.size > 1 || f.reason != null) {
            ChipRow {
                reasons.forEach { r ->
                    FilterChip(selected = f.reason == r, onClick = { onFilter(f.copy(reason = if (f.reason == r) null else r)) }, label = { Text(reasonChip(r)) })
                }
            }
        }
        val triggers = withSelected(facets.triggers, f.trigger)
        if (triggers.size > 1 || f.trigger != null) {
            ChipRow {
                triggers.forEach { t ->
                    FilterChip(selected = f.trigger == t, onClick = { onFilter(f.copy(trigger = if (f.trigger == t) null else t)) }, label = { Text(t.badge) })
                }
            }
        }
        val appIds = withSelected(facets.apps, f.pkgId)
        if (appIds.size > 1 || f.pkgId != null) {
            // Every app in the window: the row scrolls sideways.
            ChipRow {
                appIds.forEach { p ->
                    FilterChip(selected = f.pkgId == p, onClick = { onFilter(f.copy(pkgId = if (f.pkgId == p) null else p)) }, label = { Text(apps.name(p)) })
                }
            }
        }
    }
}

private fun <T> withSelected(values: List<T>, selected: T?): List<T> =
    if (selected == null || selected in values) values else listOf(selected) + values

private fun reasonChip(r: RabbitHoleReason): String = when (r) {
    RabbitHoleReason.LONG_BINGE -> "Long binge"
    RabbitHoleReason.MULTI_APP_SPIRAL -> "Multi-app spiral"
    RabbitHoleReason.HIJACKED_PICKUP -> "Hijacked pickup"
}

@Composable
fun ChipRow(content: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) { content() }
}

// -- Gateways -------------------------------------------------------------------------------------------------------------

fun LazyListScope.gatewaysSection(data: GatewayData) {
    val r = data.report
    item("gw-head") {
        SectionCard("Gateway apps", eyebrow = "Where spirals start", icon = Icons.Outlined.DoorFront) {
            Text(
                "How often opening an app first led to a rabbit hole, or to 15+ minutes in other distracting apps. Rates are pulled " +
                    "toward your overall ${pct(r.baseRate)} until an app has enough sessions to speak for itself.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Muted("${r.totalSpirals} of ${r.totalEntries} sessions spiralled · ${scopeLine(data.scope)}")
        }
    }
    if (r.ranked.isEmpty()) {
        item("gw-empty") { Muted("No app has started ${r.rules.minEntries} or more sessions in this window yet.", Modifier.padding(horizontal = Space.s)) }
    }
    items(r.ranked, key = { "gw-${it.pkgId}" }) { row -> GatewayCard(row, data.apps, low = false) }
    if (r.lowSample.isNotEmpty()) {
        item("gw-low-head") {
            Muted("Too few sessions to rank (fewer than ${r.rules.minEntries}):", Modifier.padding(horizontal = Space.s, vertical = Space.xs))
        }
        items(r.lowSample.take(LOW_SAMPLE_SHOWN), key = { "gwl-${it.pkgId}" }) { row -> GatewayCard(row, data.apps, low = true) }
    }
}

private const val LOW_SAMPLE_SHOWN = 10

@Composable
private fun GatewayCard(row: GatewayRow, apps: LabApps, low: Boolean) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(Space.m)) {
            BarRow(
                apps.name(row.pkgId),
                if (low) "${row.spirals} of ${row.entries}" else pct(row.shrunkRate),
                if (low) 0f else row.shrunkRate.toFloat(),
                color = MaterialTheme.colorScheme.tertiary,
                caption = (if (low) "" else "${row.spirals} of ${row.entries} sessions spiralled (raw ${pct(row.rawRate)}) · ") +
                    "${formatDuration(row.avgDownstreamMs)} elsewhere per session",
            )
        }
    }
}

// -- Notifications ----------------------------------------------------------------------------------------------------------

fun LazyListScope.alertsSection(data: NotificationCostData, sort: NotificationSort, onSort: (NotificationSort) -> Unit) {
    val r = data.report
    item("al-head") {
        SectionCard("Notification cost", eyebrow = "What alerts cost you", icon = Icons.Outlined.NotificationsActive) {
            Text(
                if (r.coveredMs == 0L) "The notification listener wasn't recording in this window, so there's nothing to measure."
                else "Measured only while the notification listener was recording: ${formatDurationShort(r.coveredMs)} of this window" +
                    (if (r.firstCoveredMs != null && r.lastCoveredMs != null) ", ${formatDay(data.scope.range.start)} – ${formatDay(data.scope.range.end)}" else "") + ".",
                style = MaterialTheme.typography.bodyMedium,
            )
            StatRow {
                StatTile("Notifications", "${r.totalVolume}", Modifier.weight(1f))
                StatTile("Pickup lift", liftText(r.overall), Modifier.weight(1f), caption = liftCaption(r.overall))
            }
            Muted(
                "Pickup lift compares how often you picked up within a minute of an alert with how often you pick up in any minute of " +
                    "waking idle time (${formatDurationShort(r.exposureMs)} observed, ${r.basePickups} pickups).",
            )
        }
    }
    item("al-sort") {
        ChipRow {
            NotificationSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { onSort(s) }, label = { Text(s.label) }) }
        }
    }
    if (r.rows.isEmpty() && r.coveredMs > 0) item("al-empty") { Muted("No notifications recorded in this window.", Modifier.padding(horizontal = Space.s)) }
    items(r.rows, key = { "al-${it.pkgId}" }) { row -> NotificationAppCard(row, data.apps) }
}

private fun liftText(l: PickupLift): String = l.lift?.let { "%.1f×".format(Locale.getDefault(), it) } ?: "—"

private fun liftCaption(l: PickupLift): String = if (l.lift == null) "Need more data" else "${l.alertPickups} of ${l.alerts} alerts"

@Composable
private fun NotificationAppCard(row: NotificationCostRow, apps: LabApps) {
    var expanded by rememberSaveable(row.pkgId) { mutableStateOf(false) }
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.small)
                    .clickable(enabled = row.channels.isNotEmpty()) { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(apps.name(row.pkgId), style = MaterialTheme.typography.titleMedium)
                    Muted(costLine(row))
                }
                if (row.channels.isNotEmpty()) {
                    Icon(
                        if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                        contentDescription = if (expanded) "Hide channels" else "Show ${row.channels.size} channels",
                    )
                }
            }
            if (!expanded) row.target?.let { MuteButton(it, "Notification settings") }
            if (expanded) {
                row.channels.forEach { ch ->
                    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            Text(channelLabel(ch.channelId), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                            Muted(costLine(ch))
                            ch.target?.let {
                                MuteButton(it, if (it is NotificationSettingsTarget.Channel) "Mute channel" else "App notification settings")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun channelLabel(id: String?): String = when {
    id.isNullOrBlank() -> "No channel"
    id.startsWith("conversation:") || id.startsWith("conv:") -> "A conversation (name kept private)"
    id.startsWith("channel:") -> "A channel (name kept private)"
    else -> id
}

private fun costLine(r: NotificationCostRow): String = buildString {
    append("${r.volume} notifications")
    r.clickRate?.let { append(" · ${pct(it)} opened") }
    append(" · lift ${if (r.lift.lift != null) liftText(r.lift) else "need more data"}")
    if (r.downstreamMs > 0) append(" · ${formatDuration(r.downstreamMs)} distracting after")
}

@Composable
private fun MuteButton(target: NotificationSettingsTarget, label: String) {
    val context = LocalContext.current
    OutlinedButton(onClick = {
        if (!NotificationSettingsIntents.open(context, target)) {
            Toast.makeText(context, "Couldn't open notification settings on this device.", Toast.LENGTH_SHORT).show()
        }
    }) {
        Icon(Icons.Outlined.NotificationsOff, null)
        Spacer(Modifier.width(Space.s))
        Text(label)
    }
}

// -- Rhythm ---------------------------------------------------------------------------------------------------------------

fun LazyListScope.rhythmSection(data: RhythmData, metric: HeatMetric, onMetric: (HeatMetric) -> Unit) {
    val r = data.report
    item("rh-map") {
        SectionCard("Your week by hour", eyebrow = "Circadian heatmap", icon = Icons.Outlined.GridOn) {
            ChipRow {
                HeatMetric.entries.forEach { m -> FilterChip(selected = metric == m, onClick = { onMetric(m) }, label = { Text(m.label) }) }
            }
            if (r.coveredDays == 0) {
                Muted("No fully recorded days in this window yet.")
            } else {
                CircadianHeatmapView(r.heatmap, metric)
            }
            Muted("Averages per fully recorded day of each weekday. Hours start at your day's rollover. ${scopeLine(data.scope)}.")
            if (r.heatmap.foldedDstDays > 0) {
                Muted("${r.heatmap.foldedDstDays} day(s) had a repeated clock hour (daylight saving ended); both passes of that hour are shown together in it.")
            }
            if (r.heatmap.unplacedMs > 0) Muted("${formatDuration(r.heatmap.unplacedMs)} from a repeated hour couldn't be placed and is left out.")
        }
    }
    item("rh-sleep") {
        val s = r.sleep
        SectionCard("Nights", eyebrow = "Sleep proxy", icon = Icons.Outlined.Bedtime) {
            StatRow {
                StatTile("Phone-free night", s.overnightGap.overallMs?.let { formatDurationShort(it) } ?: "—", Modifier.weight(1f), caption = trendCaption(s.overnightGap, higherIsBetter = true))
                StatTile(
                    "First pickup",
                    s.firstPickupMinute?.let { "%02d:%02d".format(Locale.US, it / 60, it % 60) } ?: "—",
                    Modifier.weight(1f),
                    caption = "typical, ${s.firstPickupDays} days",
                )
            }
            StatTile(
                "Late night (23:00–04:00)",
                s.lateNight.overallMs?.let { formatDurationShort(it) + " a day" } ?: "—",
                Modifier.fillMaxWidth(),
                caption = trendCaption(s.lateNight, higherIsBetter = false),
            )
            Muted("The longest phone-free stretch around each night, not actual sleep.")
        }
    }
    item("rh-gaps") {
        val g = r.gaps
        SectionCard("Time between pickups", eyebrow = "Inter-pickup gaps", icon = Icons.Outlined.HourglassBottom) {
            if (g.count == 0) {
                Muted("Not enough pickups recorded yet.")
            } else {
                Text("Median gap ${formatDurationShort(g.medianMs ?: 0)} across ${g.count} gaps", style = MaterialTheme.typography.bodyMedium)
                val max = g.buckets.values.maxOrNull()?.coerceAtLeast(1) ?: 1
                GapBucket.entries.forEach { b ->
                    val n = g.buckets[b] ?: 0
                    BarRow(b.label, "$n · ${pct(n.toDouble() / g.count)}", n.toFloat() / max)
                }
                Muted("Within each recorded day; the overnight stretch is left out.")
            }
        }
    }
}

private fun trendCaption(v: RecentVsEarlier, higherIsBetter: Boolean): String {
    val change = v.changeMs ?: return if (v.recentDays > 0) "${v.recentDays + v.earlierDays} days" else "no data yet"
    if (change == 0L) return "same as before"
    val verdict = if ((change > 0) == higherIsBetter) "better" else "worse"
    return "${signedDuration(change)} last ${v.recentDays} days · $verdict"
}

// -- Friction -------------------------------------------------------------------------------------------------------------

fun LazyListScope.frictionSection(data: FrictionData) {
    val r = data.report
    item("fr-exits") {
        val e = r.exits
        SectionCard("After a pause", eyebrow = "Immediate exits", icon = Icons.Outlined.Psychology) {
            if (r.launchPauses == 0) {
                Muted("No launch pauses in this window. Friction may be off, or no paused app was opened.")
            } else {
                Text(
                    "${r.launchPauses} launch pauses" + (r.pausesPerCoveredDay?.let { " (%.1f a day)".format(Locale.getDefault(), it) } ?: "") +
                        "; you left ${e.exits} times.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (e.exits > 0) {
                    StatRow {
                        StatTile("Stayed away", "${e.sustained}", Modifier.weight(1f), caption = "10+ min, recorded")
                        StatTile("Back quickly", "${e.quickReopens}", Modifier.weight(1f), caption = "within 10 min")
                        StatTile("Unknown", "${e.unresolved}", Modifier.weight(1f), caption = "not yet / gap")
                    }
                }
                Muted("\"Stayed away\" only counts when ten minutes were fully recorded with no distracting app reopened.")
            }
        }
    }
    item("fr-timer") {
        val t = r.timer
        SectionCard("Session timers", eyebrow = "Adherence", icon = Icons.Outlined.Timer) {
            if (t.timersSet == 0 && t.expiries == 0) {
                Muted("No timers set in this window.")
            } else {
                StatRow {
                    StatTile("Closed on time", "${t.closedOnExpiry}", Modifier.weight(1f), caption = "of ${t.expiries} expiries")
                    StatTile("+5 min", "${t.extended}", Modifier.weight(1f))
                    StatTile("Kept going", "${t.dismissed}", Modifier.weight(1f))
                }
                Muted("${t.timersSet} timers chosen at launch.")
            }
        }
    }
    item("fr-habit") {
        val h = r.habituation
        SectionCard("Habituation", eyebrow = "Is the pause still noticed?", icon = Icons.Outlined.HourglassBottom) {
            if (h.recentMedianMs == null || h.earlierMedianMs == null) {
                Muted("Need at least 10 continued pauses with a recorded reaction (${h.samples} so far).")
            } else {
                Text(
                    "After the countdown you now tap through in ${formatReaction(h.recentMedianMs)} (earlier: ${formatReaction(h.earlierMedianMs)}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (h.autopilot) {
                    Muted("That's quick enough to be on autopilot. A longer pause or a different prompt can make it register again.")
                }
            }
        }
    }
    item("fr-sub") {
        SectionCard("Where the time went", eyebrow = "Substitution", icon = Icons.Outlined.SwapHoriz) {
            val s = r.substitution
            if (s == null) {
                Muted("Needs at least 3 fully recorded days in both this period and the one before.")
            } else {
                Text(
                    "Distracting ${formatDurationShort(s.distractingBeforeMs)} → ${formatDurationShort(s.distractingNowMs)} a day (${signedDuration(s.distractingChangeMs)}); " +
                        "neutral ${formatDurationShort(s.neutralBeforeMs)} → ${formatDurationShort(s.neutralNowMs)} (${signedDuration(s.neutralChangeMs)}).",
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (s.flagged) {
                    Text(
                        "Distracting time went down while neutral time went up, with friction on. Some of that time may have moved rather than gone.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LocalStatusColors.current.warn,
                    )
                }
                if (s.risingNeutral.isNotEmpty()) {
                    Muted("Rising neutral apps: " + s.risingNeutral.joinToString(", ") { (p, ms) -> "${data.apps.name(p)} ${signedDuration(ms)}/day" })
                }
                Muted("${s.currentDays} recorded days vs ${s.previousDays} before. These are side-by-side minutes, not proof of cause.")
            }
        }
    }
    item("fr-misc") {
        Muted(
            "${r.snoozes} one-hour snoozes" + (r.frictionCoverage?.let { " · friction service running ${pct(it)} of recorded days" } ?: "") +
                " · ${scopeLine(data.scope)}",
            Modifier.padding(horizontal = Space.s),
        )
    }
}

private fun formatReaction(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.1fs".format(Locale.getDefault(), ms / 1000.0)
