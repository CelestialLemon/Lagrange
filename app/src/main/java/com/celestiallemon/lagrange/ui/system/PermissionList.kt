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
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space

private fun Context.launch(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        startActivity(PermissionHealthChecker.appInfoIntent(this))
    }
}

/** Permission Health rows. [compact] shows just the two collector permissions (onboarding). */
@Composable
fun PermissionList(health: PermissionHealth, compact: Boolean, onChanged: () -> Unit) {
    val context = LocalContext.current
    val status = LocalStatusColors.current
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { onChanged() }

    Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
        PermissionRow(
            icon = Icons.Outlined.DataUsage,
            title = "Usage Access",
            body = "Reads app, screen and unlock events so nothing is lost when Android purges its history.",
            state = if (health.usageAccess) "Granted" to status.good else "Required" to status.bad,
            action = if (health.usageAccess) null else "Grant" to { context.launch(PermissionHealthChecker.usageAccessIntent(context)) },
        )
        PermissionRow(
            icon = Icons.Outlined.NotificationsActive,
            title = "Notification Access",
            body = "Records notification metadata only (app, channel, timing). Never titles, text or senders.",
            state = if (health.notificationAccess) "Granted" to status.good else "Recommended" to status.warn,
            action = if (health.notificationAccess) null else "Grant" to { context.launch(PermissionHealthChecker.notificationAccessIntent(context)) },
        )
        RestrictedSettingsHint { context.launch(PermissionHealthChecker.appInfoIntent(context)) }
        if (compact) return@Column

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        PermissionRow(
            icon = Icons.Outlined.AccessibilityNew,
            title = "Accessibility (friction)",
            body = "Only needed once gentle friction ships in Phase 3. Collection works without it.",
            state = if (health.accessibilityEnabled) "On" to status.good else "Not needed yet" to status.neutral,
            action = null,
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
                Check.YES -> "On. Android restricts sideloaded accessibility tools, so Phase 3 friction may be unavailable. Collection is unaffected."
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

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    body: String,
    state: Pair<String, Color>,
    action: Pair<String, () -> Unit>?,
) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                StatusPill(state.first, state.second)
            }
            Spacer(Modifier.height(Space.xs))
            Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (action != null) {
                TextButton(onClick = action.second, contentPadding = PaddingValues(0.dp)) {
                    Text(action.first)
                }
            }
        }
    }
}

@Composable
private fun RestrictedSettingsHint(onOpen: () -> Unit) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp))
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text(
                "Toggle greyed out? Android blocks these for sideloaded apps until you open App info → ⋮ → Allow restricted settings.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpen, contentPadding = PaddingValues(0.dp)) { Text("Open App info") }
        }
    }
}

@Composable
private fun BatteryRow(unrestricted: Boolean, good: Color, neutral: Color, onToggle: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Outlined.BatteryChargingFull, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Space.m))
        Column(Modifier.weight(1f)) {
            Text("Unrestricted battery", style = MaterialTheme.typography.titleSmall)
            Text(
                "Troubleshooting only: turn on if harvests are missed while the phone dozes.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Space.xs))
            StatusPill(if (unrestricted) "Unrestricted" else "Optimized (default)", if (unrestricted) good else neutral)
        }
        Spacer(Modifier.width(Space.s))
        Switch(checked = unrestricted, onCheckedChange = onToggle)
    }
}
