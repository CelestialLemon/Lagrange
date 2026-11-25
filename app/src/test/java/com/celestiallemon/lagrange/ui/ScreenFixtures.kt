package com.celestiallemon.lagrange.ui

import com.celestiallemon.lagrange.data.TodayComparison
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.Archetype
import com.celestiallemon.lagrange.data.db.DailyAppRollupEntity
import com.celestiallemon.lagrange.data.db.DailyNoteEntity
import com.celestiallemon.lagrange.data.db.DailyRollupEntity
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.data.db.SessionEntity
import com.celestiallemon.lagrange.data.db.SessionSegmentEntity
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.SliceBaseline
import com.celestiallemon.lagrange.domain.SliceCutoff
import com.celestiallemon.lagrange.system.Check
import com.celestiallemon.lagrange.system.PermissionHealth
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** A plausible Tuesday for screenshots: realistic apps, sessions and numbers, nothing real. */
object ScreenFixtures {
    val date: LocalDate = LocalDate.of(2026, 11, 24)
    private val zone = ZoneId.systemDefault()
    private const val MIN = 60_000L

    fun at(h: Int, m: Int): Long = date.atTime(LocalTime.of(h, m)).atZone(zone).toInstant().toEpochMilli()

    val now = at(16, 42)

    private fun pkg(id: Long, name: String, label: String, cat: AppCategory) =
        PackageEntity(id = id, packageName = name, displayName = label, category = cat.name, categoryConfirmed = true, updatedAtMs = 0)

    val packages: Map<Long, PackageEntity> = listOf(
        pkg(1, "com.instagram.android", "Instagram", AppCategory.DISTRACTING),
        pkg(2, "com.google.android.youtube", "YouTube", AppCategory.DISTRACTING),
        pkg(3, "com.reddit.frontpage", "Reddit", AppCategory.DISTRACTING),
        pkg(4, "com.whatsapp", "WhatsApp", AppCategory.NEUTRAL),
        pkg(5, "com.android.chrome", "Chrome", AppCategory.NEUTRAL),
        pkg(6, "com.google.android.apps.maps", "Maps", AppCategory.UTILITY),
        pkg(7, "com.google.android.gm", "Gmail", AppCategory.NEUTRAL),
        pkg(8, "com.spotify.music", "Spotify", AppCategory.EXCLUDED),
    ).associateBy { it.id }

    private var nextId = 1L

    private fun session(
        h: Int, m: Int, minutes: Double, apps: List<Pair<Long, Double>>, archetype: Archetype,
        trigger: TriggerEvidence = TriggerEvidence.NO_OBSERVED_TRIGGER, rabbit: Boolean = false,
        glance: Boolean = false, inProgress: Boolean = false, blocked: Int = 0,
    ): EpisodeUi {
        val id = nextId++
        val start = at(h, m)
        val dur = (minutes * MIN).toLong()
        var t = start
        val segs = apps.mapIndexed { i, (p, mins) ->
            val d = (mins * MIN).toLong()
            SessionSegmentEntity(id * 10 + i, id, i, p, t, t + d, d, false, if (i == 0) trigger.name else null, null).also { t += d }
        }
        val distracting = apps.filter { packages[it.first]?.category == AppCategory.DISTRACTING.name }.sumOf { (it.second * MIN).toLong() }
        return EpisodeUi(
            SessionEntity(
                id = id, episodeKey = "k$id", algorithmVersion = 1, logicalDate = date.toString(), startMs = start, endMs = start + dur,
                activeDurationMs = dur, physicalPickupCount = 1, isGlanceOnly = glance, triggerEvidence = trigger.name, triggerPkgId = apps.firstOrNull()?.first,
                triggerNotificationId = null, entryPkgId = apps.firstOrNull()?.first, totalDistractingMs = distracting, totalNeutralMs = 0, totalUtilityMs = 0,
                totalExcludedMs = 0, blockedAttemptCount = blocked, distinctAppCount = apps.size, distinctDistractingCount = 0, archetype = archetype.name,
                isRabbitHole = rabbit, rabbitHoleReasons = if (rabbit) "LONG_BINGE,MULTI_APP_SPIRAL" else null, worthItRating = null,
                contextFlags = if (inProgress) "IN_PROGRESS" else null,
            ),
            segs,
        )
    }

