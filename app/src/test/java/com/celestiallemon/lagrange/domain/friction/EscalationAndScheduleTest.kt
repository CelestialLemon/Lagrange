package com.celestiallemon.lagrange.domain.friction

import com.celestiallemon.lagrange.data.db.AppCategory
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EscalationAndScheduleTest {
    private val reddit = FrictionAppPolicy(1L, "com.reddit.frontpage", "Reddit", AppCategory.DISTRACTING)
    private val chrome = FrictionAppPolicy(2L, "com.android.chrome", "Chrome", AppCategory.NEUTRAL)
    private val maps = FrictionAppPolicy(3L, "com.google.android.apps.maps", "Maps", AppCategory.UTILITY)
    private val kindle = FrictionAppPolicy(4L, "com.amazon.kindle", "Kindle", AppCategory.NEUTRAL)
    private val enabledSettings = FrictionSettings(frictionEnabled = true)

    private fun usage(realOpens: Int = 0, distractingMin: Long = 0, prompts: Int = 0) = TodayUsageSnapshot(
        logicalDate = "2026-11-23",
        appRealOpensToday = realOpens,
        distractingMsToday = distractingMin * 60_000L,
        promptsShownToday = prompts,
        budgetMs = 60 * 60_000L,
    )

    @Test
    fun `base tiers follow spec thresholds on real opens and distracting minutes`() {
        // Real opens 1–2 & <15m -> Tier 0 (3s)
        assertEquals(0, EscalationPolicy.baseTier(0, 0))
        assertEquals(0, EscalationPolicy.baseTier(1, 14 * 60_000L))
        // Real opens 3–5 or 15–44m -> Tier 1 (5s)
        assertEquals(1, EscalationPolicy.baseTier(2, 0))
        assertEquals(1, EscalationPolicy.baseTier(4, 10 * 60_000L))
        assertEquals(1, EscalationPolicy.baseTier(0, 15 * 60_000L))
        assertEquals(1, EscalationPolicy.baseTier(0, 44 * 60_000L))
        // Real opens 6–10 or 45–89m -> Tier 2 (8s)
        assertEquals(2, EscalationPolicy.baseTier(5, 0))
        assertEquals(2, EscalationPolicy.baseTier(9, 20 * 60_000L))
        assertEquals(2, EscalationPolicy.baseTier(0, 45 * 60_000L))
        // Real opens 11+ or 90m+ -> Tier 3 (12s)
        assertEquals(3, EscalationPolicy.baseTier(10, 0))
        assertEquals(3, EscalationPolicy.baseTier(0, 90 * 60_000L))
    }

    @Test
    fun `schedule boost and focus boost elevate tier and features up to tier 3`() {
        val schedule = ActiveScheduleEffect(
            primaryScheduleId = 7L,
            primaryScheduleName = "Wind-Down",
            escalationBoost = 1,
            requireTimerPrompt = true,
            nudgeIntervalMin = 10,
        )
        // Base Tier 0 + schedule(+1) + focusBoost(+1) = Tier 2 (8s, timer chips + intention chips)
        val decision = EscalationPolicy.evaluateLaunch(
            app = reddit,
            usage = usage(realOpens = 0, distractingMin = 5),
            settings = enabledSettings,
            activeSchedule = schedule,
            focusBoostActive = true,
            snoozeActive = false,
            inGraceWindow = false,
            redirectTarget = kindle,
        ) as LaunchDecision.ShowPause

        assertEquals(2, decision.plan.tier)
        assertEquals(8, decision.plan.pauseDurationSec)
        assertTrue(decision.plan.showTimerChips)
        assertTrue(decision.plan.encourageTimerSelection)
        assertTrue(decision.plan.showIntentionChips)
        assertFalse(decision.plan.showBudgetComparison)
        assertEquals("Kindle", decision.plan.redirectDisplayName)
        assertEquals(7L, decision.plan.scheduleId)
    }

    @Test
    fun `per-app overrides allow selective rollout, neutral opt-in, fixed pause, and never gate utility`() {
        // Selective rollout: disable friction on one distracting app.
        val disabledReddit = reddit.copy(override = AppFrictionOverride(enabled = false))
        assertEquals(
            LaunchDecision.Suppress(SuppressionReason.APP_NOT_ELIGIBLE),
            EscalationPolicy.evaluateLaunch(disabledReddit, usage(), enabledSettings, null, false, false, false),
        )

        // Neutral opt-in with fixed pause override.
        val optedInChrome = chrome.copy(override = AppFrictionOverride(enabled = true, fixedPauseSec = 7, requireTimerPrompt = true))
        val decision = EscalationPolicy.evaluateLaunch(optedInChrome, usage(), enabledSettings, null, false, false, false) as LaunchDecision.ShowPause
        assertEquals(7, decision.plan.pauseDurationSec)
        assertTrue(decision.plan.showTimerChips)

        // Utility app is NEVER friction-gated even with enabled = true override.
        val forcedMaps = maps.copy(override = AppFrictionOverride(enabled = true))
        assertFalse(forcedMaps.isFrictionEligible)

        // Unconfirmed auto-suggested distracting app does not gate by default until confirmed or explicitly opted in.
        val unconfirmedReddit = reddit.copy(categoryConfirmed = false)
        assertFalse(unconfirmedReddit.isFrictionEligible)
        assertTrue(unconfirmedReddit.copy(override = AppFrictionOverride(enabled = true)).isFrictionEligible)

        // Redirect revalidation rejects uninstalled, distracting, or friction-eligible neutral targets.
        val uninstalledKindle = kindle.copy(isInstalled = false)
        val noRedirect1 = EscalationPolicy.evaluateLaunch(reddit, usage(), enabledSettings, null, false, false, false, redirectTarget = uninstalledKindle) as LaunchDecision.ShowPause
        assertNull(noRedirect1.plan.redirectPackageName)
        val noRedirect2 = EscalationPolicy.evaluateLaunch(reddit, usage(), enabledSettings, null, false, false, false, redirectTarget = optedInChrome) as LaunchDecision.ShowPause
        assertNull(noRedirect2.plan.redirectPackageName)

        // JSON round-trip.
        val raw = optedInChrome.override!!.toJsonOrNull()
        assertNotNull(raw)
        assertEquals(optedInChrome.override, AppFrictionOverride.parse(raw))
        assertNull(AppFrictionOverride().toJsonOrNull())
    }

    @Test
    fun `daily prompt cap and snooze and grace window suppress launches`() {
        val capped = enabledSettings.copy(dailyPromptCap = 5)
        assertEquals(
            LaunchDecision.Suppress(SuppressionReason.DAILY_CAP_REACHED),
            EscalationPolicy.evaluateLaunch(reddit, usage(prompts = 5), capped, null, false, false, false),
        )
        assertEquals(
            LaunchDecision.Suppress(SuppressionReason.SNOOZED),
            EscalationPolicy.evaluateLaunch(reddit, usage(), enabledSettings, null, false, snoozeActive = true, inGraceWindow = false),
        )
        assertEquals(
            LaunchDecision.Suppress(SuppressionReason.GRACE_WINDOW),
            EscalationPolicy.evaluateLaunch(reddit, usage(), enabledSettings, null, false, snoozeActive = false, inGraceWindow = true),
        )
    }

    @Test
    fun `schedule matcher handles weekday windows and midnight wrap across day boundary`() {
        val zone = ZoneId.of("UTC")
        val weeknightWindDown = FrictionSchedule(
            id = 1L,
            name = "Weeknight Wind-Down",
            enabled = true,
            daysOfWeekMask = FrictionSchedule.MASK_WEEKDAYS, // Mon..Fri
            startMinuteOfDay = 22 * 60 + 30, // 22:30
            endMinuteOfDay = 4 * 60, // 04:00
            escalationBoost = 1,
            requireTimerPrompt = true,
            nudgeIntervalMin = 10,
        )
        // Friday 2026-11-27 23:00 -> active (Friday evening).
        assertTrue(ScheduleMatcher.matches(weeknightWindDown, ZonedDateTime.of(2026, 11, 27, 23, 0, 0, 0, zone)))
        // Saturday 2026-11-28 02:30 -> active (belongs to Friday night's wrap!).
        assertTrue(ScheduleMatcher.matches(weeknightWindDown, ZonedDateTime.of(2026, 11, 28, 2, 30, 0, 0, zone)))
        // Saturday 2026-11-28 04:00 -> ended (half-open end).
        assertFalse(ScheduleMatcher.matches(weeknightWindDown, ZonedDateTime.of(2026, 11, 28, 4, 0, 0, 0, zone)))
        // Saturday 2026-11-28 23:00 -> NOT active (Saturday not in Mon..Fri).
        assertFalse(ScheduleMatcher.matches(weeknightWindDown, ZonedDateTime.of(2026, 11, 28, 23, 0, 0, 0, zone)))
        // Monday 2026-11-30 01:00 -> NOT active (belongs to Sunday night, which is not in Mon..Fri).
        assertFalse(ScheduleMatcher.matches(weeknightWindDown, ZonedDateTime.of(2026, 11, 30, 1, 0, 0, 0, zone)))
    }

    @Test
    fun `overlapping schedules combine max boost, tightest nudge, and OR timer prompt`() {
        val zone = ZoneId.of("UTC")
        val s1 = FrictionSchedule(1, "Work", true, FrictionSchedule.MASK_EVERY_DAY, 9 * 60, 17 * 60, 1, false, 15)
        val s2 = FrictionSchedule(2, "Deep", true, FrictionSchedule.MASK_EVERY_DAY, 10 * 60, 12 * 60, 2, true, 10)
        val atMs = ZonedDateTime.of(2026, 11, 23, 11, 0, 0, 0, zone).toInstant().toEpochMilli()

        val effect = ScheduleMatcher.activeEffect(listOf(s1, s2), atMs, zone)!!
        assertEquals(2L, effect.primaryScheduleId)
        assertEquals(2, effect.escalationBoost)
        assertTrue(effect.requireTimerPrompt)
        assertEquals(10, effect.nudgeIntervalMin)

        // Equal-boost tie-breaker must deterministically select the lowest schedule ID regardless of input order.
        val eqLow = FrictionSchedule(3, "Alpha", true, FrictionSchedule.MASK_EVERY_DAY, 9 * 60, 17 * 60, 1, false, null)
        val eqHigh = FrictionSchedule(9, "Beta", true, FrictionSchedule.MASK_EVERY_DAY, 9 * 60, 17 * 60, 1, false, null)
        assertEquals(3L, ScheduleMatcher.activeEffect(listOf(eqLow, eqHigh), atMs, zone)!!.primaryScheduleId)
        assertEquals(3L, ScheduleMatcher.activeEffect(listOf(eqHigh, eqLow), atMs, zone)!!.primaryScheduleId)
    }
}
