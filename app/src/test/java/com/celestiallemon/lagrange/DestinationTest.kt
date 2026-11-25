package com.celestiallemon.lagrange

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DestinationTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun `every destination round-trips through its intent to MainActivity`() {
        for (d in Destination.entries) {
            val intent = d.intent(context)
            assertEquals(MainActivity::class.java.name, intent.component?.className)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
            assertEquals(d, Destination.fromIntent(intent))
        }
    }

    @Test
    fun `missing or unknown destinations open nothing in particular`() {
        assertNull(Destination.fromIntent(null))
        assertNull(Destination.fromIntent(Intent()))
        assertNull(Destination.fromIntent(Intent().putExtra(Destination.EXTRA_DESTINATION, "LAB")))
        assertNull(Destination.fromIntent(Intent().putExtra(Destination.EXTRA_DESTINATION, "SETTINGS_OF_THE_FUTURE")))
    }

    @Test
    fun `legacy SYSTEM alerts still open the Settings tab`() {
        assertEquals(Destination.SETTINGS, Destination.fromIntent(Intent().putExtra(Destination.EXTRA_DESTINATION, "SYSTEM")))
    }
}
