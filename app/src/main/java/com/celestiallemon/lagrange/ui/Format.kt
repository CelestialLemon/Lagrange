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
