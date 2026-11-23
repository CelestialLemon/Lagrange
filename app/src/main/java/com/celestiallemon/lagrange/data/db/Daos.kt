package com.celestiallemon.lagrange.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

/** An interned name and its id. */
data class NameId(val id: Long, val name: String)

@Dao
interface PackageDao {
    @Query("SELECT id AS id, package_name AS name FROM packages WHERE package_name IN (:names)")
    suspend fun idsOf(names: List<String>): List<NameId>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(pkg: PackageEntity): Long

    @Query("SELECT id FROM packages WHERE package_name = :name")
    suspend fun idOf(name: String): Long?

    @Query("SELECT * FROM packages WHERE package_name = :name")
    suspend fun byName(name: String): PackageEntity?

    @Query("SELECT * FROM packages WHERE id = :id")
    suspend fun byId(id: Long): PackageEntity?

    @Query("SELECT * FROM packages ORDER BY display_name COLLATE NOCASE")
    fun observeAll(): Flow<List<PackageEntity>>

    @Query("SELECT * FROM packages")
    suspend fun all(): List<PackageEntity>

    @Update
    suspend fun update(pkg: PackageEntity)

    @Query(
        "UPDATE packages SET category = :category, category_confirmed = 1, updated_at_ms = :nowMs " +
            "WHERE package_name = :name",
    )
    suspend fun setCategory(name: String, category: String, nowMs: Long): Int

    @Query(
        "UPDATE packages SET friction_override_json = :overrideJson, updated_at_ms = :nowMs " +
            "WHERE package_name = :name",
    )
    suspend fun setFrictionOverride(name: String, overrideJson: String?, nowMs: Long): Int

    @Query(
        "UPDATE packages SET is_installed = :installed, uninstalled_at_ms = :uninstalledAtMs, updated_at_ms = :nowMs " +
            "WHERE id = :id",
    )
    suspend fun setInstallState(id: Long, installed: Boolean, uninstalledAtMs: Long?, nowMs: Long): Int

    @Query("UPDATE packages SET category_confirmed = 1, updated_at_ms = :nowMs WHERE id IN (:ids)")
    suspend fun confirm(ids: List<Long>, nowMs: Long)
}

@Dao
interface ClassDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(cls: ClassEntity): Long

    @Query("SELECT id FROM classes WHERE class_name = :name")
    suspend fun idOf(name: String): Long?

    @Query("SELECT id AS id, class_name AS name FROM classes WHERE class_name IN (:names)")
    suspend fun idsOf(names: List<String>): List<NameId>

    @Query("SELECT COUNT(*) FROM classes WHERE class_name = ''")
    suspend fun emptyClassRows(): Int

    /** Chrome (and other browsers') Custom Tab activities. */
    @Query("SELECT id FROM classes WHERE class_name LIKE '%CustomTabActivity'")
    suspend fun customTabIds(): List<Long>

    @Query("SELECT id AS id, class_name AS name FROM classes WHERE id IN (:ids)")
    suspend fun namesOf(ids: List<Long>): List<NameId>
}

/** Per-type event counts, for probes and diagnostics. */
data class EventTypeCount(val eventType: Int, val count: Long)

/** How much recent foreground time each package has, used to order the categorization review. */
data class PackageUsageRank(val pkgId: Long, val resumeCount: Long)

@Dao
interface RawUsageEventDao {
    /** Returns the new row id, or -1 where the unique key already existed (an overlapping re-query). */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(events: List<RawUsageEventEntity>): List<Long>

    @Query("SELECT COUNT(*) FROM raw_usage_events")
    fun observeCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM raw_usage_events")
    suspend fun count(): Long

    @Query("SELECT COUNT(*) FROM raw_usage_events WHERE timestamp_utc_ms >= :fromMs")
    fun observeCountSince(fromMs: Long): Flow<Long>

    @Query("SELECT MIN(timestamp_utc_ms) FROM raw_usage_events")
    fun observeEarliest(): Flow<Long?>

    @Query("SELECT event_type AS eventType, COUNT(*) AS count FROM raw_usage_events GROUP BY event_type")
    suspend fun countsByType(): List<EventTypeCount>

    @Query(
        "SELECT pkg_id AS pkgId, COUNT(*) AS resumeCount FROM raw_usage_events " +
            "WHERE event_type = 1 AND timestamp_utc_ms >= :fromMs GROUP BY pkg_id",
    )
    suspend fun resumeCountsSince(fromMs: Long): List<PackageUsageRank>

