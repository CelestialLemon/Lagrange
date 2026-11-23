package com.celestiallemon.lagrange.data.db

import androidx.room3.ColumnInfo
import androidx.room3.Entity
import androidx.room3.ForeignKey
import androidx.room3.Index
import androidx.room3.PrimaryKey

// ---------------------------------------------------------------------------------------------------------------------
// Interning lookup tables. Raw ledgers reference these by integer id so years of history stay compact.
// ---------------------------------------------------------------------------------------------------------------------

@Entity(tableName = "packages", indices = [Index(value = ["package_name"], unique = true)])
data class PackageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "package_name") val packageName: String,
    @ColumnInfo(name = "display_name") val displayName: String,
    /** An [AppCategory] name. */
    val category: String,
    /** False until the user has reviewed this app's category, so suggestions are never mistaken for choices. */
    @ColumnInfo(name = "category_confirmed") val categoryConfirmed: Boolean = false,
    @ColumnInfo(name = "is_launcher_or_system_ui") val isLauncherOrSystemUi: Boolean = false,
    @ColumnInfo(name = "is_installed") val isInstalled: Boolean = true,
    @ColumnInfo(name = "installed_at_ms") val installedAtMs: Long? = null,
    @ColumnInfo(name = "uninstalled_at_ms") val uninstalledAtMs: Long? = null,
    @ColumnInfo(name = "daily_goal_ms") val dailyGoalMs: Long? = null,
    @ColumnInfo(name = "friction_override_json") val frictionOverrideJson: String? = null,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

/** Activity class names. A null class is stored as the empty string, so it interns to one canonical row. */
@Entity(tableName = "classes", indices = [Index(value = ["class_name"], unique = true)])
data class ClassEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "class_name") val className: String,
)

// ---------------------------------------------------------------------------------------------------------------------
// Immutable raw ledgers.
// ---------------------------------------------------------------------------------------------------------------------

/**
 * One `UsageEvents.Event`. Every key column is non-null so the unique index really deduplicates overlapping
 * re-queries; [seqInMs] separates genuinely repeated identical events within the same millisecond. Source order is
 * preserved by insertion order ([id]).
 */
@Entity(
    tableName = "raw_usage_events",
    foreignKeys = [
        ForeignKey(entity = PackageEntity::class, parentColumns = ["id"], childColumns = ["pkg_id"]),
        ForeignKey(entity = ClassEntity::class, parentColumns = ["id"], childColumns = ["cls_id"]),
    ],
    indices = [
        Index(value = ["timestamp_utc_ms", "event_type", "pkg_id", "cls_id", "seq_in_ms"], unique = true),
        Index(value = ["pkg_id"]),
        Index(value = ["cls_id"]),
    ],
)
data class RawUsageEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_utc_ms") val timestampUtcMs: Long,
    @ColumnInfo(name = "event_type") val eventType: Int,
    @ColumnInfo(name = "pkg_id") val pkgId: Long,
    @ColumnInfo(name = "cls_id") val clsId: Long,
    @ColumnInfo(name = "seq_in_ms", defaultValue = "0") val seqInMs: Int = 0,
)

/**
 * Notification lifecycle metadata. Never holds title, text, sender or extras; keys are stored only as SHA-256
 * hashes. Updates to the same live notification bump [updateCount] instead of adding rows.
 */
