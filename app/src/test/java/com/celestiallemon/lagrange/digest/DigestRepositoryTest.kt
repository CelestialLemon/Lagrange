package com.celestiallemon.lagrange.digest

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.data.DerivedRepository
import com.celestiallemon.lagrange.data.DerivedSettings
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.LabRepository
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.IngestionStateEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.domain.MINUTE_MS
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** The digest repository against Room: due-instant anchoring, unobserved tails and unknown coverage. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DigestRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-11-23") // a Monday
    private val now = at(today, 21)
    private val db = LagrangeDatabase.inMemory(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val derived = DerivedRepository(db, settings = { DerivedSettings(4, 60 * MINUTE_MS) }, clock = { now }, fallbackZone = { zone })
    private val prefs = LagrangePreferences(
        PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "digest${System.nanoTime()}.preferences_pb") },
        db,
    )
    private val repo = DigestRepository(db, derived, LabRepository(db, derived, clock = { now }), prefs, clock = { now })

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private suspend fun recordedThrough(ms: Long) {
        db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.DERIVED_DATA_END_MS, ms))
        db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.LAST_HARVEST_AT_MS, ms))
    }

    private suspend fun rollup(date: LocalDate, distractingMin: Long, coverage: String = DayCoverage.COMPLETE.name, status: String = DayStatus.NORMAL.name) =
        db.derivedDao().upsertDailyRollup(
            DailyRollupEntity(
                logicalDate = date.toString(), algorithmVersion = 1, coverageStatus = coverage, notifCoverageRatio = 1.0,
                frictionCoverageRatio = 1.0, totalScreenMs = 2 * distractingMin * MINUTE_MS, activeUsageMs = 2 * distractingMin * MINUTE_MS,
                distractingMs = distractingMin * MINUTE_MS, neutralMs = 0, utilityMs = 0, excludedMs = 0, selfAppMs = 0, episodeCount = 1,
                physicalPickupCount = 1, glanceCount = 0, microCheckCount = 0, rabbitHoleCount = 0, blockedAttemptCount = 0,
                notificationCount = 0, longestFocusGapMs = 90 * MINUTE_MS, overnightGapMs = null, firstPickupMs = null,
                lastActivityMs = null, budgetTargetMs = 60 * MINUTE_MS, baselineMedianMs = null, dayStatus = status,
                spikeSummaryJson = null,
            ),
        )

    @Test
    fun `fresh data gives today so far with a budget verdict`() = runBlocking {
        recordedThrough(now - 5 * MINUTE_MS)
        rollup(today, 20)
        val d = repo.composeDaily(dueAtMs = now)!!
        assertEquals(today.toString(), d.key)
        assertTrue(d.title, d.title.startsWith("Today so far · 20m distracting, within"))
        assertNull(d.coverageNote)
    }

    @Test
    fun `an unobserved tail makes today a lower bound with no verdict`() = runBlocking {
        recordedThrough(at(today, 9, 15)) // e.g. Usage Access revoked after the morning harvest
        rollup(today, 20)
        val d = repo.composeDaily(dueAtMs = now)!!
        assertTrue(d.title, d.title.contains("at least 20m"))
        assertFalse(d.title, d.title.contains("within"))
        assertEquals("Nothing has been recorded since Mon 09:15, so these numbers are lower bounds and aren't compared with your usual.", d.coverageNote)
        assertFalse(d.lines.any { it.contains("phone-free") })
    }

    @Test
    fun `an unknown coverage value is never presented as a whole day`() = runBlocking {
        recordedThrough(now - 5 * MINUTE_MS)
        rollup(today, 20, coverage = "FROM_THE_FUTURE")
        val d = repo.composeDaily(dueAtMs = now)!!
        assertTrue(d.title, d.title.contains("at least 20m"))
        assertNotNull(d.coverageNote)
    }

    @Test
    fun `no rollup means no digest`() = runBlocking {
        recordedThrough(now)
        assertNull(repo.composeDaily(dueAtMs = now))
        assertNull(repo.composeWeekly(dueAtMs = now))
    }

    @Test
    fun `a Sunday weekly run delayed into Monday keeps Sunday's week and range`() = runBlocking {
        recordedThrough(now)
        val sunday = today.minusDays(1)
        (7 downTo 1).forEach { rollup(sunday.minusDays(it.toLong()), 30) }
        rollup(today.minusDays(1), 500) // Sunday itself: outside the week a Sunday run summarizes
        val late = repo.composeWeekly(dueAtMs = at(sunday, 20))!!
        assertEquals("2026-W47", late.key)
        assertTrue(late.title, late.title.startsWith("Your week · 30m a day"))
        // Run on time on Monday's own schedule, the key would be the next week.
        assertEquals("2026-W48", repo.composeWeekly()!!.key)
    }

    @Test
    fun `a due instant ahead of a clock moved back is kept, not pulled to now`() = runBlocking {
        recordedThrough(now)
        val tuesday = today.plusDays(1)
        (7 downTo 1).forEach { rollup(tuesday.minusDays(it.toLong()), 30) }
        // The run was due Tuesday 20:00, but the wall clock now reads Monday 21:00.
        assertEquals("2026-W48", repo.composeWeekly(dueAtMs = at(tuesday, 20))!!.key)
    }

    @Test
    fun `a pending full rebuild never names 1970 as the last recording`() = runBlocking {
        recordedThrough(now)
        db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.DERIVED_PENDING_FROM_MS, 0L))
        db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.DERIVED_PENDING_TO_MS, Long.MAX_VALUE))
        rollup(today, 20)
        val d = repo.composeDaily(dueAtMs = now)!!
        assertTrue(d.title, d.title.contains("at least 20m"))
        assertFalse(d.coverageNote, d.coverageNote!!.contains("Thu"))
    }
}
