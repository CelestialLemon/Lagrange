package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.BaselinePoint
import com.celestiallemon.lagrange.domain.BaselineRules
import com.celestiallemon.lagrange.domain.Baselines
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.HourSlots
import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.SliceBaseline
import com.celestiallemon.lagrange.domain.SliceCutoff
import java.time.LocalDate

// Fair Spike Autopsy and Good-Day history (spec §4.2 C). A day is judged against the median of comparable days
// (previous 28 days, weekday vs weekend, covered days only, confirmed zero-use days included), exactly the rule that
// sets `daily_rollups.day_status`; today in progress is judged against the same time-of-day slice of those days.

/** Four blocks of the logical day, anchored to the rollover hour (04:00 → Morning 04–10, …, Late night 22–04). */
enum class DayBlock(val label: String, val firstSlot: Int) {
    MORNING("Morning", 0),
    AFTERNOON("Afternoon", 6),
    EVENING("Evening", 12),
    LATE_NIGHT("Late night", 18);

    val slots: IntRange get() = firstSlot until firstSlot + SLOTS

    /** Wall-clock hours `[start, end)` this block shows under [rolloverHour]. */
    fun wallHours(rolloverHour: Int): Pair<Int, Int> = (firstSlot + rolloverHour) % 24 to (firstSlot + SLOTS + rolloverHour) % 24

    companion object {
        const val SLOTS = 6
        fun ofSlot(slot: Int): DayBlock = entries[slot.coerceIn(0, 23) / SLOTS]
    }
}

/** A day's whole-phone facts, as far as the spike rule cares (from `daily_rollups`). */
data class DayFacts(
    val date: LocalDate,
    val quality: DayQuality,
    val activeMs: Long,
    val distractingMs: Long = 0,
    val budgetMs: Long? = null,
    /** The day's own coverage verdict (`day_status` isn't UNCERTAIN_COVERAGE); only consulted for today in progress. */
    val coverageOk: Boolean = true,
)

/** A spike or good day (or today so far), with the comparison that put it there. */
data class DayHighlight(
    val date: LocalDate,
    val status: DayStatus,
    val actualMs: Long,
    val baselineMs: Long,
    /** Comparable (same weekday/weekend kind) covered days the median came from. */
    val matchedDays: List<LocalDate>,
    /** Today, compared through the current minute against the same slice of the matched days. */
    val live: Boolean = false,
) {
    val deltaMs: Long get() = actualMs - baselineMs
}

data class AppDelta(val pkgId: Long, val actualMs: Long, val medianMs: Long) {
    val deltaMs: Long get() = actualMs - medianMs
}

/** One block of the day vs the median of the same block on matched days. [complete] is false for the block "now" is in. */
data class BlockExcess(val block: DayBlock, val actualMs: Long, val medianMs: Long?, val complete: Boolean = true) {
    val deltaMs: Long? get() = medianMs?.let { actualMs - it }
}

data class LongSession(
    val episodeKey: String,
    val startMs: Long,
    val endMs: Long,
    val activeMs: Long,
    val distractingMs: Long,
    val isRabbitHole: Boolean,
    val trigger: TriggerEvidence,
    val hopChain: List<Long>,
    val inProgress: Boolean = false,
)

data class SpikeAutopsy(
    val highlight: DayHighlight,
    val longSessions: List<LongSession>,
    /** Apps above their own median, biggest increase first. Never presented as adding up to [excessMs]. */
    val appDeltas: List<AppDelta>,
    /** Sum of every app's positive delta: usually not equal to [excessMs] (medians don't add up). */
    val appDeltaSumMs: Long,
    val blocks: List<BlockExcess>,
    val triggers: Map<TriggerEvidence, Int>,
    val episodeCount: Int,
    val note: String?,
) {
    val excessMs: Long get() = highlight.deltaMs
}

