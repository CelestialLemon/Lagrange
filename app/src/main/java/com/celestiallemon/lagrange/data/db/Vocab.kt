package com.celestiallemon.lagrange.data.db

/**
 * Text vocabularies stored in the database. Values are persisted by [name], so constants may be added but never
 * renamed or removed without a migration.
 */
enum class AppCategory { DISTRACTING, NEUTRAL, UTILITY, EXCLUDED, SELF_APP }

enum class ServiceKind { USAGE_HARVEST, NOTIFICATION_LISTENER, ACCESSIBILITY }

enum class CoverageStatus { ACTIVE, GAP, BACKFILL }

enum class RemovalReason {
    ACTIVE, CLICK, APP_CANCEL, CANCEL, CANCEL_ALL, BUNDLE_DISMISSED, TIMEOUT, DISCONNECT_RECONCILED, OTHER;

    companion object {
        /** `NotificationListenerService.REASON_BUNDLE_DISMISSED`, API 36.1+; kept as a literal so older SDKs map it. */
        const val ANDROID_REASON_BUNDLE_DISMISSED = 24

        fun fromAndroid(reason: Int): RemovalReason = when (reason) {
            1 -> CLICK // REASON_CLICK
            2 -> CANCEL // REASON_CANCEL
            3 -> CANCEL_ALL // REASON_CANCEL_ALL
            8, 9 -> APP_CANCEL // REASON_APP_CANCEL, REASON_APP_CANCEL_ALL
            19 -> TIMEOUT // REASON_TIMEOUT
            ANDROID_REASON_BUNDLE_DISMISSED -> BUNDLE_DISMISSED
            else -> OTHER
        }
    }
}

/** Raw `UsageEvents.Event` type constants referenced by the collector. */
object UsageEventTypes {
    const val ACTIVITY_RESUMED = 1
    const val ACTIVITY_PAUSED = 2
    const val NOTIFICATION_INTERRUPTION = 12
    const val SCREEN_INTERACTIVE = 15
    const val SCREEN_NON_INTERACTIVE = 16
    const val KEYGUARD_SHOWN = 17
    const val KEYGUARD_HIDDEN = 18
    const val ACTIVITY_STOPPED = 23
    const val DEVICE_SHUTDOWN = 26
    const val DEVICE_STARTUP = 27
}

/** How an episode (or a mid-session app switch) was most plausibly started. Persisted in `sessions.trigger_evidence`. */
enum class TriggerEvidence {
    OBSERVED_CLICK, LIKELY_APP_CANCEL_CLICK, RECENT_POSSIBLE_ALERT, PENDING_NOTIFICATION, NO_OBSERVED_TRIGGER, PARTIAL_COVERAGE, BACKFILL,
}

/**
 * Episode shape. [BLOCKED_ATTEMPT] is an episode whose only app opens were closed at a launch pause: it is neither a
 * micro-check nor real use, so it never inflates those counts.
 */
enum class Archetype { GLANCE, MICRO_CHECK, UTILITY, FOCUSED, RABBIT_HOLE, MIXED, BLOCKED_ATTEMPT }

enum class RabbitHoleReason { LONG_BINGE, MULTI_APP_SPIRAL, HIJACKED_PICKUP }

/** `daily_rollups.coverage_status`. */
enum class DayCoverage { COMPLETE, PARTIAL_HARVEST, NO_NOTIF_COVERAGE, BACKFILL }

/** `daily_rollups.day_status`. */
enum class DayStatus { NORMAL, SPIKE, GOOD_DAY, INSUFFICIENT_BASELINE, UNCERTAIN_COVERAGE }

/** `friction_events.event_kind`, `outcome`, and `intention_tag` values (spec §3.3). */
object FrictionVocab {
    const val LAUNCH_PAUSE = "LAUNCH_PAUSE"
    const val TIMER_EXPIRED = "TIMER_EXPIRED"
    const val TIME_BLINDNESS_NUDGE = "TIME_BLINDNESS_NUDGE"
    const val SNOOZE_1H = "SNOOZE_1H"

    const val OUTCOME_CLOSED_GO_HOME = "CLOSED_GO_HOME"
    const val OUTCOME_CLOSED_SYSTEM_NAV = "CLOSED_SYSTEM_NAV"
    const val OUTCOME_REDIRECTED = "REDIRECTED"
    const val OUTCOME_CONTINUED = "CONTINUED"
    const val OUTCOME_EXTENDED_5M = "EXTENDED_5M"
    const val OUTCOME_DISMISSED_SESSION = "DISMISSED_SESSION"
    const val OUTCOME_NUDGE_DISMISSED = "NUDGE_DISMISSED"
    const val OUTCOME_NUDGE_IGNORED = "NUDGE_IGNORED"
    const val OUTCOME_SNOOZE_ACTIVATED = "SNOOZE_ACTIVATED"

    /** Outcomes where the user left during the pause: the open never became real use. */
    val BLOCKING_OUTCOMES = setOf(OUTCOME_CLOSED_GO_HOME, OUTCOME_CLOSED_SYSTEM_NAV, OUTCOME_REDIRECTED)
}

enum class IntentionTag(val label: String) {
    REPLY("Reply"),
    LOOKUP("Look something up"),
    UNWIND("Unwind"),
    BORED("Bored"),
    CUSTOM("Custom"),
}
