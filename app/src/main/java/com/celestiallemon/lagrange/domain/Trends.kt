package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.ceil

// Multi-horizon trends (spec §4.2 A). Everything here is plain Kotlin over per-day facts the repository reads from
// `daily_rollups` (and `sessions` for self-report), so every rule is tested on the JVM.
//
// The one rule that runs through all of it: a day only feeds an average, a delta or a weekday/weekend split when
// collection was continuous for it ("covered"). A confirmed zero-use day is a covered 0; a day with a harvest gap, a
// day still in progress or a day with no data at all is never treated as 0. Those days are still drawn (muted or
// hatched) so the chart shows what was seen, but they never move a statistic.

/** An inclusive range of logical dates. */
data class DateRange(val start: LocalDate, val end: LocalDate) {
    init {
        require(!end.isBefore(start)) { "range end $end is before start $start" }
    }

    val days: Int get() = (ChronoUnit.DAYS.between(start, end) + 1).toInt()

    operator fun contains(date: LocalDate): Boolean = !date.isBefore(start) && !date.isAfter(end)

    fun dates(): List<LocalDate> = (0 until days).map { start.plusDays(it.toLong()) }

    /** The equal-length range immediately before this one. */
    fun previous(): DateRange = DateRange(start.minusDays(days.toLong()), start.minusDays(1))
}

enum class WindowKind(val label: String, val days: Int?) {
    D7("7D", 7), D30("30D", 30), D90("90D", 90), Y1("1Y", 365), ALL("All", null), CUSTOM("Custom", null)
}

/** A window the user picked: a fixed horizon ending today, all of history, or explicit dates. */
data class TrendWindow(val kind: WindowKind, val customStart: LocalDate? = null, val customEnd: LocalDate? = null) {
    /**
     * The dates this window covers. Fixed horizons end on [today]; All-Time starts at the first day with data;
     * a custom range is kept inside `[earliest or start, today]` and never empty.
     */
    fun resolve(today: LocalDate, earliest: LocalDate?): DateRange = when (kind) {
        WindowKind.ALL -> DateRange(minOf(earliest ?: today, today), today)
        WindowKind.CUSTOM -> {
            val end = minOf(customEnd ?: today, today)
            val start = minOf(customStart ?: end, end)
            DateRange(start, end)
        }
        else -> DateRange(today.minusDays(kind.days!! - 1L), today)
    }

    /** Whether the period-over-period comparison makes sense (All-Time has nothing before it). */
    val hasPrevious: Boolean get() = kind != WindowKind.ALL

    companion object {
        val DEFAULT = TrendWindow(WindowKind.D30)
        fun custom(start: LocalDate, end: LocalDate) = TrendWindow(WindowKind.CUSTOM, minOf(start, end), maxOf(start, end))
    }
}

/** How much a day can be trusted. Only [COVERED] and [BACKFILL] days feed statistics. */
enum class DayQuality {
    /** Continuous collection, live notification data or not. */
    COVERED,

    /** Continuous usage history imported at setup (usage complete; notification data never existed). */
    BACKFILL,

    /** A harvest gap or service downtime: what was seen is real, but it is not the whole day. */
    PARTIAL,

    /** Today: still being lived. */
    IN_PROGRESS,

    /** No rollup exists for this date (before collection began, or not rebuilt yet). */
    NO_DATA;

    val countsAsCovered: Boolean get() = this == COVERED || this == BACKFILL

    companion object {
        /** Classifies a `daily_rollups` row (or its absence). */
        fun of(coverage: DayCoverage?, status: DayStatus?, inProgress: Boolean): DayQuality = when {
            coverage == null && status == null -> NO_DATA
            inProgress -> IN_PROGRESS
            status == DayStatus.UNCERTAIN_COVERAGE || coverage == DayCoverage.PARTIAL_HARVEST -> PARTIAL
            coverage == DayCoverage.BACKFILL -> BACKFILL
            else -> COVERED
        }
    }
}

/** Everything a day contributes to the Trends tab. */
data class DailyMetrics(
    val date: LocalDate,
    val quality: DayQuality,
    val distractingMs: Long = 0,
    val activeMs: Long = 0,
    val screenMs: Long = 0,
    val episodes: Int = 0,
    val pickups: Int = 0,
    val glances: Int = 0,
    val rabbitHoles: Int = 0,
    val worth: WorthTotals = WorthTotals(),
    val budgetMs: Long? = null,
    val dayStatus: DayStatus? = null,
)

