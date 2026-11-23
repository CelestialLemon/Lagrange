package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.domain.CoverageSpan
import com.celestiallemon.lagrange.domain.HOUR_MS
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

    private fun pause(at: Long, outcome: String, pkg: Long = INSTA, reaction: Long? = null, timer: Int? = null) =
        LabFriction(at, pkg, FrictionVocab.LAUNCH_PAUSE, outcome, reaction, timer)

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
    fun `friction counts as enabled from its last setting before the window or any enable inside it`() {
        assertTrue(FrictionAnalyzer.frictionEnabledDuring(listOf(0L to true), 10, 20))
        assertFalse(FrictionAnalyzer.frictionEnabledDuring(listOf(0L to true, 5L to false), 10, 20))
        assertTrue(FrictionAnalyzer.frictionEnabledDuring(listOf(0L to false, 15L to true), 10, 20))
        assertFalse(FrictionAnalyzer.frictionEnabledDuring(listOf(25L to true), 10, 20))
        assertFalse(FrictionAnalyzer.frictionEnabledDuring(emptyList(), 10, 20))
        assertNotNull(FrictionInput(emptyList(), emptyList(), 0, 1, 1))
    }
}