/** What an autopsy is built from. Per-app and per-slot maps cover the highlighted day and its matched days. */
data class AutopsyInput(
    val highlight: DayHighlight,
    /** Active-category usage per app (`daily_app_rollups`, or, for a live day, the matched days cut at the same minute). */
    val appMs: Map<LocalDate, Map<Long, Long>>,
    /** Active-category usage per logical hour slot 0..23 (slot 24 already folded with [SpikeAnalyzer.foldRepeated]). */
    val slotMs: Map<LocalDate, Map<Int, Long>>,
    /** The day's unlocked episodes. */
    val episodes: List<LabEpisode>,
    val note: String? = null,
    /** For a live day: where "now" sits. Matched days are cut at the same place; blocks after it are left out. */
    val cutoff: SliceCutoff? = null,
)

data class SpikeRules(
    val baseline: BaselineRules = BaselineRules(),
    val topSessions: Int = 3,
    val minSessionMs: Long = MINUTE_MS,
    val topApps: Int = 5,
    /** App increases smaller than this are noise, not a contributor. */
    val minAppDeltaMs: Long = MINUTE_MS,
)

object SpikeAnalyzer {
    /**
     * Spikes and good days among the settled, covered days of [range], newest first. [days] should reach 28 days
     * before the range so early days have their baseline. Days that are not covered (gaps, in progress, no data) are
     * never judged and never feed anyone else's baseline. Hidden (no rows) until a baseline exists (≥ 7 covered days).
     */
    fun history(range: DateRange, days: Map<LocalDate, DayFacts>, rules: SpikeRules = SpikeRules()): List<DayHighlight> {
        val points = points(days)
        return range.dates().asReversed().mapNotNull { date ->
            val point = points[date] ?: return@mapNotNull null
            val base = Baselines.baseline(date, points, rules.baseline)
            val median = base.medianMs ?: return@mapNotNull null
            when (val status = Baselines.status(point, base, rules.baseline)) {
                DayStatus.SPIKE, DayStatus.GOOD_DAY -> DayHighlight(date, status, point.activeMs, median, base.matchedDays)
                else -> null
            }
        }
    }

    /** Covered days as baseline points (only settled, continuously collected days count). */
    fun points(days: Map<LocalDate, DayFacts>): Map<LocalDate, BaselinePoint> =
        days.values.filter { it.quality.countsAsCovered }.associate { d ->
            d.date to BaselinePoint(d.date, eligible = true, activeMs = d.activeMs, distractingMs = d.distractingMs, budgetMs = d.budgetMs)
        }

    /** Matched (comparable, covered) days for [date]; empty while the baseline is still hidden. */
    fun matchedDays(date: LocalDate, days: Map<LocalDate, DayFacts>, rules: SpikeRules = SpikeRules()): List<LocalDate> {
        val base = Baselines.baseline(date, points(days), rules.baseline)
        return if (base.medianMs == null) emptyList() else base.matchedDays
    }

    /**
     * Today so far against the same time-of-day slice of comparable days ([slice] from the Today comparison). Null
     * unless today is a live spike or good day; a coverage gap today means no comparison at all.
     */
    fun live(today: LocalDate, eligible: Boolean, slice: SliceBaseline?, matchedDays: List<LocalDate>, rules: SpikeRules = SpikeRules()): DayHighlight? {
        val median = slice?.medianMs ?: return null
        return when (val status = Baselines.partialStatus(eligible, slice.todayMs, median, rules.baseline)) {
            DayStatus.SPIKE, DayStatus.GOOD_DAY -> DayHighlight(today, status, slice.todayMs, median, matchedDays, live = true)
            else -> null
        }
    }

