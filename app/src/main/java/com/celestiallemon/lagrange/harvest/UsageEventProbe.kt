package com.celestiallemon.lagrange.harvest

import android.util.Log
import com.celestiallemon.lagrange.data.db.UsageEventTypes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** What the system is actually willing to return. Used for the Phase 0 platform checks. */
data class ProbeReport(
    val scannedFromMs: Long,
    val scannedToMs: Long,
    val totalEvents: Long,
    val earliestEventMs: Long?,
    val latestEventMs: Long?,
    val countsByType: Map<Int, Long>,
) {
    val notificationInterruptionCount: Long get() = countsByType[UsageEventTypes.NOTIFICATION_INTERRUPTION] ?: 0
    val notificationInterruptionSeen: Boolean get() = notificationInterruptionCount > 0

    /** How far back the OS still retains events, in fractional days. */
    fun retentionDays(nowMs: Long): Double? = earliestEventMs?.let { (nowMs - it) / 86_400_000.0 }
}

/**
 * Scans the system's usage history without storing anything: measures how many days Android still retains and
 * whether `NOTIFICATION_INTERRUPTION` (type 12) events are visible to a Usage Access caller.
 */
object UsageEventProbe {
    private const val DAY = 86_400_000L

    suspend fun run(source: UsageEventSource, nowMs: Long, lookbackDays: Int = 30): ProbeReport? =
        withContext(Dispatchers.IO) {
            if (!source.hasUsageAccess() || !source.isUserUnlocked()) return@withContext null
            val from = nowMs - lookbackDays * DAY
            val counts = HashMap<Int, Long>()
            var total = 0L
            var earliest = Long.MAX_VALUE
            var latest = Long.MIN_VALUE
            var start = from
            while (start < nowMs) {
                val end = minOf(start + DAY, nowMs)
                source.query(start, end)?.forEach { e ->
                    counts.merge(e.eventType, 1L, Long::plus)
                    total++
                    earliest = minOf(earliest, e.timestampMs)
                    latest = maxOf(latest, e.timestampMs)
                }
                start = end
            }
            ProbeReport(from, nowMs, total, earliest.takeIf { total > 0 }, latest.takeIf { total > 0 }, counts).also {
                Log.i(
                    "LagrangeProbe",
                    "events=${it.totalEvents} retentionDays=${it.retentionDays(nowMs)} " +
                        "type12=${it.notificationInterruptionCount} types=${it.countsByType.toSortedMap()}",
                )
            }
        }
}
