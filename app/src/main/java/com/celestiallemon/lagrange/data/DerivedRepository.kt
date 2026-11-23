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
import com.celestiallemon.lagrange.data.db.UsageEventTypes
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
    private val lookbackMs: Long = LOOKBACK_MS,
    private val lookaheadMs: Long = LOOKAHEAD_MS,
) {
    private val mutex = Mutex()
    private val _progress = MutableStateFlow(RecomputeProgress())
    val progress: StateFlow<RecomputeProgress> = _progress

    private val state get() = db.ingestionStateDao()

    // -- Dirty tracking -----------------------------------------------------------------------------------------------

    /** Requests a rebuild of every logical day touching `[fromMs, toMs]`, plus baselines for the 28 days after it. */
    suspend fun markDirty(fromMs: Long, toMs: Long = Long.MAX_VALUE) = db.withWriteTransaction { markDirtyLocked(fromMs, toMs) }

    suspend fun markAllDirty() = markDirty(0)

    private suspend fun markDirtyLocked(fromMs: Long, toMs: Long) = DerivedDirty.mark(state, fromMs, toMs)

    /** Queues a full rebuild when the derived tables were built by another algorithm version. Returns true if it did. */
    suspend fun ensureAlgorithmVersion(): Boolean = db.withWriteTransaction {
        val built = state.get(IngestionKeys.DERIVED_ALGORITHM_VERSION)
        if (built == DerivedAlgorithm.VERSION.toLong()) return@withWriteTransaction false
        markDirtyLocked(0, Long.MAX_VALUE)
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ALGORITHM_VERSION, DerivedAlgorithm.VERSION.toLong()))
        true
    }

    /**
     * Compares the settings that shape derived rows (rollover hour, budget, app categories) with the ones last
     * reconciled, queueing the rebuild each change needs, in one transaction with the new fingerprint. Safe to call
     * any time; returns true if something was queued.
     */
    suspend fun reconcileSettings(): Boolean {
        val s = settings()
        return db.withWriteTransaction {
            // Read inside the transaction: the fingerprint stored below must describe exactly the rows checked.
            val packages = db.packageDao().all().sortedBy { it.id }
            var queued = false
            val rollover = state.get(IngestionKeys.DERIVED_ROLLOVER_HOUR)
            if (rollover != null && rollover != s.rolloverHour.toLong()) {
                markDirtyLocked(0, Long.MAX_VALUE) // every day boundary moved
                queued = true
            }
            val budget = state.get(IngestionKeys.DERIVED_BUDGET_MS)
            if (budget != null && budget != s.dailyBudgetMs) {
                markDirtyLocked(clock() - BUDGET_DIRTY_MS, Long.MAX_VALUE) // past days keep the budget that applied then
                queued = true
            }
            val maxId = state.get(IngestionKeys.DERIVED_CATEGORY_MAX_ID)
            val hash = state.get(IngestionKeys.DERIVED_CATEGORY_HASH)
            if (maxId != null && hash != null && categoryHash(packages.filter { it.id <= maxId }) != hash) {
                markDirtyLocked(0, Long.MAX_VALUE) // every total the app ever contributed to changed
                queued = true
            }
            state.put(IngestionStateEntity(IngestionKeys.DERIVED_ROLLOVER_HOUR, s.rolloverHour.toLong()))
            state.put(IngestionStateEntity(IngestionKeys.DERIVED_BUDGET_MS, s.dailyBudgetMs))
            state.put(IngestionStateEntity(IngestionKeys.DERIVED_CATEGORY_MAX_ID, packages.lastOrNull()?.id ?: 0L))
            state.put(IngestionStateEntity(IngestionKeys.DERIVED_CATEGORY_HASH, categoryHash(packages)))
            queued
        }
    }

    private fun categoryHash(rows: List<com.celestiallemon.lagrange.data.db.PackageEntity>): Long {
        var h = -0x340d631b7bdddcdbL // FNV-1a 64
        for (r in rows) for (c in "${r.id}:${r.category};") {
            h = h xor c.code.toLong()
            h *= 0x100000001b3L
        }
        return h
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
        // The chunk cursor (ACTIVE_FROM) advances as chunks commit; the baseline start must not.
        val bf = state.get(IngestionKeys.DERIVED_BASELINE_FROM_MS)
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_BASELINE_FROM_MS, minOf(bf ?: from, from)))
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_FROM_MS, from))
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_TO_MS, to))
        state.remove(IngestionKeys.DERIVED_PENDING_FROM_MS)
        state.remove(IngestionKeys.DERIVED_PENDING_TO_MS)
        from to to
    }

    private suspend fun finishActive() = db.withWriteTransaction {
        state.remove(IngestionKeys.DERIVED_ACTIVE_FROM_MS)
        state.remove(IngestionKeys.DERIVED_ACTIVE_TO_MS)
        state.remove(IngestionKeys.DERIVED_BASELINE_FROM_MS)
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_LAST_RUN_AT_MS, clock()))
    }

    /** Rebuilds everything that is dirty. Serialized; returns the number of logical days rebuilt. */
    suspend fun runPending(): Int = mutex.withLock {
        withContext(Dispatchers.IO) {
            var days = 0
            try {
                reconcileSettings()
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
        val earliest: Long,
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
        val earliest = raw.earliest() ?: return null
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
            earliest = earliest,
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

    private suspend fun recompute(fromMs: Long, toMs: Long, restarts: Int = 0): Int {
        val ctx = context()
        if (ctx == null) {
            finishActive()
            return 0
        }
        val earliest = ctx.earliest
        val firstDate = ctx.zones.dateOf(maxOf(fromMs, earliest), ctx.rollover)
        val lastDate = if (toMs >= ctx.dataEnd) ctx.today else minOf(ctx.today, ctx.zones.dateOf(maxOf(toMs, earliest), ctx.rollover))
        val baselineFromMs = state.get(IngestionKeys.DERIVED_BASELINE_FROM_MS) ?: fromMs
        val baselineFirst = ctx.zones.dateOf(maxOf(minOf(baselineFromMs, fromMs), earliest), ctx.rollover)
        val baselineLast = minOf(maxOf(lastDate, baselineFirst).plusDays(BASELINE_TRAIL_DAYS), ctx.today)
        if (firstDate > lastDate) {
            // Every chunk already committed (a run killed after its last chunk): only the baselines remain.
            refreshBaselines(ctx, baselineFirst, baselineLast)
            finishActive()
            return 0
        }
        pruneOutside(ctx, fromMs <= earliest, toMs >= ctx.dataEnd, firstDate, lastDate)
        val total = (ChronoUnit.DAYS.between(firstDate, lastDate) + 1).toInt()
        var done = 0
        _progress.value = RecomputeProgress(true, 0, total)
        var d = firstDate
        while (d <= lastDate) {
            val chunkLast = minOf(d.plusDays(chunkDays - 1L), lastDate)
            val windows = generateSequence(d) { it.plusDays(1) }.takeWhile { it <= chunkLast }.map { ctx.zones.window(it, ctx.rollover) }.toList()
            val checkSpill = d == firstDate && restarts < MAX_SPILL_RESTARTS
            val restartFrom = rebuildChunk(ctx, windows, checkSpill)
            if (restartFrom != null) {
                // An episode crossing the range start changed (late events merged or split it): the day it starts in
                // must be rebuilt too, before anything here is replaced, or its rating and totals would be lost.
                db.withWriteTransaction {
                    state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_FROM_MS, restartFrom))
                    val bf = state.get(IngestionKeys.DERIVED_BASELINE_FROM_MS)
                    state.put(IngestionStateEntity(IngestionKeys.DERIVED_BASELINE_FROM_MS, minOf(bf ?: restartFrom, restartFrom)))
                }
                Log.i(TAG, "episode crosses $firstDate start; widening rebuild")
                return recompute(restartFrom, toMs, restarts + 1)
            }
            done += windows.size
            _progress.value = RecomputeProgress(true, done, total)
            db.withWriteTransaction {
                state.put(IngestionStateEntity(IngestionKeys.DERIVED_ACTIVE_FROM_MS, windows.last().endMs))
            }
            d = chunkLast.plusDays(1)
        }
        refreshBaselines(ctx, baselineFirst, baselineLast)
        finishActive()
        Log.i(TAG, "rebuilt $total day(s) $firstDate..$lastDate")
        return total
    }

    /**
     * A rebuild reaching the start (or the end) of the ledger owns everything before (after) it: rows there belong to
     * days that no longer exist under the current rollover hour or zone history, and would haunt baselines.
     */
    private suspend fun pruneOutside(ctx: Context, fromStart: Boolean, toEnd: Boolean, firstDate: LocalDate, lastDate: LocalDate) {
        if (!fromStart && !toEnd) return
        val from = if (fromStart) firstDate.toString() else "0000-01-01"
        val to = if (toEnd) lastDate.toString() else "9999-12-31"
        val fromMs = if (fromStart) ctx.zones.window(firstDate, ctx.rollover).startMs else Long.MIN_VALUE
        val toMs = if (toEnd) ctx.zones.window(lastDate, ctx.rollover).endMs else Long.MAX_VALUE
        db.withWriteTransaction {
            val dao = db.derivedDao()
            dao.deleteDailyOutside(from, to)
            dao.deleteAppRollupsOutside(from, to)
            dao.deleteHourlyOutside(from, to)
            dao.deleteSessionsOutside(fromMs, toMs)
        }
    }

    /** A chunk's ledger slice, read wide enough that every episode touching `[cs, ce)` reconstructs whole. */
    private class Loaded(
        val loadFrom: Long,
        val loadTo: Long,
        val input: ReconstructionInput,
        val episodes: List<Episode>,
    )

    private suspend fun load(ctx: Context, cs: Long, ce: Long): Loaded {
        var back = lookbackMs
        var ahead = lookaheadMs
        var attempt = 0
        while (true) {
            val loadFrom = cs - back
            val loadTo = minOf(ce + ahead, ctx.dataEnd + 1)
            val events = db.rawUsageEventDao().between(loadFrom, loadTo).map { UsageEvent(it.timestampUtcMs, it.eventType, it.pkgId, it.clsId, it.id) }
            val notifications = db.rawNotificationDao().eligibleAlive(loadFrom, loadTo).map { it.toMeta() }
            val friction = db.derivedDao().frictionBetween(loadFrom, loadTo).map { it.toMeta() }
            val input = ReconstructionInput(events, ctx.apps, ctx.customTabs, notifications, friction, ctx.coverage, dataEndMs = minOf(ctx.dataEnd, loadTo))
            val episodes = SessionReconstructor.reconstruct(input, rules)
            // An episode cut off by the read margins (still "open" at loadTo, or already running when the read began)
            // would be stored or counted short: widen the read until every episode touching the chunk is whole.
            val cutAhead = loadTo <= ctx.dataEnd && episodes.any { it.inProgress && it.startMs < ce }
            // Only what the reconstructor reads counts as the read's edge (not unrelated background events).
            val firstEvent = events.firstOrNull { it.type in FOREGROUND_TYPES || it.type in SCREEN_EVENT_TYPES }?.timestampMs
            // The screen was already on when the read began and stayed on into the chunk: whatever opened that
            // episode lies before the read, even if no event of it made it in.
            val firstScreen = events.firstOrNull { it.type == UsageEventTypes.SCREEN_INTERACTIVE || it.type == UsageEventTypes.SCREEN_NON_INTERACTIVE }
            val firstFg = events.firstOrNull { it.type in FOREGROUND_TYPES }
            val openAtRead = (if (firstScreen == null) events.any { it.timestampMs >= cs && it.type in FOREGROUND_TYPES } else firstScreen.type == UsageEventTypes.SCREEN_NON_INTERACTIVE && firstScreen.timestampMs > cs) ||
                // An app already in front when the read began (its first event is a close) and still there in the chunk.
                (firstFg != null && firstFg.type != UsageEventTypes.ACTIVITY_RESUMED && firstFg.timestampMs > cs)
            val cutBehind = loadFrom > ctx.earliest && firstEvent != null &&
                (openAtRead || episodes.any { it.startMs - firstEvent < MARGIN_EDGE_MS && it.endMs > cs })
            if ((cutAhead || cutBehind) && attempt < MAX_MARGIN_DOUBLINGS) {
                if (cutAhead) ahead *= 2
                if (cutBehind) back *= 2
                attempt++
                continue
            }
            return Loaded(loadFrom, loadTo, input, episodes)
        }
    }

    /**
     * Rebuilds one chunk. With [checkSpill] (the first chunk of a range) it first checks that the episodes crossing
     * the chunk start still match the stored ones; if not, nothing is written and the start of the earliest such
     * episode is returned so the caller can widen the range.
     */
    private suspend fun rebuildChunk(ctx: Context, windows: List<com.celestiallemon.lagrange.domain.DayWindow>, checkSpill: Boolean = false): Long? {
        val cs = windows.first().startMs
        val ce = windows.last().endMs
        val loaded = load(ctx, cs, ce)
        val episodes = loaded.episodes
        if (checkSpill && cs > ctx.earliest) {
            val fresh = episodes.filter { it.startMs < cs && it.endMs > cs }
            val stored = db.derivedDao().sessionsSpanning(cs)
            if (!sameRows(ctx, fresh, stored)) {
                val from = (fresh.map { it.startMs } + stored.map { it.startMs }).min()
                if (from < cs) return from
            }
        }
        val results = RollupEngine.compute(
            RollupInput(windows, episodes, ctx.apps, ctx.coverage, loaded.input.notifications, ctx.rollover, ctx.dataEnd, ctx.budgetFor, ctx.zones),
        )
        writeChunk(ctx, cs, ce, windows.first().date, windows.last().date, episodes.filter { it.startMs >= cs }, results)
        return null
    }

    /** Whether [episodes] would be stored exactly as [rows] (session and segment fields alike; ratings aside). */
    private suspend fun sameRows(ctx: Context, episodes: List<Episode>, rows: List<SessionEntity>): Boolean {
        if (episodes.size != rows.size) return false
        for ((ep, row) in episodes.sortedBy { it.startMs }.zip(rows.sortedBy { it.startMs })) {
            val date = ctx.zones.dateOf(ep.startMs, ctx.rollover).toString()
            if (ep.toEntity(date, row.worthItRating) != row.copy(id = 0)) return false
            val segs = db.derivedDao().segmentsOfSession(row.id).map { it.copy(id = 0, sessionId = 0) }
            if (segs != ep.segments.mapIndexed { i, s -> s.toEntity(0, i) }) return false
        }
        return true
    }

    /**
     * Replaces the chunk's sessions. [candidates] are the reconstructed episodes starting at or after the chunk start,
     * sorted. Normally only those starting inside `[cs, ce)` are written, but when one of them now runs past [ce]
     * (late events merged a pickup across the boundary) every stored session it overlaps is superseded: the replaced
     * range grows to cover it, and the ratings found there are carried over in the same transaction.
     */
    private suspend fun writeChunk(
        ctx: Context,
        cs: Long,
        ce: Long,
        firstDate: LocalDate,
        lastDate: LocalDate,
        candidates: List<Episode>,
        results: List<DayResult>,
    ) = db.withWriteTransaction {
        val dao = db.derivedDao()
        var end = ce
        while (true) {
            var grown = maxOf(end, candidates.filter { it.startMs < end }.maxOfOrNull { it.endMs + 1 } ?: end)
            // A rated session whose rebuilt episode now starts past the boundary moves with it: write that episode
            // here too, or the rating would be deleted with its old row and never seen by the next chunk.
            for (o in dao.ratingsStartingIn(cs, grown)) {
                val home = candidates.firstOrNull { !it.isGlanceOnly && o.startMs in it.pickupAtMs..it.endMs } ?: continue
                if (home.startMs >= grown) grown = home.endMs + 1
            }
            if (grown == end) break
            end = grown
        }
        val episodes = candidates.filter { it.startMs < end }
        val old = dao.ratingsStartingIn(cs, end).map { RatingCarryOver.OldRating(it.episodeKey, it.startMs, it.worthItRating) }
        dao.deleteSessionsStartingIn(cs, end)
        val ratings = RatingCarryOver.reattach(old, episodes)
        if (ratings.size < old.size) Log.w(TAG, "${old.size - ratings.size} rating(s) had no matching episode after rebuild")
        for ((i, ep) in episodes.withIndex()) {
            val date = ctx.zones.dateOf(ep.startMs, ctx.rollover).toString()
            val id = dao.insertSession(ep.toEntity(date, ratings[i]))
            if (ep.segments.isNotEmpty()) dao.insertSegments(ep.segments.mapIndexed { idx, s -> s.toEntity(id, idx) })
        }
        dao.deleteAppRollups(firstDate.toString(), lastDate.toString())
        dao.deleteHourly(firstDate.toString(), lastDate.toString())
        // A day the engine no longer produces (e.g. now before the first data) must not keep its old totals.
        val produced = results.map { it.rollup.logicalDate }.toSet()
        generateSequence(firstDate) { it.plusDays(1) }.takeWhile { it <= lastDate }
            .filter { it !in produced }.forEach { dao.deleteDailyRollup(it.toString()) }
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
        val nowZone = ctx.zones.zoneAt(ctx.dataEnd)
        val cutoff = PartialDayBaseline.cutoff(ctx.dataEnd, ctx.rollover, nowZone)
        // Whole hours come from the hourly buckets; the current hour is cut at the same wall-clock minute from each
        // day's own sessions, so use that happened after "now" on a past day is never credited to the slice.
        val wholeHours = cutoff.copy(fraction = 0.0)
        val prior = hourly.filter { it.pkgId in activePkgs }.groupBy { LocalDate.parse(it.logicalDate) }.mapValues { (date, cells) ->
            val slots = cells.groupBy { it.hourSlot }.mapValues { (_, c) -> c.sumOf { it.durationMs } }
            PartialDayBaseline.sliceMs(slots, wholeHours, HourSlots.repeatedSlot(ctx.zones.window(date, ctx.rollover), ctx.rollover))
        }
        val nowLocal = java.time.Instant.ofEpochMilli(ctx.dataEnd).atZone(nowZone).toLocalDateTime()
        // After a westward trip the clock can read a time before this logical day's own rollover: no past day has
        // a comparable "same time", so there is no slice to show.
        if (!PartialDayBaseline.comparable(nowLocal, ctx.today, ctx.rollover)) return null
        val partialHour = base.matchedDays.associateWith { date ->
            PartialDayBaseline.currentHourOn(date, nowLocal, ctx.rollover, ctx.zones)
                ?.let { activeUseBetween(ctx, it.startMs, it.endMs, activePkgs) } ?: 0L
        }
        // A matched day with no hourly rows was a confirmed zero-use day.
        val withZeros = base.matchedDays.associateWith { (prior[it] ?: 0L) + (partialHour[it] ?: 0L) }
        return PartialDayBaseline.compare(ctx.today, today.activeMs, cutoff, withZeros, points)
    }

    /**
     * Active use in `[fromMs, toMs)` exactly as the day would have looked with the ledger ending at [toMs]: the
     * stretch is reconstructed from raw events (stored segments only carry a total, not where inside them the
     * launch pauses and merged screen-off gaps fell).
     */
    private suspend fun activeUseBetween(ctx: Context, fromMs: Long, toMs: Long, pkgs: Set<Long>): Long {
        val loadFrom = fromMs - lookbackMs
        val events = db.rawUsageEventDao().between(loadFrom, toMs).map { UsageEvent(it.timestampUtcMs, it.eventType, it.pkgId, it.clsId, it.id) }
        if (events.isEmpty()) return 0L
        val friction = db.derivedDao().frictionBetween(loadFrom, toMs).map { it.toMeta() }
        val episodes = SessionReconstructor.reconstruct(
            ReconstructionInput(events, ctx.apps, ctx.customTabs, emptyList(), friction, ctx.coverage, dataEndMs = toMs),
            rules,
        )
        return episodes.sumOf { ep ->
            ep.segments.filter { it.pkgId in pkgs }.sumOf { seg -> seg.effectiveSpans().sumOf { it.overlapMs(fromMs, toMs) } }
        }
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

    /**
     * Rates an episode by its stable key. Session ids change whenever a rebuild replaces rows, so the key is resolved
     * at write time; if the key itself changed, the rating goes to the unlocked episode that now contains [startMs].
     * Returns false when no episode matches any more.
     */
    suspend fun setWorthIt(episodeKey: String, startMs: Long, rating: Int?): Boolean {
        require(rating == null || rating in 1..5) { "rating must be 1..5" }
        return db.withWriteTransaction {
            val dao = db.derivedDao()
            dao.setRatingByKey(episodeKey, rating) > 0 || dao.setRatingContaining(startMs, rating) > 0
        }
    }

    /** The logical date containing [ms] under the current settings. */
    suspend fun dateOf(ms: Long): LocalDate = zoneTimeline().dateOf(ms, settings().rolloverHour)

    suspend fun windowOf(date: LocalDate): com.celestiallemon.lagrange.domain.DayWindow = zoneTimeline().window(date, settings().rolloverHour)

    /**
     * One logical day as a replayable fixture: the ledger slice the rebuild itself would read for that day (same
     * adaptive margins) plus its reconstruction.
     */
    suspend fun dayFixture(date: LocalDate): DayFixture = withContext(Dispatchers.IO) {
        val s = settings()
        val ctx = context()
        val zones = ctx?.zones ?: zoneTimeline()
        val w = zones.window(date, s.rolloverHour)
        val loaded = ctx?.let { load(it, w.startMs, w.endMs) }
        val input = loaded?.input
        val events = input?.events.orEmpty()
        val notifications = input?.notifications.orEmpty()
        val friction = input?.friction.orEmpty()
        val usedPkgs = (events.map { it.pkgId } + notifications.map { it.pkgId } + friction.map { it.pkgId }).toSet()
        val clsIds = events.map { it.clsId }.toSet()
        val apps = ctx?.apps ?: appInfos()
        val loadFrom = loaded?.loadFrom ?: w.startMs
        val loadTo = loaded?.loadTo ?: w.startMs
        val fixture = DayFixture(
            algorithmVersion = DerivedAlgorithm.VERSION,
            logicalDate = date.toString(),
            zoneId = w.zone.id,
            rolloverHour = s.rolloverHour,
            windowStartMs = w.startMs,
            windowEndMs = w.endMs,
            dataEndMs = input?.dataEndMs ?: w.startMs,
            apps = apps.values.filter { it.pkgId in usedPkgs }.sortedBy { it.pkgId }
                .map { FixtureApp(it.pkgId, it.packageName, it.category.name, it.isSystemSurface) },
            classes = db.classDao().namesOf(clsIds.toList()).map { FixtureClass(it.id, it.name) }.sortedBy { it.id },
            customTabClassIds = ctx?.customTabs.orEmpty().filter { it in clsIds }.sorted(),
            events = events.map { FixtureEvent(it.timestampMs, it.type, it.pkgId, it.clsId, it.order) },
            notifications = notifications.map {
                FixtureNotification(
                    it.id, it.pkgId, it.postedAtMs, it.removedAtMs, it.removalReason.name, it.importance, it.isOngoing,
                    it.isForegroundService, it.isGroupSummary, it.isAmbient, it.matchedInterruptionFilter, it.dndOrSilentAtPost,
                )
            },
            friction = friction.map { FixtureFriction(it.timestampMs, it.pkgId, it.eventKind, it.pauseDurationSec, it.postCountdownReactionMs, it.outcome) },
            coverage = ctx?.coverage.orEmpty().filter { it.endMs > loadFrom && it.startMs < loadTo }
                .map { FixtureCoverage(it.kind.name, it.status.name, it.startMs, it.endMs) },
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
        // A collector service (listener, accessibility) can die without a callback, so an open interval only counts
        // up to its last heartbeat: the last moment it was provably alive. Harvests heartbeat a connected listener
        // before deriving. Usage-harvest intervals are always closed by the harvester itself.
        val openEnd = if (kind == ServiceKind.USAGE_HARVEST) maxOf(dataEnd, lastSeenMs) else lastSeenMs
        return CoverageSpan(kind, st, startMs, endMs ?: openEnd)
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
        private const val BUDGET_DIRTY_MS = 24 * HOUR_MS
        /** An episode starting this close to the first event read may have begun before the read: widen it. */
        private const val MARGIN_EDGE_MS = 60_000L
        private const val MAX_MARGIN_DOUBLINGS = 5
        /** A changed episode crossing a range start widens the range at most this many days back per run. */
        private const val MAX_SPILL_RESTARTS = 16
        private val SCREEN_EVENT_TYPES = setOf(
            UsageEventTypes.SCREEN_INTERACTIVE, UsageEventTypes.SCREEN_NON_INTERACTIVE, UsageEventTypes.KEYGUARD_SHOWN, UsageEventTypes.KEYGUARD_HIDDEN,
        )
        private val FOREGROUND_TYPES = setOf(UsageEventTypes.ACTIVITY_RESUMED, UsageEventTypes.ACTIVITY_PAUSED, UsageEventTypes.ACTIVITY_STOPPED)
    }
}
