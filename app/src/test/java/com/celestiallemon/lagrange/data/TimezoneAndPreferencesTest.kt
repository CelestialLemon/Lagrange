package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
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
        db.ingestionStateDao().remove(IngestionKeys.DERIVED_PENDING_FROM_MS)
        assertTrue(tracker.check())
        assertEquals("Europe/Berlin", db.tzHistoryDao().latest()!!.zoneId)
        // A zone change re-derives the days around it, in the same transaction as the tz_history row.
        assertEquals(now - 2 * 24 * 60 * 60 * 1000L, db.ingestionStateDao().get(IngestionKeys.DERIVED_PENDING_FROM_MS))
        assertEquals(3, db.tzHistoryDao().all().size)
    }

    @Test
    fun `a settings change whose preference write was lost is finished from history at startup`() = runBlocking {
        val p = prefs()
        assertFalse(p.repairFromHistory())
        val history = db.settingsHistoryDao()
        history.insert(SettingsHistoryEntity(timestampUtcMs = 1, settingKey = LagrangePreferences.HISTORY_DAILY_BUDGET_MS, valueJson = "${30 * 60_000L}"))
        history.insert(SettingsHistoryEntity(timestampUtcMs = 2, settingKey = LagrangePreferences.HISTORY_ROLLOVER_HOUR, valueJson = "6"))
        assertTrue(p.repairFromHistory())
        assertEquals(30 * 60_000L, p.current().dailyBudgetMs)
        assertEquals(6, p.current().rolloverHour)
        assertFalse(p.repairFromHistory())
        // A normal change writes history and preference together.
        p.setDailyBudgetMs(45 * 60_000L)
        assertEquals("${45 * 60_000L}", history.byKey(LagrangePreferences.HISTORY_DAILY_BUDGET_MS).last().valueJson)
        assertFalse(p.repairFromHistory())
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
    fun `daily budget defaults to an hour, is range-checked and every change is kept in history`() = runBlocking {
        val p = prefs()
        assertEquals(60 * 60_000L, p.current().dailyBudgetMs)
        p.setDailyBudgetMs(60 * 60_000L) // unchanged: no history row
        p.setDailyBudgetMs(45 * 60_000L)
        assertEquals(45 * 60_000L, p.current().dailyBudgetMs)
        val rows = db.settingsHistoryDao().byKey(LagrangePreferences.HISTORY_DAILY_BUDGET_MS)
        assertEquals(listOf("${45 * 60_000L}"), rows.map { it.valueJson })
        assertTrue(runCatching { p.setDailyBudgetMs(60_000L) }.isFailure)
        assertTrue(runCatching { p.setDailyBudgetMs(13 * 3_600_000L) }.isFailure)
        assertEquals(45 * 60_000L, p.current().dailyBudgetMs)

        val b = prefs()
        b.importJson(p.exportJson())
        assertEquals(45 * 60_000L, b.current().dailyBudgetMs)
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
