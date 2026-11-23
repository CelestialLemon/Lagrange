package com.celestiallemon.lagrange.domain.friction

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.IntentionTag
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Per-app friction overrides stored as JSON in `packages.friction_override_json`.
 *
 * A `null` [enabled] follows the category default (on for `DISTRACTING`, opt-in for `NEUTRAL`, never for
 * `UTILITY`/`EXCLUDED`/`SELF_APP`). Setting `enabled = false` on distracting apps lets the user roll out
 * friction on just 1–2 chosen apps at first.
 */
@Serializable
data class AppFrictionOverride(
    val enabled: Boolean? = null,
    val fixedPauseSec: Int? = null,
    val requireTimerPrompt: Boolean? = null,
) {
    val isDefault: Boolean
        get() = enabled == null && fixedPauseSec == null && requireTimerPrompt == null

    fun toJsonOrNull(): String? = if (isDefault) null else json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

        fun parse(raw: String?): AppFrictionOverride? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), raw) }
                .getOrNull()
                ?.takeUnless { it.isDefault }
        }
    }
}

/** Pure-Kotlin projection of a `friction_schedules` row. */
data class FrictionSchedule(
    val id: Long,
    val name: String,
    val enabled: Boolean,
    /** Bitmask Mon=1<<0 .. Sun=1<<6 (`1 shl (dayOfWeek.value - 1)`). */
    val daysOfWeekMask: Int,
    /** 0..1439. */
    val startMinuteOfDay: Int,
    /** 0..1439; values < [startMinuteOfDay] wrap past midnight into the next morning. */
    val endMinuteOfDay: Int,
    val escalationBoost: Int,
    val requireTimerPrompt: Boolean,
    val nudgeIntervalMin: Int?,
) {
    val wrapsMidnight: Boolean get() = startMinuteOfDay > endMinuteOfDay

    companion object {
        const val MASK_MON = 1 shl 0
        const val MASK_TUE = 1 shl 1
        const val MASK_WED = 1 shl 2
        const val MASK_THU = 1 shl 3
        const val MASK_FRI = 1 shl 4
        const val MASK_SAT = 1 shl 5
        const val MASK_SUN = 1 shl 6
        const val MASK_WEEKDAYS = MASK_MON or MASK_TUE or MASK_WED or MASK_THU or MASK_FRI
        const val MASK_EVERY_DAY = MASK_WEEKDAYS or MASK_SAT or MASK_SUN
    }
}

/** Combined effect of all currently active contextual schedules at a given instant. */
data class ActiveScheduleEffect(
    val primaryScheduleId: Long,
    val primaryScheduleName: String,
    val escalationBoost: Int,
    val requireTimerPrompt: Boolean,
    val nudgeIntervalMin: Int?,
)

/** Friction settings snapshot read from user preferences. */
data class FrictionSettings(
    val frictionEnabled: Boolean = false,
    val escalationEnabled: Boolean = true,
    val tier0PauseSec: Int = 3,
    val tier1PauseSec: Int = 5,
    val tier2PauseSec: Int = 8,
    val tier3PauseSec: Int = 12,
    /** Switch-back grace window in seconds (monotonic). 0 disables grace window. */
    val graceWindowSec: Int = 120,
    /** Continuous distracting time interval for non-blocking nudges in minutes. 0 disables nudges. */
    val nudgeIntervalMin: Int = 15,
    /** Quick soft session timer presets offered on pause cards, in minutes. */
    val timerPresetsMin: List<Int> = listOf(2, 5, 10, 15),
    /** Optional cap on launch-pause prompts shown per logical day; null = unlimited. */
    val dailyPromptCap: Int? = null,
    /** Optional healthy redirect app package (e.g., Kindle, Notes). */
    val redirectPackageName: String? = null,
    val rolloverHour: Int = 4,
    val dailyBudgetMs: Long = 60L * 60 * 1000,
) {
    fun pauseSecondsForTier(tier: Int): Int = when (tier.coerceIn(0, 3)) {
        0 -> tier0PauseSec
        1 -> tier1PauseSec
        2 -> tier2PauseSec
        else -> tier3PauseSec
    }.coerceIn(MIN_PAUSE_SEC, MAX_PAUSE_SEC)

    companion object {
        const val MIN_PAUSE_SEC = 1
        const val MAX_PAUSE_SEC = 60
        const val SNOOZE_DURATION_MS = 60L * 60 * 1000
        const val FOCUS_BOOST_DURATION_MS = 60L * 60 * 1000
        const val MICRO_LOCK_MERGE_MS = 15_000L
        val DEFAULT_TIMER_PRESETS_MIN = listOf(2, 5, 10, 15)
    }
}

