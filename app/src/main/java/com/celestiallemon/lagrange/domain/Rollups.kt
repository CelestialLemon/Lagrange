package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.Archetype
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.ServiceKind
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Which zone applied when, from `tz_history`. Every instant is resolved with the zone rules in force at that instant,
 * so a DST change or a trip never shifts history recorded before it.
 */
class ZoneTimeline(entries: List<Pair<Long, ZoneId>>, private val fallback: ZoneId) {
    private val sorted = entries.sortedBy { it.first }
    private val starts = LongArray(sorted.size) { sorted[it].first }

    /** Day starts are asked for over and over (every window, every [dateOf] probe); a timeline is immutable. */
    private val dayStarts = java.util.concurrent.ConcurrentHashMap<Long, Long>()

    fun zoneAt(ms: Long): ZoneId {
        if (sorted.isEmpty()) return fallback
        // Last entry with from <= ms (binary search); before the first entry the first zone applies.
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= ms) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return sorted[if (found < 0) 0 else found].second
    }

    /**
     * The logical day whose window contains [ms]. Windows are canonical: on a westward trip the local clock runs some
     * hours twice, and those hours stay in the (longer) day they started in instead of jumping back a date.
     */
    fun dateOf(ms: Long, rolloverHour: Int): LocalDate {
        var d = localDateOf(ms, rolloverHour)
        repeat(6) {
            val start = dayStartMs(d, rolloverHour)
            val end = dayStartMs(d.plusDays(1), rolloverHour)
            d = when {
                ms < start -> d.minusDays(1)
                ms >= maxOf(start, end) -> d.plusDays(1)
                else -> return d
            }
        }
        return d
    }

    /** The date by [ms]'s own local clock and rollover, ignoring travel (may repeat or skip around a zone change). */
    private fun localDateOf(ms: Long, rolloverHour: Int): LocalDate = LogicalDay.dateOf(ms, rolloverHour, zoneAt(ms))

    /** The first recorded zone change strictly after [ms], or null. */
    fun nextChangeAfter(ms: Long): Long? {
        var current = zoneAt(ms)
        for ((from, z) in sorted) {
            if (from <= ms) continue
            if (z != current) return from
            current = z
        }
        return null
    }

    /**
     * When the logical day [date] begins: the earliest instant whose own zone puts it at the rollover hour on [date].
     * Every recorded zone is tried (never just the device's current one), so windows agree with [dateOf] on travel
     * days. When a zone change skips the rollover entirely (flying east across it), the day begins at the change.
     * A rollover hour inside a DST gap resolves forward.
     */
    fun dayStartMs(date: LocalDate, rolloverHour: Int): Long =
        dayStarts.getOrPut(date.toEpochDay() * 24 + rolloverHour) { computeDayStart(date, rolloverHour) }

    private fun computeDayStart(date: LocalDate, rolloverHour: Int): Long {
        val local = date.atTime(rolloverHour, 0)
        val candidates = ArrayList<Long>()
        for (z in zones) {
            val t = local.atZone(z).toInstant().toEpochMilli()
            if (zoneAt(t) == z) candidates += t
        }
        for ((from, _) in sorted) {
            if (localDateOf(from, rolloverHour) >= date && localDateOf(from - 1, rolloverHour) < date) candidates += from
        }
        candidates.minOrNull()?.let { return it }
        val guess = local.atZone(zoneAt(local.atZone(fallback).toInstant().toEpochMilli())).toInstant().toEpochMilli()
        return local.atZone(zoneAt(guess)).toInstant().toEpochMilli()
    }

    private val zones: List<ZoneId> = (sorted.map { it.second } + fallback).distinct()

    /**
     * The earliest instant in `[fromMs, toMs)` whose own zone (the one in force then) shows the wall time [local],
     * or null when that wall time never happened there (a DST gap, or skipped by travel).
     */
    fun instantOf(local: java.time.LocalDateTime, fromMs: Long, toMs: Long): Long? = zones.mapNotNull { z ->
        val t = local.atZone(z).toInstant().toEpochMilli()
        t.takeIf { it in fromMs until toMs && zoneAt(it) == z && Instant.ofEpochMilli(it).atZone(z).toLocalDateTime() == local }
    }.minOrNull()

    fun window(date: LocalDate, rolloverHour: Int): DayWindow {
        val start = dayStartMs(date, rolloverHour)
        val end = dayStartMs(date.plusDays(1), rolloverHour).coerceAtLeast(start)
        return DayWindow(date, start, end, zoneAt(start))
    }

    companion object {
        fun fixed(zone: ZoneId) = ZoneTimeline(emptyList(), zone)
    }
}

