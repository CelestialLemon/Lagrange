package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageIntervalEntity
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.IngestionStateEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawUsageEventEntity
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
import com.celestiallemon.lagrange.data.db.UsageEventTypes as T
import com.celestiallemon.lagrange.domain.DayFixture
import com.celestiallemon.lagrange.domain.DerivedAlgorithm
import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.MINUTE_MS
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DerivedRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val dbs = ArrayList<LagrangeDatabase>()
    private val today = LocalDate.parse("2026-11-23")
    private val now = at(today, 21, 0)
    private var settings = DerivedSettings(4, 60 * MINUTE_MS)

    @After
    fun close() = dbs.forEach { it.close() }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private class Env(val db: LagrangeDatabase, val repo: DerivedRepository, val interner: Interner)

    private fun env(
        chunkDays: Int = DerivedRepository.DEFAULT_CHUNK_DAYS,
        lookbackMs: Long = DerivedRepository.LOOKBACK_MS,
        lookaheadMs: Long = DerivedRepository.LOOKAHEAD_MS,
    ): Env {
        val db = LagrangeDatabase.inMemory(context).also { dbs += it }
        val repo = DerivedRepository(
            db, settings = { settings }, clock = { now }, fallbackZone = { zone }, chunkDays = chunkDays,
            lookbackMs = lookbackMs, lookaheadMs = lookaheadMs,
        )
        return Env(db, repo, Interner(db, FakeResolver()))
    }

    private suspend fun Env.setup(fromMs: Long) {
        db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.LAST_HARVEST_AT_MS, now))
        for (kind in listOf(ServiceKind.USAGE_HARVEST, ServiceKind.NOTIFICATION_LISTENER)) {
            db.coverageDao().insert(CoverageIntervalEntity(serviceKind = kind.name, startMs = fromMs, endMs = null, status = CoverageStatus.ACTIVE.name, lastSeenMs = now))
        }
        for ((pkg, cat) in listOf(INSTAGRAM to AppCategory.DISTRACTING, WHATSAPP to AppCategory.NEUTRAL, MAPS to AppCategory.UTILITY)) {
            interner.packageId(pkg)
            db.packageDao().setCategory(pkg, cat.name, now)
        }
    }

    /** Screen on, unlock, one app for [durMs], lock, screen off. */
    private suspend fun Env.session(startMs: Long, pkg: String, durMs: Long) {
        val sys = interner.packageId("android")
        val none = interner.classId("")
        val p = interner.packageId(pkg)
        val c = interner.classId("$pkg.Main")
        db.rawUsageEventDao().insertAll(
            listOf(
                RawUsageEventEntity(timestampUtcMs = startMs, eventType = T.SCREEN_INTERACTIVE, pkgId = sys, clsId = none),
                RawUsageEventEntity(timestampUtcMs = startMs + 1_000, eventType = T.KEYGUARD_HIDDEN, pkgId = sys, clsId = none),
                RawUsageEventEntity(timestampUtcMs = startMs + 2_000, eventType = T.ACTIVITY_RESUMED, pkgId = p, clsId = c),
                RawUsageEventEntity(timestampUtcMs = startMs + 2_000 + durMs, eventType = T.ACTIVITY_PAUSED, pkgId = p, clsId = c),
                RawUsageEventEntity(timestampUtcMs = startMs + 3_000 + durMs, eventType = T.KEYGUARD_SHOWN, pkgId = sys, clsId = none),
                RawUsageEventEntity(timestampUtcMs = startMs + 3_000 + durMs, eventType = T.SCREEN_NON_INTERACTIVE, pkgId = sys, clsId = none),
            ),
        )
    }

    /** [days] days of history ending today: a WhatsApp session at 09:00 and an Instagram session at 21:00 before today. */
    private suspend fun Env.history(days: Int) {
        setup(at(today.minusDays(days.toLong()), 0))
        for (i in days downTo 0) {
            val d = today.minusDays(i.toLong())
            session(at(d, 9), WHATSAPP, 30 * MINUTE_MS)
            if (i > 0) session(at(d, 20), INSTAGRAM, (10 + i % 5) * MINUTE_MS)
        }
    }

    private suspend fun Env.state(key: String) = db.ingestionStateDao().get(key)

    private suspend fun Env.sessions(): List<SessionEntity> = db.derivedDao().allSessions()

    @Test
    fun `a full rebuild writes sessions, segments and rollups and clears its markers`() = runBlocking {
        val e = env()
        e.history(3)
        e.repo.markAllDirty()
        assertTrue(e.repo.hasPending())
        assertEquals(4, e.repo.runPending())
        assertFalse(e.repo.hasPending())
        assertNull(e.state(IngestionKeys.DERIVED_ACTIVE_FROM_MS))
        assertNotNull(e.state(IngestionKeys.DERIVED_LAST_RUN_AT_MS))

        val sessions = e.sessions()
        assertEquals(7, sessions.size)
        assertEquals(sessions.map { it.episodeKey }.toSet().size, sessions.size)
        assertEquals(today.minusDays(3).toString(), sessions.first().logicalDate)
        val segments = e.db.derivedDao().segmentsFor(today.toString())
        assertEquals(30 * MINUTE_MS, segments.single().durationMs)

        val rollups = e.db.derivedDao().dailyRollupsBetween(today.minusDays(3).toString(), today.toString())
        assertEquals(4, rollups.size)
        assertEquals(30 * MINUTE_MS, rollups.last().neutralMs)
        assertEquals(DerivedAlgorithm.VERSION, rollups.last().algorithmVersion)
        val apps = e.db.derivedDao().dailyRollupsBetween(today.toString(), today.toString())
        assertEquals(1, apps.size)
    }

    @Test
    fun `worth-it ratings survive full rebuilds and recategorization`() = runBlocking {
        val e = env()
        e.history(2)
        e.repo.markAllDirty()
        e.repo.runPending()
        val target = e.sessions().first { it.logicalDate == today.minusDays(1).toString() && it.totalDistractingMs > 0 }
        assertTrue(e.repo.setWorthIt(target.episodeKey, target.startMs, 2))

        e.repo.markAllDirty()
        e.repo.runPending()
        assertEquals(2, e.sessions().single { it.episodeKey == target.episodeKey }.worthItRating)

        // Recategorizing changes totals but not the episode, so the rating stays.
        e.db.packageDao().setCategory(INSTAGRAM, AppCategory.NEUTRAL.name, now)
        e.repo.markAllDirty()
        e.repo.runPending()
        val after = e.sessions().single { it.episodeKey == target.episodeKey }
        assertEquals(2, after.worthItRating)
        assertEquals(0L, after.totalDistractingMs)
        assertEquals(1, e.sessions().count { it.worthItRating != null })
    }

    @Test
    fun `an incremental rebuild touches only its days but refreshes baselines for the 28 days after`() = runBlocking {
        val e = env()
        e.history(45)
        e.repo.markAllDirty()
        e.repo.runPending()
        val dirtyDay = today.minusDays(40)
        val idsBefore = e.sessions().associate { it.episodeKey to it.id }

        // Sentinels: one inside the 28-day trailing window, one beyond it.
        val inside = today.minusDays(20).toString()
        val beyond = today.minusDays(5).toString()
        e.db.derivedDao().updateBaseline(inside, 1L, "NORMAL")
        e.db.derivedDao().updateBaseline(beyond, 1L, "NORMAL")

        val w = e.repo.windowOf(dirtyDay)
        e.repo.markDirty(w.startMs + HOUR_MS, w.startMs + 2 * HOUR_MS)
        assertEquals(1, e.repo.runPending())

        val idsAfter = e.sessions().associate { it.episodeKey to it.id }
        assertEquals(idsBefore.keys, idsAfter.keys)
        val changed = idsBefore.filter { (k, id) -> idsAfter[k] != id }.keys
        val rebuiltDays = e.sessions().filter { it.episodeKey in changed }.map { it.logicalDate }.toSet()
        assertEquals(setOf(dirtyDay.toString()), rebuiltDays)

        val insideRow = e.db.derivedDao().dailyRollup(inside)!!
        assertNotEquals(1L, insideRow.baselineMedianMs)
        assertEquals(1L, e.db.derivedDao().dailyRollup(beyond)!!.baselineMedianMs)
    }

    @Test
    fun `baselines appear after 7 covered days and today gets a status`() = runBlocking {
        val e = env()
        e.history(20)
        e.repo.markAllDirty()
        e.repo.runPending()
        val early = e.db.derivedDao().dailyRollup(today.minusDays(18).toString())!!
        assertNull(early.baselineMedianMs)
        assertEquals("INSUFFICIENT_BASELINE", early.dayStatus)
        val later = e.db.derivedDao().dailyRollup(today.minusDays(1).toString())!!
        assertNotNull(later.baselineMedianMs)
        assertNotEquals("INSUFFICIENT_BASELINE", later.dayStatus)
        val cmp = e.repo.todayComparison()!!
        assertEquals(today, cmp.date)
        assertEquals(20, cmp.coveredDays)
        assertNotNull(cmp.slice?.medianMs)
    }

    @Test
    fun `a run killed mid-way resumes from its persisted active range`() = runBlocking {
        val e = env()
        e.history(5)
        e.db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_FROM_MS, at(today.minusDays(2), 5)))
        e.db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_TO_MS, Long.MAX_VALUE))
        assertTrue(e.repo.hasPending())
        assertEquals(3, e.repo.runPending())
        assertFalse(e.repo.hasPending())
        assertEquals(setOf(today.minusDays(2), today.minusDays(1), today).map { it.toString() }.toSet(), e.sessions().map { it.logicalDate }.toSet())
    }

    @Test
    fun `an algorithm version change queues exactly one full rebuild`() = runBlocking {
        val e = env()
        e.history(1)
        assertTrue(e.repo.ensureAlgorithmVersion())
        assertFalse(e.repo.ensureAlgorithmVersion())
        e.repo.runPending()
        assertFalse(e.repo.ensureAlgorithmVersion())
        e.db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.DERIVED_ALGORITHM_VERSION, 0))
        assertTrue(e.repo.ensureAlgorithmVersion())
        assertTrue(e.repo.hasPending())
    }

    @Test
    fun `chunk size never changes the result, even for sessions crossing the rollover`() = runBlocking {
        suspend fun build(chunk: Int): Pair<List<SessionEntity>, List<com.celestiallemon.lagrange.data.db.DailyRollupEntity>> {
            val e = env(chunk)
            e.history(9)
            e.session(at(today.minusDays(4), 3, 50), INSTAGRAM, 30 * MINUTE_MS)
            e.repo.markAllDirty()
            e.repo.runPending()
            return e.sessions().map { it.copy(id = 0) } to e.db.derivedDao().dailyRollupsBetween("0000", "9999")
        }
        val one = build(1)
        val seven = build(7)
        assertEquals(one.first, seven.first)
        assertEquals(one.second, seven.second)
    }

    @Test
    fun `past days keep the budget that applied then`() = runBlocking {
        val e = env()
        e.history(4)
        e.db.settingsHistoryDao().insert(SettingsHistoryEntity(timestampUtcMs = at(today.minusDays(2), 12), settingKey = LagrangePreferences.HISTORY_DAILY_BUDGET_MS, valueJson = "${30 * MINUTE_MS}"))
        settings = settings.copy(dailyBudgetMs = 90 * MINUTE_MS)
        e.repo.markAllDirty()
        e.repo.runPending()
        fun budget(d: LocalDate) = runBlocking { e.db.derivedDao().dailyRollup(d.toString())!!.budgetTargetMs }
        assertEquals(UserPrefs.DEFAULT_DAILY_BUDGET_MS, budget(today.minusDays(3)))
        assertEquals(30 * MINUTE_MS, budget(today.minusDays(2)))
        assertEquals(30 * MINUTE_MS, budget(today.minusDays(1)))
        assertEquals(90 * MINUTE_MS, budget(today))
    }

    @Test
    fun `a day exported as a fixture replays to the stored sessions`() = runBlocking {
        val e = env()
        e.history(2)
        e.repo.markAllDirty()
        e.repo.runPending()
        val day = today.minusDays(1)
        val fixture = DayFixture.decode(e.repo.dayFixture(day).encode())
        assertEquals(day.toString(), fixture.logicalDate)
        assertEquals(zone.id, fixture.zoneId)
        assertEquals(fixture.expected, fixture.replay())
        assertEquals(e.db.derivedDao().sessionsFor(day.toString()).map { it.episodeKey }, fixture.expected.map { it.key })
        assertTrue(fixture.apps.any { it.packageName == INSTAGRAM && it.category == "DISTRACTING" })
    }

    @Test
    fun `a run killed after its last chunk still refreshes the baselines of every rebuilt day`() = runBlocking {
        val e = env()
        e.history(12)
        e.repo.markAllDirty()
        e.repo.runPending()
        val stale = today.minusDays(4).toString()
        val good = e.db.derivedDao().dailyRollup(stale)!!
        e.db.derivedDao().updateBaseline(stale, 1L, "SPIKE")
        // Killed after the final chunk committed: the chunk cursor is past today, the baseline start is not.
        val state = e.db.ingestionStateDao()
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_FROM_MS, at(today.plusDays(1), 5)))
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_TO_MS, Long.MAX_VALUE))
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_BASELINE_FROM_MS, at(today.minusDays(6), 5)))
        assertTrue(e.repo.hasPending())
        e.repo.runPending()
        val refreshed = e.db.derivedDao().dailyRollup(stale)!!
        assertEquals(good.baselineMedianMs, refreshed.baselineMedianMs)
        assertEquals(good.dayStatus, refreshed.dayStatus)
        assertFalse(e.repo.hasPending())
        assertNull(e.state(IngestionKeys.DERIVED_BASELINE_FROM_MS))
    }

    @Test
    fun `a rating follows its episode when late events merge it across a chunk boundary`() = runBlocking {
        val e = env(chunkDays = 1)
        e.history(2)
        val boundary = at(today.minusDays(1), 4)
        e.session(boundary + 5_000, INSTAGRAM, 5 * MINUTE_MS)
        e.repo.markAllDirty()
        e.repo.runPending()
        val rated = e.sessions().single { it.startMs == boundary + 6_000 }
        assertEquals(today.minusDays(1).toString(), rated.logicalDate)
        assertTrue(e.repo.setWorthIt(rated.episodeKey, rated.startMs, 4))

        // A late-arriving pickup just before 04:00 whose screen-off is within the micro-lock merge window.
        e.session(boundary - 12_000, WHATSAPP, 1_000)
        e.repo.markDirty(boundary - HOUR_MS, boundary + HOUR_MS)
        e.repo.runPending()
        val merged = e.sessions().single { it.startMs <= boundary + 6_000 && it.endMs >= boundary + 6_000 }
        assertEquals(boundary - 11_000, merged.startMs)
        assertEquals(today.minusDays(2).toString(), merged.logicalDate)
        assertEquals(4, merged.worthItRating)
        assertEquals(1, e.sessions().count { it.worthItRating != null })
    }

    @Test
    fun `episodes longer than the read margins are stored whole, whatever the chunking`() = runBlocking {
        suspend fun build(e: Env): Pair<List<SessionEntity>, List<com.celestiallemon.lagrange.data.db.DailyRollupEntity>> {
            e.history(4)
            e.session(at(today.minusDays(2), 3, 0), INSTAGRAM, 3 * HOUR_MS)
            e.repo.markAllDirty()
            e.repo.runPending()
            return e.sessions().map { it.copy(id = 0) } to e.db.derivedDao().dailyRollupsBetween("0000", "9999")
        }
        val tight = build(env(chunkDays = 1, lookbackMs = HOUR_MS, lookaheadMs = HOUR_MS))
        val wide = build(env(chunkDays = 7))
        assertEquals(wide.first, tight.first)
        assertEquals(wide.second, tight.second)
        val long = tight.first.single { it.startMs == at(today.minusDays(2), 3, 0) + 1_000 }
        assertEquals(3 * HOUR_MS + 2_000, long.activeDurationMs)
    }

    @Test
    fun `a rating by a stale key lands on the episode containing its start, or reports failure`() = runBlocking {
        val e = env()
        e.history(1)
        e.repo.markAllDirty()
        e.repo.runPending()
        val target = e.sessions().first { !it.isGlanceOnly }
        assertTrue(e.repo.setWorthIt("${target.startMs}:999", target.startMs + 1_000, 5))
        assertEquals(5, e.sessions().single { it.id == target.id }.worthItRating)
        assertFalse(e.repo.setWorthIt("gone", at(today, 3), 1))
    }

    @Test
    fun `listener coverage stops at its last heartbeat`() = runBlocking {
        val e = env()
        e.history(1)
        val listener = e.db.coverageDao().all().single { it.serviceKind == ServiceKind.NOTIFICATION_LISTENER.name }
        e.db.coverageDao().update(listener.copy(lastSeenMs = at(today, 8)))
        e.repo.markAllDirty()
        e.repo.runPending()
        val todays = e.sessions().single { it.logicalDate == today.toString() }
        assertEquals("PARTIAL_COVERAGE", todays.triggerEvidence)
        val yesterdays = e.sessions().first { it.logicalDate == today.minusDays(1).toString() }
        assertEquals("NO_OBSERVED_TRIGGER", yesterdays.triggerEvidence)
    }

    @Test
    fun `the same-time slice never credits a past day's use after the cutoff minute`() = runBlocking {
        val e = env()
        e.setup(at(today.minusDays(10), 0))
        for (i in 10 downTo 1) e.session(at(today.minusDays(i.toLong()), 12, 35), INSTAGRAM, 20 * MINUTE_MS)
        e.session(at(today, 10), INSTAGRAM, 5 * MINUTE_MS)
        e.db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.LAST_HARVEST_AT_MS, at(today, 12, 30)))
        e.repo.markAllDirty()
        e.repo.runPending()
        val cmp = e.repo.todayComparison()!!
        assertEquals(0L, cmp.slice!!.medianMs)
        // Half an hour later the past days' 12:35 sessions are partly in the slice.
        e.db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.LAST_HARVEST_AT_MS, at(today, 12, 45)))
        assertEquals((10 * MINUTE_MS - 2_000).toDouble(), e.repo.todayComparison()!!.slice!!.medianMs!!.toDouble(), 2_000.0)
    }

    @Test
    fun `moving the rollover hour leaves no rows on days that no longer exist`() = runBlocking {
        val e = env()
        e.setup(at(today.minusDays(2), 0))
        e.session(at(today.minusDays(2), 2), WHATSAPP, 10 * MINUTE_MS) // 02:00: the day before under rollover 4
        e.session(at(today.minusDays(1), 9), INSTAGRAM, 10 * MINUTE_MS)
        e.repo.markAllDirty()
        e.repo.runPending()
        val orphan = today.minusDays(3).toString()
        assertNotNull(e.db.derivedDao().dailyRollup(orphan))

        settings = settings.copy(rolloverHour = 0)
        assertTrue(e.repo.hasPending() || e.repo.reconcileSettings())
        e.repo.runPending()
        assertNull(e.db.derivedDao().dailyRollup(orphan))
        assertTrue(e.db.derivedDao().hourlyFor(listOf(orphan)).isEmpty())
        assertEquals(today.minusDays(2).toString(), e.sessions().first().logicalDate)
    }

    @Test
    fun `settings changed while nothing was listening still queue their rebuild`() = runBlocking {
        val e = env()
        e.history(2)
        e.repo.markAllDirty()
        e.repo.runPending()
        assertFalse(e.repo.reconcileSettings())

        settings = settings.copy(dailyBudgetMs = 45 * MINUTE_MS)
        assertTrue(e.repo.reconcileSettings())
        val from = e.state(IngestionKeys.DERIVED_PENDING_FROM_MS)!!
        assertTrue(from > at(today.minusDays(2), 0))
        e.repo.runPending()
        assertEquals(45 * MINUTE_MS, e.db.derivedDao().dailyRollup(today.toString())!!.budgetTargetMs)

        e.db.packageDao().setCategory(WHATSAPP, AppCategory.DISTRACTING.name, now)
        assertTrue(e.repo.reconcileSettings())
        assertEquals(0L, e.state(IngestionKeys.DERIVED_PENDING_FROM_MS))
        e.repo.runPending()
        assertFalse(e.repo.reconcileSettings())

        // A brand-new app showing up is not a recategorization.
        e.interner.packageId("com.example.new")
        assertFalse(e.repo.reconcileSettings())
    }

    private companion object {
        const val INSTAGRAM = "com.instagram.android"
        const val WHATSAPP = "com.whatsapp"
        const val MAPS = "com.google.android.apps.maps"
    }
}
