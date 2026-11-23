package com.celestiallemon.lagrange.domain.friction

import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Evaluates contextual schedules (`friction_schedules`) at a local wall-clock instant, including midnight-wrapping
 * windows such as `22:30–04:00`.
 */
object ScheduleMatcher {

    fun dayBit(day: DayOfWeek): Int = 1 shl (day.value - 1)

    fun matches(schedule: FrictionSchedule, atUtcMs: Long, zone: ZoneId): Boolean =
        matches(schedule, Instant.ofEpochMilli(atUtcMs).atZone(zone))

    fun matches(schedule: FrictionSchedule, local: ZonedDateTime): Boolean {
        if (!schedule.enabled || schedule.daysOfWeekMask == 0) return false
        val start = schedule.startMinuteOfDay.coerceIn(0, 1439)
        val end = schedule.endMinuteOfDay.coerceIn(0, 1439)
        val minuteOfDay = local.hour * 60 + local.minute
        val todayBit = dayBit(local.dayOfWeek)
        val yesterdayBit = dayBit(local.dayOfWeek.minus(1))
        return when {
            start == end -> (schedule.daysOfWeekMask and todayBit) != 0
            start < end -> (schedule.daysOfWeekMask and todayBit) != 0 && minuteOfDay in start until end
            else -> {
                // Midnight-wrapping window: evening part belongs to today's day-of-week; early-morning part
                // (after 00:00 before end) belongs to the schedule that began on yesterday's day-of-week.
                if (minuteOfDay >= start) (schedule.daysOfWeekMask and todayBit) != 0
                else if (minuteOfDay < end) (schedule.daysOfWeekMask and yesterdayBit) != 0
                else false
            }
        }
    }

    /**
     * Combines all schedules active at [atUtcMs]. When several overlap, the highest escalation boost wins
     * (ties broken by lowest schedule id for determinism), timer prompts OR together, and the tightest nudge
     * interval wins.
     */
    fun activeEffect(schedules: List<FrictionSchedule>, atUtcMs: Long, zone: ZoneId): ActiveScheduleEffect? {
        val local = Instant.ofEpochMilli(atUtcMs).atZone(zone)
        val matched = schedules.filter { matches(it, local) }
        if (matched.isEmpty()) return null
        val primary = matched.maxWith(compareBy<FrictionSchedule> { it.escalationBoost }.thenByDescending { -it.id })
        val maxBoost = matched.maxOf { it.escalationBoost }
        val requireTimer = matched.any { it.requireTimerPrompt }
        val tightestNudge = matched.mapNotNull { it.nudgeIntervalMin?.takeIf { m -> m > 0 } }.minOrNull()
        return ActiveScheduleEffect(
            primaryScheduleId = primary.id,
            primaryScheduleName = primary.name,
            escalationBoost = maxBoost,
            requireTimerPrompt = requireTimer,
            nudgeIntervalMin = tightestNudge,
        )
    }
}
