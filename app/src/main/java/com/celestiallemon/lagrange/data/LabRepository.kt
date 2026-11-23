package com.celestiallemon.lagrange.data

import androidx.room3.withReadTransaction
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.HourlyAppUsageEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.RawNotificationEntity
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.AppMeta
import com.celestiallemon.lagrange.domain.Baselines
import com.celestiallemon.lagrange.domain.DailyMetrics
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.HourSlots
import com.celestiallemon.lagrange.domain.PartialDayBaseline
import com.celestiallemon.lagrange.domain.ReconstructionRules
import com.celestiallemon.lagrange.domain.RollupEngine
import com.celestiallemon.lagrange.domain.RollupRules
import com.celestiallemon.lagrange.domain.SliceCutoff
import com.celestiallemon.lagrange.domain.Span
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.UsageCoverage
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.domain.lab.AutopsyInput
import com.celestiallemon.lagrange.domain.lab.CheckInLog
import com.celestiallemon.lagrange.domain.lab.CheckInRules
import com.celestiallemon.lagrange.domain.lab.DayFacts
import com.celestiallemon.lagrange.domain.lab.DayHighlight
import com.celestiallemon.lagrange.domain.lab.FrictionAnalyzer
import com.celestiallemon.lagrange.domain.lab.FrictionInput
import com.celestiallemon.lagrange.domain.lab.FrictionReport
import com.celestiallemon.lagrange.domain.lab.FrictionRules
import com.celestiallemon.lagrange.domain.lab.GatewayAnalyzer
import com.celestiallemon.lagrange.domain.lab.GatewayReport
import com.celestiallemon.lagrange.domain.lab.LabApps
import com.celestiallemon.lagrange.domain.lab.LabCoverage
import com.celestiallemon.lagrange.domain.lab.LabEpisode
import com.celestiallemon.lagrange.domain.lab.LabFriction
import com.celestiallemon.lagrange.domain.lab.LabNotification
import com.celestiallemon.lagrange.domain.lab.LabSegment
import com.celestiallemon.lagrange.domain.lab.NotificationCostAnalyzer
import com.celestiallemon.lagrange.domain.lab.NotificationCostReport
import com.celestiallemon.lagrange.domain.lab.NotificationSort
import com.celestiallemon.lagrange.domain.lab.RhythmAnalyzer
import com.celestiallemon.lagrange.domain.lab.RhythmDay
import com.celestiallemon.lagrange.domain.lab.RhythmReport
import com.celestiallemon.lagrange.domain.lab.SpikeAnalyzer
import com.celestiallemon.lagrange.domain.lab.SpikeAutopsy
import com.celestiallemon.lagrange.domain.lab.SpanSet
import com.celestiallemon.lagrange.domain.lab.SubstitutionDay
import com.celestiallemon.lagrange.domain.lab.WorthCheckInSampler
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Where the check-in remembers what it offered (device-local preferences in the app, a variable in tests). */
interface CheckInStore {
    val changes: Flow<String?>

    /** Atomically replaces the stored JSON with [transform] of it. */
    suspend fun update(transform: (String?) -> String?)
}

/** The scope of one Lab read: the window's dates and instants, and which of its days can be counted. */
data class LabScope(
    val today: LocalDate,
    val window: TrendWindow,
    val range: DateRange,
    val rolloverHour: Int,
    val fromMs: Long,
    val toMs: Long,
    /** Derived tables are trustworthy up to here. */
    val dataEndMs: Long,
    /** Covered, settled days of the window, oldest first. */
    val coveredDates: List<LocalDate>,
) {
    val coverage: LabCoverage get() = LabCoverage(range, coveredDates.size, range.days)
}

