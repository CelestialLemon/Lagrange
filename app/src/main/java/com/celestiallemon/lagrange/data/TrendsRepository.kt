package com.celestiallemon.lagrange.data

import androidx.room3.withReadTransaction
import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.DayWorthRow
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.AppInsightRules
import com.celestiallemon.lagrange.domain.AppMeta
import com.celestiallemon.lagrange.domain.AppRow
import com.celestiallemon.lagrange.domain.AppSessionAnalyzer
import com.celestiallemon.lagrange.domain.AppSessionInsights
import com.celestiallemon.lagrange.domain.AppWindowTotals
import com.celestiallemon.lagrange.domain.AppsBreakdown
import com.celestiallemon.lagrange.domain.DailyMetrics
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.DaySample
import com.celestiallemon.lagrange.domain.FlowNode
import com.celestiallemon.lagrange.domain.FrictionRow
import com.celestiallemon.lagrange.domain.HourProfile
import com.celestiallemon.lagrange.domain.HourSlots
import com.celestiallemon.lagrange.domain.InsightWindow
import com.celestiallemon.lagrange.domain.PeriodDelta
import com.celestiallemon.lagrange.domain.ReconstructionRules
import com.celestiallemon.lagrange.domain.RepeatedHourCell
import com.celestiallemon.lagrange.domain.SegmentRow
import com.celestiallemon.lagrange.domain.SessionRow
import com.celestiallemon.lagrange.domain.SlotTotal
import com.celestiallemon.lagrange.domain.SwitchCoverage
import com.celestiallemon.lagrange.domain.TimeOfDayProfile
import com.celestiallemon.lagrange.domain.TrendCalculator
import com.celestiallemon.lagrange.domain.TrendPoint
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.UsageCoverage
import com.celestiallemon.lagrange.domain.WorthTotals
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Per-day facts for a window plus the lead-in days its averages and comparison need. */
data class TrendDataset(
    val today: LocalDate,
    val window: TrendWindow,
    val range: DateRange,
    /** Every date of [TrendCalculator.loadRange], NO_DATA ones included. */
    val days: Map<LocalDate, DailyMetrics>,
    val earliest: LocalDate?,
    /** The first day not settled yet (today, or earlier while collection or a rebuild catches up). */
    val firstUnsettled: LocalDate = today,
) {
    fun coveredDaysIn(r: DateRange): Int = r.dates().count { days[it]?.quality?.countsAsCovered == true }
}

/** One consistent read of the Trends tab: the daily series and the apps breakdown of the same window. */
data class TrendsOverview(val data: TrendDataset, val apps: List<AppRow>)

/** Everything the per-app deep dive shows for one app and window. */
data class AppDeepDive(
    val meta: AppMeta,
    val today: LocalDate,
    val earliest: LocalDate?,
    val window: TrendWindow,
    val range: DateRange,
    val rolloverHour: Int,
    val points: List<TrendPoint>,
    val delta: PeriodDelta,
    val coveredDays: Int,
    val partialDays: Int,
    val noDataDays: Int,
    val inProgressDays: Int,
    val observedTotalMs: Long,
    val observedOpens: Int,
    val profile: HourProfile,
    val insights: AppSessionInsights,
    /** Days in the window with notification coverage complete enough for trigger evidence. */
    val notifCoveredDays: Int,
    /** Days in the window with any data. */
    val dataDays: Int,
    /** Display names for the apps that appear in sequence flows. */
    val names: Map<Long, String>,
)

/**
 * Reads for the Trends tab and the per-app deep dive (spec §4.2 A/B), plus the per-app goal. Everything is read from
 * derived tables with aggregate queries inside one read transaction per screen load, so a rebuild landing midway
 * can't mix two generations of rows; nothing here writes derived rows.
 */