/** One logical day: `[startMs, endMs)`, 23, 24 or 25 hours long. */
data class DayWindow(val date: LocalDate, val startMs: Long, val endMs: Long, val zone: ZoneId) {
    val isWeekend: Boolean get() = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
}

/** Hour slots inside a logical day: 0..23 by local clock hour counted from the rollover, 24 for a repeated fall-back hour. */
object HourSlots {
    fun slotOf(ms: Long, rolloverHour: Int, zone: ZoneId): Int {
        val z = Instant.ofEpochMilli(ms).atZone(zone)
        if (z.offset != z.withEarlierOffsetAtOverlap().offset) return REPEATED_SLOT
        return (z.hour - rolloverHour + 24) % 24
    }

    /**
     * The first instant strictly after [ms] where the local hour changes: the next wall-clock hour (either
     * occurrence, if it repeats) or the next offset transition, whichever comes first. Always moves forward, even
     * across half-hour DST shifts (Lord Howe) where "one hour later on the wall" can lie before [ms].
     */
    fun nextHourBoundary(ms: Long, zone: ZoneId): Long {
        val instant = Instant.ofEpochMilli(ms)
        val local = instant.atZone(zone).toLocalDateTime().truncatedTo(ChronoUnit.HOURS).plusHours(1)
        val at = local.atZone(zone)
        val candidates = listOfNotNull(
            at.withEarlierOffsetAtOverlap().toInstant().toEpochMilli(),
            at.withLaterOffsetAtOverlap().toInstant().toEpochMilli(),
            zone.rules.nextTransition(instant)?.instant?.toEpochMilli(),
        )
        return candidates.filter { it > ms }.minOrNull() ?: (ms + HOUR_MS)
    }

    /** The ordinary slot the repeated (slot 24) hour shares a wall-clock hour with on this day, or null when nothing repeats. */
    fun repeatedSlot(window: DayWindow, rolloverHour: Int): Int? {
        val t = window.zone.rules.nextTransition(Instant.ofEpochMilli(window.startMs)) ?: return null
        if (!t.isOverlap || t.instant.toEpochMilli() >= window.endMs) return null
        return (t.dateTimeAfter.hour - rolloverHour + 24) % 24
    }

    const val REPEATED_SLOT = 24
}

// -----------------------------------------------------------------------------------------------------------------
// Daily rollups.
// -----------------------------------------------------------------------------------------------------------------

/** Plain mirror of a `daily_rollups` row. */
data class DayRollup(
    val logicalDate: LocalDate,
    val coverage: DayCoverage,
    val notifCoverageRatio: Double,
    val frictionCoverageRatio: Double,
    val usageCoverageRatio: Double,
    val totalScreenMs: Long,
    val activeUsageMs: Long,
    val distractingMs: Long,
    val neutralMs: Long,
    val utilityMs: Long,
    val excludedMs: Long,
    val selfAppMs: Long,
    val episodeCount: Int,
    val physicalPickupCount: Int,
    val glanceCount: Int,
    val microCheckCount: Int,
    val rabbitHoleCount: Int,
    val blockedAttemptCount: Int,
    val notificationCount: Int,
    val longestFocusGapMs: Long,
    val overnightGapMs: Long?,
    val firstPickupMs: Long?,
    val lastActivityMs: Long?,
    val budgetTargetMs: Long?,
    /** Usage collection was continuous for the day: it can count toward baselines (zero use included). */
    val baselineEligible: Boolean,
    val baselineMedianMs: Long? = null,
    val dayStatus: DayStatus = if (baselineEligible) DayStatus.INSUFFICIENT_BASELINE else DayStatus.UNCERTAIN_COVERAGE,
)

