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
}
