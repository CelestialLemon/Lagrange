package com.celestiallemon.lagrange.data.db

import androidx.room3.Dao
import androidx.room3.Query
import kotlinx.coroutines.flow.Flow

/** Self-report on one logical day's unlocked episodes (4–5 valued, 1–2 regretted, 3 neutral, null unrated). */
data class DayWorthRow(
    val logicalDate: String,
    val valuedMs: Long,
    val regrettedMs: Long,
    val neutralMs: Long,
    val unratedMs: Long,
    val valuedCount: Int,
    val regrettedCount: Int,
    val neutralCount: Int,
    val unratedCount: Int,
)

/** One app's usage summed over a date range; `covered*` only over days whose collection was continuous. */
data class AppRangeTotalsRow(val pkgId: Long, val totalMs: Long, val opens: Int, val coveredMs: Long, val coveredOpens: Int)

data class AppDayRow(val logicalDate: String, val totalMs: Long, val openCount: Int)

data class HourSlotRow(val hourSlot: Int, val durationMs: Long, val opens: Int)

data class RepeatedHourRow(val logicalDate: String, val durationMs: Long, val opens: Int)

/** The columns of `sessions` the deep dive reads. */
data class SessionLite(
    val id: Long,
    val logicalDate: String,
    val startMs: Long,
    val endMs: Long,
    val activeDurationMs: Long,
    val triggerEvidence: String,
    val entryPkgId: Long?,
    val worthItRating: Int?,
)

data class FrictionLite(val timestampMs: Long, val eventKind: String, val outcome: String)

/** Where a real (not blocked) run of an app started: reopen follow-ups for the Lab's friction view. */
data class RunStartRow(val pkgId: Long, val startMs: Long)

/** "Covered" for a joined `daily_rollups d` row: see [AnalyticsDao]. */
private const val COVERED = "d.coverage_status IN ('COMPLETE', 'NO_NOTIF_COVERAGE', 'BACKFILL') " +
    "AND d.day_status IN ('NORMAL', 'SPIKE', 'GOOD_DAY', 'INSUFFICIENT_BASELINE')"

/** Episodes with a segment of `:pkgId` starting in `[:fromMs, :toMs)` (bounded through the `pkg_id` index). */
private const val SESSIONS_WITH_APP =
    "SELECT session_id FROM session_segments WHERE pkg_id = :pkgId AND start_ms >= :fromMs AND start_ms < :toMs"

/**
 * Read-only aggregate queries for Trends and the per-app deep dive. Date bounds are inclusive `YYYY-MM-DD` strings
 * (which sort chronologically); instant bounds are half-open UTC ms. A day is *covered* when its rollup carries a
 * known, continuous coverage (`COMPLETE`, `NO_NOTIF_COVERAGE`, `BACKFILL`) with a known, baseline-eligible status,
 * and it is settled (before `firstUnsettled`): exactly `DayQuality.countsAsCovered`, unknown values excluded.
 */
@Dao
interface AnalyticsDao {
    @Query("SELECT MIN(logical_date) FROM daily_rollups")
    suspend fun earliestRollupDate(): String?

    @Query("SELECT * FROM daily_rollups WHERE logical_date >= :fromDate AND logical_date <= :toDate ORDER BY logical_date")
    suspend fun rollups(fromDate: String, toDate: String): List<DailyRollupEntity>

    @Query(
        "SELECT logical_date AS logicalDate, " +
            "SUM(CASE WHEN worth_it_rating IN (4, 5) THEN active_duration_ms ELSE 0 END) AS valuedMs, " +
            "SUM(CASE WHEN worth_it_rating IN (1, 2) THEN active_duration_ms ELSE 0 END) AS regrettedMs, " +
            "SUM(CASE WHEN worth_it_rating = 3 THEN active_duration_ms ELSE 0 END) AS neutralMs, " +
            "SUM(CASE WHEN worth_it_rating IS NULL OR worth_it_rating NOT BETWEEN 1 AND 5 THEN active_duration_ms ELSE 0 END) AS unratedMs, " +
            "SUM(CASE WHEN worth_it_rating IN (4, 5) THEN 1 ELSE 0 END) AS valuedCount, " +
            "SUM(CASE WHEN worth_it_rating IN (1, 2) THEN 1 ELSE 0 END) AS regrettedCount, " +
            "SUM(CASE WHEN worth_it_rating = 3 THEN 1 ELSE 0 END) AS neutralCount, " +
            "SUM(CASE WHEN worth_it_rating IS NULL OR worth_it_rating NOT BETWEEN 1 AND 5 THEN 1 ELSE 0 END) AS unratedCount " +
            "FROM sessions WHERE is_glance_only = 0 AND logical_date >= :fromDate AND logical_date <= :toDate GROUP BY logical_date",
    )
    suspend fun worthByDay(fromDate: String, toDate: String): List<DayWorthRow>