data class AppDay(
    val logicalDate: LocalDate,
    val pkgId: Long,
    val totalMs: Long,
    val openCount: Int,
    val blockedAttemptCount: Int,
    val sessionCount: Int,
    val rabbitHoleContributionMs: Long,
)

data class HourCell(val logicalDate: LocalDate, val hourSlot: Int, val pkgId: Long, val durationMs: Long, val openCount: Int)

data class DayResult(val rollup: DayRollup, val apps: List<AppDay>, val hourly: List<HourCell>)

data class RollupInput(
    val windows: List<DayWindow>,
    /** Every episode that overlaps the windows, plus the evening before the first one (for the overnight gap). */
    val episodes: List<Episode>,
    val apps: Map<Long, AppInfo>,
    val coverage: List<CoverageSpan>,
    val notifications: List<NotificationMeta>,
    val rolloverHour: Int,
    /** The ledger is complete up to here; today's figures stop at this instant. */
    val dataEndMs: Long,
    val budgetFor: (LocalDate) -> Long? = { null },
    /** Which zone applied when; hour slots follow it through mid-day travel. Defaults to each window's zone. */
    val zones: ZoneTimeline? = null,
)

data class RollupRules(
    /** Below this share of harvested time a day is "uncertain" and never feeds a baseline. */
    val minUsageCoverage: Double = 0.95,
    val minNotifCoverage: Double = 0.90,
    /** A gap is this day's sleep proxy when its midpoint falls in [rollover - before, rollover + after): 22:00-10:00 by default. */
    val nightBeforeRolloverMs: Long = 6 * HOUR_MS,
    val nightAfterRolloverMs: Long = 6 * HOUR_MS,
    val minOvernightGapMs: Long = 2 * HOUR_MS,
)

/**
 * Builds `daily_rollups`, `daily_app_rollups` and `hourly_app_usage` from reconstructed episodes (spec §3.1, §3.3).
 * Episodes are *counted* on the logical day they start; *durations* are split exactly across logical-day and local
 * hour boundaries, so a 23:45–00:40 binge stays one session on that night's timeline while every minute lands in
 * the hour it happened (23- and 25-hour DST days included).
 */
