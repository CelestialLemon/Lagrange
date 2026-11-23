package com.celestiallemon.lagrange.domain.digest

import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.domain.DayQuality
import java.time.LocalDate
import java.time.LocalTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestComposerTest {
    private val min = 60_000L
    private val hour = 60 * min
    private val today = LocalDate.of(2026, 11, 24) // a Tuesday

    private fun day(
        date: LocalDate,
        distracting: Long,
        quality: DayQuality = DayQuality.COVERED,
        budget: Long? = hour,
        status: DayStatus? = DayStatus.NORMAL,
        active: Long = distracting * 2,
        baseline: Long? = 3 * hour,
        focus: Long = 0,
    ) = DigestDay(date, quality, distracting, active, budget, baseline, status, focus)

    @Test
    fun `daily digest reports budget, baseline, rabbit hole, focus stretch and exits`() {
        val d = DigestComposer.daily(
            DailyDigestInput(
                today = today,
                day = day(today.minusDays(1), 45 * min, focus = 2 * hour + 10 * min, status = DayStatus.GOOD_DAY),
                biggestRabbitHole = DigestRabbitHole(today.minusDays(1), LocalTime.of(22, 5), 38 * min, listOf("Reddit", "YouTube")),
                exits = DigestExits(exits = 3, sustained = 2),
                currentBudgetMs = 2 * hour,
            ),
        )!!
        assertEquals(DigestKind.DAILY, d.kind)
        assertEquals("2026-11-23", d.key)
        assertEquals("Yesterday · 45m distracting, within your 1h budget", d.title)
        assertTrue(d.lines[0].contains("a full weekday usually lands around 3h"))
        assertTrue(d.lines.any { it == "A good day: lighter than your usual weekday." })
        assertTrue(d.lines.any { it == "Biggest rabbit hole: 38m at 22:05 · Reddit → YouTube." })
        assertTrue(d.lines.any { it == "Longest phone-free stretch while awake: 2h 10m." })
        assertTrue(d.lines.any { it == "Launch pauses: 3 immediate exits, 2 stayed away 10m+." })
        assertNull(d.coverageNote)
    }

    @Test
    fun `over budget is named with the overage`() {
        val d = DigestComposer.daily(DailyDigestInput(today, day(today.minusDays(1), 90 * min), currentBudgetMs = hour))!!
        assertTrue(d.title, d.title.endsWith("30m over your 1h budget"))
    }

    @Test
    fun `a day with a coverage gap is a lower bound and never compared`() {
        val d = DigestComposer.daily(
            DailyDigestInput(today, day(today.minusDays(1), 20 * min, quality = DayQuality.PARTIAL, status = DayStatus.SPIKE), currentBudgetMs = hour),
        )!!
        assertTrue(d.title, d.title.contains("at least 20m"))
        assertFalse(d.lines[0].contains("usually"))
        assertFalse("a spike verdict on a gap day is suppressed", d.lines.any { it.contains("heavier") })
        assertNotNull(d.coverageNote)
    }

    @Test
    fun `no rollup or no data means no digest instead of a confident zero`() {
        assertNull(DigestComposer.daily(DailyDigestInput(today, null, currentBudgetMs = hour)))
        assertNull(DigestComposer.daily(DailyDigestInput(today, DigestDay(today, DayQuality.NO_DATA), currentBudgetMs = hour)))
    }

    @Test
    fun `an evening digest reports today so far without a day verdict`() {
        val d = DigestComposer.daily(
            DailyDigestInput(today, day(today, 30 * min, quality = DayQuality.IN_PROGRESS, budget = null, status = DayStatus.SPIKE), currentBudgetMs = hour),
        )!!
        assertTrue(d.title.startsWith("Today so far · 30m distracting, within your 1h budget"))
        assertTrue(d.lines[0].contains("so far"))
        assertFalse(d.lines.any { it.contains("heavier") })
    }

    @Test
    fun `daily target switches from yesterday to today twelve hours into the logical day`() {
        val start = 1_000_000L
        assertEquals(today.minusDays(1), DigestComposer.dailyTarget(start + 11 * hour, today, start))
        assertEquals(today, DigestComposer.dailyTarget(start + 12 * hour, today, start))
    }

    @Test
    fun `weekly digest averages covered days only and compares only with enough of them`() {
        val (cur, prev) = DigestComposer.weeklyRanges(today)
        val current = cur.mapIndexed { i, date ->
            if (i == 2) day(date, 5 * hour, quality = DayQuality.PARTIAL) else day(date, 40 * min, focus = (i + 1) * 10 * min, status = if (i == 0) DayStatus.GOOD_DAY else DayStatus.NORMAL)
        }
        val previous = prev.map { day(it, 60 * min) }
        val d = DigestComposer.weekly(WeeklyDigestInput(today, current, previous, exits = DigestExits(4, 1)))!!
        assertEquals("2026-W48", d.key)
        assertEquals("Your week · 40m a day distracting, 20m less than the week before", d.title)
        assertEquals("Based on 6 of 7 fully covered days.", d.lines[0])
        assertTrue(d.lines.contains("Within budget on 6 of 6 covered days."))
        assertTrue(d.lines.any { it.startsWith("Longest phone-free stretch: 1h 10m") })
        assertEquals("1 day had collection gaps and is left out of the averages.", d.coverageNote)
    }

    @Test
    fun `weekly comparison is withheld when the prior week is thin, and a gap-only week says nothing`() {
        val (cur, prev) = DigestComposer.weeklyRanges(today)
        val current = cur.map { day(it, 40 * min) }
        val previous = prev.mapIndexed { i, date -> if (i < 2) day(date, hour) else DigestDay(date, DayQuality.NO_DATA) }
        val d = DigestComposer.weekly(WeeklyDigestInput(today, current, previous))!!
        assertEquals("Your week · 40m a day distracting", d.title)
        assertTrue(d.lines.any { it.startsWith("Not enough fully covered days") })

        val gaps = cur.map { day(it, 40 * min, quality = DayQuality.PARTIAL) }
        assertNull(DigestComposer.weekly(WeeklyDigestInput(today, gaps, previous)))
    }

    @Test
    fun `iso week keys handle the year boundary`() {
        assertEquals("2026-W53", DigestComposer.isoWeekKey(LocalDate.of(2027, 1, 1)))
        assertEquals("2025-W01", DigestComposer.isoWeekKey(LocalDate.of(2024, 12, 30)))
    }

    @Test
    fun `durations round like the app headlines`() {
        assertEquals("0m", DigestComposer.duration(0))
        assertEquals("<1m", DigestComposer.duration(20_000))
        assertEquals("1h 5m", DigestComposer.duration(65 * min))
        assertEquals("2h", DigestComposer.duration(2 * hour))
    }
}