    @Query(
        "SELECT a.pkg_id AS pkgId, SUM(a.total_ms) AS totalMs, SUM(a.open_count) AS opens, " +
            "SUM(CASE WHEN $COVERED AND a.logical_date < :firstUnsettled THEN a.total_ms ELSE 0 END) AS coveredMs, " +
            "SUM(CASE WHEN $COVERED AND a.logical_date < :firstUnsettled THEN a.open_count ELSE 0 END) AS coveredOpens " +
            "FROM daily_app_rollups a LEFT JOIN daily_rollups d ON d.logical_date = a.logical_date " +
            "WHERE a.logical_date >= :fromDate AND a.logical_date <= :toDate GROUP BY a.pkg_id",
    )
    suspend fun appTotals(fromDate: String, toDate: String, firstUnsettled: String): List<AppRangeTotalsRow>

    @Query(
        "SELECT logical_date AS logicalDate, total_ms AS totalMs, open_count AS openCount FROM daily_app_rollups " +
            "WHERE pkg_id = :pkgId AND logical_date >= :fromDate AND logical_date <= :toDate ORDER BY logical_date",
    )
    suspend fun appDays(pkgId: Long, fromDate: String, toDate: String): List<AppDayRow>

    /** Ordinary hour slots (0..23) of one app, summed over the covered days of the range. */
    @Query(
        "SELECT h.hour_slot AS hourSlot, SUM(h.duration_ms) AS durationMs, SUM(h.open_count) AS opens " +
            "FROM hourly_app_usage h JOIN daily_rollups d ON d.logical_date = h.logical_date " +
            "WHERE h.pkg_id = :pkgId AND h.logical_date >= :fromDate AND h.logical_date <= :toDate AND h.logical_date < :firstUnsettled " +
            "AND $COVERED AND h.hour_slot < 24 GROUP BY h.hour_slot ORDER BY h.hour_slot",
    )
    suspend fun appHourSlots(pkgId: Long, fromDate: String, toDate: String, firstUnsettled: String): List<HourSlotRow>

    /** The repeated fall-back hour (slot 24) of one app, per covered day, so each can be placed at its wall-clock hour. */
    @Query(
        "SELECT h.logical_date AS logicalDate, h.duration_ms AS durationMs, h.open_count AS opens " +
            "FROM hourly_app_usage h JOIN daily_rollups d ON d.logical_date = h.logical_date " +
            "WHERE h.pkg_id = :pkgId AND h.logical_date >= :fromDate AND h.logical_date <= :toDate AND h.logical_date < :firstUnsettled " +
            "AND $COVERED AND h.hour_slot = 24",
    )
    suspend fun appRepeatedHours(pkgId: Long, fromDate: String, toDate: String, firstUnsettled: String): List<RepeatedHourRow>

    /**
     * Unlocked episodes with a segment of the app *starting* in `[fromMs, toMs)`. Selecting by the open (not by the
     * episode's start date) keeps an open just after the rollover on the day it happened.
     */
    @Query(
        "SELECT id, logical_date AS logicalDate, start_ms AS startMs, end_ms AS endMs, active_duration_ms AS activeDurationMs, " +
            "trigger_evidence AS triggerEvidence, entry_pkg_id AS entryPkgId, worth_it_rating AS worthItRating FROM sessions " +
            "WHERE is_glance_only = 0 AND id IN ($SESSIONS_WITH_APP) ORDER BY start_ms",
    )
    suspend fun sessionsWithApp(pkgId: Long, fromMs: Long, toMs: Long): List<SessionLite>

