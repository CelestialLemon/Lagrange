package com.celestiallemon.lagrange.domain.friction

/**
 * Pure-Kotlin escalation and eligibility policy (spec §5.3).
 *
 * Escalation is driven strictly by today's **real opens** (blocked attempts where the user chose Go Home /
 * Redirect / System Nav are excluded) and today's distracting time, plus any active schedule boost and Focus
 * Boost 1h (+1 tier).
 */
object EscalationPolicy {

    /**
     * Computes the base escalation tier (0..3) before contextual schedule or Focus Boost modifiers.
     *
     * @param completedRealOpensToday real opens already completed today (so this launch is `completedRealOpensToday + 1`)
     * @param distractingMsToday real distracting time accrued today in milliseconds
     */
    fun baseTier(completedRealOpensToday: Int, distractingMsToday: Long): Int {
        val nthOpen = completedRealOpensToday.coerceAtLeast(0) + 1
        val distractingMin = distractingMsToday.coerceAtLeast(0L) / 60_000L
        val openTier = when {
            nthOpen >= 11 -> 3
            nthOpen >= 6 -> 2
            nthOpen >= 3 -> 1
            else -> 0
        }
        val timeTier = when {
            distractingMin >= 90 -> 3
            distractingMin >= 45 -> 2
            distractingMin >= 15 -> 1
            else -> 0
        }
        return maxOf(openTier, timeTier)
    }

    fun evaluateLaunch(
        app: FrictionAppPolicy,
        usage: TodayUsageSnapshot,
        settings: FrictionSettings,
        activeSchedule: ActiveScheduleEffect?,
        focusBoostActive: Boolean,
        snoozeActive: Boolean,
        inGraceWindow: Boolean,
        redirectTarget: FrictionAppPolicy? = null,
    ): LaunchDecision {
        if (!settings.frictionEnabled) return LaunchDecision.Suppress(SuppressionReason.FRICTION_DISABLED)
        if (!app.isFrictionEligible) return LaunchDecision.Suppress(SuppressionReason.APP_NOT_ELIGIBLE)
        if (snoozeActive) return LaunchDecision.Suppress(SuppressionReason.SNOOZED)
        if (inGraceWindow) return LaunchDecision.Suppress(SuppressionReason.GRACE_WINDOW)
        if (settings.dailyPromptCap != null && usage.promptsShownToday >= settings.dailyPromptCap) {
            return LaunchDecision.Suppress(SuppressionReason.DAILY_CAP_REACHED)
        }

        val base = if (settings.escalationEnabled) baseTier(usage.appRealOpensToday, usage.distractingMsToday) else 0
        val boost = (activeSchedule?.escalationBoost ?: 0) + (if (focusBoostActive) 1 else 0)
        val tier = (base + boost).coerceIn(0, 3)

        val pauseSec = app.override?.fixedPauseSec
            ?.coerceIn(FrictionSettings.MIN_PAUSE_SEC, FrictionSettings.MAX_PAUSE_SEC)
            ?: settings.pauseSecondsForTier(tier)

        val scheduleEncouragesTimer = activeSchedule?.requireTimerPrompt == true
        val showTimerChips = app.override?.requireTimerPrompt ?: (tier >= 1 || scheduleEncouragesTimer)
        val encourageTimer = app.override?.requireTimerPrompt == true || scheduleEncouragesTimer
        val showIntentionChips = tier >= 2
        val showBudgetComparison = tier >= 3

        val validRedirect = redirectTarget?.takeIf { it.packageName != app.packageName }

        return LaunchDecision.ShowPause(
            LaunchPausePlan(
                pkgId = app.pkgId,
                packageName = app.packageName,
                displayName = app.displayName,
                logicalDate = usage.logicalDate,
                tier = tier,
                pauseDurationSec = pauseSec,
                nthOpenToday = usage.appRealOpensToday.coerceAtLeast(0) + 1,
                distractingMsToday = usage.distractingMsToday.coerceAtLeast(0L),
                budgetMs = usage.budgetMs,
                showTimerChips = showTimerChips,
                encourageTimerSelection = encourageTimer,
                showIntentionChips = showIntentionChips,
                showBudgetComparison = showBudgetComparison,
                timerPresetsMin = settings.timerPresetsMin.filter { it > 0 }.distinct().sorted(),
                scheduleId = activeSchedule?.primaryScheduleId,
                scheduleName = activeSchedule?.primaryScheduleName,
                focusBoostActive = focusBoostActive,
                redirectPackageName = validRedirect?.packageName,
                redirectDisplayName = validRedirect?.displayName,
                redirectPkgId = validRedirect?.pkgId,
            ),
        )
    }
}
