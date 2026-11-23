package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import java.time.LocalDate

// Apps breakdown and the per-app deep dive ("Reddit view", spec §4.2 B). Plain Kotlin over rows the repository reads
// from `daily_app_rollups`, `hourly_app_usage`, `sessions`, `session_segments` and `friction_events`.

// -----------------------------------------------------------------------------------------------------------------
// Apps breakdown.
// -----------------------------------------------------------------------------------------------------------------

/** One app's usage summed over a window. `covered*` count only days whose collection was continuous. */
data class AppWindowTotals(
    val pkgId: Long,
    val totalMs: Long,
    val opens: Int,
    val coveredMs: Long,
    val coveredOpens: Int,
)

data class AppMeta(
    val pkgId: Long,
    val packageName: String,
    val displayName: String,
    val category: AppCategory,
    val dailyGoalMs: Long?,
    val isSystemSurface: Boolean = false,
)

data class AppRow(
    val meta: AppMeta,
    /** Everything observed in the window, partial days included. */
    val totalMs: Long,
    val opens: Int,
    /** Mean per covered day (zero-use covered days included); null without covered days. */
    val dailyAvgMs: Double?,
    val dailyAvgOpens: Double?,
    /** Change of the covered-day mean vs the previous equal-length window; null when either side is too thin or was zero. */
    val trendPct: Double?,
    val previousAvgMs: Double?,
) {
    /** Covered-day mean as a share of the per-app goal, when one is set. */
    val goalFraction: Double? get() = meta.dailyGoalMs?.takeIf { it > 0 }?.let { g -> dailyAvgMs?.div(g) }
}

enum class AppSort(val label: String) { TIME("Time"), OPENS("Opens"), TREND("Trend"), NAME("Name") }

object AppsBreakdown {
    /**
     * Rows for every app with usage in the window, plus every app with a goal (at zero when unused). [coveredDays]/[previousCoveredDays] are the numbers of
     * covered days in each window: an app's average divides by all of them, because a covered day without the app is
     * a real zero for it.
     */
    fun rows(
        current: List<AppWindowTotals>,
        previous: List<AppWindowTotals>,
        coveredDays: Int,
        previousCoveredDays: Int,
        meta: Map<Long, AppMeta>,
        minCoveredDays: Int,
    ): List<AppRow> {
        val prev = previous.associateBy { it.pkgId }
        val usedRows = current.mapNotNull { t ->
            val m = meta[t.pkgId] ?: return@mapNotNull null
            if (m.isSystemSurface || (t.totalMs <= 0 && t.opens <= 0)) return@mapNotNull null
            val avg = if (coveredDays > 0) t.coveredMs.toDouble() / coveredDays else null
            val prevAvg = if (previousCoveredDays > 0) (prev[t.pkgId]?.coveredMs ?: 0L).toDouble() / previousCoveredDays else null
            val trend = if (avg != null && prevAvg != null && prevAvg > 0 && coveredDays >= minCoveredDays && previousCoveredDays >= minCoveredDays) {
                (avg - prevAvg) / prevAvg
            } else {
                null
            }
            AppRow(m, t.totalMs, t.opens, avg, if (coveredDays > 0) t.coveredOpens.toDouble() / coveredDays else null, trend, prevAvg)
        }
        // An app with a goal stays reachable (and honestly at zero) in a window where it wasn't used at all.
        val used = usedRows.mapTo(HashSet()) { it.meta.pkgId }
        val goalOnly = meta.values.filter { it.dailyGoalMs != null && !it.isSystemSurface && it.pkgId !in used }.map { m ->
            val prevAvg = if (previousCoveredDays > 0) (prev[m.pkgId]?.coveredMs ?: 0L).toDouble() / previousCoveredDays else null
            val zero = if (coveredDays > 0) 0.0 else null
            val trend = if (zero != null && prevAvg != null && prevAvg > 0 && coveredDays >= minCoveredDays && previousCoveredDays >= minCoveredDays) {
                -1.0
            } else {
                null
            }
            AppRow(m, 0, 0, zero, zero, trend, prevAvg)
        }
        return usedRows + goalOnly
    }

