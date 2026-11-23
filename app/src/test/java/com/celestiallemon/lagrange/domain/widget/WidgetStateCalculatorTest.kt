package com.celestiallemon.lagrange.domain.widget

import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WidgetStateCalculatorTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val now = 1_800_000_000_000L
    private val clock: (Long) -> String = { "T+${(it - now) / min}" }

    private fun input(
        distracting: Long = 30 * min,
        budget: Long = hour,
        rollup: Boolean = true,
        access: Boolean = true,
        coverage: DayCoverage? = DayCoverage.COMPLETE,
        status: DayStatus? = DayStatus.NORMAL,
        lastHarvest: Long? = now - 10 * min,
        friction: Boolean = true,
        a11y: Boolean = true,
        boost: Long? = null,
        snooze: Long? = null,
        inProgress: Long? = null,
        lastEnded: Long? = null,
    ) = WidgetInput(
        nowMs = now, hasUsageAccess = access, hasRollup = rollup, distractingMs = distracting, budgetMs = budget,
        dayCoverage = coverage, dayStatus = status, inProgressStartMs = inProgress, lastEndedMs = lastEnded,
        longestFocusGapMs = 95 * min, lastHarvestAtMs = lastHarvest, frictionEnabled = friction,
        accessibilityConnected = a11y, focusBoostUntilMs = boost, snoozeUntilMs = snooze,
    )

    @Test
    fun `on track, near budget and over budget`() {
        val on = WidgetStateCalculator.compute(input(), clock)
        assertEquals(WidgetStatus.ON_TRACK, on.status)
        assertEquals("30m", on.usedText)
        assertEquals("of 1h budget", on.budgetText)
        assertEquals("On track · 30m left", on.statusLabel)
        assertEquals(0.5f, on.progress, 0.001f)
        assertEquals("1h 35m", on.longestText)

        assertEquals(WidgetStatus.NEAR_BUDGET, WidgetStateCalculator.compute(input(distracting = 50 * min), clock).status)

        val over = WidgetStateCalculator.compute(input(distracting = 75 * min), clock)
        assertEquals(WidgetStatus.OVER_BUDGET, over.status)
        assertEquals("Over by 15m", over.statusLabel)
        assertEquals(1f, over.progress, 0f)
    }

    @Test
    fun `coverage gaps are named and the number is a lower bound`() {
        val gap = WidgetStateCalculator.compute(input(coverage = DayCoverage.PARTIAL_HARVEST), clock)
        assertEquals(WidgetStatus.COVERAGE_GAP, gap.status)
        assertEquals("≥ 30m", gap.usedText)
        val stale = WidgetStateCalculator.compute(input(lastHarvest = now - 49 * hour), clock)
        assertEquals(WidgetStatus.COVERAGE_GAP, stale.status)
        // Over budget still wins: the recorded part alone passed it.
        assertEquals(WidgetStatus.OVER_BUDGET, WidgetStateCalculator.compute(input(distracting = 2 * hour, status = DayStatus.UNCERTAIN_COVERAGE), clock).status)
    }

    @Test
    fun `missing access or data never renders a confident zero`() {
        val noAccess = WidgetStateCalculator.compute(input(access = false), clock)
        assertEquals(WidgetStatus.GRANT_ACCESS, noAccess.status)
        assertEquals("—", noAccess.usedText)
        val waiting = WidgetStateCalculator.compute(input(rollup = false), clock)
        assertEquals(WidgetStatus.WAITING, waiting.status)
        assertEquals("—", waiting.usedText)
    }

    @Test
    fun `friction badge shows boost, snooze or setup, and schedules its own expiry`() {
        val boost = WidgetStateCalculator.compute(input(boost = now + 40 * min), clock)
        assertEquals(WidgetBadgeKind.FOCUS_BOOST, boost.badge!!.kind)
        assertEquals("Focus Boost until T+40", boost.badge!!.text)
        assertEquals(now + 40 * min, boost.nextChangeAtMs)
        assertEquals(WidgetBadgeKind.SNOOZED, WidgetStateCalculator.compute(input(snooze = now + 5 * min), clock).badge!!.kind)
        assertEquals(WidgetBadgeKind.SET_UP_FRICTION, WidgetStateCalculator.compute(input(a11y = false), clock).badge!!.kind)
        assertNull(WidgetStateCalculator.compute(input(friction = false, boost = now + 5 * min), clock).badge)
        assertNull("an expired boost is no badge", WidgetStateCalculator.compute(input(boost = now - 1), clock).badge)
    }

    @Test
    fun `phone-free streak is a clock time while idle and the gap before an in-progress pickup`() {
        val idle = WidgetStateCalculator.compute(input(lastEnded = now - 20 * min), clock)
        assertEquals("Phone-free since" to "T+-20", idle.streakLabel to idle.streakText)
        val using = WidgetStateCalculator.compute(input(lastEnded = now - 50 * min, inProgress = now - 5 * min), clock)
        assertEquals("Before this pickup" to "45m", using.streakLabel to using.streakText)
    }
}
