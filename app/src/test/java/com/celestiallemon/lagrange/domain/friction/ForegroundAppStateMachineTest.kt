package com.celestiallemon.lagrange.domain.friction

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundAppStateMachineTest {
    private val activities = mutableSetOf(
        "com.reddit.frontpage" to "com.reddit.launch.main.MainActivity",
        "com.reddit.frontpage" to "com.reddit.detail.DetailActivity",
        "com.google.android.youtube" to "com.google.android.youtube.HomeActivity",
        "com.android.chrome" to "com.google.android.apps.chrome.Main",
        "com.celestiallemon.lagrange" to "com.celestiallemon.lagrange.MainActivity",
        "com.google.android.apps.nexuslauncher" to "com.google.android.apps.nexuslauncher.NexusLauncherActivity",
        "com.google.android.dialer" to "com.android.incallui.InCallActivity",
    )

    private val classifier = object : WindowClassifier {
        override val ownPackage = "com.celestiallemon.lagrange"
        override fun isActivity(packageName: String, className: String) = (packageName to className) in activities
        override fun isImePackage(packageName: String) = packageName == "com.google.android.inputmethod.latin"
        override fun isSystemUiPackage(packageName: String) = packageName in setOf("com.android.systemui", "android", "com.google.android.permissioncontroller")
        override fun isLauncherPackage(packageName: String) = packageName == "com.google.android.apps.nexuslauncher"
        override fun isCallOrDialerPackage(packageName: String) = packageName == "com.google.android.dialer"
    }

    @Test
    fun `ignores IME, SystemUI, dialogs, toasts, and own overlay windows`() {
        val sm = ForegroundAppStateMachine(classifier)

        // Enter Reddit.
        val enter = sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)
        assertEquals(ForegroundTransition.AppEntered("com.reddit.frontpage", null), enter)

        // Keyboard opens -> ignored, Reddit stays foreground.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.IME),
            sm.onWindowStateChanged("com.google.android.inputmethod.latin", "com.android.inputmethod.latin.LatinIME", 2_000),
        )
        // Notification shade / volume panel -> ignored.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SYSTEM_UI),
            sm.onWindowStateChanged("com.android.systemui", "android.widget.FrameLayout", 3_000),
        )
        // Dialog / toast / non-Activity pane -> ignored.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.NON_ACTIVITY),
            sm.onWindowStateChanged("com.reddit.frontpage", "android.app.AlertDialog", 4_000),
        )
        // Own TYPE_ACCESSIBILITY_OVERLAY window -> ignored (never triggers a loop).
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.OWN_NON_ACTIVITY),
            sm.onWindowStateChanged("com.celestiallemon.lagrange", "android.widget.FrameLayout", 5_000),
        )
        assertEquals("com.reddit.frontpage", sm.currentPackage)

        // Returning focus to Reddit after shade closes -> same app, no re-trigger.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 6_000),
        )
    }

    @Test
    fun `rotation and in-app activity navigation never re-trigger launch pause`() {
        val sm = ForegroundAppStateMachine(classifier)
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)
        // Screen rotation recreates MainActivity.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 2_000),
        )
        // Navigating to DetailActivity inside Reddit stays same app.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.detail.DetailActivity", 3_000),
        )
    }

    @Test
    fun `debounces brief launcher bounce unless last exit was blocked`() {
        val sm = ForegroundAppStateMachine(classifier, ForegroundConfig(briefBounceDebounceMs = 1_500L))
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)

        // Brief accidental swipe to launcher and back within 800ms -> debounced.
        assertTrue(sm.onWindowStateChanged("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity", 10_000) is ForegroundTransition.LeftToHome)
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.TRANSIENT_BOUNCE),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 10_800),
        )

        // Genuine exit to launcher (>1.5s) -> re-opening Reddit fires AppEntered.
        sm.onWindowStateChanged("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity", 20_000)
        assertEquals(
            ForegroundTransition.AppEntered("com.reddit.frontpage", null),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 22_000),
        )

        // When user chose Go Home on a launch pause (blocked), immediate reopen within 500ms MUST trigger!
        sm.markLastExitBlocked()
        sm.onWindowStateChanged("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity", 22_100)
        assertEquals(
            ForegroundTransition.AppEntered("com.reddit.frontpage", null),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 22_500),
        )
    }

    @Test
    fun `micro-lock continues seamlessly while long lock unlocks into fresh app entry`() {
        val sm = ForegroundAppStateMachine(classifier)
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)

        // Micro-lock: 5s screen off -> resumes Reddit seamlessly without re-triggering pause.
        sm.onScreenOff(10_000)
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SCREEN_OFF),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 12_000),
        )
        assertEquals(ForegroundTransition.MicroLockResumed("com.reddit.frontpage"), sm.onUserPresent(15_000))
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 15_100),
        )

        // Real screen off: 30s -> unlocking straight into Reddit fires AppEntered(fromUnlock = true).
        sm.onScreenOff(20_000)
        assertEquals(
            ForegroundTransition.AppEntered("com.reddit.frontpage", null, fromUnlock = true),
            sm.onUserPresent(50_000),
        )
        // Follow-up window event from Android right after unlock is ignored as SAME_APP.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 50_050),
        )
    }

    @Test
    fun `incoming call and own main activity produce dedicated transitions`() {
        val sm = ForegroundAppStateMachine(classifier)
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)
        assertEquals(
            ForegroundTransition.CallInterrupted("com.google.android.dialer", "com.reddit.frontpage"),
            sm.onWindowStateChanged("com.google.android.dialer", "com.android.incallui.InCallActivity", 2_000),
        )
        assertEquals(
            ForegroundTransition.SelfAppEntered("com.google.android.dialer"),
            sm.onWindowStateChanged("com.celestiallemon.lagrange", "com.celestiallemon.lagrange.MainActivity", 3_000),
        )
        assertEquals("com.celestiallemon.lagrange", sm.currentPackage)
        assertTrue(sm.onWindowStateChanged("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity", 4_000) is ForegroundTransition.LeftToHome)
        assertNull(sm.currentPackage)
    }
}