    /** Search (name or package), category filter and sort, applied in that order. */
    fun filter(rows: List<AppRow>, query: String, category: AppCategory?, sort: AppSort): List<AppRow> {
        val q = query.trim()
        val kept = rows.filter { r ->
            (category == null || r.meta.category == category) &&
                (q.isEmpty() || r.meta.displayName.contains(q, ignoreCase = true) || r.meta.packageName.contains(q, ignoreCase = true))
        }
        val byName = compareBy<AppRow> { it.meta.displayName.lowercase() }
        return when (sort) {
            AppSort.TIME -> kept.sortedWith(compareByDescending<AppRow> { it.totalMs }.then(byName))
            AppSort.OPENS -> kept.sortedWith(compareByDescending<AppRow> { it.opens }.then(byName))
            // Biggest increases first; apps without a comparable trend go last.
            AppSort.TREND -> kept.sortedWith(compareBy<AppRow> { it.trendPct == null }.thenByDescending { it.trendPct ?: 0.0 }.then(byName))
            AppSort.NAME -> kept.sortedWith(byName)
        }
    }
}

// -----------------------------------------------------------------------------------------------------------------
// 24-hour time-of-day profile.
// -----------------------------------------------------------------------------------------------------------------

/** Summed `hourly_app_usage` for one ordinary slot (0..23) over the covered days of a window. */
data class SlotTotal(val slot: Int, val durationMs: Long, val opens: Int)

/** A slot-24 cell (the repeated hour of a fall-back day). */
data class RepeatedHourCell(val date: LocalDate, val durationMs: Long, val opens: Int)

data class HourBin(
    /** Position along the logical day: 0 is the rollover hour. */
    val slot: Int,
    /** Wall-clock hour this slot shows. */
    val wallHour: Int,
    val avgMs: Double,
    val avgOpens: Double,
)

data class HourProfile(
    val bins: List<HourBin>,
    val coveredDays: Int,
    /** Repeated-hour time whose wall-clock hour could not be resolved; kept out of the bins rather than misplaced. */
    val unplacedRepeatedMs: Long,
) {
    val peak: HourBin? get() = bins.filter { it.avgMs > 0 }.maxByOrNull { it.avgMs }
    val isEmpty: Boolean get() = bins.all { it.avgMs <= 0.0 && it.avgOpens <= 0.0 }
}

object TimeOfDayProfile {
    /**
     * Average minutes and opens per logical hour, ordered from the rollover hour. A fall-back day's repeated hour
     * (slot 24) is folded into the wall-clock hour it repeated ([repeatedSlotOf]), so that hour honestly reads as
     * two hours of clock time that day instead of disappearing or landing in a 25th column.
     */
    fun build(
        rolloverHour: Int,
        slots: List<SlotTotal>,
        repeated: List<RepeatedHourCell>,
        repeatedSlotOf: (LocalDate) -> Int?,
        coveredDays: Int,
    ): HourProfile {
        val ms = LongArray(24)
        val opens = IntArray(24)
        for (s in slots) {
            if (s.slot !in 0..23) continue
            ms[s.slot] += s.durationMs
            opens[s.slot] += s.opens
        }
        var unplaced = 0L
        for (c in repeated) {
            val slot = repeatedSlotOf(c.date)
            if (slot == null || slot !in 0..23) {
                unplaced += c.durationMs
                continue
            }
            ms[slot] += c.durationMs
            opens[slot] += c.opens
        }
        val d = coveredDays.coerceAtLeast(0)
        val bins = (0 until 24).map { slot ->
            HourBin(
                slot = slot,
                wallHour = (slot + rolloverHour) % 24,
                avgMs = if (d > 0) ms[slot].toDouble() / d else 0.0,
                avgOpens = if (d > 0) opens[slot].toDouble() / d else 0.0,
            )
        }
        return HourProfile(bins, d, unplaced)
    }
}

// -----------------------------------------------------------------------------------------------------------------
// Sessions of one app: shape, loops, sequences, triggers, value.
// -----------------------------------------------------------------------------------------------------------------

/** One `session_segments` row. [durationMs] is real use (launch-pause time already scrubbed). */
data class SegmentRow(
    val pkgId: Long,
    val seqIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val isBlockedAttempt: Boolean,
    val triggerEvidence: TriggerEvidence?,
)

/** An unlocked episode with every one of its segments (not only the target app's). */
data class SessionRow(
    val id: Long,
    val logicalDate: LocalDate,
    val startMs: Long,
    val endMs: Long,
    val activeDurationMs: Long,
    val triggerEvidence: TriggerEvidence,
    val entryPkgId: Long?,
    val worthItRating: Int?,
    val segments: List<SegmentRow>,
)

