package com.celestiallemon.lagrange.digest

import com.celestiallemon.lagrange.data.AnalyticsContext
import com.celestiallemon.lagrange.data.DerivedRepository
import com.celestiallemon.lagrange.data.LabRepository
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.TrendsRepository
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.WindowKind
import com.celestiallemon.lagrange.domain.digest.DailyDigestInput
import com.celestiallemon.lagrange.domain.digest.Digest
import com.celestiallemon.lagrange.domain.digest.DigestComposer
import com.celestiallemon.lagrange.domain.digest.DigestDay
import com.celestiallemon.lagrange.domain.digest.DigestExits
import com.celestiallemon.lagrange.domain.digest.DigestRabbitHole
import com.celestiallemon.lagrange.domain.digest.WeeklyDigestInput
import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Gathers the facts a reflection digest needs from the derived tables and the Behavior Lab (so rabbit holes and
 * sustained exits are counted exactly as the Lab counts them), then hands them to the pure [DigestComposer].
 * Read-only.
 */
class DigestRepository(
    private val db: LagrangeDatabase,
    private val derived: DerivedRepository,
    private val lab: LabRepository,
    private val preferences: LagrangePreferences,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    suspend fun composeDaily(): Digest? = withContext(Dispatchers.IO) {
        val now = clock()
        val ctx = derived.analyticsContext()
        val todayStart = ctx.zones.window(ctx.today, ctx.rolloverHour).startMs
        val target = DigestComposer.dailyTarget(now, ctx.today, todayStart)
        val days = days(ctx, DateRange(target, target))
        val window = TrendWindow(WindowKind.CUSTOM, target, target)
        DigestComposer.daily(
            DailyDigestInput(
                today = ctx.today,
                day = days[target],
                biggestRabbitHole = biggestRabbitHole(ctx, window),
                exits = exits(window),
                currentBudgetMs = preferences.current().dailyBudgetMs,
            ),
        )
    }

    suspend fun composeWeekly(): Digest? = withContext(Dispatchers.IO) {
        val ctx = derived.analyticsContext()
        val (current, previous) = DigestComposer.weeklyRanges(ctx.today)
        val days = days(ctx, DateRange(previous.first(), current.last()))
        val window = TrendWindow(WindowKind.CUSTOM, current.first(), current.last())
        DigestComposer.weekly(
            WeeklyDigestInput(
                today = ctx.today,
                current = current.map { days[it] ?: DigestDay(it, DayQuality.NO_DATA) },
                previous = previous.map { days[it] ?: DigestDay(it, DayQuality.NO_DATA) },
                biggestRabbitHole = biggestRabbitHole(ctx, window),
                exits = exits(window),
            ),
        )
    }

    /** The days of [range] that have a rollup, with the same settled/covered judgement Trends uses. */
    private suspend fun days(ctx: AnalyticsContext, range: DateRange): Map<LocalDate, DigestDay> {
        val rows = db.analyticsDao().rollups(range.start.toString(), range.end.toString())
        val byDate = rows.associateBy { it.logicalDate }
        val metrics = TrendsRepository.buildDays(range, rows, emptyList(), ctx.firstUnsettled)
        return metrics.mapNotNull { (date, m) ->
            val row = byDate[date.toString()] ?: return@mapNotNull null
            date to toDay(date, m.quality, row, ctx.today)
        }.toMap()
    }

    private fun toDay(date: LocalDate, quality: DayQuality, row: DailyRollupEntity, today: LocalDate): DigestDay = DigestDay(
        date = date,
        quality = when {
            quality != DayQuality.IN_PROGRESS -> quality
            // A past day whose rebuild hasn't caught up yet may be clipped: report it as a lower bound, never as whole.
            date.isBefore(today) -> DayQuality.PARTIAL
            // Today so far, unless collection already had a gap today: then it is a lower bound too.
            DayQuality.of(
                runCatching { DayCoverage.valueOf(row.coverageStatus) }.getOrNull(),
                runCatching { DayStatus.valueOf(row.dayStatus) }.getOrNull(),
                inProgress = false,
            ) == DayQuality.PARTIAL -> DayQuality.PARTIAL
            else -> DayQuality.IN_PROGRESS
        },
        distractingMs = row.distractingMs,
        activeMs = row.activeUsageMs,
        budgetMs = row.budgetTargetMs,
        baselineMedianMs = row.baselineMedianMs,
        dayStatus = runCatching { DayStatus.valueOf(row.dayStatus) }.getOrNull(),
        longestFocusGapMs = row.longestFocusGapMs,
    )

    private suspend fun biggestRabbitHole(ctx: AnalyticsContext, window: TrendWindow): DigestRabbitHole? {
        val data = lab.rabbitHoles(window)
        val top = data.episodes.filter { it.isRabbitHole }.maxByOrNull { it.totalDistractingMs } ?: return null
        val zone = ctx.zones.zoneAt(top.startMs)
        return DigestRabbitHole(
            date = top.logicalDate,
            startLocal = Instant.ofEpochMilli(top.startMs).atZone(zone).toLocalTime(),
            distractingMs = top.totalDistractingMs,
            apps = data.apps.hopChain(top).map(data.apps::name),
        )
    }

    private suspend fun exits(window: TrendWindow): DigestExits {
        val report = lab.friction(window).report
        return DigestExits(report.exits.exits, report.exits.sustained)
    }
}