    @Query("SELECT * FROM raw_usage_events ORDER BY timestamp_utc_ms, id")
    suspend fun allForTest(): List<RawUsageEventEntity>

    /** The ledger slice `[fromMs, toMs)` in source order. */
    @Query("SELECT * FROM raw_usage_events WHERE timestamp_utc_ms >= :fromMs AND timestamp_utc_ms < :toMs ORDER BY timestamp_utc_ms, id")
    suspend fun between(fromMs: Long, toMs: Long): List<RawUsageEventEntity>

    @Query("SELECT MIN(timestamp_utc_ms) FROM raw_usage_events")
    suspend fun earliest(): Long?

    @Query("SELECT MAX(timestamp_utc_ms) FROM raw_usage_events")
    suspend fun latest(): Long?

    /** The ledger slice with names resolved, for the Debug Timeline Inspector. */
    @Query(
        "SELECT e.id AS id, e.timestamp_utc_ms AS timestampMs, e.event_type AS eventType, p.package_name AS packageName, " +
            "c.class_name AS className FROM raw_usage_events e JOIN packages p ON p.id = e.pkg_id JOIN classes c ON c.id = e.cls_id " +
            "WHERE e.timestamp_utc_ms >= :fromMs AND e.timestamp_utc_ms < :toMs ORDER BY e.timestamp_utc_ms, e.id",
    )
    suspend fun namedBetween(fromMs: Long, toMs: Long): List<NamedUsageEvent>
}

/** A raw usage event with its package and class names. */
data class NamedUsageEvent(val id: Long, val timestampMs: Long, val eventType: Int, val packageName: String, val className: String)

@Dao
interface RawNotificationDao {
    @Insert
    suspend fun insert(row: RawNotificationEntity): Long

    @Update
    suspend fun update(row: RawNotificationEntity)

    /** The live (not yet removed) record for a notification key, if any. */
    @Query("SELECT * FROM raw_notifications WHERE sbn_key_hash = :keyHash AND removed_at_ms IS NULL ORDER BY id DESC LIMIT 1")
    suspend fun openByKey(keyHash: String): RawNotificationEntity?

    @Query("SELECT * FROM raw_notifications WHERE removed_at_ms IS NULL")
    suspend fun allOpen(): List<RawNotificationEntity>

    @Query("SELECT COUNT(*) FROM raw_notifications")
    fun observeCount(): Flow<Long>

    @Query("SELECT COUNT(*) FROM raw_notifications WHERE posted_at_ms >= :fromMs")
    fun observeCountSince(fromMs: Long): Flow<Long>

    @Query("SELECT * FROM raw_notifications ORDER BY id")
    suspend fun all(): List<RawNotificationEntity>

    @Query("SELECT * FROM raw_notifications WHERE id = :id")
    suspend fun byId(id: Long): RawNotificationEntity?

    /** Trigger-eligible notifications alive at some point in `[fromMs, toMs)` (posted before its end, not removed before its start). */
    @Query(
        "SELECT * FROM raw_notifications WHERE posted_at_ms < :toMs AND (removed_at_ms IS NULL OR removed_at_ms >= :fromMs) " +
            "AND is_ongoing = 0 AND is_foreground_service = 0 AND is_group_summary = 0 ORDER BY posted_at_ms, id",
    )
    suspend fun eligibleAlive(fromMs: Long, toMs: Long): List<RawNotificationEntity>

    /** Every notification posted or removed in `[fromMs, toMs)`, for the inspector. */
    @Query(
        "SELECT * FROM raw_notifications WHERE (posted_at_ms >= :fromMs AND posted_at_ms < :toMs) " +
            "OR (removed_at_ms >= :fromMs AND removed_at_ms < :toMs) ORDER BY posted_at_ms, id",
    )
    suspend fun touching(fromMs: Long, toMs: Long): List<RawNotificationEntity>
}

@Dao
interface TzHistoryDao {
    @Insert
    suspend fun insert(row: TzHistoryEntity): Long

    @Query("SELECT * FROM tz_history ORDER BY effective_from_utc_ms DESC, id DESC LIMIT 1")
    suspend fun latest(): TzHistoryEntity?

    @Query("SELECT * FROM tz_history ORDER BY effective_from_utc_ms, id")
    suspend fun all(): List<TzHistoryEntity>
}

