package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.Span
import com.celestiallemon.lagrange.domain.Spans
import kotlin.math.exp

// Notification Cost (spec §4.2 C, §5.4). Every figure is computed only over time the notification listener was
// actually recording: a notification posted while the listener was off simply isn't in the sample, and time without
// coverage is never treated as "no notifications".

/** A `raw_notifications` row's metadata (never content). */
data class LabNotification(
    val id: Long,
    val pkgId: Long,
    val channelId: String?,
    val postedAtMs: Long,
    val removedAtMs: Long?,
    val removalReason: RemovalReason,
    val importance: Int = 3,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val isGroupSummary: Boolean = false,
    val isAmbient: Boolean = false,
    val matchedInterruptionFilter: Boolean = true,
    val dndOrSilentAtPost: Boolean = false,
) {
    /** Counted in volume: a real notification, not an ongoing/foreground-service one or a group's summary row. */
    val isCountable: Boolean get() = !isOngoing && !isForegroundService && !isGroupSummary

    /** Could plausibly have buzzed, rung or lit the screen (same rule as trigger attribution). */
    val isAlerting: Boolean
        get() = isCountable && importance >= 3 && matchedInterruptionFilter && !isAmbient && !dndOrSilentAtPost
}

data class NotificationRules(
    val pickupWindowMs: Long = 60_000,
    /** Idle gaps this long or longer are sleep or away time, not "waking" idle time. */
    val wakingGapMaxMs: Long = 3 * HOUR_MS,
    /** Fewer alerting notifications than this → no lift ("Need more data"). */
    val minAlerts: Int = 10,
    /** Fewer idle pickups than this, or less exposure than [minExposureMs], → no base rate. */
    val minBasePickups: Int = 5,
    val minExposureMs: Long = 2 * HOUR_MS,
    // Segment-level attribution windows: the reconstructor's own (spec §4.1.3).
    val clickBeforeMs: Long = 5_000,
    val clickAfterMs: Long = 15_000,
    val appCancelAfterMs: Long = 10_000,
    val alertBeforeMs: Long = 60_000,
)

/** Pickup lift: P(pickup within 60 s | alerting notification) ÷ P(pickup in a random 60 s of waking idle time). */
data class PickupLift(
    val lift: Double?,
    val alertPickupRate: Double?,
    val baseRate: Double?,
    val alerts: Int,
    val alertPickups: Int,
    /** Why [lift] is null. */
    val needMoreData: String? = null,
)

/** One row of the table: a whole app, or one of its channels. */
data class NotificationCostRow(
    val pkgId: Long,
    /** Null for the app row (and for notifications without a channel). */
    val channelId: String?,
    val volume: Int,
    val clicks: Int,
    val alerting: Int,
    val lift: PickupLift,
    /** Distracting time after a notification of this row opened (or plausibly prompted) an episode or a hop. */
    val downstreamMs: Long,
    val attributed: Int,
    val channels: List<NotificationCostRow> = emptyList(),
    val target: NotificationSettingsTarget? = null,
) {
    val clickRate: Double? get() = if (volume == 0) null else clicks.toDouble() / volume
}

enum class NotificationSort(val label: String) {
    VOLUME("Volume"),
    DOWNSTREAM("Distracting after"),
    LIFT("Pickup lift"),
}

data class NotificationCostReport(
    val rows: List<NotificationCostRow>,
    /** Listener-covered time inside the window. */
    val coveredMs: Long,
    val firstCoveredMs: Long?,
    val lastCoveredMs: Long?,
    val totalVolume: Int,
    val overall: PickupLift,
    /** Waking idle time the base rate came from. */
    val exposureMs: Long,
    val basePickups: Int,
)

/** Where "Mute channel" goes. A pseudonymized or missing channel can only open the app's notification settings. */
sealed interface NotificationSettingsTarget {
    val packageName: String

    data class Channel(override val packageName: String, val channelId: String) : NotificationSettingsTarget
    data class App(override val packageName: String) : NotificationSettingsTarget
}

