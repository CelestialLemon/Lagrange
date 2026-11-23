package com.celestiallemon.lagrange.domain

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LogicalDayTest {
    private val ny = ZoneId.of("America/New_York")
    private fun at(s: String, zone: ZoneId = ny) = LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `before the rollover hour belongs to the previous day`() {
        assertEquals(LocalDate.parse("2026-11-22"), LogicalDay.dateOf(at("2026-11-23T00:40"), 4, ny))
        assertEquals(LocalDate.parse("2026-11-23"), LogicalDay.dateOf(at("2026-11-23T04:00"), 4, ny))
        assertEquals(at("2026-11-22T04:00"), LogicalDay.startMs(at("2026-11-23T03:59"), 4, ny))
    }

    @Test
    fun `a rollover inside the spring-forward gap resolves forward`() {
        // 2026-03-08 02:00 does not exist in New York; the day starts at 03:00 EDT.
        assertEquals(at("2026-03-08T03:00"), LogicalDay.startMs(at("2026-03-08T12:00"), 2, ny))
    }

    @Test
    fun `midnight rollover is the calendar day`() {
        assertEquals(LocalDate.parse("2026-11-23"), LogicalDay.dateOf(at("2026-11-23T00:01"), 0, ny))
    }
}