/**
 * Self-report split for a set of unlocked episodes, by `worth_it_rating`: 4–5 valued, 1–2 regretted, 3 neutral.
 * Unrated episodes are kept as their own bucket rather than assumed either way.
 */
data class WorthTotals(
    val valuedMs: Long = 0,
    val regrettedMs: Long = 0,
    val neutralMs: Long = 0,
    val unratedMs: Long = 0,
    val valuedCount: Int = 0,
    val regrettedCount: Int = 0,
    val neutralCount: Int = 0,
    val unratedCount: Int = 0,
) {
    val ratedCount: Int get() = valuedCount + regrettedCount + neutralCount
    val ratedMs: Long get() = valuedMs + regrettedMs + neutralMs
    val netValuedMs: Long get() = valuedMs - regrettedMs

    operator fun plus(o: WorthTotals) = WorthTotals(
        valuedMs + o.valuedMs, regrettedMs + o.regrettedMs, neutralMs + o.neutralMs, unratedMs + o.unratedMs,
        valuedCount + o.valuedCount, regrettedCount + o.regrettedCount, neutralCount + o.neutralCount, unratedCount + o.unratedCount,
    )

    companion object {
        enum class Bucket { VALUED, NEUTRAL, REGRETTED, UNRATED }

        fun bucketOf(rating: Int?): Bucket = when (rating) {
            null -> Bucket.UNRATED
            4, 5 -> Bucket.VALUED
            1, 2 -> Bucket.REGRETTED
            3 -> Bucket.NEUTRAL
            else -> Bucket.UNRATED // out-of-range values are never trusted as a rating
        }

        /** Totals for episodes given as (rating, active ms). */
        fun of(episodes: List<Pair<Int?, Long>>): WorthTotals = episodes.fold(WorthTotals()) { acc, (rating, ms) ->
            when (bucketOf(rating)) {
                Bucket.VALUED -> acc.copy(valuedMs = acc.valuedMs + ms, valuedCount = acc.valuedCount + 1)
                Bucket.REGRETTED -> acc.copy(regrettedMs = acc.regrettedMs + ms, regrettedCount = acc.regrettedCount + 1)
                Bucket.NEUTRAL -> acc.copy(neutralMs = acc.neutralMs + ms, neutralCount = acc.neutralCount + 1)
                Bucket.UNRATED -> acc.copy(unratedMs = acc.unratedMs + ms, unratedCount = acc.unratedCount + 1)
            }
        }
    }
}

enum class MetricUnit { DURATION, COUNT }

/**
 * A trend series. [value] is the bar; [secondary] an optional second quantity drawn with it (episodes inside
 * pickups, regretted time below valued time); [trend] the quantity averages and deltas are computed over.
 */
enum class TrendMetric(val label: String, val unit: MetricUnit) {
    DISTRACTING("Distracting", MetricUnit.DURATION),
    ACTIVE_USAGE("Active", MetricUnit.DURATION),
    TOTAL_SCREEN("Screen on", MetricUnit.DURATION),
    EPISODES("Episodes", MetricUnit.COUNT),
    PHYSICAL_PICKUPS("Pickups", MetricUnit.COUNT),
    GLANCES("Glances", MetricUnit.COUNT),
    RABBIT_HOLES("Rabbit holes", MetricUnit.COUNT),
    VALUED_VS_REGRETTED("Valued vs regretted", MetricUnit.DURATION);

    fun value(d: DailyMetrics): Double = when (this) {
        DISTRACTING -> d.distractingMs.toDouble()
        ACTIVE_USAGE -> d.activeMs.toDouble()
        TOTAL_SCREEN -> d.screenMs.toDouble()
        EPISODES -> d.episodes.toDouble()
        PHYSICAL_PICKUPS -> d.pickups.toDouble()
        GLANCES -> d.glances.toDouble()
        RABBIT_HOLES -> d.rabbitHoles.toDouble()
        VALUED_VS_REGRETTED -> d.worth.valuedMs.toDouble()
    }

    fun secondary(d: DailyMetrics): Double? = when (this) {
        PHYSICAL_PICKUPS -> d.episodes.toDouble()
        VALUED_VS_REGRETTED -> d.worth.regrettedMs.toDouble()
        else -> null
    }

    /** What averages, deltas and splits are computed over: net valued time for the self-report series. */
    fun trend(d: DailyMetrics): Double = when (this) {
        VALUED_VS_REGRETTED -> d.worth.netValuedMs.toDouble()
        else -> value(d)
    }

    /** Value drawn below the axis (regretted time) rather than inside the bar. */
    val diverging: Boolean get() = this == VALUED_VS_REGRETTED