enum class LengthBucket(val label: String, val maxMs: Long) {
    UNDER_1M("<1m", MINUTE_MS),
    M1_5("1–5m", 5 * MINUTE_MS),
    M5_15("5–15m", 15 * MINUTE_MS),
    M15_30("15–30m", 30 * MINUTE_MS),
    OVER_30M("30m+", Long.MAX_VALUE);

    companion object {
        fun of(ms: Long): LengthBucket = entries.first { ms < it.maxMs }
    }
}

data class SessionShape(
    val counts: Map<LengthBucket, Int>,
    val runs: Int,
    val medianRunMs: Long?,
    /** Re-opens of this app within the loop window of leaving it (blocked attempts excluded on both ends). */
    val reopenLoops: Int,
) {
    val loopRate: Double? get() = if (runs > 0) reopenLoops.toDouble() / runs else null
}

/** Where a run of the app came from, or went next. */
sealed interface FlowNode {
    data class App(val pkgId: Long) : FlowNode
    /** Inflow: the run opened the episode with no notification evidence (idle pickup or a direct launch). */
    data object SessionStart : FlowNode
    /** Inflow: the run opened the episode right after a notification was tapped (observed or very likely). */
    data object NotificationStart : FlowNode
    /** The same app again (after a micro-lock or a long launcher stop). */
    data object SameApp : FlowNode
    /** Outflow: nothing real came after it in the episode. */
    data object SessionEnd : FlowNode
}

data class FlowEdge(val node: FlowNode, val count: Int, val share: Double)

data class SequenceFlows(val inflow: List<FlowEdge>, val outflow: List<FlowEdge>, val runs: Int)

data class TriggerBreakdown(
    /** Evidence on the runs that opened an episode. */
    val episodeStarts: Map<TriggerEvidence, Int>,
    /** Evidence on mid-episode switches into the app. */
    val midSession: Map<TriggerEvidence, Int>,
) {
    val combined: Map<TriggerEvidence, Int>
        get() = TriggerEvidence.entries.associateWith { (episodeStarts[it] ?: 0) + (midSession[it] ?: 0) }.filterValues { it > 0 }
    val total: Int get() = combined.values.sum()

    /** Arrivals where the notification listener was actually recording (the only ones evidence can speak to). */
    val observable: Int get() = combined.filterKeys { it != TriggerEvidence.PARTIAL_COVERAGE && it != TriggerEvidence.BACKFILL }.values.sum()
}

/** A `friction_events` row, as far as the deep dive cares. */
data class FrictionRow(val timestampMs: Long, val eventKind: String, val outcome: String)

data class FrictionSummary(
    val launchPauses: Int,
    val immediateExits: Int,
    /** Immediate exits followed by another open (real or paused) of the app within [AppInsightRules.quickReopenMs]. */
    val quickReopens: Int,
    val timerCheckIns: Int,
    val extensions: Int,
    /** Exits whose 10-minute follow-up is not yet complete or lacked continuous usage coverage; kept out of the reopen rate. */
    val pendingExits: Int = 0,
) {
    val hasData: Boolean get() = launchPauses > 0 || timerCheckIns > 0 || extensions > 0
    val exitRate: Double? get() = if (launchPauses > 0) immediateExits.toDouble() / launchPauses else null
    val resolvedExits: Int get() = immediateExits - pendingExits
    val quickReopenRate: Double? get() = if (resolvedExits > 0) quickReopens.toDouble() / resolvedExits else null
    val extensionRate: Double? get() = if (timerCheckIns > 0) extensions.toDouble() / timerCheckIns else null
}

data class ValueSummary(val episodes: Int, val rated: Int, val averageRating: Double?, val worth: WorthTotals)

data class AppInsightRules(
    val reopenLoopMs: Long = 60_000,
    val quickReopenMs: Long = 10 * MINUTE_MS,
    val topFlows: Int = 5,
)

/**
 * The instants an analysis speaks for: target-app opens starting in `[fromMs, toMs)`. Sessions may reach outside it
 * (an episode that began before the rollover); their other segments are still read as neighbours. [dataEndMs] is how
 * far collection has run, so recent outcomes that could still change are not counted as settled.
 */
data class InsightWindow(val fromMs: Long = Long.MIN_VALUE, val toMs: Long = Long.MAX_VALUE, val dataEndMs: Long = Long.MAX_VALUE) {
    operator fun contains(ms: Long): Boolean = ms >= fromMs && ms < toMs

    companion object {
        val ALL = InsightWindow()
    }
}

