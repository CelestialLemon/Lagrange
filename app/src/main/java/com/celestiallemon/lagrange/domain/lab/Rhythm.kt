package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.Span
import java.time.DayOfWeek
import java.time.LocalDate

// Circadian heatmap, inter-pickup gaps and the sleep proxy (spec §4.2 C). Covered days only: a day with a harvest
// gap never contributes zeros to any hour.

/** One covered logical day, as the rhythm views need it. Slots are logical hours (0 = the rollover hour, 24 = DST repeat). */
data class RhythmDay(
    val date: LocalDate,
    val distractingSlots: Map<Int, Long> = emptyMap(),
    val pickupSlots: Map<Int, Int> = emptyMap(),
    /** Which slot the repeated fall-back hour (slot 24) repeated, if this day had one. */
    val repeatedSlot: Int? = null,
    /** The day's sleep-proxy gap from `daily_rollups`. */
    val overnightGapMs: Long? = null,
    /** The day's first unlocked pickup as a wall-clock minute of the day (0..1439). */
    val firstPickupMinute: Int? = null,
    /** Unlocked episodes that started this logical day, as spans. */
    val episodes: List<Span> = emptyList(),
)

data class HeatCell(val distractingMs: Long, val pickups: Double)

data class CircadianHeatmap(
    val rolloverHour: Int,
    /** [dayOfWeek index 0=Mon..6=Sun][logical slot 0..23]: average per covered day of that weekday. */
    val cells: List<List<HeatCell>>,
    /** Covered days behind each row (Mon..Sun). */
    val daysPerRow: List<Int>,
    val maxDistractingMs: Long,
    val maxPickups: Double,
    /** Days whose repeated DST hour was folded into the clock hour it repeated. */
    val foldedDstDays: Int,
    /** Time from a repeated hour that couldn't be placed (left out rather than misplaced). */
    val unplacedMs: Long,
) {
    fun wallHour(slot: Int): Int = (slot + rolloverHour) % 24
    val isEmpty: Boolean get() = daysPerRow.all { it == 0 }

    companion object {
        val ROW_DAYS: List<DayOfWeek> = DayOfWeek.entries
    }
}

enum class GapBucket(val label: String, val upToMs: Long) {
    UNDER_5M("<5m", 5 * MINUTE_MS),
    M5_15("5–15m", 15 * MINUTE_MS),
    M15_60("15–60m", HOUR_MS),
    H1_3("1–3h", 3 * HOUR_MS),
    H3_PLUS("3h+", Long.MAX_VALUE);

    companion object {
        fun of(gapMs: Long): GapBucket = entries.first { gapMs < it.upToMs }
    }
}

data class GapDistribution(val count: Int, val medianMs: Long?, val buckets: Map<GapBucket, Int>)

/** Recent (last [RhythmRules.recentDays] covered days with a value) vs the earlier covered days in the window. */
data class RecentVsEarlier(val overallMs: Long?, val recentMs: Long?, val earlierMs: Long?, val recentDays: Int, val earlierDays: Int) {
    val changeMs: Long? get() = if (recentMs != null && earlierMs != null) recentMs - earlierMs else null
}

data class SleepSummary(
    val overnightGap: RecentVsEarlier,
    /** Median first pickup, wall-clock minute of the day (median taken on the logical day, so 23:50 and 00:10 average sensibly). */
    val firstPickupMinute: Int?,
    val firstPickupDays: Int,
    /** Average distracting time 23:00–04:00 per covered day. */
    val lateNight: RecentVsEarlier,
)

data class RhythmReport(
    val heatmap: CircadianHeatmap,
    val gaps: GapDistribution,
    val sleep: SleepSummary,
    val coveredDays: Int,
)

data class RhythmRules(
    val recentDays: Int = 7,
    /** Wall-clock hours that count as late night (23:00–04:00). */
    val lateNightHours: Set<Int> = setOf(23, 0, 1, 2, 3),
)

object RhythmAnalyzer {
    fun analyze(days: List<RhythmDay>, rolloverHour: Int, rules: RhythmRules = RhythmRules()): RhythmReport {
        val sorted = days.sortedBy { it.date }
        return RhythmReport(heatmap(sorted, rolloverHour), gaps(sorted), sleep(sorted, rolloverHour, rules), sorted.size)
    }

