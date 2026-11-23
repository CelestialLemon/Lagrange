package com.celestiallemon.lagrange.domain.friction

import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.IntentionTag
import java.time.ZoneId

/**
 * Pure-Kotlin Gentle Friction Engine coordinating:
 * - foreground app state machine (Activity filtering, IME/SystemUI/self ignore, bounce & rotation debounce),
 * - escalating launch pauses (real opens only, schedule boosts, Focus Boost 1h, daily cap, per-app overrides),
 * - cross-app soft session timer (counts across all DISTRACTING apps, pauses on UTILITY/NEUTRAL/SELF_APP, clears on >15s lock),
 * - anti-fatigue 2-minute monotonic switch-back grace window,
 * - in-session time-blindness milestone nudges (default 15m, tracking app-hop chain),
 * - 1-hour safe snooze escape hatch (`SNOOZE_1H`),
 * - restart-safe monotonic state snapshot & reboot invalidation.
 */
class FrictionEngine(
    classifier: WindowClassifier,
    foregroundConfig: ForegroundConfig = ForegroundConfig(),
) {
    private val foreground = ForegroundAppStateMachine(classifier, foregroundConfig)

    var activeOverlay: ActiveOverlay? = null
        private set

    private var timerRemainingMs: Long? = null
    private var timerInitialMin: Int? = null
    private var timerRunning: Boolean = false
    private var lastTickElapsedMs: Long? = null

    private var snoozeUntilElapsedMs: Long? = null
    private var focusBoostUntilElapsedMs: Long? = null
    private val graceUntilByPackage = LinkedHashMap<String, Long>()

    private var sessionDistractingMs: Long = 0L
    private val sessionDistractingApps = ArrayList<String>()
    private var sessionLastNudgeMilestone: Int = 0
    private var screenOffAtElapsedMs: Long? = null

    /** Policy of the currently foregrounded app, if any. */
    private var currentAppPolicy: FrictionAppPolicy? = null
    private var currentLogicalDate: String? = null
    private var bootCount: Int = 0

    val currentForegroundPackage: String? get() = foreground.currentPackage

    val accumulatedSessionDistractingMs: Long get() = sessionDistractingMs

    fun liveStatus(nowElapsedMs: Long): FrictionLiveStatus {
        pruneExpired(nowElapsedMs)
        return FrictionLiveStatus(
            snoozeUntilElapsedMs = snoozeUntilElapsedMs,
            focusBoostUntilElapsedMs = focusBoostUntilElapsedMs,
            activeTimerRemainingMs = timerRemainingMs,
            activeTimerInitialMin = timerInitialMin,
            sessionDistractingMs = sessionDistractingMs,
            currentForegroundPackage = foreground.currentPackage,
        )
    }

    fun snapshot(nowElapsedMs: Long): FrictionRuntimeSnapshot {
        pruneExpired(nowElapsedMs)
        return FrictionRuntimeSnapshot(
            bootCount = bootCount,
            savedAtElapsedMs = nowElapsedMs,
            activeTimerRemainingMs = timerRemainingMs,
            activeTimerInitialMin = timerInitialMin,
            snoozeUntilElapsedMs = snoozeUntilElapsedMs,
            focusBoostUntilElapsedMs = focusBoostUntilElapsedMs,
            graceUntilByPackage = graceUntilByPackage.toMap(),
            sessionDistractingMs = sessionDistractingMs,
            sessionDistractingApps = sessionDistractingApps.toList(),
            sessionLastNudgeMilestone = sessionLastNudgeMilestone,
            screenOffAtElapsedMs = screenOffAtElapsedMs,
        )
    }

    /**
     * Restores persisted monotonic state after a service restart. Discards the snapshot completely if the device
     * rebooted ([currentBootCount] changed) or the monotonic clock went backwards.
     */
    fun restore(saved: FrictionRuntimeSnapshot?, currentBootCount: Int, nowElapsedMs: Long): Boolean {
        bootCount = currentBootCount
        if (saved == null) return false
        if (saved.bootCount != currentBootCount || nowElapsedMs < saved.savedAtElapsedMs) {
            clearAllRuntimeState()
            return false
        }
        snoozeUntilElapsedMs = saved.snoozeUntilElapsedMs?.takeIf { it > nowElapsedMs }
        focusBoostUntilElapsedMs = saved.focusBoostUntilElapsedMs?.takeIf { it > nowElapsedMs }

        val offAt = saved.screenOffAtElapsedMs
        val sessionExpired = offAt != null && (nowElapsedMs - offAt) >= FrictionSettings.MICRO_LOCK_MERGE_MS
        if (sessionExpired) {
            clearSessionScopedState()
            screenOffAtElapsedMs = offAt
        } else {
            timerRemainingMs = saved.activeTimerRemainingMs?.takeIf { it > 0L }
            timerInitialMin = if (timerRemainingMs != null) saved.activeTimerInitialMin else null
            timerRunning = false
            graceUntilByPackage.clear()
            saved.graceUntilByPackage.forEach { (pkg, exp) ->
                if (exp > nowElapsedMs) graceUntilByPackage[pkg] = exp
            }
            sessionDistractingMs = saved.sessionDistractingMs.coerceAtLeast(0L)
            sessionDistractingApps.clear()
            sessionDistractingApps.addAll(saved.sessionDistractingApps)
            sessionLastNudgeMilestone = saved.sessionLastNudgeMilestone.coerceAtLeast(0)
            screenOffAtElapsedMs = offAt
        }
        lastTickElapsedMs = nowElapsedMs
        return true
    }

    // -- External state controls (UI / Quick Settings tile) -----------------------------------------------------------

    fun activateSnooze1h(
        nowWallMs: Long,
        nowElapsedMs: Long,
        logicalDate: String,
        pkgIdForLog: Long,
    ): List<FrictionEffect> {
        val effects = ArrayList<FrictionEffect>()
        advanceActiveClocks(nowElapsedMs)
        snoozeUntilElapsedMs = nowElapsedMs + FrictionSettings.SNOOZE_DURATION_MS
        focusBoostUntilElapsedMs = null
        val overlay = activeOverlay
        if (overlay != null) {
            activeOverlay = null
            effects += FrictionEffect.DismissOverlay
        }
        effects += FrictionEffect.LogEvent(
            FrictionEventRecord(
                timestampUtcMs = nowWallMs,
                logicalDate = logicalDate,
                pkgId = overlay?.pkgId ?: pkgIdForLog,
                eventKind = FrictionVocab.SNOOZE_1H,
                scheduleId = (overlay as? ActiveOverlay.LaunchPause)?.plan?.scheduleId,
                escalationTier = (overlay as? ActiveOverlay.LaunchPause)?.plan?.tier ?: 0,
                pauseDurationSec = 0,
                postCountdownReactionMs = null,
                intentionTag = null,
                selectedTimerMin = null,
                redirectPkgId = null,
                outcome = FrictionVocab.OUTCOME_SNOOZE_ACTIVATED,
            ),
        )
        resumeDistractingTrackingIfNeeded(nowElapsedMs)
        effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
        return effects
    }

    fun cancelSnooze(nowElapsedMs: Long): List<FrictionEffect> {
        if (snoozeUntilElapsedMs == null) return emptyList()
        snoozeUntilElapsedMs = null
        return listOf(FrictionEffect.PersistState(snapshot(nowElapsedMs)))
    }

    fun activateFocusBoost1h(nowElapsedMs: Long): List<FrictionEffect> {
        focusBoostUntilElapsedMs = nowElapsedMs + FrictionSettings.FOCUS_BOOST_DURATION_MS
        snoozeUntilElapsedMs = null
        return listOf(FrictionEffect.PersistState(snapshot(nowElapsedMs)))
    }

    fun cancelFocusBoost(nowElapsedMs: Long): List<FrictionEffect> {
        if (focusBoostUntilElapsedMs == null) return emptyList()
        focusBoostUntilElapsedMs = null
        return listOf(FrictionEffect.PersistState(snapshot(nowElapsedMs)))
    }

    // -- Window & lifecycle events ------------------------------------------------------------------------------------

    /**
     * Processes a `TYPE_WINDOW_STATE_CHANGED` event.
     *
     * [resolveApp] and [loadUsage] are only called when a real foreground app change actually occurs, keeping
     * noisy system/IME/dialog events allocation-free and off the database.
     */
    fun onWindowStateChanged(
        packageName: String?,
        className: String?,
        nowWallMs: Long,
        nowElapsedMs: Long,
        zone: ZoneId,
        settings: FrictionSettings,
        schedules: List<FrictionSchedule>,
        resolveApp: (String) -> FrictionAppPolicy,
        loadUsage: (FrictionAppPolicy) -> TodayUsageSnapshot,
        resolveRedirect: (String) -> FrictionAppPolicy? = { null },
    ): List<FrictionEffect> {
        val transition = foreground.onWindowStateChanged(packageName, className, nowElapsedMs)
        return handleTransition(transition, nowWallMs, nowElapsedMs, zone, settings, schedules, resolveApp, loadUsage, resolveRedirect)
    }

    fun onScreenOff(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val effects = ArrayList<FrictionEffect>()
        advanceActiveClocks(nowElapsedMs)
        timerRunning = false
        screenOffAtElapsedMs = nowElapsedMs
        dismissActiveOverlayAsSystemExit(nowWallMs, nowElapsedMs, clearExpiredTimer = true, effects = effects)
        foreground.onScreenOff(nowElapsedMs)
        effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
        return effects
    }

    fun onUserPresent(
        nowWallMs: Long,
        nowElapsedMs: Long,
        zone: ZoneId,
        settings: FrictionSettings,
        schedules: List<FrictionSchedule>,
        resolveApp: (String) -> FrictionAppPolicy,
        loadUsage: (FrictionAppPolicy) -> TodayUsageSnapshot,
        resolveRedirect: (String) -> FrictionAppPolicy? = { null },
    ): List<FrictionEffect> {
        val offAt = screenOffAtElapsedMs
        val offDuration = if (offAt != null) (nowElapsedMs - offAt).coerceAtLeast(0L) else Long.MAX_VALUE
        screenOffAtElapsedMs = null

        val effects = ArrayList<FrictionEffect>()
        if (offDuration >= FrictionSettings.MICRO_LOCK_MERGE_MS) {
            clearSessionScopedState()
            effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
        }

        val transition = foreground.onUserPresent(nowElapsedMs)
        effects += handleTransition(transition, nowWallMs, nowElapsedMs, zone, settings, schedules, resolveApp, loadUsage, resolveRedirect)
        return effects
    }

    fun onServiceDisconnected(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val effects = ArrayList<FrictionEffect>()
        advanceActiveClocks(nowElapsedMs)
        timerRunning = false
        dismissActiveOverlayAsSystemExit(nowWallMs, nowElapsedMs, clearExpiredTimer = false, effects = effects)
        effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
        return effects
    }

    /**
     * Periodic tick (e.g. every second while in a distracting app) that advances the cross-app soft session
     * timer and checks time-blindness nudge milestones.
     */
    fun onTick(
        nowWallMs: Long,
        nowElapsedMs: Long,
        zone: ZoneId,
        settings: FrictionSettings,
        schedules: List<FrictionSchedule>,
        loadUsage: (FrictionAppPolicy) -> TodayUsageSnapshot,
    ): List<FrictionEffect> {
        pruneExpired(nowElapsedMs)
        val app = currentAppPolicy ?: return emptyList()
        if (!foreground.isScreenInteractive || !app.countsAsDistracting) {
            lastTickElapsedMs = nowElapsedMs
            return emptyList()
        }
        if (isBlockingOverlayShowing()) {
            lastTickElapsedMs = nowElapsedMs
            return emptyList()
        }

        advanceActiveClocks(nowElapsedMs)

        val effects = ArrayList<FrictionEffect>()
        val activeSchedule = ScheduleMatcher.activeEffect(schedules, nowWallMs, zone)

        // Conflict priority: soft session timer expiry takes highest priority.
        if (timerRemainingMs != null && timerRemainingMs!! <= 0L) {
            if (activeOverlay is ActiveOverlay.TimeBlindnessNudge) {
                logAndDismissNudge(FrictionVocab.OUTCOME_NUDGE_IGNORED, effects)
            }
            val usage = loadUsage(app)
            val overlay = ActiveOverlay.TimerExpired(
                pkgId = app.pkgId,
                packageName = app.packageName,
                displayName = app.displayName,
                logicalDate = usage.logicalDate,
                timerMinutes = timerInitialMin ?: 5,
                distractingMsToday = usage.distractingMsToday,
                budgetMs = usage.budgetMs,
                shownAtWallMs = nowWallMs,
                shownAtElapsedMs = nowElapsedMs,
                scheduleId = activeSchedule?.primaryScheduleId,
                escalationTier = EscalationPolicy.baseTier(usage.appRealOpensToday, usage.distractingMsToday),
            )
            timerRunning = false
            activeOverlay = overlay
            effects += FrictionEffect.ShowOverlay(overlay)
            effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
            return effects
        }

        // Time-blindness milestone nudge (suppressed during snooze or when friction is disabled).
        val snoozed = snoozeUntilElapsedMs?.let { nowElapsedMs < it } == true
        val effectiveIntervalMin = activeSchedule?.nudgeIntervalMin ?: settings.nudgeIntervalMin
        if (settings.frictionEnabled && !snoozed && effectiveIntervalMin > 0 && activeOverlay == null) {
            val intervalMs = effectiveIntervalMin * 60_000L
            val milestone = (sessionDistractingMs / intervalMs).toInt()
            if (milestone > sessionLastNudgeMilestone) {
                sessionLastNudgeMilestone = milestone
                val logicalDate = currentLogicalDate ?: loadUsage(app).logicalDate
                val nudge = ActiveOverlay.TimeBlindnessNudge(
                    pkgId = app.pkgId,
                    packageName = app.packageName,
                    logicalDate = logicalDate,
                    continuousDistractingMs = sessionDistractingMs,
                    milestoneMinutes = milestone * effectiveIntervalMin,
                    appsHopped = sessionDistractingApps.toList(),
                    shownAtWallMs = nowWallMs,
                    shownAtElapsedMs = nowElapsedMs,
                    scheduleId = activeSchedule?.primaryScheduleId,
                )
                activeOverlay = nudge
                effects += FrictionEffect.ShowOverlay(nudge)
                effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
            }
        }

        return effects
    }

    // -- Overlay user actions -----------------------------------------------------------------------------------------

    fun onPauseIntentionSelected(tag: IntentionTag?) {
        val current = activeOverlay as? ActiveOverlay.LaunchPause ?: return
        activeOverlay = current.copy(selectedIntention = if (current.selectedIntention == tag) null else tag)
    }

    fun onPauseTimerSelected(minutes: Int?) {
        val current = activeOverlay as? ActiveOverlay.LaunchPause ?: return
        activeOverlay = current.copy(selectedTimerMin = minutes)
    }

    fun onPauseGoHome(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val pause = activeOverlay as? ActiveOverlay.LaunchPause ?: return emptyList()
        activeOverlay = null
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        timerRunning = false
        val reaction = reactionMsIfPastCountdown(pause, nowElapsedMs)
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(pause.toRecord(FrictionVocab.OUTCOME_CLOSED_GO_HOME, reaction, redirectPkgId = null)),
            FrictionEffect.PerformGoHome,
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onPauseRedirect(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val pause = activeOverlay as? ActiveOverlay.LaunchPause ?: return emptyList()
        val redirectPkg = pause.plan.redirectPackageName ?: return onPauseGoHome(nowWallMs, nowElapsedMs)
        activeOverlay = null
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        timerRunning = false
        val reaction = reactionMsIfPastCountdown(pause, nowElapsedMs)
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(pause.toRecord(FrictionVocab.OUTCOME_REDIRECTED, reaction, redirectPkgId = pause.plan.redirectPkgId)),
            FrictionEffect.LaunchRedirect(redirectPkg),
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onPauseContinue(
        nowWallMs: Long,
        nowElapsedMs: Long,
        settings: FrictionSettings,
    ): List<FrictionEffect> {
        val pause = activeOverlay as? ActiveOverlay.LaunchPause ?: return emptyList()
        if (nowElapsedMs < pause.countdownEndElapsedMs) return emptyList()
        activeOverlay = null
        val reaction = (nowElapsedMs - pause.countdownEndElapsedMs).coerceAtLeast(0L)

        // Grant anti-fatigue monotonic switch-back grace window.
        if (settings.graceWindowSec > 0) {
            graceUntilByPackage[pause.packageName] = nowElapsedMs + settings.graceWindowSec * 1_000L
        }

        // Start cross-app soft session timer if selected.
        val chosenTimer = pause.selectedTimerMin?.takeIf { it > 0 }
        if (chosenTimer != null) {
            timerRemainingMs = chosenTimer * 60_000L
            timerInitialMin = chosenTimer
        }

        recordDistractingAppVisit(currentAppPolicy)
        resumeDistractingTrackingIfNeeded(nowElapsedMs)

        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(pause.toRecord(FrictionVocab.OUTCOME_CONTINUED, reaction, redirectPkgId = null)),
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onPauseBackPressed(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val pause = activeOverlay as? ActiveOverlay.LaunchPause ?: return emptyList()
        activeOverlay = null
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        timerRunning = false
        val reaction = reactionMsIfPastCountdown(pause, nowElapsedMs)
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(pause.toRecord(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, reaction, redirectPkgId = null)),
            FrictionEffect.PerformGoHome,
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onTimerGoHome(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val expired = activeOverlay as? ActiveOverlay.TimerExpired ?: return emptyList()
        activeOverlay = null
        clearTimer()
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(expired.toRecord(FrictionVocab.OUTCOME_CLOSED_GO_HOME, selectedTimerMin = null)),
            FrictionEffect.PerformGoHome,
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onTimerBackPressed(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val expired = activeOverlay as? ActiveOverlay.TimerExpired ?: return emptyList()
        activeOverlay = null
        clearTimer()
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(expired.toRecord(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, selectedTimerMin = null)),
            FrictionEffect.PerformGoHome,
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onTimerExtend5m(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val expired = activeOverlay as? ActiveOverlay.TimerExpired ?: return emptyList()
        activeOverlay = null
        timerRemainingMs = 5 * 60_000L
        timerInitialMin = 5
        resumeDistractingTrackingIfNeeded(nowElapsedMs)
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(expired.toRecord(FrictionVocab.OUTCOME_EXTENDED_5M, selectedTimerMin = 5)),
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onTimerDismissSession(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val expired = activeOverlay as? ActiveOverlay.TimerExpired ?: return emptyList()
        activeOverlay = null
        clearTimer()
        resumeDistractingTrackingIfNeeded(nowElapsedMs)
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(expired.toRecord(FrictionVocab.OUTCOME_DISMISSED_SESSION, selectedTimerMin = null)),
            FrictionEffect.PersistState(snapshot(nowElapsedMs)),
        )
    }

    fun onNudgeDismissed(userTapped: Boolean): List<FrictionEffect> {
        val nudge = activeOverlay as? ActiveOverlay.TimeBlindnessNudge ?: return emptyList()
        val outcome = if (userTapped) FrictionVocab.OUTCOME_NUDGE_DISMISSED else FrictionVocab.OUTCOME_NUDGE_IGNORED
        activeOverlay = null
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(nudge.toRecord(outcome)),
        )
    }

    // -- Internal state transitions -----------------------------------------------------------------------------------

    private fun handleTransition(
        transition: ForegroundTransition,
        nowWallMs: Long,
        nowElapsedMs: Long,
        zone: ZoneId,
        settings: FrictionSettings,
        schedules: List<FrictionSchedule>,
        resolveApp: (String) -> FrictionAppPolicy,
        loadUsage: (FrictionAppPolicy) -> TodayUsageSnapshot,
        resolveRedirect: (String) -> FrictionAppPolicy?,
    ): List<FrictionEffect> {
        pruneExpired(nowElapsedMs)
        when (transition) {
            is ForegroundTransition.Ignored -> return emptyList()
            is ForegroundTransition.ScreenOff -> return emptyList()
            is ForegroundTransition.MicroLockResumed -> {
                val app = transition.packageName?.let(resolveApp)
                currentAppPolicy = app
                resumeDistractingTrackingIfNeeded(nowElapsedMs)
                return emptyList()
            }
            is ForegroundTransition.LeftToHome,
            is ForegroundTransition.SelfAppEntered,
            is ForegroundTransition.CallInterrupted,
            -> {
                val effects = ArrayList<FrictionEffect>()
                advanceActiveClocks(nowElapsedMs)
                timerRunning = false
                currentAppPolicy = null
                dismissActiveOverlayAsSystemExit(nowWallMs, nowElapsedMs, clearExpiredTimer = true, effects = effects)
                effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
                return effects
            }
            is ForegroundTransition.AppEntered -> {
                val effects = ArrayList<FrictionEffect>()
                advanceActiveClocks(nowElapsedMs)
                timerRunning = false
                // If user navigated away from an active launch pause directly to another app, log CLOSED_SYSTEM_NAV.
                dismissActiveOverlayAsSystemExit(nowWallMs, nowElapsedMs, clearExpiredTimer = false, effects = effects)

                val app = resolveApp(transition.packageName)
                currentAppPolicy = app

                if (!app.countsAsDistracting && !app.isFrictionEligible) {
                    // Utility / Neutral (without opt-in) / Excluded: timer stays paused, no friction.
                    lastTickElapsedMs = nowElapsedMs
                    effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
                    return effects
                }

                val activeSchedule = ScheduleMatcher.activeEffect(schedules, nowWallMs, zone)
                val usage = loadUsage(app)
                currentLogicalDate = usage.logicalDate

                // Conflict priority (spec §5.4): an already-expired cross-app timer takes priority over a launch pause.
                if (app.countsAsDistracting && timerRemainingMs != null && timerRemainingMs!! <= 0L) {
                    val expired = ActiveOverlay.TimerExpired(
                        pkgId = app.pkgId,
                        packageName = app.packageName,
                        displayName = app.displayName,
                        logicalDate = usage.logicalDate,
                        timerMinutes = timerInitialMin ?: 5,
                        distractingMsToday = usage.distractingMsToday,
                        budgetMs = usage.budgetMs,
                        shownAtWallMs = nowWallMs,
                        shownAtElapsedMs = nowElapsedMs,
                        scheduleId = activeSchedule?.primaryScheduleId,
                        escalationTier = EscalationPolicy.baseTier(usage.appRealOpensToday, usage.distractingMsToday),
                    )
                    activeOverlay = expired
                    effects += FrictionEffect.ShowOverlay(expired)
                    effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
                    return effects
                }

                val snoozed = snoozeUntilElapsedMs?.let { nowElapsedMs < it } == true
                val focusBoost = focusBoostUntilElapsedMs?.let { nowElapsedMs < it } == true
                val inGrace = graceUntilByPackage[app.packageName]?.let { nowElapsedMs < it } == true
                val redirect = settings.redirectPackageName?.let(resolveRedirect)

                val decision = EscalationPolicy.evaluateLaunch(
                    app = app,
                    usage = usage,
                    settings = settings,
                    activeSchedule = activeSchedule,
                    focusBoostActive = focusBoost,
                    snoozeActive = snoozed,
                    inGraceWindow = inGrace,
                    redirectTarget = redirect,
                )

                when (decision) {
                    is LaunchDecision.ShowPause -> {
                        val pause = ActiveOverlay.LaunchPause(
                            plan = decision.plan,
                            shownAtWallMs = nowWallMs,
                            shownAtElapsedMs = nowElapsedMs,
                        )
                        activeOverlay = pause
                        effects += FrictionEffect.ShowOverlay(pause)
                    }
                    is LaunchDecision.Suppress -> {
                        recordDistractingAppVisit(app)
                        resumeDistractingTrackingIfNeeded(nowElapsedMs)
                    }
                }
                effects += FrictionEffect.PersistState(snapshot(nowElapsedMs))
                return effects
            }
        }
    }

    private fun advanceActiveClocks(nowElapsedMs: Long) {
        val last = lastTickElapsedMs
        lastTickElapsedMs = nowElapsedMs
        if (last == null || nowElapsedMs <= last) return
        val app = currentAppPolicy ?: return
        if (!foreground.isScreenInteractive || !app.countsAsDistracting || isBlockingOverlayShowing()) return

        val delta = nowElapsedMs - last
        sessionDistractingMs += delta
        if (timerRunning && timerRemainingMs != null) {
            timerRemainingMs = (timerRemainingMs!! - delta).coerceAtLeast(0L)
        }
    }

    private fun resumeDistractingTrackingIfNeeded(nowElapsedMs: Long) {
        lastTickElapsedMs = nowElapsedMs
        val app = currentAppPolicy
        timerRunning = app != null && app.countsAsDistracting && timerRemainingMs != null && !isBlockingOverlayShowing()
    }

    private fun recordDistractingAppVisit(app: FrictionAppPolicy?) {
        if (app == null || !app.countsAsDistracting) return
        if (sessionDistractingApps.lastOrNull() != app.displayName) {
            sessionDistractingApps += app.displayName
        }
    }

    private fun dismissActiveOverlayAsSystemExit(
        nowWallMs: Long,
        nowElapsedMs: Long,
        clearExpiredTimer: Boolean,
        effects: MutableList<FrictionEffect>,
    ) {
        when (val overlay = activeOverlay) {
            null -> return
            is ActiveOverlay.LaunchPause -> {
                activeOverlay = null
                val reaction = reactionMsIfPastCountdown(overlay, nowElapsedMs)
                effects += FrictionEffect.DismissOverlay
                effects += FrictionEffect.LogEvent(overlay.toRecord(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, reaction, redirectPkgId = null))
            }
            is ActiveOverlay.TimerExpired -> {
                activeOverlay = null
                if (clearExpiredTimer) clearTimer()
                effects += FrictionEffect.DismissOverlay
                effects += FrictionEffect.LogEvent(overlay.toRecord(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, selectedTimerMin = null))
            }
            is ActiveOverlay.TimeBlindnessNudge -> {
                logAndDismissNudge(FrictionVocab.OUTCOME_NUDGE_IGNORED, effects)
            }
        }
    }

    private fun logAndDismissNudge(outcome: String, effects: MutableList<FrictionEffect>) {
        val nudge = activeOverlay as? ActiveOverlay.TimeBlindnessNudge ?: return
        activeOverlay = null
        effects += FrictionEffect.DismissOverlay
        effects += FrictionEffect.LogEvent(nudge.toRecord(outcome))
    }

    private fun isBlockingOverlayShowing(): Boolean =
        activeOverlay is ActiveOverlay.LaunchPause || activeOverlay is ActiveOverlay.TimerExpired

    private fun clearTimer() {
        timerRemainingMs = null
        timerInitialMin = null
        timerRunning = false
    }

    private fun clearSessionScopedState() {
        clearTimer()
        graceUntilByPackage.clear()
        sessionDistractingMs = 0L
        sessionDistractingApps.clear()
        sessionLastNudgeMilestone = 0
    }

    private fun clearAllRuntimeState() {
        clearSessionScopedState()
        snoozeUntilElapsedMs = null
        focusBoostUntilElapsedMs = null
        screenOffAtElapsedMs = null
        lastTickElapsedMs = null
    }

    private fun pruneExpired(nowElapsedMs: Long) {
        if (snoozeUntilElapsedMs != null && nowElapsedMs >= snoozeUntilElapsedMs!!) snoozeUntilElapsedMs = null
        if (focusBoostUntilElapsedMs != null && nowElapsedMs >= focusBoostUntilElapsedMs!!) focusBoostUntilElapsedMs = null
        graceUntilByPackage.entries.removeIf { (_, expiry) -> nowElapsedMs >= expiry }
    }

    private fun reactionMsIfPastCountdown(pause: ActiveOverlay.LaunchPause, nowElapsedMs: Long): Long? =
        if (nowElapsedMs >= pause.countdownEndElapsedMs) (nowElapsedMs - pause.countdownEndElapsedMs).coerceAtLeast(0L) else null

    private fun ActiveOverlay.LaunchPause.toRecord(
        outcome: String,
        reactionMs: Long?,
        redirectPkgId: Long?,
    ) = FrictionEventRecord(
        timestampUtcMs = shownAtWallMs,
        logicalDate = logicalDate,
        pkgId = pkgId,
        eventKind = FrictionVocab.LAUNCH_PAUSE,
        scheduleId = plan.scheduleId,
        escalationTier = plan.tier,
        pauseDurationSec = plan.pauseDurationSec,
        postCountdownReactionMs = reactionMs,
        intentionTag = selectedIntention?.name,
        selectedTimerMin = selectedTimerMin,
        redirectPkgId = redirectPkgId,
        outcome = outcome,
    )

    private fun ActiveOverlay.TimerExpired.toRecord(
        outcome: String,
        selectedTimerMin: Int?,
    ) = FrictionEventRecord(
        timestampUtcMs = shownAtWallMs,
        logicalDate = logicalDate,
        pkgId = pkgId,
        eventKind = FrictionVocab.TIMER_EXPIRED,
        scheduleId = scheduleId,
        escalationTier = escalationTier,
        pauseDurationSec = 0,
        postCountdownReactionMs = null,
        intentionTag = null,
        selectedTimerMin = selectedTimerMin,
        redirectPkgId = null,
        outcome = outcome,
    )

    private fun ActiveOverlay.TimeBlindnessNudge.toRecord(outcome: String) = FrictionEventRecord(
        timestampUtcMs = shownAtWallMs,
        logicalDate = logicalDate,
        pkgId = pkgId,
        eventKind = FrictionVocab.TIME_BLINDNESS_NUDGE,
        scheduleId = scheduleId,
        escalationTier = 0,
        pauseDurationSec = 0,
        postCountdownReactionMs = null,
        intentionTag = null,
        selectedTimerMin = null,
        redirectPkgId = null,
        outcome = outcome,
    )
}
