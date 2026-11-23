package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.room3.useReaderConnection
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.data.db.FrictionEventEntity
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawUsageEventEntity
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.FakeResolver
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DatabaseSchemaTest {
    private val db = LagrangeDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val interner = Interner(db, FakeResolver())

    @After
    fun close() = db.close()

    private fun query(sql: String): List<List<String?>> = runBlocking {
        db.useReaderConnection { c ->
            c.usePrepared(sql) { s ->
                buildList { while (s.step()) add((0 until s.getColumnCount()).map { if (s.isNull(it)) null else s.getText(it) }) }
            }
        }
    }

    @Test
    fun `every v1 table exists`() {
        val tables = query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] }.toSet()
        val expected = setOf(
            "packages", "classes", "raw_usage_events", "raw_notifications", "tz_history", "service_coverage_intervals",
            "settings_history", "daily_notes", "friction_schedules", "friction_events", "sessions", "session_segments",
            "daily_rollups", "daily_app_rollups", "hourly_app_usage", "ingestion_state",
        )
        assertTrue("missing: ${expected - tables}", tables.containsAll(expected))
    }

    @Test
    fun `foreign keys are enforced`() {
        assertEquals("1", query("PRAGMA foreign_keys")[0][0])
        runBlocking {
            try {
                db.rawUsageEventDao().insertAll(listOf(RawUsageEventEntity(timestampUtcMs = 1, eventType = 1, pkgId = 999, clsId = 999)))
                fail("insert with dangling pkg_id must fail")
            } catch (e: Exception) {
                assertTrue(e.message.orEmpty().contains("FOREIGN KEY", ignoreCase = true))
            }
        }
    }

    @Test
    fun `canonical empty class row is seeded once and null interns to it`() = runBlocking {
        assertEquals(1, db.classDao().emptyClassRows())
        val seeded = db.classDao().idOf("")
        assertEquals(seeded, interner.classId(null))
        assertEquals(seeded, interner.classId(""))
        assertEquals(1, db.classDao().emptyClassRows())
    }

    @Test
    fun `unique key deduplicates re-inserted events including null-class ones`() = runBlocking {
        val pkg = interner.packageId("com.example")
        val nullCls = interner.classId(null)
        val rows = listOf(
            RawUsageEventEntity(timestampUtcMs = 10, eventType = 15, pkgId = pkg, clsId = nullCls),
            RawUsageEventEntity(timestampUtcMs = 10, eventType = 15, pkgId = pkg, clsId = nullCls, seqInMs = 1),
            RawUsageEventEntity(timestampUtcMs = 11, eventType = 1, pkgId = pkg, clsId = interner.classId("a.B")),
        )
        assertTrue(db.rawUsageEventDao().insertAll(rows).all { it > 0 })
        assertTrue(db.rawUsageEventDao().insertAll(rows).all { it == -1L })
        assertEquals(3L, db.rawUsageEventDao().count())
    }

    @Test
    fun `deleting a session cascades to its segments and schedules null out friction events`() = runBlocking {
        val pkg = interner.packageId("com.example")
        val derived = db.derivedDao()
        val session = derived.insertSession(
            SessionEntity(
                episodeKey = "k", algorithmVersion = 1, logicalDate = "2026-11-23", startMs = 0, endMs = 1, activeDurationMs = 1,
                physicalPickupCount = 1, isGlanceOnly = false, triggerEvidence = "NO_OBSERVED_TRIGGER", triggerPkgId = null,
                triggerNotificationId = null, entryPkgId = pkg, totalDistractingMs = 0, totalNeutralMs = 1, totalUtilityMs = 0,
                totalExcludedMs = 0, blockedAttemptCount = 0, distinctAppCount = 1, distinctDistractingCount = 0, archetype = "MICRO_CHECK",
                isRabbitHole = false, rabbitHoleReasons = null, worthItRating = null, contextFlags = null,
            ),
        )
        derived.insertSegment(SessionSegmentEntity(sessionId = session, seqIndex = 0, pkgId = pkg, startMs = 0, endMs = 1, durationMs = 1, isBlockedAttempt = false, segmentTriggerEvidence = null, segmentTriggerPkgId = null))
        assertEquals(1, derived.segmentCount(session))
        derived.deleteSession(session)
        assertEquals(0, derived.segmentCount(session))

        val schedule = derived.insertSchedule(FrictionScheduleEntity(name = "Work", enabled = true, daysOfWeekMask = 31, startMinuteOfDay = 540, endMinuteOfDay = 1050, escalationBoost = 1, requireTimerPrompt = false, nudgeIntervalMin = null))
        val event = derived.insertFrictionEvent(FrictionEventEntity(timestampUtcMs = 1, logicalDate = "2026-11-23", pkgId = pkg, eventKind = "LAUNCH_PAUSE", scheduleId = schedule, escalationTier = 0, pauseDurationSec = 3, postCountdownReactionMs = null, intentionTag = null, selectedTimerMin = null, redirectPkgId = null, outcome = "CONTINUED"))
        derived.deleteSchedule(schedule)
        assertNull(derived.frictionEvent(event)!!.scheduleId)
    }

    @Test
    fun `exported schema matches the compiled database and never allows destructive fallback`() = runBlocking {
        val schema = File("schemas/com.celestiallemon.lagrange.data.db.LagrangeDatabase/1.json")
        assertTrue("schema export missing at ${schema.absolutePath}", schema.isFile)
        val exported = Json.parseToJsonElement(schema.readText()).jsonObject["database"]!!.jsonObject["identityHash"]!!.jsonPrimitive.content
        assertEquals(exported, db.identityHash())
        val sources = File("src/main/java").walkTopDown().filter { it.extension == "kt" }
            .filter { it.readText().contains("fallbackToDestructiveMigration(") }.toList()
        assertTrue("destructive migration used in $sources", sources.isEmpty())
    }
}