object ChannelSettings {
    /** Prefixes the recorder uses for keyed pseudonyms (`NotificationRecorder`), plus the short legacy form. */
    val PSEUDONYM_PREFIXES = listOf("conversation:", "conv:", "channel:")

    fun isRealChannel(channelId: String?): Boolean =
        !channelId.isNullOrBlank() && PSEUDONYM_PREFIXES.none { channelId.startsWith(it) }

    fun target(packageName: String, channelId: String?): NotificationSettingsTarget =
        if (isRealChannel(channelId)) NotificationSettingsTarget.Channel(packageName, channelId!!) else NotificationSettingsTarget.App(packageName)
}

object NotificationCostAnalyzer {
    val ATTRIBUTING = setOf(TriggerEvidence.OBSERVED_CLICK, TriggerEvidence.LIKELY_APP_CANCEL_CLICK, TriggerEvidence.RECENT_POSSIBLE_ALERT)

    /**
     * @param notifications trigger-relevant notifications posted in `[fromMs, toMs)` (any coverage; filtered here).
     * @param listener spans the notification listener was ACTIVE.
     * @param episodes unlocked episodes overlapping the window (plus a little before, for idle gaps), any order.
     * @param reserved notifications lockscreen glances consumed as their trigger: never matched to a switch, though
     *   the glances themselves count neither as pickups nor as downstream use.
     */
    fun analyze(
        notifications: List<LabNotification>,
        listener: List<Span>,
        episodes: List<LabEpisode>,
        fromMs: Long,
        toMs: Long,
        dataEndMs: Long,
        apps: LabApps,
        sort: NotificationSort = NotificationSort.VOLUME,
        rules: NotificationRules = NotificationRules(),
        /** Where usage collection was observing (null: assume everywhere). */
        usage: List<Span>? = null,
        reserved: Set<Long> = emptySet(),
    ): NotificationCostReport {
        val end = minOf(toMs, dataEndMs)
        val covered = notifications.filter { it.postedAtMs in fromMs until end && Spans.contains(listener, it.postedAtMs) && it.isCountable }
        // Credit is resolved against every candidate the reconstructor could see (including ones posted before the
        // window or outside listener coverage) and only then narrowed to this cohort; open sessions can't be credited yet.
        val candidates = notifications.filter { it.postedAtMs < end && it.isCountable }
        val matches = attribute(candidates, episodes.filter { !it.inProgress && it.endMs <= dataEndMs }, apps, rules, reserved)
        // A tap that opened a launch-paused app was still a tap; only the downstream credit is withheld.
        val likelyAppCancel = matches.filter { it.evidence == TriggerEvidence.LIKELY_APP_CANCEL_CLICK }.map { it.notificationId }.toSet()
        val attributions = matches.filter { !it.blockedAttempt }
        fun clicked(n: LabNotification) = n.removalReason == RemovalReason.CLICK || (n.removalReason == RemovalReason.APP_CANCEL && n.id in likelyAppCancel)

        val idle = PickupBase.of(episodes, listener, fromMs, end, rules, usage)
        val alertHits = covered.filter { it.isAlerting }.mapNotNull { n -> idle.alertOutcome(n.postedAtMs)?.let { n.id to it } }.toMap()
        val downstreamById = attributions.groupBy { it.notificationId }.mapValues { (_, a) -> a.sumOf { it.downstreamMs } }

        fun row(pkg: Long, channel: String?, ns: List<LabNotification>, channels: List<NotificationCostRow> = emptyList(), target: NotificationSettingsTarget? = null): NotificationCostRow {
            val alerts = ns.filter { it.id in alertHits }
            return NotificationCostRow(
                pkgId = pkg,
                channelId = channel,
                volume = ns.size,
                clicks = ns.count(::clicked),
                alerting = ns.count { it.isAlerting },
                lift = idle.lift(alerts.size, alerts.count { alertHits[it.id] == true }, rules),
                downstreamMs = ns.sumOf { downstreamById[it.id] ?: 0L },
                attributed = ns.count { it.id in downstreamById },
                channels = channels,
                target = target,
            )
        }

        val rows = covered.groupBy { it.pkgId }.map { (pkg, ns) ->
            val pkgName = apps.packageName(pkg)
            val channels = ns.groupBy { it.channelId }.map { (ch, cns) ->
                row(pkg, ch, cns, target = pkgName?.let { ChannelSettings.target(it, ch) })
            }.sortedWith(compareByDescending<NotificationCostRow> { it.volume }.thenBy { it.channelId.orEmpty() })
            row(pkg, null, ns, channels, pkgName?.let { NotificationSettingsTarget.App(it) })
        }
        val sorted = when (sort) {
            NotificationSort.VOLUME -> rows.sortedWith(compareByDescending<NotificationCostRow> { it.volume }.thenByDescending { it.downstreamMs }.thenBy { it.pkgId })
            NotificationSort.DOWNSTREAM -> rows.sortedWith(compareByDescending<NotificationCostRow> { it.downstreamMs }.thenByDescending { it.volume }.thenBy { it.pkgId })
            NotificationSort.LIFT -> rows.sortedWith(compareByDescending<NotificationCostRow> { it.lift.lift ?: -1.0 }.thenByDescending { it.volume }.thenBy { it.pkgId })
        }
        val coveredSpans = listener.mapNotNull { it.clip(fromMs, end) }.sortedBy { it.startMs }
        val allAlerts = covered.filter { it.id in alertHits }
        return NotificationCostReport(
            rows = sorted,
            coveredMs = Spans.coveredMs(listener, fromMs, end),
            firstCoveredMs = coveredSpans.firstOrNull()?.startMs,
            lastCoveredMs = coveredSpans.maxOfOrNull { it.endMs },
            totalVolume = covered.size,
            overall = idle.lift(allAlerts.size, allAlerts.count { alertHits[it.id] == true }, rules),
            exposureMs = idle.exposureMs,
            basePickups = idle.pickups,
        )
    }

