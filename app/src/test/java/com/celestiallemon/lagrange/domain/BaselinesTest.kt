package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.DayStatus
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BaselinesTest {
    // 2026-11-23 is a Monday.
    private val monday = LocalDate.parse("2026-11-23")

    private fun point(date: LocalDate, activeMs: Long, eligible: Boolean = true, distracting: Long = 0, budget: Long? = null) =
        BaselinePoint(date, eligible, activeMs, distracting, budget)

    /** 28 prior days: weekdays at [weekdayMs], weekends at [weekendMs]. */
    private fun history(weekdayMs: (LocalDate) -> Long, weekendMs: (LocalDate) -> Long = weekdayMs): MutableMap<LocalDate, BaselinePoint> =
        (1..28L).map { monday.minusDays(it) }.associateWith { d ->
            point(d, if (Baselines.isWeekend(d)) weekendMs(d) else weekdayMs(d))
        }.toMutableMap()

    @Test
    fun `weekdays are compared with weekdays and weekends with weekends`() {
        val points = history({ 2 * HOUR_MS }, { 4 * HOUR_MS })
        assertEquals(2 * HOUR_MS, Baselines.baseline(monday, points).medianMs)
        assertEquals(4 * HOUR_MS, Baselines.baseline(LocalDate.parse("2026-11-22"), points.also { it.remove(LocalDate.parse("2026-11-22")) }).medianMs)
        assertEquals(20, Baselines.baseline(monday, points).matchedDays.size)
    }

    @Test
    fun `the baseline stays hidden until 7 covered days exist`() {
        val six = (1..6L).map { monday.minusDays(it) }.associateWith { point(it, HOUR_MS) }
        val b = Baselines.baseline(monday, six)
        assertNull(b.medianMs)
        assertEquals(6, b.coveredDays)
        assertEquals(DayStatus.INSUFFICIENT_BASELINE, Baselines.status(point(monday, HOUR_MS), b))

        val seven = (1..7L).map { monday.minusDays(it) }.associateWith { point(it, HOUR_MS) }
        assertEquals(HOUR_MS, Baselines.baseline(monday, seven).medianMs)
    }

    @Test
    fun `uncertain days are excluded and confirmed zero-use days are included`() {
        val points = history({ d -> if (d.dayOfMonth % 2 == 0) 0L else 2 * HOUR_MS })
        // Make a few days uncertain with absurd values: they must not move the median.
        points.keys.take(3).forEach { points[it] = point(it, 20 * HOUR_MS, eligible = false) }
        val weekdays = points.values.filter { it.eligible && !Baselines.isWeekend(it.date) }.map { it.activeMs }
        assertEquals(Baselines.median(weekdays), Baselines.baseline(monday, points).medianMs)
        assertEquals(points.values.count { it.eligible }, Baselines.baseline(monday, points).coveredDays)
        // Zero-use days pull the median down rather than being skipped.
        val zeros = history({ 0L }).also { it[monday.minusDays(1)] = point(monday.minusDays(1), 5 * HOUR_MS) }
        assertEquals(0L, Baselines.baseline(monday, zeros).medianMs)
    }

    @Test
    fun `day status - spike, good day, budget good day and normal`() {
        val base = Baselines.baseline(monday, history({ 2 * HOUR_MS }))
        assertEquals(DayStatus.SPIKE, Baselines.status(point(monday, 3 * HOUR_MS), base))
        assertEquals(DayStatus.NORMAL, Baselines.status(point(monday, 160 * MIN), base))
        assertEquals(DayStatus.GOOD_DAY, Baselines.status(point(monday, HOUR_MS), base))
        assertEquals(DayStatus.GOOD_DAY, Baselines.status(point(monday, 110 * MIN, distracting = 30 * MIN, budget = 45 * MIN), base))
        assertEquals(DayStatus.NORMAL, Baselines.status(point(monday, 110 * MIN, distracting = 50 * MIN, budget = 45 * MIN), base))
        assertEquals(DayStatus.UNCERTAIN_COVERAGE, Baselines.status(point(monday, 3 * HOUR_MS, eligible = false), base))
        // 1.4x but only 20 minutes over a small median is not a spike.
        val small = Baselines.baseline(monday, history({ 50 * MIN }))
        assertEquals(DayStatus.NORMAL, Baselines.status(point(monday, 70 * MIN), small))
    }

    @Test
    fun `partial-day slice compares today with the same time of day, pro-rating the current hour`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val now = LocalDateTime.parse("2026-11-23T14:30").atZone(zone).toInstant().toEpochMilli()
        val cutoff = PartialDayBaseline.cutoff(now, 4, zone)
        assertEquals(10, cutoff.slot)
        assertEquals(0.5, cutoff.fraction, 1e-9)
        // 30 min in each of slots 0..23: by 14:30 that's 10 full slots + half of slot 10.
        val slots = (0..23).associateWith { 30 * MIN }
        assertEquals(10 * 30 * MIN + 15 * MIN, PartialDayBaseline.sliceMs(slots, cutoff, null))
        // A repeated fall-back hour counts where its wall-clock hour falls.
        assertEquals(10 * MIN, PartialDayBaseline.sliceMs(mapOf(HourSlots.REPEATED_SLOT to 10 * MIN), cutoff, 9))
        assertEquals(0L, PartialDayBaseline.sliceMs(mapOf(HourSlots.REPEATED_SLOT to 10 * MIN), cutoff, 21))

        val points = history({ 4 * HOUR_MS })
        val prior = points.keys.associateWith { if (Baselines.isWeekend(it)) 3 * HOUR_MS else 90 * MIN }
        val slice = PartialDayBaseline.compare(monday, 2 * HOUR_MS, cutoff, prior, points)
        assertEquals(90 * MIN, slice.medianMs)
        assertEquals(20, slice.days)
        assertEquals(DayStatus.NORMAL, Baselines.partialStatus(true, 2 * HOUR_MS, slice.medianMs))
        assertEquals(DayStatus.SPIKE, Baselines.partialStatus(true, 130 * MIN, slice.medianMs))
        assertEquals(DayStatus.GOOD_DAY, Baselines.partialStatus(true, 60 * MIN, slice.medianMs))
        assertEquals(DayStatus.INSUFFICIENT_BASELINE, Baselines.partialStatus(true, 60 * MIN, null))
        assertEquals(DayStatus.UNCERTAIN_COVERAGE, Baselines.partialStatus(false, 60 * MIN, slice.medianMs))
    }

    @Test
    fun `the slice is hidden while the full-day baseline is`() {
        val zone = ZoneId.of("Asia/Kolkata")
        val cutoff = PartialDayBaseline.cutoff(LocalDateTime.parse("2026-11-23T14:30").atZone(zone).toInstant().toEpochMilli(), 4, zone)
        val few = (1..5L).map { monday.minusDays(it) }.associateWith { point(it, HOUR_MS) }
        val slice = PartialDayBaseline.compare(monday, HOUR_MS, cutoff, few.keys.associateWith { 30 * MIN }, few)
        assertNull(slice.medianMs)
    }
}