@Entity(
    tableName = "raw_notifications",
    foreignKeys = [ForeignKey(entity = PackageEntity::class, parentColumns = ["id"], childColumns = ["pkg_id"])],
    indices = [
        Index(value = ["sbn_key_hash"]),
        Index(value = ["pkg_id"]),
        Index(value = ["posted_at_ms"]),
        Index(value = ["removed_at_ms"]),
    ],
)
data class RawNotificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "sbn_key_hash") val sbnKeyHash: String,
    @ColumnInfo(name = "pkg_id") val pkgId: Long,
    @ColumnInfo(name = "channel_id") val channelId: String?,
    @ColumnInfo(name = "android_category") val androidCategory: String?,
    @ColumnInfo(name = "group_key_hash") val groupKeyHash: String?,
    @ColumnInfo(name = "posted_at_ms") val postedAtMs: Long,
    @ColumnInfo(name = "last_updated_at_ms") val lastUpdatedAtMs: Long,
    @ColumnInfo(name = "update_count") val updateCount: Int = 0,
    @ColumnInfo(name = "removed_at_ms") val removedAtMs: Long? = null,
    /** A [RemovalReason] name; `ACTIVE` while the notification is still showing. */
    @ColumnInfo(name = "removal_reason") val removalReason: String = RemovalReason.ACTIVE.name,
    val importance: Int,
    @ColumnInfo(name = "matched_interruption_filter") val matchedInterruptionFilter: Boolean,
    @ColumnInfo(name = "is_ongoing") val isOngoing: Boolean,
    @ColumnInfo(name = "is_foreground_service") val isForegroundService: Boolean,
    @ColumnInfo(name = "is_group_summary") val isGroupSummary: Boolean,
    @ColumnInfo(name = "is_ambient") val isAmbient: Boolean,
    @ColumnInfo(name = "is_conversation") val isConversation: Boolean,
    @ColumnInfo(name = "is_only_alert_once") val isOnlyAlertOnce: Boolean,
    @ColumnInfo(name = "dnd_or_silent_at_post") val dndOrSilentAtPost: Boolean,
)

@Entity(tableName = "tz_history", indices = [Index(value = ["effective_from_utc_ms"])])
data class TzHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "effective_from_utc_ms") val effectiveFromUtcMs: Long,
    @ColumnInfo(name = "zone_id") val zoneId: String,
    @ColumnInfo(name = "is_assumed") val isAssumed: Boolean,
)

/**
 * When each collector was actually running. `end_ms` is null while an interval is open; [lastSeenMs] is a heartbeat
 * so an interval left open by a killed process can be closed at the last moment it was known to be alive.
 */
@Entity(tableName = "service_coverage_intervals", indices = [Index(value = ["service_kind", "start_ms"])])
data class CoverageIntervalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** A [ServiceKind] name. */
    @ColumnInfo(name = "service_kind") val serviceKind: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long?,
    /** A [CoverageStatus] name. */
    val status: String,
    @ColumnInfo(name = "last_seen_ms") val lastSeenMs: Long,
)

/** Collector bookkeeping (harvest cursor and friends), kept in the database so it commits with the data it tracks. */
@Entity(tableName = "ingestion_state")
data class IngestionStateEntity(
    @PrimaryKey val key: String,
    @ColumnInfo(name = "long_value") val longValue: Long,
)

// ---------------------------------------------------------------------------------------------------------------------
// User context, goals, schedules and settings history.
// ---------------------------------------------------------------------------------------------------------------------

@Entity(tableName = "settings_history", indices = [Index(value = ["timestamp_utc_ms"])])
data class SettingsHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_utc_ms") val timestampUtcMs: Long,
    @ColumnInfo(name = "setting_key") val settingKey: String,
    @ColumnInfo(name = "value_json") val valueJson: String,
)

@Entity(tableName = "daily_notes")
data class DailyNoteEntity(
    @PrimaryKey @ColumnInfo(name = "logical_date") val logicalDate: String,
    @ColumnInfo(name = "note_text") val noteText: String,
    @ColumnInfo(name = "updated_at_ms") val updatedAtMs: Long,
)

@Entity(tableName = "friction_schedules")
data class FrictionScheduleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val enabled: Boolean,
    @ColumnInfo(name = "days_of_week_mask") val daysOfWeekMask: Int,
    @ColumnInfo(name = "start_minute_of_day") val startMinuteOfDay: Int,
    @ColumnInfo(name = "end_minute_of_day") val endMinuteOfDay: Int,
    @ColumnInfo(name = "escalation_boost") val escalationBoost: Int,
    @ColumnInfo(name = "require_timer_prompt") val requireTimerPrompt: Boolean,
    @ColumnInfo(name = "nudge_interval_min") val nudgeIntervalMin: Int?,
)