    /** Every segment (any app) of those episodes, in order: sequences need the neighbours too. */
    @Query(
        "SELECT g.* FROM session_segments g JOIN sessions s ON s.id = g.session_id " +
            "WHERE s.is_glance_only = 0 AND g.session_id IN ($SESSIONS_WITH_APP) ORDER BY g.session_id, g.seq_index",
    )
    suspend fun segmentsOfSessionsWithApp(pkgId: Long, fromMs: Long, toMs: Long): List<SessionSegmentEntity>

    /** Starts of real (not blocked) runs of the app in `[fromMs, toMs)`: follow-ups of exits near the window's end. */
    @Query(
        "SELECT g.start_ms FROM session_segments g JOIN sessions s ON s.id = g.session_id " +
            "WHERE g.pkg_id = :pkgId AND g.is_blocked_attempt = 0 AND g.duration_ms > 0 AND s.is_glance_only = 0 " +
            "AND g.start_ms >= :fromMs AND g.start_ms < :toMs ORDER BY g.start_ms",
    )
    suspend fun runStarts(pkgId: Long, fromMs: Long, toMs: Long): List<Long>

    /** Latest end of a real (not blocked) run of the app that started before [beforeMs]: context for a loop across the window start. */
    @Query(
        "SELECT MAX(g.end_ms) FROM session_segments g JOIN sessions s ON s.id = g.session_id " +
            "WHERE g.pkg_id = :pkgId AND g.is_blocked_attempt = 0 AND g.duration_ms > 0 AND s.is_glance_only = 0 " +
            "AND g.start_ms < :beforeMs",
    )
    suspend fun lastRunEndBefore(pkgId: Long, beforeMs: Long): Long?

    @Query(
        "SELECT timestamp_utc_ms AS timestampMs, event_kind AS eventKind, outcome FROM friction_events " +
            "WHERE pkg_id = :pkgId AND timestamp_utc_ms >= :fromMs AND timestamp_utc_ms < :toMs ORDER BY timestamp_utc_ms, id",
    )
    suspend fun frictionOf(pkgId: Long, fromMs: Long, toMs: Long): List<FrictionLite>

    // -- Behavior Lab (read-only) -------------------------------------------------------------------------------------

    /** Unlocked episodes counted on the logical days of the range (an episode belongs to the day it started). */
    @Query(
        "SELECT * FROM sessions WHERE is_glance_only = 0 AND logical_date >= :fromDate AND logical_date <= :toDate ORDER BY start_ms, id",
    )
    suspend fun unlockedSessions(fromDate: String, toDate: String): List<SessionEntity>

    /** Of the unlocked episodes that started before [beforeMs], the one that ended last. */
    @Query("SELECT * FROM sessions WHERE is_glance_only = 0 AND start_ms < :beforeMs ORDER BY end_ms DESC, id DESC LIMIT 1")
    suspend fun lastUnlockedEndingBefore(beforeMs: Long): SessionEntity?

    /** Every segment of those episodes, in order. */
    @Query(
        "SELECT g.* FROM session_segments g JOIN sessions s ON s.id = g.session_id " +
            "WHERE s.is_glance_only = 0 AND s.logical_date >= :fromDate AND s.logical_date <= :toDate ORDER BY g.session_id, g.seq_index",
    )
    suspend fun unlockedSegments(fromDate: String, toDate: String): List<SessionSegmentEntity>

    @Query(
        "SELECT * FROM sessions WHERE is_glance_only = 0 AND is_rabbit_hole = 1 AND logical_date >= :fromDate AND logical_date <= :toDate " +
            "ORDER BY start_ms DESC, id",
    )
    suspend fun rabbitHolesBetween(fromDate: String, toDate: String): List<SessionEntity>

    @Query(
        "SELECT g.* FROM session_segments g JOIN sessions s ON s.id = g.session_id WHERE s.is_glance_only = 0 AND s.is_rabbit_hole = 1 " +
            "AND s.logical_date >= :fromDate AND s.logical_date <= :toDate ORDER BY g.session_id, g.seq_index",
    )
    suspend fun rabbitHoleSegmentsBetween(fromDate: String, toDate: String): List<SessionSegmentEntity>