@Dao
interface CoverageDao {
    @Insert
    suspend fun insert(row: CoverageIntervalEntity): Long

    @Update
    suspend fun update(row: CoverageIntervalEntity)

    @Query("SELECT * FROM service_coverage_intervals WHERE service_kind = :kind AND end_ms IS NULL ORDER BY start_ms")
    suspend fun open(kind: String): List<CoverageIntervalEntity>

    @Query(
        "SELECT * FROM service_coverage_intervals WHERE service_kind = :kind AND status = :status " +
            "ORDER BY start_ms DESC, id DESC LIMIT 1",
    )
    suspend fun latest(kind: String, status: String): CoverageIntervalEntity?

    @Query("UPDATE service_coverage_intervals SET last_seen_ms = MAX(last_seen_ms, :nowMs) WHERE service_kind = :kind AND end_ms IS NULL")
    suspend fun heartbeat(kind: String, nowMs: Long)

    @Query("SELECT * FROM service_coverage_intervals ORDER BY start_ms, id")
    fun observeAll(): Flow<List<CoverageIntervalEntity>>

    @Query("SELECT * FROM service_coverage_intervals ORDER BY start_ms, id")
    suspend fun all(): List<CoverageIntervalEntity>

    @Query("SELECT * FROM service_coverage_intervals WHERE start_ms < :toMs AND (end_ms IS NULL OR end_ms > :fromMs) ORDER BY start_ms, id")
    suspend fun overlapping(fromMs: Long, toMs: Long): List<CoverageIntervalEntity>
}

@Dao
interface IngestionStateDao {
    @Upsert
    suspend fun put(row: IngestionStateEntity)

    @Query("SELECT long_value FROM ingestion_state WHERE `key` = :key")
    suspend fun get(key: String): Long?

    @Query("SELECT long_value FROM ingestion_state WHERE `key` = :key")
    fun observe(key: String): Flow<Long?>

    @Query("DELETE FROM ingestion_state WHERE `key` = :key")
    suspend fun remove(key: String)
}

@Dao
interface SettingsHistoryDao {
    @Insert
    suspend fun insert(row: SettingsHistoryEntity): Long

    @Query("SELECT * FROM settings_history ORDER BY timestamp_utc_ms, id")
    suspend fun all(): List<SettingsHistoryEntity>

    @Query("SELECT * FROM settings_history WHERE setting_key = :key ORDER BY timestamp_utc_ms, id")
    suspend fun byKey(key: String): List<SettingsHistoryEntity>

    /** The row written last (highest id), whatever the wall clock said when it was written. */
    @Query("SELECT * FROM settings_history WHERE setting_key = :key ORDER BY id DESC LIMIT 1")
    suspend fun lastWritten(key: String): SettingsHistoryEntity?
}

/** An existing self-report, read before a rebuild replaces the derived rows it lives on. */
data class RatingRow(val episodeKey: String, val startMs: Long, val endMs: Long, val worthItRating: Int)

/** Reads and writes for user-authored and derived tables. */
@Dao
interface DerivedDao {
    @Upsert
    suspend fun upsertNote(row: DailyNoteEntity)

    @Insert
    suspend fun insertSchedule(row: FrictionScheduleEntity): Long

    @Update
    suspend fun updateSchedule(row: FrictionScheduleEntity)

    @Query("UPDATE friction_schedules SET enabled = :enabled WHERE id = :id")
    suspend fun setScheduleEnabled(id: Long, enabled: Boolean): Int

    @Query("SELECT * FROM friction_schedules ORDER BY id")
    fun observeSchedules(): Flow<List<FrictionScheduleEntity>>

    @Query("SELECT * FROM friction_schedules ORDER BY id")
    suspend fun allSchedules(): List<FrictionScheduleEntity>

    @Query("SELECT * FROM friction_schedules WHERE id = :id")
    suspend fun scheduleById(id: Long): FrictionScheduleEntity?