/** What a switch without recorded evidence means at its moment: nothing happened, or nothing could have been seen. */
fun interface SwitchCoverage {
    fun unobserved(atMs: Long, endMs: Long): TriggerEvidence

    companion object {
        /**
         * The reconstructor's rule (spec §4.1) applied to one switch: if the listener recorded the whole window a tap
         * or alert would have had to fall in, the absence of evidence means [TriggerEvidence.NO_OBSERVED_TRIGGER];
         * otherwise the switch is [TriggerEvidence.BACKFILL] or [TriggerEvidence.PARTIAL_COVERAGE].
         */
        fun of(coverage: List<CoverageSpan>, rules: ReconstructionRules = ReconstructionRules()): SwitchCoverage {
            val listener = coverage.filter { it.kind == ServiceKind.NOTIFICATION_LISTENER && it.status == CoverageStatus.ACTIVE }.map { it.span }
            val backfill = coverage.filter { it.status == CoverageStatus.BACKFILL }.map { it.span }
            return SwitchCoverage { at, end ->
                val from = at - maxOf(rules.alertBeforeMs, rules.clickBeforeMs)
                val to = maxOf(at, minOf(at + rules.clickAfterMs, end))
                when {
                    to > from && Spans.coveredMs(listener, from, to) >= to - from -> TriggerEvidence.NO_OBSERVED_TRIGGER
                    Spans.contains(backfill, at) -> TriggerEvidence.BACKFILL
                    else -> TriggerEvidence.PARTIAL_COVERAGE
                }
            }
        }
    }
}

/** Whether usage collection continuously covered `[fromMs, toMs)` with no gap. */
fun interface UsageCoverage {
    fun isContinuous(fromMs: Long, toMs: Long): Boolean

    companion object {
        fun of(coverage: List<CoverageSpan>): UsageCoverage {
            val active = coverage.filter {
                it.kind == ServiceKind.USAGE_HARVEST && (it.status == CoverageStatus.ACTIVE || it.status == CoverageStatus.BACKFILL)
            }.map { it.span }
            val gaps = coverage.filter {
                it.kind == ServiceKind.USAGE_HARVEST && it.status == CoverageStatus.GAP
            }.map { it.span }
            return UsageCoverage { from, to ->
                to <= from || (Spans.coveredMs(active, from, to) >= to - from && gaps.none { it.overlapMs(from, to) > 0 })
            }
        }
    }
}

data class AppSessionInsights(
    val shape: SessionShape,
    val flows: SequenceFlows,
    val triggers: TriggerBreakdown,
    val value: ValueSummary,
    val friction: FrictionSummary,
)

object AppSessionAnalyzer {
    private val STRONG_NOTIFICATION = setOf(TriggerEvidence.OBSERVED_CLICK, TriggerEvidence.LIKELY_APP_CANCEL_CLICK)
    private val UNOBSERVED = setOf(TriggerEvidence.PARTIAL_COVERAGE, TriggerEvidence.BACKFILL)

    /** One real appearance of the target app, with its episode context. */
    private data class Run(val session: SessionRow, val seg: SegmentRow, val prevReal: SegmentRow?, val nextReal: SegmentRow?)

    /**
     * @param followUpOpens starts of real runs of the app shortly after the window (quick reopens of late exits).
     * @param switchCoverage how a switch without evidence is labelled; by default it inherits the episode's label.
     * @param usageCoverage whether usage collection continuously covered an exit's follow-up interval.
     * @param priorRunEndMs latest end of a real run of the app that started before [window.fromMs], so a reopen loop
     * crossing the window start is still attributed to the first in-window open.
     */
    fun analyze(
        pkgId: Long,
        sessions: List<SessionRow>,
        friction: List<FrictionRow>,
        isTransient: (Long) -> Boolean,
        rules: AppInsightRules = AppInsightRules(),
        window: InsightWindow = InsightWindow.ALL,
        followUpOpens: List<Long> = emptyList(),
        switchCoverage: SwitchCoverage? = null,
        usageCoverage: UsageCoverage? = null,
        priorRunEndMs: Long? = null,
    ): AppSessionInsights {
        val allRuns = runsOf(pkgId, sessions, isTransient)
        val runs = allRuns.filter { it.seg.startMs in window }
        val precedingEndMs = listOfNotNull(
            priorRunEndMs,
            allRuns.filter { it.seg.startMs < window.fromMs }.maxOfOrNull { it.seg.endMs },
        ).maxOrNull()
        return AppSessionInsights(
            shape = shape(runs, rules, precedingEndMs),
            flows = flows(pkgId, runs, rules),
            triggers = triggers(pkgId, sessions, isTransient, window, switchCoverage),
            value = value(pkgId, sessions, isTransient, window),
            friction = friction(friction, runs.map { it.seg.startMs } + followUpOpens, rules, window, usageCoverage),
        )
    }