    /**
     * A notification credited with opening (or plausibly prompting) an episode or a hop within one. With
     * [blockedAttempt] the hop was a launch-paused open: the reconstructor still consumed the notification for it, but
     * nothing happened downstream, so it owns no time ([downstreamMs] is 0) and doesn't bound the stretch before it.
     */
    data class Attribution(
        val notificationId: Long,
        val episodeId: Long,
        val evidence: TriggerEvidence,
        val fromMs: Long,
        val downstreamMs: Long,
        val blockedAttempt: Boolean = false,
    )

    /**
     * Session-level: the episode's own stored trigger notification (its entry segment mirrors that trigger, so it is
     * never matched a second time). Segment-level: a hop to another app, matched like the reconstructor's switch rule
     * (a tap on a notification of the trigger app removed within [-5 s, +15 s] of the hop and before the segment ended,
     * the app's own cancel within 10 s after it, or its alert within 60 s before it), each notification credited at
     * most once overall. Within an episode every distracting segment has one owner: the latest credit at or before
     * it, so a notification that opened the episode owns the stretch up to the next notification-driven hop.
     * [reserved] holds notifications the reconstructor gave to episodes not passed here (lockscreen glances), so a
     * switch can't take them either. A launch-paused (blocked) hop is matched too, as the reconstructor does, so it
     * uses up its notification; it is returned flagged [Attribution.blockedAttempt] with no downstream time.
     */
    fun attribute(
        notifications: List<LabNotification>,
        episodes: List<LabEpisode>,
        apps: LabApps,
        rules: NotificationRules = NotificationRules(),
        reserved: Set<Long> = emptySet(),
    ): List<Attribution> {
        val byId = notifications.associateBy { it.id }
        val byPkg = notifications.groupBy { it.pkgId }
        val sorted = episodes.sortedWith(compareBy<LabEpisode> { it.startMs }.thenBy { it.id })
        // Stored episode triggers are authoritative and were consumed first by the reconstructor.
        val consumed = HashSet<Long>(reserved)
        for (e in sorted) if (e.triggerEvidence in ATTRIBUTING) e.triggerNotificationId?.let { consumed += it }

        // Like the reconstructor: direct evidence (taps, app self-cancels) is matched everywhere first, so a
        // circumstantial "recent alert" can't use up the notification a later switch was observed tapping.
        val segmentMatch = java.util.IdentityHashMap<LabSegment, Pair<Long, TriggerEvidence>>()
        for (direct in listOf(true, false)) for (e in sorted) {
            val entry = entryOf(e, apps)
            for (seg in e.segments.sortedBy { it.seqIndex }) {
                if (seg === entry) continue
                val ev = seg.triggerEvidence ?: continue
                if (ev !in ATTRIBUTING) continue
                if ((ev != TriggerEvidence.RECENT_POSSIBLE_ALERT) != direct) continue
                val at = seg.startMs
                val match = when (ev) {
                    TriggerEvidence.OBSERVED_CLICK -> byPkg[seg.triggerPkgId ?: seg.pkgId].orEmpty().filter {
                        it.id !in consumed && it.removalReason == RemovalReason.CLICK && it.removedAtMs != null &&
                            it.removedAtMs >= at - rules.clickBeforeMs && it.removedAtMs <= minOf(at + rules.clickAfterMs, seg.endMs)
                    }.minByOrNull { kotlin.math.abs(it.removedAtMs!! - at) }
                    TriggerEvidence.LIKELY_APP_CANCEL_CLICK -> byPkg[seg.pkgId].orEmpty().filter {
                        it.id !in consumed && it.removalReason == RemovalReason.APP_CANCEL && it.postedAtMs < at && it.removedAtMs != null &&
                            it.removedAtMs in at..(at + rules.appCancelAfterMs)
                    }.minByOrNull { it.removedAtMs!! }
                    else -> byPkg[seg.pkgId].orEmpty().filter {
                        it.id !in consumed && it.isAlerting && it.postedAtMs in (at - rules.alertBeforeMs)..at
                    }.maxByOrNull { it.postedAtMs }
                } ?: continue
                consumed += match.id
                segmentMatch[seg] = match.id to ev
            }
        }

        val out = ArrayList<Attribution>()
        for (e in sorted) {
            val credits = ArrayList<Triple<Long, TriggerEvidence, Long>>()
            val sessionNotif = e.triggerNotificationId
            if (sessionNotif != null && e.triggerEvidence in ATTRIBUTING && sessionNotif in byId) credits += Triple(sessionNotif, e.triggerEvidence, e.startMs)
            for (seg in e.segments) segmentMatch[seg]?.let { (id, ev) ->
                if (seg.isBlockedAttempt) out += Attribution(id, e.id, ev, seg.startMs, 0L, blockedAttempt = true)
                else credits += Triple(id, ev, seg.startMs)
            }
            if (credits.isEmpty()) continue
            credits.sortBy { it.third }
            val segs = apps.realSegments(e).filter { apps.isDistracting(it.pkgId) }
            for ((i, c) in credits.withIndex()) {
                val until = credits.getOrNull(i + 1)?.third ?: Long.MAX_VALUE
                val owned = segs.filter { it.startMs >= c.third && it.startMs < until }.sumOf { it.durationMs }
                out += Attribution(c.first, e.id, c.second, c.third, owned)
            }
        }
        return out.sortedWith(compareBy<Attribution> { it.fromMs }.thenBy { it.notificationId })
    }

