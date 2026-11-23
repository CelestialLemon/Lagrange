package com.celestiallemon.lagrange.data

import android.util.Log
import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageIntervalEntity
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.DailyAppRollupEntity
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.FrictionEventEntity
import com.celestiallemon.lagrange.data.db.HourlyAppUsageEntity
import com.celestiallemon.lagrange.data.db.IngestionStateEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawNotificationEntity
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.domain.AppInfo
import com.celestiallemon.lagrange.domain.BaselinePoint
import com.celestiallemon.lagrange.domain.Baselines
import com.celestiallemon.lagrange.domain.CoverageSpan
import com.celestiallemon.lagrange.domain.DayFixture
import com.celestiallemon.lagrange.domain.DayResult
import com.celestiallemon.lagrange.domain.DayRollup
import com.celestiallemon.lagrange.domain.DerivedAlgorithm
import com.celestiallemon.lagrange.domain.Episode
import com.celestiallemon.lagrange.domain.FixtureApp
import com.celestiallemon.lagrange.domain.FixtureClass
import com.celestiallemon.lagrange.domain.FixtureCoverage
import com.celestiallemon.lagrange.domain.FixtureEpisode
import com.celestiallemon.lagrange.domain.FixtureEvent
import com.celestiallemon.lagrange.domain.FixtureFriction
import com.celestiallemon.lagrange.domain.FixtureNotification
import com.celestiallemon.lagrange.domain.FrictionMeta
import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.HourSlots
import com.celestiallemon.lagrange.domain.NotificationMeta
import com.celestiallemon.lagrange.domain.PartialDayBaseline
import com.celestiallemon.lagrange.domain.RatingCarryOver
import com.celestiallemon.lagrange.domain.ReconstructionInput
import com.celestiallemon.lagrange.domain.ReconstructionRules
import com.celestiallemon.lagrange.domain.RollupEngine
import com.celestiallemon.lagrange.domain.RollupInput
import com.celestiallemon.lagrange.domain.SessionReconstructor
import com.celestiallemon.lagrange.domain.SliceBaseline
import com.celestiallemon.lagrange.domain.UsageEvent
import com.celestiallemon.lagrange.domain.ZoneTimeline
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The settings that shape derived tables. */
data class DerivedSettings(val rolloverHour: Int, val dailyBudgetMs: Long)

/** Today's same-time-of-day comparison, for the Today screen. */
data class TodayComparison(
    val date: LocalDate,
    val dataEndMs: Long,
    val slice: SliceBaseline?,
    val coveredDays: Int,
    val fullDayMedianMs: Long?,
    val coveredDaysNeeded: Int,
)

data class RecomputeProgress(val running: Boolean = false, val doneDays: Int = 0, val totalDays: Int = 0)

/**
 * Keeps `sessions`, `session_segments`, `daily_rollups`, `daily_app_rollups` and `hourly_app_usage` in step with the
 * raw ledger.
 *
 * Callers mark a dirty UTC range ([markDirty]); [runPending] rebuilds the logical days it touches in small chunks
 * (a few days of events in memory at a time, well inside Android 17's background limits), then refreshes baselines
 * and day status for those days plus the 28 days after them (their baselines read the rebuilt days). The range being
 * worked on is persisted and advanced after every chunk, so a run killed half-way resumes where it stopped.
 *
 * User input is never lost: `worth_it_rating` values are read before a chunk's sessions are replaced and written
 * back onto the rebuilt episodes inside the same transaction ([RatingCarryOver]); notes, friction logs and settings
 * history live in their own tables.
 */