    /** The daily distracting budget is a reference line only for the series it limits. */
    val showsBudget: Boolean get() = this == DISTRACTING

    /** Lower is better for every series except net valued time. */
    val higherIsBetter: Boolean get() = this == VALUED_VS_REGRETTED
}

/** One day of a series, ready to draw. */
data class DaySample(
    val date: LocalDate,
    val quality: DayQuality,
    /** Null only when the day has no data at all. */
    val value: Double?,
    val secondary: Double? = null,
    val trend: Double? = value,
    val dayStatus: DayStatus? = null,
)

data class TrendPoint(
    val sample: DaySample,
    /** Mean of [DaySample.trend] over the covered days among the 7 (28) ending here; null with too few of them. */
    val ma7: Double?,
    val ma28: Double?,
    val reference: Double?,
) {
    val date: LocalDate get() = sample.date
}

data class PeriodStats(val range: DateRange, val coveredDays: Int, val mean: Double?, val median: Double?)

/** Current window vs the equal-length window right before it, both over covered days only. */
data class PeriodDelta(
    val current: PeriodStats,
    val previous: PeriodStats?,
    val minCoveredDays: Int,
) {
    /** Both sides have enough covered days for the comparison to mean something. */
    val sufficient: Boolean
        get() = previous != null && current.coveredDays >= minCoveredDays && previous.coveredDays >= minCoveredDays &&
            current.mean != null && previous.mean != null

    val deltaMean: Double? get() = if (sufficient) current.mean!! - previous!!.mean!! else null

    val deltaMedian: Double?
        get() = if (sufficient && current.median != null && previous!!.median != null) current.median - previous.median else null

    /** Relative change of the mean; null when the previous mean is zero (any change from nothing is undefined). */
    val pctChange: Double?
        get() {
            val d = deltaMean ?: return null
            val p = previous!!.mean!!
            return if (p == 0.0) null else d / kotlin.math.abs(p)
        }
}

data class DayAverage(val mean: Double?, val days: Int)

data class WeekSplit(
    val weekday: DayAverage,
    val weekend: DayAverage,
    val byDayOfWeek: Map<DayOfWeek, DayAverage>,
    val coveredDays: Int,
    val minCoveredDays: Int,
) {
    /** Splits stay hidden until enough covered days exist (spec §4.2 A: at least seven). */
    val visible: Boolean get() = coveredDays >= minCoveredDays
}

data class TrendReport(
    val metric: TrendMetric,
    val range: DateRange,
    val points: List<TrendPoint>,
    val delta: PeriodDelta,
    val split: WeekSplit,
    val coveredDays: Int,
    val partialDays: Int,
    val noDataDays: Int,
    val inProgressDays: Int,
    /** Sum of [DaySample.value] over every day with data, partial ones included (it all really happened). */
    val observedTotal: Double,
    val coveredMean: Double?,
    /** Self-report over the window (all days with data). */
    val worth: WorthTotals,
)

data class TrendRules(
    val shortWindow: Int = 7,
    val longWindow: Int = 28,
    /** An average needs at least this many covered days inside its window. */
    val minCoveredShort: Int = 4,
    val minCoveredLong: Int = 14,
    /** A period comparison needs `max(minDeltaDays, ceil(length × minDeltaShare))` covered days on both sides. */
    val minDeltaDays: Int = 3,
    val minDeltaShare: Double = 0.5,
    val minSplitDays: Int = 7,
)

object TrendCalculator {
    /**
     * Rolling mean of `trend` over the covered days among the [window] calendar days ending at each index. [samples]
     * must be consecutive dates. Uncovered days neither count as zero nor shrink the divisor: they are skipped, and
     * an average with fewer than [minCovered] covered days is null.
     */
    fun movingAverage(samples: List<DaySample>, window: Int, minCovered: Int): List<Double?> {
        val out = ArrayList<Double?>(samples.size)
        var sum = 0.0
        var n = 0
        for (i in samples.indices) {
            samples[i].takeIf { it.quality.countsAsCovered }?.trend?.let { sum += it; n++ }
            val drop = i - window
            if (drop >= 0) samples[drop].takeIf { it.quality.countsAsCovered }?.trend?.let { sum -= it; n-- }
            out += if (n >= minCovered && n > 0) sum / n else null
        }
        return out
    }

    fun stats(range: DateRange, samples: Map<LocalDate, DaySample>): PeriodStats {
        val values = range.dates().mapNotNull { d -> samples[d]?.takeIf { it.quality.countsAsCovered }?.trend }
        return PeriodStats(range, values.size, values.takeIf { it.isNotEmpty() }?.average(), median(values))
    }