    fun episodes(): List<EpisodeUi> {
        nextId = 1
        return listOf(
            session(7, 2, 0.2, emptyList(), Archetype.GLANCE, glance = true),
            session(7, 15, 6.0, listOf(4L to 2.0, 7L to 4.0), Archetype.MIXED, TriggerEvidence.PENDING_NOTIFICATION),
            session(8, 40, 0.1, emptyList(), Archetype.GLANCE, glance = true),
            session(8, 41, 0.2, emptyList(), Archetype.GLANCE, glance = true),
            session(9, 5, 1.0, listOf(6L to 1.0), Archetype.UTILITY),
            session(10, 32, 0.8, listOf(4L to 0.8), Archetype.MICRO_CHECK, TriggerEvidence.OBSERVED_CLICK),
            session(12, 48, 34.0, listOf(1L to 14.0, 3L to 12.0, 2L to 8.0), Archetype.RABBIT_HOLE, TriggerEvidence.RECENT_POSSIBLE_ALERT, rabbit = true),
            session(14, 10, 0.1, emptyList(), Archetype.GLANCE, glance = true),
            session(14, 20, 12.0, listOf(5L to 9.0, 1L to 3.0), Archetype.MIXED, blocked = 1),
            session(15, 55, 4.0, listOf(1L to 4.0), Archetype.MICRO_CHECK),
            session(16, 38, 4.0, listOf(5L to 4.0), Archetype.FOCUSED, inProgress = true),
        )
    }

    fun rollup(status: DayStatus = DayStatus.GOOD_DAY, distracting: Long = 41 * MIN) = DailyRollupEntity(
        logicalDate = date.toString(), algorithmVersion = 1, coverageStatus = "FULL", notifCoverageRatio = 1.0, frictionCoverageRatio = 1.0,
        totalScreenMs = 132 * MIN, activeUsageMs = 118 * MIN, distractingMs = distracting, neutralMs = 52 * MIN, utilityMs = 9 * MIN,
        excludedMs = 16 * MIN, selfAppMs = 2 * MIN, episodeCount = 23, physicalPickupCount = 41, glanceCount = 18, microCheckCount = 9,
        rabbitHoleCount = 1, blockedAttemptCount = 3, notificationCount = 64, longestFocusGapMs = 142 * MIN, overnightGapMs = 452 * MIN,
        firstPickupMs = at(7, 2), lastActivityMs = at(16, 40), budgetTargetMs = 90 * MIN, baselineMedianMs = 160 * MIN,
        dayStatus = status.name, spikeSummaryJson = null,
    )

    fun apps() = listOf(1L to 24, 4L to 21, 5L to 17, 3L to 12, 2L to 8, 8L to 16, 6L to 9, 7L to 6)
        .sortedByDescending { it.second }
        .map { (p, m) -> DailyAppRollupEntity(date.toString(), p, m * MIN, (m / 3).coerceAtLeast(1), 0, 1, 0) }

    fun today(status: DayStatus = DayStatus.GOOD_DAY, distracting: Long = 41 * MIN, note: String? = "Deadline day") = TodayState(
        date = date,
        rollup = rollup(status, distracting),
        episodes = episodes(),
        apps = apps(),
        packages = packages,
        note = note?.let { DailyNoteEntity(date.toString(), it, 0) },
        budgetMs = 90 * MIN,
        comparison = TodayComparison(date, now, SliceBaseline(118 * MIN, 151 * MIN, 9, SliceCutoff(12, 0.5)), 24, 196 * MIN, 7),
    )

    val stats = CollectorStats(rawEventsTotal = 1_204_331, rawEventsToday = 4_812, notificationsTotal = 88_120, notificationsToday = 64, lastHarvestAtMs = now - 9 * MIN, listenerActive = true)

    val health = PermissionHealth(
        usageAccess = true, notificationAccess = true, accessibilityEnabled = true, accessibilityConnected = true, postNotifications = true,
        hibernationExempt = Check.YES, advancedProtection = Check.NO, batteryUnrestricted = true,
    )

    fun catalog() = packages.values.map {
        com.celestiallemon.lagrange.data.CatalogApp(it.packageName, it.displayName, AppCategory.valueOf(it.category), confirmed = it.id != 3L, recentOpens = (40 - it.id * 4).coerceAtLeast(0))
    }