class TrendsRepository(
    private val db: LagrangeDatabase,
    private val derived: DerivedRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transientPackages: Set<String> = ReconstructionRules().transientPackages,
    private val rules: AppInsightRules = AppInsightRules(),
) {
    private val dao get() = db.analyticsDao()

    suspend fun today(): LocalDate = derived.dateOf(clock())

    suspend fun earliest(): LocalDate? = dao.earliestRollupDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    suspend fun trendData(window: TrendWindow): TrendDataset = withContext(Dispatchers.IO) {
        db.withReadTransaction { readTrendData(window) }
    }

    /** The series and the apps breakdown for [window], read together. */
    suspend fun overview(window: TrendWindow): TrendsOverview = withContext(Dispatchers.IO) {
        db.withReadTransaction {
            val data = readTrendData(window)
            TrendsOverview(data, readApps(data))
        }
    }

    /** Every app with usage in the dataset's window, with its covered-day average and trend. */
    suspend fun apps(data: TrendDataset): List<AppRow> = withContext(Dispatchers.IO) { db.withReadTransaction { readApps(data) } }

    private suspend fun readTrendData(window: TrendWindow): TrendDataset {
        val ctx = derived.analyticsContext()
        val earliest = earliest()
        val range = window.resolve(ctx.today, earliest)
        val load = TrendCalculator.loadRange(range, window.hasPrevious)
        val rollups = dao.rollups(load.start.toString(), load.end.toString())
        val worth = dao.worthByDay(load.start.toString(), load.end.toString())
        return TrendDataset(ctx.today, window, range, buildDays(load, rollups, worth, ctx.firstUnsettled), earliest, ctx.firstUnsettled)
    }

    private suspend fun readApps(data: TrendDataset): List<AppRow> {
        val r = data.range
        val unsettled = data.firstUnsettled.toString()
        val current = dao.appTotals(r.start.toString(), r.end.toString(), unsettled).map { it.toTotals() }
        val withPrevious = data.window.hasPrevious
        val prevRange = r.previous()
        val previous = if (withPrevious) dao.appTotals(prevRange.start.toString(), prevRange.end.toString(), unsettled).map { it.toTotals() } else emptyList()
        return AppsBreakdown.rows(
            current = current,
            previous = previous,
            coveredDays = data.coveredDaysIn(r),
            previousCoveredDays = if (withPrevious) data.coveredDaysIn(prevRange) else 0,
            meta = metas(),
            minCoveredDays = TrendCalculator.minDeltaDays(r.days),
        )
    }

    suspend fun appMeta(pkgId: Long): AppMeta? = dao.packageById(pkgId)?.toMeta()

    suspend fun deepDive(pkgId: Long, window: TrendWindow): AppDeepDive? = withContext(Dispatchers.IO) {
        db.withReadTransaction { readDeepDive(pkgId, window) }
    }

    private suspend fun readDeepDive(pkgId: Long, window: TrendWindow): AppDeepDive? {
        val meta = appMeta(pkgId) ?: return null
        val ctx = derived.analyticsContext()
        val today = ctx.today
        val earliest = earliest()
        val range = window.resolve(today, earliest)
        val load = TrendCalculator.loadRange(range, window.hasPrevious)
        val rollups = dao.rollups(load.start.toString(), load.end.toString())
        val days = buildDays(load, rollups, emptyList(), ctx.firstUnsettled)
        val appDays = dao.appDays(pkgId, load.start.toString(), load.end.toString()).associateBy { LocalDate.parse(it.logicalDate) }
        // Day status (spike / good day) describes the whole phone, never this app, so it isn't carried here.
        val samples = days.mapValues { (date, d) ->
            if (d.quality == DayQuality.NO_DATA) DaySample(date, DayQuality.NO_DATA, null)
            else DaySample(date, d.quality, (appDays[date]?.totalMs ?: 0L).toDouble())
        }
        val goal = meta.dailyGoalMs?.toDouble()
        val points = TrendCalculator.series(range, samples, reference = { goal })
        val inRange = points.map { it.sample }
        val coveredDays = inRange.count { it.quality.countsAsCovered }

        val from = range.start.toString()
        val to = range.end.toString()
        val unsettled = ctx.firstUnsettled.toString()
        val slots = dao.appHourSlots(pkgId, from, to, unsettled).map { SlotTotal(it.hourSlot, it.durationMs, it.opens) }
        val repeated = dao.appRepeatedHours(pkgId, from, to, unsettled).map { RepeatedHourCell(LocalDate.parse(it.logicalDate), it.durationMs, it.opens) }
        val repeatedSlots = repeated.map { it.date }.distinct().associateWith { date ->
            HourSlots.repeatedSlot(ctx.zones.window(date, ctx.rolloverHour), ctx.rolloverHour, ctx.zones)
        }
        val profile = TimeOfDayProfile.build(ctx.rolloverHour, slots, repeated, { repeatedSlots[it] }, coveredDays)

        // Opens are attributed to the logical day they happened on: select by the instants of the window's days.
        val fromMs = ctx.zones.window(range.start, ctx.rolloverHour).startMs
        val toMs = ctx.zones.window(range.end, ctx.rolloverHour).endMs
        val insightWindow = InsightWindow(fromMs, toMs, ctx.derivedThroughMs)
        val followTo = toMs + rules.quickReopenMs
        val sessions = sessionRows(pkgId, fromMs, toMs)
        val friction = dao.frictionOf(pkgId, fromMs, followTo).map { FrictionRow(it.timestampMs, it.eventKind, it.outcome) }
        val followUps = if (!meta.isSystemSurface) dao.runStarts(pkgId, toMs, followTo) else emptyList()
        val priorRunEndMs = if (!meta.isSystemSurface) dao.lastRunEndBefore(pkgId, fromMs) else null
        val metas = metas()
        val insights = AppSessionAnalyzer.analyze(
            pkgId, sessions, friction,
            isTransient = { metas[it]?.isSystemSurface == true },
            rules = rules,
            window = insightWindow,
            followUpOpens = followUps,
            switchCoverage = SwitchCoverage.of(ctx.coverage),
            usageCoverage = UsageCoverage.of(ctx.coverage),
            priorRunEndMs = priorRunEndMs,
        )
        val flowIds = (insights.flows.inflow + insights.flows.outflow).mapNotNull { (it.node as? FlowNode.App)?.pkgId }
        val rangeRollups = rollups.filter { LocalDate.parse(it.logicalDate) in range }

        return AppDeepDive(
            meta = meta,
            today = today,
            earliest = earliest,
            window = window,
            range = range,
            rolloverHour = ctx.rolloverHour,
            points = points,
            delta = TrendCalculator.delta(range, samples, window.hasPrevious),
            coveredDays = coveredDays,
            partialDays = inRange.count { it.quality == DayQuality.PARTIAL },
            noDataDays = inRange.count { it.quality == DayQuality.NO_DATA },
            inProgressDays = inRange.count { it.quality == DayQuality.IN_PROGRESS },
            observedTotalMs = range.dates().sumOf { appDays[it]?.totalMs ?: 0L },
            observedOpens = range.dates().sumOf { appDays[it]?.openCount ?: 0 },
            profile = profile,
            insights = insights,
            notifCoveredDays = rangeRollups.count { it.coverageStatus == DayCoverage.COMPLETE.name },
            dataDays = rangeRollups.size,
            names = flowIds.associateWith { metas[it]?.displayName ?: "App #$it" },
        )
    }

    /**
     * Sets (or clears, with null) an app's daily goal. The change is appended to `settings_history` in the same
     * transaction so the goal that applied at any time can be reconstructed later.
     */
    suspend fun setDailyGoal(pkgId: Long, goalMs: Long?) {
        require(goalMs == null || goalMs in GOAL_RANGE_MS) { "goal must be within $GOAL_RANGE_MS" }
        db.withWriteTransaction<Unit> {
            val pkg = dao.packageById(pkgId) ?: return@withWriteTransaction
            if (pkg.dailyGoalMs == goalMs) return@withWriteTransaction
            val now = clock()
            dao.setDailyGoal(pkgId, goalMs, now)
            db.settingsHistoryDao().insert(
                SettingsHistoryEntity(timestampUtcMs = now, settingKey = goalHistoryKey(pkg.packageName), valueJson = goalMs?.toString() ?: "null"),
            )
        }
    }

    private suspend fun sessionRows(pkgId: Long, fromMs: Long, toMs: Long): List<SessionRow> {
        val sessions = dao.sessionsWithApp(pkgId, fromMs, toMs)
        val segments = dao.segmentsOfSessionsWithApp(pkgId, fromMs, toMs).groupBy { it.sessionId }
        return sessions.map { s ->
            SessionRow(
                id = s.id,
                logicalDate = LocalDate.parse(s.logicalDate),
                startMs = s.startMs,
                endMs = s.endMs,
                activeDurationMs = s.activeDurationMs,
                triggerEvidence = evidenceOf(s.triggerEvidence) ?: TriggerEvidence.NO_OBSERVED_TRIGGER,
                entryPkgId = s.entryPkgId,
                worthItRating = s.worthItRating,
                segments = segments[s.id].orEmpty().map { g ->
                    SegmentRow(g.pkgId, g.seqIndex, g.startMs, g.endMs, g.durationMs, g.isBlockedAttempt, evidenceOf(g.segmentTriggerEvidence))
                },
            )
        }
    }

    private suspend fun metas(): Map<Long, AppMeta> = db.packageDao().all().associate { it.id to it.toMeta() }

    private fun PackageEntity.toMeta() = AppMeta(
        pkgId = id,
        packageName = packageName,
        displayName = displayName.ifBlank { packageName },
        category = runCatching { AppCategory.valueOf(category) }.getOrDefault(AppCategory.NEUTRAL),
        dailyGoalMs = dailyGoalMs,
        isSystemSurface = isLauncherOrSystemUi || packageName in transientPackages || packageName.isEmpty(),
    )

    private fun com.celestiallemon.lagrange.data.db.AppRangeTotalsRow.toTotals() = AppWindowTotals(pkgId, totalMs, opens, coveredMs, coveredOpens)

    companion object {
        /** Per-app goals between one minute and a whole day. */
        val GOAL_RANGE_MS = 60_000L..24 * 60 * 60_000L

        fun goalHistoryKey(packageName: String) = "app_daily_goal_ms:$packageName"

        private fun evidenceOf(name: String?): TriggerEvidence? = name?.let { n -> TriggerEvidence.entries.firstOrNull { it.name == n } }

        /**
         * Per-day facts for every date of [load]; dates without a rollup are NO_DATA, and days from [firstUnsettled]
         * on (today, or a day whose rollup may still be clipped at an older harvest) are in progress.
         */
        fun buildDays(load: DateRange, rollups: List<DailyRollupEntity>, worth: List<DayWorthRow>, firstUnsettled: LocalDate): Map<LocalDate, DailyMetrics> {
            val byDate = rollups.associateBy { it.logicalDate }
            val worthByDate = worth.associateBy { it.logicalDate }
            return load.dates().associateWith { date ->
                val row = byDate[date.toString()] ?: return@associateWith DailyMetrics(date, DayQuality.NO_DATA)
                val coverage = runCatching { DayCoverage.valueOf(row.coverageStatus) }.getOrNull()
                val status = runCatching { DayStatus.valueOf(row.dayStatus) }.getOrNull()
                val w = worthByDate[date.toString()]
                val unsettled = !date.isBefore(firstUnsettled)
                DailyMetrics(
                    date = date,
                    // A row whose vocabulary this build doesn't know is never trusted as covered.
                    quality = when {
                        unsettled -> DayQuality.IN_PROGRESS
                        coverage == null || status == null -> DayQuality.PARTIAL
                        else -> DayQuality.of(coverage, status, inProgress = false)
                    },
                    distractingMs = row.distractingMs,
                    activeMs = row.activeUsageMs,
                    screenMs = row.totalScreenMs,
                    episodes = row.episodeCount,
                    pickups = row.physicalPickupCount,
                    glances = row.glanceCount,
                    rabbitHoles = row.rabbitHoleCount,
                    worth = w?.let {
                        WorthTotals(it.valuedMs, it.regrettedMs, it.neutralMs, it.unratedMs, it.valuedCount, it.regrettedCount, it.neutralCount, it.unratedCount)
                    } ?: WorthTotals(),
                    budgetMs = row.budgetTargetMs,
                    dayStatus = status,
                )
            }
        }
    }
}
