package com.celestiallemon.lagrange.ui.system

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccessibilityNew
import androidx.compose.material.icons.outlined.BatteryChargingFull
import androidx.compose.material.icons.outlined.BedtimeOff
import androidx.compose.material.icons.outlined.DataUsage
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.system.Check
import com.celestiallemon.lagrange.system.PermissionHealth
import com.celestiallemon.lagrange.system.PermissionHealthChecker
import androidx.compose.foundation.background
import androidx.compose.material3.FilledTonalButton
import com.celestiallemon.lagrange.ui.components.LeadingIcon
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.SwitchRow
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone

/** Tries each intent in turn (OEM settings apps differ), ending at App info, which always exists. */
private fun Context.launch(vararg intents: Intent) {
    for (intent in intents.toList() + PermissionHealthChecker.appInfoIntent(this)) {
        try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // next
        } catch (_: SecurityException) {
            // next
        }
    }
}

/** Permission Health rows. [compact] shows just the two collector permissions (onboarding). */
@Composable
fun PermissionList(health: PermissionHealth, compact: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val status = PermissionTones
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    RowGroup {
        PermissionRow(
            icon = Icons.Outlined.DataUsage,
            title = "Usage Access",
            body = "Reads app, screen and unlock events so nothing is lost when Android purges its history.",
            state = if (health.usageAccess) "Granted" to status.good else "Required" to status.bad,
            action = if (health.usageAccess) null else "Grant" to { context.launch(PermissionHealthChecker.usageAccessIntent(context), PermissionHealthChecker.usageAccessListIntent()) },
        )
        PermissionRow(
            icon = Icons.Outlined.NotificationsActive,
            title = "Notification Access",
            body = "Records notification metadata only (app, channel, timing). Never titles, text or senders.",
            state = if (health.notificationAccess) "Granted" to status.good else "Recommended" to status.warn,
            action = if (health.notificationAccess) null else "Grant" to { context.launch(PermissionHealthChecker.notificationAccessIntent(context), PermissionHealthChecker.notificationAccessListIntent()) },
        )
        RestrictedSettingsHint { context.launch(PermissionHealthChecker.appInfoIntent(context)) }
        if (compact) return@RowGroup
        PermissionRow(
            icon = Icons.Outlined.AccessibilityNew,
            title = "Accessibility (gentle friction)",
            body = when {
                health.advancedProtection == Check.YES && !health.accessibilityEnabled ->
                    "Blocked while Android Advanced Protection is on. Analytics still work without it."
                health.accessibilityConnected ->
                    "Connected. Observes foreground app switches only (never screen content) for calm launch pauses and timers."
                health.accessibilityEnabled ->
                    "Enabled in Android Settings, waiting for the system to bind the service."
                else ->
                    "Needed for real-time launch pauses, soft session timers and nudges. Analytics work without it."
            },
            state = when {
                health.accessibilityConnected -> "Connected" to status.good
                health.accessibilityEnabled -> "Enabled" to status.warn
                else -> "Off" to status.neutral
            },
            action = if (health.accessibilityConnected) null else "Open settings" to {
                context.launch(PermissionHealthChecker.accessibilityIntent())
            },
        )
        PermissionRow(
            icon = Icons.Outlined.NotificationsNone,
            title = "Notifications",
            body = "For digests and coverage-gap alerts.",
            state = if (health.postNotifications) "Allowed" to status.good else "Off" to status.neutral,
            action = if (health.postNotifications) null else "Allow" to { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
        )
        PermissionRow(
            icon = Icons.Outlined.BedtimeOff,
            title = "App hibernation",
            body = "Stops Android from revoking access if you don't open Lagrange for a few months.",
            state = when (health.hibernationExempt) {
                Check.YES -> "Exempt" to status.good
                Check.NO -> "May hibernate" to status.warn
                Check.UNKNOWN -> "Unknown" to status.neutral
            },
            action = if (health.hibernationExempt == Check.YES) null else "Fix" to { context.launch(PermissionHealthChecker.hibernationIntent(context)) },
        )
        PermissionRow(
            icon = Icons.Outlined.GppGood,
            title = "Advanced Protection",
            body = when (health.advancedProtection) {
                Check.YES -> "On. Android restricts accessibility access to verified accessibility tools, so gentle friction may be unavailable. Analytics are unaffected."
                Check.NO -> "Off. Nothing restricts Lagrange's services."
                Check.UNKNOWN -> "Android didn't say whether Advanced Protection is on."
            },
            state = when (health.advancedProtection) {
                Check.YES -> "On" to status.warn
                Check.NO -> "Off" to status.good
                Check.UNKNOWN -> "Unknown" to status.neutral
            },
            action = null,
        )
        BatteryRow(health.batteryUnrestricted, status.good, status.neutral) { want ->
            context.launch(
                if (want) PermissionHealthChecker.requestBatteryExemptionIntent(context) else PermissionHealthChecker.batterySettingsIntent(),
            )
        }
    }
}

/** Status tones for permission rows, named like the old status colours so the rules above read the same. */
private object PermissionTones {
    val good = Tone.Good
    val warn = Tone.Caution
    val bad = Tone.Critical
    val neutral = Tone.Neutral
}

/** One permission: what it's for, whether it's on, and the one thing to do about it. */
@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    body: String,
    state: Pair<String, Tone>,
    action: Pair<String, () -> Unit>?,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.Top,
    ) {
        LeadingIcon(icon, if (state.second == Tone.Neutral) Tone.Neutral else state.second)
        Spacer(Modifier.width(Spacing.l))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Tag(state.first, tone = state.second)
            }
            Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                FilledTonalButton(onClick = action.second, modifier = Modifier.padding(top = Spacing.xs)) { Text(action.first) }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsHint(onOpen: () -> Unit) {
    ListRow(
        headline = "Switch greyed out?",
        supporting = "Android blocks these for sideloaded apps until you open App info → ⋮ → Allow restricted settings.",
        supportingLines = 4,
        leading = { LeadingIcon(Icons.Outlined.Info, Tone.Brand) },
        trailing = { TextButton(onClick = onOpen) { Text("App info") } },
    )
}

@Composable
private fun BatteryRow(unrestricted: Boolean, @Suppress("UNUSED_PARAMETER") good: Tone, @Suppress("UNUSED_PARAMETER") neutral: Tone, onToggle: (Boolean) -> Unit) {
    SwitchRow(
        "Unrestricted battery",
        checked = unrestricted,
        onCheckedChange = onToggle,
        supporting = "Troubleshooting only: turn on if recording falls behind while the phone sleeps.",
        leading = { LeadingIcon(Icons.Outlined.BatteryChargingFull) },
    )
}