    fun friction(mode: String) = com.celestiallemon.lagrange.ui.friction.FrictionUiModel(
        prefs = com.celestiallemon.lagrange.data.UserPrefs(frictionEnabled = mode != "off", redirectPackageName = "com.google.android.apps.maps", dailyBudgetMs = 90 * MIN),
        health = health,
        schedules = listOf(
            com.celestiallemon.lagrange.data.db.FrictionScheduleEntity(1, "Work Hours", true, 0b0011111, 540, 1050, 1, false, null),
            com.celestiallemon.lagrange.data.db.FrictionScheduleEntity(2, "Wind-Down", false, 0b1111111, 1350, 240, 0, true, 10),
        ),
        live = com.celestiallemon.lagrange.domain.friction.FrictionLiveStatus(
            snoozeUntilElapsedMs = if (mode == "snoozed") 1_000_000L + 42 * MIN else null,
            activeTimerRemainingMs = if (mode == "on") 7 * MIN else null,
            activeTimerInitialMin = if (mode == "on") 10 else null,
        ),
        blockedToday = 4,
        error = null,
        apps = catalog(),
        nowElapsed = 1_000_000L,
    )

    /** 90 days of history with a gentle downward trend, a few partial days and a gap, run through the real calculator. */
    fun trends(): com.celestiallemon.lagrange.ui.trends.TrendsUiModel {
        val window = com.celestiallemon.lagrange.domain.TrendWindow(com.celestiallemon.lagrange.domain.WindowKind.D30)
        val start = date.minusDays(89)
        val rnd = java.util.Random(7)
        val days = (0L..89L).associate { i ->
            val d = start.plusDays(i)
            val weekend = d.dayOfWeek.value >= 6
            val quality = when {
                i in 40L..42L -> com.celestiallemon.lagrange.domain.DayQuality.NO_DATA
                i % 17L == 5L -> com.celestiallemon.lagrange.domain.DayQuality.PARTIAL
                i == 89L -> com.celestiallemon.lagrange.domain.DayQuality.IN_PROGRESS
                else -> com.celestiallemon.lagrange.domain.DayQuality.COVERED
            }
            val base = (95 - i * 0.45 + (if (weekend) 25 else 0) + rnd.nextGaussian() * 14).coerceAtLeast(12.0)
            val mins = if (quality == com.celestiallemon.lagrange.domain.DayQuality.PARTIAL) base * 0.4 else base
            val status = when {
                quality != com.celestiallemon.lagrange.domain.DayQuality.COVERED -> null
                mins > 120 -> DayStatus.SPIKE
                mins < 45 -> DayStatus.GOOD_DAY
                else -> DayStatus.NORMAL
            }
            d to com.celestiallemon.lagrange.domain.DailyMetrics(
                d, quality, distractingMs = (mins * MIN).toLong(), activeMs = (mins * 2.4 * MIN).toLong(), screenMs = (mins * 2.8 * MIN).toLong(),
                episodes = (mins / 4).toInt(), pickups = (mins / 2.6).toInt(), glances = 18, rabbitHoles = if (mins > 100) 2 else 0,
                budgetMs = 90 * MIN, dayStatus = status,
            )
        }
        val range = com.celestiallemon.lagrange.domain.DateRange(date.minusDays(29), date)
        val report = com.celestiallemon.lagrange.domain.TrendCalculator.report(com.celestiallemon.lagrange.domain.TrendMetric.DISTRACTING, window, range, days)
        fun row(id: Long, avgMin: Double, opens: Int, trend: Double?, goal: Long? = null): com.celestiallemon.lagrange.domain.AppRow {
            val p = packages.getValue(id)
            return com.celestiallemon.lagrange.domain.AppRow(
                com.celestiallemon.lagrange.domain.AppMeta(id, p.packageName, p.displayName, AppCategory.valueOf(p.category), goal),
                totalMs = (avgMin * 30 * MIN).toLong(), opens = opens, dailyAvgMs = avgMin * MIN, dailyAvgOpens = opens / 30.0, trendPct = trend, previousAvgMs = null,
            )
        }
        val apps = listOf(row(1, 31.0, 412, -0.18, 30 * MIN), row(4, 26.0, 655, 0.04), row(5, 22.0, 290, 0.11), row(3, 17.0, 160, -0.31), row(2, 14.0, 88, 0.22), row(6, 6.0, 41, null))
        val dataset = com.celestiallemon.lagrange.data.TrendDataset(date, window, range, days, start)
        return com.celestiallemon.lagrange.ui.trends.TrendsUiModel(
            com.celestiallemon.lagrange.ui.trends.TrendsUiState(window, com.celestiallemon.lagrange.data.TrendsOverview(dataset, apps), loading = false),
            report, apps, com.celestiallemon.lagrange.domain.TrendMetric.DISTRACTING, "", null, com.celestiallemon.lagrange.domain.AppSort.TIME,
        )
    }
}
