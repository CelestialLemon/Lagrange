package com.celestiallemon.lagrange.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** The logical day starts at a configurable rollover hour (04:00 by default), not at midnight. */
object LogicalDay {
    /** The instant the logical day containing [nowMs] began. DST gaps resolve forward, per `ZonedDateTime` rules. */
    fun startMs(nowMs: Long, rolloverHour: Int, zone: ZoneId): Long {
        val now = Instant.ofEpochMilli(nowMs).atZone(zone)
        val todayStart = now.toLocalDate().atTime(rolloverHour, 0).atZone(zone)
        val start = if (now.isBefore(todayStart)) now.toLocalDate().minusDays(1).atTime(rolloverHour, 0).atZone(zone) else todayStart
        return start.toInstant().toEpochMilli()
    }

    /** The logical date [nowMs] belongs to. */
    fun dateOf(nowMs: Long, rolloverHour: Int, zone: ZoneId): LocalDate =
        Instant.ofEpochMilli(startMs(nowMs, rolloverHour, zone)).atZone(zone).toLocalDate()
}
