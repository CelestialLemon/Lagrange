package com.celestiallemon.lagrange.ui.friction

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.domain.digest.DigestKind
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.theme.Space

private val isoDays = listOf(1 to "Mon", 2 to "Tue", 3 to "Wed", 4 to "Thu", 5 to "Fri", 6 to "Sat", 7 to "Sun")

private fun Context.notificationsAllowed(): Boolean =
    checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
        NotificationManagerCompat.from(this).areNotificationsEnabled()

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Reflection Digests (spec §5.5, §6): a calm daily and weekly local summary. Turning one on asks for
 * POST_NOTIFICATIONS first; when Android won't show the prompt again it points to the app's notification settings.
 */
@Composable
internal fun DigestSettingsCard(prefs: UserPrefs, vm: FrictionViewModel) {
    val context = LocalContext.current
    val message by vm.digestMessage.collectAsStateWithLifecycle()
    var allowed by remember { mutableStateOf(context.notificationsAllowed()) }
    var denied by rememberSaveable { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        allowed = context.notificationsAllowed()
        onPauseOrDispose { }
    }
    // The toggle that asked for permission; it is applied whatever the answer (the setting is kept for later).
    var pendingEnable by rememberSaveable { mutableIntStateOf(0) }
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = context.notificationsAllowed()
        denied = !granted
        when (pendingEnable) {
            1 -> vm.setDailyDigestEnabled(true)
            2 -> vm.setWeeklyDigestEnabled(true)
        }
        pendingEnable = 0
    }

    fun openNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            // Nothing to open on this build.
        }
    }

    fun askPermission(forToggle: Int) {
        val activity = context.findActivity()
        val permissionGranted = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!permissionGranted && (!denied || activity?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true)) {
            pendingEnable = forToggle
            requestPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            // Granted but blocked in settings, or permanently denied: only the settings page can change it.
            when (forToggle) {
                1 -> vm.setDailyDigestEnabled(true)
                2 -> vm.setWeeklyDigestEnabled(true)
            }
            openNotificationSettings()
        }
    }

    SectionCard(title = "Reflection digests", eyebrow = "Local summaries", icon = Icons.Outlined.NotificationsActive) {
        Text(
            "A quiet notification with your budget, biggest rabbit hole, longest phone-free stretch and launch-pause exits. Days with collection gaps are labelled as lower bounds, never compared.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!allowed) {
            Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
                Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    Text(
                        "Digests need notification permission. Nothing leaves your phone: they are composed and shown on-device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    FilledTonalButton(onClick = { askPermission(0) }) { Text("Allow notifications") }
                }
            }
        }

        // Daily
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Daily digest", style = MaterialTheme.typography.titleSmall)
                Text(
                    "After noon (logical day) it reports today so far; earlier, the day before.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefs.dailyDigestEnabled,
                onCheckedChange = { on -> if (on && !allowed) askPermission(1) else vm.setDailyDigestEnabled(on) },
            )
        }
        if (prefs.dailyDigestEnabled) {
            TimeStepperRow("At", prefs.dailyDigestMinuteOfDay) { step ->
                vm.setDailyDigestMinute((prefs.dailyDigestMinuteOfDay + step + 1440) % 1440)
            }
        }

        HorizontalDivider()

        // Weekly
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Weekly digest", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The seven days before it runs, against the week before (only when both have enough fully covered days).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = prefs.weeklyDigestEnabled,
                onCheckedChange = { on -> if (on && !allowed) askPermission(2) else vm.setWeeklyDigestEnabled(on) },
            )
        }
        if (prefs.weeklyDigestEnabled) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.xs), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                isoDays.forEach { (day, label) ->
                    FilterChip(
                        selected = prefs.weeklyDigestDayOfWeek == day,
                        onClick = { vm.setWeeklyDigestDay(day) },
                        label = { Text(label) },
                    )
                }
            }
            TimeStepperRow("At", prefs.weeklyDigestMinuteOfDay) { step ->
                vm.setWeeklyDigestMinute((prefs.weeklyDigestMinuteOfDay + step + 1440) % 1440)
            }
        }

        HorizontalDivider()

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalArrangement = Arrangement.spacedBy(Space.s)) {
            OutlinedButton(onClick = { if (allowed) vm.sendPreviewDigest(DigestKind.DAILY) else askPermission(0) }) {
                Text("Send preview digest")
            }
            TextButton(onClick = { if (allowed) vm.sendPreviewDigest(DigestKind.WEEKLY) else askPermission(0) }) {
                Text("Preview weekly")
            }
        }
        message?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    msg,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = vm::clearDigestMessage) { Text("Dismiss") }
            }
        }
    }
}
