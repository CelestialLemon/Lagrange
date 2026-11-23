package com.celestiallemon.lagrange.digest

import androidx.room3.withReadTransaction
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
    /**
     * The daily digest for the occurrence due at [dueAtMs] (null: as of now, for previews). The day it summarizes is
     * chosen from the due instant, so a run delayed past the logical rollover still reports the intended day. When
     * the derived data stops short of the end of that day (or of now, for today so far) the day is a lower bound.
     */
    suspend fun composeDaily(dueAtMs: Long? = null): Digest? = read {
        val now = clock()
        val ctx = derived.analyticsContext()
        // The supplied due instant is kept even if the wall clock was moved back: the run is about its occurrence.
        val anchor = dueAtMs ?: now
        val anchorDate = ctx.zones.dateOf(anchor, ctx.rolloverHour)
        val anchorStart = ctx.zones.window(anchorDate, ctx.rolloverHour).startMs
        val target = DigestComposer.dailyTarget(anchor, anchorDate, anchorStart)
        val targetEnd = ctx.zones.window(target, ctx.rolloverHour).endMs
        val days = days(ctx, DateRange(target, target))
        val tail = DigestComposer.unobservedTail(ctx.derivedThroughMs, minOf(now, targetEnd))
        val day = days[target]?.let { if (tail) it.copy(quality = DayQuality.PARTIAL) else it }
        if (day == null || day.quality == DayQuality.NO_DATA) return@read null // nothing honest to say: skip the Lab
        val window = TrendWindow(WindowKind.CUSTOM, target, target)
        DigestComposer.daily(
            DailyDigestInput(
                today = ctx.today,
                day = day,
                biggestRabbitHole = biggestRabbitHole(ctx, window),
                exits = exits(window),
                currentBudgetMs = preferences.current().dailyBudgetMs,
                recordedThrough = if (tail && ctx.derivedThroughMs != Long.MIN_VALUE) {
                    Instant.ofEpochMilli(ctx.derivedThroughMs).atZone(ctx.zones.zoneAt(ctx.derivedThroughMs)).toLocalDateTime()
                } else {
                    null
                },
            ),
        )
    }

    /**
     * The weekly digest for the occurrence due at [dueAtMs] (null: now): the seven logical days before the due day and
     * the seven before those, keyed by the due day's ISO week, so a run delayed into the next week can't claim (and
     * suppress) that week's own digest.
     */
    suspend fun composeWeekly(dueAtMs: Long? = null): Digest? = read {
        val now = clock()
        val ctx = derived.analyticsContext()
        // The supplied due instant is kept even if the wall clock was moved back: the run is about its occurrence.
        val anchor = dueAtMs ?: now
        val anchorDate = ctx.zones.dateOf(anchor, ctx.rolloverHour)
        val (current, previous) = DigestComposer.weeklyRanges(anchorDate)
        val days = days(ctx, DateRange(previous.first(), current.last()))
        if (current.none { days[it]?.covered == true }) return@read null // no fully covered day: nothing to say
        val window = TrendWindow(WindowKind.CUSTOM, current.first(), current.last())
        DigestComposer.weekly(
            WeeklyDigestInput(
                today = anchorDate,
                current = current.map { days[it] ?: DigestDay(it, DayQuality.NO_DATA) },
                previous = previous.map { days[it] ?: DigestDay(it, DayQuality.NO_DATA) },
                biggestRabbitHole = biggestRabbitHole(ctx, window),
                exits = exits(window),
            ),
        )
    }

    /**
     * One read transaction for everything a digest reads (context, rollups, Lab), so a rebuild or rollover landing
     * mid-compose can't mix two generations of derived data in one notification.
     */
    private suspend fun <T> read(block: suspend () -> T): T = withContext(Dispatchers.IO) { db.withReadTransaction { block() } }

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
            // Unknown coverage or status (a newer writer, a corrupt row): never presented as whole.
            DayCoverage.entries.none { it.name == row.coverageStatus } || DayStatus.entries.none { it.name == row.dayStatus } -> DayQuality.PARTIAL
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