data class SpikesReport(
    val scope: LabScope,
    /** Settled spikes and good days of the window, newest first. */
    val history: List<DayHighlight>,
    /** Today so far, when it is already running high or low against the same time on comparable days. */
    val live: DayHighlight?,
    /** Covered days the baseline has right now, and how many it needs before anything is judged. */
    val baselineDays: Int,
    val baselineDaysNeeded: Int,
    val apps: LabApps = LabApps.EMPTY,
)

data class RabbitHoleData(val scope: LabScope, val episodes: List<LabEpisode>, val apps: LabApps)

data class GatewayData(val scope: LabScope, val report: GatewayReport, val apps: LabApps)

data class NotificationCostData(val scope: LabScope, val report: NotificationCostReport, val apps: LabApps)

data class RhythmData(val scope: LabScope, val report: RhythmReport)

data class FrictionData(val scope: LabScope, val report: FrictionReport, val apps: LabApps)

/** The check-in card: one unrated rabbit hole to ask about. */
data class CheckInCard(val episode: LabEpisode, val apps: LabApps)

/**
 * Reads for the Behavior Lab (spec §4.2 C). Every section is one read transaction over the derived tables (plus raw
 * notifications and friction logs where the section needs them), so a rebuild landing midway can't mix generations.
 * All ranking and every "enough data?" rule lives in plain Kotlin under `domain/lab`; this class only gathers rows.
 */
