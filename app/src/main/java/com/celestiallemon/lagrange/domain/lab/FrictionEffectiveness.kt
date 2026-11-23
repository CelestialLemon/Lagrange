package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.Span
import com.celestiallemon.lagrange.domain.Spans
import com.celestiallemon.lagrange.domain.UsageCoverage
import java.time.LocalDate

// Honest Friction Effectiveness (spec §4.2 C). Counts, not claims: an exit only counts as "stayed away" once ten
// continuously observed minutes have passed with no distracting app reopened; anything less is unresolved, never
// assumed a success. Shifts in category totals are reported as minutes next to each other, never as cause and effect.

/** A `friction_events` row, as far as the Lab cares. */
data class LabFriction(
    val timestampMs: Long,
    val pkgId: Long,
    val eventKind: String,
    val outcome: String,
    val reactionMs: Long? = null,
    val selectedTimerMin: Int? = null,
    /** The countdown length; the row's timestamp is when the prompt was shown, not when it was answered. */
    val pauseDurationSec: Int = 0,
) {
    /**
     * When the user actually left, as tight as the row allows: exactly countdown + reaction after the prompt appeared
     * when a reaction was recorded, otherwise somewhere between the prompt appearing and the countdown ending.
     */
    val exitWindow: Span
        get() {
            val countdownEnd = timestampMs + pauseDurationSec.coerceAtLeast(0) * 1_000L
            return if (reactionMs != null && reactionMs >= 0) Span(countdownEnd + reactionMs, countdownEnd + reactionMs)
            else Span(timestampMs, countdownEnd)
        }
}

data class FrictionRules(
    val sustainMs: Long = 10 * MINUTE_MS,
    /** A median reaction below this after the countdown reads as tapping through on autopilot. */
    val autopilotReactionMs: Long = 500,
    val minReactionsPerHalf: Int = 5,
    /** Per covered day, both shifts must be at least this big to be worth showing. */
    val minShiftMsPerDay: Long = 10 * MINUTE_MS,
    val minSubstitutionDays: Int = 3,
    val topRisingApps: Int = 3,
    val minRisingAppMsPerDay: Long = MINUTE_MS,
)

data class ExitOutcomes(val exits: Int, val sustained: Int, val quickReopens: Int, val unresolved: Int)

data class TimerAdherence(
    /** Launch pauses where a timer was chosen. */
    val timersSet: Int,
    val expiries: Int,
    val closedOnExpiry: Int,
    val extended: Int,
    val dismissed: Int,
)

data class Habituation(
    val samples: Int,
    val earlierMedianMs: Long?,
    val recentMedianMs: Long?,
    /** True when the recent median is under [FrictionRules.autopilotReactionMs]. */
    val autopilot: Boolean,
)

data class SubstitutionDay(val date: LocalDate, val distractingMs: Long, val neutralMs: Long)

data class Substitution(
    val currentDays: Int,
    val previousDays: Int,
    /** Per covered day. */
    val distractingBeforeMs: Long,
    val distractingNowMs: Long,
    val neutralBeforeMs: Long,
    val neutralNowMs: Long,
    val frictionActive: Boolean,
    /** Distracting down and neutral up by at least the threshold while friction was on. */
    val flagged: Boolean,
    /** Neutral apps that rose most, (pkg, per-day increase). */
    val risingNeutral: List<Pair<Long, Long>>,
) {
    val distractingChangeMs: Long get() = distractingNowMs - distractingBeforeMs
    val neutralChangeMs: Long get() = neutralNowMs - neutralBeforeMs
}

data class FrictionReport(
    val launchPauses: Int,
    val pausesPerCoveredDay: Double?,
    val exits: ExitOutcomes,
    val timer: TimerAdherence,
    val habituation: Habituation,
    /** Null until both periods have enough covered days. */
    val substitution: Substitution?,
    val snoozes: Int,
    /** Average share of each covered day the friction service was running. */
    val frictionCoverage: Double?,
    val coveredDays: Int,
)