    /** A segment that is real use of a real app: not a launch-pause blocked attempt, not a system surface, not empty. */
    private fun isReal(s: SegmentRow, isTransient: (Long) -> Boolean) = !s.isBlockedAttempt && !isTransient(s.pkgId) && s.durationMs > 0

    private fun runsOf(pkgId: Long, sessions: List<SessionRow>, isTransient: (Long) -> Boolean): List<Run> {
        val out = ArrayList<Run>()
        for (session in sessions) {
            val real = session.segments.sortedBy { it.seqIndex }.filter { isReal(it, isTransient) }
            for ((i, seg) in real.withIndex()) {
                if (seg.pkgId != pkgId) continue
                out += Run(session, seg, real.getOrNull(i - 1), real.getOrNull(i + 1))
            }
        }
        return out.sortedWith(compareBy({ it.seg.startMs }, { it.seg.seqIndex }))
    }

    private fun shape(runs: List<Run>, rules: AppInsightRules, priorRunEndMs: Long? = null): SessionShape {
        val counts = LengthBucket.entries.associateWith { 0 }.toMutableMap()
        for (r in runs) counts.merge(LengthBucket.of(r.seg.durationMs), 1, Int::plus)
        var loops = 0
        var lastEnd = priorRunEndMs
        for (r in runs) {
            if (lastEnd != null) {
                val gap = r.seg.startMs - lastEnd
                if (gap in 0..rules.reopenLoopMs) loops++
            }
            lastEnd = r.seg.endMs
        }
        val durations = runs.map { it.seg.durationMs }.sorted()
        val median = if (durations.isEmpty()) null else if (durations.size % 2 == 1) durations[durations.size / 2]
        else (durations[durations.size / 2 - 1] + durations[durations.size / 2]) / 2
        return SessionShape(counts, runs.size, median, loops)
    }

    private fun flows(pkg: Long, runs: List<Run>, rules: AppInsightRules): SequenceFlows {
        val inflow = HashMap<FlowNode, Int>()
        val outflow = HashMap<FlowNode, Int>()
        for (r in runs) {
            val inNode = when {
                r.prevReal == null -> if (r.session.triggerEvidence in STRONG_NOTIFICATION && r.session.entryPkgId == pkg) FlowNode.NotificationStart else FlowNode.SessionStart
                r.prevReal.pkgId == pkg -> FlowNode.SameApp
                else -> FlowNode.App(r.prevReal.pkgId)
            }
            val outNode = when {
                r.nextReal == null -> FlowNode.SessionEnd
                r.nextReal.pkgId == pkg -> FlowNode.SameApp
                else -> FlowNode.App(r.nextReal.pkgId)
            }
            inflow.merge(inNode, 1, Int::plus)
            outflow.merge(outNode, 1, Int::plus)
        }
        return SequenceFlows(rank(inflow, runs.size, rules.topFlows), rank(outflow, runs.size, rules.topFlows), runs.size)
    }

    /** Most frequent first (ties by a stable label order); [top] entries at most. */
    private fun rank(counts: Map<FlowNode, Int>, total: Int, top: Int): List<FlowEdge> =
        counts.entries
            .sortedWith(compareByDescending<Map.Entry<FlowNode, Int>> { it.value }.thenBy { nodeOrder(it.key) })
            .take(top)
            .map { FlowEdge(it.key, it.value, if (total > 0) it.value.toDouble() / total else 0.0) }

    private fun nodeOrder(n: FlowNode): String = when (n) {
        is FlowNode.App -> "1:%019d".format(n.pkgId)
        FlowNode.NotificationStart -> "0:a"
        FlowNode.SessionStart -> "0:b"
        FlowNode.SameApp -> "0:c"
        FlowNode.SessionEnd -> "0:d"
    }