class LabRepository(
    private val db: LagrangeDatabase,
    private val derived: DerivedRepository,
    private val checkInStore: CheckInStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val transientPackages: Set<String> = ReconstructionRules().transientPackages,
    private val checkInRules: CheckInRules = CheckInRules(),
    private val frictionRules: FrictionRules = FrictionRules(),
) {
    private val dao get() = db.analyticsDao()

    // -- Spikes ---------------------------------------------------------------------------------------------------------

    suspend fun spikes(window: TrendWindow): SpikesReport {
        val comparison = runCatching { derived.todayComparison() }.getOrNull()
        return io {
            val ctx = derived.analyticsContext()
            val scope = scope(ctx, window)
            val days = facts(ctx, scope.range.start.minusDays(BASELINE_LOOKBACK_DAYS), scope.range.end)
            val history = SpikeAnalyzer.history(scope.range, days)
            val base = Baselines.baseline(ctx.today, SpikeAnalyzer.points(days))
            val live = if (ctx.today in scope.range && comparison?.date == ctx.today) liveHighlight(ctx, comparison, days) else null
            SpikesReport(scope, history, live, base.coveredDays, Baselines.MIN_COVERED_DAYS, LabApps(metas()))
        }
    }

    /** The autopsy of a settled spike or good day, or of today's live one. Null when [date] isn't one (any more). */
    suspend fun autopsy(date: LocalDate): SpikeAutopsy? {
        val comparison = runCatching { derived.todayComparison() }.getOrNull()
        return io {
            val ctx = derived.analyticsContext()
            val days = facts(ctx, date.minusDays(BASELINE_LOOKBACK_DAYS), date)
            val live = date == ctx.today
            val highlight = if (live) {
                comparison?.takeIf { it.date == date }?.let { liveHighlight(ctx, it, days) }
            } else {
                SpikeAnalyzer.history(DateRange(date, date), days).firstOrNull()
            } ?: return@io null
            val cutoff = if (live) comparison?.slice?.cutoff else null
            val metas = metas()
            val apps = LabApps(metas)
            val active = metas.values.filter { RollupEngine.isActiveCategory(it.category) }.map { it.pkgId }.toSet()
            val dates = listOf(date) + highlight.matchedDays
            val keys = dates.map { it.toString() }
            val hourly = dao.hourlyFor(keys).filter { it.pkgId in active }.groupBy { LocalDate.parse(it.logicalDate) }
            val repeated = dates.associateWith { HourSlots.repeatedSlot(ctx.zones.window(it, ctx.rolloverHour), ctx.rolloverHour, ctx.zones) }
            val slotMs = dates.associateWith { d ->
                SpikeAnalyzer.foldRepeated(hourly[d].orEmpty().groupBy { it.hourSlot }.mapValues { (_, c) -> c.sumOf { it.durationMs } }, repeated[d]).first
            }
            val rollupApps = dao.appRollupsFor(keys).filter { it.pkgId in active }.groupBy { LocalDate.parse(it.logicalDate) }
                .mapValues { (_, rows) -> rows.associate { it.pkgId to it.totalMs } }
            // Live: matched days count whole hours before "now" from the hourly cells, and the hour "now" is in
            // exactly, clipped from their own segments at the same wall-clock minute.
            val wholeHours = cutoff?.copy(fraction = 0.0)
            val appMs = dates.associateWith { d ->
                if (wholeHours != null && d != date) sliceApps(hourly[d].orEmpty(), repeated[d], wholeHours) else rollupApps[d].orEmpty()
            }
            val partialHour = if (cutoff == null || comparison == null) null else {
                val nowLocal = Instant.ofEpochMilli(comparison.dataEndMs).atZone(ctx.zones.zoneAt(comparison.dataEndMs)).toLocalDateTime()
                highlight.matchedDays.associateWith { d ->
                    val hour = PartialDayBaseline.currentHourOn(d, nowLocal, ctx.rolloverHour, ctx.zones) ?: return@associateWith emptyMap()
                    clippedUse(dao.segmentsOverlapping(hour.startMs, hour.endMs), hour, active)
                }
            }
            val episodes = episodesBetween(date, date)
            val note = dao.notesBetween(date.toString(), date.toString()).firstOrNull()?.noteText
            SpikeAnalyzer.autopsy(AutopsyInput(highlight, appMs, slotMs, episodes, note, cutoff, partialHour), apps)
        }
    }

    private fun liveHighlight(ctx: AnalyticsContext, comparison: TodayComparison, days: Map<LocalDate, DayFacts>): DayHighlight? {
        val row = days[ctx.today]
        // Today's rollup carries the coverage verdict so far; a gap today means no comparison at all. Nor is there one
        // while the derived tables lag behind the instant the comparison cut the matched days at.
        val eligible = SpikeAnalyzer.liveEligible(row?.coverageOk == true, comparison.dataEndMs, ctx.derivedThroughMs)
        return SpikeAnalyzer.live(ctx.today, eligible, comparison.slice, SpikeAnalyzer.matchedDays(ctx.today, days))
    }

    private fun sliceApps(rows: List<HourlyAppUsageEntity>, repeatedSlot: Int?, cutoff: SliceCutoff): Map<Long, Long> =
        SpikeAnalyzer.sliceApps(
            rows.groupBy { it.pkgId }.mapValues { (_, cells) ->
                SpikeAnalyzer.foldRepeated(cells.groupBy { it.hourSlot }.mapValues { (_, c) -> c.sumOf { it.durationMs } }, repeatedSlot).first
            },
            cutoff,
        )

    // -- Rabbit holes ---------------------------------------------------------------------------------------------------

    suspend fun rabbitHoles(window: TrendWindow): RabbitHoleData = io {
        val ctx = derived.analyticsContext()
        val scope = scope(ctx, window)
        val from = scope.range.start.toString()
        val to = scope.range.end.toString()
        val episodes = toLab(dao.rabbitHolesBetween(from, to), dao.rabbitHoleSegmentsBetween(from, to))
        RabbitHoleData(scope, episodes, LabApps(metas()))
    }

    // -- Gateways -------------------------------------------------------------------------------------------------------

    suspend fun gateways(window: TrendWindow): GatewayData = io {
        val ctx = derived.analyticsContext()
        val scope = scope(ctx, window)
        val apps = LabApps(metas())
        val covered = scope.coveredDates.toSet()
        val episodes = episodesBetween(scope.range.start, scope.range.end).filter { it.logicalDate in covered && !it.inProgress }
        GatewayData(scope, GatewayAnalyzer.analyze(episodes, apps), apps)
    }

    // -- Notifications --------------------------------------------------------------------------------------------------

    suspend fun notificationCost(window: TrendWindow, sort: NotificationSort = NotificationSort.VOLUME): NotificationCostData = io {
        val ctx = derived.analyticsContext()
        val scope = scope(ctx, window)
        val apps = LabApps(metas())
        val listener = ctx.coverage.filter { it.kind == ServiceKind.NOTIFICATION_LISTENER && it.status == CoverageStatus.ACTIVE }.map { it.span }
        val end = minOf(scope.toMs, scope.dataEndMs)
        val notifications = if (end > scope.fromMs) dao.countableNotificationsBetween(scope.fromMs, end).map { it.toLab() } else emptyList()
        // The day before the window too: the idle stretch before the window's first pickup starts there.
        val episodes = episodesBetween(scope.range.start.minusDays(1), scope.range.end).filter { !it.inProgress && it.endMs <= scope.dataEndMs }
        val usage = SpanSet.usageObserved(ctx.coverage)
        val report = NotificationCostAnalyzer.analyze(notifications, listener, episodes, scope.fromMs, scope.toMs, scope.dataEndMs, apps, sort, usage = usage)
        NotificationCostData(scope, report, apps)
    }

    // -- Rhythm ---------------------------------------------------------------------------------------------------------

    suspend fun rhythm(window: TrendWindow): RhythmData = io {
        val ctx = derived.analyticsContext()
        val scope = scope(ctx, window)
        val metas = metas()
        val from = scope.range.start.toString()
        val to = scope.range.end.toString()
        val distracting = dao.hourlyOfCategory(AppCategory.DISTRACTING.name, from, to)
            .filter { metas[it.pkgId]?.isSystemSurface != true }
            .groupBy { LocalDate.parse(it.logicalDate) }
        val episodes = dao.unlockedSessions(from, to).groupBy { LocalDate.parse(it.logicalDate) }
        val rollups = dao.rollups(from, to).associateBy { LocalDate.parse(it.logicalDate) }
        val days = scope.coveredDates.map { date ->
            val eps = episodes[date].orEmpty()
            val window = ctx.zones.window(date, ctx.rolloverHour)
            val hourly = distracting[date].orEmpty()
            val hasRepeat = hourly.any { it.hourSlot == HourSlots.REPEATED_SLOT } ||
                eps.any { HourSlots.slotOf(it.startMs, ctx.rolloverHour, ctx.zones.zoneAt(it.startMs)) == HourSlots.REPEATED_SLOT }
            val roll = rollups[date]
            RhythmDay(
                date = date,
                distractingSlots = hourly.groupBy { it.hourSlot }.mapValues { (_, c) -> c.sumOf { it.durationMs } },
                pickupSlots = eps.groupingBy { HourSlots.slotOf(it.startMs, ctx.rolloverHour, ctx.zones.zoneAt(it.startMs)) }.eachCount(),
                repeatedSlot = if (hasRepeat) HourSlots.repeatedSlot(window, ctx.rolloverHour, ctx.zones) else null,
                overnightGapMs = roll?.overnightGapMs,
                firstPickupMinute = roll?.firstPickupMs?.let { ms ->
                    val t = Instant.ofEpochMilli(ms).atZone(ctx.zones.zoneAt(ms)).toLocalTime()
                    t.hour * 60 + t.minute
                },
                episodes = eps.map { Span(it.startMs, it.endMs) },
                nightWindow = Span(window.startMs - NIGHT_RULES.nightBeforeRolloverMs, window.startMs + NIGHT_RULES.nightAfterRolloverMs),
            )
        }
        RhythmData(scope, RhythmAnalyzer.analyze(days, ctx.rolloverHour))
    }

    // -- Friction -------------------------------------------------------------------------------------------------------

    suspend fun friction(window: TrendWindow): FrictionData = io {
        val ctx = derived.analyticsContext()
        val scope = scope(ctx, window)
        val apps = LabApps(metas())
        val followTo = scope.toMs + frictionRules.sustainMs
        val events = db.derivedDao().frictionBetween(scope.fromMs, followTo).map {
            LabFriction(it.timestampUtcMs, it.pkgId, it.eventKind, it.outcome, it.postCountdownReactionMs, it.selectedTimerMin, it.pauseDurationSec)
        }
        val opens = dao.runStartsBetween(scope.fromMs, followTo)
            .filter { apps.isDistracting(it.pkgId) && !apps.isTransient(it.pkgId) }
            .map { it.startMs }

        // Substitution: covered days of this period against the period before (All-Time: its two halves).
        val (current, previous) = if (window.kind == WindowKind.ALL) {
            val dates = scope.range.dates()
            val half = dates.size / 2
            if (half == 0) null to null else DateRange(dates[half], dates.last()) to DateRange(dates.first(), dates[half - 1])
        } else {
            scope.range to scope.range.previous()
        }
        val loadFrom = previous?.start ?: scope.range.start
        val rollups = dao.rollups(loadFrom.toString(), scope.range.end.toString())
        val days = TrendsRepository.buildDays(DateRange(minOf(loadFrom, scope.range.start), scope.range.end), rollups, emptyList(), ctx.firstUnsettled)
        val byDate = rollups.associateBy { LocalDate.parse(it.logicalDate) }
        fun substitutionDays(r: DateRange?) = r?.dates().orEmpty().filter { days[it]?.quality?.countsAsCovered == true }
            .mapNotNull { d -> byDate[d]?.let { SubstitutionDay(d, it.distractingMs, it.neutralMs) } }
        suspend fun neutralApps(r: DateRange?): Map<Long, Long> {
            if (r == null) return emptyMap()
            return dao.appTotals(r.start.toString(), r.end.toString(), ctx.firstUnsettled.toString())
                .filter { row -> apps.category(row.pkgId) == AppCategory.NEUTRAL && !apps.isTransient(row.pkgId) && row.coveredMs > 0 }
                .associate { row -> row.pkgId to row.coveredMs }
        }
        val currentStartMs = current?.let { ctx.zones.window(it.start, ctx.rolloverHour).startMs } ?: scope.fromMs
        val enabled = db.settingsHistoryDao().byKey(FRICTION_ENABLED_KEY).mapNotNull { row ->
            row.valueJson.trim().trim('"').toBooleanStrictOrNull()?.let { row.timestampUtcMs to it }
        }
        val currentDays = substitutionDays(current)
        // The comparison ends with its last settled covered day; a switch flipped today says nothing about it.
        val currentEndMs = currentDays.maxOfOrNull { it.date }?.let { ctx.zones.window(it, ctx.rolloverHour).endMs } ?: scope.toMs
        val input = FrictionInput(
            events = events,
            distractingOpens = opens,
            fromMs = scope.fromMs,
            toMs = scope.toMs,
            dataEndMs = scope.dataEndMs,
            coverage = UsageCoverage.of(ctx.coverage),
            coveredDays = scope.coveredDates.size,
            coveredDaySpans = scope.coveredDates.map { ctx.zones.window(it, ctx.rolloverHour).let { w -> Span(w.startMs, w.endMs) } },
            frictionCoverageRatios = scope.coveredDates.mapNotNull { byDate[it]?.frictionCoverageRatio },
            current = currentDays,
            previous = substitutionDays(previous),
            currentNeutralApps = neutralApps(current),
            previousNeutralApps = neutralApps(previous),
            enabledHistory = enabled,
            substitutionFromMs = currentStartMs,
            substitutionToMs = currentEndMs,
        )
        FrictionData(scope, FrictionAnalyzer.analyze(input, apps, frictionRules), apps)
    }

    // -- Check-in -------------------------------------------------------------------------------------------------------

    /**
     * The rabbit hole to ask "Was that worth it?" about right now, if any. Recording that it was offered is part of
     * asking, so the per-day cap holds across screens and restarts.
     */
    suspend fun checkIn(): CheckInCard? {
        val store = checkInStore ?: return null
        val now = clock()
        val (ctx, candidates, apps) = io {
            val ctx = derived.analyticsContext()
            val since = now - checkInRules.recentMs
            Triple(ctx, toLab(dao.rabbitHolesEndingSince(since), dao.rabbitHoleSegmentsEndingSince(since)), LabApps(metas()))
        }
        var picked: String? = null
        store.update { raw ->
            val log = decodeLog(raw)
            val sel = WorthCheckInSampler.select(log, ctx.today, candidates, now, ctx.derivedThroughMs, checkInRules)
            picked = sel.episodeKey
            if (sel.log == log && raw != null) raw else encodeLog(sel.log)
        }
        val key = picked ?: return null
        return candidates.firstOrNull { it.episodeKey == key }?.let { CheckInCard(it, apps) }
    }

    /** "Not now": this episode is never offered again. */
    suspend fun dismissCheckIn(episodeKey: String) {
        val store = checkInStore ?: return
        store.update { encodeLog(WorthCheckInSampler.dismiss(decodeLog(it), episodeKey, checkInRules)) }
    }

    /** The session behind [episodeKey] with its segments, for the session sheet. */
    suspend fun session(episodeKey: String): Pair<SessionEntity, List<SessionSegmentEntity>>? = io {
        val s = dao.sessionByKey(episodeKey) ?: return@io null
        s to dao.segmentsOf(s.id)
    }

    suspend fun packages(): Map<Long, PackageEntity> = io { db.packageDao().all().associateBy { it.id } }

    // -- Shared ---------------------------------------------------------------------------------------------------------

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { db.withReadTransaction { block() } }

    private suspend fun earliest(): LocalDate? = dao.earliestRollupDate()?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private suspend fun scope(ctx: AnalyticsContext, window: TrendWindow): LabScope {
        val range = window.resolve(ctx.today, earliest())
        val rollups = dao.rollups(range.start.toString(), range.end.toString())
        val days = TrendsRepository.buildDays(range, rollups, emptyList(), ctx.firstUnsettled)
        val fromMs = ctx.zones.window(range.start, ctx.rolloverHour).startMs
        val toMs = ctx.zones.window(range.end, ctx.rolloverHour).endMs
        return LabScope(
            today = ctx.today,
            window = window,
            range = range,
            rolloverHour = ctx.rolloverHour,
            fromMs = fromMs,
            toMs = toMs,
            dataEndMs = minOf(ctx.derivedThroughMs, clock()),
            coveredDates = range.dates().filter { days[it]?.quality?.countsAsCovered == true },
        )
    }

    private suspend fun facts(ctx: AnalyticsContext, from: LocalDate, to: LocalDate): Map<LocalDate, DayFacts> {
        val range = DateRange(minOf(from, to), to)
        val rollups = dao.rollups(range.start.toString(), range.end.toString())
        val byDate = rollups.associateBy { it.logicalDate }
        return TrendsRepository.buildDays(range, rollups, emptyList(), ctx.firstUnsettled).mapValues { (date, m) -> m.toFacts(byDate[date.toString()]) }
    }

    private fun DailyMetrics.toFacts(row: DailyRollupEntity?) =
        DayFacts(date, quality, activeMs, distractingMs, budgetMs, coverageOk = row != null && row.dayStatus != DayStatus.UNCERTAIN_COVERAGE.name)

    private suspend fun episodesBetween(from: LocalDate, to: LocalDate): List<LabEpisode> =
        toLab(dao.unlockedSessions(from.toString(), to.toString()), dao.unlockedSegments(from.toString(), to.toString()))

    private fun toLab(sessions: List<SessionEntity>, segments: List<SessionSegmentEntity>): List<LabEpisode> {
        val bySession = segments.groupBy { it.sessionId }
        return sessions.map { it.toLab(bySession[it.id].orEmpty()) }
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

    companion object {
        const val BASELINE_LOOKBACK_DAYS = 28L
        const val FRICTION_ENABLED_KEY = "friction_enabled"
        private val NIGHT_RULES = RollupRules()

        private val json = Json { ignoreUnknownKeys = true }

        fun decodeLog(raw: String?): CheckInLog =
            raw?.let { runCatching { json.decodeFromString(CheckInLog.serializer(), it) }.getOrNull() } ?: CheckInLog()

        fun encodeLog(log: CheckInLog): String = json.encodeToString(CheckInLog.serializer(), log)

        private fun evidenceOf(name: String?): TriggerEvidence? = name?.let { n -> TriggerEvidence.entries.firstOrNull { it.name == n } }

        fun reasonsOf(csv: String?): List<RabbitHoleReason> =
            csv?.split(',')?.mapNotNull { n -> RabbitHoleReason.entries.firstOrNull { it.name == n.trim() } }.orEmpty()

        fun SessionEntity.toLab(segments: List<SessionSegmentEntity>) = LabEpisode(
            id = id,
            episodeKey = episodeKey,
            logicalDate = LocalDate.parse(logicalDate),
            startMs = startMs,
            endMs = endMs,
            activeDurationMs = activeDurationMs,
            totalDistractingMs = totalDistractingMs,
            triggerEvidence = evidenceOf(triggerEvidence) ?: TriggerEvidence.NO_OBSERVED_TRIGGER,
            triggerPkgId = triggerPkgId,
            triggerNotificationId = triggerNotificationId,
            entryPkgId = entryPkgId,
            isRabbitHole = isRabbitHole,
            rabbitHoleReasons = reasonsOf(rabbitHoleReasons),
            worthItRating = worthItRating,
            inProgress = contextFlags?.contains("IN_PROGRESS") == true,
            segments = segments.sortedBy { it.seqIndex }.map {
                LabSegment(it.pkgId, it.seqIndex, it.startMs, it.endMs, it.durationMs, it.isBlockedAttempt, evidenceOf(it.segmentTriggerEvidence), it.segmentTriggerPkgId)
            },
        )

        /**
         * Per-app active use inside [window] from stored segments. A segment's launch pauses aren't placed within it, so
         * its overlap is scaled by its own active share (duration over wall span).
         */
        fun clippedUse(segments: List<SessionSegmentEntity>, window: Span, pkgs: Set<Long>): Map<Long, Long> {
            val out = HashMap<Long, Long>()
            for (g in segments) {
                if (g.pkgId !in pkgs || g.isBlockedAttempt) continue
                val wall = g.endMs - g.startMs
                if (wall <= 0) continue
                val overlap = minOf(g.endMs, window.endMs) - maxOf(g.startMs, window.startMs)
                if (overlap <= 0) continue
                val ms = if (g.durationMs >= wall) overlap else overlap * g.durationMs / wall
                if (ms > 0) out.merge(g.pkgId, ms, Long::plus)
            }
            return out
        }

        fun RawNotificationEntity.toLab() = LabNotification(
            id = id,
            pkgId = pkgId,
            channelId = channelId,
            postedAtMs = postedAtMs,
            removedAtMs = removedAtMs,
            removalReason = RemovalReason.entries.firstOrNull { it.name == removalReason } ?: RemovalReason.OTHER,
            importance = importance,
            isOngoing = isOngoing,
            isForegroundService = isForegroundService,
            isGroupSummary = isGroupSummary,
            isAmbient = isAmbient,
            matchedInterruptionFilter = matchedInterruptionFilter,
            dndOrSilentAtPost = dndOrSilentAtPost,
        )
    }
}
