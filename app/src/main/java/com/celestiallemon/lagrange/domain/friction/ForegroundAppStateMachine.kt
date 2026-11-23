package com.celestiallemon.lagrange.domain.friction

/**
 * Resolves package/class traits needed by [ForegroundAppStateMachine] without touching Android APIs in domain code.
 */
interface WindowClassifier {
    val ownPackage: String
    /** True when `ComponentName(packageName, className)` resolves to a real Android Activity. */
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
)

enum class IgnoreReason {
    EMPTY,
    SCREEN_OFF,
    OWN_NON_ACTIVITY,
    IME,
    SYSTEM_UI,
    NON_ACTIVITY,
    SAME_APP,
    TRANSIENT_BOUNCE,
}

sealed interface ForegroundTransition {
    data class Ignored(val reason: IgnoreReason) : ForegroundTransition
    data class AppEntered(val packageName: String, val previousPackage: String?, val fromUnlock: Boolean = false) : ForegroundTransition
    data class SelfAppEntered(val previousPackage: String?) : ForegroundTransition
    data class LeftToHome(val previousPackage: String?) : ForegroundTransition
    data class CallInterrupted(val packageName: String, val previousPackage: String?) : ForegroundTransition
    data class ScreenOff(val previousPackage: String?) : ForegroundTransition
    data class MicroLockResumed(val packageName: String?) : ForegroundTransition
}

/**
 * Pure-Kotlin state machine turning noisy `TYPE_WINDOW_STATE_CHANGED` + screen lifecycle events into clean,
 * debounced foreground app transitions (spec §5.2).
 */
class ForegroundAppStateMachine(
    private val classifier: WindowClassifier,
    private val config: ForegroundConfig = ForegroundConfig(),
) {
    var currentPackage: String? = null
        private set

    var isScreenInteractive: Boolean = true
        private set

    private var appBeforeScreenOff: String? = null
    private var screenOffAtElapsedMs: Long? = null
    private var lastRealAppBeforeHome: String? = null
    private var leftRealAppAtElapsedMs: Long? = null
    private var lastAppWasBlocked: Boolean = false

    /**
     * Marks that the current app was exited via a launch-pause block (`CLOSED_GO_HOME`, `CLOSED_SYSTEM_NAV`,
     * `REDIRECTED`), so immediately reopening the same app will never be debounced as an accidental bounce.
     */
    fun markLastExitBlocked() {
        lastAppWasBlocked = true
        lastRealAppBeforeHome = null
        leftRealAppAtElapsedMs = null
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
        if (!isScreenInteractive) return ForegroundTransition.Ignored(IgnoreReason.SCREEN_OFF)

        // Incoming calls / dialer take immediate priority so overlays never block answering a call.
        if (classifier.isCallOrDialerPackage(pkg)) {
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

        // Debounce a very brief launcher/recents bounce right back into the same already-active app.
        val bouncedFrom = lastRealAppBeforeHome
        val leftAt = leftRealAppAtElapsedMs
        if (!lastAppWasBlocked && currentPackage == null && bouncedFrom == pkg && leftAt != null &&
            elapsedMs - leftAt in 0 until config.briefBounceDebounceMs
        ) {
            currentPackage = pkg
            lastRealAppBeforeHome = null
            leftRealAppAtElapsedMs = null
            return ForegroundTransition.Ignored(IgnoreReason.TRANSIENT_BOUNCE)
        }

        val prev = currentPackage
        currentPackage = pkg
        lastRealAppBeforeHome = null
        leftRealAppAtElapsedMs = null
        lastAppWasBlocked = false
        return ForegroundTransition.AppEntered(pkg, prev, fromUnlock = false)
    }

    fun onScreenOff(elapsedMs: Long): ForegroundTransition {
        val prev = currentPackage
        isScreenInteractive = false
        screenOffAtElapsedMs = elapsedMs
        appBeforeScreenOff = prev
        lastRealAppBeforeHome = null
        leftRealAppAtElapsedMs = null
        return ForegroundTransition.ScreenOff(prev)
    }

    fun onUserPresent(elapsedMs: Long): ForegroundTransition {
        val offAt = screenOffAtElapsedMs
        val prevApp = appBeforeScreenOff
        isScreenInteractive = true
        screenOffAtElapsedMs = null
        appBeforeScreenOff = null

        val offDuration = if (offAt != null) (elapsedMs - offAt).coerceAtLeast(0L) else Long.MAX_VALUE
        if (offDuration < config.microLockMergeMs) {
            // Brief micro-lock: continue seamlessly in the same app without re-triggering a launch pause.
            currentPackage = prevApp
            return ForegroundTransition.MicroLockResumed(prevApp)
        }

        // Genuine unlock after a real screen-off: if unlocking directly into a non-home, non-self, non-call app,
        // fire an unlock entry right away (and set currentPackage so a duplicate window event right after is ignored).
        currentPackage = null
        lastAppWasBlocked = false
        if (prevApp != null && prevApp != classifier.ownPackage && !classifier.isLauncherPackage(prevApp) &&
            !classifier.isSystemUiPackage(prevApp) && !classifier.isCallOrDialerPackage(prevApp)
        ) {
            currentPackage = prevApp
            return ForegroundTransition.AppEntered(prevApp, previousPackage = null, fromUnlock = true)
        }
        return ForegroundTransition.Ignored(IgnoreReason.EMPTY)
    }
}