    /**
     * How the app was arrived at, with the same structure the reconstructor attributes: the episode's entry carries
     * the episode trigger; a switch into the app (its predecessor is a different package) carries the switch evidence.
     * A launch pause the user continued through is part of the same arrival: the real run after it takes the pause's
     * evidence when it has none of its own. A switch with no evidence is labelled by [switchCoverage] at its moment
     * (by default it inherits the episode's coverage label). Blocked attempts alone are not arrivals.
     */
    fun triggers(
        pkgId: Long,
        sessions: List<SessionRow>,
        isTransient: (Long) -> Boolean,
        window: InsightWindow = InsightWindow.ALL,
        switchCoverage: SwitchCoverage? = null,
    ): TriggerBreakdown {
        val starts = HashMap<TriggerEvidence, Int>()
        val mid = HashMap<TriggerEvidence, Int>()
        for (s in sessions) {
            val segs = s.segments.sortedBy { it.seqIndex }
            val entry = segs.firstOrNull { !it.isBlockedAttempt && !isTransient(it.pkgId) }
            for ((i, seg) in segs.withIndex()) {
                if (seg.pkgId != pkgId || !isReal(seg, isTransient) || seg.startMs !in window) continue
                if (seg === entry) {
                    starts.merge(s.triggerEvidence, 1, Int::plus)
                    continue
                }
                // Step back over blocked attempts of this app right before it: they are the start of this arrival.
                var k = i - 1
                while (k >= 0 && segs[k].pkgId == pkgId && segs[k].isBlockedAttempt) k--
                if (k < 0 || segs[k].pkgId == pkgId) continue // a continuation of the same app, not an arrival
                val arrival = segs[k + 1]
                val ev = seg.triggerEvidence
                    ?: (k + 1 until i).firstNotNullOfOrNull { segs[it].triggerEvidence }
                    ?: switchCoverage?.unobserved(arrival.startMs, arrival.endMs)
                    ?: if (s.triggerEvidence in UNOBSERVED) s.triggerEvidence else TriggerEvidence.NO_OBSERVED_TRIGGER
                mid.merge(ev, 1, Int::plus)
            }
        }
        return TriggerBreakdown(starts, mid)
    }

    /** Self-report on the episodes the app took real part in (with a real open inside [window]). */
    fun value(pkgId: Long, sessions: List<SessionRow>, isTransient: (Long) -> Boolean, window: InsightWindow = InsightWindow.ALL): ValueSummary {
        val involved = sessions.filter { s -> s.segments.any { it.pkgId == pkgId && isReal(it, isTransient) && it.startMs in window } }
        val ratings = involved.mapNotNull { s -> s.worthItRating?.takeIf { it in 1..5 } }
        return ValueSummary(
            episodes = involved.size,
            rated = ratings.size,
            averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
            worth = WorthTotals.of(involved.map { it.worthItRating to it.activeDurationMs }),
        )
    }

    /**
     * Launch-pause outcomes for the app, for events inside [window]. An immediate exit is a pause the user left
     * (home, system navigation or a healthy redirect); a quick reopen is another open of the app (a real run or
     * another pause) less than 10 minutes after that exit. [events] and [opens] may reach past the window so a late
     * exit's follow-up is seen; an exit whose 10 minutes haven't been collected yet (and has no reopen) is pending.
     */
    fun friction(
        events: List<FrictionRow>,
        opens: List<Long>,
        rules: AppInsightRules = AppInsightRules(),
        window: InsightWindow = InsightWindow.ALL,
        usageCoverage: UsageCoverage? = null,
    ): FrictionSummary {
        val sorted = events.sortedBy { it.timestampMs }
        val allPauses = sorted.filter { it.eventKind == FrictionVocab.LAUNCH_PAUSE }
        val pauses = allPauses.filter { it.timestampMs in window }
        val exits = pauses.filter { it.outcome in FrictionVocab.BLOCKING_OUTCOMES }
        val allOpens = (opens + allPauses.map { it.timestampMs }).sorted()
        var j = 0
        var quick = 0
        var pending = 0
        for (e in exits) {
            while (j < allOpens.size && allOpens[j] <= e.timestampMs) j++
            val followEnd = e.timestampMs + rules.quickReopenMs
            when {
                j < allOpens.size && allOpens[j] - e.timestampMs < rules.quickReopenMs -> quick++
                followEnd > window.dataEndMs || (usageCoverage != null && !usageCoverage.isContinuous(e.timestampMs, followEnd)) -> pending++
            }
        }
        val inWindow = sorted.filter { it.timestampMs in window }
        return FrictionSummary(
            launchPauses = pauses.size,
            immediateExits = exits.size,
            quickReopens = quick,
            timerCheckIns = inWindow.count { it.eventKind == TIMER_EXPIRED },
            extensions = inWindow.count { it.outcome == EXTENDED_5M },
            pendingExits = pending,
        )
    }

    const val TIMER_EXPIRED = "TIMER_EXPIRED"
    const val EXTENDED_5M = "EXTENDED_5M"
}
