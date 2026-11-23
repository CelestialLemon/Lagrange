package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.DayCoverage
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RollupEngineTest {
    private val kolkata = ZoneId.of("Asia/Kolkata")
    private val newYork = ZoneId.of("America/New_York")

    private fun instant(local: String, zone: ZoneId) = LocalDateTime.parse(local).atZone(zone).toInstant().toEpochMilli()

    /** A ledger whose offset 0 is [local] in [zone], fully covered by both collectors for days around it. */
    private fun ledger(local: String, zone: ZoneId) = Ledger(instant(local, zone)).apply {
        usageCoverage(-3 * DAY_MS, 3 * DAY_MS)
        listener(-3 * DAY_MS, 3 * DAY_MS)
    }

    private fun compute(
        l: Ledger,
        zone: ZoneId,
        vararg dates: String,
        rollover: Int = 4,
        dataEnd: Long = l.at(3 * DAY_MS),
        budget: Long? = null,
        rules: RollupRules = RollupRules(),
    ): Map<LocalDate, DayResult> {
        val zones = ZoneTimeline.fixed(zone)
        val windows = dates.map { zones.window(LocalDate.parse(it), rollover) }
        val episodes = SessionReconstructor.reconstruct(l.input().copy(dataEndMs = dataEnd))
        return RollupEngine.compute(
            RollupInput(windows, episodes, P.apps, l.coverage, l.notifications, rollover, dataEnd, budgetFor = { budget }),
            rules,
        ).associateBy { it.rollup.logicalDate }
    }

    private fun Map<LocalDate, DayResult>.day(d: String) = getValue(LocalDate.parse(d))

    @Test
    fun `use before the rollover hour belongs to the previous logical day`() {
        val l = ledger("2026-11-23T03:00", kolkata).session(0, P.INSTAGRAM to 10 * MIN)
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23")
        assertEquals(10 * MIN, r.day("2026-11-22").rollup.distractingMs)
        assertEquals(1, r.day("2026-11-22").rollup.episodeCount)
        assertEquals(0L, r.day("2026-11-23").rollup.distractingMs)
        assertEquals(0, r.day("2026-11-23").rollup.episodeCount)
    }

    @Test
    fun `an episode crossing the rollover stays on its start day while its minutes split`() {
        val l = ledger("2026-11-23T03:50", kolkata).session(0, P.INSTAGRAM to 30 * MIN)
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23")
        val before = r.day("2026-11-22").rollup
        val after = r.day("2026-11-23").rollup
        assertEquals(10 * MIN - 2 * SEC, before.distractingMs)
        assertEquals(20 * MIN + 2 * SEC, after.distractingMs)
        assertEquals(1, before.episodeCount)
        assertEquals(0, after.episodeCount)
        assertEquals(1, r.day("2026-11-22").apps.single { it.pkgId == P.INSTAGRAM }.openCount)
    }

    @Test
    fun `minutes split at local hour boundaries, including half-hour zones`() {
        val l = ledger("2026-11-23T13:50", kolkata).session(0, P.INSTAGRAM to 20 * MIN)
        val cells = compute(l, kolkata, "2026-11-23").day("2026-11-23").hourly.filter { it.pkgId == P.INSTAGRAM }
        assertEquals(mapOf(9 to 10 * MIN - 2 * SEC, 10 to 10 * MIN + 2 * SEC), cells.associate { it.hourSlot to it.durationMs })
        assertEquals(1, cells.single { it.hourSlot == 9 }.openCount)
    }

    @Test
    fun `a spring-forward day is 23 hours and skips the missing hour`() {
        val zones = ZoneTimeline.fixed(newYork)
        val w = zones.window(LocalDate.parse("2026-03-07"), 4)
        assertEquals(23 * HOUR_MS, w.endMs - w.startMs)
        val l = ledger("2026-03-08T01:30", newYork).session(0, P.YOUTUBE to HOUR_MS)
        val day = compute(l, newYork, "2026-03-07").day("2026-03-07")
        val cells = day.hourly.filter { it.pkgId == P.YOUTUBE }.associate { it.hourSlot to it.durationMs }
        assertEquals(mapOf(21 to 30 * MIN - 2 * SEC, 23 to 30 * MIN + 2 * SEC), cells)
        assertEquals(HOUR_MS, day.rollup.distractingMs)
    }

    @Test
    fun `a fall-back day is 25 hours and the repeated hour gets slot 24`() {
        val zones = ZoneTimeline.fixed(newYork)
        val w = zones.window(LocalDate.parse("2026-10-31"), 4)
        assertEquals(25 * HOUR_MS, w.endMs - w.startMs)
        assertEquals(21, HourSlots.repeatedSlot(w, 4))
        val l = ledger("2026-11-01T00:30", newYork).session(0, P.YOUTUBE to 2 * HOUR_MS)
        val day = compute(l, newYork, "2026-10-31").day("2026-10-31")
        val cells = day.hourly.filter { it.pkgId == P.YOUTUBE }.associate { it.hourSlot to it.durationMs }
        assertEquals(mapOf(20 to 30 * MIN - 2 * SEC, 21 to HOUR_MS, HourSlots.REPEATED_SLOT to 30 * MIN + 2 * SEC), cells)
        assertEquals(2 * HOUR_MS, day.rollup.distractingMs)
        // An ordinary day has nothing repeated.
        assertNull(HourSlots.repeatedSlot(zones.window(LocalDate.parse("2026-11-05"), 4), 4))
    }

    @Test
    fun `a harvest gap or thin usage coverage makes the day ineligible for baselines`() {
        val l = Ledger(instant("2026-11-23T10:00", kolkata)).listener(-3 * DAY_MS, 3 * DAY_MS)
        l.usageCoverage(-3 * DAY_MS, 3 * DAY_MS)
        l.usageCoverage(HOUR_MS, 3 * HOUR_MS, CoverageStatus.GAP)
        l.session(0, P.WHATSAPP to 5 * MIN)
        val gap = compute(l, kolkata, "2026-11-23").day("2026-11-23").rollup
        assertFalse(gap.baselineEligible)
        assertEquals(DayCoverage.PARTIAL_HARVEST, gap.coverage)

        val thin = Ledger(instant("2026-11-23T10:00", kolkata)).listener(-3 * DAY_MS, 3 * DAY_MS)
        thin.usageCoverage(0, 6 * HOUR_MS)
        thin.session(0, P.WHATSAPP to 5 * MIN)
        val r = compute(thin, kolkata, "2026-11-23").day("2026-11-23").rollup
        assertFalse(r.baselineEligible)
        assertTrue(r.usageCoverageRatio < 0.95)
    }

    @Test
    fun `coverage status reports missing notification coverage and backfill`() {
        val noNotif = Ledger(instant("2026-11-23T10:00", kolkata)).usageCoverage(-3 * DAY_MS, 3 * DAY_MS).session(0, P.WHATSAPP to MIN)
        val r = compute(noNotif, kolkata, "2026-11-23").day("2026-11-23").rollup
        assertEquals(DayCoverage.NO_NOTIF_COVERAGE, r.coverage)
        assertTrue(r.baselineEligible)
        assertEquals(0.0, r.notifCoverageRatio, 1e-9)

        val backfill = Ledger(instant("2026-11-23T10:00", kolkata)).usageCoverage(-3 * DAY_MS, 3 * DAY_MS, CoverageStatus.BACKFILL)
            .session(0, P.WHATSAPP to MIN)
        assertEquals(DayCoverage.BACKFILL, compute(backfill, kolkata, "2026-11-23").day("2026-11-23").rollup.coverage)

        val full = ledger("2026-11-23T10:00", kolkata).session(0, P.WHATSAPP to MIN)
        assertEquals(DayCoverage.COMPLETE, compute(full, kolkata, "2026-11-23").day("2026-11-23").rollup.coverage)
    }

    @Test
    fun `focus gaps, overnight gap, first pickup and last activity`() {
        val l = ledger("2026-11-22T23:00", kolkata)
        l.session(0, P.INSTAGRAM to 10 * MIN) // 23:00 the night before: previous logical day
        l.session(8 * HOUR_MS, P.WHATSAPP to 5 * MIN) // 07:00
        l.glance(9 * HOUR_MS) // glances never break a focus stretch
        l.session(10 * HOUR_MS, P.MAPS to 5 * MIN) // 09:00
        l.session(14 * HOUR_MS, P.CHROME to 5 * MIN) // 13:00
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23").day("2026-11-23").rollup
        val end2300 = l.at(10 * MIN + 3 * SEC)
        assertEquals(l.at(8 * HOUR_MS + SEC) - end2300, r.overnightGapMs)
        assertEquals(l.at(8 * HOUR_MS + SEC), r.firstPickupMs)
        assertEquals(l.at(14 * HOUR_MS + 5 * MIN + 3 * SEC), r.lastActivityMs)
        assertEquals(l.at(14 * HOUR_MS) - l.at(10 * HOUR_MS + 5 * MIN + 3 * SEC) + SEC, r.longestFocusGapMs)
        assertEquals(3, r.episodeCount)
        assertEquals(1, r.glanceCount)
    }

    @Test
    fun `opens, sessions, totals and budget per day`() {
        val l = ledger("2026-11-23T10:00", kolkata)
        l.session(0, P.INSTAGRAM to 2 * MIN, P.WHATSAPP to MIN)
        l.session(HOUR_MS, P.INSTAGRAM to 3 * MIN)
        val day = compute(l, kolkata, "2026-11-23", budget = 45 * MIN).day("2026-11-23")
        val insta = day.apps.single { it.pkgId == P.INSTAGRAM }
        assertEquals(2, insta.openCount)
        assertEquals(2, insta.sessionCount)
        assertEquals(5 * MIN, insta.totalMs)
        assertEquals(5 * MIN, day.rollup.distractingMs)
        assertEquals(MIN, day.rollup.neutralMs)
        assertEquals(6 * MIN, day.rollup.activeUsageMs)
        assertEquals(45 * MIN, day.rollup.budgetTargetMs)
        assertEquals(2, day.rollup.physicalPickupCount)
    }

    @Test
    fun `a day still in progress is clipped at the end of the data`() {
        val l = ledger("2026-11-23T10:00", kolkata).session(0, P.INSTAGRAM to 10 * MIN)
        val r = compute(l, kolkata, "2026-11-23", dataEnd = l.at(HOUR_MS)).day("2026-11-23").rollup
        assertEquals(10 * MIN, r.distractingMs)
        assertTrue(r.baselineEligible)
        // A day entirely in the future produces nothing.
        assertTrue(compute(l, kolkata, "2026-11-25", dataEnd = l.at(HOUR_MS)).isEmpty())
    }

    @Test
    fun `a half-hour DST rollback never moves the hour split backwards`() {
        val lordHowe = ZoneId.of("Australia/Lord_Howe")
        // 2025-04-06: 02:00 (+11:00) falls back to 01:30 (+10:30), so 01:30-02:00 happens twice.
        val firstHalf = instant("2025-04-06T01:00", lordHowe)
        var t = firstHalf - 2 * HOUR_MS
        while (t < firstHalf + 4 * HOUR_MS) {
            val next = HourSlots.nextHourBoundary(t, lordHowe)
            assertTrue("boundary after $t went to $next", next > t && next - t <= HOUR_MS)
            t += MIN
        }
        val l = ledger("2025-04-06T00:30", lordHowe).session(0, P.YOUTUBE to 3 * HOUR_MS)
        val day = compute(l, lordHowe, "2025-04-05").day("2025-04-05")
        assertEquals(3 * HOUR_MS, day.hourly.filter { it.pkgId == P.YOUTUBE }.sumOf { it.durationMs })
        assertEquals(3 * HOUR_MS, day.rollup.distractingMs)
        assertTrue(day.hourly.any { it.hourSlot == HourSlots.REPEATED_SLOT })
    }

    @Test
    fun `hour slots follow the zone in force after mid-day travel`() {
        val la = ZoneId.of("America/Los_Angeles")
        // Lands in Los Angeles at 20:00 UTC (15:00 in New York, 12:00 in LA) on a winter day.
        val landed = java.time.Instant.parse("2026-01-15T20:00:00Z").toEpochMilli()
        val zones = ZoneTimeline(listOf(0L to newYork, landed to la), newYork)
        val l = Ledger(landed + 5 * MIN).usageCoverage(-DAY_MS, DAY_MS).listener(-DAY_MS, DAY_MS).session(0, P.YOUTUBE to 10 * MIN)
        val episodes = SessionReconstructor.reconstruct(l.input().copy(dataEndMs = l.at(DAY_MS)))
        val w = zones.window(LocalDate.parse("2026-01-15"), 4)
        val day = RollupEngine.compute(RollupInput(listOf(w), episodes, P.apps, l.coverage, l.notifications, 4, l.at(DAY_MS), zones = zones)).single()
        val slots = day.hourly.filter { it.pkgId == P.YOUTUBE }.map { it.hourSlot }.toSet()
        assertEquals(setOf(12 - 4), slots)
        assertEquals(1, day.hourly.single { it.pkgId == P.YOUTUBE }.openCount)
    }

    @Test
    fun `travel days get windows that agree with dateOf whatever the device zone is now`() {
        val kolkata = ZoneId.of("Asia/Kolkata")
        val flew = java.time.Instant.parse("2026-11-23T06:30:00Z").toEpochMilli()
        val zones = ZoneTimeline(listOf(0L to kolkata, flew to newYork), fallback = newYork)
        val d23 = zones.window(LocalDate.parse("2026-11-23"), 4)
        assertEquals(java.time.Instant.parse("2026-11-22T22:30:00Z").toEpochMilli(), d23.startMs)
        assertEquals(d23.startMs, zones.window(LocalDate.parse("2026-11-22"), 4).endMs)
        var t = d23.startMs - DAY_MS
        while (t < d23.endMs + DAY_MS) {
            val date = zones.dateOf(t, 4)
            val w = zones.window(date, 4)
            assertTrue("$t in $date", t >= w.startMs && t < w.endMs)
            t += 17 * MIN
        }
    }

    @Test
    fun `a long phone-free morning is not mistaken for sleep`() {
        val l = ledger("2026-11-22T23:30", kolkata)
        l.session(0, P.INSTAGRAM to 5 * MIN) // 23:30
        l.session(7 * HOUR_MS + 30 * MIN, P.WHATSAPP to 5 * MIN) // 07:00 next day
        l.session(15 * HOUR_MS + 30 * MIN, P.CHROME to 5 * MIN) // 15:00: almost 8 hours phone-free at work
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23").day("2026-11-23").rollup
        assertEquals(l.at(7 * HOUR_MS + 30 * MIN + SEC) - l.at(5 * MIN + 3 * SEC), r.overnightGapMs)
        assertEquals(l.at(15 * HOUR_MS + 30 * MIN + SEC) - l.at(7 * HOUR_MS + 35 * MIN + 3 * SEC), r.longestFocusGapMs)
    }

    @Test
    fun `lockscreen time before the unlock counts as screen on`() {
        // Screen on 03:50, unlocked 03:55, screen off 03:56: six lit minutes, one of them unlocked.
        val l = ledger("2026-11-23T03:50", kolkata)
        l.screenOn(0).unlock(5 * MIN).use(P.INSTAGRAM, 5 * MIN, 6 * MIN).lock(6 * MIN).screenOff(6 * MIN)
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23")
        val day = r.day("2026-11-22").rollup
        assertEquals(6 * MIN, day.totalScreenMs)
        assertEquals(MIN, day.activeUsageMs)
        assertEquals(1, day.episodeCount)
        assertEquals(0, day.glanceCount)
        assertEquals(0L, r.day("2026-11-23").rollup.totalScreenMs)
    }

    @Test
    fun `lockscreen time before an unlock after the rollover stays on the day it was lit`() {
        // Screen on 03:58, unlocked 04:01, screen off 04:03: the episode starts on the new day, the lockscreen
        // minutes before 04:00 stay on the old one.
        val l = ledger("2026-11-23T03:50", kolkata)
        l.screenOn(8 * MIN).unlock(11 * MIN).use(P.INSTAGRAM, 11 * MIN, 13 * MIN).lock(13 * MIN).screenOff(13 * MIN)
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23")
        val before = r.day("2026-11-22").rollup
        val after = r.day("2026-11-23").rollup
        assertEquals(2 * MIN, before.totalScreenMs)
        assertEquals(3 * MIN, after.totalScreenMs)
        assertEquals(0, before.episodeCount)
        assertEquals(1, after.episodeCount)
        assertEquals(2 * MIN, after.activeUsageMs)
    }

    @Test
    fun `glances inside a merged episode are lit time counted once`() {
        val l = ledger("2026-11-23T10:00", kolkata)
        l.session(0, P.INSTAGRAM to MIN) // lit 0 .. 1m03s
        l.glance(MIN + 5 * SEC, durMs = 2 * SEC) // micro-lock glance, merged into the episode's gap
        l.session(MIN + 10 * SEC, P.INSTAGRAM to MIN) // lit 1m10s .. 2m13s
        val day = compute(l, kolkata, "2026-11-23").day("2026-11-23").rollup
        assertEquals(1, day.episodeCount)
        assertEquals(2 * (MIN + 3 * SEC) + 2 * SEC, day.totalScreenMs)
    }

    @Test
    fun `an episode running past the rollover still opens the next day's first focus gap`() {
        // 03:50 - 05:00 on the phone, then nothing until a noon pickup.
        val l = ledger("2026-11-23T03:50", kolkata)
        l.session(0, P.INSTAGRAM to 70 * MIN)
        l.session(8 * HOUR_MS + 10 * MIN, P.WHATSAPP to 5 * MIN)
        val stretch = l.at(8 * HOUR_MS + 10 * MIN + SEC) - l.at(70 * MIN + 3 * SEC) // 05:00 -> 12:00, about seven hours

        // With a night window that ends at 06:00 the morning is a phone-free stretch, not sleep.
        val earlyNight = RollupRules(nightAfterRolloverMs = 2 * HOUR_MS)
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23", rules = earlyNight)
        val today = r.day("2026-11-23").rollup
        assertEquals(stretch, today.longestFocusGapMs)
        assertNull(today.overnightGapMs)
        // The crossing episode is still counted on the day it started.
        assertEquals(1, r.day("2026-11-22").rollup.episodeCount)
        assertEquals(1, today.episodeCount)
        assertEquals(l.at(8 * HOUR_MS + 10 * MIN + SEC), today.firstPickupMs)

        // With the default 22:00 - 10:00 night it is the sleep proxy instead, and is never counted twice.
        val byDefault = compute(l, kolkata, "2026-11-22", "2026-11-23").day("2026-11-23").rollup
        assertEquals(stretch, byDefault.overnightGapMs)
        assertEquals(0L, byDefault.longestFocusGapMs)
    }

    @Test
    fun `a stretch after an episode running past the rollover counts when it is not the night`() {
        // 03:50 - 05:00, then nothing until 15:30: ten and a half phone-free hours, mostly after the night window.
        val l = ledger("2026-11-23T03:50", kolkata)
        l.session(0, P.INSTAGRAM to 70 * MIN)
        l.session(11 * HOUR_MS + 40 * MIN, P.WHATSAPP to 5 * MIN)
        l.session(12 * HOUR_MS, P.CHROME to 5 * MIN)
        val r = compute(l, kolkata, "2026-11-22", "2026-11-23").day("2026-11-23").rollup
        assertNull(r.overnightGapMs)
        assertEquals(l.at(11 * HOUR_MS + 40 * MIN + SEC) - l.at(70 * MIN + 3 * SEC), r.longestFocusGapMs)
        assertEquals(2, r.episodeCount)
    }
}
