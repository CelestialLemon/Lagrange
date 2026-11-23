package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionVocab
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
     * Rows for every app with usage (or a goal) in the window. [coveredDays]/[previousCoveredDays] are the numbers of
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
        return current.mapNotNull { t ->
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
) {
    val hasData: Boolean get() = launchPauses > 0 || timerCheckIns > 0 || extensions > 0
    val exitRate: Double? get() = if (launchPauses > 0) immediateExits.toDouble() / launchPauses else null
    val quickReopenRate: Double? get() = if (immediateExits > 0) quickReopens.toDouble() / immediateExits else null
    val extensionRate: Double? get() = if (timerCheckIns > 0) extensions.toDouble() / timerCheckIns else null
}

data class ValueSummary(val episodes: Int, val rated: Int, val averageRating: Double?, val worth: WorthTotals)

data class AppInsightRules(
    val reopenLoopMs: Long = 60_000,
    val quickReopenMs: Long = 10 * MINUTE_MS,
    val topFlows: Int = 5,
)

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
    private data class Run(val session: SessionRow, val index: Int, val seg: SegmentRow, val prevReal: SegmentRow?, val nextReal: SegmentRow?)

    fun analyze(
        pkgId: Long,
        sessions: List<SessionRow>,
        friction: List<FrictionRow>,
        isTransient: (Long) -> Boolean,
        rules: AppInsightRules = AppInsightRules(),
    ): AppSessionInsights {
        val runs = runsOf(pkgId, sessions, isTransient)
        return AppSessionInsights(
            shape = shape(runs, rules),
            flows = flows(pkgId, runs, rules),
            triggers = triggers(pkgId, sessions, isTransient),
            value = value(pkgId, sessions, isTransient),
            friction = friction(friction, runs.map { it.seg.startMs }, rules),
        )
    }

    /** A segment that is real use of a real app: not a launch-pause blocked attempt, not a system surface, not empty. */
    private fun isReal(s: SegmentRow, isTransient: (Long) -> Boolean) = !s.isBlockedAttempt && !isTransient(s.pkgId) && s.durationMs > 0

    private fun runsOf(pkgId: Long, sessions: List<SessionRow>, isTransient: (Long) -> Boolean): List<Run> {
        val out = ArrayList<Run>()
        for (session in sessions) {
            val segs = session.segments.sortedBy { it.seqIndex }
            val real = segs.filter { isReal(it, isTransient) }
            for ((i, seg) in real.withIndex()) {
                if (seg.pkgId != pkgId) continue
                out += Run(session, i, seg, real.getOrNull(i - 1), real.getOrNull(i + 1))
            }
        }
        return out.sortedWith(compareBy({ it.seg.startMs }, { it.seg.seqIndex }))
    }

    private fun shape(runs: List<Run>, rules: AppInsightRules): SessionShape {
        val counts = LengthBucket.entries.associateWith { 0 }.toMutableMap()
        for (r in runs) counts.merge(LengthBucket.of(r.seg.durationMs), 1, Int::plus)
        var loops = 0
        for (i in 1 until runs.size) {
            val gap = runs[i].seg.startMs - runs[i - 1].seg.endMs
            if (gap in 0..rules.reopenLoopMs) loops++
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
     * the episode trigger; a switch into the app (its immediate predecessor is a different package) carries the
     * switch evidence. A switch with no evidence is "no observed trigger" when the listener was recording and keeps
     * the episode's coverage label when it wasn't. Blocked attempts are not arrivals.
     */
    fun triggers(pkgId: Long, sessions: List<SessionRow>, isTransient: (Long) -> Boolean): TriggerBreakdown {
        val starts = HashMap<TriggerEvidence, Int>()
        val mid = HashMap<TriggerEvidence, Int>()
        for (s in sessions) {
            val segs = s.segments.sortedBy { it.seqIndex }
            val entry = segs.firstOrNull { !it.isBlockedAttempt && !isTransient(it.pkgId) }
            for ((i, seg) in segs.withIndex()) {
                if (seg.pkgId != pkgId || !isReal(seg, isTransient)) continue
                if (seg === entry) {
                    starts.merge(s.triggerEvidence, 1, Int::plus)
                } else if (i > 0 && segs[i - 1].pkgId != pkgId) {
                    val ev = seg.triggerEvidence
                        ?: if (s.triggerEvidence in UNOBSERVED) s.triggerEvidence else TriggerEvidence.NO_OBSERVED_TRIGGER
                    mid.merge(ev, 1, Int::plus)
                }
            }
        }
        return TriggerBreakdown(starts, mid)
    }

    /** Self-report on the episodes the app took real part in. */
    fun value(pkgId: Long, sessions: List<SessionRow>, isTransient: (Long) -> Boolean): ValueSummary {
        val involved = sessions.filter { s -> s.segments.any { it.pkgId == pkgId && isReal(it, isTransient) } }
        val ratings = involved.mapNotNull { s -> s.worthItRating?.takeIf { it in 1..5 } }
        return ValueSummary(
            episodes = involved.size,
            rated = ratings.size,
            averageRating = ratings.takeIf { it.isNotEmpty() }?.average(),
            worth = WorthTotals.of(involved.map { it.worthItRating to it.activeDurationMs }),
        )
    }

    /**
     * Launch-pause outcomes for the app. An immediate exit is a pause the user left (home, system navigation or a
     * healthy redirect); a quick reopen is another open of the app (a real run or another pause) within 10 minutes
     * of that exit.
     */
    fun friction(events: List<FrictionRow>, runStarts: List<Long>, rules: AppInsightRules = AppInsightRules()): FrictionSummary {
        val sorted = events.sortedBy { it.timestampMs }
        val pauses = sorted.filter { it.eventKind == FrictionVocab.LAUNCH_PAUSE }
        val exits = pauses.filter { it.outcome in FrictionVocab.BLOCKING_OUTCOMES }
        val opens = (runStarts + pauses.map { it.timestampMs }).sorted()
        val quick = exits.count { e ->
            opens.any { it > e.timestampMs && it - e.timestampMs <= rules.quickReopenMs }
        }
        return FrictionSummary(
            launchPauses = pauses.size,
            immediateExits = exits.size,
            quickReopens = quick,
            timerCheckIns = sorted.count { it.eventKind == TIMER_EXPIRED },
            extensions = sorted.count { it.outcome == EXTENDED_5M },
        )
    }

    const val TIMER_EXPIRED = "TIMER_EXPIRED"
    const val EXTENDED_5M = "EXTENDED_5M"
}