object RollupEngine {
    private val ACTIVE = setOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY)

    fun compute(input: RollupInput, rules: RollupRules = RollupRules()): List<DayResult> {
        val nonGlance = input.episodes.filter { !it.isGlanceOnly }.sortedBy { it.startMs }
        return input.windows.mapNotNull { w -> computeDay(w, input, nonGlance, rules) }
    }

    private fun computeDay(w: DayWindow, input: RollupInput, nonGlance: List<Episode>, rules: RollupRules): DayResult? {
        val clip = minOf(w.endMs, input.dataEndMs)
        if (clip <= w.startMs) return null
        val len = (clip - w.startMs).toDouble()
        fun spansOf(kind: ServiceKind, vararg status: CoverageStatus) =
            input.coverage.filter { it.kind == kind && it.status in status }.map { it.span }

        val usageCov = Spans.coveredMs(spansOf(ServiceKind.USAGE_HARVEST, CoverageStatus.ACTIVE, CoverageStatus.BACKFILL), w.startMs, clip) / len
        val gap = spansOf(ServiceKind.USAGE_HARVEST, CoverageStatus.GAP).any { it.overlapMs(w.startMs, clip) > 0 }
        val backfill = spansOf(ServiceKind.USAGE_HARVEST, CoverageStatus.BACKFILL).any { it.overlapMs(w.startMs, clip) > 0 }
        val notifCov = Spans.coveredMs(spansOf(ServiceKind.NOTIFICATION_LISTENER, CoverageStatus.ACTIVE), w.startMs, clip) / len
        val frictionCov = Spans.coveredMs(spansOf(ServiceKind.ACCESSIBILITY, CoverageStatus.ACTIVE), w.startMs, clip) / len
        val eligible = usageCov >= rules.minUsageCoverage && !gap
        val coverage = when {
            backfill -> DayCoverage.BACKFILL
            !eligible -> DayCoverage.PARTIAL_HARVEST
            notifCov < rules.minNotifCoverage -> DayCoverage.NO_NOTIF_COVERAGE
            else -> DayCoverage.COMPLETE
        }

        val zones = input.zones ?: ZoneTimeline.fixed(w.zone)
        // Durations, split by day and hour.
        var screen = 0L
        var totals = CategoryTotals()
        val appMs = HashMap<Long, Long>()
        val appRabbit = HashMap<Long, Long>()
        val hourly = HashMap<Pair<Int, Long>, LongArray>() // (slot, pkg) -> [ms, opens]
        for (ep in input.episodes) {
            if (ep.endMs <= w.startMs || ep.startMs >= w.endMs) continue
            for (s in ep.activeSpans) screen += s.overlapMs(w.startMs, clip)
            for (seg in ep.segments) {
                val cat = input.apps[seg.pkgId]?.category ?: AppCategory.NEUTRAL
                for (piece in seg.effectiveSpans()) {
                    val part = piece.clip(w.startMs, clip) ?: continue
                    totals = totals.plus(cat, part.durationMs)
                    appMs.merge(seg.pkgId, part.durationMs, Long::plus)
                    if (ep.isRabbitHole) appRabbit.merge(seg.pkgId, part.durationMs, Long::plus)
                    forEachHour(part, zones, input.rolloverHour) { slot, ms ->
                        hourly.getOrPut(slot to seg.pkgId) { LongArray(2) }[0] += ms
                    }
                }
            }
        }

        // Counts, on the day each episode (or open) starts.
        val starting = input.episodes.filter { it.startMs >= w.startMs && it.startMs < w.endMs }
        val startingUnlocked = starting.filter { !it.isGlanceOnly }
        val appOpens = HashMap<Long, Int>()
        val appBlocked = HashMap<Long, Int>()
        val appSessions = HashMap<Long, Int>()
        for (ep in startingUnlocked) {
            val pkgs = HashSet<Long>()
            for (seg in ep.segments) {
                if (seg.isBlockedAttempt) appBlocked.merge(seg.pkgId, 1, Int::plus)
                if (seg.effectiveMs > 0) pkgs += seg.pkgId
            }
            pkgs.forEach { appSessions.merge(it, 1, Int::plus) }
        }
        // Opens are attributed where the open happened, which can be the next day for a session crossing midnight.
        for (ep in input.episodes) {
            if (ep.isGlanceOnly) continue
            for (seg in ep.segments) {
                if (seg.isBlockedAttempt || isTransient(input.apps[seg.pkgId]) || seg.effectiveMs == 0L) continue
                val at = seg.effectiveSpans().firstOrNull()?.startMs ?: continue
                if (at < w.startMs || at >= w.endMs) continue
                appOpens.merge(seg.pkgId, 1, Int::plus)
                val slot = HourSlots.slotOf(at, input.rolloverHour, zones.zoneAt(at))
                hourly.getOrPut(slot to seg.pkgId) { LongArray(2) }[1] += 1
            }
        }

        val firstPickup = startingUnlocked.minOfOrNull { it.startMs }
        val lastActivity = startingUnlocked.maxOfOrNull { it.endMs }
        val overnight = overnightGap(nonGlance, w, clip, rules)
        val sortedDay = startingUnlocked.sortedBy { it.startMs }
        var longest = 0L
        for (i in 1 until sortedDay.size) {
            val g = sortedDay[i].startMs - sortedDay[i - 1].endMs
            if (g > longest && !(overnight != null && g == overnight.second && sortedDay[i].startMs == overnight.first)) longest = g
        }

        val rollup = DayRollup(
            logicalDate = w.date,
            coverage = coverage,
            notifCoverageRatio = notifCov.coerceIn(0.0, 1.0),
            frictionCoverageRatio = frictionCov.coerceIn(0.0, 1.0),
            usageCoverageRatio = usageCov.coerceIn(0.0, 1.0),
            totalScreenMs = screen,
            activeUsageMs = totals.distractingMs + totals.neutralMs + totals.utilityMs,
            distractingMs = totals.distractingMs,
            neutralMs = totals.neutralMs,
            utilityMs = totals.utilityMs,
            excludedMs = totals.excludedMs,
            selfAppMs = totals.selfAppMs,
            episodeCount = startingUnlocked.size,
            physicalPickupCount = startingUnlocked.sumOf { it.physicalPickupCount },
            glanceCount = starting.count { it.isGlanceOnly },
            microCheckCount = startingUnlocked.count { it.archetype == Archetype.MICRO_CHECK },
            rabbitHoleCount = startingUnlocked.count { it.isRabbitHole },
            blockedAttemptCount = startingUnlocked.sumOf { it.blockedAttemptCount },
            notificationCount = input.notifications.count { it.isTriggerEligible && it.postedAtMs >= w.startMs && it.postedAtMs < w.endMs },
            longestFocusGapMs = longest,
            overnightGapMs = overnight?.second,
            firstPickupMs = firstPickup,
            lastActivityMs = lastActivity,
            budgetTargetMs = input.budgetFor(w.date),
            baselineEligible = eligible,
        )
        val pkgs = appMs.keys + appOpens.keys + appBlocked.keys
        val apps = pkgs.map { p ->
            AppDay(w.date, p, appMs[p] ?: 0, appOpens[p] ?: 0, appBlocked[p] ?: 0, appSessions[p] ?: 0, appRabbit[p] ?: 0)
        }.sortedBy { it.pkgId }
        val cells = hourly.map { (k, v) -> HourCell(w.date, k.first, k.second, v[0], v[1].toInt()) }
            .sortedWith(compareBy({ it.hourSlot }, { it.pkgId }))
        return DayResult(rollup, apps, cells)
    }

    /** Active categories (counted in Active Phone Time): everything but EXCLUDED and SELF_APP. */
    fun isActiveCategory(c: AppCategory) = c in ACTIVE

    private fun isTransient(app: AppInfo?) = app?.isSystemSurface == true

    /**
     * Sleep proxy: the longest phone-free stretch between two unlocked episodes whose midpoint lies in the night around
     * this day's rollover (by default 22:00 → 10:00) and that has already ended. The windows of consecutive days never
     * overlap, so one gap is never one day's sleep and the next day's too, and a long morning at work doesn't count.
     * Returns (end, length).
     */
    private fun overnightGap(sorted: List<Episode>, w: DayWindow, clip: Long, rules: RollupRules): Pair<Long, Long>? {
        val nightFrom = w.startMs - rules.nightBeforeRolloverMs
        val nightTo = w.startMs + rules.nightAfterRolloverMs
        var best: Pair<Long, Long>? = null
        var prevEnd = Long.MIN_VALUE
        for (ep in sorted) {
            if (prevEnd != Long.MIN_VALUE) {
                val gapStart = prevEnd
                val gapEnd = ep.startMs
                val len = gapEnd - gapStart
                val mid = gapStart + len / 2
                if (gapEnd <= clip && len >= rules.minOvernightGapMs && mid >= nightFrom && mid < nightTo) {
                    if (best == null || len > best.second) best = gapEnd to len
                }
            }
            prevEnd = maxOf(prevEnd, ep.endMs)
        }
        return best
    }

    /**
     * Splits [span] at local hour boundaries and at zone changes (travel), reporting each piece with the slot of the
     * zone in force at that instant.
     */
    inline fun forEachHour(span: Span, zones: ZoneTimeline, rolloverHour: Int, action: (slot: Int, ms: Long) -> Unit) {
        var t = span.startMs
        while (t < span.endMs) {
            val zone = zones.zoneAt(t)
            val zoneChange = zones.nextChangeAfter(t) ?: Long.MAX_VALUE
            val next = minOf(span.endMs, HourSlots.nextHourBoundary(t, zone), zoneChange).coerceAtLeast(t + 1)
            action(HourSlots.slotOf(t, rolloverHour, zone), next - t)
            t = next
        }
    }
}

