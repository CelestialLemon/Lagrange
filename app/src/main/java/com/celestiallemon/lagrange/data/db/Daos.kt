package com.celestiallemon.lagrange.data.db

import androidx.room3.Dao
import androidx.room3.Insert
import androidx.room3.OnConflictStrategy
import androidx.room3.Query
import androidx.room3.Update
import androidx.room3.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface PackageDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(pkg: PackageEntity): Long

    @Query("SELECT id FROM packages WHERE package_name = :name")
    suspend fun idOf(name: String): Long?

    @Query("SELECT * FROM packages WHERE package_name = :name")
    suspend fun byName(name: String): PackageEntity?

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

    @Query("UPDATE packages SET category_confirmed = 1, updated_at_ms = :nowMs WHERE id IN (:ids)")
    suspend fun confirm(ids: List<Long>, nowMs: Long)
}

@Dao
interface ClassDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(cls: ClassEntity): Long

    @Query("SELECT id FROM classes WHERE class_name = :name")
    suspend fun idOf(name: String): Long?

    @Query("SELECT COUNT(*) FROM classes WHERE class_name = ''")
    suspend fun emptyClassRows(): Int
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
}

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

    @Query("UPDATE service_coverage_intervals SET last_seen_ms = :nowMs WHERE service_kind = :kind AND end_ms IS NULL")
    suspend fun heartbeat(kind: String, nowMs: Long)

    @Query("SELECT * FROM service_coverage_intervals ORDER BY start_ms, id")
    fun observeAll(): Flow<List<CoverageIntervalEntity>>

    @Query("SELECT * FROM service_coverage_intervals ORDER BY start_ms, id")
    suspend fun all(): List<CoverageIntervalEntity>
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
}

/** Writes for user-authored and derived tables. Phase 1 only needs these for schema tests; later phases extend it. */
@Dao
interface DerivedDao {
    @Upsert
    suspend fun upsertNote(row: DailyNoteEntity)

    @Insert
    suspend fun insertSchedule(row: FrictionScheduleEntity): Long

    @Query("DELETE FROM friction_schedules WHERE id = :id")
    suspend fun deleteSchedule(id: Long)

    @Insert
    suspend fun insertFrictionEvent(row: FrictionEventEntity): Long

    @Query("SELECT * FROM friction_events WHERE id = :id")
    suspend fun frictionEvent(id: Long): FrictionEventEntity?

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
}