data class FrictionInput(
    /** Friction events from the window start through the present (later events still reveal reopens). */
    val events: List<LabFriction>,
    /** Starts of real distracting runs from the window start on. */
    val distractingOpens: List<Long>,
    val fromMs: Long,
    val toMs: Long,
    val dataEndMs: Long,
    val coverage: UsageCoverage? = null,
    val coveredDays: Int = 0,
    val frictionCoverageRatios: List<Double> = emptyList(),
    val current: List<SubstitutionDay> = emptyList(),
    val previous: List<SubstitutionDay> = emptyList(),
    /** Per-app NEUTRAL totals over the covered days of each period. */
    val currentNeutralApps: Map<Long, Long> = emptyMap(),
    val previousNeutralApps: Map<Long, Long> = emptyMap(),
    /** The covered days' windows: the per-day prompt rate counts only prompts inside them (null: every prompt). */
    val coveredDaySpans: List<Span>? = null,
    /** `friction_enabled` history: (effective ms, value), any order. */
    val enabledHistory: List<Pair<Long, Boolean>> = emptyList(),
    /** Where the current substitution period starts (All-Time compares its halves); defaults to [fromMs]. */
    val substitutionFromMs: Long? = null,
    /** Where it ends: the end of its last settled covered day, so today's switch can't speak for past days. */
    val substitutionToMs: Long? = null,
)

object FrictionAnalyzer {
    private val CLOSED = setOf(FrictionVocab.OUTCOME_CLOSED_GO_HOME, FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV)

    fun analyze(input: FrictionInput, apps: LabApps, rules: FrictionRules = FrictionRules()): FrictionReport {
        val sorted = input.events.sortedBy { it.timestampMs }
        val inWindow = sorted.filter { it.timestampMs >= input.fromMs && it.timestampMs < input.toMs }
        val pauses = inWindow.filter { it.eventKind == FrictionVocab.LAUNCH_PAUSE }
        val expiries = inWindow.filter { it.eventKind == FrictionVocab.TIMER_EXPIRED }
        return FrictionReport(
            launchPauses = pauses.size,
            pausesPerCoveredDay = if (input.coveredDays == 0) null else {
                val onCovered = input.coveredDaySpans?.let { spans -> pauses.count { Spans.contains(spans, it.timestampMs) } } ?: pauses.size
                onCovered.toDouble() / input.coveredDays
            },
            exits = exits(sorted, input, apps, rules),
            timer = TimerAdherence(
                // A timer only starts when the user goes on; a choice followed by Go Home never ran.
                timersSet = pauses.count { it.outcome == FrictionVocab.OUTCOME_CONTINUED && it.selectedTimerMin != null && it.selectedTimerMin > 0 },
                expiries = expiries.size,
                closedOnExpiry = expiries.count { it.outcome in CLOSED },
                extended = expiries.count { it.outcome == FrictionVocab.OUTCOME_EXTENDED_5M },
                dismissed = expiries.count { it.outcome == FrictionVocab.OUTCOME_DISMISSED_SESSION },
            ),
            habituation = habituation(pauses, rules),
            substitution = substitution(
                input,
                pauses.any { it.timestampMs >= (input.substitutionFromMs ?: input.fromMs) && it.timestampMs < (input.substitutionToMs ?: input.toMs) },
                rules,
            ),
            snoozes = inWindow.count { it.eventKind == FrictionVocab.SNOOZE_1H },
            frictionCoverage = input.frictionCoverageRatios.takeIf { it.isNotEmpty() }?.average(),
            coveredDays = input.coveredDays,
        )
    }