    fun minDeltaDays(length: Int, rules: TrendRules = TrendRules()): Int =
        maxOf(rules.minDeltaDays, ceil(length * rules.minDeltaShare).toInt())

    fun delta(range: DateRange, samples: Map<LocalDate, DaySample>, withPrevious: Boolean, rules: TrendRules = TrendRules()): PeriodDelta =
        PeriodDelta(
            current = stats(range, samples),
            previous = if (withPrevious) stats(range.previous(), samples) else null,
            minCoveredDays = minDeltaDays(range.days, rules),
        )

    fun split(range: DateRange, samples: Map<LocalDate, DaySample>, rules: TrendRules = TrendRules()): WeekSplit {
        val covered = range.dates().mapNotNull { d -> samples[d]?.takeIf { it.quality.countsAsCovered && it.trend != null } }
        fun avg(list: List<DaySample>) = DayAverage(list.takeIf { it.isNotEmpty() }?.map { it.trend!! }?.average(), list.size)
        val weekend = covered.filter { Baselines.isWeekend(it.date) }
        val weekday = covered.filterNot { Baselines.isWeekend(it.date) }
        val byDow = DayOfWeek.entries.associateWith { dow -> avg(covered.filter { it.date.dayOfWeek == dow }) }
        return WeekSplit(avg(weekday), avg(weekend), byDow, covered.size, rules.minSplitDays)
    }

    /**
     * A drawable series over [range]. [samples] may (and for correct averages and deltas should) reach 27 days
     * before the range and cover the previous equal-length range; missing dates are treated as no data.
     */
    fun series(
        range: DateRange,
        samples: Map<LocalDate, DaySample>,
        reference: (LocalDate) -> Double? = { null },
        rules: TrendRules = TrendRules(),
    ): List<TrendPoint> {
        val lead = rules.longWindow - 1L
        val from = range.start.minusDays(lead)
        val all = DateRange(from, range.end).dates().map { samples[it] ?: DaySample(it, DayQuality.NO_DATA, null) }
        val ma7 = movingAverage(all, rules.shortWindow, rules.minCoveredShort)
        val ma28 = movingAverage(all, rules.longWindow, rules.minCoveredLong)
        return all.indices.drop(lead.toInt()).map { i -> TrendPoint(all[i], ma7[i], ma28[i], reference(all[i].date)) }
    }

    fun samplesFor(metric: TrendMetric, days: Map<LocalDate, DailyMetrics>): Map<LocalDate, DaySample> = days.mapValues { (date, d) ->
        if (d.quality == DayQuality.NO_DATA) DaySample(date, DayQuality.NO_DATA, null)
        else DaySample(date, d.quality, metric.value(d), metric.secondary(d), metric.trend(d), d.dayStatus)
    }

    fun report(metric: TrendMetric, window: TrendWindow, range: DateRange, days: Map<LocalDate, DailyMetrics>, rules: TrendRules = TrendRules()): TrendReport {
        val samples = samplesFor(metric, days)
        val points = series(range, samples, reference = { date -> if (metric.showsBudget) days[date]?.budgetMs?.toDouble() else null }, rules = rules)
        val inRange = points.map { it.sample }
        val covered = inRange.filter { it.quality.countsAsCovered }
        return TrendReport(
            metric = metric,
            range = range,
            points = points,
            delta = delta(range, samples, window.hasPrevious, rules),
            split = split(range, samples, rules),
            coveredDays = covered.size,
            partialDays = inRange.count { it.quality == DayQuality.PARTIAL },
            noDataDays = inRange.count { it.quality == DayQuality.NO_DATA },
            inProgressDays = inRange.count { it.quality == DayQuality.IN_PROGRESS },
            observedTotal = inRange.sumOf { it.value ?: 0.0 },
            coveredMean = covered.mapNotNull { it.trend }.takeIf { it.isNotEmpty() }?.average(),
            worth = range.dates().mapNotNull { days[it]?.worth }.fold(WorthTotals()) { a, b -> a + b },
        )
    }

    /**
     * The dates a report over [range] needs loaded: 27 days of average lead-in and, when the window is compared with
     * the one before it, that previous equal-length window.
     */
    fun loadRange(range: DateRange, withPrevious: Boolean = true, rules: TrendRules = TrendRules()): DateRange {
        val leadIn = range.start.minusDays(rules.longWindow - 1L)
        return DateRange(if (withPrevious) minOf(leadIn, range.previous().start) else leadIn, range.end)
    }

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }
}
