package com.celestiallemon.lagrange.domain.digest

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestScheduleTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun at(s: String, zone: ZoneId = berlin) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
    private fun local(ms: Long, zone: ZoneId = berlin) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDateTime()

    @Test
    fun `daily runs later today, or tomorrow once the time has passed`() {
        assertEquals(LocalDateTime.parse("2026-11-24T21:30"), local(DigestSchedule.nextDaily(at("2026-11-24T08:00"), berlin, 21 * 60 + 30)))
        assertEquals(LocalDateTime.parse("2026-11-25T21:30"), local(DigestSchedule.nextDaily(at("2026-11-24T21:30"), berlin, 21 * 60 + 30)))
    }

    @Test
    fun `a time inside the spring-forward gap is shifted later by the gap`() {
        val next = DigestSchedule.nextDaily(at("2026-03-29T01:00"), berlin, 2 * 60 + 30)
        assertEquals(LocalDateTime.parse("2026-03-29T03:30"), local(next))
    }

    @Test
    fun `weekly runs on the configured ISO day`() {
        val next = DigestSchedule.nextWeekly(at("2026-11-24T10:00"), berlin, 7, 20 * 60)
        assertEquals(DayOfWeek.SUNDAY, local(next).dayOfWeek)
        assertEquals(LocalDateTime.parse("2026-11-29T20:00"), local(next))
        val sameDayPassed = DigestSchedule.nextWeekly(at("2026-11-29T20:01"), berlin, 7, 20 * 60)
        assertEquals(LocalDateTime.parse("2026-12-06T20:00"), local(sameDayPassed))
    }

    @Test
    fun `delay is never negative and follows the zone`() {
        val now = at("2026-11-24T08:00")
        val kolkata = ZoneId.of("Asia/Kolkata")
        val d = DigestSchedule.delayMs(DigestKind.DAILY, now, kolkata, 21 * 60)
        assertTrue(d > 0)
        assertEquals(LocalDateTime.parse("2026-11-24T21:00"), local(now + d, kolkata))
    }
}
