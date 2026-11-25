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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.SwitchRow
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone

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

    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        if (!allowed) {
            Banner(
                "Digests need notification permission",
                Tone.Brand,
                Icons.Outlined.NotificationsActive,
                body = "They're composed and shown on this phone. Nothing leaves it.",
                action = "Allow notifications" to { askPermission(0) },
            )
        }
        RowGroup {
            SwitchRow(
                "Daily digest",
                checked = prefs.dailyDigestEnabled,
                onCheckedChange = { on -> if (on && !allowed) askPermission(1) else vm.setDailyDigestEnabled(on) },
                supporting = "Budget, biggest rabbit hole, longest phone-free stretch. After noon it covers today so far.",
            )
            if (prefs.dailyDigestEnabled) {
                Box(Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = Spacing.l, vertical = Spacing.xs)) {
                    TimeStepperRow("At", prefs.dailyDigestMinuteOfDay) { step -> vm.setDailyDigestMinute((prefs.dailyDigestMinuteOfDay + step + 1440) % 1440) }
                }
            }
            SwitchRow(
                "Weekly digest",
                checked = prefs.weeklyDigestEnabled,
                onCheckedChange = { on -> if (on && !allowed) askPermission(2) else vm.setWeeklyDigestEnabled(on) },
                supporting = "The past seven days against the week before, when both have enough recorded days.",
            )
            if (prefs.weeklyDigestEnabled) {
                Column(
                    Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow).padding(horizontal = Spacing.l, vertical = Spacing.s),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                ) {
                    Choice(isoDays.map { it.first }, prefs.weeklyDigestDayOfWeek, vm::setWeeklyDigestDay, { d -> isoDays.first { it.first == d }.second }, forceChips = true)
                    TimeStepperRow("At", prefs.weeklyDigestMinuteOfDay) { step -> vm.setWeeklyDigestMinute((prefs.weeklyDigestMinuteOfDay + step + 1440) % 1440) }
                }
            }
            ListRow(
                "Preview",
                supporting = message ?: "See what tonight's digest would say",
                onClick = { if (allowed) vm.sendPreviewDigest(DigestKind.DAILY) else askPermission(0) },
                trailing = {
                    TextButton(onClick = { if (allowed) vm.sendPreviewDigest(DigestKind.WEEKLY) else askPermission(0) }) { Text("Weekly") }
                },
            )
        }
    }
}
