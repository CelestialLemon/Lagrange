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
 * - in-session time-blindness milestone nudges (default 15m, tracking elapsed distracting minutes & app-hop chain),
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

    /** Remaining ms (`0L` = pending unresolved expiry; `null` = no active timer). */
    private var timerRemainingMs: Long? = null
    private var timerInitialMin: Int? = null
    private var timerRunning: Boolean = false
    private var lastTickElapsedMs: Long? = null

    private var snoozeUntilElapsedMs: Long? = null
    private var focusBoostUntilElapsedMs: Long? = null
    private val graceUntilByPackage = LinkedHashMap<String, Long>()

    private var sessionDistractingMs: Long = 0L
    private val sessionDistractingApps = ArrayList<String>()
    /** Cumulative distracting minutes already nudged in this session (immune to mid-session interval changes). */
    private var sessionLastNudgeMinutes: Int = 0
    private var screenOffAtElapsedMs: Long? = null
    private var lastCheckpointElapsedMs: Long = Long.MIN_VALUE

    /** Policy of the currently foregrounded app, if any. */
    private var currentAppPolicy: FrictionAppPolicy? = null
    private var bootCount: Int = 0

    /** Cumulative distracting ms accrued in-process since start (monotonically non-decreasing, for live usage delta). */
    var totalAccruedDistractingMs: Long = 0L
        private set

    val currentForegroundPackage: String? get() = foreground.currentPackage

    val accumulatedSessionDistractingMs: Long get() = sessionDistractingMs

    fun setInitialScreenInteractive(interactive: Boolean) {
        foreground.setInitialScreenInteractive(interactive)
    }

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
            sessionLastNudgeMinutes = sessionLastNudgeMinutes,
            screenOffAtElapsedMs = screenOffAtElapsedMs,
        )
    }

    /**
     * Restores persisted monotonic state after a service restart. Discards the snapshot completely if the device
     * rebooted ([currentBootCount] changed) or the monotonic clock went backwards.
     */
    fun restore(saved: FrictionRuntimeSnapshot?, currentBootCount: Int, nowElapsedMs: Long): Boolean {
        bootCount = currentBootCount
        lastCheckpointElapsedMs = nowElapsedMs
        if (saved == null) {
            syncScreenStateAfterRestore(nowElapsedMs)
            return false
        }
        if (saved.bootCount != currentBootCount || nowElapsedMs < saved.savedAtElapsedMs) {
            clearAllRuntimeState()
            syncScreenStateAfterRestore(nowElapsedMs)
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
            // Preserve 0L as well as positive values so an unresolved pending timer expiry survives restart.
            timerRemainingMs = saved.activeTimerRemainingMs?.takeIf { it >= 0L }
            timerInitialMin = if (timerRemainingMs != null) saved.activeTimerInitialMin else null
            timerRunning = false
            graceUntilByPackage.clear()
            saved.graceUntilByPackage.forEach { (pkg, exp) ->
                if (exp > nowElapsedMs) graceUntilByPackage[pkg] = exp
            }
            sessionDistractingMs = saved.sessionDistractingMs.coerceAtLeast(0L)
            sessionDistractingApps.clear()
            sessionDistractingApps.addAll(saved.sessionDistractingApps)
            sessionLastNudgeMinutes = saved.sessionLastNudgeMinutes.coerceAtLeast(0)
            screenOffAtElapsedMs = offAt
        }
        syncScreenStateAfterRestore(nowElapsedMs)
        lastTickElapsedMs = nowElapsedMs
        return true
    }

    /**
     * Reconciles the restored screen-off marker with the actual screen state (set beforehand through
     * [setInitialScreenInteractive]). Screen already on: no USER_PRESENT will clear the marker, so drop it now and it
     * can never expire a session started after this restart. Screen off: arm the marker (and the state machine) so the
     * next unlock is handled as a real unlock even if the screen-off broadcast arrived before the host was ready.
     */
    private fun syncScreenStateAfterRestore(nowElapsedMs: Long) {
        if (foreground.isScreenInteractive) {
            screenOffAtElapsedMs = null
        } else {
            val offAt = screenOffAtElapsedMs ?: nowElapsedMs
            screenOffAtElapsedMs = offAt
            foreground.onScreenOff(offAt)
        }
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
            if (overlay is ActiveOverlay.TimerExpired) {
                // Clearing an expired timer on snooze prevents it from immediately re-firing on the next tick.
                clearTimer()
            }
            effects += FrictionEffect.DismissOverlay
        } else if (timerRemainingMs != null && timerRemainingMs!! <= 0L) {
            clearTimer()
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
        // If snoozed from a launch pause, the user is now continuing into the distracting app: log the shown pause as
        // CONTINUED so it counts toward the daily prompt cap and real opens (never as a blocked attempt).
        if (overlay is ActiveOverlay.LaunchPause) {
            recordDistractingAppVisit(currentAppPolicy)
            val reaction = reactionMsIfPastCountdown(overlay, nowElapsedMs)
            effects += FrictionEffect.LogEvent(overlay.toRecord(FrictionVocab.OUTCOME_CONTINUED, reaction, redirectPkgId = null))
        }
        resumeDistractingTrackingIfNeeded(nowElapsedMs)
        effects += persist(nowElapsedMs)
        return effects
    }

    fun cancelSnooze(nowElapsedMs: Long): List<FrictionEffect> {
        if (snoozeUntilElapsedMs == null) return emptyList()
        snoozeUntilElapsedMs = null
        return listOf(persist(nowElapsedMs))
    }

    fun activateFocusBoost1h(nowElapsedMs: Long): List<FrictionEffect> {
        focusBoostUntilElapsedMs = nowElapsedMs + FrictionSettings.FOCUS_BOOST_DURATION_MS
        snoozeUntilElapsedMs = null
        return listOf(persist(nowElapsedMs))
    }

    fun cancelFocusBoost(nowElapsedMs: Long): List<FrictionEffect> {
        if (focusBoostUntilElapsedMs == null) return emptyList()
        focusBoostUntilElapsedMs = null
        return listOf(persist(nowElapsedMs))
    }

    /**
     * Host restart recovery: adopts [packageName] (the app Android reports as already resumed) as the foreground app
     * without prompting, so a restored soft timer keeps counting down (and a pending expiry fires on the next tick)
     * even when no fresh window event arrives.
     */
    fun onForegroundRecovered(
        packageName: String,
        nowElapsedMs: Long,
        resolveApp: (String) -> FrictionAppPolicy,
    ): List<FrictionEffect> {
        if (activeOverlay != null || !foreground.adoptRecoveredForeground(packageName)) return emptyList()
        val app = resolveApp(packageName)
        currentAppPolicy = app
        recordDistractingAppVisit(app)
        resumeDistractingTrackingIfNeeded(nowElapsedMs)
        return listOf(persist(nowElapsedMs))
    }

    // -- Overlay lifecycle recovery -----------------------------------------------------------------------------------

    /**
     * Called when the Android host could not attach the overlay window (or Android detached it unexpectedly).
     * Clears [activeOverlay] without logging a false blocked attempt and resumes normal tracking.
     */
    fun onOverlayAttachFailed(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val failed = activeOverlay ?: return emptyList()
        activeOverlay = null
        val effects = ArrayList<FrictionEffect>()
        if (failed is ActiveOverlay.TimerExpired) {
            clearTimer()
        }
        if (failed is ActiveOverlay.LaunchPause) {
            recordDistractingAppVisit(currentAppPolicy)
            if (failed.pkgId > 0L) effects += FrictionEffect.RecordUnpromptedOpen(failed.pkgId, nowWallMs)
        }
        resumeDistractingTrackingIfNeeded(nowElapsedMs)
        effects += persist(nowElapsedMs)
        return effects
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
        effects += FrictionEffect.CancelUnlockFallback
        advanceActiveClocks(nowElapsedMs)
        timerRunning = false
        screenOffAtElapsedMs = nowElapsedMs
        // Dismiss unresolved blocking overlays and tell the state machine the exit was blocked, so unlocking
        // (even a <15s micro-lock straight back into the same activity) re-evaluates the app as a fresh entry.
        val hadBlocking = isBlockingOverlayShowing()
        // Keep a pending (0 ms) expiry armed: a micro-lock straight back into the app shows the check-in again, while a
        // long lock clears it with the rest of the session on unlock.
        dismissActiveOverlayAsSystemExit(nowWallMs, nowElapsedMs, clearExpiredTimer = false, effects = effects)
        // Nothing accrues while the screen is off; unlock re-resolves the foreground policy from its transition,
        // so an unlock that lands on Home never keeps counting the pre-lock app.
        currentAppPolicy = null
        foreground.onScreenOff(nowElapsedMs, exitBlocked = hadBlocking)
        effects += persist(nowElapsedMs)
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
        }

        val transition = foreground.onUserPresent(nowElapsedMs)
        effects += handleTransition(transition, nowWallMs, nowElapsedMs, zone, settings, schedules, resolveApp, loadUsage, resolveRedirect)
        // Always persist the cleared screenOffAtElapsedMs marker (even on micro-lock resume!).
        if (effects.none { it is FrictionEffect.PersistState }) {
            effects += persist(nowElapsedMs)
        }
        return effects
    }

    fun onUnlockFallbackTimeout(
        nowWallMs: Long,
        nowElapsedMs: Long,
        zone: ZoneId,
        settings: FrictionSettings,
        schedules: List<FrictionSchedule>,
        resolveApp: (String) -> FrictionAppPolicy,
        loadUsage: (FrictionAppPolicy) -> TodayUsageSnapshot,
        resolveRedirect: (String) -> FrictionAppPolicy? = { null },
    ): List<FrictionEffect> {
        val transition = foreground.onUnlockFallbackTimeout()
        return handleTransition(transition, nowWallMs, nowElapsedMs, zone, settings, schedules, resolveApp, loadUsage, resolveRedirect)
    }

    fun onServiceDisconnected(nowWallMs: Long, nowElapsedMs: Long): List<FrictionEffect> {
        val effects = ArrayList<FrictionEffect>()
        effects += FrictionEffect.CancelUnlockFallback
        advanceActiveClocks(nowElapsedMs)
        timerRunning = false
        // The service is going away while the user may well still be in the app: remove the window without logging a
        // blocked outcome (that would scrub a real session). A pending timer expiry stays armed for the next start.
        when (activeOverlay) {
            null -> Unit
            is ActiveOverlay.TimeBlindnessNudge -> logAndDismissNudge(FrictionVocab.OUTCOME_NUDGE_IGNORED, effects)
            is ActiveOverlay.LaunchPause, is ActiveOverlay.TimerExpired -> {
                activeOverlay = null
                effects += FrictionEffect.DismissOverlay
            }
        }
        effects += persist(nowElapsedMs)
        return effects
    }

    /**
     * Periodic tick (e.g. every second while in a distracting app) that advances the cross-app soft session
     * timer, checkpoints running monotonic state periodically, and checks time-blindness nudge milestones.
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
        val snoozed = snoozeUntilElapsedMs?.let { nowElapsedMs < it } == true

        // Conflict priority: soft session timer expiry takes highest priority, while honoring master switch & 1h snooze.
        if (timerRemainingMs != null && timerRemainingMs!! <= 0L) {
            if (settings.frictionEnabled && !snoozed) {
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
                effects += persist(nowElapsedMs)
                return effects
            } else {
                // Friction disabled or snoozed: the check-in is dropped, not deferred, so it can never ambush the user
                // the moment the snooze ends or friction is switched back on.
                clearTimer()
                effects += persist(nowElapsedMs)
            }
        }

        // Time-blindness milestone nudge (suppressed during snooze or when friction is disabled).
        val effectiveIntervalMin = activeSchedule?.nudgeIntervalMin ?: settings.nudgeIntervalMin
        if (settings.frictionEnabled && !snoozed && effectiveIntervalMin > 0 && activeOverlay == null) {
            val intervalMs = effectiveIntervalMin * 60_000L
            val milestoneMinutes = (sessionDistractingMs / intervalMs).toInt() * effectiveIntervalMin
            if (milestoneMinutes > sessionLastNudgeMinutes && milestoneMinutes > 0) {
                sessionLastNudgeMinutes = milestoneMinutes
                val usage = loadUsage(app)
                val nudge = ActiveOverlay.TimeBlindnessNudge(
                    pkgId = app.pkgId,
                    packageName = app.packageName,
                    logicalDate = usage.logicalDate,
                    continuousDistractingMs = sessionDistractingMs,
                    milestoneMinutes = milestoneMinutes,
                    appsHopped = sessionDistractingApps.toList(),
                    shownAtWallMs = nowWallMs,
                    shownAtElapsedMs = nowElapsedMs,
                    scheduleId = activeSchedule?.primaryScheduleId,
                )
                activeOverlay = nudge
                effects += FrictionEffect.ShowOverlay(nudge)
                effects += persist(nowElapsedMs)
                return effects
            }
        }

        // Bounded periodic checkpoint while a timer or distracting session is actively running so a sudden
        // process death never rolls back a running timer by minutes.
        if (nowElapsedMs - lastCheckpointElapsedMs >= TIMER_CHECKPOINT_INTERVAL_MS) {
            effects += persist(nowElapsedMs)
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

    /**
     * Completes a "Go Home" tap on a launch pause after the host confirms navigation succeeded.
     * When [navigatedHome] is `false`, the overlay stays up and nothing is falsely logged as blocked.
     */
    fun onPauseGoHome(nowWallMs: Long, nowElapsedMs: Long, navigatedHome: Boolean = true): List<FrictionEffect> {
        val pause = activeOverlay as? ActiveOverlay.LaunchPause ?: return emptyList()
        if (!navigatedHome) return emptyList()
        activeOverlay = null
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        timerRunning = false
        val reaction = reactionMsIfPastCountdown(pause, nowElapsedMs)
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(pause.toRecord(FrictionVocab.OUTCOME_CLOSED_GO_HOME, reaction, redirectPkgId = null)),
            persist(nowElapsedMs),
        )
    }

    /**
     * Completes a Healthy Redirect tap after the host attempts the launch. Logs the destination actually reached:
     * `REDIRECTED` with `redirectPkgId` when the redirect launched, `CLOSED_GO_HOME` with `null` redirect when it
     * fell back to Home, or leaves the pause active when both failed.
     */
    fun onPauseRedirect(
        nowWallMs: Long,
        nowElapsedMs: Long,
        result: RedirectLaunchResult = RedirectLaunchResult.LAUNCHED_REDIRECT,
    ): List<FrictionEffect> {
        val pause = activeOverlay as? ActiveOverlay.LaunchPause ?: return emptyList()
        if (result == RedirectLaunchResult.FAILED) return emptyList()
        activeOverlay = null
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        timerRunning = false
        val reaction = reactionMsIfPastCountdown(pause, nowElapsedMs)
        val record = when (result) {
            RedirectLaunchResult.LAUNCHED_REDIRECT ->
                pause.toRecord(FrictionVocab.OUTCOME_REDIRECTED, reaction, redirectPkgId = pause.plan.redirectPkgId)
            RedirectLaunchResult.FELL_BACK_HOME ->
                pause.toRecord(FrictionVocab.OUTCOME_CLOSED_GO_HOME, reaction, redirectPkgId = null)
            RedirectLaunchResult.FAILED -> error("unreachable")
        }
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(record),
            persist(nowElapsedMs),
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
            persist(nowElapsedMs),
        )
    }

    fun onPauseBackPressed(nowWallMs: Long, nowElapsedMs: Long, navigatedHome: Boolean = true): List<FrictionEffect> {
        val pause = activeOverlay as? ActiveOverlay.LaunchPause ?: return emptyList()
        if (!navigatedHome) return emptyList()
        activeOverlay = null
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        timerRunning = false
        val reaction = reactionMsIfPastCountdown(pause, nowElapsedMs)
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(pause.toRecord(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, reaction, redirectPkgId = null)),
            persist(nowElapsedMs),
        )
    }

    fun onTimerGoHome(nowWallMs: Long, nowElapsedMs: Long, navigatedHome: Boolean = true): List<FrictionEffect> {
        val expired = activeOverlay as? ActiveOverlay.TimerExpired ?: return emptyList()
        if (!navigatedHome) return emptyList()
        activeOverlay = null
        clearTimer()
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(expired.toRecord(FrictionVocab.OUTCOME_CLOSED_GO_HOME, selectedTimerMin = null)),
            persist(nowElapsedMs),
        )
    }

    fun onTimerBackPressed(nowWallMs: Long, nowElapsedMs: Long, navigatedHome: Boolean = true): List<FrictionEffect> {
        val expired = activeOverlay as? ActiveOverlay.TimerExpired ?: return emptyList()
        if (!navigatedHome) return emptyList()
        activeOverlay = null
        clearTimer()
        foreground.markLastExitBlocked()
        currentAppPolicy = null
        return listOf(
            FrictionEffect.DismissOverlay,
            FrictionEffect.LogEvent(expired.toRecord(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, selectedTimerMin = null)),
            persist(nowElapsedMs),
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
            persist(nowElapsedMs),
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
            persist(nowElapsedMs),
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
            is ForegroundTransition.UnlockAwaitingApp -> {
                return if (transition.fallbackPackage != null) {
                    listOf(FrictionEffect.ScheduleUnlockFallback(foreground.config.unlockConfirmDelayMs))
                } else {
                    emptyList()
                }
            }
            is ForegroundTransition.MicroLockResumed -> {
                val app = transition.packageName?.let(resolveApp)
                currentAppPolicy = app
                resumeDistractingTrackingIfNeeded(nowElapsedMs)
                return emptyList()
            }
            is ForegroundTransition.BounceResumed -> {
                val app = resolveApp(transition.packageName)
                currentAppPolicy = app
                resumeDistractingTrackingIfNeeded(nowElapsedMs)
                return listOf(FrictionEffect.CancelUnlockFallback)
            }
            is ForegroundTransition.LeftToHome,
            is ForegroundTransition.SelfAppEntered,
            is ForegroundTransition.CallInterrupted,
            -> {
                val effects = ArrayList<FrictionEffect>()
                effects += FrictionEffect.CancelUnlockFallback
                advanceActiveClocks(nowElapsedMs)
                timerRunning = false
                currentAppPolicy = null
                val hadBlocking = isBlockingOverlayShowing()
                // An incoming call only interrupts the check-in; it returns when the user goes back to a distracting app.
                dismissActiveOverlayAsSystemExit(
                    nowWallMs,
                    nowElapsedMs,
                    clearExpiredTimer = transition !is ForegroundTransition.CallInterrupted,
                    effects = effects,
                )
                if (hadBlocking && transition is ForegroundTransition.LeftToHome) {
                    // Prevent a <1.5s Home bounce from bypassing an unresolved launch pause or timer expiry.
                    foreground.markLastExitBlocked()
                }
                effects += persist(nowElapsedMs)
                return effects
            }
            is ForegroundTransition.AppEntered -> {
                val effects = ArrayList<FrictionEffect>()
                effects += FrictionEffect.CancelUnlockFallback
                advanceActiveClocks(nowElapsedMs)
                timerRunning = false
                // If user navigated away from an active launch pause directly to another app, log CLOSED_SYSTEM_NAV.
                dismissActiveOverlayAsSystemExit(nowWallMs, nowElapsedMs, clearExpiredTimer = false, effects = effects)

                val app = resolveApp(transition.packageName)
                currentAppPolicy = app

                if (!app.countsAsDistracting && !app.isFrictionEligible) {
                    // Utility / Neutral (without opt-in) / Excluded: timer stays paused, no friction.
                    lastTickElapsedMs = nowElapsedMs
                    effects += persist(nowElapsedMs)
                    return effects
                }

                val activeSchedule = ScheduleMatcher.activeEffect(schedules, nowWallMs, zone)
                val usage = loadUsage(app)
                val snoozed = snoozeUntilElapsedMs?.let { nowElapsedMs < it } == true

                // Conflict priority (spec §5.4): an already-expired cross-app timer takes priority over a launch pause,
                // while still respecting master frictionEnabled and 1h snooze.
                if (settings.frictionEnabled && !snoozed && app.countsAsDistracting &&
                    timerRemainingMs != null && timerRemainingMs!! <= 0L
                ) {
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
                    effects += persist(nowElapsedMs)
                    return effects
                }

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
                        // Record unprompted real opens (grace, snooze, daily cap, disabled) so live escalation stays accurate.
                        if ((app.countsAsDistracting || app.isFrictionEligible) && app.pkgId > 0L) {
                            effects += FrictionEffect.RecordUnpromptedOpen(app.pkgId, nowWallMs)
                        }
                        resumeDistractingTrackingIfNeeded(nowElapsedMs)
                    }
                }
                effects += persist(nowElapsedMs)
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
        totalAccruedDistractingMs += delta
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
        sessionLastNudgeMinutes = 0
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

    private fun persist(nowElapsedMs: Long): FrictionEffect.PersistState {
        lastCheckpointElapsedMs = nowElapsedMs
        return FrictionEffect.PersistState(snapshot(nowElapsedMs))
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

    companion object {
        const val TIMER_CHECKPOINT_INTERVAL_MS = 15_000L
    }
}
