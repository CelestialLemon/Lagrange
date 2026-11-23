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
        "com.google.android.gm" to "com.google.android.gm.ConversationListActivity",
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

        val enter = sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)
        assertEquals(ForegroundTransition.AppEntered("com.reddit.frontpage", null), enter)

        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.IME),
            sm.onWindowStateChanged("com.google.android.inputmethod.latin", "com.android.inputmethod.latin.LatinIME", 2_000),
        )
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SYSTEM_UI),
            sm.onWindowStateChanged("com.android.systemui", "android.widget.FrameLayout", 3_000),
        )
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.NON_ACTIVITY),
            sm.onWindowStateChanged("com.reddit.frontpage", "android.app.AlertDialog", 4_000),
        )
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.OWN_NON_ACTIVITY),
            sm.onWindowStateChanged("com.celestiallemon.lagrange", "android.widget.FrameLayout", 5_000),
        )
        assertEquals("com.reddit.frontpage", sm.currentPackage)

        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 6_000),
        )
    }

    @Test
    fun `rotation and in-app activity navigation never re-trigger launch pause`() {
        val sm = ForegroundAppStateMachine(classifier)
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 2_000),
        )
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.detail.DetailActivity", 3_000),
        )
    }

    @Test
    fun `emits BounceResumed on brief launcher bounce unless last exit was blocked`() {
        val sm = ForegroundAppStateMachine(classifier, ForegroundConfig(briefBounceDebounceMs = 1_500L))
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)

        assertTrue(sm.onWindowStateChanged("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity", 10_000) is ForegroundTransition.LeftToHome)
        assertEquals(
            ForegroundTransition.BounceResumed("com.reddit.frontpage"),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 10_800),
        )

        // Genuine exit to launcher (>1.5s) -> re-opening Reddit fires AppEntered.
        sm.onWindowStateChanged("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity", 20_000)
        assertEquals(
            ForegroundTransition.AppEntered("com.reddit.frontpage", null),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 22_000),
        )

        // Blocked exit -> immediate reopen within 500ms MUST trigger AppEntered, never BounceResumed!
        sm.markLastExitBlocked()
        sm.onWindowStateChanged("com.google.android.apps.nexuslauncher", "com.google.android.apps.nexuslauncher.NexusLauncherActivity", 22_100)
        assertEquals(
            ForegroundTransition.AppEntered("com.reddit.frontpage", null),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 22_500),
        )
    }

    @Test
    fun `micro-lock reconciles post-wake app changes and long lock avoids phantom pauses on notification taps`() {
        val sm = ForegroundAppStateMachine(classifier)
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 1_000)

        // Micro-lock with same app -> MicroLockResumed.
        sm.onScreenOff(10_000)
        assertEquals(ForegroundTransition.MicroLockResumed("com.reddit.frontpage"), sm.onUserPresent(15_000))

        // Micro-lock where a lockscreen notification launches Gmail before USER_PRESENT -> enters Gmail instead of Reddit!
        sm.onScreenOff(20_000)
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SCREEN_OFF),
            sm.onWindowStateChanged("com.google.android.gm", "com.google.android.gm.ConversationListActivity", 24_000),
        )
        assertEquals(
            ForegroundTransition.AppEntered("com.google.android.gm", "com.reddit.frontpage", fromUnlock = false),
            sm.onUserPresent(25_000),
        )

        // Long lock (>=15s) from Reddit, user unlocks by tapping a Gmail notification arriving right AFTER USER_PRESENT:
        // must NOT fire a phantom Reddit entry!
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 30_000)
        sm.onScreenOff(35_000)
        assertEquals(
            ForegroundTransition.UnlockAwaitingApp("com.reddit.frontpage"),
            sm.onUserPresent(65_000),
        )
        assertEquals(
            ForegroundTransition.AppEntered("com.google.android.gm", null, fromUnlock = true),
            sm.onWindowStateChanged("com.google.android.gm", "com.google.android.gm.ConversationListActivity", 65_080),
        )
        // Fallback timeout after Gmail already arrived is a no-op.
        assertEquals(ForegroundTransition.Ignored(IgnoreReason.EMPTY), sm.onUnlockFallbackTimeout())

        // Long lock (>=15s) unlocking directly into already-resumed Reddit without a window event -> fallback triggers!
        sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 70_000)
        sm.onScreenOff(75_000)
        assertEquals(ForegroundTransition.UnlockAwaitingApp("com.reddit.frontpage"), sm.onUserPresent(105_000))
        assertEquals(
            ForegroundTransition.AppEntered("com.reddit.frontpage", null, fromUnlock = true),
            sm.onUnlockFallbackTimeout(),
        )
        // Duplicate window event right after fallback is ignored as SAME_APP.
        assertEquals(
            ForegroundTransition.Ignored(IgnoreReason.SAME_APP),
            sm.onWindowStateChanged("com.reddit.frontpage", "com.reddit.launch.main.MainActivity", 105_400),
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
