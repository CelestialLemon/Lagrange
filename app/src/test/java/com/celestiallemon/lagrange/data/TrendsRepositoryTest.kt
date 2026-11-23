package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.DailyAppRollupEntity
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.FrictionEventEntity
import com.celestiallemon.lagrange.data.db.HourlyAppUsageEntity
import com.celestiallemon.lagrange.data.db.IngestionStateEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.FlowNode
import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.LengthBucket
import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.TrendCalculator
import com.celestiallemon.lagrange.domain.TrendMetric
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.WindowKind
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class TrendsRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    // A DST zone, so the window includes a real 25-hour fall-back day (logical 2026-10-31 with a 04:00 rollover).
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.parse("2026-11-23")
    private val now = LocalDateTime.of(today, LocalTime.of(21, 0)).atZone(zone).toInstant().toEpochMilli()
    private val settings = DerivedSettings(4, 60 * MINUTE_MS)
    private val dbs = ArrayList<LagrangeDatabase>()

    @After
    fun close() = dbs.forEach { it.close() }

    private class Env(val db: LagrangeDatabase, val repo: TrendsRepository, val interner: Interner)

    private fun env(): Env {
        val db = LagrangeDatabase.inMemory(context).also { dbs += it }
        val derived = DerivedRepository(db, settings = { settings }, clock = { now }, fallbackZone = { zone })
        return Env(db, TrendsRepository(db, derived, clock = { now }), Interner(db, FakeResolver()))
    }

    private fun d(offset: Long) = today.plusDays(offset)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private fun rollup(
        date: LocalDate,
        coverage: DayCoverage = DayCoverage.COMPLETE,
        status: DayStatus = DayStatus.NORMAL,
        distracting: Long = 0,
        active: Long = distracting,
        pickups: Int = 0,
        episodes: Int = 0,
    ) = DailyRollupEntity(
        logicalDate = date.toString(), algorithmVersion = 1, coverageStatus = coverage.name, notifCoverageRatio = 1.0,
        frictionCoverageRatio = 1.0, totalScreenMs = active, activeUsageMs = active, distractingMs = distracting, neutralMs = 0,
        utilityMs = 0, excludedMs = 0, selfAppMs = 0, episodeCount = episodes, physicalPickupCount = pickups, glanceCount = 0,
        microCheckCount = 0, rabbitHoleCount = 0, blockedAttemptCount = 0, notificationCount = 0, longestFocusGapMs = 0,
        overnightGapMs = null, firstPickupMs = null, lastActivityMs = null, budgetTargetMs = 60 * MINUTE_MS,
        baselineMedianMs = null, dayStatus = status.name, spikeSummaryJson = null,
    )

    private suspend fun Env.rollups(vararg rows: DailyRollupEntity) = rows.forEach { db.derivedDao().upsertDailyRollup(it) }

    private suspend fun Env.appDay(date: LocalDate, pkg: Long, ms: Long, opens: Int) =
        db.derivedDao().upsertDailyAppRollup(DailyAppRollupEntity(date.toString(), pkg, ms, opens, 0, opens, 0))

    private suspend fun Env.hour(date: LocalDate, slot: Int, pkg: Long, ms: Long, opens: Int) =
        db.derivedDao().upsertHourly(HourlyAppUsageEntity(date.toString(), slot, pkg, ms, opens))

    private var episode = 0

    /** An unlocked episode of consecutive segments `(pkg, ms, blocked)`. */
    private suspend fun Env.session(
        date: LocalDate,
        startMs: Long,
        parts: List<Triple<Long, Long, Boolean>>,
        trigger: TriggerEvidence = TriggerEvidence.NO_OBSERVED_TRIGGER,
        rating: Int? = null,
        glance: Boolean = false,
        segEvidence: Map<Int, TriggerEvidence> = emptyMap(),
    ): Long {
        val total = parts.sumOf { if (it.third) 0 else it.second }
        val end = startMs + parts.sumOf { it.second }
        val id = db.derivedDao().insertSession(
            SessionEntity(
                episodeKey = "e${episode++}", algorithmVersion = 1, logicalDate = date.toString(), startMs = startMs, endMs = end,
                activeDurationMs = total, physicalPickupCount = 1, isGlanceOnly = glance, triggerEvidence = trigger.name,
                triggerPkgId = null, triggerNotificationId = null, entryPkgId = parts.firstOrNull { !it.third }?.first,
                totalDistractingMs = total, totalNeutralMs = 0, totalUtilityMs = 0, totalExcludedMs = 0, blockedAttemptCount = 0,
                distinctAppCount = 1, distinctDistractingCount = 1, archetype = "FOCUSED", isRabbitHole = false,
                rabbitHoleReasons = null, worthItRating = rating, contextFlags = null,
            ),
        )
        var t = startMs
        parts.forEachIndexed { i, (pkg, ms, blocked) ->
            db.derivedDao().insertSegment(
                SessionSegmentEntity(
                    sessionId = id, seqIndex = i, pkgId = pkg, startMs = t, endMs = t + ms, durationMs = if (blocked) 0 else ms,
                    isBlockedAttempt = blocked, segmentTriggerEvidence = segEvidence[i]?.name, segmentTriggerPkgId = null,
                ),
            )
            t += ms
        }
        return id
    }

    private suspend fun Env.pkg(name: String, category: AppCategory = AppCategory.DISTRACTING): Long {
        val id = interner.packageId(name)
        db.packageDao().setCategory(name, category.name, now)
        return id
    }

    private fun friction(ts: Long, pkg: Long, outcome: String) = FrictionEventEntity(
        timestampUtcMs = ts, logicalDate = "", pkgId = pkg, eventKind = "LAUNCH_PAUSE", scheduleId = null, escalationTier = 0,
        pauseDurationSec = 5, postCountdownReactionMs = null, intentionTag = null, selectedTimerMin = null, redirectPkgId = null,
        outcome = outcome,
    )

    // -- Trends dataset ----------------------------------------------------------------------------------------------

    @Test
    fun `trend data classifies every day and reads self-report per day`() = runBlocking {
        val e = env()
        e.pkg(REDDIT)
        e.rollups(
            rollup(d(-1), distracting = 30 * MINUTE_MS, pickups = 10, episodes = 8),
            rollup(d(-2), DayCoverage.PARTIAL_HARVEST, DayStatus.UNCERTAIN_COVERAGE, distracting = 5 * MINUTE_MS),
            rollup(d(-3), DayCoverage.BACKFILL, DayStatus.INSUFFICIENT_BASELINE, distracting = 20 * MINUTE_MS),
            rollup(d(-4), DayCoverage.NO_NOTIF_COVERAGE, DayStatus.SPIKE, distracting = 0),
            rollup(d(-5)).copy(coverageStatus = "FROM_THE_FUTURE"),
            rollup(today, distracting = 10 * MINUTE_MS),
        )
        val data = e.repo.trendData(TrendWindow(WindowKind.D7))
        assertEquals(today, data.today)
        assertEquals(d(-5), data.earliest)
        assertEquals(TrendCalculator.loadRange(data.range).days, data.days.size)
        assertEquals(DayQuality.COVERED, data.days[d(-1)]!!.quality)
        assertEquals(DayQuality.PARTIAL, data.days[d(-2)]!!.quality)
        assertEquals(DayQuality.BACKFILL, data.days[d(-3)]!!.quality)
        assertEquals(DayQuality.COVERED, data.days[d(-4)]!!.quality)
        assertEquals(DayQuality.PARTIAL, data.days[d(-5)]!!.quality) // unknown vocabulary is never trusted
        assertEquals(DayQuality.NO_DATA, data.days[d(-6)]!!.quality)
        assertEquals(DayQuality.IN_PROGRESS, data.days[today]!!.quality)
        assertEquals(3, data.coveredDaysIn(data.range))
        assertEquals(10, data.days[d(-1)]!!.pickups)
        assertEquals(60 * MINUTE_MS, data.days[d(-1)]!!.budgetMs)

        val r = TrendCalculator.report(TrendMetric.DISTRACTING, data.window, data.range, data.days)
        assertEquals((30 + 20 + 0) / 3.0 * MINUTE_MS, r.coveredMean!!, 1e-6)
    }

    @Test
    fun `self-report buckets ratings and skips glances`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        e.rollups(rollup(d(-1)))
        val day = d(-1)
        fun one(ms: Long) = listOf(Triple(reddit, ms, false))
        e.session(day, at(day, 9), one(10 * MINUTE_MS), rating = 5)
        e.session(day, at(day, 10), one(5 * MINUTE_MS), rating = 4)
        e.session(day, at(day, 11), one(7 * MINUTE_MS), rating = 1)
        e.session(day, at(day, 12), one(3 * MINUTE_MS), rating = 3)
        e.session(day, at(day, 13), one(2 * MINUTE_MS), rating = null)
        e.session(day, at(day, 14), emptyList(), rating = 5, glance = true)
        val w = e.repo.trendData(TrendWindow(WindowKind.D7)).days[day]!!.worth
        assertEquals(15 * MINUTE_MS, w.valuedMs)
        assertEquals(2, w.valuedCount)
        assertEquals(7 * MINUTE_MS, w.regrettedMs)
        assertEquals(3 * MINUTE_MS, w.neutralMs)
        assertEquals(2 * MINUTE_MS, w.unratedMs)
        assertEquals(1, w.unratedCount)
    }

    // -- Apps breakdown ----------------------------------------------------------------------------------------------

    @Test
    fun `apps average over covered days only and skip system surfaces`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        val maps = e.pkg(MAPS, AppCategory.UTILITY)
        val sysui = e.interner.packageId("com.android.systemui")
        // Current 7 days: four covered (one without Reddit = a real zero), one partial, today.
        e.rollups(
            rollup(d(-1)), rollup(d(-2)), rollup(d(-3)), rollup(d(-4)),
            rollup(d(-5), DayCoverage.PARTIAL_HARVEST, DayStatus.UNCERTAIN_COVERAGE),
            rollup(today),
        )
        e.appDay(d(-1), reddit, 40 * MINUTE_MS, 4)
        e.appDay(d(-2), reddit, 20 * MINUTE_MS, 2)
        e.appDay(d(-3), reddit, 20 * MINUTE_MS, 2)
        e.appDay(d(-5), reddit, 99 * MINUTE_MS, 9)
        e.appDay(today, reddit, 11 * MINUTE_MS, 1)
        e.appDay(d(-1), maps, 5 * MINUTE_MS, 1)
        e.appDay(d(-1), sysui, HOUR_MS, 30)
        // Previous 7 days: four covered at 10 minutes.
        for (i in 8L..11) {
            e.rollups(rollup(d(-i)))
            e.appDay(d(-i), reddit, 10 * MINUTE_MS, 1)
        }
        val data = e.repo.trendData(TrendWindow(WindowKind.D7))
        val rows = e.repo.apps(data).associateBy { it.meta.pkgId }
        assertEquals(setOf(reddit, maps), rows.keys)
        val r = rows.getValue(reddit)
        assertEquals((40 + 20 + 20 + 99 + 11) * MINUTE_MS, r.totalMs)
        assertEquals(18, r.opens)
        assertEquals(20.0 * MINUTE_MS, r.dailyAvgMs!!, 1e-6) // 80 minutes over 4 covered days
        assertEquals(10.0 * MINUTE_MS, r.previousAvgMs!!, 1e-6)
        assertEquals(1.0, r.trendPct!!, 1e-9)
        assertNull(rows.getValue(maps).trendPct) // nothing before to compare with

        val allTime = e.repo.apps(e.repo.trendData(TrendWindow(WindowKind.ALL)))
        assertTrue(allTime.all { it.trendPct == null })
    }

    // -- Deep dive ---------------------------------------------------------------------------------------------------

    @Test
    fun `deep dive draws covered zeros, folds the repeated hour and analyses sessions`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        val chrome = e.pkg(CHROME, AppCategory.NEUTRAL)
        val fallBack = LocalDate.parse("2026-10-31")
        e.rollups(
            rollup(d(-1)), rollup(d(-2)), rollup(d(-3), DayCoverage.PARTIAL_HARVEST, DayStatus.UNCERTAIN_COVERAGE),
            rollup(fallBack), rollup(today),
        )
        e.appDay(d(-1), reddit, 30 * MINUTE_MS, 3)
        e.appDay(d(-3), reddit, 15 * MINUTE_MS, 1)
        e.appDay(fallBack, reddit, 90 * MINUTE_MS, 3)
        // Hourly: 20:00 slot (16) on d(-1); on the fall-back day, 01:00 first pass (slot 21) and the repeated hour (24).
        e.hour(d(-1), 16, reddit, 30 * MINUTE_MS, 3)
        e.hour(d(-3), 16, reddit, 15 * MINUTE_MS, 1) // partial: left out of the profile
        e.hour(fallBack, 21, reddit, 45 * MINUTE_MS, 2)
        e.hour(fallBack, 24, reddit, 45 * MINUTE_MS, 1)
        e.hour(today, 10, reddit, 5 * MINUTE_MS, 1) // in progress: left out

        val day = d(-1)
        // Notification-led start, then Chrome; then a 30s-later reopen from idle; then Chrome -> Reddit via a shade tap.
        val t0 = at(day, 20)
        e.session(day, t0, listOf(Triple(reddit, 10 * MINUTE_MS, false), Triple(chrome, 2 * MINUTE_MS, false)), TriggerEvidence.OBSERVED_CLICK, rating = 2)
        val t1 = t0 + 12 * MINUTE_MS + 30_000
        e.session(day, t1, listOf(Triple(reddit, 30_000L, false)), rating = 4)
        e.session(
            day, at(day, 22),
            listOf(Triple(chrome, MINUTE_MS, false), Triple(reddit, 20 * MINUTE_MS, false)),
            segEvidence = mapOf(1 to TriggerEvidence.OBSERVED_CLICK),
        )
        // A blocked attempt only.
        e.session(day, at(day, 23), listOf(Triple(reddit, 4_000L, true)))
        e.db.derivedDao().insertFrictionEvent(
            FrictionEventEntity(
                timestampUtcMs = at(day, 23), logicalDate = day.toString(), pkgId = reddit, eventKind = "LAUNCH_PAUSE",
                scheduleId = null, escalationTier = 0, pauseDurationSec = 5, postCountdownReactionMs = null, intentionTag = null,
                selectedTimerMin = null, redirectPkgId = null, outcome = "CLOSED_GO_HOME",
            ),
        )

        val dive = e.repo.deepDive(reddit, TrendWindow(WindowKind.D30))!!
        assertEquals(30, dive.points.size)
        val byDate = dive.points.associateBy { it.date }
        assertEquals(30.0 * MINUTE_MS, byDate.getValue(d(-1)).sample.value!!, 0.0)
        assertEquals(0.0, byDate.getValue(d(-2)).sample.value!!, 0.0) // covered day without the app: a real zero
        assertEquals(DayQuality.PARTIAL, byDate.getValue(d(-3)).sample.quality)
        assertNull(byDate.getValue(d(-4)).sample.value) // no data
        assertEquals(3, dive.coveredDays)
        assertEquals(1, dive.partialDays)
        assertEquals((30 + 15 + 90) * MINUTE_MS, dive.observedTotalMs)
        assertEquals(7, dive.observedOpens)
        assertNull(byDate.getValue(d(-1)).reference) // no goal yet

        // Profile: slot 21 (01:00) holds both passes of the repeated hour, averaged over 3 covered days.
        val p = dive.profile
        assertEquals(3, p.coveredDays)
        assertEquals(90.0 * MINUTE_MS / 3, p.bins[21].avgMs, 1e-6)
        assertEquals(1, p.bins[21].wallHour)
        assertEquals(10.0 * MINUTE_MS, p.bins[16].avgMs, 1e-6)
        assertEquals(0.0, p.bins[6].avgMs, 0.0) // today's 10:00 is excluded
        assertEquals(0L, p.unplacedRepeatedMs)
        assertEquals(21, p.peak!!.slot)

        val s = dive.insights
        assertEquals(3, s.shape.runs)
        assertEquals(1, s.shape.counts[LengthBucket.UNDER_1M])
        assertEquals(1, s.shape.counts[LengthBucket.M5_15])
        assertEquals(1, s.shape.counts[LengthBucket.M15_30])
        assertEquals(0, s.shape.reopenLoops) // left Reddit for Chrome, came back 2.5 minutes after leaving Reddit
        val inflow = s.flows.inflow.associate { it.node to it.count }
        assertEquals(1, inflow[FlowNode.NotificationStart])
        assertEquals(1, inflow[FlowNode.SessionStart])
        assertEquals(1, inflow[FlowNode.App(chrome)])
        val outflow = s.flows.outflow.associate { it.node to it.count }
        assertEquals(1, outflow[FlowNode.App(chrome)])
        assertEquals(2, outflow[FlowNode.SessionEnd])
        assertEquals("Label($CHROME)", dive.names[chrome])
        assertEquals(mapOf(TriggerEvidence.OBSERVED_CLICK to 1, TriggerEvidence.NO_OBSERVED_TRIGGER to 1), s.triggers.episodeStarts)
        assertEquals(mapOf(TriggerEvidence.OBSERVED_CLICK to 1), s.triggers.midSession)
        assertEquals(3, s.value.episodes)
        assertEquals(3.0, s.value.averageRating!!, 1e-9)
        assertEquals(1, s.friction.launchPauses)
        assertEquals(1, s.friction.immediateExits)
        assertEquals(0, s.friction.quickReopens)
        assertEquals(4, dive.notifCoveredDays) // today counts: its sessions are in the evidence too
        assertEquals(5, dive.dataDays)
    }

    @Test
    fun `a missing package has no deep dive`() = runBlocking {
        assertNull(env().repo.deepDive(424242, TrendWindow.DEFAULT))
    }

    // -- Goals -------------------------------------------------------------------------------------------------------

    @Test
    fun `setting a goal persists it, logs it once and draws it on the deep dive`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        e.rollups(rollup(d(-1)))
        e.repo.setDailyGoal(reddit, 30 * MINUTE_MS)
        e.repo.setDailyGoal(reddit, 30 * MINUTE_MS) // unchanged: no second history row
        assertEquals(30 * MINUTE_MS, e.db.analyticsDao().observePackage(reddit).first()!!.dailyGoalMs)
        e.repo.setDailyGoal(reddit, null)
        assertNull(e.db.analyticsDao().packageById(reddit)!!.dailyGoalMs)
        val history = e.db.settingsHistoryDao().all().filter { it.settingKey == TrendsRepository.goalHistoryKey(REDDIT) }
        assertEquals(listOf((30 * MINUTE_MS).toString(), "null"), history.map { it.valueJson })
        assertTrue(history.all { it.timestampUtcMs == now })

        e.repo.setDailyGoal(reddit, 45 * MINUTE_MS)
        val dive = e.repo.deepDive(reddit, TrendWindow(WindowKind.D7))!!
        assertEquals(45 * MINUTE_MS, dive.meta.dailyGoalMs)
        assertEquals(45.0 * MINUTE_MS, dive.points.first().reference!!, 0.0)
    }

    @Test
    fun `goals outside one minute to a day are rejected and unknown apps ignored`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        for (bad in listOf(0L, 59_999L, 24 * HOUR_MS + 1)) {
            try {
                e.repo.setDailyGoal(reddit, bad)
                fail("accepted $bad")
            } catch (_: IllegalArgumentException) {
            }
        }
        e.repo.setDailyGoal(999, 30 * MINUTE_MS)
        assertTrue(e.db.settingsHistoryDao().all().none { it.settingKey.startsWith("app_daily_goal_ms") })
        assertNull(e.db.analyticsDao().packageById(reddit)!!.dailyGoalMs)
    }

    // -- Review regressions --------------------------------------------------------------------------------------------

    @Test
    fun `a day the last harvest hasn't finished stays in progress even after rollover`() = runBlocking {
        val e = env()
        // The last harvest ran yesterday evening: yesterday's rollup is complete as far as it goes, but not settled.
        e.db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.LAST_HARVEST_AT_MS, at(d(-1), 22)))
        e.rollups(rollup(d(-2), distracting = 10 * MINUTE_MS), rollup(d(-1), distracting = 5 * MINUTE_MS))
        val data = e.repo.trendData(TrendWindow(WindowKind.D7))
        assertEquals(d(-1), data.firstUnsettled)
        val report = TrendCalculator.report(TrendMetric.DISTRACTING, data.window, data.range, data.days)
        val byDate = report.points.associateBy { it.date }
        assertEquals(DayQuality.COVERED, byDate.getValue(d(-2)).sample.quality)
        assertEquals(DayQuality.IN_PROGRESS, byDate.getValue(d(-1)).sample.quality)
        assertEquals(1, report.coveredDays)
    }

    @Test
    fun `deep dive opens count in the window they happened, not their episode's day`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        val chrome = e.pkg(CHROME, AppCategory.NEUTRAL)
        val first = d(-29) // first day of a 30-day window
        // An episode of the day before the window (03:30 is before the 04:00 rollover) reaching Reddit inside it.
        e.session(first.minusDays(1), at(first, 3, 30), listOf(Triple(chrome, 40 * MINUTE_MS, false), Triple(reddit, MINUTE_MS, false)))
        // One entirely before the window.
        e.session(first.minusDays(1), at(first, 2, 0), listOf(Triple(reddit, MINUTE_MS, false)))
        val dive = e.repo.deepDive(reddit, TrendWindow(WindowKind.D30))!!
        assertEquals(1, dive.insights.shape.runs)
        assertEquals(1, dive.insights.triggers.total)
    }

    @Test
    fun `an unknown coverage label never counts as covered`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        e.rollups(rollup(d(-1)), rollup(d(-2)).copy(coverageStatus = "SOMETHING_NEWER"))
        e.appDay(d(-1), reddit, 20 * MINUTE_MS, 2)
        e.appDay(d(-2), reddit, 50 * MINUTE_MS, 5)
        val data = e.repo.trendData(TrendWindow(WindowKind.D7))
        val row = e.repo.apps(data).single { it.meta.pkgId == reddit }
        assertEquals(70 * MINUTE_MS, row.totalMs)
        assertEquals(20.0 * MINUTE_MS, row.dailyAvgMs!!, 1e-6) // only d(-1) is covered
    }

    @Test
    fun `an app with a goal but no use in the window is still listed`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        e.rollups(rollup(d(-1)))
        e.repo.setDailyGoal(reddit, 30 * MINUTE_MS)
        val rows = e.repo.apps(e.repo.trendData(TrendWindow(WindowKind.D7)))
        assertEquals(listOf(reddit), rows.map { it.meta.pkgId })
        assertEquals(0L, rows.single().totalMs)
    }

    // -- Refresh signal ----------------------------------------------------------------------------------------------

    @Test
    fun `user signals change when an episode is rated or friction is logged`() = runBlocking {
        val e = env()
        val reddit = e.pkg(REDDIT)
        val dao = e.db.analyticsDao()
        e.session(d(-1), at(d(-1), 9), listOf(Triple(reddit, MINUTE_MS, false)))
        val unrated = dao.observeUserSignals().first()
        assertEquals(1, e.db.derivedDao().setRatingByKey("e${episode - 1}", 4))
        val rated = dao.observeUserSignals().first()
        assertFalse(unrated == rated)
        e.db.derivedDao().setRatingByKey("e${episode - 1}", 2)
        val rerated = dao.observeUserSignals().first()
        assertFalse(rated == rerated)
        e.db.derivedDao().insertFrictionEvent(friction(at(d(-1), 10), reddit, "CLOSED_GO_HOME"))
        assertFalse(rerated == dao.observeUserSignals().first())
    }

    private companion object {
        const val REDDIT = "com.reddit.frontpage"
        const val CHROME = "com.android.chrome"
        const val MAPS = "com.google.android.apps.maps"
    }
}
