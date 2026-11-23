package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.HourSlots
import com.celestiallemon.lagrange.domain.SliceBaseline
import com.celestiallemon.lagrange.domain.SliceCutoff
import java.time.DayOfWeek
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpikeAutopsyTest {
    // A Wednesday, so the weekday/weekend split is easy to reason about.
    private val day = LocalDate.of(2026, 11, 25)

    private fun weekday(d: LocalDate) = d.dayOfWeek != DayOfWeek.SATURDAY && d.dayOfWeek != DayOfWeek.SUNDAY

    private fun history(days: Int = 28, activeMin: (LocalDate) -> Long = { 60 }): MutableMap<LocalDate, DayFacts> =
        (1..days).associate { i ->
            val d = day.minusDays(i.toLong())
            d to DayFacts(d, DayQuality.COVERED, activeMin(d) * MIN)
        }.toMutableMap()

    @Test
    fun `a day well above the matched median is a spike and well below is a good day`() {
        val days = history()
        days[day] = DayFacts(day, DayQuality.COVERED, 120 * MIN)
        val next = day.plusDays(1)
        days[next] = DayFacts(next, DayQuality.COVERED, 20 * MIN)
        val out = SpikeAnalyzer.history(DateRange(day, next), days)
        assertEquals(listOf(next, day), out.map { it.date })
        assertEquals(DayStatus.GOOD_DAY, out[0].status)
        assertEquals(DayStatus.SPIKE, out[1].status)
        assertEquals(60 * MIN, out[1].baselineMs)
        assertEquals(60 * MIN, out[1].deltaMs)
        assertTrue(out[1].matchedDays.all { weekday(it) })
    }

    @Test
    fun `covered zero-use days count toward the median and uncovered days never do`() {
        // Every weekday was recorded with no use at all: those zeros are real, so the usual weekday is 0 minutes.
        val days = history { d -> if (weekday(d)) 0 else 60 }
        // A huge day that was only partly recorded must not drag the baseline up.
        val gap = day.minusDays(1)
        days[gap] = DayFacts(gap, DayQuality.PARTIAL, 900 * MIN)
        days[day] = DayFacts(day, DayQuality.COVERED, 40 * MIN)
        val h = SpikeAnalyzer.history(DateRange(day, day), days).single()
        assertFalse(gap in h.matchedDays)
        assertEquals(0L, h.baselineMs)
        assertEquals(19, h.matchedDays.size)
        assertEquals(DayStatus.SPIKE, h.status)
    }

    @Test
    fun `nothing is judged until seven covered days exist`() {
        val days = history(days = 6)
        days[day] = DayFacts(day, DayQuality.COVERED, 600 * MIN)
        assertTrue(SpikeAnalyzer.history(DateRange(day, day), days).isEmpty())
        assertTrue(SpikeAnalyzer.matchedDays(day, days).isEmpty())
    }

    @Test
    fun `an unsettled or partial day is never judged itself`() {
        val days = history()
        days[day] = DayFacts(day, DayQuality.IN_PROGRESS, 600 * MIN)
        assertTrue(SpikeAnalyzer.history(DateRange(day, day), days).isEmpty())
    }

    @Test
    fun `today is compared with the same slice of matched days`() {
        val slice = SliceBaseline(todayMs = 90 * MIN, medianMs = 30 * MIN, days = 12, cutoff = SliceCutoff(8, 0.5))
        val live = SpikeAnalyzer.live(day, eligible = true, slice = slice, matchedDays = listOf(day.minusDays(7)))
        assertEquals(DayStatus.SPIKE, live!!.status)
        assertTrue(live.live)
        assertEquals(60 * MIN, live.deltaMs)
        // A coverage gap today means no verdict at all, and a normal day isn't highlighted.
        assertNull(SpikeAnalyzer.live(day, eligible = false, slice = slice, matchedDays = emptyList()))
        assertNull(SpikeAnalyzer.live(day, true, slice.copy(todayMs = 31 * MIN), emptyList()))
        assertNull(SpikeAnalyzer.live(day, true, slice.copy(medianMs = null), emptyList()))
    }

    private val matched = listOf(day.minusDays(7), day.minusDays(14), day.minusDays(21))

    private fun highlight(actual: Long = 120 * MIN, base: Long = 60 * MIN) = DayHighlight(day, DayStatus.SPIKE, actual, base, matched)

    @Test
    fun `app deltas compare each app with its own median and are not additive`() {
        val appMs = mapOf(
            day to mapOf(INSTA to 50 * MIN, TIKTOK to 40 * MIN, MAPS to 30 * MIN, LAUNCHER to 99 * MIN),
            matched[0] to mapOf(INSTA to 10 * MIN, TIKTOK to 40 * MIN, YOUTUBE to 30 * MIN),
            matched[1] to mapOf(INSTA to 20 * MIN, TIKTOK to 0L, YOUTUBE to 20 * MIN),
            // Missing apps on a matched day are real zeros.
            matched[2] to mapOf(YOUTUBE to 10 * MIN),
        )
        val a = SpikeAnalyzer.autopsy(AutopsyInput(highlight(), appMs, emptyMap(), emptyList()), testApps)
        // Instagram: median 10 → +40; Maps: median 0 → +30; TikTok: median 0 → +40; YouTube: below usual; launcher excluded.
        assertEquals(listOf(INSTA, TIKTOK, MAPS), a.appDeltas.map { it.pkgId })
        assertEquals(10 * MIN, a.appDeltas[0].medianMs)
        assertEquals(110 * MIN, a.appDeltaSumMs)
        assertNotEquals(a.excessMs, a.appDeltaSumMs)
        assertTrue(a.appDeltas.none { it.pkgId == LAUNCHER || it.pkgId == YOUTUBE })
    }

    @Test
    fun `long sessions rank by distracting time and carry their trigger and hop chain`() {
        val eps = listOf(
            episode(1_000, INSTA to 5, date = day),
            episode(2_000_000, MAPS to 40, date = day),
            episode(9_000_000, INSTA to 10, LAUNCHER to 1, TIKTOK to 20, date = day, rabbit = true, trigger = TriggerEvidence.OBSERVED_CLICK),
            episode(20_000_000, YOUTUBE to 15, date = day),
        )
        val a = SpikeAnalyzer.autopsy(AutopsyInput(highlight(), emptyMap(), emptyMap(), eps, note = "  "), testApps)
        assertEquals(3, a.longSessions.size)
        val top = a.longSessions.first()
        assertEquals(listOf(INSTA, TIKTOK), top.hopChain)
        assertEquals(TriggerEvidence.OBSERVED_CLICK, top.trigger)
        assertTrue(top.isRabbitHole)
        assertEquals(YOUTUBE, a.longSessions[1].hopChain.single())
        assertEquals(4, a.episodeCount)
        assertEquals(3, a.triggers[TriggerEvidence.NO_OBSERVED_TRIGGER])
        assertNull("a blank note is no note", a.note)
    }

    @Test
    fun `the day note is attached`() {
        val a = SpikeAnalyzer.autopsy(AutopsyInput(highlight(), emptyMap(), emptyMap(), emptyList(), note = "Sick day"), testApps)
        assertEquals("Sick day", a.note)
    }

    @Test
    fun `blocks follow the rollover and a live day stops at the cutoff`() {
        assertEquals(4 to 10, DayBlock.MORNING.wallHours(4))
        assertEquals(22 to 4, DayBlock.LATE_NIGHT.wallHours(4))
        assertEquals(DayBlock.EVENING, DayBlock.ofSlot(13))

        val slots = mapOf(
            day to mapOf(1 to 30 * MIN, 7 to 20 * MIN),
            matched[0] to mapOf(1 to 10 * MIN, 7 to 60 * MIN, 8 to 60 * MIN, 20 to 60 * MIN),
            matched[1] to mapOf(1 to 10 * MIN, 7 to 60 * MIN, 8 to 60 * MIN),
            matched[2] to mapOf(1 to 10 * MIN, 7 to 60 * MIN, 8 to 60 * MIN),
        )
        val settled = SpikeAnalyzer.autopsy(AutopsyInput(highlight(), emptyMap(), slots, emptyList()), testApps)
        assertEquals(4, settled.blocks.size)
        assertEquals(120 * MIN, settled.blocks[1].medianMs)
        assertTrue(settled.blocks.all { it.complete })

        // "Now" is halfway through slot 8: matched days count slot 7 whole and half of slot 8; evening and late night are left out.
        val live = SpikeAnalyzer.autopsy(AutopsyInput(highlight().copy(live = true), emptyMap(), slots, emptyList(), cutoff = SliceCutoff(8, 0.5)), testApps)
        assertEquals(listOf(DayBlock.MORNING, DayBlock.AFTERNOON), live.blocks.map { it.block })
        assertEquals(90 * MIN, live.blocks[1].medianMs)
        assertEquals(20 * MIN, live.blocks[1].actualMs)
        assertFalse(live.blocks[1].complete)
        assertTrue(live.blocks[0].complete)
        assertEquals(30 * MIN, live.blocks[0].actualMs)
        assertEquals(20 * MIN, live.blocks[0].deltaMs)
    }

    @Test
    fun `the repeated DST hour is folded into the hour it repeated or reported as unplaced`() {
        val (folded, lost) = SpikeAnalyzer.foldRepeated(mapOf(21 to 10 * MIN, HourSlots.REPEATED_SLOT to 15 * MIN), 21)
        assertEquals(mapOf(21 to 25 * MIN), folded)
        assertEquals(0L, lost)
        val (kept, unplaced) = SpikeAnalyzer.foldRepeated(mapOf(3 to 5 * MIN, HourSlots.REPEATED_SLOT to 15 * MIN), null)
        assertEquals(mapOf(3 to 5 * MIN), kept)
        assertEquals(15 * MIN, unplaced)
    }

    @Test
    fun `live per-app slices cut matched days at the same minute`() {
        val sliced = SpikeAnalyzer.sliceApps(mapOf(INSTA to mapOf(2 to 10 * MIN, 5 to 20 * MIN, 9 to 30 * MIN)), SliceCutoff(5, 0.25))
        assertEquals(mapOf(INSTA to 15 * MIN), sliced)
    }
}