/** Per-app policy resolved from `packages`. */
data class FrictionAppPolicy(
    val pkgId: Long,
    val packageName: String,
    val displayName: String,
    val category: AppCategory,
    val override: AppFrictionOverride? = null,
    val isSystemSurface: Boolean = false,
) {
    /**
     * Whether this app can trigger a launch pause.
     * `UTILITY`, `EXCLUDED`, `SELF_APP`, and system surfaces are never friction-gated (spec §5.1).
     */
    val isFrictionEligible: Boolean
        get() = when {
            isSystemSurface -> false
            category == AppCategory.DISTRACTING -> override?.enabled ?: true
            category == AppCategory.NEUTRAL -> override?.enabled ?: false
            else -> false
        }

    /** Cross-app soft timers and time-blindness nudges count foreground time across all `DISTRACTING` apps. */
    val countsAsDistracting: Boolean
        get() = !isSystemSurface && category == AppCategory.DISTRACTING
}

/** Today's live usage context for computing escalation and displaying honest stats on overlays. */
data class TodayUsageSnapshot(
    val logicalDate: String,
    /** Real opens of this specific app today (excluding blocked attempts). */
    val appRealOpensToday: Int,
    /** Total real distracting time across all distracting apps today. */
    val distractingMsToday: Long,
    /** Total launch-pause prompts shown across all apps today (for [FrictionSettings.dailyPromptCap]). */
    val promptsShownToday: Int,
    val budgetMs: Long,
)

/** Computed parameters for a launch-pause overlay. */
data class LaunchPausePlan(
    val pkgId: Long,
    val packageName: String,
    val displayName: String,
    val logicalDate: String,
    val tier: Int,
    val pauseDurationSec: Int,
    val nthOpenToday: Int,
    val distractingMsToday: Long,
    val budgetMs: Long,
    val showTimerChips: Boolean,
    val encourageTimerSelection: Boolean,
    val showIntentionChips: Boolean,
    val showBudgetComparison: Boolean,
    val timerPresetsMin: List<Int>,
    val scheduleId: Long?,
    val scheduleName: String?,
    val focusBoostActive: Boolean,
    val redirectPackageName: String?,
    val redirectDisplayName: String?,
    val redirectPkgId: Long?,
)

enum class SuppressionReason {
    FRICTION_DISABLED,
    APP_NOT_ELIGIBLE,
    SNOOZED,
    GRACE_WINDOW,
    DAILY_CAP_REACHED,
}

sealed interface LaunchDecision {
    data class Suppress(val reason: SuppressionReason) : LaunchDecision
    data class ShowPause(val plan: LaunchPausePlan) : LaunchDecision
}

/** Active overlay state managed by [FrictionEngine] and rendered by the overlay controller. */
sealed interface ActiveOverlay {
    val pkgId: Long
    val packageName: String
    val logicalDate: String

    data class LaunchPause(
        val plan: LaunchPausePlan,
        val shownAtWallMs: Long,
        val shownAtElapsedMs: Long,
        val selectedIntention: IntentionTag? = null,
        val selectedTimerMin: Int? = null,
    ) : ActiveOverlay {
        override val pkgId: Long get() = plan.pkgId
        override val packageName: String get() = plan.packageName
        override val logicalDate: String get() = plan.logicalDate
        val countdownEndElapsedMs: Long get() = shownAtElapsedMs + plan.pauseDurationSec * 1_000L
    }