// -----------------------------------------------------------------------------------------------------------------
// Baselines and day status.
// -----------------------------------------------------------------------------------------------------------------

/** What a day contributes to baselines. */
data class BaselinePoint(val date: LocalDate, val eligible: Boolean, val activeMs: Long, val distractingMs: Long, val budgetMs: Long?)

data class Baseline(val medianMs: Long?, val coveredDays: Int, val matchedDays: List<LocalDate>)

data class BaselineRules(
    val windowDays: Int = 28,
    /** Baselines stay hidden until this many covered days exist in the window. */
    val minCoveredDays: Int = 7,
    /** ...and at least this many of them match the day's weekday/weekend type. */
    val minMatchedDays: Int = 3,
    val spikeRatio: Double = 1.4,
    val spikeMinMs: Long = 30 * MINUTE_MS,
    val goodRatio: Double = 0.75,
    val goodMinMs: Long = 20 * MINUTE_MS,
    val partialSpikeMinMs: Long = 15 * MINUTE_MS,
    val partialGoodMinMs: Long = 10 * MINUTE_MS,
)

/**
 * Fair comparisons (spec §4.2 A/C): a day is measured against the median of the previous 28 days *of the same kind*
 * (weekday vs weekend), counting only days whose collection was continuous. Confirmed zero-use days count; uncertain
 * days never do.
 */
