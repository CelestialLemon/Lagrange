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

/**
 * Read-only aggregate queries for Trends and the per-app deep dive. Date bounds are inclusive `YYYY-MM-DD` strings
 * (which sort chronologically). "Covered" means the day's rollup is neither `UNCERTAIN_COVERAGE` nor `PARTIAL_HARVEST` and the day isn't
 * [inProgress] (today), matching `DayQuality.countsAsCovered`.
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
            "SUM(CASE WHEN d.day_status IS NOT NULL AND d.day_status != 'UNCERTAIN_COVERAGE' AND d.coverage_status != 'PARTIAL_HARVEST' AND a.logical_date != :inProgress THEN a.total_ms ELSE 0 END) AS coveredMs, " +
            "SUM(CASE WHEN d.day_status IS NOT NULL AND d.day_status != 'UNCERTAIN_COVERAGE' AND d.coverage_status != 'PARTIAL_HARVEST' AND a.logical_date != :inProgress THEN a.open_count ELSE 0 END) AS coveredOpens " +
            "FROM daily_app_rollups a LEFT JOIN daily_rollups d ON d.logical_date = a.logical_date " +
            "WHERE a.logical_date >= :fromDate AND a.logical_date <= :toDate GROUP BY a.pkg_id",
    )
    suspend fun appTotals(fromDate: String, toDate: String, inProgress: String): List<AppRangeTotalsRow>

    @Query(
        "SELECT logical_date AS logicalDate, total_ms AS totalMs, open_count AS openCount FROM daily_app_rollups " +
            "WHERE pkg_id = :pkgId AND logical_date >= :fromDate AND logical_date <= :toDate ORDER BY logical_date",
    )
    suspend fun appDays(pkgId: Long, fromDate: String, toDate: String): List<AppDayRow>

    /** Ordinary hour slots (0..23) of one app, summed over the covered days of the range. */
    @Query(
        "SELECT h.hour_slot AS hourSlot, SUM(h.duration_ms) AS durationMs, SUM(h.open_count) AS opens " +
            "FROM hourly_app_usage h JOIN daily_rollups d ON d.logical_date = h.logical_date " +
            "WHERE h.pkg_id = :pkgId AND h.logical_date >= :fromDate AND h.logical_date <= :toDate AND h.logical_date != :inProgress " +
            "AND d.day_status != 'UNCERTAIN_COVERAGE' AND d.coverage_status != 'PARTIAL_HARVEST' AND h.hour_slot < 24 GROUP BY h.hour_slot ORDER BY h.hour_slot",
    )
    suspend fun appHourSlots(pkgId: Long, fromDate: String, toDate: String, inProgress: String): List<HourSlotRow>

    /** The repeated fall-back hour (slot 24) of one app, per covered day, so each can be placed at its wall-clock hour. */
    @Query(
        "SELECT h.logical_date AS logicalDate, h.duration_ms AS durationMs, h.open_count AS opens " +
            "FROM hourly_app_usage h JOIN daily_rollups d ON d.logical_date = h.logical_date " +
            "WHERE h.pkg_id = :pkgId AND h.logical_date >= :fromDate AND h.logical_date <= :toDate AND h.logical_date != :inProgress " +
            "AND d.day_status != 'UNCERTAIN_COVERAGE' AND d.coverage_status != 'PARTIAL_HARVEST' AND h.hour_slot = 24",
    )
    suspend fun appRepeatedHours(pkgId: Long, fromDate: String, toDate: String, inProgress: String): List<RepeatedHourRow>

    /** Unlocked episodes in the range in which the app has at least one segment. */
    @Query(
        "SELECT id, logical_date AS logicalDate, start_ms AS startMs, end_ms AS endMs, active_duration_ms AS activeDurationMs, " +
            "trigger_evidence AS triggerEvidence, entry_pkg_id AS entryPkgId, worth_it_rating AS worthItRating FROM sessions " +
            "WHERE is_glance_only = 0 AND logical_date >= :fromDate AND logical_date <= :toDate " +
            "AND id IN (SELECT session_id FROM session_segments WHERE pkg_id = :pkgId) ORDER BY start_ms",
    )
    suspend fun sessionsWithApp(pkgId: Long, fromDate: String, toDate: String): List<SessionLite>

    /** Every segment (any app) of those episodes, in order: sequences need the neighbours too. */
    @Query(
        "SELECT g.* FROM session_segments g JOIN sessions s ON s.id = g.session_id " +
            "WHERE s.is_glance_only = 0 AND s.logical_date >= :fromDate AND s.logical_date <= :toDate " +
            "AND g.session_id IN (SELECT session_id FROM session_segments WHERE pkg_id = :pkgId) ORDER BY g.session_id, g.seq_index",
    )
    suspend fun segmentsOfSessionsWithApp(pkgId: Long, fromDate: String, toDate: String): List<SessionSegmentEntity>

    @Query(
        "SELECT timestamp_utc_ms AS timestampMs, event_kind AS eventKind, outcome FROM friction_events " +
            "WHERE pkg_id = :pkgId AND logical_date >= :fromDate AND logical_date <= :toDate ORDER BY timestamp_utc_ms, id",
    )
    suspend fun frictionOf(pkgId: Long, fromDate: String, toDate: String): List<FrictionLite>

    @Query("SELECT * FROM packages WHERE id = :id")
    suspend fun packageById(id: Long): PackageEntity?

    @Query("SELECT * FROM packages WHERE id = :id")
    fun observePackage(id: Long): Flow<PackageEntity?>

    @Query("UPDATE packages SET daily_goal_ms = :goalMs, updated_at_ms = :nowMs WHERE id = :id")
    suspend fun setDailyGoal(id: Long, goalMs: Long?, nowMs: Long): Int

    /**
     * A fingerprint of the derived rows Trends draws from: changes when a rebuild rewrites rollups (usage, counts,
     * coverage or status) or a rating is written. Only a refresh signal, never shown.
     */
    @Query(
        "SELECT (SELECT COUNT(*) || ':' || COALESCE(SUM(active_usage_ms + total_screen_ms + distracting_ms + episode_count + glance_count " +
            "+ physical_pickup_count + rabbit_hole_count), 0) || ':' || COALESCE(SUM(LENGTH(day_status) * 31 + LENGTH(coverage_status)), 0) " +
            "FROM daily_rollups) || '|' || (SELECT COUNT(worth_it_rating) || ':' || COALESCE(SUM(worth_it_rating * (id % 9973)), 0) FROM sessions)",
    )
    fun observeDerivedSignature(): Flow<String>
}