    /**
     * Each immediate exit, timed from when the user actually left ([LabFriction.exitWindow]): a quick reopen if a
     * distracting app was opened (or its launch paused) within 10 min of it; sustained once 10 continuously observed
     * minutes passed with no such open; otherwise unresolved. When the row only bounds the exit instant, an open whose
     * verdict depends on where in that bound the exit fell is unresolved too.
     */
    fun exits(sortedEvents: List<LabFriction>, input: FrictionInput, apps: LabApps, rules: FrictionRules = FrictionRules()): ExitOutcomes {
        val exits = sortedEvents.filter {
            it.eventKind == FrictionVocab.LAUNCH_PAUSE && it.outcome in FrictionVocab.BLOCKING_OUTCOMES &&
                it.timestampMs >= input.fromMs && it.timestampMs < input.toMs
        }
        // Derived runs past the derived cutoff may still change; logged pauses are final.
        val opens = (input.distractingOpens.filter { it < input.dataEndMs } +
            sortedEvents.filter { it.eventKind == FrictionVocab.LAUNCH_PAUSE && apps.isDistracting(it.pkgId) }.map { it.timestampMs }).sorted()
        var sustained = 0
        var quick = 0
        var unresolved = 0
        for (e in exits) {
            val (earliest, latest) = e.exitWindow.let { it.startMs to it.endMs }
            // The paused open itself (at the prompt) and anything before the user could have left don't count.
            val next = opens.firstOrNull { it > latest }
            val followEnd = latest + rules.sustainMs
            when {
                next != null && next - earliest < rules.sustainMs -> quick++ // within 10 min whenever exactly they left
                next != null && next < followEnd -> unresolved++ // within 10 min of some possible exit instants only
                followEnd > input.dataEndMs || (input.coverage != null && !input.coverage.isContinuous(earliest, followEnd)) -> unresolved++
                else -> sustained++
            }
        }
        return ExitOutcomes(exits.size, sustained, quick, unresolved)
    }

    /** Reaction after the countdown on pauses the user went through with: earlier half vs recent half of the window. */
    fun habituation(pauses: List<LabFriction>, rules: FrictionRules = FrictionRules()): Habituation {
        val reactions = pauses.filter { it.outcome == FrictionVocab.OUTCOME_CONTINUED && it.reactionMs != null && it.reactionMs >= 0 }
            .sortedBy { it.timestampMs }.map { it.reactionMs!! }
        val half = reactions.size / 2
        val earlier = reactions.take(half)
        val recent = reactions.drop(half)
        val enough = earlier.size >= rules.minReactionsPerHalf && recent.size >= rules.minReactionsPerHalf
        val recentMedian = if (enough) medianLong(recent) else null
        return Habituation(
            samples = reactions.size,
            earlierMedianMs = if (enough) medianLong(earlier) else null,
            recentMedianMs = recentMedian,
            autopilot = recentMedian != null && recentMedian < rules.autopilotReactionMs,
        )
    }

    fun frictionEnabledDuring(history: List<Pair<Long, Boolean>>, fromMs: Long, toMs: Long): Boolean {
        val sorted = history.sortedBy { it.first }
        val atStart = sorted.lastOrNull { it.first <= fromMs }?.second ?: false
        return atStart || sorted.any { it.first in fromMs until toMs && it.second }
    }

    fun substitution(input: FrictionInput, pausedInWindow: Boolean, rules: FrictionRules = FrictionRules()): Substitution? {
        val cur = input.current
        val prev = input.previous
        if (cur.size < rules.minSubstitutionDays || prev.size < rules.minSubstitutionDays) return null
        fun avg(days: List<SubstitutionDay>, f: (SubstitutionDay) -> Long) = days.sumOf(f) / days.size
        val dBefore = avg(prev) { it.distractingMs }
        val dNow = avg(cur) { it.distractingMs }
        val nBefore = avg(prev) { it.neutralMs }
        val nNow = avg(cur) { it.neutralMs }
        val active = pausedInWindow || frictionEnabledDuring(input.enabledHistory, input.substitutionFromMs ?: input.fromMs, input.substitutionToMs ?: input.toMs)
        val rising = (input.currentNeutralApps.keys + input.previousNeutralApps.keys).map { pkg ->
            pkg to ((input.currentNeutralApps[pkg] ?: 0L) / cur.size - (input.previousNeutralApps[pkg] ?: 0L) / prev.size)
        }.filter { it.second >= rules.minRisingAppMsPerDay }
            .sortedWith(compareByDescending<Pair<Long, Long>> { it.second }.thenBy { it.first })
            .take(rules.topRisingApps)
        return Substitution(
            currentDays = cur.size,
            previousDays = prev.size,
            distractingBeforeMs = dBefore,
            distractingNowMs = dNow,
            neutralBeforeMs = nBefore,
            neutralNowMs = nNow,
            frictionActive = active,
            flagged = active && dBefore - dNow >= rules.minShiftMsPerDay && nNow - nBefore >= rules.minShiftMsPerDay,
            risingNeutral = rising,
        )
    }
}
