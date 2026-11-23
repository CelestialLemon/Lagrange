package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.domain.CoverageSpan
import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.Span
import com.celestiallemon.lagrange.domain.UsageCoverage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrictionEffectivenessTest {
    private val day = 24 * HOUR_MS

    private fun pause(at: Long, outcome: String, pkg: Long = INSTA, reaction: Long? = null, timer: Int? = null, countdownSec: Int = 0) =
        LabFriction(at, pkg, FrictionVocab.LAUNCH_PAUSE, outcome, reaction, timer, countdownSec)

    private fun expiry(at: Long, outcome: String) = LabFriction(at, INSTA, FrictionVocab.TIMER_EXPIRED, outcome)

    private val harvest = UsageCoverage.of(
        listOf(
            CoverageSpan(ServiceKind.USAGE_HARVEST, CoverageStatus.ACTIVE, 0, day),
            CoverageSpan(ServiceKind.USAGE_HARVEST, CoverageStatus.GAP, 5 * HOUR_MS, 5 * HOUR_MS + 2 * MIN),
        ),
    )

    @Test
    fun `an exit counts as sustained only after ten continuously observed minutes`() {
        val h = HOUR_MS
        val events = listOf(
            pause(1 * h, FrictionVocab.OUTCOME_CLOSED_GO_HOME), // nothing for an hour: sustained
            pause(2 * h, FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV), // TikTok run 4 min later: quick reopen
            pause(3 * h, FrictionVocab.OUTCOME_REDIRECTED), // another paused distracting launch 9 min later: quick reopen
            pause(3 * h + 9 * MIN, FrictionVocab.OUTCOME_CONTINUED),
            pause(5 * h - 5 * MIN, FrictionVocab.OUTCOME_CLOSED_GO_HOME), // a harvest gap 5 min later: unresolved
            pause(6 * h, FrictionVocab.OUTCOME_CLOSED_GO_HOME), // a Maps run 3 min later is not a reopen: sustained
            pause(8 * h - 5 * MIN, FrictionVocab.OUTCOME_CLOSED_GO_HOME), // data ends in 5 min: unresolved
            pause(8 * h - 20 * MIN, FrictionVocab.OUTCOME_CONTINUED), // not an exit
        )
        val input = FrictionInput(
            events = events,
            distractingOpens = listOf(2 * h + 4 * MIN, 8 * h + MIN),
            fromMs = 0,
            toMs = day,
            dataEndMs = 8 * h,
            coverage = harvest,
        )
        val out = FrictionAnalyzer.exits(events.sortedBy { it.timestampMs }, input, testApps)
        assertEquals(ExitOutcomes(exits = 6, sustained = 2, quickReopens = 2, unresolved = 2), out)
    }

    @Test
    fun `a run that starts exactly ten minutes later no longer counts as a quick reopen`() {
        val events = listOf(pause(0, FrictionVocab.OUTCOME_CLOSED_GO_HOME))
        val input = FrictionInput(events, listOf(10 * MIN), 0, day, day, harvest)
        assertEquals(1, FrictionAnalyzer.exits(events, input, testApps).sustained)
        // Without any coverage information the analyzer only needs the data to reach that far.
        assertEquals(1, FrictionAnalyzer.exits(events, input.copy(coverage = null, distractingOpens = emptyList()), testApps).sustained)
    }

    @Test
    fun `the ten minutes run from when the user left, not from when the prompt appeared`() {
        val shown = 12 * HOUR_MS
        // 30 s countdown, left 15 s after it: out at 12:00:45. A reopen at 12:10:20 is 9m35s later: quick.
        val exact = listOf(pause(shown, FrictionVocab.OUTCOME_CLOSED_GO_HOME, reaction = 15_000, countdownSec = 30))
        val reopen = shown + 10 * MIN + 20_000
        assertEquals(1, FrictionAnalyzer.exits(exact, FrictionInput(exact, listOf(reopen), 0, day, day, harvest), testApps).quickReopens)
        // Left during the countdown (no reaction): out somewhere in 12:00:00–12:00:30. A reopen at 12:10:10 is within
        // ten minutes of some of those instants only, so it can't be called either way.
        val bounded = listOf(pause(shown, FrictionVocab.OUTCOME_CLOSED_GO_HOME, countdownSec = 30))
        val ambiguous = FrictionAnalyzer.exits(bounded, FrictionInput(bounded, listOf(shown + 10 * MIN + 10_000), 0, day, day, harvest), testApps)
        assertEquals(ExitOutcomes(1, sustained = 0, quickReopens = 0, unresolved = 1), ambiguous)
        // The follow-up must be complete after the latest possible exit instant too.
        val late = FrictionAnalyzer.exits(bounded, FrictionInput(bounded, emptyList(), 0, day, shown + 10 * MIN + 10_000, harvest), testApps)
        assertEquals(1, late.unresolved)
        assertEquals(Span(shown + 45_000, shown + 45_000), exact.single().exitWindow)
    }

    @Test
    fun `an open during the countdown bound came after the user left and is a quick reopen`() {
        val shown = 12 * HOUR_MS
        // Left during a 12 s countdown (no reaction recorded), then straight into TikTok at 12:00:05.
        val events = listOf(pause(shown, FrictionVocab.OUTCOME_CLOSED_GO_HOME, countdownSec = 12))
        val out = FrictionAnalyzer.exits(events, FrictionInput(events, listOf(shown + 5_000), 0, day, day, harvest), testApps)
        assertEquals(ExitOutcomes(1, sustained = 0, quickReopens = 1, unresolved = 0), out)
    }

    @Test
    fun `a direct switch logged a moment before the recorded exit is a quick reopen`() {
        val shown = 12 * HOUR_MS
        // 30 s countdown, left 15 s after it (12:00:45) by switching straight to TikTok, whose run starts at 12:00:44.9.
        val events = listOf(pause(shown, FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, reaction = 15_000, countdownSec = 30))
        val out = FrictionAnalyzer.exits(events, FrictionInput(events, listOf(shown + 44_900), 0, day, day, harvest), testApps)
        assertEquals(1, out.quickReopens)
    }

    @Test
    fun `reopening a paused app that isn't distracting is a reopen too`() {
        val events = listOf(pause(HOUR_MS, FrictionVocab.OUTCOME_CLOSED_GO_HOME, pkg = KINDLE))
        val input = FrictionInput(events, emptyList(), 0, day, day, harvest)
        assertEquals(1, FrictionAnalyzer.exits(events, input, testApps).sustained)
        val reopened = input.copy(ownOpens = mapOf(KINDLE to listOf(HOUR_MS + 30_000)))
        assertEquals(1, FrictionAnalyzer.exits(events, reopened, testApps).quickReopens)
        // Another neutral app's run is not a reopen of this one.
        assertEquals(1, FrictionAnalyzer.exits(events, input.copy(ownOpens = mapOf(MAPS to listOf(HOUR_MS + 30_000))), testApps).sustained)
    }

    @Test
    fun `a follow-up past what was loaded is unresolved, not sustained`() {
        val events = listOf(pause(0, FrictionVocab.OUTCOME_CLOSED_GO_HOME, reaction = 60_000))
        val input = FrictionInput(events, emptyList(), 0, day, day, harvest)
        assertEquals(1, FrictionAnalyzer.exits(events, input, testApps).sustained)
        // Opens were only loaded through 10 minutes after the prompt; the user left a minute later.
        assertEquals(1, FrictionAnalyzer.exits(events, input.copy(followUpToMs = 10 * MIN), testApps).unresolved)
    }

    @Test
    fun `a timer chosen and then abandoned with Go Home never started`() {
        val events = listOf(
            pause(0, FrictionVocab.OUTCOME_CLOSED_GO_HOME, timer = 5),
            pause(1, FrictionVocab.OUTCOME_CONTINUED, timer = 5),
        )
        assertEquals(1, FrictionAnalyzer.analyze(FrictionInput(events, emptyList(), 0, day, day), testApps).timer.timersSet)
    }

    @Test
    fun `prompts per day count only prompts on the covered days`() {
        val events = listOf(pause(HOUR_MS, FrictionVocab.OUTCOME_CONTINUED), pause(2 * HOUR_MS, FrictionVocab.OUTCOME_CONTINUED)) +
            (1..8).map { pause(day + it * HOUR_MS, FrictionVocab.OUTCOME_CONTINUED) }
        val input = FrictionInput(events, emptyList(), 0, 2 * day, 2 * day, coveredDays = 1, coveredDaySpans = listOf(Span(0, day)))
        val r = FrictionAnalyzer.analyze(input, testApps)
        assertEquals(10, r.launchPauses)
        assertEquals(2.0, r.pausesPerCoveredDay!!, 1e-9)
    }

    @Test
    fun `timer adherence counts what happened when timers ran out`() {
        val events = listOf(
            pause(0, FrictionVocab.OUTCOME_CONTINUED, timer = 10),
            pause(1, FrictionVocab.OUTCOME_CONTINUED, timer = 0),
            pause(2, FrictionVocab.OUTCOME_CONTINUED),
            expiry(10, FrictionVocab.OUTCOME_CLOSED_GO_HOME),
            expiry(11, FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV),
            expiry(12, FrictionVocab.OUTCOME_EXTENDED_5M),
            expiry(13, FrictionVocab.OUTCOME_DISMISSED_SESSION),
            LabFriction(20, INSTA, FrictionVocab.SNOOZE_1H, FrictionVocab.OUTCOME_SNOOZE_ACTIVATED),
            expiry(day + 1, FrictionVocab.OUTCOME_CLOSED_GO_HOME), // after the window
        )
        val r = FrictionAnalyzer.analyze(FrictionInput(events, emptyList(), 0, day, day, coveredDays = 2, frictionCoverageRatios = listOf(1.0, 0.5)), testApps)
        assertEquals(TimerAdherence(timersSet = 1, expiries = 4, closedOnExpiry = 2, extended = 1, dismissed = 1), r.timer)
        assertEquals(3, r.launchPauses)
        assertEquals(1.5, r.pausesPerCoveredDay!!, 1e-9)
        assertEquals(1, r.snoozes)
        assertEquals(0.75, r.frictionCoverage!!, 1e-9)
        assertNull(FrictionAnalyzer.analyze(FrictionInput(emptyList(), emptyList(), 0, day, day), testApps).pausesPerCoveredDay)
    }

    @Test
    fun `habituation compares reaction medians and flags autopilot under half a second`() {
        val reactions = listOf(2_000L, 1_800, 1_500, 1_600, 1_700, 400, 300, 450, 350, 900)
        val pauses = reactions.mapIndexed { i, r -> pause(i * 1_000L, FrictionVocab.OUTCOME_CONTINUED, reaction = r) } +
            pause(50_000, FrictionVocab.OUTCOME_CLOSED_GO_HOME, reaction = 100) // not a continue: ignored
        val h = FrictionAnalyzer.habituation(pauses)
        assertEquals(10, h.samples)
        assertEquals(1_700L, h.earlierMedianMs)
        assertEquals(400L, h.recentMedianMs)
        assertTrue(h.autopilot)
        // Too few samples in either half: no medians and no autopilot verdict.
        val thin = FrictionAnalyzer.habituation(pauses.take(9))
        assertNull(thin.recentMedianMs)
        assertFalse(thin.autopilot)
    }

    private fun days(n: Int, distracting: Long, neutral: Long, start: LocalDate = LocalDate.of(2026, 11, 1)) =
        (0 until n).map { SubstitutionDay(start.plusDays(it.toLong()), distracting * MIN, neutral * MIN) }

    @Test
    fun `substitution is flagged only when friction was on and both shifts are big enough`() {
        val input = FrictionInput(
            events = emptyList(),
            distractingOpens = emptyList(),
            fromMs = 10 * day,
            toMs = 20 * day,
            dataEndMs = 20 * day,
            current = days(5, 60, 50),
            previous = days(5, 90, 30),
            currentNeutralApps = mapOf(KINDLE to 5 * 40 * MIN, MAPS to 5 * 10 * MIN),
            previousNeutralApps = mapOf(KINDLE to 5 * 15 * MIN, MAPS to 5 * 15 * MIN),
            enabledHistory = listOf(0L to false, 12 * day to true),
        )
        val s = FrictionAnalyzer.substitution(input, pausedInWindow = false)!!
        assertTrue(s.frictionActive)
        assertTrue(s.flagged)
        assertEquals(-30 * MIN, s.distractingChangeMs)
        assertEquals(20 * MIN, s.neutralChangeMs)
        assertEquals(listOf(KINDLE to 25 * MIN), s.risingNeutral)

        // Friction off the whole time: the minutes are still shown, but nothing is flagged.
        val off = FrictionAnalyzer.substitution(input.copy(enabledHistory = listOf(0L to false)), pausedInWindow = false)!!
        assertFalse(off.frictionActive)
        assertFalse(off.flagged)
        // A neutral rise under 10 min a day isn't a shift.
        assertFalse(FrictionAnalyzer.substitution(input.copy(current = days(5, 60, 39)), false)!!.flagged)
        // Fewer than three covered days in either period: nothing to compare.
        assertNull(FrictionAnalyzer.substitution(input.copy(previous = days(2, 90, 30)), false))
    }

    @Test
    fun `friction switched on after the compared days does not mark them as under friction`() {
        val input = FrictionInput(
            events = listOf(pause(19 * day, FrictionVocab.OUTCOME_CONTINUED)),
            distractingOpens = emptyList(),
            fromMs = 10 * day,
            toMs = 20 * day,
            dataEndMs = 20 * day,
            current = days(5, 60, 50),
            previous = days(5, 90, 30),
            enabledHistory = listOf(0L to false, 19 * day to true),
            // The settled covered days the average is made of; today (day 19) and the gap day 13 aren't among them.
            substitutionSpans = listOf(10, 11, 12, 14, 15).map { Span(it * day, (it + 1) * day) },
        )
        assertFalse(FrictionAnalyzer.analyze(input, testApps).substitution!!.frictionActive)
        assertTrue(FrictionAnalyzer.analyze(input.copy(substitutionSpans = null), testApps).substitution!!.frictionActive)
        // Friction on only during the excluded gap day: still not "with friction on".
        val gapDay = input.copy(events = emptyList(), enabledHistory = listOf(0L to false, 13 * day to true, 14 * day to false))
        assertFalse(FrictionAnalyzer.analyze(gapDay, testApps).substitution!!.frictionActive)
    }

    @Test
    fun `friction counts as enabled from its last setting before the window or any enable inside it`() {
        assertTrue(FrictionAnalyzer.frictionEnabledDuring(listOf(0L to true), 10, 20))
        assertFalse(FrictionAnalyzer.frictionEnabledDuring(listOf(0L to true, 5L to false), 10, 20))
        assertTrue(FrictionAnalyzer.frictionEnabledDuring(listOf(0L to false, 15L to true), 10, 20))
        assertFalse(FrictionAnalyzer.frictionEnabledDuring(listOf(25L to true), 10, 20))
        assertFalse(FrictionAnalyzer.frictionEnabledDuring(emptyList(), 10, 20))
        assertNotNull(FrictionInput(emptyList(), emptyList(), 0, 1, 1))
    }
}