    @Query("DELETE FROM friction_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Long)

    @Insert
    suspend fun insertFrictionEvent(row: FrictionEventEntity): Long

    @Query("SELECT * FROM friction_events WHERE id = :id")
    suspend fun frictionEvent(id: Long): FrictionEventEntity?

    @Query("SELECT * FROM friction_events WHERE logical_date = :logicalDate ORDER BY timestamp_utc_ms, id")
    suspend fun frictionOn(logicalDate: String): List<FrictionEventEntity>

    @Query("SELECT COUNT(*) FROM friction_events WHERE timestamp_utc_ms >= :fromMs AND timestamp_utc_ms < :toMs AND event_kind = 'LAUNCH_PAUSE'")
    suspend fun launchPauseCountBetween(fromMs: Long, toMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM friction_events WHERE timestamp_utc_ms >= :fromMs AND timestamp_utc_ms < :toMs AND pkg_id = :pkgId " +
            "AND event_kind = 'LAUNCH_PAUSE' AND outcome = 'CONTINUED'",
    )
    suspend fun continuedLaunchPausesBetween(fromMs: Long, toMs: Long, pkgId: Long): Int

    @Query(
        "SELECT COUNT(*) FROM friction_events WHERE timestamp_utc_ms >= :fromMs AND timestamp_utc_ms < :toMs AND event_kind = 'LAUNCH_PAUSE' " +
            "AND outcome IN ('CLOSED_GO_HOME', 'CLOSED_SYSTEM_NAV', 'REDIRECTED')",
    )
    fun observeBlockedCountBetween(fromMs: Long, toMs: Long): Flow<Int>

    @Query("SELECT * FROM daily_app_rollups WHERE logical_date = :logicalDate AND pkg_id = :pkgId")
    suspend fun appRollup(logicalDate: String, pkgId: Long): DailyAppRollupEntity?

    @Query("SELECT * FROM daily_app_rollups WHERE logical_date = :logicalDate")
    suspend fun appRollupsOn(logicalDate: String): List<DailyAppRollupEntity>

    @Insert
    suspend fun insertSession(row: SessionEntity): Long

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: Long)

    @Insert
    suspend fun insertSegment(row: SessionSegmentEntity): Long

    @Query("SELECT COUNT(*) FROM session_segments WHERE session_id = :sessionId")
    suspend fun segmentCount(sessionId: Long): Int

    @Upsert
    suspend fun upsertDailyRollup(row: DailyRollupEntity)

    @Upsert
    suspend fun upsertDailyAppRollup(row: DailyAppRollupEntity)

    @Upsert
    suspend fun upsertHourly(row: HourlyAppUsageEntity)

    // -- Rebuilds ---------------------------------------------------------------------------------------------------

    @Query("SELECT * FROM friction_events WHERE timestamp_utc_ms >= :fromMs AND timestamp_utc_ms < :toMs ORDER BY timestamp_utc_ms, id")
    suspend fun frictionBetween(fromMs: Long, toMs: Long): List<FrictionEventEntity>

    @Query(
        "SELECT episode_key AS episodeKey, start_ms AS startMs, end_ms AS endMs, worth_it_rating AS worthItRating FROM sessions " +
            "WHERE start_ms >= :fromMs AND start_ms < :toMs AND worth_it_rating IS NOT NULL",
    )
    suspend fun ratingsStartingIn(fromMs: Long, toMs: Long): List<RatingRow>

    /** Segments go with their sessions (ON DELETE CASCADE). */
    @Query("DELETE FROM sessions WHERE start_ms >= :fromMs AND start_ms < :toMs")
    suspend fun deleteSessionsStartingIn(fromMs: Long, toMs: Long): Int

    @Insert
    suspend fun insertSegments(rows: List<SessionSegmentEntity>)

    @Query("DELETE FROM daily_app_rollups WHERE logical_date >= :fromDate AND logical_date <= :toDate")
    suspend fun deleteAppRollups(fromDate: String, toDate: String)

    @Query("DELETE FROM hourly_app_usage WHERE logical_date >= :fromDate AND logical_date <= :toDate")
    suspend fun deleteHourly(fromDate: String, toDate: String)

    // Rows outside the days a full rebuild covers (left behind when the rollover hour moved a boundary).
    @Query("DELETE FROM daily_rollups WHERE logical_date < :fromDate OR logical_date > :toDate")
    suspend fun deleteDailyOutside(fromDate: String, toDate: String)

    @Query("DELETE FROM daily_app_rollups WHERE logical_date < :fromDate OR logical_date > :toDate")
    suspend fun deleteAppRollupsOutside(fromDate: String, toDate: String)

    @Query("DELETE FROM hourly_app_usage WHERE logical_date < :fromDate OR logical_date > :toDate")
    suspend fun deleteHourlyOutside(fromDate: String, toDate: String)

