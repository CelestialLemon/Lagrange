package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.HourSlots
import com.celestiallemon.lagrange.domain.Span
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RhythmTest {
    private val monday = LocalDate.of(2026, 11, 2)

    @Test
    fun `cells average each weekday over its covered days only`() {
        val days = listOf(
            RhythmDay(monday, distractingSlots = mapOf(10 to 30 * MIN), pickupSlots = mapOf(10 to 3)),
            RhythmDay(monday.plusWeeks(1), distractingSlots = mapOf(10 to 10 * MIN), pickupSlots = mapOf(10 to 1)),
            RhythmDay(monday.plusDays(2), distractingSlots = mapOf(0 to 50 * MIN)),
        )
        val map = RhythmAnalyzer.heatmap(days, rolloverHour = 4)
        assertEquals(listOf(2, 0, 1, 0, 0, 0, 0), map.daysPerRow)
        assertEquals(20 * MIN, map.cells[0][10].distractingMs)
        assertEquals(2.0, map.cells[0][10].pickups, 1e-9)
        assertEquals(50 * MIN, map.cells[2][0].distractingMs)
        // An uncovered weekday stays empty rather than reading as zero use.
        assertEquals(0L, map.cells[1][10].distractingMs)
        assertEquals(50 * MIN, map.maxDistractingMs)
        assertEquals(14, map.wallHour(10))
        assertEquals(3, map.wallHour(23))
    }

    @Test
    fun `the repeated DST hour folds into the hour it repeated and is counted`() {
        val fallBack = RhythmDay(
            monday,
            distractingSlots = mapOf(21 to 10 * MIN, HourSlots.REPEATED_SLOT to 20 * MIN),
            pickupSlots = mapOf(HourSlots.REPEATED_SLOT to 2),
            repeatedSlot = 21,
        )
        val lost = RhythmDay(monday.plusDays(1), distractingSlots = mapOf(HourSlots.REPEATED_SLOT to 7 * MIN))
        val map = RhythmAnalyzer.heatmap(listOf(fallBack, lost), rolloverHour = 4)
        assertEquals(30 * MIN, map.cells[0][21].distractingMs)
        assertEquals(2.0, map.cells[0][21].pickups, 1e-9)
        assertEquals(1, map.foldedDstDays)
        assertEquals(7 * MIN, map.unplacedMs)
        assertTrue(map.cells.all { it.size == 24 })
    }

    @Test
    fun `gap buckets have exclusive upper bounds`() {
        assertEquals(GapBucket.UNDER_5M, GapBucket.of(5 * MIN - 1))
        assertEquals(GapBucket.M5_15, GapBucket.of(5 * MIN))
        assertEquals(GapBucket.M15_60, GapBucket.of(15 * MIN))
        assertEquals(GapBucket.H1_3, GapBucket.of(HOUR_MS))
        assertEquals(GapBucket.H3_PLUS, GapBucket.of(3 * HOUR_MS))
    }

    @Test
    fun `gaps skip the overnight gap, handle overlaps and need both ends covered`() {
        val h = HOUR_MS
        val t0 = 100 * h
        val day = RhythmDay(
            monday,
            overnightGapMs = 6 * h,
            nightWindow = Span(t0, t0 + 8 * h),
            episodes = listOf(
                Span(t0, t0 + 10 * MIN),
                Span(t0 + 6 * h + 10 * MIN, t0 + 6 * h + 20 * MIN), // the overnight gap: left out
                Span(t0 + 6 * h + 22 * MIN, t0 + 6 * h + 40 * MIN), // 2 min
                Span(t0 + 6 * h + 30 * MIN, t0 + 6 * h + 35 * MIN), // overlaps the previous one: no gap
                Span(t0 + 7 * h + 40 * MIN, t0 + 7 * h + 45 * MIN), // 60 min
            ),
        )
        // Two days later (the day between wasn't covered): its first gap is unobserved, the next one counts.
        val later = RhythmDay(monday.plusDays(2), episodes = listOf(Span(t0 + 48 * h, t0 + 48 * h + MIN), Span(t0 + 48 * h + 11 * MIN, t0 + 48 * h + 12 * MIN)))
        val g = RhythmAnalyzer.gaps(listOf(day, later))
        assertEquals(3, g.count)
        assertEquals(1, g.buckets[GapBucket.UNDER_5M])
        assertEquals(1, g.buckets[GapBucket.M5_15])
        assertEquals(1, g.buckets[GapBucket.H1_3])
        assertEquals(0, g.buckets[GapBucket.H3_PLUS])
        assertEquals(10 * MIN, g.medianMs)
        assertNull(RhythmAnalyzer.gaps(emptyList()).medianMs)
    }

    @Test
    fun `a gap across the rollover between two covered days counts`() {
        val rollover = 200 * HOUR_MS
        val before = RhythmDay(monday, episodes = listOf(Span(rollover - 30 * MIN, rollover - 2 * MIN)))
        val after = RhythmDay(monday.plusDays(1), episodes = listOf(Span(rollover + 8 * MIN, rollover + 20 * MIN)))
        val g = RhythmAnalyzer.gaps(listOf(before, after))
        assertEquals(1, g.count)
        assertEquals(10 * MIN, g.medianMs)
    }

    @Test
    fun `a gap as long as the overnight gap but outside the night window is still a gap`() {
        val h = HOUR_MS
        val t0 = 300 * h
        val d = RhythmDay(
            monday,
            overnightGapMs = 2 * h,
            nightWindow = Span(t0 - 6 * h, t0 + 6 * h),
            episodes = listOf(Span(t0 - 3 * h, t0 - 2 * h), Span(t0, t0 + MIN), Span(t0 + 8 * h, t0 + 8 * h + MIN), Span(t0 + 10 * h + MIN, t0 + 10 * h + 2 * MIN)),
        )
        val g = RhythmAnalyzer.gaps(listOf(d))
        // 2h (night: dropped), ~8h (kept), 2h at midday (same length, outside the night window: kept).
        assertEquals(2, g.count)
        assertEquals(1, g.buckets[GapBucket.H1_3])
        assertEquals(1, g.buckets[GapBucket.H3_PLUS])
    }

    @Test
    fun `an overnight gap filed under the next day is still left out`() {
        // Both episodes are Monday's (before the rollover), but the rollup filed the gap under Tuesday, whose night
        // window holds its midpoint.
        val h = HOUR_MS
        val t0 = 400 * h
        val mon = RhythmDay(monday, episodes = listOf(Span(t0, t0 + MIN), Span(t0 + 3 * h, t0 + 3 * h + MIN)))
        val tue = RhythmDay(monday.plusDays(1), overnightGapMs = 3 * h - MIN, nightWindow = Span(t0, t0 + 12 * h))
        assertEquals(0, RhythmAnalyzer.gaps(listOf(mon, tue)).count)
        // Without that filing it would have counted.
        assertEquals(1, RhythmAnalyzer.gaps(listOf(mon)).count)
    }

    @Test
    fun `first pickup median is taken from the rollover so it wraps midnight`() {
        // 23:50, 00:10 and 00:30 with a 04:00 rollover: the middle one is 00:10, not noon.
        val days = listOf(23 * 60 + 50, 10, 30).mapIndexed { i, m -> RhythmDay(monday.plusDays(i.toLong()), firstPickupMinute = m) }
        val s = RhythmAnalyzer.sleep(days, rolloverHour = 4)
        assertEquals(10, s.firstPickupMinute)
        assertEquals(3, s.firstPickupDays)
    }

    @Test
    fun `late night covers 23 to 04 wall clock whatever the rollover`() {
        // Rollover 04:00: 23:00 is slot 19 and 03:00 is slot 23; 04:00 (slot 0) and 22:00 (slot 18) are not late night.
        val d = RhythmDay(monday, distractingSlots = mapOf(19 to 10 * MIN, 23 to 5 * MIN, 0 to 40 * MIN, 18 to 40 * MIN))
        assertEquals(15 * MIN, RhythmAnalyzer.sleep(listOf(d), 4).lateNight.overallMs)
        // Rollover 00:00: 23:00 is slot 23, 00:00..03:00 are slots 0..3.
        val m = RhythmDay(monday, distractingSlots = mapOf(23 to 10 * MIN, 3 to 5 * MIN, 4 to 40 * MIN))
        assertEquals(15 * MIN, RhythmAnalyzer.sleep(listOf(m), 0).lateNight.overallMs)
    }

    @Test
    fun `overnight trend compares the last seven days with the earlier ones`() {
        val values = (0 until 10).map { monday.plusDays(it.toLong()) to (if (it < 3) 8 * HOUR_MS else 6 * HOUR_MS) }
        val t = RhythmAnalyzer.recentVsEarlier(values.shuffled(java.util.Random(1)), 7)
        assertEquals(6 * HOUR_MS, t.recentMs)
        assertEquals(8 * HOUR_MS, t.earlierMs)
        assertEquals(-2 * HOUR_MS, t.changeMs)
        assertEquals(7, t.recentDays)
        assertEquals(3, t.earlierDays)
        // Not enough days for an earlier period: no trend, no made-up change.
        assertNull(RhythmAnalyzer.recentVsEarlier(values.take(5), 7).changeMs)
    }
}