class DerivedRepository(
    private val db: LagrangeDatabase,
    private val settings: suspend () -> DerivedSettings,
    private val clock: () -> Long = System::currentTimeMillis,
    private val fallbackZone: () -> ZoneId = ZoneId::systemDefault,
    private val rules: ReconstructionRules = ReconstructionRules(),
    private val chunkDays: Int = DEFAULT_CHUNK_DAYS,
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(RecomputeProgress())
    val progress: StateFlow<RecomputeProgress> = _progress

    private val state get() = db.ingestionStateDao()

    // -- Dirty tracking -----------------------------------------------------------------------------------------------

    /** Requests a rebuild of every logical day touching `[fromMs, toMs]`, plus baselines for the 28 days after it. */
    suspend fun markDirty(fromMs: Long, toMs: Long = Long.MAX_VALUE) = db.withWriteTransaction { markDirtyLocked(fromMs, toMs) }

    suspend fun markAllDirty() = markDirty(0)

    private suspend fun markDirtyLocked(fromMs: Long, toMs: Long) {
        val pf = state.get(IngestionKeys.DERIVED_PENDING_FROM_MS)
        val pt = state.get(IngestionKeys.DERIVED_PENDING_TO_MS)
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_PENDING_FROM_MS, minOf(pf ?: fromMs, fromMs)))
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_PENDING_TO_MS, maxOf(pt ?: toMs, toMs)))
    }

    /** Queues a full rebuild when the derived tables were built by another algorithm version. Returns true if it did. */
    suspend fun ensureAlgorithmVersion(): Boolean = db.withWriteTransaction {
        val built = state.get(IngestionKeys.DERIVED_ALGORITHM_VERSION)
        if (built == DerivedAlgorithm.VERSION.toLong()) return@withWriteTransaction false
        markDirtyLocked(0, Long.MAX_VALUE)
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ALGORITHM_VERSION, DerivedAlgorithm.VERSION.toLong()))
        true
    }

    suspend fun hasPending(): Boolean =
        state.get(IngestionKeys.DERIVED_PENDING_FROM_MS) != null || state.get(IngestionKeys.DERIVED_ACTIVE_FROM_MS) != null

    /** Moves pending requests into the active range (merged with any range a killed run left behind). */
    private suspend fun claim(): Pair<Long, Long>? = db.withWriteTransaction {
        val pf = state.get(IngestionKeys.DERIVED_PENDING_FROM_MS)
        val pt = state.get(IngestionKeys.DERIVED_PENDING_TO_MS)
        val af = state.get(IngestionKeys.DERIVED_ACTIVE_FROM_MS)
        val at = state.get(IngestionKeys.DERIVED_ACTIVE_TO_MS)
        val from = listOfNotNull(pf, af).minOrNull() ?: return@withWriteTransaction null
        val to = listOfNotNull(pt, at).maxOrNull() ?: Long.MAX_VALUE
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_FROM_MS, from))
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_TO_MS, to))
        state.remove(IngestionKeys.DERIVED_PENDING_FROM_MS)
        state.remove(IngestionKeys.DERIVED_PENDING_TO_MS)
        from to to
    }

    private suspend fun finishActive() = db.withWriteTransaction {
        state.remove(IngestionKeys.DERIVED_ACTIVE_FROM_MS)
        state.remove(IngestionKeys.DERIVED_ACTIVE_TO_MS)
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_LAST_RUN_AT_MS, clock()))
    }

    /** Rebuilds everything that is dirty. Serialized; returns the number of logical days rebuilt. */
    suspend fun runPending(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            var days = 0
            try {
                for (round in 0 until MAX_ROUNDS) {
                    val (from, to) = claim() ?: break
                    days += recompute(from, to)
                }
            } finally {
                _progress.value = RecomputeProgress()
            }
            days
        }
    }

    // -- Rebuild ------------------------------------------------------------------------------------------------------

    private class Context(
        val zones: ZoneTimeline,
        val rollover: Int,
        val dataEnd: Long,
        val today: LocalDate,
        val apps: Map<Long, AppInfo>,
        val customTabs: Set<Long>,
        val coverage: List<CoverageSpan>,
        val budgetFor: (LocalDate) -> Long?,
    )

    private suspend fun context(): Context? {
        val raw = db.rawUsageEventDao()
        val latest = raw.latest() ?: return null
        val dataEnd = maxOf(latest, state.get(IngestionKeys.LAST_HARVEST_AT_MS) ?: latest)
        val s = settings()
        val zones = zoneTimeline()
        val apps = appInfos()
        val budgets = db.settingsHistoryDao().byKey(LagrangePreferences.HISTORY_DAILY_BUDGET_MS)
            .mapNotNull { row -> row.valueJson.trim().toLongOrNull()?.let { row.timestampUtcMs to it } }
        val today = zones.dateOf(dataEnd, s.rolloverHour)
        val budgetFor: (LocalDate) -> Long? = { date ->
            if (date >= today) {
                s.dailyBudgetMs
            } else {
                val end = zones.window(date, s.rolloverHour).endMs
                budgets.lastOrNull { it.first < end }?.second
                    ?: if (budgets.isNotEmpty()) UserPrefs.DEFAULT_DAILY_BUDGET_MS else s.dailyBudgetMs
            }
        }
        return Context(
            zones = zones,
            rollover = s.rolloverHour,
            dataEnd = dataEnd,
            today = today,
            apps = apps,
            customTabs = db.classDao().customTabIds().toSet(),
            coverage = db.coverageDao().all().map { it.toSpan(dataEnd) },
            budgetFor = budgetFor,
        )
    }

    private suspend fun recompute(fromMs: Long, toMs: Long): Int {
        val earliest = db.rawUsageEventDao().earliest()
        val ctx = context()
        if (earliest == null || ctx == null) {
            finishActive()
            return 0
        }
        val firstDate = ctx.zones.dateOf(maxOf(fromMs, earliest), ctx.rollover)
        val lastDate = if (toMs >= ctx.dataEnd) ctx.today else minOf(ctx.today, ctx.zones.dateOf(maxOf(toMs, earliest), ctx.rollover))
        if (firstDate > lastDate) {
            finishActive()
            return 0
        }
        val total = (ChronoUnit.DAYS.between(firstDate, lastDate) + 1).toInt()
        var done = 0
        _progress.value = RecomputeProgress(true, 0, total)
        var d = firstDate
        while (d <= lastDate) {
            val chunkLast = minOf(d.plusDays(chunkDays - 1L), lastDate)
            val windows = generateSequence(d) { it.plusDays(1) }.takeWhile { it <= chunkLast }.map { ctx.zones.window(it, ctx.rollover) }.toList()
            rebuildChunk(ctx, windows)
            done += windows.size
            _progress.value = RecomputeProgress(true, done, total)
            db.withWriteTransaction {
                state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_FROM_MS, windows.last().endMs))
            }
            d = chunkLast.plusDays(1)
        }
        refreshBaselines(ctx, firstDate, minOf(lastDate.plusDays(BASELINE_TRAIL_DAYS), ctx.today))
        finishActive()
        Log.i(TAG, "rebuilt $total day(s) $firstDate..$lastDate")
        return total
    }

    private suspend fun rebuildChunk(ctx: Context, windows: List<com.celestiallemon.lagrange.domain.DayWindow>) {
        val cs = windows.first().startMs
        val ce = windows.last().endMs
        val loadFrom = cs - LOOKBACK_MS
        val loadTo = minOf(ce + LOOKAHEAD_MS, ctx.dataEnd + 1)
        val events = db.rawUsageEventDao().between(loadFrom, loadTo).map { UsageEvent(it.timestampUtcMs, it.eventType, it.pkgId, it.clsId, it.id) }
        val notifications = db.rawNotificationDao().eligibleAlive(loadFrom, loadTo).map { it.toMeta() }
        val friction = db.derivedDao().frictionBetween(loadFrom, loadTo).map { it.toMeta() }
        val episodes = SessionReconstructor.reconstruct(
            ReconstructionInput(events, ctx.apps, ctx.customTabs, notifications, friction, ctx.coverage, dataEndMs = minOf(ctx.dataEnd, loadTo)),
            rules,
        )
        val results = RollupEngine.compute(
            RollupInput(windows, episodes, ctx.apps, ctx.coverage, notifications, ctx.rollover, ctx.dataEnd, ctx.budgetFor),
        )
        val stored = episodes.filter { it.startMs >= cs && it.startMs < ce }
        writeChunk(ctx, cs, ce, windows.first().date, windows.last().date, stored, results)
    }

    private suspend fun writeChunk(
        ctx: Context,
        cs: Long,
        ce: Long,
        firstDate: LocalDate,
        lastDate: LocalDate,
        episodes: List<Episode>,
        results: List<DayResult>,
    ) = db.withWriteTransaction {
        val dao = db.derivedDao()
        val old = dao.ratingsStartingIn(cs, ce).map { RatingCarryOver.OldRating(it.episodeKey, it.startMs, it.worthItRating) }
        dao.deleteSessionsStartingIn(cs, ce)
        val ratings = RatingCarryOver.reattach(old, episodes)
        if (ratings.size < old.size) Log.w(TAG, "${old.size - ratings.size} rating(s) had no matching episode after rebuild")
        for ((i, ep) in episodes.withIndex()) {
            val date = ctx.zones.dateOf(ep.startMs, ctx.rollover).toString()
            val id = dao.insertSession(ep.toEntity(date, ratings[i]))
            if (ep.segments.isNotEmpty()) dao.insertSegments(ep.segments.mapIndexed { idx, s -> s.toEntity(id, idx) })
        }
        dao.deleteAppRollups(firstDate.toString(), lastDate.toString())
        dao.deleteHourly(firstDate.toString(), lastDate.toString())
        for (r in results) {
            val date = r.rollup.logicalDate.toString()
            dao.upsertDailyRollup(r.rollup.toEntity(dao.dailyRollup(date)))
            if (r.apps.isNotEmpty()) dao.insertAppRollups(r.apps.map { DailyAppRollupEntity(date, it.pkgId, it.totalMs, it.openCount, it.blockedAttemptCount, it.sessionCount, it.rabbitHoleContributionMs) })
            if (r.hourly.isNotEmpty()) dao.insertHourly(r.hourly.map { HourlyAppUsageEntity(date, it.hourSlot, it.pkgId, it.durationMs, it.openCount) })
        }
    }

    // -- Baselines ----------------------------------------------------------------------------------------------------

    private suspend fun points(from: LocalDate, to: LocalDate): Map<LocalDate, BaselinePoint> =
        db.derivedDao().dailyRollupsBetween(from.toString(), to.toString()).associate { row ->
            val date = LocalDate.parse(row.logicalDate)
            date to BaselinePoint(date, row.dayStatus != DayStatus.UNCERTAIN_COVERAGE.name, row.activeUsageMs, row.distractingMs, row.budgetTargetMs)
        }

    private suspend fun refreshBaselines(ctx: Context, from: LocalDate, to: LocalDate) {
        if (from > to) return
        val points = points(from.minusDays(BASELINE_TRAIL_DAYS), to)
        val rows = db.derivedDao().dailyRollupsBetween(from.toString(), to.toString())
        val todayInProgress = ctx.dataEnd < ctx.zones.window(ctx.today, ctx.rollover).endMs
        db.withWriteTransaction {
            for (row in rows) {
                val date = LocalDate.parse(row.logicalDate)
                val point = points[date] ?: continue
                val base = Baselines.baseline(date, points)
                val status = if (date == ctx.today && todayInProgress) {
                    val slice = slice(ctx, points)
                    Baselines.partialStatus(point.eligible, point.activeMs, slice?.medianMs)
                } else {
                    Baselines.status(point, base)
                }
                if (row.baselineMedianMs != base.medianMs || row.dayStatus != status.name) {
                    db.derivedDao().updateBaseline(row.logicalDate, base.medianMs, status.name)
                }
            }
        }
    }

    private suspend fun slice(ctx: Context, points: Map<LocalDate, BaselinePoint>): SliceBaseline? {
        val today = points[ctx.today] ?: return null
        val base = Baselines.baseline(ctx.today, points)
        val activePkgs = ctx.apps.values.filter { RollupEngine.isActiveCategory(it.category) }.map { it.pkgId }.toSet()
        val hourly = if (base.matchedDays.isEmpty()) emptyList() else db.derivedDao().hourlyFor(base.matchedDays.map { it.toString() })
        val cutoff = PartialDayBaseline.cutoff(ctx.dataEnd, ctx.rollover, ctx.zones.zoneAt(ctx.dataEnd))
        val prior = hourly.filter { it.pkgId in activePkgs }.groupBy { LocalDate.parse(it.logicalDate) }.mapValues { (date, cells) ->
            val slots = cells.groupBy { it.hourSlot }.mapValues { (_, c) -> c.sumOf { it.durationMs } }
            PartialDayBaseline.sliceMs(slots, cutoff, HourSlots.repeatedSlot(ctx.zones.window(date, ctx.rollover), ctx.rollover))
        }
        // A matched day with no hourly rows was a confirmed zero-use day.
        val withZeros = base.matchedDays.associateWith { prior[it] ?: 0L }
        return PartialDayBaseline.compare(ctx.today, today.activeMs, cutoff, withZeros, points)
    }

    /**
     * Today against comparable days: the same-time-of-day slice (weekday/weekend matched) plus how many covered days
     * the baseline has. Null before any data exists.
     */
    suspend fun todayComparison(): TodayComparison? = withContext(Dispatchers.IO) {
        val ctx = context() ?: return@withContext null
        val points = points(ctx.today.minusDays(BASELINE_TRAIL_DAYS), ctx.today)
        val base = Baselines.baseline(ctx.today, points)
        TodayComparison(ctx.today, ctx.dataEnd, slice(ctx, points), base.coveredDays, base.medianMs, Baselines.MIN_COVERED_DAYS)
    }

    // -- Self-report, inspector, fixtures -------------------------------------------------------------------------------

    suspend fun setWorthIt(sessionId: Long, rating: Int?) {
        require(rating == null || rating in 1..5) { "rating must be 1..5" }
        db.derivedDao().setRating(sessionId, rating)
    }

    /** The logical date containing [ms] under the current settings. */
    suspend fun dateOf(ms: Long): LocalDate = zoneTimeline().dateOf(ms, settings().rolloverHour)

    suspend fun windowOf(date: LocalDate): com.celestiallemon.lagrange.domain.DayWindow = zoneTimeline().window(date, settings().rolloverHour)

    /** One logical day as a replayable fixture: the ledger slice (with reconstruction margins) plus today's reconstruction. */
    suspend fun dayFixture(date: LocalDate): DayFixture = withContext(Dispatchers.IO) {
        val s = settings()
        val zones = zoneTimeline()
        val w = zones.window(date, s.rolloverHour)
        val latest = db.rawUsageEventDao().latest() ?: w.startMs
        val dataEnd = maxOf(latest, state.get(IngestionKeys.LAST_HARVEST_AT_MS) ?: latest)
        val loadFrom = w.startMs - LOOKBACK_MS
        val loadTo = minOf(w.endMs + LOOKAHEAD_MS, dataEnd + 1)
        val events = db.rawUsageEventDao().between(loadFrom, loadTo)
        val notifications = db.rawNotificationDao().eligibleAlive(loadFrom, loadTo)
        val friction = db.derivedDao().frictionBetween(loadFrom, loadTo)
        val apps = appInfos()
        val usedPkgs = (events.map { it.pkgId } + notifications.map { it.pkgId } + friction.map { it.pkgId }).toSet()
        val clsIds = events.map { it.clsId }.toSet()
        val customTabs = db.classDao().customTabIds().filter { it in clsIds }
        val coverage = db.coverageDao().overlapping(loadFrom, loadTo).map { it.toSpan(dataEnd) }
        val fixture = DayFixture(
            algorithmVersion = DerivedAlgorithm.VERSION,
            logicalDate = date.toString(),
            zoneId = w.zone.id,
            rolloverHour = s.rolloverHour,
            windowStartMs = w.startMs,
            windowEndMs = w.endMs,
            dataEndMs = minOf(dataEnd, loadTo),
            apps = apps.values.filter { it.pkgId in usedPkgs }.sortedBy { it.pkgId }
                .map { FixtureApp(it.pkgId, it.packageName, it.category.name, it.isSystemSurface) },
            classes = db.classDao().namesOf(clsIds.toList()).map { FixtureClass(it.id, it.name) }.sortedBy { it.id },
            customTabClassIds = customTabs.sorted(),
            events = events.map { FixtureEvent(it.timestampUtcMs, it.eventType, it.pkgId, it.clsId, it.id) },
            notifications = notifications.map {
                FixtureNotification(
                    it.id, it.pkgId, it.postedAtMs, it.removedAtMs, it.removalReason, it.importance, it.isOngoing,
                    it.isForegroundService, it.isGroupSummary, it.isAmbient, it.matchedInterruptionFilter, it.dndOrSilentAtPost,
                )
            },
            friction = friction.map { FixtureFriction(it.timestampUtcMs, it.pkgId, it.eventKind, it.pauseDurationSec, it.postCountdownReactionMs, it.outcome) },
            coverage = coverage.map { FixtureCoverage(it.kind.name, it.status.name, it.startMs, it.endMs) },
            expected = emptyList(),
        )
        fixture.copy(expected = fixture.replay(rules))
    }

    // -- Helpers ------------------------------------------------------------------------------------------------------

    private suspend fun zoneTimeline(): ZoneTimeline = ZoneTimeline(
        db.tzHistoryDao().all().mapNotNull { row -> runCatching { row.effectiveFromUtcMs to ZoneId.of(row.zoneId) }.getOrNull() },
        fallbackZone(),
    )

    private suspend fun appInfos(): Map<Long, AppInfo> = db.packageDao().all().associate { p ->
        val category = runCatching { AppCategory.valueOf(p.category) }.getOrDefault(AppCategory.NEUTRAL)
        p.id to AppInfo(p.id, p.packageName, category, p.isLauncherOrSystemUi || p.packageName in rules.transientPackages)
    }

    private fun CoverageIntervalEntity.toSpan(dataEnd: Long): CoverageSpan {
        val kind = runCatching { ServiceKind.valueOf(serviceKind) }.getOrDefault(ServiceKind.USAGE_HARVEST)
        val st = runCatching { CoverageStatus.valueOf(status) }.getOrDefault(CoverageStatus.GAP)
        // An open interval is alive "now": as far as the ledger goes, or its last heartbeat if that is later.
        return CoverageSpan(kind, st, startMs, endMs ?: maxOf(dataEnd, lastSeenMs))
    }

    private fun RawNotificationEntity.toMeta() = NotificationMeta(
        id = id, pkgId = pkgId, postedAtMs = postedAtMs, removedAtMs = removedAtMs,
        removalReason = runCatching { RemovalReason.valueOf(removalReason) }.getOrDefault(RemovalReason.OTHER),
        importance = importance, isOngoing = isOngoing, isForegroundService = isForegroundService, isGroupSummary = isGroupSummary,
        isAmbient = isAmbient, matchedInterruptionFilter = matchedInterruptionFilter, dndOrSilentAtPost = dndOrSilentAtPost,
    )

    private fun FrictionEventEntity.toMeta() = FrictionMeta(timestampUtcMs, pkgId, eventKind, pauseDurationSec, postCountdownReactionMs, outcome)

    private fun Episode.toEntity(logicalDate: String, rating: Int?) = SessionEntity(
        episodeKey = episodeKey,
        algorithmVersion = DerivedAlgorithm.VERSION,
        logicalDate = logicalDate,
        startMs = startMs,
        endMs = endMs,
        activeDurationMs = activeDurationMs,
        physicalPickupCount = physicalPickupCount,
        isGlanceOnly = isGlanceOnly,
        triggerEvidence = triggerEvidence.name,
        triggerPkgId = triggerPkgId,
        triggerNotificationId = triggerNotificationId,
        entryPkgId = entryPkgId,
        totalDistractingMs = totals.distractingMs,
        totalNeutralMs = totals.neutralMs,
        totalUtilityMs = totals.utilityMs,
        totalExcludedMs = totals.excludedMs,
        blockedAttemptCount = blockedAttemptCount,
        distinctAppCount = distinctAppCount,
        distinctDistractingCount = distinctDistractingCount,
        archetype = archetype.name,
        isRabbitHole = isRabbitHole,
        rabbitHoleReasons = rabbitHoleReasons.joinToString(",") { it.name }.ifEmpty { null },
        worthItRating = rating,
        contextFlags = contextFlags,
    )

    private fun com.celestiallemon.lagrange.domain.Segment.toEntity(sessionId: Long, seq: Int) = SessionSegmentEntity(
        sessionId = sessionId,
        seqIndex = seq,
        pkgId = pkgId,
        startMs = startMs,
        endMs = endMs,
        // Real use only: launch-pause time on a blocked attempt and merged screen-off gaps are not counted.
        durationMs = effectiveMs,
        isBlockedAttempt = isBlockedAttempt,
        segmentTriggerEvidence = triggerEvidence?.name,
        segmentTriggerPkgId = triggerPkgId,
    )

    private fun DayRollup.toEntity(old: DailyRollupEntity?) = DailyRollupEntity(
        logicalDate = logicalDate.toString(),
        algorithmVersion = DerivedAlgorithm.VERSION,
        coverageStatus = coverage.name,
        notifCoverageRatio = notifCoverageRatio,
        frictionCoverageRatio = frictionCoverageRatio,
        totalScreenMs = totalScreenMs,
        activeUsageMs = activeUsageMs,
        distractingMs = distractingMs,
        neutralMs = neutralMs,
        utilityMs = utilityMs,
        excludedMs = excludedMs,
        selfAppMs = selfAppMs,
        episodeCount = episodeCount,
        physicalPickupCount = physicalPickupCount,
        glanceCount = glanceCount,
        microCheckCount = microCheckCount,
        rabbitHoleCount = rabbitHoleCount,
        blockedAttemptCount = blockedAttemptCount,
        notificationCount = notificationCount,
        longestFocusGapMs = longestFocusGapMs,
        overnightGapMs = overnightGapMs,
        firstPickupMs = firstPickupMs,
        lastActivityMs = lastActivityMs,
        budgetTargetMs = budgetTargetMs,
        // Baselines are refreshed right after the rebuild; until then keep the previous figures (no flicker).
        baselineMedianMs = old?.baselineMedianMs,
        dayStatus = if (!baselineEligible) DayStatus.UNCERTAIN_COVERAGE.name
        else old?.dayStatus?.takeIf { it != DayStatus.UNCERTAIN_COVERAGE.name } ?: DayStatus.INSUFFICIENT_BASELINE.name,
        spikeSummaryJson = old?.spikeSummaryJson,
    )

    companion object {
        private const val TAG = "LagrangeDerived"
        const val DEFAULT_CHUNK_DAYS = 3
        /** How far before/after a chunk the ledger is read so episodes crossing its edges reconstruct correctly. */
        const val LOOKBACK_MS = 12 * HOUR_MS
        const val LOOKAHEAD_MS = 12 * HOUR_MS
        const val BASELINE_TRAIL_DAYS = 28L
        private const val MAX_ROUNDS = 8
    }
}
