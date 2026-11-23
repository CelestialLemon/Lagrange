package com.celestiallemon.lagrange.backup

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.DailyAppRollupEntity
import com.celestiallemon.lagrange.data.db.DailyNoteEntity
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.FrictionEventEntity
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawNotificationEntity
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.data.db.TzHistoryEntity
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.time.ZoneId
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class CsvExportTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "csv.preferences_pb") }
    private val db = LagrangeDatabase.create(context)
    private val restore = RestoreCoordinator(context)
    private val backups = LagrangeBackups(context, db, LagrangePreferences(store, db), restore, "test", 1)

    // 2026-11-24 08:00 UTC: 09:00 in Berlin, 13:30 in Kolkata.
    private val t0 = 1_795_507_200_000L
    private val min = 60_000L

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        restore.clearAll()
        context.deleteDatabase(LagrangeDatabase.FILE_NAME)
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().decodeToString()
            }
        }
        return out
    }

    /** A minimal RFC 4180 reader (quoted fields, doubled quotes, embedded CR/LF), to prove the files round-trip. */
    private fun parse(csv: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var i = 0
        var quoted = false
        while (i < csv.length) {
            val c = csv[i]
            if (quoted) {
                if (c == '"' && i + 1 < csv.length && csv[i + 1] == '"') { field.append('"'); i++ }
                else if (c == '"') quoted = false
                else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                ',' -> { row += field.toString(); field.clear() }
                '\r' -> {
                    assertEquals("records end with CRLF", '\n', csv[i + 1])
                    row += field.toString(); field.clear(); rows += row; row = ArrayList(); i++
                }
                '\n' -> fail("bare LF outside a quoted field")
                else -> field.append(c)
            }
            i++
        }
        assertTrue("file ends with a complete record", row.isEmpty() && field.isEmpty())
        return rows
    }

    private fun table(files: Map<String, String>, name: String): List<Map<String, String>> {
        val rows = parse(files.getValue(name))
        val header = rows.first()
        rows.drop(1).forEach { assertEquals("$name: every record has every column", header.size, it.size) }
        return rows.drop(1).map { header.zip(it).toMap() }
    }

    private suspend fun seed(): Map<String, Long> {
        val interner = Interner(db, FakeResolver())
        val reddit = interner.packageId("com.reddit.frontpage")
        val kindle = interner.packageId("com.amazon.kindle")
        // Rename to something that needs quoting.
        db.packageDao().byId(reddit)!!.let { db.packageDao().update(it.copy(displayName = "Reddit, \"the\" front\npage", category = "DISTRACTING")) }
        db.tzHistoryDao().insert(TzHistoryEntity(effectiveFromUtcMs = 0, zoneId = "Europe/Berlin", isAssumed = true))
        db.tzHistoryDao().insert(TzHistoryEntity(effectiveFromUtcMs = t0 + 60 * min, zoneId = "Asia/Kolkata", isAssumed = false))

        val dao = db.derivedDao()
        dao.upsertDailyRollup(
            DailyRollupEntity(
                logicalDate = "2026-11-24", algorithmVersion = 1, coverageStatus = DayCoverage.COMPLETE.name, notifCoverageRatio = 1.0,
                frictionCoverageRatio = 0.5, totalScreenMs = 100 * min, activeUsageMs = 90 * min, distractingMs = 40 * min, neutralMs = 30 * min,
                utilityMs = 20 * min, excludedMs = 0, selfAppMs = 0, episodeCount = 3, physicalPickupCount = 3, glanceCount = 1,
                microCheckCount = 0, rabbitHoleCount = 1, blockedAttemptCount = 1, notificationCount = 2, longestFocusGapMs = 120 * min,
                overnightGapMs = null, firstPickupMs = t0, lastActivityMs = t0 + 120 * min, budgetTargetMs = 60 * min, baselineMedianMs = null,
                dayStatus = DayStatus.NORMAL.name, spikeSummaryJson = null,
            ),
        )
        dao.upsertNote(DailyNoteEntity("2026-11-24", "Flew to Delhi, \"long\" day", t0))
        dao.upsertDailyAppRollup(DailyAppRollupEntity("2026-11-24", reddit, 40 * min, 3, 1, 2, 30 * min))
        dao.upsertDailyAppRollup(DailyAppRollupEntity("2026-11-24", kindle, 20 * min, 1, 0, 1, 0))
        dao.upsertDailyAppRollup(DailyAppRollupEntity("2026-11-23", reddit, 10 * min, 1, 0, 1, 0))
        for (i in 0 until 5) {
            val start = t0 + i * 30 * min
            val id = dao.insertSession(
                SessionEntity(
                    episodeKey = "k$i", algorithmVersion = 1, logicalDate = "2026-11-24", startMs = start, endMs = start + 10 * min,
                    activeDurationMs = 10 * min, physicalPickupCount = 1, isGlanceOnly = false, triggerEvidence = "NO_OBSERVED_TRIGGER",
                    triggerPkgId = null, triggerNotificationId = null, entryPkgId = reddit, totalDistractingMs = 10 * min, totalNeutralMs = 0,
                    totalUtilityMs = 0, totalExcludedMs = 0, blockedAttemptCount = 0, distinctAppCount = 1, distinctDistractingCount = 1,
                    archetype = "FOCUSED", isRabbitHole = i == 0, rabbitHoleReasons = if (i == 0) "LONG_BINGE,HOPPING" else null,
                    worthItRating = null, contextFlags = null,
                ),
            )
            dao.insertSegment(SessionSegmentEntity(sessionId = id, seqIndex = 0, pkgId = reddit, startMs = start, endMs = start + 6 * min, durationMs = 6 * min, isBlockedAttempt = false, segmentTriggerEvidence = null, segmentTriggerPkgId = null))
            dao.insertSegment(SessionSegmentEntity(sessionId = id, seqIndex = 1, pkgId = kindle, startMs = start + 6 * min, endMs = start + 10 * min, durationMs = 4 * min, isBlockedAttempt = false, segmentTriggerEvidence = null, segmentTriggerPkgId = null))
        }
        for (i in 0 until 3) {
            db.rawNotificationDao().insert(
                RawNotificationEntity(
                    sbnKeyHash = "SECRET_KEY_HASH_$i", pkgId = reddit, channelId = "conversation:abc$i", androidCategory = "msg",
                    groupKeyHash = "SECRET_GROUP_HASH", postedAtMs = t0 + i * min, lastUpdatedAtMs = t0 + i * min, updateCount = i,
                    removedAtMs = if (i == 0) t0 + 5 * min else null,
                    removalReason = if (i == 0) RemovalReason.CLICK.name else RemovalReason.ACTIVE.name, importance = 4,
                    matchedInterruptionFilter = true, isOngoing = false, isForegroundService = false, isGroupSummary = false, isAmbient = false,
                    isConversation = true, isOnlyAlertOnce = false, dndOrSilentAtPost = false,
                ),
            )
        }
        val schedule = dao.insertSchedule(
            FrictionScheduleEntity(name = "Work, Hours", enabled = true, daysOfWeekMask = 31, startMinuteOfDay = 540, endMinuteOfDay = 1050, escalationBoost = 1, requireTimerPrompt = false, nudgeIntervalMin = null),
        )
        dao.insertFrictionEvent(
            FrictionEventEntity(
                timestampUtcMs = t0 + 90 * min, logicalDate = "2026-11-24", pkgId = reddit, eventKind = FrictionVocab.LAUNCH_PAUSE,
                scheduleId = schedule, escalationTier = 2, pauseDurationSec = 10, postCountdownReactionMs = 1200, intentionTag = "BORED",
                selectedTimerMin = null, redirectPkgId = kindle, outcome = "REDIRECTED",
            ),
        )
        return mapOf("reddit" to reddit, "kindle" to kindle)
    }

    private suspend fun export(pageSize: Int): Pair<Map<String, String>, CsvExportSummary> {
        val out = ByteArrayOutputStream()
        val summary = backups.exportCsvBundle(out, pageSize)
        return unzip(out.toByteArray()) to summary
    }

    @Test
    fun `bundle holds the six tidy files with resolved apps and both timestamps`() = runBlocking {
        seed()
        val (files, summary) = export(pageSize = 2)
        assertEquals(
            listOf("daily_summary.csv", "daily_app_usage.csv", "sessions.csv", "session_segments.csv", "notifications.csv", "friction_events.csv"),
            files.keys.toList(),
        )
        assertEquals(mapOf("daily_summary.csv" to 1L, "daily_app_usage.csv" to 3L, "sessions.csv" to 5L, "session_segments.csv" to 10L, "notifications.csv" to 3L, "friction_events.csv" to 1L), summary.rowsPerFile)

        val daily = table(files, CsvExporter.DAILY_SUMMARY).single()
        assertEquals("Tuesday", daily["weekday"])
        assertEquals((40 * min).toString(), daily["distracting_ms"])
        assertEquals("Flew to Delhi, \"long\" day", daily["day_note"])
        assertEquals("2026-11-24T09:00:00+01:00", daily["first_pickup_local"])
        assertEquals(t0.toString(), daily["first_pickup_utc_ms"])
        // After the tz_history change to Kolkata, local time follows the zone actually in force.
        assertEquals("2026-11-24T15:30:00+05:30", daily["last_activity_local"])

        val apps = table(files, CsvExporter.DAILY_APP_USAGE)
        assertEquals(listOf("2026-11-23", "2026-11-24", "2026-11-24"), apps.map { it["logical_date"] })
        assertEquals("Reddit, \"the\" front\npage", apps[0]["app_name"])
        assertEquals("DISTRACTING", apps[0]["category"])

        val sessions = table(files, CsvExporter.SESSIONS)
        assertEquals((1..5).map { it.toString() }, sessions.map { it["session_id"] })
        assertEquals("com.reddit.frontpage", sessions[0]["entry_package"])
        assertEquals("true", sessions[0]["is_rabbit_hole"])
        assertEquals("LONG_BINGE,HOPPING", sessions[0]["rabbit_hole_reasons"])
        assertEquals("2026-11-24T09:00:00+01:00", sessions[0]["start_local"])
        assertEquals("2026-11-24T15:30:00+05:30", sessions[4]["start_local"])

        val segments = table(files, CsvExporter.SESSION_SEGMENTS)
        assertEquals(10, segments.map { it["segment_id"] }.distinct().size)
        assertEquals("com.amazon.kindle", segments[1]["package_name"])

        val friction = table(files, CsvExporter.FRICTION_EVENTS).single()
        assertEquals("Work, Hours", friction["schedule_name"])
        assertEquals("com.amazon.kindle", friction["redirect_package"])
        assertEquals("2026-11-24T15:00:00+05:30", friction["timestamp_local"])
    }

    @Test
    fun `notifications are metadata only, with pseudonymous channels and no key hashes`() = runBlocking {
        seed()
        val (files, _) = export(pageSize = 1)
        val text = files.getValue(CsvExporter.NOTIFICATIONS)
        assertFalse(text.contains("SECRET_KEY_HASH"))
        assertFalse(text.contains("SECRET_GROUP_HASH"))
        val rows = table(files, CsvExporter.NOTIFICATIONS)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.getValue("channel_id").startsWith("conversation:") })
        assertEquals("CLICK", rows[0]["removal_reason"])
        assertEquals("", rows[1]["removed_utc_ms"])
        val header = parse(text).first()
        listOf("title", "text", "sender", "sbn_key_hash", "group_key_hash").forEach { assertFalse(it, it in header) }
    }

    @Test
    fun `paging returns every row exactly once at any page size`() = runBlocking {
        seed()
        val reference = export(pageSize = 10_000).first
        for (size in listOf(1, 2, 3, 7)) assertEquals("page size $size", reference, export(pageSize = size).first)
    }

    @Test
    fun `an empty database still yields six headers`() = runBlocking {
        val (files, summary) = export(pageSize = 2)
        assertEquals(6, files.size)
        assertEquals(0L, summary.totalRows)
        files.values.forEach { assertEquals(1, parse(it).size) }
    }

    @Test
    fun `cancellation propagates and the snapshot is cleaned up`() = runBlocking {
        seed()
        val failing = object : OutputStream() {
            var written = 0
            override fun write(b: Int) {
                if (++written > 200) throw CancellationException("stopped")
            }
        }
        try {
            backups.exportCsvBundle(failing, pageSize = 1)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(File(context.cacheDir, "snapshots").listFiles().orEmpty().isEmpty())
        // The lock was released: another export still works.
        assertEquals(6, export(pageSize = 5).first.size)
    }

    @Test
    fun `rfc 4180 escaping and file names`() {
        assertEquals("plain", Csv.field("plain"))
        assertEquals("\"a,b\"", Csv.field("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.field("say \"hi\""))
        assertEquals("\"line\r\nbreak\"", Csv.field("line\r\nbreak"))
        assertEquals("", Csv.field(null))
        assertEquals("a,,\"c,d\"\r\n", Csv.record(listOf("a", null, "c,d")))
        assertEquals("lagrange-csv-20261124-093005.zip", CsvExporter.fileName(t0 + 30 * min + 5_000, ZoneId.of("Europe/Berlin")))
    }
}
