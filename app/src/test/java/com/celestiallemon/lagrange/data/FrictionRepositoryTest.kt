package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawUsageEventEntity
import com.celestiallemon.lagrange.data.db.UsageEventTypes as T
import com.celestiallemon.lagrange.domain.friction.AppFrictionOverride
import com.celestiallemon.lagrange.domain.friction.FrictionEventRecord
import com.celestiallemon.lagrange.domain.friction.FrictionRuntimeSnapshot
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime
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

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class FrictionRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val db = LagrangeDatabase.inMemory(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val zone = ZoneId.of("UTC")
    private val baseMs = ZonedDateTime.of(2026, 11, 23, 10, 0, 0, 0, zone).toInstant().toEpochMilli()

    private val prefs = LagrangePreferences(
        PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "fp${System.nanoTime()}.preferences_pb") },
        db,
        clock = { baseMs },
    )
    private val interner = Interner(db, FakeResolver())
    private val derived = DerivedRepository(
        db = db,
        settings = { prefs.current().let { DerivedSettings(it.rolloverHour, it.dailyBudgetMs) } },
        clock = { baseMs + 60_000L },
        fallbackZone = { zone },
    )
    private var dirtyCallbacks = 0
    private var bootCount = 1
    private var elapsed = 10_000L

    private val repo = FrictionRepository(
        db = db,
        preferences = prefs,
        interner = interner,
        derived = derived,
        ownPackage = "com.celestiallemon.lagrange",
        onDirty = { dirtyCallbacks++ },
        wallClock = { baseMs + 20_000L },
        elapsedClock = { elapsed },
        bootCountProvider = { bootCount },
    )

    @After
    fun close() {
        scope.cancel()
        db.close()
    }

    @Test
    fun `schedule CRUD persists to Room and records settings history`() = runBlocking {
        val id = repo.createSchedule(
            FrictionScheduleEntity(
                name = "Wind-Down",
                enabled = true,
                daysOfWeekMask = FrictionSchedule.MASK_EVERY_DAY,
                startMinuteOfDay = 22 * 60 + 30,
                endMinuteOfDay = 4 * 60,
                escalationBoost = 1,
                requireTimerPrompt = true,
                nudgeIntervalMin = 10,
            ),
        )
        assertTrue(id > 0)
        assertEquals(1, repo.allSchedules().size)

        repo.setScheduleEnabled(id, false)
        assertFalse(repo.allSchedules().single().enabled)

        repo.updateSchedule(
            FrictionScheduleEntity(
                id = id,
                name = "Late Night",
                enabled = true,
                daysOfWeekMask = FrictionSchedule.MASK_WEEKDAYS,
                startMinuteOfDay = 23 * 60,
                endMinuteOfDay = 3 * 60,
                escalationBoost = 2,
                requireTimerPrompt = true,
                nudgeIntervalMin = 15,
            ),
        )
        val updated = repo.allSchedules().single()
        assertEquals("Late Night", updated.name)
        assertEquals(2, updated.escalationBoost)
        assertTrue(updated.wrapsMidnight)

        repo.deleteSchedule(id)
        assertTrue(repo.allSchedules().isEmpty())

        val history = db.settingsHistoryDao().byKey(FrictionRepository.HISTORY_SCHEDULES)
        assertEquals(4, history.size)
    }

    @Test
    fun `blocked launch pause marks day dirty and SessionReconstructor scrubs it end-to-end`() = runBlocking {
        val redditId = interner.packageId("com.reddit.frontpage")
        val clsId = interner.classId("com.reddit.MainActivity")
        val emptyCls = interner.classId(null)
        val sysId = interner.packageId("")
        db.packageDao().setCategory("com.reddit.frontpage", AppCategory.DISTRACTING.name, baseMs)

        // Raw ledger: unlock at t=0, open Reddit at t=1s, user taps Go Home at t=3s, screen off at t=5s.
        db.rawUsageEventDao().insertAll(
            listOf(
                RawUsageEventEntity(timestampUtcMs = baseMs, eventType = T.SCREEN_INTERACTIVE, pkgId = sysId, clsId = emptyCls),
                RawUsageEventEntity(timestampUtcMs = baseMs + 500, eventType = T.KEYGUARD_HIDDEN, pkgId = sysId, clsId = emptyCls),
                RawUsageEventEntity(timestampUtcMs = baseMs + 1_000, eventType = T.ACTIVITY_RESUMED, pkgId = redditId, clsId = clsId),
                RawUsageEventEntity(timestampUtcMs = baseMs + 3_000, eventType = T.ACTIVITY_PAUSED, pkgId = redditId, clsId = clsId),
                RawUsageEventEntity(timestampUtcMs = baseMs + 5_000, eventType = T.SCREEN_NON_INTERACTIVE, pkgId = sysId, clsId = emptyCls),
            ),
        )

        // Write LAUNCH_PAUSE (CLOSED_GO_HOME) through FrictionRepository -> marks dirty + triggers callback.
        val evId = repo.recordEvent(
            FrictionEventRecord(
                timestampUtcMs = baseMs + 1_100,
                logicalDate = "2026-11-23",
                pkgId = redditId,
                eventKind = FrictionVocab.LAUNCH_PAUSE,
                scheduleId = null,
                escalationTier = 0,
                pauseDurationSec = 3,
                postCountdownReactionMs = null,
                intentionTag = null,
                selectedTimerMin = null,
                redirectPkgId = null,
                outcome = FrictionVocab.OUTCOME_CLOSED_GO_HOME,
            ),
        )
        assertTrue(evId > 0)
        assertEquals(1, dirtyCallbacks)
        assertNotNull(db.ingestionStateDao().get(IngestionKeys.DERIVED_PENDING_FROM_MS))

        // Run derived rebuild -> blocked attempt is scrubbed cleanly!
        derived.runPending()
        val segments = db.derivedDao().segmentsFor("2026-11-23")
        assertEquals(1, segments.size)
        assertTrue(segments.single().isBlockedAttempt)
        assertEquals(0L, segments.single().durationMs)

        val appRollup = db.derivedDao().appRollup("2026-11-23", redditId)!!
        assertEquals(0, appRollup.openCount)
        assertEquals(1, appRollup.blockedAttemptCount)

        // Blocked attempts MUST NOT escalate today's real open count!
        val usageBeforeContinue = repo.todayUsageSnapshot(redditId, baseMs + 10_000)
        assertEquals(0, usageBeforeContinue.appRealOpensToday)
        assertEquals(1, usageBeforeContinue.promptsShownToday)

        // A CONTINUED pause after derivedDataEndMs immediately increments real opens for live escalation.
        repo.recordEvent(
            FrictionEventRecord(
                timestampUtcMs = baseMs + 30_000,
                logicalDate = "2026-11-23",
                pkgId = redditId,
                eventKind = FrictionVocab.LAUNCH_PAUSE,
                scheduleId = null,
                escalationTier = 0,
                pauseDurationSec = 3,
                postCountdownReactionMs = 250L,
                intentionTag = "LOOKUP",
                selectedTimerMin = 5,
                redirectPkgId = null,
                outcome = FrictionVocab.OUTCOME_CONTINUED,
            ),
        )
        val usageAfterContinue = repo.todayUsageSnapshot(redditId, baseMs + 35_000)
        assertEquals(1, usageAfterContinue.appRealOpensToday)
        assertEquals(2, usageAfterContinue.promptsShownToday)
    }

    @Test
    fun `portable friction preferences round-trip without leaking device-local monotonic runtime state`() = runBlocking {
        prefs.setFrictionEnabled(true)
        prefs.setGraceWindowSec(180)
        prefs.setNudgeIntervalMin(20)
        prefs.setDailyPromptCap(15)
        prefs.setRedirectPackageName("com.amazon.kindle")
        prefs.setTierPauseSeconds(2, 4, 7, 11)
        prefs.setTimerPresetsMin(listOf(3, 10, 20))
        prefs.saveFrictionRuntime(FrictionRuntimeSnapshot(bootCount = 1, savedAtElapsedMs = 5_000L, snoozeUntilElapsedMs = 3_605_000L))

        val exported = prefs.exportJson()
        assertFalse("monotonic runtime state must not be exported", exported.contains("snoozeUntilElapsedMs"))

        val other = LagrangePreferences(
            PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "fp2_${System.nanoTime()}.preferences_pb") },
            db,
        )
        other.importJson(exported)
        val p = other.current()
        assertTrue(p.frictionEnabled)
        assertEquals(180, p.graceWindowSec)
        assertEquals(20, p.nudgeIntervalMin)
        assertEquals(15, p.dailyPromptCap)
        assertEquals("com.amazon.kindle", p.redirectPackageName)
        assertEquals(listOf(3, 10, 20), p.timerPresetsMin)
        assertEquals(7, p.tier2PauseSec)
        assertNull(other.loadFrictionRuntime())
    }

    @Test
    fun `per-app override and offline focus boost persist and invalidate after reboot`() = runBlocking {
        val catalog = AppCatalogRepository(db, interner, { listOf("com.reddit.frontpage") }, FakeResolver(), clock = { baseMs })
        catalog.syncLaunchable()
        catalog.setFrictionOverride("com.reddit.frontpage", AppFrictionOverride(enabled = false, fixedPauseSec = 6))
        val policy = repo.resolveAppPolicy("com.reddit.frontpage")
        assertFalse(policy.isFrictionEligible)
        assertEquals(6, policy.override?.fixedPauseSec)

        repo.activateFocusBoost1h(serviceConnected = false)
        assertTrue(repo.liveStatus.value.isFocusBoostActive(elapsed))

        // Simulate reboot (bootCount increments): live status clears on refresh.
        bootCount = 2
        elapsed = 1_000L
        repo.refreshLiveStatusFromDisk()
        assertFalse(repo.liveStatus.value.isFocusBoostActive(elapsed))
    }
}
