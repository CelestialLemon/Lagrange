package com.celestiallemon.lagrange.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

private val dateTime = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
private val date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)

fun formatDateTime(ms: Long): String = dateTime.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun formatDate(ms: Long): String = date.format(Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()))

fun formatAgo(ms: Long?, nowMs: Long = System.currentTimeMillis()): String {
    if (ms == null) return "never"
    val minutes = (nowMs - ms) / 60_000
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 48 * 60 -> "${minutes / 60} h ago"
        else -> "${minutes / (24 * 60)} days ago"
    }
}

fun formatCount(n: Long): String = String.format(Locale.getDefault(), "%,d", n)

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", bytes / (1L shl 20).toDouble())
    bytes >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", bytes / (1L shl 10).toDouble())
    else -> "$bytes B"
}

fun formatHour(hour: Int): String = String.format(Locale.US, "%02d:00", hour)

private val clock = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val clockSeconds = DateTimeFormatter.ofPattern("HH:mm:ss", Locale.US)
private val dayLabel = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

/** Wall-clock time, e.g. "14:05" or "2:05 PM". */
fun formatClock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = clock.format(Instant.ofEpochMilli(ms).atZone(zone))

fun formatClockSeconds(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = clockSeconds.format(Instant.ofEpochMilli(ms).atZone(zone))

fun formatDay(date: java.time.LocalDate): String = dayLabel.format(date)

/** A compact duration: "45s", "12m", "1h 05m". */
fun formatDuration(ms: Long): String {
    val totalSec = (ms.coerceAtLeast(0) + 500) / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return when {
        h > 0 -> String.format(Locale.US, "%dh %02dm", h, m)
        m > 0 -> "${m}m"
        else -> "${s}s"
    }
}

/** A rounded duration in words for headlines: "1h 5m", "23m", "under a minute". */
fun formatDurationShort(ms: Long): String {
    val totalMin = (ms.coerceAtLeast(0) + 30_000) / 60_000
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        m > 0 -> "${m}m"
        ms <= 0 -> "0m" // a confirmed zero is exactly zero, never "under a minute"
        else -> "<1m"
    }
}