@Entity(
    tableName = "friction_events",
    foreignKeys = [
        ForeignKey(entity = PackageEntity::class, parentColumns = ["id"], childColumns = ["pkg_id"]),
        ForeignKey(
            entity = FrictionScheduleEntity::class,
            parentColumns = ["id"],
            childColumns = ["schedule_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["timestamp_utc_ms"]),
        Index(value = ["logical_date"]),
        Index(value = ["pkg_id"]),
        Index(value = ["schedule_id"]),
    ],
)
data class FrictionEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "timestamp_utc_ms") val timestampUtcMs: Long,
    @ColumnInfo(name = "logical_date") val logicalDate: String,
    @ColumnInfo(name = "pkg_id") val pkgId: Long,
    @ColumnInfo(name = "event_kind") val eventKind: String,
    @ColumnInfo(name = "schedule_id") val scheduleId: Long?,
    @ColumnInfo(name = "escalation_tier") val escalationTier: Int,
    @ColumnInfo(name = "pause_duration_sec") val pauseDurationSec: Int,
    @ColumnInfo(name = "post_countdown_reaction_ms") val postCountdownReactionMs: Long?,
    @ColumnInfo(name = "intention_tag") val intentionTag: String?,
    @ColumnInfo(name = "selected_timer_min") val selectedTimerMin: Int?,
    @ColumnInfo(name = "redirect_pkg_id") val redirectPkgId: Long?,
    val outcome: String,
)

// ---------------------------------------------------------------------------------------------------------------------
// Derived tables: tagged with algorithm_version and recomputable from the raw ledgers.
// ---------------------------------------------------------------------------------------------------------------------

@Entity(
    tableName = "sessions",
    foreignKeys = [
        ForeignKey(
            entity = RawNotificationEntity::class,
            parentColumns = ["id"],
            childColumns = ["trigger_notification_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["episode_key"], unique = true),
        Index(value = ["logical_date"]),
        Index(value = ["start_ms"]),
        Index(value = ["trigger_notification_id"]),
    ],
)
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "episode_key") val episodeKey: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: Int,
    @ColumnInfo(name = "logical_date") val logicalDate: String,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "active_duration_ms") val activeDurationMs: Long,
    @ColumnInfo(name = "physical_pickup_count") val physicalPickupCount: Int,
    @ColumnInfo(name = "is_glance_only") val isGlanceOnly: Boolean,
    @ColumnInfo(name = "trigger_evidence") val triggerEvidence: String,
    @ColumnInfo(name = "trigger_pkg_id") val triggerPkgId: Long?,
    @ColumnInfo(name = "trigger_notification_id") val triggerNotificationId: Long?,
    @ColumnInfo(name = "entry_pkg_id") val entryPkgId: Long?,
    @ColumnInfo(name = "total_distracting_ms") val totalDistractingMs: Long,
    @ColumnInfo(name = "total_neutral_ms") val totalNeutralMs: Long,
    @ColumnInfo(name = "total_utility_ms") val totalUtilityMs: Long,
    @ColumnInfo(name = "total_excluded_ms") val totalExcludedMs: Long,
    @ColumnInfo(name = "blocked_attempt_count") val blockedAttemptCount: Int,
    @ColumnInfo(name = "distinct_app_count") val distinctAppCount: Int,
    @ColumnInfo(name = "distinct_distracting_count") val distinctDistractingCount: Int,
    val archetype: String,
    @ColumnInfo(name = "is_rabbit_hole") val isRabbitHole: Boolean,
    @ColumnInfo(name = "rabbit_hole_reasons") val rabbitHoleReasons: String?,
    @ColumnInfo(name = "worth_it_rating") val worthItRating: Int?,
    @ColumnInfo(name = "context_flags") val contextFlags: String?,
)

@Entity(
    tableName = "session_segments",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(entity = PackageEntity::class, parentColumns = ["id"], childColumns = ["pkg_id"]),
    ],
    indices = [Index(value = ["session_id"]), Index(value = ["pkg_id"])],
)
data class SessionSegmentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: Long,
    @ColumnInfo(name = "seq_index") val seqIndex: Int,
    @ColumnInfo(name = "pkg_id") val pkgId: Long,
    @ColumnInfo(name = "start_ms") val startMs: Long,
    @ColumnInfo(name = "end_ms") val endMs: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "is_blocked_attempt") val isBlockedAttempt: Boolean,
    @ColumnInfo(name = "segment_trigger_evidence") val segmentTriggerEvidence: String?,
    @ColumnInfo(name = "segment_trigger_pkg_id") val segmentTriggerPkgId: Long?,
)

