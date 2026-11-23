package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DigestPreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LagrangeDatabase.inMemory(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun prefs(name: String = "d${System.nanoTime()}") = LagrangePreferences(
        PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "$name.preferences_pb") },
        db,
    )

    @Test
    fun `overlapping partial digest edits never undo each other`() = runBlocking {
        val p = prefs()
        repeat(20) { round ->
            val day = round % 7 + 1
            val minute = 300 + round
            // A weekday chip tap and a time step racing: both must land.
            listOf(
                scope.async { p.setWeeklyDigest(dayOfWeek = day) },
                scope.async { p.setWeeklyDigest(minuteOfDay = minute) },
                scope.async { p.setDailyDigest(enabled = round % 2 == 0) },
                scope.async { p.setDailyDigest(minuteOfDay = minute) },
            ).awaitAll()
            val now = p.current()
            assertEquals(day, now.weeklyDigestDayOfWeek)
            assertEquals(minute, now.weeklyDigestMinuteOfDay)
            assertEquals(round % 2 == 0, now.dailyDigestEnabled)
            assertEquals(minute, now.dailyDigestMinuteOfDay)
        }
    }

    @Test
    fun `digest settings default off and changes are logged to settings history`() = runBlocking {
        val p = prefs()
        val d = p.current()
        assertFalse(d.dailyDigestEnabled)
        assertFalse(d.weeklyDigestEnabled)
        assertEquals(21 * 60 + 30, d.dailyDigestMinuteOfDay)
        assertEquals(7, d.weeklyDigestDayOfWeek)

        p.setDailyDigest(enabled = true)
        p.setDailyDigest(minuteOfDay = 8 * 60)
        p.setDailyDigest(enabled = true) // no change, no row
        p.setWeeklyDigest(enabled = true, dayOfWeek = 1, minuteOfDay = 19 * 60)
        val now = p.current()
        assertTrue(now.dailyDigestEnabled)
        assertEquals(480, now.dailyDigestMinuteOfDay)
        assertEquals(1, now.weeklyDigestDayOfWeek)

        val daily = db.settingsHistoryDao().byKey(LagrangePreferences.HISTORY_DAILY_DIGEST).map { it.valueJson }
        assertEquals(listOf("{\"enabled\":true,\"minute_of_day\":1290}", "{\"enabled\":true,\"minute_of_day\":480}"), daily)
        assertEquals(
            listOf("{\"enabled\":true,\"day_of_week\":1,\"minute_of_day\":1140}"),
            db.settingsHistoryDao().byKey(LagrangePreferences.HISTORY_WEEKLY_DIGEST).map { it.valueJson },
        )
    }

    @Test
    fun `digest settings round-trip through backup json, but dedupe keys stay on the device`() = runBlocking {
        val source = prefs()
        source.setDailyDigest(enabled = true, minuteOfDay = 7 * 60 + 15)
        source.setWeeklyDigest(enabled = true, dayOfWeek = 5, minuteOfDay = 18 * 60)
        assertTrue(source.claimDailyDigest("2026-11-24"))
        assertTrue(source.claimWeeklyDigest("2026-W48"))
        val json = source.exportJson()
        assertFalse(json.contains("2026-11-24"))
        assertFalse(json.contains("2026-W48"))

        val target = prefs()
        target.importJson(json)
        val t = target.current()
        assertTrue(t.dailyDigestEnabled)
        assertEquals(435, t.dailyDigestMinuteOfDay)
        assertTrue(t.weeklyDigestEnabled)
        assertEquals(5, t.weeklyDigestDayOfWeek)
        assertEquals(1080, t.weeklyDigestMinuteOfDay)
        assertNull(t.lastDailyDigestDate)
    }

    @Test
    fun `out-of-range or missing digest values in a backup fall back to defaults`() = runBlocking {
        val source = prefs()
        val obj = Json.parseToJsonElement(source.exportJson()).jsonObject.toMutableMap()
        obj["daily_digest_minute_of_day"] = JsonPrimitive(5000)
        obj["weekly_digest_day_of_week"] = JsonPrimitive(9)
        obj.remove("weekly_digest_enabled")
        val target = prefs()
        target.setWeeklyDigest(enabled = true)
        target.importJson(JsonObject(obj).toString())
        val t = target.current()
        assertEquals(UserPrefs.DEFAULT_DAILY_DIGEST_MINUTE, t.dailyDigestMinuteOfDay)
        assertEquals(UserPrefs.DEFAULT_WEEKLY_DIGEST_DAY, t.weeklyDigestDayOfWeek)
        assertFalse(t.weeklyDigestEnabled)
    }

    @Test
    fun `claims are atomic per key and can be released only by their owner`() = runBlocking {
        val p = prefs()
        assertTrue(p.claimDailyDigest("2026-11-24"))
        assertFalse(p.claimDailyDigest("2026-11-24"))
        p.releaseDailyDigest("2026-11-23") // someone else's: ignored
        assertFalse(p.claimDailyDigest("2026-11-24"))
        p.releaseDailyDigest("2026-11-24")
        assertTrue(p.claimDailyDigest("2026-11-24"))
        assertTrue(p.claimCoverageAlert(1000))
        assertFalse(p.claimCoverageAlert(1000))
        assertTrue(p.claimCoverageAlert(2000))
    }
}