    fun heatmap(days: List<RhythmDay>, rolloverHour: Int): CircadianHeatmap {
        val sumMs = Array(7) { LongArray(24) }
        val sumPickups = Array(7) { LongArray(24) }
        val count = IntArray(7)
        var folded = 0
        var unplaced = 0L
        for (d in days) {
            val row = d.date.dayOfWeek.value - 1
            count[row]++
            val (ms, lost) = SpikeAnalyzer.foldRepeated(d.distractingSlots, d.repeatedSlot)
            val (picks, _) = SpikeAnalyzer.foldRepeated(d.pickupSlots.mapValues { it.value.toLong() }, d.repeatedSlot)
            if (d.distractingSlots.containsKey(24) || d.pickupSlots.containsKey(24)) {
                if (d.repeatedSlot != null) folded++
            }
            unplaced += lost
            for ((slot, v) in ms) sumMs[row][slot] += v
            for ((slot, v) in picks) sumPickups[row][slot] += v
        }
        val cells = (0 until 7).map { r ->
            (0 until 24).map { s ->
                if (count[r] == 0) HeatCell(0, 0.0) else HeatCell(sumMs[r][s] / count[r], sumPickups[r][s].toDouble() / count[r])
            }
        }
        return CircadianHeatmap(
            rolloverHour = rolloverHour,
            cells = cells,
            daysPerRow = count.toList(),
            maxDistractingMs = cells.maxOf { row -> row.maxOf { it.distractingMs } },
            maxPickups = cells.maxOf { row -> row.maxOf { it.pickups } },
            foldedDstDays = folded,
            unplacedMs = unplaced,
        )
    }

    /**
     * Phone-free stretches between consecutive unlocked episodes, within each covered day; the day's sleep-proxy gap
     * is left out (it belongs to the sleep view), as is a stretch that started on a day that wasn't covered.
     */
    fun gaps(days: List<RhythmDay>): GapDistribution {
        val all = ArrayList<Long>()
        for (d in days) {
            var prevEnd = Long.MIN_VALUE
            var skippedOvernight = false
            for (ep in d.episodes.sortedBy { it.startMs }) {
                if (prevEnd != Long.MIN_VALUE) {
                    val g = ep.startMs - prevEnd
                    if (g > 0) {
                        if (!skippedOvernight && d.overnightGapMs != null && g == d.overnightGapMs) skippedOvernight = true else all += g
                    }
                }
                prevEnd = maxOf(prevEnd, ep.endMs)
            }
        }
        val buckets = GapBucket.entries.associateWith { b -> all.count { GapBucket.of(it) == b } }
        return GapDistribution(all.size, medianLong(all), buckets)
    }

    fun sleep(days: List<RhythmDay>, rolloverHour: Int, rules: RhythmRules = RhythmRules()): SleepSummary {
        val overnight = recentVsEarlier(days.mapNotNull { d -> d.overnightGapMs?.let { d.date to it } }, rules.recentDays)

        // Minutes since the rollover keep a night owl's 00:30 after their 23:50, then convert back to clock time.
        val rollMin = rolloverHour * 60
        val sinceRollover = days.mapNotNull { d -> d.firstPickupMinute?.let { ((it - rollMin) % 1440 + 1440) % 1440 } }
        val firstPickup = medianLong(sinceRollover.map { it.toLong() })?.let { ((it.toInt() + rollMin) % 1440) }

        val lateSlots = rules.lateNightHours.map { ((it - rolloverHour) % 24 + 24) % 24 }.toSet()
        val late = days.map { d ->
            val (ms, _) = SpikeAnalyzer.foldRepeated(d.distractingSlots, d.repeatedSlot)
            d.date to ms.filterKeys { it in lateSlots }.values.sum()
        }
        return SleepSummary(overnight, firstPickup, sinceRollover.size, recentVsEarlier(late, rules.recentDays, mean = true))
    }

    /** Median (or mean, for per-day averages) of the last [recentDays] values vs the rest. */
    fun recentVsEarlier(values: List<Pair<LocalDate, Long>>, recentDays: Int, mean: Boolean = false): RecentVsEarlier {
        val sorted = values.sortedBy { it.first }.map { it.second }
        val recent = sorted.takeLast(recentDays)
        val earlier = sorted.dropLast(recentDays)
        fun stat(v: List<Long>): Long? = if (v.isEmpty()) null else if (mean) v.sum() / v.size else medianLong(v)
        return RecentVsEarlier(stat(sorted), stat(recent), stat(earlier), recent.size, earlier.size)
    }
}
