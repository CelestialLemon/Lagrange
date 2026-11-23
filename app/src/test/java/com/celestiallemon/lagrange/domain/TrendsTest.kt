package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendsTest {
    // 2026-11-23 is a Monday.
    private val today = LocalDate.parse("2026-11-23")

    private fun d(offset: Long) = today.plusDays(offset)

    private fun covered(date: LocalDate, v: Double) = DaySample(date, DayQuality.COVERED, v)

    private fun samples(vararg pairs: Pair<LocalDate, DaySample>) = pairs.toMap()

    // -- Windows -----------------------------------------------------------------------------------------------------

    @Test
    fun `fixed windows end today and span exactly their length`() {
        for (kind in listOf(WindowKind.D7, WindowKind.D30, WindowKind.D90, WindowKind.Y1)) {
            val r = TrendWindow(kind).resolve(today, earliest = d(-1000))
            assertEquals(today, r.end)
            assertEquals(kind.days, r.days)
        }
        assertEquals(DateRange(d(-6), today), TrendWindow(WindowKind.D7).resolve(today, null))
    }

    @Test
    fun `all time starts at the first day with data and has nothing to compare with`() {
        val all = TrendWindow(WindowKind.ALL)
        assertEquals(DateRange(d(-40), today), all.resolve(today, d(-40)))
        assertEquals(DateRange(today, today), all.resolve(today, null))
        // A clock that went backwards never yields an inverted range.
        assertEquals(DateRange(today, today), all.resolve(today, d(3)))
        assertFalse(all.hasPrevious)
        assertTrue(TrendWindow(WindowKind.D30).hasPrevious)
    }

    @Test
    fun `custom ranges are ordered, clamped to today and never empty`() {
        assertEquals(DateRange(d(-10), d(-3)), TrendWindow.custom(d(-3), d(-10)).resolve(today, null))
        assertEquals(DateRange(d(-2), today), TrendWindow.custom(d(-2), d(5)).resolve(today, null))
        assertEquals(DateRange(today, today), TrendWindow.custom(d(2), d(5)).resolve(today, null))
        assertTrue(TrendWindow.custom(d(-3), d(-1)).hasPrevious)
    }

    @Test
    fun `the previous window has the same length and ends the day before`() {
        val r = DateRange(d(-6), today)
        assertEquals(DateRange(d(-13), d(-7)), r.previous())
        assertEquals(7, r.previous().days)
        assertEquals((0L..6).map { d(-6 + it) }, r.dates())
        assertTrue(d(-6) in r)
        assertFalse(d(-7) in r)
    }

    @Test
    fun `load range reaches the 27 day lead-in or the previous window, whichever is earlier`() {
        val week = DateRange(d(-6), today)
        assertEquals(DateRange(d(-33), today), TrendCalculator.loadRange(week))
        val quarter = DateRange(d(-89), today)
        assertEquals(DateRange(d(-179), today), TrendCalculator.loadRange(quarter))
    }

    // -- Day quality -------------------------------------------------------------------------------------------------

    @Test
    fun `only covered and backfill days count`() {
        assertEquals(DayQuality.NO_DATA, DayQuality.of(null, null, inProgress = false))
        assertEquals(DayQuality.NO_DATA, DayQuality.of(null, null, inProgress = true))
        assertEquals(DayQuality.IN_PROGRESS, DayQuality.of(DayCoverage.COMPLETE, DayStatus.NORMAL, inProgress = true))
        assertEquals(DayQuality.PARTIAL, DayQuality.of(DayCoverage.PARTIAL_HARVEST, DayStatus.UNCERTAIN_COVERAGE, false))
        assertEquals(DayQuality.PARTIAL, DayQuality.of(DayCoverage.COMPLETE, DayStatus.UNCERTAIN_COVERAGE, false))
        assertEquals(DayQuality.PARTIAL, DayQuality.of(DayCoverage.BACKFILL, DayStatus.UNCERTAIN_COVERAGE, false))
        assertEquals(DayQuality.BACKFILL, DayQuality.of(DayCoverage.BACKFILL, DayStatus.INSUFFICIENT_BASELINE, false))
        assertEquals(DayQuality.COVERED, DayQuality.of(DayCoverage.NO_NOTIF_COVERAGE, DayStatus.SPIKE, false))
        assertEquals(setOf(DayQuality.COVERED, DayQuality.BACKFILL), DayQuality.entries.filter { it.countsAsCovered }.toSet())
    }

    // -- Moving averages ---------------------------------------------------------------------------------------------

    @Test
    fun `uncovered gaps are skipped, never averaged in as zero`() {
        // 7 days: 4 covered at 60, then 3 uncovered of every kind.
        val days = listOf(
            covered(d(0), 60.0), covered(d(1), 60.0), covered(d(2), 60.0), covered(d(3), 60.0),
            DaySample(d(4), DayQuality.PARTIAL, 5.0),
            DaySample(d(5), DayQuality.NO_DATA, null),
            DaySample(d(6), DayQuality.IN_PROGRESS, 1.0),
        )
        val ma = TrendCalculator.movingAverage(days, window = 7, minCovered = 4)
        assertEquals(listOf(null, null, null, 60.0, 60.0, 60.0, 60.0), ma)
    }

    @Test
    fun `confirmed zero days pull the average down`() {
        val days = listOf(covered(d(0), 60.0), covered(d(1), 60.0), covered(d(2), 0.0), covered(d(3), 0.0))
        assertEquals(30.0, TrendCalculator.movingAverage(days, 7, 4).last()!!, 1e-9)
    }

    @Test
    fun `an average needs enough covered days and slides off old ones`() {
        val days = (0L until 10).map { covered(d(it), it.toDouble()) }
        val ma = TrendCalculator.movingAverage(days, window = 3, minCovered = 2)
        assertNull(ma[0])
        assertEquals(0.5, ma[1]!!, 1e-9)
        assertEquals(1.0, ma[2]!!, 1e-9)
        assertEquals(8.0, ma[9]!!, 1e-9) // 7, 8, 9
        // A gap inside the window shrinks the sample, not the value.
        val gappy = days.toMutableList().apply { this[8] = DaySample(d(8), DayQuality.NO_DATA, null) }
        assertEquals(8.0, TrendCalculator.movingAverage(gappy, 3, 2)[9]!!, 1e-9) // 7, 9
        assertNull(TrendCalculator.movingAverage(gappy, 3, 3)[9])
    }

    @Test
    fun `backfill days feed averages`() {
        val days = (0L until 4).map { DaySample(d(it), DayQuality.BACKFILL, 10.0) }
        assertEquals(10.0, TrendCalculator.movingAverage(days, 7, 4).last()!!, 1e-9)
    }

    @Test
    fun `series fills missing dates as no data and uses the lead-in for early averages`() {
        val range = DateRange(d(-6), today)
        // Covered days only in the lead-in (before the range) plus the range's first day.
        val map = (7L..12).associate { d(-it) to covered(d(-it), 100.0) } + (d(-6) to covered(d(-6), 100.0))
        val points = TrendCalculator.series(range, map)
        assertEquals(7, points.size)
        assertEquals(range.dates(), points.map { it.date })
        assertEquals(100.0, points.first().ma7!!, 1e-9)
        assertEquals(DayQuality.NO_DATA, points[1].sample.quality)
        assertNull(points[1].sample.value)
        // 7 covered days in the 28-day window is below the long minimum.
        assertNull(points.first().ma28)
    }

    // -- Period deltas -----------------------------------------------------------------------------------------------

    @Test
    fun `delta compares mean and median against the equal-length previous window`() {
        val range = DateRange(d(-3), today) // 4 days, needs max(3, 2) = 3 covered on each side
        val map = samples(
            d(-7) to covered(d(-7), 10.0), d(-6) to covered(d(-6), 20.0), d(-5) to covered(d(-5), 30.0),
            d(-4) to DaySample(d(-4), DayQuality.PARTIAL, 999.0),
            d(-3) to covered(d(-3), 40.0), d(-2) to covered(d(-2), 20.0), d(-1) to covered(d(-1), 60.0),
            today to DaySample(today, DayQuality.IN_PROGRESS, 500.0),
        )
        val delta = TrendCalculator.delta(range, map, withPrevious = true)
        assertEquals(3, delta.current.coveredDays)
        assertEquals(3, delta.previous!!.coveredDays)
        assertEquals(3, delta.minCoveredDays)
        assertTrue(delta.sufficient)
        assertEquals(40.0 - 20.0, delta.deltaMean!!, 1e-9)
        assertEquals(40.0 - 20.0, delta.deltaMedian!!, 1e-9)
        assertEquals(1.0, delta.pctChange!!, 1e-9)
    }

    @Test
    fun `delta is hidden when either side has too few covered days`() {
        val range = DateRange(d(-6), today) // needs 4
        val map = (0L..6).associate { d(-it) to covered(d(-it), 10.0) } + (7L..9).associate { d(-it) to covered(d(-it), 5.0) }
        val delta = TrendCalculator.delta(range, map, true)
        assertEquals(4, delta.minCoveredDays)
        assertEquals(3, delta.previous!!.coveredDays)
        assertFalse(delta.sufficient)
        assertNull(delta.deltaMean)
        assertNull(delta.deltaMedian)
        assertNull(delta.pctChange)
    }

    @Test
    fun `a change from zero has no percentage and all time has no previous window`() {
        val range = DateRange(d(-2), today)
        val map = (0L..5).associate { d(-it) to covered(d(-it), if (it <= 2) 30.0 else 0.0) }
        val delta = TrendCalculator.delta(range, map, true)
        assertEquals(30.0, delta.deltaMean!!, 1e-9)
        assertNull(delta.pctChange)
        val allTime = TrendCalculator.delta(range, map, withPrevious = false)
        assertNull(allTime.previous)
        assertFalse(allTime.sufficient)
    }

    @Test
    fun `minimum delta days scale with the window`() {
        assertEquals(3, TrendCalculator.minDeltaDays(1))
        assertEquals(4, TrendCalculator.minDeltaDays(7))
        assertEquals(15, TrendCalculator.minDeltaDays(30))
        assertEquals(183, TrendCalculator.minDeltaDays(365))
    }

    @Test
    fun `median handles odd, even and empty lists`() {
        assertNull(TrendCalculator.median(emptyList()))
        assertEquals(2.0, TrendCalculator.median(listOf(3.0, 1.0, 2.0))!!, 1e-9)
        assertEquals(2.5, TrendCalculator.median(listOf(4.0, 1.0, 3.0, 2.0))!!, 1e-9)
    }

    // -- Weekday / weekend -------------------------------------------------------------------------------------------

    @Test
    fun `weekday and weekend averages use covered days only`() {
        // Two weeks ending Sunday 2026-11-22. Weekdays 60, weekends 120; one Saturday partial, one Tuesday missing.
        val range = DateRange(d(-14), d(-1))
        val map = range.dates().associateWith { date ->
            when {
                date == LocalDate.parse("2026-11-21") -> DaySample(date, DayQuality.PARTIAL, 999.0)
                date == LocalDate.parse("2026-11-17") -> DaySample(date, DayQuality.NO_DATA, null)
                Baselines.isWeekend(date) -> covered(date, 120.0)
                else -> covered(date, 60.0)
            }
        }
        val split = TrendCalculator.split(range, map)
        assertEquals(12, split.coveredDays)
        assertTrue(split.visible)
        assertEquals(60.0, split.weekday.mean!!, 1e-9)
        assertEquals(9, split.weekday.days)
        assertEquals(120.0, split.weekend.mean!!, 1e-9)
        assertEquals(3, split.weekend.days)
        assertEquals(1, split.byDayOfWeek[DayOfWeek.SATURDAY]!!.days)
        assertEquals(1, split.byDayOfWeek[DayOfWeek.TUESDAY]!!.days)
        assertEquals(2, split.byDayOfWeek[DayOfWeek.MONDAY]!!.days)
        assertEquals(60.0, split.byDayOfWeek[DayOfWeek.MONDAY]!!.mean!!, 1e-9)
    }

    @Test
    fun `splits stay hidden below seven covered days`() {
        val range = DateRange(d(-6), today)
        val map = (1L..6).associate { d(-it) to covered(d(-it), 10.0) } + (today to DaySample(today, DayQuality.IN_PROGRESS, 10.0))
        val split = TrendCalculator.split(range, map)
        assertEquals(6, split.coveredDays)
        assertFalse(split.visible)
        assertNull(split.byDayOfWeek[DayOfWeek.MONDAY]!!.mean)
    }

    // -- Valued vs regretted -----------------------------------------------------------------------------------------

    @Test
    fun `ratings bucket as valued, neutral, regretted and honestly unrated`() {
        val w = WorthTotals.of(
            listOf(5 to 10L, 4 to 20L, 3 to 5L, 2 to 7L, 1 to 3L, null to 100L, 0 to 50L, 9 to 1L),
        )
        assertEquals(30L, w.valuedMs)
        assertEquals(2, w.valuedCount)
        assertEquals(5L, w.neutralMs)
        assertEquals(1, w.neutralCount)
        assertEquals(10L, w.regrettedMs)
        assertEquals(2, w.regrettedCount)
        assertEquals(151L, w.unratedMs)
        assertEquals(3, w.unratedCount)
        assertEquals(5, w.ratedCount)
        assertEquals(20L, w.netValuedMs)
        assertEquals(w.valuedMs * 2, (w + w).valuedMs)
    }

    @Test
    fun `valued vs regretted draws both sides and trends on net valued time`() {
        val day = DailyMetrics(today, DayQuality.COVERED, worth = WorthTotals(valuedMs = 40, regrettedMs = 15))
        val m = TrendMetric.VALUED_VS_REGRETTED
        assertEquals(40.0, m.value(day), 0.0)
        assertEquals(15.0, m.secondary(day)!!, 0.0)
        assertEquals(25.0, m.trend(day), 0.0)
        assertTrue(m.diverging)
        assertTrue(m.higherIsBetter)
        assertFalse(m.showsBudget)
    }

    @Test
    fun `pickups carry episodes as their inner bar`() {
        val day = DailyMetrics(today, DayQuality.COVERED, pickups = 12, episodes = 9)
        assertEquals(12.0, TrendMetric.PHYSICAL_PICKUPS.value(day), 0.0)
        assertEquals(9.0, TrendMetric.PHYSICAL_PICKUPS.secondary(day)!!, 0.0)
        assertNull(TrendMetric.EPISODES.secondary(day))
    }

    // -- Report ------------------------------------------------------------------------------------------------------

    @Test
    fun `report counts day kinds, totals what was seen and draws the budget only for distracting`() {
        val window = TrendWindow(WindowKind.D7)
        val range = window.resolve(today, null)
        val days = TrendCalculator.loadRange(range).dates().associateWith { date ->
            when (date) {
                today -> DailyMetrics(date, DayQuality.IN_PROGRESS, distractingMs = 7, budgetMs = 100)
                d(-1) -> DailyMetrics(date, DayQuality.PARTIAL, distractingMs = 50, budgetMs = 100, worth = WorthTotals(valuedMs = 9, valuedCount = 1))
                d(-2) -> DailyMetrics(date, DayQuality.NO_DATA)
                else -> DailyMetrics(date, DayQuality.COVERED, distractingMs = 20, budgetMs = 100, worth = WorthTotals(regrettedMs = 1, regrettedCount = 1))
            }
        }
        val r = TrendCalculator.report(TrendMetric.DISTRACTING, window, range, days)
        assertEquals(7, r.points.size)
        assertEquals(4, r.coveredDays)
        assertEquals(1, r.partialDays)
        assertEquals(1, r.noDataDays)
        assertEquals(1, r.inProgressDays)
        assertEquals(4 * 20.0 + 50 + 7, r.observedTotal, 1e-9)
        assertEquals(20.0, r.coveredMean!!, 1e-9)
        assertEquals(100.0, r.points.first().reference!!, 0.0)
        assertNull(r.points.first { it.date == d(-2) }.reference)
        assertEquals(1, r.worth.valuedCount)
        assertEquals(4, r.worth.regrettedCount)
        assertTrue(r.delta.sufficient)
        assertEquals(0.0, r.delta.deltaMean!!, 1e-9)

        val active = TrendCalculator.report(TrendMetric.ACTIVE_USAGE, window, range, days)
        assertTrue(active.points.all { it.reference == null })
    }

    @Test
    fun `no-data days have no value in a report`() {
        val range = DateRange(d(-1), today)
        val days = mapOf(d(-1) to DailyMetrics(d(-1), DayQuality.NO_DATA, distractingMs = 999))
        val s = TrendCalculator.samplesFor(TrendMetric.DISTRACTING, days)
        assertNull(s[d(-1)]!!.value)
        assertNull(s[d(-1)]!!.trend)
        val r = TrendCalculator.report(TrendMetric.DISTRACTING, TrendWindow.custom(d(-1), today), range, days)
        assertEquals(0.0, r.observedTotal, 0.0)
        assertNull(r.coveredMean)
        assertNotNull(r.points.last())
    }
}
