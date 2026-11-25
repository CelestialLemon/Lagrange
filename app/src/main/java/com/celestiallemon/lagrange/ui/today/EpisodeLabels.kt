package com.celestiallemon.lagrange.ui.today

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CenterFocusStrong
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.Handyman
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.Shuffle
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.WifiTetheringError
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.celestiallemon.lagrange.data.db.Archetype
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence

fun triggerOf(name: String?): TriggerEvidence? = name?.let { n -> TriggerEvidence.entries.firstOrNull { it.name == n } }

fun archetypeOf(name: String?): Archetype? = name?.let { n -> Archetype.entries.firstOrNull { it.name == n } }

fun dayStatusOf(name: String?): DayStatus? = name?.let { n -> DayStatus.entries.firstOrNull { it.name == n } }

/** Short badge text. Wording is deliberately modest: most evidence is circumstantial. */
val TriggerEvidence.badge: String
    get() = when (this) {
        TriggerEvidence.OBSERVED_CLICK -> "Notification tap"
        TriggerEvidence.LIKELY_APP_CANCEL_CLICK -> "Likely notification"
        TriggerEvidence.RECENT_POSSIBLE_ALERT -> "After an alert"
        TriggerEvidence.PENDING_NOTIFICATION -> "Unread waiting"
        TriggerEvidence.NO_OBSERVED_TRIGGER -> "Self-started"
        TriggerEvidence.PARTIAL_COVERAGE -> "Partial data"
        TriggerEvidence.BACKFILL -> "Backfilled"
    }

/** One-sentence explanation for the session sheet. */
val TriggerEvidence.explanation: String
    get() = when (this) {
        TriggerEvidence.OBSERVED_CLICK -> "A notification from this app was opened within seconds of the pickup."
        TriggerEvidence.LIKELY_APP_CANCEL_CLICK -> "A notification from this app vanished right as it opened, which usually means it was tapped."
        TriggerEvidence.RECENT_POSSIBLE_ALERT -> "An alerting notification from this app arrived in the minute before. It may have pulled you in."
        TriggerEvidence.PENDING_NOTIFICATION -> "An older notification from this app was still waiting when you opened it."
        TriggerEvidence.NO_OBSERVED_TRIGGER -> "Nothing in the notification log points to a trigger. This looks self-initiated."
        TriggerEvidence.PARTIAL_COVERAGE -> "The notification listener was not recording here, so the trigger is unknown."
        TriggerEvidence.BACKFILL -> "This is from the history imported at setup; notification data does not exist for it."
    }

val TriggerEvidence.icon: ImageVector
    get() = when (this) {
        TriggerEvidence.OBSERVED_CLICK -> Icons.Outlined.TouchApp
        TriggerEvidence.LIKELY_APP_CANCEL_CLICK -> Icons.Outlined.TouchApp
        TriggerEvidence.RECENT_POSSIBLE_ALERT -> Icons.Outlined.NotificationsActive
        TriggerEvidence.PENDING_NOTIFICATION -> Icons.Outlined.NotificationsNone
        TriggerEvidence.NO_OBSERVED_TRIGGER -> Icons.Outlined.Bolt
        TriggerEvidence.PARTIAL_COVERAGE -> Icons.Outlined.WifiTetheringError
        TriggerEvidence.BACKFILL -> Icons.Outlined.History
    }

val TriggerEvidence.isNotificationLed: Boolean
    get() = this == TriggerEvidence.OBSERVED_CLICK || this == TriggerEvidence.LIKELY_APP_CANCEL_CLICK ||
        this == TriggerEvidence.RECENT_POSSIBLE_ALERT || this == TriggerEvidence.PENDING_NOTIFICATION

val Archetype.label: String
    get() = when (this) {
        Archetype.GLANCE -> "Glance"
        Archetype.MICRO_CHECK -> "Micro-check"
        Archetype.UTILITY -> "Utility"
        Archetype.FOCUSED -> "Focused"
        Archetype.RABBIT_HOLE -> "Rabbit hole"
        Archetype.MIXED -> "Mixed"
        Archetype.BLOCKED_ATTEMPT -> "Blocked attempt"
    }

val Archetype.icon: ImageVector
    get() = when (this) {
        Archetype.GLANCE -> Icons.Outlined.Visibility
        Archetype.MICRO_CHECK -> Icons.Outlined.Bolt
        Archetype.UTILITY -> Icons.Outlined.Handyman
        Archetype.FOCUSED -> Icons.Outlined.CenterFocusStrong
        Archetype.RABBIT_HOLE -> Icons.Outlined.Cyclone
        Archetype.MIXED -> Icons.Outlined.Shuffle
        Archetype.BLOCKED_ATTEMPT -> Icons.Outlined.Block
    }

val RabbitHoleReason.label: String
    get() = when (this) {
        RabbitHoleReason.LONG_BINGE -> "20+ minutes in distracting apps"
        RabbitHoleReason.MULTI_APP_SPIRAL -> "Spiralled through several distracting apps"
        RabbitHoleReason.HIJACKED_PICKUP -> "Picked up for something useful, got pulled away"
    }

fun rabbitReasonsOf(csv: String?): List<RabbitHoleReason> =
    csv?.split(',')?.mapNotNull { n -> RabbitHoleReason.entries.firstOrNull { it.name == n.trim() } }.orEmpty()

val unknownIcon: ImageVector get() = Icons.Outlined.HelpOutline

val DayStatus.chip: String
    get() = when (this) {
        DayStatus.NORMAL -> "Typical"
        DayStatus.SPIKE -> "Spike"
        DayStatus.GOOD_DAY -> "Good day"
        DayStatus.INSUFFICIENT_BASELINE -> "Building baseline"
        DayStatus.UNCERTAIN_COVERAGE -> "Coverage gap · not compared"
    }
