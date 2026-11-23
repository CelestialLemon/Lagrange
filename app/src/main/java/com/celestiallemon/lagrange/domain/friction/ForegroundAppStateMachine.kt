package com.celestiallemon.lagrange.domain.friction

/**
 * Resolves package/class traits needed by [ForegroundAppStateMachine] without touching Android APIs in domain code.
 */
interface WindowClassifier {
    val ownPackage: String
    /** True when `ComponentName.createRelative(packageName, className)` resolves to a real Android Activity. */
    fun isActivity(packageName: String, className: String): Boolean
    /** True when [packageName] is an enabled/active input method (keyboard). */
    fun isImePackage(packageName: String): Boolean
    /** True for SystemUI, volume panel, lockscreen, PermissionController, IntentResolver, `android`. */
    fun isSystemUiPackage(packageName: String): Boolean
    /** True for home screen / launcher packages. */
    fun isLauncherPackage(packageName: String): Boolean
    /** True for dialer / in-call UI packages (incoming calls must immediately clear overlays). */
    fun isCallOrDialerPackage(packageName: String): Boolean
}

data class ForegroundConfig(
    /** Brief bounce to launcher/recents shorter than this won't re-trigger the already-active app. */
    val briefBounceDebounceMs: Long = 1_500L,
    /** Screen-off gaps shorter than this merge into the same session (matches ReconstructionRules.microLockMergeMs). */
    val microLockMergeMs: Long = FrictionSettings.MICRO_LOCK_MERGE_MS,
    /** Wait briefly after unlock for Android's real top-activity event before falling back to the pre-lock app. */
    val unlockConfirmDelayMs: Long = 350L,
)

enum class IgnoreReason {
    EMPTY,
    SCREEN_OFF,
    OWN_NON_ACTIVITY,
    IME,
    SYSTEM_UI,
    NON_ACTIVITY,
    SAME_APP,
}

sealed interface ForegroundTransition {
    data class Ignored(val reason: IgnoreReason) : ForegroundTransition
    data class AppEntered(val packageName: String, val previousPackage: String?, val fromUnlock: Boolean = false) : ForegroundTransition
    /** Brief accidental bounce through Launcher (<1.5s) back into the same already-active app: resume tracking without prompting. */
    data class BounceResumed(val packageName: String) : ForegroundTransition
    data class SelfAppEntered(val previousPackage: String?) : ForegroundTransition
    data class LeftToHome(val previousPackage: String?) : ForegroundTransition
    data class CallInterrupted(val packageName: String, val previousPackage: String?) : ForegroundTransition
    data class ScreenOff(val previousPackage: String?) : ForegroundTransition
    data class MicroLockResumed(val packageName: String?) : ForegroundTransition
    /** Screen unlocked after >=15s without a verified post-wake app event yet; host may wait [unlockConfirmDelayMs] before fallback. */
    data class UnlockAwaitingApp(val fallbackPackage: String?) : ForegroundTransition
}

/**
 * Pure-Kotlin state machine turning noisy `TYPE_WINDOW_STATE_CHANGED` + screen lifecycle events into clean,
 * debounced foreground app transitions (spec §5.2).
 */