    fun autopsy(input: AutopsyInput, apps: LabApps, rules: SpikeRules = SpikeRules()): SpikeAutopsy {
        val h = input.highlight
        val matched = h.matchedDays
        val episodes = input.episodes.sortedBy { it.startMs }

        val longSessions = episodes
            .filter { it.activeDurationMs >= rules.minSessionMs }
            .sortedWith(compareByDescending<LabEpisode> { it.totalDistractingMs }.thenByDescending { it.activeDurationMs }.thenBy { it.startMs })
            .take(rules.topSessions)
            .map { e ->
                LongSession(e.episodeKey, e.startMs, e.endMs, e.activeDurationMs, e.totalDistractingMs, e.isRabbitHole, e.triggerEvidence, apps.hopChain(e), e.inProgress)
            }

        // Each app against its own median over the matched days (a matched day without the app is a real zero).
        val target = input.appMs[h.date].orEmpty()
        val pkgs = (target.keys + matched.flatMap { input.appMs[it].orEmpty().keys }).filterNot { apps.isTransient(it) }.toSet()
        val deltas = pkgs.mapNotNull { pkg ->
            val actual = target[pkg] ?: 0L
            val median = if (matched.isEmpty()) 0L else medianLong(matched.map { input.appMs[it]?.get(pkg) ?: 0L }) ?: 0L
            AppDelta(pkg, actual, median).takeIf { it.deltaMs > 0 }
        }
        val ranked = deltas.filter { it.deltaMs >= rules.minAppDeltaMs }
            .sortedWith(compareByDescending<AppDelta> { it.deltaMs }.thenBy { it.pkgId })
            .take(rules.topApps)

        val blocks = DayBlock.entries.mapNotNull { block ->
            val cut = input.cutoff
            if (cut != null && block.firstSlot > cut.slot) return@mapNotNull null
            val actual = blockMs(input.slotMs[h.date].orEmpty(), block, null)
            val medians = matched.map { blockMs(input.slotMs[it].orEmpty(), block, cut) }
            BlockExcess(block, actual, medianLong(medians), complete = cut == null || cut.slot !in block.slots)
        }

        val triggers = episodes.groupingBy { it.triggerEvidence }.eachCount()
        return SpikeAutopsy(h, longSessions, ranked, deltas.sumOf { it.deltaMs }, blocks, triggers, episodes.size, input.note?.takeIf { it.isNotBlank() })
    }

    /** Usage in [block]; with a [cutoff], slots after it are left out and the slot it falls in is pro-rated. */
    fun blockMs(slots: Map<Int, Long>, block: DayBlock, cutoff: SliceCutoff?): Long {
        var total = 0.0
        for ((slot, ms) in slots) {
            if (slot !in block.slots) continue
            total += when {
                cutoff == null || slot < cutoff.slot -> ms.toDouble()
                slot == cutoff.slot -> ms * cutoff.fraction
                else -> 0.0
            }
        }
        return total.toLong()
    }

    /**
     * Folds the repeated fall-back hour (slot 24) into the wall-clock slot it repeated, so that hour reads as two hours
     * of clock time instead of vanishing. When the repeated slot is unknown the time is dropped from the slots (never
     * misplaced) and reported in the second value.
     */
    fun foldRepeated(slots: Map<Int, Long>, repeatedSlot: Int?): Pair<Map<Int, Long>, Long> {
        val out = HashMap<Int, Long>()
        var unplaced = 0L
        for ((slot, ms) in slots) {
            val target = if (slot == HourSlots.REPEATED_SLOT) repeatedSlot?.takeIf { it in 0..23 } else slot.takeIf { it in 0..23 }
            if (target == null) unplaced += ms else out.merge(target, ms, Long::plus)
        }
        return out to unplaced
    }

    /** Per-app usage before [cutoff] from per-app hour slots (folded): the live slice of a matched day, app by app. */
    fun sliceApps(slotsByApp: Map<Long, Map<Int, Long>>, cutoff: SliceCutoff): Map<Long, Long> =
        slotsByApp.mapValues { (_, slots) -> DayBlock.entries.sumOf { blockMs(slots, it, cutoff) } }.filterValues { it > 0 }
}
