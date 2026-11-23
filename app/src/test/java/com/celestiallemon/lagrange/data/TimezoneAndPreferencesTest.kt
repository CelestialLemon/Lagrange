package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.File
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TimezoneAndPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LagrangeDatabase.inMemory(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun prefs(name: String = "p${System.nanoTime()}") = LagrangePreferences(
        PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "$name.preferences_pb") },
        db,
    )

    @Test
    fun `timezone history records an assumed backfill zone, then only changes`() = runBlocking {
        var zone = ZoneId.of("Asia/Kolkata")
        var now = 1_800_000_000_000L
        val tracker = TimezoneTracker(db, { now }, { zone })
        assertTrue(tracker.check())
        val first = db.tzHistoryDao().all()
        assertEquals(2, first.size)
        assertTrue(first[0].isAssumed)
        assertFalse(first[1].isAssumed)
        assertFalse(tracker.check())
        zone = ZoneId.of("Europe/Berlin")
        now += 1000
        assertTrue(tracker.check())
        assertEquals("Europe/Berlin", db.tzHistoryDao().latest()!!.zoneId)
        assertEquals(3, db.tzHistoryDao().all().size)
    }

    @Test
    fun `rollover defaults to 4 and changes are kept in settings history`() = runBlocking {
        val p = prefs()
        assertEquals(4, p.current().rolloverHour)
        p.setRolloverHour(4) // no change, no history row
        p.setRolloverHour(5)
        assertEquals(5, p.current().rolloverHour)
        val history = db.settingsHistoryDao().all()
        assertEquals(1, history.size)
        assertEquals("5", history.single().valueJson)
    }

    @Test
    fun `portable preferences round-trip and reject unknown formats`() = runBlocking {
        val a = prefs()
        a.setRolloverHour(3)
        a.setOnboardingComplete(true)
        a.setBackupTreeUri("content://tree/x")
        val json = a.exportJson()
        assertFalse("device-specific folder must not travel", json.contains("content://"))
        val b = prefs()
        b.importJson(json)
        assertEquals(3, b.current().rolloverHour)
        assertTrue(b.current().onboardingComplete)
        val bad = runCatching { LagrangePreferences.parsePortable("""{"format_version": 99}""") }
        assertTrue(bad.isFailure)
    }
}