class ForegroundAppStateMachine(
    private val classifier: WindowClassifier,
    val config: ForegroundConfig = ForegroundConfig(),
) {
    var currentPackage: String? = null
        private set

    var isScreenInteractive: Boolean = true
        private set

    private var appBeforeScreenOff: String? = null
    /** True when the screen turned off over an unresolved launch pause / timer expiry for [appBeforeScreenOff]. */
    private var blockedBeforeScreenOff: Boolean = false
    private var screenOffAtElapsedMs: Long? = null
    /** Real non-SystemUI activity observed while screen was waking/locked (e.g., camera shortcut or lockscreen notification). */
    private var postWakeObservedApp: String? = null
    private var postWakeObservedLauncher: Boolean = false
    /** Fallback candidate after a >=15s unlock when Android does not emit a fresh window event for an already-resumed app. */
    private var pendingUnlockFallbackApp: String? = null

    private var lastRealAppBeforeHome: String? = null
    private var leftRealAppAtElapsedMs: Long? = null
    private var lastAppWasBlocked: Boolean = false

    fun setInitialScreenInteractive(interactive: Boolean) {
        isScreenInteractive = interactive
    }

    /**
     * Marks that the current app was exited via an unresolved launch pause or timer expiry (`CLOSED_GO_HOME`,
     * `CLOSED_SYSTEM_NAV`, `REDIRECTED`), so neither a `<1.5s` Home bounce nor a `<15s` micro-lock can bypass it.
     */
    fun markLastExitBlocked() {
        lastAppWasBlocked = true
        lastRealAppBeforeHome = null
        leftRealAppAtElapsedMs = null
        pendingUnlockFallbackApp = null
        currentPackage = null
    }

    fun onWindowStateChanged(
        packageName: String?,
        className: String?,
        elapsedMs: Long,
    ): ForegroundTransition {
        val pkg = packageName?.trim().orEmpty()
        val cls = className?.trim().orEmpty()
        if (pkg.isEmpty() || cls.isEmpty()) return ForegroundTransition.Ignored(IgnoreReason.EMPTY)

        // While screen is non-interactive / on lockscreen, never fire overlays yet, but DO record verified
        // post-wake activities (e.g., lockscreen notification launch or camera shortcut) so unlock reconciles accurately.
        if (!isScreenInteractive) {
            if (!classifier.isImePackage(pkg) && !classifier.isSystemUiPackage(pkg)) {
                if (classifier.isCallOrDialerPackage(pkg) || classifier.isActivity(pkg, cls)) {
                    if (classifier.isLauncherPackage(pkg)) {
                        postWakeObservedApp = null
                        postWakeObservedLauncher = true
                    } else {
                        postWakeObservedApp = pkg
                        postWakeObservedLauncher = false
                    }
                }
            }
            return ForegroundTransition.Ignored(IgnoreReason.SCREEN_OFF)
        }

        // Incoming calls / dialer take immediate priority so overlays never block answering a call.
        if (classifier.isCallOrDialerPackage(pkg)) {
            pendingUnlockFallbackApp = null
            if (pkg == currentPackage) return ForegroundTransition.Ignored(IgnoreReason.SAME_APP)
            val prev = currentPackage
            currentPackage = pkg
            lastRealAppBeforeHome = null
            lastAppWasBlocked = false
            return ForegroundTransition.CallInterrupted(pkg, prev)
        }

        // Active IME/keyboard and SystemUI (shade, volume panel, lockscreen, permission sheets) never change the
        // resolved foreground app.
        if (classifier.isImePackage(pkg)) return ForegroundTransition.Ignored(IgnoreReason.IME)
        if (classifier.isSystemUiPackage(pkg)) return ForegroundTransition.Ignored(IgnoreReason.SYSTEM_UI)

        // Non-Activity windows (our own TYPE_ACCESSIBILITY_OVERLAY, toasts, dialogs, popups) are strictly ignored.
        if (!classifier.isActivity(pkg, cls)) {
            val reason = if (pkg == classifier.ownPackage) IgnoreReason.OWN_NON_ACTIVITY else IgnoreReason.NON_ACTIVITY
            return ForegroundTransition.Ignored(reason)
        }

        // Any verified post-unlock Activity resolves unlock ambiguity immediately.
        val fromUnlock = pendingUnlockFallbackApp != null
        pendingUnlockFallbackApp = null

        // Own package Activity (e.g. MainActivity): never triggers friction, clears overlays and pauses distracting timer.
        if (pkg == classifier.ownPackage) {
            if (currentPackage == pkg) return ForegroundTransition.Ignored(IgnoreReason.SAME_APP)
            val prev = currentPackage
            currentPackage = pkg
            lastRealAppBeforeHome = null
            lastAppWasBlocked = false
            return ForegroundTransition.SelfAppEntered(prev)
        }

        // Launcher / Home screen.
        if (classifier.isLauncherPackage(pkg)) {
            val prev = currentPackage
            if (prev == null) return ForegroundTransition.Ignored(IgnoreReason.SAME_APP)
            if (prev != classifier.ownPackage && !lastAppWasBlocked) {
                lastRealAppBeforeHome = prev
                leftRealAppAtElapsedMs = elapsedMs
            } else {
                lastRealAppBeforeHome = null
                leftRealAppAtElapsedMs = null
            }
            currentPackage = null
            return ForegroundTransition.LeftToHome(prev)
        }

        // Same real app (e.g. screen rotation recreating the Activity, or navigating between Activities inside one app).
        if (pkg == currentPackage) {
            return ForegroundTransition.Ignored(IgnoreReason.SAME_APP)
        }

        // Debounce a very brief launcher/recents bounce right back into the same already-active app:
        // emit BounceResumed (not Ignored!) so the engine restores currentAppPolicy and resumes timers/nudges!
        val bouncedFrom = lastRealAppBeforeHome
        val leftAt = leftRealAppAtElapsedMs
        if (!lastAppWasBlocked && currentPackage == null && bouncedFrom == pkg && leftAt != null &&
            elapsedMs - leftAt in 0 until config.briefBounceDebounceMs
        ) {
            currentPackage = pkg
            lastRealAppBeforeHome = null
            leftRealAppAtElapsedMs = null
            return ForegroundTransition.BounceResumed(pkg)
        }

        val prev = currentPackage
        currentPackage = pkg
        lastRealAppBeforeHome = null
        leftRealAppAtElapsedMs = null
        lastAppWasBlocked = false
        return ForegroundTransition.AppEntered(pkg, prev, fromUnlock = fromUnlock)
    }

    /**
     * @param exitBlocked `true` when the screen turned off over an unresolved launch pause or timer expiry: the
     *   pre-lock app is kept as a candidate but must be re-evaluated as a fresh entry on unlock (never resumed),
     *   so even a `<15s` micro-lock straight back into the same activity cannot bypass the pause.
     */
    fun onScreenOff(elapsedMs: Long, exitBlocked: Boolean = false): ForegroundTransition {
        if (!isScreenInteractive) {
            // Lockscreen wake-and-sleep (notification, lift-to-wake) with no unlock in between: keep the original
            // lock time and pre-lock app; only forget what was seen on the lockscreen.
            postWakeObservedApp = null
            postWakeObservedLauncher = false
            return ForegroundTransition.Ignored(IgnoreReason.SCREEN_OFF)
        }
        val prev = currentPackage
        isScreenInteractive = false
        screenOffAtElapsedMs = elapsedMs
        appBeforeScreenOff = prev
        blockedBeforeScreenOff = exitBlocked && prev != null
        if (exitBlocked) {
            currentPackage = null
            lastAppWasBlocked = true
        }
        postWakeObservedApp = null
        postWakeObservedLauncher = false
        pendingUnlockFallbackApp = null
        lastRealAppBeforeHome = null
        leftRealAppAtElapsedMs = null
        return ForegroundTransition.ScreenOff(prev)
    }

    fun onUserPresent(elapsedMs: Long): ForegroundTransition {
        if (isScreenInteractive && screenOffAtElapsedMs == null) {
            return ForegroundTransition.Ignored(IgnoreReason.EMPTY)
        }
        val offAt = screenOffAtElapsedMs
        val prevApp = appBeforeScreenOff
        val observedApp = postWakeObservedApp
        val observedLauncher = postWakeObservedLauncher
        val blocked = blockedBeforeScreenOff
        isScreenInteractive = true
        screenOffAtElapsedMs = null
        appBeforeScreenOff = null
        blockedBeforeScreenOff = false
        postWakeObservedApp = null
        postWakeObservedLauncher = false

        val offDuration = if (offAt != null) (elapsedMs - offAt).coerceAtLeast(0L) else Long.MAX_VALUE
        if (offDuration < config.microLockMergeMs) {
            // If a different app (e.g. lockscreen camera or notification target) opened before USER_PRESENT,
            // transition to that app instead of resuming the stale pre-lock app.
            if (observedLauncher) {
                currentPackage = null
                return ForegroundTransition.LeftToHome(prevApp)
            }
            if (observedApp != null && observedApp != prevApp) {
                return resolveObservedAppOnUnlock(observedApp, prevApp, fromUnlock = false)
            }
            if (blocked) {
                // The pre-lock app was behind an unresolved pause: re-evaluate it as a fresh entry instead of resuming.
                currentPackage = null
                lastAppWasBlocked = false
                if (observedApp != null) return resolveObservedAppOnUnlock(observedApp, previousPackage = null, fromUnlock = false)
                val candidate = unlockCandidate(prevApp)
                pendingUnlockFallbackApp = candidate
                return ForegroundTransition.UnlockAwaitingApp(candidate)
            }
            currentPackage = prevApp
            return ForegroundTransition.MicroLockResumed(prevApp)
        }

        // Genuine unlock after >=15s screen-off.
        currentPackage = null
        lastAppWasBlocked = false
        if (observedLauncher) {
            pendingUnlockFallbackApp = null
            return ForegroundTransition.Ignored(IgnoreReason.EMPTY)
        }
        if (observedApp != null) {
            pendingUnlockFallbackApp = null
            return resolveObservedAppOnUnlock(observedApp, previousPackage = null, fromUnlock = true)
        }
        // No post-wake Activity seen yet: defer briefly for Android's real top Activity event so a lockscreen
        // notification tap into Gmail doesn't flash a phantom Reddit pause. If nothing arrives within
        // unlockConfirmDelayMs, host calls onUnlockFallbackTimeout().
        val candidate = unlockCandidate(prevApp)
        pendingUnlockFallbackApp = candidate
        return ForegroundTransition.UnlockAwaitingApp(candidate)
    }

    /**
     * Adopts [packageName] as the current foreground app after a host restart, when Android emits no fresh window
     * event for an activity that is already resumed. Returns `false` (and changes nothing) unless the screen is
     * interactive, no foreground app is known yet, and the package is a real non-system, non-self app.
     */
    fun adoptRecoveredForeground(packageName: String): Boolean {
        if (!isScreenInteractive || screenOffAtElapsedMs != null || currentPackage != null) return false
        if (unlockCandidate(packageName) == null || classifier.isImePackage(packageName)) return false
        currentPackage = packageName
        return true
    }

    private fun unlockCandidate(pkg: String?): String? = pkg?.takeIf {
        it != classifier.ownPackage &&
            !classifier.isLauncherPackage(it) &&
            !classifier.isSystemUiPackage(it) &&
            !classifier.isCallOrDialerPackage(it)
    }

    /**
     * Called by the host after [ForegroundConfig.unlockConfirmDelayMs] if no `TYPE_WINDOW_STATE_CHANGED`
     * arrived after unlock, confirming the user unlocked straight back into the previously resumed app.
     */
    fun onUnlockFallbackTimeout(): ForegroundTransition {
        val candidate = pendingUnlockFallbackApp ?: return ForegroundTransition.Ignored(IgnoreReason.EMPTY)
        pendingUnlockFallbackApp = null
        if (!isScreenInteractive || currentPackage != null) {
            return ForegroundTransition.Ignored(IgnoreReason.EMPTY)
        }
        currentPackage = candidate
        return ForegroundTransition.AppEntered(candidate, previousPackage = null, fromUnlock = true)
    }

    private fun resolveObservedAppOnUnlock(pkg: String, previousPackage: String?, fromUnlock: Boolean): ForegroundTransition {
        lastAppWasBlocked = false
        if (classifier.isCallOrDialerPackage(pkg)) {
            currentPackage = pkg
            return ForegroundTransition.CallInterrupted(pkg, previousPackage)
        }
        if (pkg == classifier.ownPackage) {
            currentPackage = pkg
            return ForegroundTransition.SelfAppEntered(previousPackage)
        }
        currentPackage = pkg
        return ForegroundTransition.AppEntered(pkg, previousPackage, fromUnlock = fromUnlock)
    }
}