object Baselines {
    /** Covered days a baseline needs before it is shown at all. */
    val MIN_COVERED_DAYS get() = BaselineRules().minCoveredDays

    fun isWeekend(date: LocalDate) = date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY

    fun baseline(date: LocalDate, points: Map<LocalDate, BaselinePoint>, rules: BaselineRules = BaselineRules()): Baseline {
        val prior = (1..rules.windowDays).mapNotNull { points[date.minusDays(it.toLong())] }.filter { it.eligible }
        val matched = prior.filter { isWeekend(it.date) == isWeekend(date) }
        val median = if (prior.size >= rules.minCoveredDays && matched.size >= rules.minMatchedDays) median(matched.map { it.activeMs }) else null
        return Baseline(median, prior.size, matched.map { it.date })
    }

    /** Status of a finished day. */
    fun status(point: BaselinePoint, baseline: Baseline, rules: BaselineRules = BaselineRules()): DayStatus {
        if (!point.eligible) return DayStatus.UNCERTAIN_COVERAGE
        val m = baseline.medianMs ?: return DayStatus.INSUFFICIENT_BASELINE
        val a = point.activeMs
        return when {
            a >= m * rules.spikeRatio && a - m >= rules.spikeMinMs -> DayStatus.SPIKE
            a <= m * rules.goodRatio && m - a >= rules.goodMinMs -> DayStatus.GOOD_DAY
            point.budgetMs != null && point.distractingMs <= point.budgetMs && a <= m -> DayStatus.GOOD_DAY
            else -> DayStatus.NORMAL
        }
    }

    /** Status of today so far, against the same time-of-day slice of comparable days. */
    fun partialStatus(eligible: Boolean, todayMs: Long, sliceMedianMs: Long?, rules: BaselineRules = BaselineRules()): DayStatus {
        if (!eligible) return DayStatus.UNCERTAIN_COVERAGE
        val m = sliceMedianMs ?: return DayStatus.INSUFFICIENT_BASELINE
        return when {
            todayMs >= m * rules.spikeRatio && todayMs - m >= rules.partialSpikeMinMs -> DayStatus.SPIKE
            todayMs <= m * rules.goodRatio && m - todayMs >= rules.partialGoodMinMs -> DayStatus.GOOD_DAY
            else -> DayStatus.NORMAL
        }
    }