    /**
     * The segment that carries the episode's own trigger, chosen like the reconstructor's entry: the first real
     * (unblocked, non-system) segment, else, when every open was launch-paused, the first non-system one. Every other
     * segment with stored evidence was a switch the reconstructor matched on its own, blocked or not.
     */
    private fun entryOf(e: LabEpisode, apps: LabApps): LabSegment? {
        val ordered = e.segments.sortedBy { it.seqIndex }
        return ordered.firstOrNull { !it.isBlockedAttempt && it.pkgId == e.entryPkgId }
            ?: ordered.firstOrNull { !it.isBlockedAttempt && !apps.isTransient(it.pkgId) }
            ?: ordered.firstOrNull { it.pkgId == e.entryPkgId }
            ?: ordered.firstOrNull { !apps.isTransient(it.pkgId) }
            ?: ordered.firstOrNull()
    }

    /** Waking idle time between episodes, and whether a notification posted in it was followed by a pickup. */
    class PickupBase private constructor(
        private val idle: List<Span>,
        private val starts: List<Long>,
        private val dataEndMs: Long,
        private val rules: NotificationRules,
        /** Where usage collection was observing (null: everywhere); a pickup can only be seen, or ruled out, there. */
        private val usage: List<Span>?,
        val exposureMs: Long,
        val pickups: Int,
    ) {
        /** P(pickup in a random 60 s of covered waking idle time), from a Poisson rate; null when the sample is thin. */
        val baseRate: Double? =
            if (pickups < rules.minBasePickups || exposureMs < rules.minExposureMs) null
            else (1.0 - exp(-(pickups.toDouble() / exposureMs) * rules.pickupWindowMs)).takeIf { it > 0.0 }

        /** Null when the notification arrived outside covered waking idle time (mid-episode, asleep) or too recently to know. */
        fun alertOutcome(postedAtMs: Long): Boolean? {
            if (postedAtMs + rules.pickupWindowMs > dataEndMs) return null
            if (idle.none { postedAtMs >= it.startMs && postedAtMs < it.endMs }) return null
            val pickup = starts.firstOrNull { it > postedAtMs && it <= postedAtMs + rules.pickupWindowMs }
            // Usage must have been observed from the alert until the pickup (or for the whole minute, for a miss).
            val until = pickup ?: (postedAtMs + rules.pickupWindowMs)
            if (usage != null && !SpanSet.covers(usage, postedAtMs, until)) return null
            return pickup != null
        }

        fun lift(alerts: Int, hits: Int, rules: NotificationRules): PickupLift {
            val pAlert = if (alerts == 0) null else hits.toDouble() / alerts
            val reason = when {
                alerts < rules.minAlerts -> "Need more data: $alerts of ${rules.minAlerts} alerts while idle"
                baseRate == null -> "Need more data: not enough idle time observed yet"
                else -> null
            }
            val lift = if (reason == null && pAlert != null && baseRate != null && baseRate > 0.0) pAlert / baseRate else null
            return PickupLift(lift, pAlert, baseRate, alerts, hits, if (lift == null) reason ?: "Need more data" else null)
        }

        companion object {
            fun of(episodes: List<LabEpisode>, listener: List<Span>, fromMs: Long, toMs: Long, rules: NotificationRules, usage: List<Span>? = null): PickupBase {
                // Idle time counts only where both the listener (alerts seen) and usage collection (pickups seen) ran.
                val observed = SpanSet.merge(if (usage == null) listener else SpanSet.intersect(listener, usage))
                val sorted = episodes.sortedBy { it.startMs }
                val idle = ArrayList<Span>()
                var exposure = 0L
                var pickups = 0
                var lastEnd = sorted.firstOrNull()?.endMs ?: 0L
                for (i in 1 until sorted.size) {
                    val gapStart = lastEnd
                    val next = sorted[i]
                    lastEnd = maxOf(lastEnd, next.endMs)
                    val gap = next.startMs - gapStart
                    if (gap <= 0 || gap >= rules.wakingGapMaxMs) continue
                    val span = Span(gapStart, next.startMs).clip(fromMs, toMs) ?: continue
                    val coveredParts = observed.mapNotNull { it.clip(span.startMs, span.endMs) }
                    idle += coveredParts
                    exposure += coveredParts.sumOf { it.endMs - it.startMs }
                    if (next.startMs < toMs && Spans.contains(observed, next.startMs - 1)) pickups++
                }
                return PickupBase(idle, sorted.map { it.startMs }, toMs, rules, usage?.let(SpanSet::merge), exposure, pickups)
            }
        }
    }
}
