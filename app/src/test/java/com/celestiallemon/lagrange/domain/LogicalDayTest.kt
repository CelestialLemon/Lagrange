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

    @Test
    fun `the fall-back day is 25 hours long and the repeated hour stays on that day`() {
        // 2026-11-01: New York falls back from 02:00 EDT to 01:00 EST. With a 04:00 rollover the logical day that
        // contains the repeated hour is Oct 31 (04:00 EDT) to Nov 1 (04:00 EST): 25 hours.
        val oct31 = LogicalDay.startMs(at("2026-10-31T12:00"), 4, ny)
        val nov1 = LogicalDay.startMs(at("2026-11-01T12:00"), 4, ny)
        val nov2 = LogicalDay.startMs(at("2026-11-02T12:00"), 4, ny)
        assertEquals(at("2026-11-01T04:00"), nov1)
        assertEquals(25 * 3_600_000L, nov1 - oct31)
        assertEquals(24 * 3_600_000L, nov2 - nov1)
        // With a midnight rollover the 25-hour day is the fall-back day itself.
        val midnight = LogicalDay.startMs(at("2026-11-01T12:00"), 0, ny)
        assertEquals(25 * 3_600_000L, LogicalDay.startMs(at("2026-11-02T12:00"), 0, ny) - midnight)
        // Both 01:30s (EDT, then EST an hour later) belong to Nov 1 at midnight rollover, and to Oct 31 at 04:00.
        val firstOneThirty = java.time.ZonedDateTime.of(2026, 11, 1, 1, 30, 0, 0, ny).withEarlierOffsetAtOverlap()
        val secondOneThirty = firstOneThirty.withLaterOffsetAtOverlap()
        assertEquals(3_600_000L, secondOneThirty.toInstant().toEpochMilli() - firstOneThirty.toInstant().toEpochMilli())
        for (t in listOf(firstOneThirty, secondOneThirty).map { it.toInstant().toEpochMilli() }) {
            assertEquals(LocalDate.parse("2026-11-01"), LogicalDay.dateOf(t, 0, ny))
            assertEquals(LocalDate.parse("2026-10-31"), LogicalDay.dateOf(t, 4, ny))
        }
        // A 01:00 rollover is ambiguous on that day: the day starts at the first 01:00 (EDT).
        assertEquals(firstOneThirty.minusMinutes(30).toInstant().toEpochMilli(), LogicalDay.startMs(at("2026-11-01T12:00"), 1, ny))
    }
}