    /** Rabbit holes that ended at or after [sinceMs]: candidates for the "Was that worth it?" check-in. */
    @Query(
        "SELECT * FROM sessions WHERE is_glance_only = 0 AND is_rabbit_hole = 1 AND end_ms >= :sinceMs ORDER BY end_ms DESC, id",
    )
    suspend fun rabbitHolesEndingSince(sinceMs: Long): List<SessionEntity>

    @Query(
        "SELECT g.* FROM session_segments g JOIN sessions s ON s.id = g.session_id " +
            "WHERE s.is_glance_only = 0 AND s.is_rabbit_hole = 1 AND s.end_ms >= :sinceMs ORDER BY g.session_id, g.seq_index",
    )
    suspend fun rabbitHoleSegmentsEndingSince(sinceMs: Long): List<SessionSegmentEntity>

    @Query("SELECT * FROM sessions WHERE episode_key = :episodeKey AND is_glance_only = 0 ORDER BY id LIMIT 1")
    suspend fun sessionByKey(episodeKey: String): SessionEntity?

    @Query("SELECT * FROM session_segments WHERE session_id = :sessionId ORDER BY seq_index")
    suspend fun segmentsOf(sessionId: Long): List<SessionSegmentEntity>

    @Query("SELECT * FROM daily_notes WHERE logical_date >= :fromDate AND logical_date <= :toDate")
    suspend fun notesBetween(fromDate: String, toDate: String): List<DailyNoteEntity>

    @Query("SELECT * FROM daily_app_rollups WHERE logical_date IN (:dates)")
    suspend fun appRollupsFor(dates: List<String>): List<DailyAppRollupEntity>

    @Query("SELECT * FROM hourly_app_usage WHERE logical_date IN (:dates)")
    suspend fun hourlyFor(dates: List<String>): List<HourlyAppUsageEntity>

    /** Hour slots (0..24) of every app of a category, per day, over the range. */
    @Query(
        "SELECT h.* FROM hourly_app_usage h JOIN packages p ON p.id = h.pkg_id " +
            "WHERE p.category = :category AND h.logical_date >= :fromDate AND h.logical_date <= :toDate",
    )
    suspend fun hourlyOfCategory(category: String, fromDate: String, toDate: String): List<HourlyAppUsageEntity>

    /** Starts of real (not blocked) runs of any app in `[fromMs, toMs)`. */
    @Query(
        "SELECT g.pkg_id AS pkgId, g.start_ms AS startMs FROM session_segments g JOIN sessions s ON s.id = g.session_id " +
            "WHERE g.is_blocked_attempt = 0 AND g.duration_ms > 0 AND s.is_glance_only = 0 " +
            "AND g.start_ms >= :fromMs AND g.start_ms < :toMs ORDER BY g.start_ms",
    )
    suspend fun runStartsBetween(fromMs: Long, toMs: Long): List<RunStartRow>

    /** Refresh signal for the Lab: notes written from Today (never shown). */
    @Query("SELECT COUNT(*) || ':' || COALESCE(MAX(updated_at_ms), 0) FROM daily_notes")
    fun observeNotesSignal(): Flow<String>

    @Query("SELECT * FROM packages WHERE id = :id")
    suspend fun packageById(id: Long): PackageEntity?

    @Query("SELECT * FROM packages WHERE id = :id")
    fun observePackage(id: Long): Flow<PackageEntity?>

    @Query("UPDATE packages SET daily_goal_ms = :goalMs, updated_at_ms = :nowMs WHERE id = :id")
    suspend fun setDailyGoal(id: Long, goalMs: Long?, nowMs: Long): Int

    /**
     * Changes a rebuild doesn't announce through `derived_last_run_at_ms`: a rating written from Today, or a friction
     * event. Only a refresh signal, never shown. (Rebuilds are observed through that ingestion key.)
     */
    @Query(
        "SELECT (SELECT COUNT(worth_it_rating) || ':' || COALESCE(SUM(worth_it_rating * (id % 9973 + 1)), 0) FROM sessions) || '|' || " +
            "(SELECT COUNT(*) || ':' || COALESCE(MAX(id), 0) FROM friction_events)",
    )
    fun observeUserSignals(): Flow<String>
}
