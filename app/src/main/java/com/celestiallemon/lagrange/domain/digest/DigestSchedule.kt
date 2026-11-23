package com.celestiallemon.lagrange.domain.digest

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.TemporalAdjusters

/**
 * When the next digest is due, in local wall-clock terms. A configured time that falls in a spring-forward gap runs
 * at the first valid instant after it; one that occurs twice on a fall-back night runs at its first occurrence.
 */
object DigestSchedule {
    /** The next instant strictly after [nowMs] at local [minuteOfDay]. */
    fun nextDaily(nowMs: Long, zone: ZoneId, minuteOfDay: Int): Long {
        require(minuteOfDay in 0..1439) { "minute of day must be 0..1439" }
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var date = now.toLocalDate()
        repeat(3) {
            val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (candidate > nowMs) return candidate
            date = date.plusDays(1)
        }
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }

    /** The next instant strictly after [nowMs] on ISO [dayOfWeek] (1 = Monday … 7 = Sunday) at local [minuteOfDay]. */
    fun nextWeekly(nowMs: Long, zone: ZoneId, dayOfWeek: Int, minuteOfDay: Int): Long {
        require(dayOfWeek in 1..7) { "day of week must be 1..7" }
        require(minuteOfDay in 0..1439) { "minute of day must be 0..1439" }
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val time = LocalTime.of(minuteOfDay / 60, minuteOfDay % 60)
        var date = now.toLocalDate().with(TemporalAdjusters.nextOrSame(DayOfWeek.of(dayOfWeek)))
        repeat(3) {
            val candidate = ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
            if (candidate > nowMs) return candidate
            date = date.plusWeeks(1)
        }
        return ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
    }

    /** Delay until the next run of [kind], never negative. */
    fun delayMs(
        kind: DigestKind,
        nowMs: Long,
        zone: ZoneId,
        minuteOfDay: Int,
        dayOfWeek: Int = 7,
    ): Long {
        val next = when (kind) {
            DigestKind.DAILY -> nextDaily(nowMs, zone, minuteOfDay)
            DigestKind.WEEKLY -> nextWeekly(nowMs, zone, dayOfWeek, minuteOfDay)
        }
        return (next - nowMs).coerceAtLeast(0L)
    }
}
