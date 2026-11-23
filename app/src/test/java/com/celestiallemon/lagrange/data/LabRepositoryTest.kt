package com.celestiallemon.lagrange.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageIntervalEntity
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.DailyAppRollupEntity
import com.celestiallemon.lagrange.data.db.DailyNoteEntity
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.FrictionEventEntity
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.HourlyAppUsageEntity
import com.celestiallemon.lagrange.data.db.IngestionStateEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.RawNotificationEntity
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.domain.lab.NotificationSettingsTarget
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class LabRepositoryTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.parse("2026-11-23")
    private val now = at(today, 21)
    private val dbs = ArrayList<LagrangeDatabase>()

    @After
    fun close() = dbs.forEach { it.close() }

    private class MemoryStore : CheckInStore {
        val state = MutableStateFlow<String?>(null)
        var writes = 0
        override val changes: Flow<String?> = state
        override suspend fun update(transform: (String?) -> String?) {
            val next = transform(state.value)
            if (next != state.value) writes++
            state.value = next
        }
    }

    private class Env(val db: LagrangeDatabase, val repo: LabRepository, val interner: Interner, val store: MemoryStore)

    private fun env(): Env = runBlocking {
        val db = LagrangeDatabase.inMemory(context).also { dbs += it }
        val derived = DerivedRepository(db, settings = { DerivedSettings(4, 60 * MINUTE_MS) }, clock = { now }, fallbackZone = { zone })
        val store = MemoryStore()
        db.ingestionStateDao().put(IngestionStateEntity(IngestionKeys.DERIVED_DATA_END_MS, now))
        Env(db, LabRepository(db, derived, store, clock = { now }), Interner(db, FakeResolver()), store)
    }

    private fun d(offset: Long) = today.plusDays(offset)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(date, LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private suspend fun Env.pkg(name: String, category: AppCategory = AppCategory.DISTRACTING): Long {
        val id = interner.packageId(name)
        db.packageDao().setCategory(name, category.name, now)
        return id
    }

    private suspend fun Env.rollup(date: LocalDate, activeMin: Long = 60, overnightGapMs: Long? = null, firstPickupMs: Long? = null) =
        db.derivedDao().upsertDailyRollup(
            DailyRollupEntity(
                logicalDate = date.toString(), algorithmVersion = 1, coverageStatus = DayCoverage.COMPLETE.name, notifCoverageRatio = 1.0,
                frictionCoverageRatio = 1.0, totalScreenMs = activeMin * MINUTE_MS, activeUsageMs = activeMin * MINUTE_MS,
                distractingMs = activeMin * MINUTE_MS, neutralMs = 0, utilityMs = 0, excludedMs = 0, selfAppMs = 0, episodeCount = 1,
                physicalPickupCount = 1, glanceCount = 0, microCheckCount = 0, rabbitHoleCount = 0, blockedAttemptCount = 0,
                notificationCount = 0, longestFocusGapMs = 0, overnightGapMs = overnightGapMs, firstPickupMs = firstPickupMs,
                lastActivityMs = null, budgetTargetMs = 30 * MINUTE_MS, baselineMedianMs = null, dayStatus = DayStatus.NORMAL.name,
                spikeSummaryJson = null,
            ),
        )

    private var keys = 0

    private suspend fun Env.session(
        date: LocalDate,
        startMs: Long,
        pkg: Long,
        minutes: Long,
        rabbit: Boolean = false,
        trigger: TriggerEvidence = TriggerEvidence.NO_OBSERVED_TRIGGER,
        triggerNotificationId: Long? = null,
        rating: Int? = null,
        flags: String? = null,
    ): String {
        val key = "k${keys++}"
        val ms = minutes * MINUTE_MS
        val id = db.derivedDao().insertSession(
            SessionEntity(
                episodeKey = key, algorithmVersion = 1, logicalDate = date.toString(), startMs = startMs, endMs = startMs + ms,
                activeDurationMs = ms, physicalPickupCount = 1, isGlanceOnly = false, triggerEvidence = trigger.name,
                triggerPkgId = null, triggerNotificationId = triggerNotificationId, entryPkgId = pkg, totalDistractingMs = ms,
                totalNeutralMs = 0, totalUtilityMs = 0, totalExcludedMs = 0, blockedAttemptCount = 0, distinctAppCount = 1,
                distinctDistractingCount = 1, archetype = "FOCUSED", isRabbitHole = rabbit,
                rabbitHoleReasons = if (rabbit) "${RabbitHoleReason.LONG_BINGE.name},BOGUS" else null, worthItRating = rating, contextFlags = flags,
            ),
        )
        db.derivedDao().insertSegment(
            SessionSegmentEntity(
                sessionId = id, seqIndex = 0, pkgId = pkg, startMs = startMs, endMs = startMs + ms, durationMs = ms,
                isBlockedAttempt = false, segmentTriggerEvidence = null, segmentTriggerPkgId = null,
            ),
        )
        return key
    }

    private suspend fun Env.coverage(kind: ServiceKind, from: Long, to: Long, status: CoverageStatus = CoverageStatus.ACTIVE) =
        db.coverageDao().insert(CoverageIntervalEntity(serviceKind = kind.name, startMs = from, endMs = to, status = status.name, lastSeenMs = to))

    private suspend fun Env.notification(pkg: Long, posted: Long, removed: Long? = null, reason: RemovalReason = RemovalReason.ACTIVE, channel: String? = "dm", ongoing: Boolean = false) =
        db.rawNotificationDao().insert(
            RawNotificationEntity(
                sbnKeyHash = "h$posted", pkgId = pkg, channelId = channel, androidCategory = null, groupKeyHash = null, postedAtMs = posted,
                lastUpdatedAtMs = posted, removedAtMs = removed, removalReason = reason.name, importance = 4, matchedInterruptionFilter = true,
                isOngoing = ongoing, isForegroundService = false, isGroupSummary = false, isAmbient = false, isConversation = false,
                isOnlyAlertOnce = false, dndOrSilentAtPost = false,
            ),
        )

    private fun friction(ts: Long, pkg: Long, outcome: String) = FrictionEventEntity(
        timestampUtcMs = ts, logicalDate = "", pkgId = pkg, eventKind = FrictionVocab.LAUNCH_PAUSE, scheduleId = null, escalationTier = 0,
        pauseDurationSec = 5, postCountdownReactionMs = null, intentionTag = null, selectedTimerMin = null, redirectPkgId = null,
        outcome = outcome,
    )

    @Test
    fun `spikes and the autopsy read settled rollups, per-app totals and the day note`() = runBlocking {
        val e = env()
        val insta = e.pkg("com.instagram.android")
        for (i in 2L..29) {
            e.rollup(d(-i))
            e.db.derivedDao().upsertDailyAppRollup(DailyAppRollupEntity(d(-i).toString(), insta, 60 * MINUTE_MS, 2, 0, 2, 0))
        }
        val spike = d(-1)
        e.rollup(spike, activeMin = 150)
        e.db.derivedDao().upsertDailyAppRollup(DailyAppRollupEntity(spike.toString(), insta, 150 * MINUTE_MS, 6, 0, 6, 0))
        e.db.derivedDao().upsertHourly(HourlyAppUsageEntity(spike.toString(), 18, insta, 150 * MINUTE_MS, 6))
        e.db.derivedDao().upsertNote(DailyNoteEntity(spike.toString(), "Long train ride", now))

        val report = e.repo.spikes(TrendWindow(WindowKind.D7))
        val h = report.history.single { it.date == spike }
        assertEquals(DayStatus.SPIKE, h.status)
        assertEquals(60 * MINUTE_MS, h.baselineMs)
        assertTrue(report.baselineDays >= report.baselineDaysNeeded)

        val autopsy = e.repo.autopsy(spike)!!
        assertEquals(insta, autopsy.appDeltas.single().pkgId)
        assertEquals(90 * MINUTE_MS, autopsy.appDeltas.single().deltaMs)
        assertEquals("Long train ride", autopsy.note)
        // An ordinary day has no autopsy.
        assertNull(e.repo.autopsy(d(-2)))
    }

    @Test
    fun `rabbit holes load with their segments and the check-in round-trips through the store`() = runBlocking {
        val e = env()
        val insta = e.pkg("com.instagram.android")
        e.rollup(d(-1))
        val older = e.session(d(-1), at(d(-1), 12), insta, 30, rabbit = true)
        val latest = e.session(d(-1), at(d(-1), 20), insta, 25, rabbit = true)
        val rated = e.session(today, at(today, 8), insta, 40, rabbit = true, rating = 2)
        e.session(today, at(today, 9), insta, 5)
        e.session(today, at(today, 20, 30), insta, 30, rabbit = true, flags = "IN_PROGRESS")

        val holes = e.repo.rabbitHoles(TrendWindow(WindowKind.D7)).episodes
        assertEquals(setOf(older, latest, rated, "k4"), holes.map { it.episodeKey }.toSet())
        val one = holes.first { it.episodeKey == latest }
        assertEquals(listOf(RabbitHoleReason.LONG_BINGE), one.rabbitHoleReasons) // unknown names are dropped
        assertEquals(25 * MINUTE_MS, one.segments.single().durationMs)
        assertTrue(holes.first { it.episodeKey == "k4" }.inProgress)

        val card = e.repo.checkIn()!!
        assertEquals(latest, card.episode.episodeKey)
        // The card knows when it stops being offered, so the UI can re-sample right then instead of every minute.
        assertEquals(card.episode.endMs + 36 * 60 * MINUTE_MS, card.expiresAtMs)
        val log = LabRepository.decodeLog(e.store.state.value)
        assertEquals(listOf(latest), log.shown)
        assertEquals(today.toString(), log.date)
        // Asking again offers the same one and doesn't rewrite the log.
        val writes = e.store.writes
        assertEquals(latest, e.repo.checkIn()!!.episode.episodeKey)
        assertEquals(writes, e.store.writes)
        // "Not now" → the next unrated one; that's the second of the day, so after it nothing more.
        e.repo.dismissCheckIn(latest)
        assertEquals(older, e.repo.checkIn()!!.episode.episodeKey)
        e.db.derivedDao().setRatingByKey(older, 4)
        assertNull(e.repo.checkIn())

        val (session, segments) = e.repo.session(latest)!!
        assertEquals(latest, session.episodeKey)
        assertEquals(1, segments.size)
        assertNull(e.repo.session("missing"))
    }

    @Test
    fun `gateways rank entries from covered days only and keep small samples apart`() = runBlocking {
        val e = env()
        val insta = e.pkg("com.instagram.android")
        val tiktok = e.pkg("com.zhiliaoapp.musically")
        e.rollup(d(-1))
        e.rollup(d(-2))
        listOf(9, 11, 13).forEach { e.session(d(-1), at(d(-1), it), insta, 10, rabbit = it == 9) }
        listOf(9, 11).forEach { e.session(d(-2), at(d(-2), it), insta, 10, rabbit = it == 11) }
        listOf(15, 17).forEach { e.session(d(-2), at(d(-2), it), tiktok, 10) }
        // A day without a rollup isn't covered: its spirals never reach the rates.
        e.session(d(-3), at(d(-3), 10), insta, 40, rabbit = true)

        val r = e.repo.gateways(TrendWindow(WindowKind.D7)).report
        assertEquals(7, r.totalEntries)
        assertEquals(2, r.totalSpirals)
        assertEquals(2.0 / 7, r.baseRate, 1e-9)
        val top = r.ranked.single()
        assertEquals(insta, top.pkgId)
        assertEquals(5, top.entries)
        assertEquals(2, top.spirals)
        assertEquals(listOf(tiktok), r.lowSample.map { it.pkgId })
    }

    @Test
    fun `a corrupt check-in log starts over instead of failing`() {
        assertEquals(LabRepository.decodeLog(null), LabRepository.decodeLog("{not json"))
        val log = LabRepository.decodeLog(null).copy(date = "2026-11-23", shown = listOf("a"), dismissed = listOf("b"))
        assertEquals(log, LabRepository.decodeLog(LabRepository.encodeLog(log)))
    }

    @Test
    fun `notification cost only counts what the listener saw and credits the session it opened`() = runBlocking {
        val e = env()
        val insta = e.pkg("com.instagram.android")
        val day = d(-1)
        e.rollup(day)
        e.coverage(ServiceKind.NOTIFICATION_LISTENER, at(day, 8), at(day, 20))
        val tapped = e.notification(insta, at(day, 10), removed = at(day, 10) + 5_000, reason = RemovalReason.CLICK)
        e.notification(insta, at(day, 11), ongoing = true)
        e.notification(insta, at(day, 21), channel = "conversation:1f") // listener was off
        e.session(day, at(day, 10) + 5_000, insta, 12, trigger = TriggerEvidence.OBSERVED_CLICK, triggerNotificationId = tapped)

        val r = e.repo.notificationCost(TrendWindow(WindowKind.D7)).report
        assertEquals(1, r.totalVolume)
        val row = r.rows.single()
        assertEquals(1, row.clicks)
        assertEquals(12 * MINUTE_MS, row.downstreamMs)
        assertEquals(NotificationSettingsTarget.Channel("com.instagram.android", "dm"), row.channels.single().target)
        assertEquals(12 * 60 * MINUTE_MS, r.coveredMs)
        assertNotNull(row.lift.needMoreData)
    }

    @Test
    fun `friction exits use run starts, usage coverage and the enabled history`() = runBlocking {
        val e = env()
        val insta = e.pkg("com.instagram.android")
        val day = d(-1)
        e.rollup(day)
        e.coverage(ServiceKind.USAGE_HARVEST, at(d(-3), 0), now)
        e.db.settingsHistoryDao().insert(SettingsHistoryEntity(timestampUtcMs = at(d(-10), 0), settingKey = LabRepository.FRICTION_ENABLED_KEY, valueJson = "true"))
        e.db.derivedDao().insertFrictionEvent(friction(at(day, 9), insta, FrictionVocab.OUTCOME_CLOSED_GO_HOME))
        e.session(day, at(day, 9, 4), insta, 5) // reopened 4 minutes later
        e.db.derivedDao().insertFrictionEvent(friction(at(day, 12), insta, FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV))

        val r = e.repo.friction(TrendWindow(WindowKind.D7)).report
        assertEquals(2, r.launchPauses)
        assertEquals(2, r.exits.exits)
        assertEquals(1, r.exits.quickReopens)
        assertEquals(1, r.exits.sustained)
        assertEquals(0, r.exits.unresolved)
        assertEquals(1, r.coveredDays)
    }

    @Test
    fun `a night that began days before the report still finds its start`() = runBlocking {
        val e = env()
        val maps = e.pkg("com.google.android.apps.maps", AppCategory.UTILITY)
        val range = e.repo.rhythm(TrendWindow(WindowKind.D7)).scope.range
        val first = range.start
        // Phone left alone from 22:00 two days before the report's first day to 02:00 the night after it (52 h):
        // the midpoint is the first day's midnight, so the rollup filed it there, but its start is two days back.
        val lastBefore = at(first.minusDays(2), 22)
        val back = at(first.plusDays(1), 2) // still the first logical day (rollover 04:00)
        e.session(first.minusDays(2), lastBefore - 10 * MINUTE_MS, maps, 10)
        e.session(first, back, maps, 10)
        e.rollup(first, overnightGapMs = back - lastBefore)
        e.coverage(ServiceKind.USAGE_HARVEST, at(first.minusDays(5), 0), now)
        assertEquals(back - lastBefore, e.repo.rhythm(TrendWindow(WindowKind.D7)).report.sleep.overnightGap.overallMs)
    }

    @Test
    fun `rhythm places distracting hours and pickups by logical slot on covered days only`() = runBlocking {
        val e = env()
        val insta = e.pkg("com.instagram.android")
        val maps = e.pkg("com.google.android.apps.maps", AppCategory.UTILITY)
        val day = d(-1) // a Sunday
        e.rollup(day, overnightGapMs = 7 * 60 * MINUTE_MS, firstPickupMs = at(day, 7, 30))
        e.db.derivedDao().upsertHourly(HourlyAppUsageEntity(day.toString(), 19, insta, 20 * MINUTE_MS, 1)) // 23:00
        e.db.derivedDao().upsertHourly(HourlyAppUsageEntity(day.toString(), 19, maps, 40 * MINUTE_MS, 1))
        e.session(day, at(day, 23), insta, 20)
        // The night before: 23:50 (still Friday's logical day) → 07:00, the 7 h the rollup stored.
        e.session(d(-2), at(d(-2), 23, 50), maps, 10)
        e.session(day, at(day, 7), maps, 30)
        // Not covered (no rollup): ignored entirely.
        e.db.derivedDao().upsertHourly(HourlyAppUsageEntity(d(-2).toString(), 19, insta, 50 * MINUTE_MS, 1))

        val r = e.repo.rhythm(TrendWindow(WindowKind.D7)).report
        assertEquals(1, r.coveredDays)
        val sunday = r.heatmap.cells[6]
        assertEquals(20 * MINUTE_MS, sunday[19].distractingMs)
        assertEquals(1.0, sunday[19].pickups, 1e-9)
        assertEquals(23, r.heatmap.wallHour(19))
        assertEquals(7 * 60 + 30, r.sleep.firstPickupMinute)
        assertEquals(20 * MINUTE_MS, r.sleep.lateNight.overallMs)
        // Usage collection wasn't watching that night: no phone-free night is claimed.
        assertNull(r.sleep.overnightGap.overallMs)
        e.coverage(ServiceKind.USAGE_HARVEST, at(d(-3), 0), now)
        assertEquals(7 * 60 * MINUTE_MS, e.repo.rhythm(TrendWindow(WindowKind.D7)).report.sleep.overnightGap.overallMs)
    }
}
