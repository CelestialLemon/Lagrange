package com.celestiallemon.lagrange

import android.app.Application
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ManifestHardeningTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `the app cannot reach the network and opts out of OS backups`() {
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val requested = info.requestedPermissions.orEmpty().toSet()
        assertFalse("INTERNET must never be requested", "android.permission.INTERNET" in requested)
        assertFalse("android.permission.ACCESS_NETWORK_STATE" in requested)
        assertEquals(0, info.applicationInfo!!.flags and ApplicationInfo.FLAG_ALLOW_BACKUP)
    }

    @Test
    fun `merged source manifest keeps backup exclusions for the database and datastore`() {
        val rules = File("src/main/res/xml/data_extraction_rules.xml").readText()
        assertTrue(rules.contains("cloud-backup") && rules.contains("device-transfer"))
        assertTrue(rules.contains("domain=\"database\""))
        assertTrue(rules.contains("domain=\"file\""))
    }
}