    @Query("DELETE FROM sessions WHERE start_ms < :fromMs OR start_ms >= :toMs")
    suspend fun deleteSessionsOutside(fromMs: Long, toMs: Long)

    @Insert
    suspend fun insertAppRollups(rows: List<DailyAppRollupEntity>)

    @Insert
    suspend fun insertHourly(rows: List<HourlyAppUsageEntity>)

    @Query("SELECT * FROM daily_rollups WHERE logical_date >= :fromDate AND logical_date <= :toDate ORDER BY logical_date")
    suspend fun dailyRollupsBetween(fromDate: String, toDate: String): List<DailyRollupEntity>

    @Query("SELECT * FROM daily_rollups WHERE logical_date = :date")
    suspend fun dailyRollup(date: String): DailyRollupEntity?

    @Query("UPDATE daily_rollups SET baseline_median_ms = :medianMs, day_status = :status WHERE logical_date = :date")
    suspend fun updateBaseline(date: String, medianMs: Long?, status: String)

    @Query("SELECT * FROM hourly_app_usage WHERE logical_date IN (:dates)")
    suspend fun hourlyFor(dates: List<String>): List<HourlyAppUsageEntity>

    // -- Today & inspector ------------------------------------------------------------------------------------------

    @Query("SELECT * FROM daily_rollups WHERE logical_date = :date")
    fun observeDailyRollup(date: String): Flow<DailyRollupEntity?>

    @Query("SELECT * FROM sessions WHERE logical_date = :date ORDER BY start_ms")
    fun observeSessions(date: String): Flow<List<SessionEntity>>

    @Query(
        "SELECT g.* FROM session_segments g JOIN sessions s ON s.id = g.session_id WHERE s.logical_date = :date " +
            "ORDER BY g.session_id, g.seq_index",
    )
    fun observeSegments(date: String): Flow<List<SessionSegmentEntity>>

    @Query("SELECT * FROM sessions WHERE logical_date = :date ORDER BY start_ms")
    suspend fun sessionsFor(date: String): List<SessionEntity>

    @Query(
        "SELECT g.* FROM session_segments g JOIN sessions s ON s.id = g.session_id WHERE s.logical_date = :date " +
            "ORDER BY g.session_id, g.seq_index",
    )
    suspend fun segmentsFor(date: String): List<SessionSegmentEntity>

    @Query("SELECT * FROM sessions ORDER BY start_ms")
    suspend fun allSessions(): List<SessionEntity>

    /** Sessions running across [atMs] (started before it, still going after it). */
    @Query("SELECT * FROM sessions WHERE start_ms < :atMs AND end_ms > :atMs ORDER BY start_ms")
    suspend fun sessionsSpanning(atMs: Long): List<SessionEntity>

    @Query("DELETE FROM daily_rollups WHERE logical_date = :date")
    suspend fun deleteDailyRollup(date: String)

    @Query("SELECT * FROM session_segments WHERE session_id = :sessionId ORDER BY seq_index")
    suspend fun segmentsOfSession(sessionId: Long): List<SessionSegmentEntity>

    @Query("UPDATE sessions SET worth_it_rating = :rating WHERE episode_key = :episodeKey AND is_glance_only = 0")
    suspend fun setRatingByKey(episodeKey: String, rating: Int?): Int

    @Query(
        "UPDATE sessions SET worth_it_rating = :rating WHERE id = (SELECT id FROM sessions WHERE is_glance_only = 0 " +
            "AND start_ms <= :atMs AND end_ms >= :atMs ORDER BY start_ms DESC LIMIT 1)",
    )
    suspend fun setRatingContaining(atMs: Long, rating: Int?): Int

    @Query("SELECT * FROM daily_notes WHERE logical_date = :date")
    fun observeNote(date: String): Flow<DailyNoteEntity?>

    @Query("DELETE FROM daily_notes WHERE logical_date = :date")
    suspend fun deleteNote(date: String)

    @Query("SELECT * FROM daily_app_rollups WHERE logical_date = :date ORDER BY total_ms DESC")
    fun observeAppRollups(date: String): Flow<List<DailyAppRollupEntity>>

    @Query("SELECT * FROM friction_events WHERE pkg_id = :pkgId AND timestamp_utc_ms >= :fromMs AND timestamp_utc_ms < :toMs ORDER BY timestamp_utc_ms")
    suspend fun frictionFor(pkgId: Long, fromMs: Long, toMs: Long): List<FrictionEventEntity>
}
