package com.celestiallemon.lagrange.system

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.domain.lab.ChannelSettings
import com.celestiallemon.lagrange.domain.lab.NotificationSettingsTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class NotificationSettingsIntentsTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    /** Records every start; actions in [missing] behave like a device without that settings page. */
    private class Recorder(base: Application, val missing: Set<String> = emptySet(), val denied: Set<String> = emptySet()) : ContextWrapper(base) {
        val tried = ArrayList<Intent>()
        val started = ArrayList<Intent>()
        override fun startActivity(intent: Intent) {
            tried += intent
            if (intent.action in missing) throw ActivityNotFoundException(intent.action)
            if (intent.action in denied) throw SecurityException(intent.action)
            started += intent
        }
    }

    @Test
    fun `a real channel opens the channel page with both extras`() {
        val i = NotificationSettingsIntents.intentFor(NotificationSettingsTarget.Channel("com.example", "promotions"))
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, i.action)
        assertEquals("com.example", i.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertEquals("promotions", i.getStringExtra(Settings.EXTRA_CHANNEL_ID))

        val ctx = Recorder(app)
        assertTrue(NotificationSettingsIntents.open(ctx, NotificationSettingsTarget.Channel("com.example", "promotions")))
        assertEquals(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, ctx.started.single().action)
        assertTrue(ctx.started.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `a pseudonymized channel goes straight to the app page`() {
        val target = ChannelSettings.target("com.example", "conversation:7c1e")
        val ctx = Recorder(app)
        assertTrue(NotificationSettingsIntents.open(ctx, target))
        val i = ctx.tried.single()
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, i.action)
        assertEquals("com.example", i.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertFalse(i.hasExtra(Settings.EXTRA_CHANNEL_ID))
    }

    @Test
    fun `a missing or refused channel page falls back to the app page`() {
        val target = NotificationSettingsTarget.Channel("com.example", "gone")
        val missing = Recorder(app, missing = setOf(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS))
        assertTrue(NotificationSettingsIntents.open(missing, target))
        assertEquals(listOf(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, Settings.ACTION_APP_NOTIFICATION_SETTINGS), missing.tried.map { it.action })
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, missing.started.single().action)

        val denied = Recorder(app, denied = setOf(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS))
        assertTrue(NotificationSettingsIntents.open(denied, target))
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, denied.started.single().action)

        val nothing = Recorder(app, missing = setOf(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS, Settings.ACTION_APP_NOTIFICATION_SETTINGS))
        assertFalse(NotificationSettingsIntents.open(nothing, target))
    }
}
