package com.celestiallemon.lagrange.system

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.security.advancedprotection.AdvancedProtectionManager
import androidx.core.app.NotificationManagerCompat
import com.celestiallemon.lagrange.friction.LagrangeAccessibilityService
import com.celestiallemon.lagrange.harvest.AndroidUsageEventSource
import com.celestiallemon.lagrange.notifications.LagrangeNotificationListenerService

/** Tri-state for checks the platform may refuse to answer. */
enum class Check { YES, NO, UNKNOWN }

data class PermissionHealth(
    val usageAccess: Boolean,
    val notificationAccess: Boolean,
    /** True when Lagrange's AccessibilityService is enabled in Android Settings. */
    val accessibilityEnabled: Boolean,
    /** True when Lagrange's AccessibilityService is actively bound and running in this process. */
    val accessibilityConnected: Boolean = false,
    val postNotifications: Boolean,
    /** True when Android won't auto-revoke permissions if the app goes unused for months. */
    val hibernationExempt: Check,
    val advancedProtection: Check,
    val batteryUnrestricted: Boolean,
) {
    val coreReady: Boolean get() = usageAccess && notificationAccess
    val frictionReady: Boolean get() = accessibilityEnabled && accessibilityConnected
}

object PermissionHealthChecker {
    fun check(context: Context): PermissionHealth = PermissionHealth(
        usageAccess = AndroidUsageEventSource.hasUsageAccess(context),
        notificationAccess = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context),
        accessibilityEnabled = isAccessibilityEnabled(context),
        accessibilityConnected = LagrangeAccessibilityService.connected,
        postNotifications = context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED,
        hibernationExempt = hibernationExempt(context),
        advancedProtection = advancedProtection(context),
        batteryUnrestricted = context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true,
    )

    internal fun isAccessibilityEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val expected = ComponentName(context, LagrangeAccessibilityService::class.java)
        val flattened = expected.flattenToString()
        val shortFlattened = expected.flattenToShortString()
        return enabled.split(':').any { raw ->
            val trimmed = raw.trim()
            trimmed == flattened || trimmed == shortFlattened ||
                ComponentName.unflattenFromString(trimmed) == expected
        }
    }

    @Suppress("DEPRECATION") // isAutoRevokeWhitelisted is the platform name for the hibernation exemption.
    private fun hibernationExempt(context: Context): Check = try {
        if (context.packageManager.isAutoRevokeWhitelisted) Check.YES else Check.NO
    } catch (_: Exception) {
        Check.UNKNOWN
    }

    /** Android 16+ device-level Advanced Protection; when on, sideloaded accessibility tools are restricted. */
    private fun advancedProtection(context: Context): Check {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.BAKLAVA) return Check.NO
        return try {
            val manager = context.getSystemService(AdvancedProtectionManager::class.java) ?: return Check.UNKNOWN
            if (manager.isAdvancedProtectionEnabled) Check.YES else Check.NO
        } catch (_: SecurityException) {
            Check.UNKNOWN
        } catch (_: RuntimeException) {
            Check.UNKNOWN
        }
    }

    // -- Intents that take the user to the right settings page -----------------------------------------------------

    fun usageAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    /** The plain Usage Access list, for settings apps that don't support the per-package page. */
    fun usageAccessListIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)

    /** The plain Notification Access list, for settings apps without the per-listener detail page. */
    fun notificationAccessListIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)

    fun notificationAccessIntent(context: Context): Intent =
        Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
            Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
            ComponentName(context, LagrangeNotificationListenerService::class.java).flattenToString(),
        )

    fun accessibilityIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)

    fun appNotificationSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)

    fun hibernationIntent(context: Context): Intent =
        Intent(Intent.ACTION_AUTO_REVOKE_PERMISSIONS, Uri.fromParts("package", context.packageName, null))

    /** App info page, where sideloaded apps find "Allow restricted settings" in the ⋮ menu. */
    fun appInfoIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))

    @Suppress("BatteryLife") // Personal sideloaded tool; offered only as an opt-in troubleshooting step.
    fun requestBatteryExemptionIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", context.packageName, null))

    fun batterySettingsIntent(): Intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}