@Entity(tableName = "daily_rollups")
data class DailyRollupEntity(
    @PrimaryKey @ColumnInfo(name = "logical_date") val logicalDate: String,
    @ColumnInfo(name = "algorithm_version") val algorithmVersion: Int,
    @ColumnInfo(name = "coverage_status") val coverageStatus: String,
    @ColumnInfo(name = "notif_coverage_ratio") val notifCoverageRatio: Double,
    @ColumnInfo(name = "friction_coverage_ratio") val frictionCoverageRatio: Double,
    @ColumnInfo(name = "total_screen_ms") val totalScreenMs: Long,
    @ColumnInfo(name = "active_usage_ms") val activeUsageMs: Long,
    @ColumnInfo(name = "distracting_ms") val distractingMs: Long,
    @ColumnInfo(name = "neutral_ms") val neutralMs: Long,
    @ColumnInfo(name = "utility_ms") val utilityMs: Long,
    @ColumnInfo(name = "excluded_ms") val excludedMs: Long,
    @ColumnInfo(name = "self_app_ms") val selfAppMs: Long,
    @ColumnInfo(name = "episode_count") val episodeCount: Int,
    @ColumnInfo(name = "physical_pickup_count") val physicalPickupCount: Int,
    @ColumnInfo(name = "glance_count") val glanceCount: Int,
    @ColumnInfo(name = "micro_check_count") val microCheckCount: Int,
    @ColumnInfo(name = "rabbit_hole_count") val rabbitHoleCount: Int,
    @ColumnInfo(name = "blocked_attempt_count") val blockedAttemptCount: Int,
    @ColumnInfo(name = "notification_count") val notificationCount: Int,
    @ColumnInfo(name = "longest_focus_gap_ms") val longestFocusGapMs: Long,
    @ColumnInfo(name = "overnight_gap_ms") val overnightGapMs: Long?,
    @ColumnInfo(name = "first_pickup_ms") val firstPickupMs: Long?,
    @ColumnInfo(name = "last_activity_ms") val lastActivityMs: Long?,
    @ColumnInfo(name = "budget_target_ms") val budgetTargetMs: Long?,
    @ColumnInfo(name = "baseline_median_ms") val baselineMedianMs: Long?,
    @ColumnInfo(name = "day_status") val dayStatus: String,
    @ColumnInfo(name = "spike_summary_json") val spikeSummaryJson: String?,
)

@Entity(
    tableName = "daily_app_rollups",
    primaryKeys = ["logical_date", "pkg_id"],
    foreignKeys = [ForeignKey(entity = PackageEntity::class, parentColumns = ["id"], childColumns = ["pkg_id"])],
    indices = [Index(value = ["pkg_id"])],
)
data class DailyAppRollupEntity(
    @ColumnInfo(name = "logical_date") val logicalDate: String,
    @ColumnInfo(name = "pkg_id") val pkgId: Long,
    @ColumnInfo(name = "total_ms") val totalMs: Long,
    @ColumnInfo(name = "open_count") val openCount: Int,
    @ColumnInfo(name = "blocked_attempt_count") val blockedAttemptCount: Int,
    @ColumnInfo(name = "session_count") val sessionCount: Int,
    @ColumnInfo(name = "rabbit_hole_contribution_ms") val rabbitHoleContributionMs: Long,
)

@Entity(
    tableName = "hourly_app_usage",
    primaryKeys = ["logical_date", "hour_slot", "pkg_id"],
    foreignKeys = [ForeignKey(entity = PackageEntity::class, parentColumns = ["id"], childColumns = ["pkg_id"])],
    indices = [Index(value = ["pkg_id"])],
)
data class HourlyAppUsageEntity(
    @ColumnInfo(name = "logical_date") val logicalDate: String,
    /** 0..23 normally; 24 exists only on 25-hour fall-back days. */
    @ColumnInfo(name = "hour_slot") val hourSlot: Int,
    @ColumnInfo(name = "pkg_id") val pkgId: Long,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "open_count") val openCount: Int,
)
