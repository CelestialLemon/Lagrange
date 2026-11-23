package com.celestiallemon.lagrange.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.celestiallemon.lagrange.domain.ZoneTimeline
import java.io.OutputStream
import java.io.Writer
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * RFC 4180 fields and records: a field is quoted when it contains a comma, a quote, CR or LF, and quotes inside it
 * are doubled; records end with CRLF; a null is an empty field.
 */
object Csv {
    fun field(value: String?): String {
        if (value == null) return ""
        val needsQuotes = value.any { it == ',' || it == '"' || it == '\r' || it == '\n' }
        return if (needsQuotes) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    fun record(values: List<String?>): String = values.joinToString(",", postfix = "\r\n") { field(it) }
}

/** What one CSV bundle contained, for the UI message and tests. */
data class CsvExportSummary(val rowsPerFile: Map<String, Long>) {
    val totalRows: Long get() = rowsPerFile.values.sum()
}

/**
 * The tidy CSV bundle (spec §7.4): six files for DuckDB, pandas or a spreadsheet, written from a consistent
 * snapshot of the database. Every instant appears twice: as ISO-8601 local time with its UTC offset (resolved through
 * `tz_history`, so a trip shows the clock you actually lived) and as UTC epoch milliseconds. Apps carry their package,
 * display name and category. Notifications are metadata only, exactly as stored: no title, text, sender, key or group
 * hash, and conversation channels are already parent channels or keyed pseudonyms at rest.
 *
 * Rows are read in keyset-paged chunks of [pageSize] and streamed into the ZIP, so memory stays flat however long
 * the history is; cancellation is checked between pages.
 */
class CsvExporter(
    private val db: SQLiteDatabase,
    private val zones: ZoneTimeline,
    private val pageSize: Int = DEFAULT_PAGE_SIZE,
) {
    init {
        require(pageSize > 0) { "page size must be positive" }
    }

    /** Writes the six CSV files into [out] (finished, not closed: the caller owns the stream). */
    suspend fun writeZip(out: OutputStream): CsvExportSummary {
        val zip = ZipOutputStream(out)
        val rows = LinkedHashMap<String, Long>()
        for (table in TABLES) {
            zip.putNextEntry(ZipEntry(table.fileName))
            // Not closed: closing the writer would close the ZIP. Flushed before the entry closes.
            val writer = zip.writer(Charsets.UTF_8).buffered(64 * 1024)
            rows[table.fileName] = writeTable(table, writer)
            writer.flush()
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
        return CsvExportSummary(rows)
    }

    private suspend fun writeTable(table: Table, out: Writer): Long {
        out.write(Csv.record(table.header))
        var count = 0L
        var after: List<String> = table.start
        while (true) {
            coroutineContext.ensureActive()
            var last: List<String>? = null
            var pageRows = 0
            db.rawQuery(table.pageSql(pageSize), after.toTypedArray()).use { c ->
                while (c.moveToNext()) {
                    out.write(Csv.record(table.row(c, this)))
                    last = table.key(c)
                    pageRows++
                }
            }
            count += pageRows
            val next = last
            if (pageRows < pageSize || next == null) break
            after = next
        }
        return count
    }

    /** ISO-8601 local date-time with offset, in the zone `tz_history` says was in force at [ms]. */
    fun local(ms: Long?): String? = ms?.let { Instant.ofEpochMilli(it).atZone(zones.zoneAt(it)).toOffsetDateTime().format(ISO) }

    /**
     * One exported table. [pageSql] selects rows strictly after the previous page's key (bound as arguments) in key
     * order, so every row is read exactly once even across pages.
     */
    private class Table(
        val fileName: String,
        val header: List<String>,
        /** Arguments for the first page: a key lower than every real key. */
        val start: List<String>,
        private val sql: String,
        val key: (Cursor) -> List<String>,
        val row: (Cursor, CsvExporter) -> List<String?>,
    ) {
        fun pageSql(limit: Int) = "$sql LIMIT $limit"
    }

    companion object {
        const val DEFAULT_PAGE_SIZE = 2_000
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        private val FILE_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.US)

        const val DAILY_SUMMARY = "daily_summary.csv"
        const val DAILY_APP_USAGE = "daily_app_usage.csv"
        const val SESSIONS = "sessions.csv"
        const val SESSION_SEGMENTS = "session_segments.csv"
        const val NOTIFICATIONS = "notifications.csv"
        const val FRICTION_EVENTS = "friction_events.csv"

        /** `lagrange-csv-YYYYMMDD-HHmmss.zip`, in local time. */
        fun fileName(atMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
            "lagrange-csv-" + FILE_STAMP.format(Instant.ofEpochMilli(atMs).atZone(zone)) + ".zip"

        /** Builds the zone timeline from a snapshot's `tz_history` (the device zone before the first entry). */
        fun zoneTimeline(db: SQLiteDatabase, fallback: ZoneId = ZoneId.systemDefault()): ZoneTimeline {
            val entries = ArrayList<Pair<Long, ZoneId>>()
            db.rawQuery("SELECT effective_from_utc_ms, zone_id FROM tz_history ORDER BY effective_from_utc_ms, id", null).use { c ->
                while (c.moveToNext()) runCatching { entries += c.getLong(0) to ZoneId.of(c.getString(1)) }
            }
            return ZoneTimeline(entries, fallback)
        }

        private fun Cursor.str(i: Int): String? = if (isNull(i)) null else getString(i)
        private fun Cursor.long(i: Int): Long? = if (isNull(i)) null else getLong(i)
        private fun Cursor.bool(i: Int): String? = if (isNull(i)) null else (getLong(i) != 0L).toString()
        private fun Cursor.num(i: Int): String? = if (isNull(i)) null else getString(i)

        private fun weekday(date: String?): String? =
            date?.let { runCatching { LocalDate.parse(it).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US) }.getOrNull() }

        private val LONG_MIN = Long.MIN_VALUE.toString()

        private val TABLES = listOf(
            Table(
                fileName = DAILY_SUMMARY,
                header = listOf(
                    "logical_date", "weekday", "coverage_status", "day_status",
                    "total_screen_ms", "active_usage_ms", "distracting_ms", "neutral_ms", "utility_ms", "excluded_ms", "self_app_ms",
                    "budget_target_ms", "baseline_median_ms",
                    "episode_count", "physical_pickup_count", "glance_count", "micro_check_count", "rabbit_hole_count",
                    "blocked_attempt_count", "notification_count",
                    "longest_focus_gap_ms", "overnight_gap_ms",
                    "first_pickup_local", "first_pickup_utc_ms", "last_activity_local", "last_activity_utc_ms",
                    "notif_coverage_ratio", "friction_coverage_ratio", "day_note", "algorithm_version",
                ),
                start = listOf(""),
                sql = "SELECT r.logical_date, r.coverage_status, r.day_status, r.total_screen_ms, r.active_usage_ms, r.distracting_ms, " +
                    "r.neutral_ms, r.utility_ms, r.excluded_ms, r.self_app_ms, r.budget_target_ms, r.baseline_median_ms, r.episode_count, " +
                    "r.physical_pickup_count, r.glance_count, r.micro_check_count, r.rabbit_hole_count, r.blocked_attempt_count, " +
                    "r.notification_count, r.longest_focus_gap_ms, r.overnight_gap_ms, r.first_pickup_ms, r.last_activity_ms, " +
                    "r.notif_coverage_ratio, r.friction_coverage_ratio, n.note_text, r.algorithm_version " +
                    "FROM daily_rollups r LEFT JOIN daily_notes n ON n.logical_date = r.logical_date " +
                    "WHERE r.logical_date > ? ORDER BY r.logical_date",
                key = { c -> listOf(c.getString(0)) },
                row = { c, x ->
                    listOf(
                        c.str(0), weekday(c.str(0)), c.str(1), c.str(2),
                        c.num(3), c.num(4), c.num(5), c.num(6), c.num(7), c.num(8), c.num(9),
                        c.num(10), c.num(11),
                        c.num(12), c.num(13), c.num(14), c.num(15), c.num(16), c.num(17), c.num(18),
                        c.num(19), c.num(20),
                        x.local(c.long(21)), c.num(21), x.local(c.long(22)), c.num(22),
                        c.num(23), c.num(24), c.str(25), c.num(26),
                    )
                },
            ),
            Table(
                fileName = DAILY_APP_USAGE,
                header = listOf(
                    "logical_date", "package_name", "app_name", "category",
                    "total_ms", "open_count", "session_count", "blocked_attempt_count", "rabbit_hole_contribution_ms",
                ),
                start = listOf("", "", LONG_MIN),
                sql = "SELECT a.logical_date, a.pkg_id, p.package_name, p.display_name, p.category, a.total_ms, a.open_count, " +
                    "a.session_count, a.blocked_attempt_count, a.rabbit_hole_contribution_ms " +
                    "FROM daily_app_rollups a LEFT JOIN packages p ON p.id = a.pkg_id " +
                    "WHERE a.logical_date > ? OR (a.logical_date = ? AND a.pkg_id > CAST(? AS INTEGER)) " +
                    "ORDER BY a.logical_date, a.pkg_id",
                key = { c -> listOf(c.getString(0), c.getString(0), c.getLong(1).toString()) },
                row = { c, _ -> listOf(c.str(0), c.str(2), c.str(3), c.str(4), c.num(5), c.num(6), c.num(7), c.num(8), c.num(9)) },
            ),
            Table(
                fileName = SESSIONS,
                header = listOf(
                    "session_id", "episode_key", "logical_date",
                    "start_local", "start_utc_ms", "end_local", "end_utc_ms",
                    "active_duration_ms", "physical_pickup_count", "is_glance_only",
                    "trigger_evidence", "trigger_package", "trigger_app_name", "trigger_notification_id",
                    "entry_package", "entry_app_name", "entry_category",
                    "distracting_ms", "neutral_ms", "utility_ms", "excluded_ms",
                    "blocked_attempt_count", "distinct_app_count", "distinct_distracting_count",
                    "archetype", "is_rabbit_hole", "rabbit_hole_reasons", "worth_it_rating", "context_flags", "algorithm_version",
                ),
                start = listOf(LONG_MIN),
                sql = "SELECT s.id, s.episode_key, s.logical_date, s.start_ms, s.end_ms, s.active_duration_ms, s.physical_pickup_count, " +
                    "s.is_glance_only, s.trigger_evidence, tp.package_name, tp.display_name, s.trigger_notification_id, " +
                    "ep.package_name, ep.display_name, ep.category, s.total_distracting_ms, s.total_neutral_ms, s.total_utility_ms, " +
                    "s.total_excluded_ms, s.blocked_attempt_count, s.distinct_app_count, s.distinct_distracting_count, s.archetype, " +
                    "s.is_rabbit_hole, s.rabbit_hole_reasons, s.worth_it_rating, s.context_flags, s.algorithm_version " +
                    "FROM sessions s LEFT JOIN packages tp ON tp.id = s.trigger_pkg_id LEFT JOIN packages ep ON ep.id = s.entry_pkg_id " +
                    "WHERE s.id > CAST(? AS INTEGER) ORDER BY s.id",
                key = { c -> listOf(c.getLong(0).toString()) },
                row = { c, x ->
                    listOf(
                        c.num(0), c.str(1), c.str(2),
                        x.local(c.long(3)), c.num(3), x.local(c.long(4)), c.num(4),
                        c.num(5), c.num(6), c.bool(7),
                        c.str(8), c.str(9), c.str(10), c.num(11),
                        c.str(12), c.str(13), c.str(14),
                        c.num(15), c.num(16), c.num(17), c.num(18),
                        c.num(19), c.num(20), c.num(21),
                        c.str(22), c.bool(23), c.str(24), c.num(25), c.str(26), c.num(27),
                    )
                },
            ),
            Table(
                fileName = SESSION_SEGMENTS,
                header = listOf(
                    "segment_id", "session_id", "seq_index", "package_name", "app_name", "category",
                    "start_local", "start_utc_ms", "end_local", "end_utc_ms", "duration_ms", "is_blocked_attempt",
                    "segment_trigger_evidence", "segment_trigger_package",
                ),
                start = listOf(LONG_MIN),
                sql = "SELECT g.id, g.session_id, g.seq_index, p.package_name, p.display_name, p.category, g.start_ms, g.end_ms, " +
                    "g.duration_ms, g.is_blocked_attempt, g.segment_trigger_evidence, tp.package_name " +
                    "FROM session_segments g LEFT JOIN packages p ON p.id = g.pkg_id LEFT JOIN packages tp ON tp.id = g.segment_trigger_pkg_id " +
                    "WHERE g.id > CAST(? AS INTEGER) ORDER BY g.id",
                key = { c -> listOf(c.getLong(0).toString()) },
                row = { c, x ->
                    listOf(
                        c.num(0), c.num(1), c.num(2), c.str(3), c.str(4), c.str(5),
                        x.local(c.long(6)), c.num(6), x.local(c.long(7)), c.num(7), c.num(8), c.bool(9),
                        c.str(10), c.str(11),
                    )
                },
            ),
            Table(
                fileName = NOTIFICATIONS,
                header = listOf(
                    "notification_id", "package_name", "app_name", "category", "channel_id", "android_category",
                    "posted_local", "posted_utc_ms", "last_updated_local", "last_updated_utc_ms", "update_count",
                    "removed_local", "removed_utc_ms", "removal_reason", "importance",
                    "matched_interruption_filter", "is_ongoing", "is_foreground_service", "is_group_summary", "is_ambient",
                    "is_conversation", "is_only_alert_once", "dnd_or_silent_at_post",
                ),
                start = listOf(LONG_MIN),
                // Deliberately no sbn_key_hash / group_key_hash: they identify individual notifications, not behaviour.
                sql = "SELECT n.id, p.package_name, p.display_name, p.category, n.channel_id, n.android_category, n.posted_at_ms, " +
                    "n.last_updated_at_ms, n.update_count, n.removed_at_ms, n.removal_reason, n.importance, " +
                    "n.matched_interruption_filter, n.is_ongoing, n.is_foreground_service, n.is_group_summary, n.is_ambient, " +
                    "n.is_conversation, n.is_only_alert_once, n.dnd_or_silent_at_post " +
                    "FROM raw_notifications n LEFT JOIN packages p ON p.id = n.pkg_id " +
                    "WHERE n.id > CAST(? AS INTEGER) ORDER BY n.id",
                key = { c -> listOf(c.getLong(0).toString()) },
                row = { c, x ->
                    listOf(
                        c.num(0), c.str(1), c.str(2), c.str(3), c.str(4), c.str(5),
                        x.local(c.long(6)), c.num(6), x.local(c.long(7)), c.num(7), c.num(8),
                        x.local(c.long(9)), c.num(9), c.str(10), c.num(11),
                        c.bool(12), c.bool(13), c.bool(14), c.bool(15), c.bool(16),
                        c.bool(17), c.bool(18), c.bool(19),
                    )
                },
            ),
            Table(
                fileName = FRICTION_EVENTS,
                header = listOf(
                    "event_id", "timestamp_local", "timestamp_utc_ms", "logical_date",
                    "package_name", "app_name", "category", "event_kind", "outcome",
                    "schedule_id", "schedule_name", "escalation_tier", "pause_duration_sec", "post_countdown_reaction_ms",
                    "intention_tag", "selected_timer_min", "redirect_package", "redirect_app_name",
                ),
                start = listOf(LONG_MIN),
                sql = "SELECT f.id, f.timestamp_utc_ms, f.logical_date, p.package_name, p.display_name, p.category, f.event_kind, " +
                    "f.outcome, f.schedule_id, s.name, f.escalation_tier, f.pause_duration_sec, f.post_countdown_reaction_ms, " +
                    "f.intention_tag, f.selected_timer_min, rp.package_name, rp.display_name " +
                    "FROM friction_events f LEFT JOIN packages p ON p.id = f.pkg_id LEFT JOIN friction_schedules s ON s.id = f.schedule_id " +
                    "LEFT JOIN packages rp ON rp.id = f.redirect_pkg_id " +
                    "WHERE f.id > CAST(? AS INTEGER) ORDER BY f.id",
                key = { c -> listOf(c.getLong(0).toString()) },
                row = { c, x ->
                    listOf(
                        c.num(0), x.local(c.long(1)), c.num(1), c.str(2),
                        c.str(3), c.str(4), c.str(5), c.str(6), c.str(7),
                        c.num(8), c.str(9), c.num(10), c.num(11), c.num(12),
                        c.str(13), c.num(14), c.str(15), c.str(16),
                    )
                },
            ),
        )
    }
}