    fun median(values: List<Long>): Long? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}

/** Where "now" sits inside today, by local wall-clock hour counted from the rollover. */
data class SliceCutoff(val slot: Int, val fraction: Double)

data class SliceBaseline(val todayMs: Long, val medianMs: Long?, val days: Int, val cutoff: SliceCutoff)

/**
 * Today-in-progress comparison (spec §4.2 C): usage so far today vs. the same time-of-day slice of prior eligible
 * weekday/weekend days, built from their hourly buckets (the current hour pro-rated).
 */
object PartialDayBaseline {
    /**
     * On logical day [date], the current hour up to the wall-clock minute [now] shows today: `[hour start, cut)` as
     * instants, in the zone in force at that moment of that day. After midnight but before the rollover the minute
     * falls on the next calendar date. Null when that minute never happened that day (a spring-forward gap) or the
     * hour has no length.
     */
    /** Whether the wall clock [now] reads a time inside logical day [today] at all (not before its own rollover after flying west). */
    fun comparable(now: java.time.LocalDateTime, today: LocalDate, rolloverHour: Int): Boolean =
        now >= today.atTime(rolloverHour, 0) && now < today.plusDays(1).atTime(rolloverHour, 0)

    fun currentHourOn(date: LocalDate, now: java.time.LocalDateTime, rolloverHour: Int, zones: ZoneTimeline): Span? {
        val local = (if (now.hour < rolloverHour) date.plusDays(1) else date).atTime(now.toLocalTime())
        val w = zones.window(date, rolloverHour)
        // Checked against the zone actually in force at each candidate instant, inside that day's window.
        val cut = zones.instantOf(local, w.startMs, w.endMs) ?: return null
        val zone = zones.zoneAt(cut)
        val hourStart = minOf(cut, maxOf(w.startMs, local.truncatedTo(ChronoUnit.HOURS).atZone(zone).toInstant().toEpochMilli()))
        return if (cut > hourStart) Span(hourStart, cut) else null
    }

    fun cutoff(nowMs: Long, rolloverHour: Int, zone: ZoneId): SliceCutoff {
        val z = Instant.ofEpochMilli(nowMs).atZone(zone)
        val slot = (z.hour - rolloverHour + 24) % 24
        val fraction = (z.minute * 60 + z.second) / 3600.0
        return SliceCutoff(slot, fraction)
    }

    /** Usage in [slots] (slot → ms) before [cutoff]; slot 24 counts where its repeated wall-clock hour falls. */
    fun sliceMs(slots: Map<Int, Long>, cutoff: SliceCutoff, repeatedSlot: Int?): Long {
        var total = 0.0
        for ((slot, ms) in slots) {
            val wall = if (slot == HourSlots.REPEATED_SLOT) repeatedSlot ?: continue else slot
            total += when {
                wall < cutoff.slot -> ms.toDouble()
                wall == cutoff.slot -> ms * cutoff.fraction
                else -> 0.0
            }
        }
        return total.toLong()
    }

    fun compare(
        today: LocalDate,
        todayMs: Long,
        cutoff: SliceCutoff,
        priorSlices: Map<LocalDate, Long>,
        points: Map<LocalDate, BaselinePoint>,
        rules: BaselineRules = BaselineRules(),
    ): SliceBaseline {
        val base = Baselines.baseline(today, points, rules)
        val slices = base.matchedDays.mapNotNull { priorSlices[it] }
        val median = if (base.medianMs != null && slices.size >= rules.minMatchedDays) Baselines.median(slices) else null
        return SliceBaseline(todayMs, median, slices.size, cutoff)
    }
}