    data class TimerExpired(
        override val pkgId: Long,
        override val packageName: String,
        val displayName: String,
        override val logicalDate: String,
        val timerMinutes: Int,
        val distractingMsToday: Long,
        val budgetMs: Long,
        val shownAtWallMs: Long,
        val shownAtElapsedMs: Long,
        val scheduleId: Long?,
        val escalationTier: Int,
    ) : ActiveOverlay

    data class TimeBlindnessNudge(
        override val pkgId: Long,
        override val packageName: String,
        override val logicalDate: String,
        val continuousDistractingMs: Long,
        val milestoneMinutes: Int,
        val appsHopped: List<String>,
        val shownAtWallMs: Long,
        val shownAtElapsedMs: Long,
        val scheduleId: Long?,
    ) : ActiveOverlay
}

/** A friction event ready to be written to `friction_events`. */
data class FrictionEventRecord(
    val timestampUtcMs: Long,
    val logicalDate: String,
    val pkgId: Long,
    val eventKind: String,
    val scheduleId: Long?,
    val escalationTier: Int,
    val pauseDurationSec: Int,
    val postCountdownReactionMs: Long?,
    val intentionTag: String?,
    val selectedTimerMin: Int?,
    val redirectPkgId: Long?,
    val outcome: String,
)

/** Side effects emitted by [FrictionEngine] for the Android host service to perform. */
sealed interface FrictionEffect {
    data class ShowOverlay(val overlay: ActiveOverlay) : FrictionEffect
    data object DismissOverlay : FrictionEffect
    data object PerformGoHome : FrictionEffect
    data class LaunchRedirect(val packageName: String) : FrictionEffect
    data class LogEvent(val record: FrictionEventRecord) : FrictionEffect
    data class PersistState(val snapshot: FrictionRuntimeSnapshot) : FrictionEffect
}

/**
 * Restart-safe monotonic runtime state persisted so an AccessibilityService process restart keeps active
 * timers, grace windows, snooze, and focus boost intact while discarding stale pre-reboot state.
 */
@Serializable
data class FrictionRuntimeSnapshot(
    val bootCount: Int,
    val savedAtElapsedMs: Long,
    val activeTimerRemainingMs: Long? = null,
    val activeTimerInitialMin: Int? = null,
    val snoozeUntilElapsedMs: Long? = null,
    val focusBoostUntilElapsedMs: Long? = null,
    val graceUntilByPackage: Map<String, Long> = emptyMap(),
    val sessionDistractingMs: Long = 0L,
    val sessionDistractingApps: List<String> = emptyList(),
    val sessionLastNudgeMilestone: Int = 0,
    val screenOffAtElapsedMs: Long? = null,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(raw: String?): FrictionRuntimeSnapshot? {
            if (raw.isNullOrBlank()) return null
            return runCatching { json.decodeFromString(serializer(), raw) }.getOrNull()
        }
    }
}

/** Observable live status exposed to the Friction tab and future Quick Settings tile. */
data class FrictionLiveStatus(
    val snoozeUntilElapsedMs: Long? = null,
    val focusBoostUntilElapsedMs: Long? = null,
    val activeTimerRemainingMs: Long? = null,
    val activeTimerInitialMin: Int? = null,
    val sessionDistractingMs: Long = 0L,
    val currentForegroundPackage: String? = null,
) {
    fun snoozeRemainingMs(nowElapsedMs: Long): Long =
        snoozeUntilElapsedMs?.let { (it - nowElapsedMs).coerceAtLeast(0L) } ?: 0L

    fun focusBoostRemainingMs(nowElapsedMs: Long): Long =
        focusBoostUntilElapsedMs?.let { (it - nowElapsedMs).coerceAtLeast(0L) } ?: 0L

    fun isSnoozed(nowElapsedMs: Long): Boolean = snoozeRemainingMs(nowElapsedMs) > 0L

    fun isFocusBoostActive(nowElapsedMs: Long): Boolean = focusBoostRemainingMs(nowElapsedMs) > 0L
}
