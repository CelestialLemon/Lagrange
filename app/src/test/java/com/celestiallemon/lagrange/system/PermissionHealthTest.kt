package com.celestiallemon.lagrange.system

import android.app.Application
import android.content.ComponentName
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.friction.LagrangeAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class PermissionHealthTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `detects LagrangeAccessibilityService in full and short flattened formats only`() {
        val resolver = context.contentResolver
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        assertFalse(PermissionHealthChecker.isAccessibilityEnabled(context))

        // Another app's service must not count.
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "com.other.app/.OtherService")
        assertFalse(PermissionHealthChecker.isAccessibilityEnabled(context))

        // Full flattened component name.
        val component = ComponentName(context, LagrangeAccessibilityService::class.java)
        Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "com.other.app/.OtherService:${component.flattenToString()}",
        )
        assertTrue(PermissionHealthChecker.isAccessibilityEnabled(context))

        // Short flattened component name (e.g. com.celestiallemon.lagrange/.friction.LagrangeAccessibilityService).
        Settings.Secure.putString(
            resolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component.flattenToShortString(),
        )
        assertTrue(PermissionHealthChecker.isAccessibilityEnabled(context))
    }

    @Test
    fun `check reports accessibility state and advanced protection safely`() {
        val component = ComponentName(context, LagrangeAccessibilityService::class.java)
        Settings.Secure.putString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            component.flattenToString(),
        )
        val health = PermissionHealthChecker.check(context)
        assertTrue(health.accessibilityEnabled)
        assertFalse(health.accessibilityConnected)
        assertFalse(health.frictionReady)
        // On Robolectric SDK < 36 or without AdvancedProtectionManager service, never crashes.
        assertTrue(health.advancedProtection in setOf(Check.NO, Check.UNKNOWN, Check.YES))
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, PermissionHealthChecker.accessibilityIntent().action)
    }
}
