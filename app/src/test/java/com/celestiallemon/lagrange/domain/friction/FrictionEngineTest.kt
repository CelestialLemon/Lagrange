package com.celestiallemon.lagrange.domain.friction

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.IntentionTag
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrictionEngineTest {
    private val reddit = FrictionAppPolicy(1L, "com.reddit.frontpage", "Reddit", AppCategory.DISTRACTING)
    private val youtube = FrictionAppPolicy(2L, "com.google.android.youtube", "YouTube", AppCategory.DISTRACTING)
    private val maps = FrictionAppPolicy(3L, "com.google.android.apps.maps", "Maps", AppCategory.UTILITY)
    private val chrome = FrictionAppPolicy(4L, "com.android.chrome", "Chrome", AppCategory.NEUTRAL)
    private val kindle = FrictionAppPolicy(5L, "com.amazon.kindle", "Kindle", AppCategory.NEUTRAL)
    private val own = FrictionAppPolicy(6L, "com.celestiallemon.lagrange", "Lagrange", AppCategory.SELF_APP)

    private val apps = listOf(reddit, youtube, maps, chrome, kindle, own).associateBy { it.packageName }
    private val zone = ZoneId.of("UTC")
    private val settings = FrictionSettings(frictionEnabled = true, graceWindowSec = 120, nudgeIntervalMin = 15)

    private val classifier = object : WindowClassifier {
        override val ownPackage = "com.celestiallemon.lagrange"
        override fun isActivity(packageName: String, className: String) = className.endsWith("Activity")
        override fun isImePackage(packageName: String) = packageName == "com.google.android.inputmethod.latin"
        override fun isSystemUiPackage(packageName: String) = packageName == "com.android.systemui"
        override fun isLauncherPackage(packageName: String) = packageName == "com.launcher"
        override fun isCallOrDialerPackage(packageName: String) = packageName == "com.dialer"
    }

    private fun usageOf(opens: Int = 0, distractingMs: Long = 0L, date: String = "2026-11-23") = TodayUsageSnapshot(
        logicalDate = date,
        appRealOpensToday = opens,
        distractingMsToday = distractingMs,
        promptsShownToday = 0,
        budgetMs = 60 * 60_000L,
    )

    private fun enter(
        engine: FrictionEngine,
        pkg: String,
        wallMs: Long,
        elapsedMs: Long,
        s: FrictionSettings = settings,
        schedules: List<FrictionSchedule> = emptyList(),
        usage: (FrictionAppPolicy) -> TodayUsageSnapshot = { usageOf() },
    ) = engine.onWindowStateChanged(
        packageName = pkg,
        className = "$pkg.MainActivity",
        nowWallMs = wallMs,
        nowElapsedMs = elapsedMs,
        zone = zone,
        settings = s,
        schedules = schedules,
        resolveApp = { apps[it]!! },
        loadUsage = usage,
        resolveRedirect = { apps[it] },
    )

    @Test
    fun `launch pause blocks early continue, logs reaction time, intention, timer, and grants 2m grace window`() {
        val engine = FrictionEngine(classifier)
        val wall0 = 1_700_000_000_000L
        val elapsed0 = 10_000L

        val show = enter(engine, reddit.packageName, wall0, elapsed0, usage = { usageOf(opens = 5) })
        val overlay = show.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay as ActiveOverlay.LaunchPause
        assertEquals(2, overlay.plan.tier)
        assertEquals(8, overlay.plan.pauseDurationSec)

        engine.onPauseIntentionSelected(IntentionTag.LOOKUP)
        engine.onPauseTimerSelected(5)

        // Attempt to continue before 8s countdown ends -> rejected.
        assertTrue(engine.onPauseContinue(wall0 + 5_000, elapsed0 + 5_000, settings).isEmpty())

        // Continue 420ms after countdown finishes -> records exact post_countdown_reaction_ms.
        val cont = engine.onPauseContinue(wall0 + 8_420, elapsed0 + 8_420, settings)
        val log = cont.filterIsInstance<FrictionEffect.LogEvent>().single().record
        assertEquals(FrictionVocab.LAUNCH_PAUSE, log.eventKind)
        assertEquals(FrictionVocab.OUTCOME_CONTINUED, log.outcome)
        assertEquals(wall0, log.timestampUtcMs)
        assertEquals(420L, log.postCountdownReactionMs)
        assertEquals(IntentionTag.LOOKUP.name, log.intentionTag)
        assertEquals(5, log.selectedTimerMin)

        // Switch to Chrome (OTP) and return to Reddit within 2 minutes -> grace window suppresses launch pause!
        enter(engine, chrome.packageName, wall0 + 30_000, elapsed0 + 30_000)
        val returnSoon = enter(engine, reddit.packageName, wall0 + 60_000, elapsed0 + 60_000)
        assertTrue(returnSoon.none { it is FrictionEffect.ShowOverlay })
        assertTrue(returnSoon.any { it is FrictionEffect.RecordUnpromptedOpen && it.pkgId == reddit.pkgId })

        // Switch away and return AFTER 2m grace window expires -> launch pause triggers again!
        enter(engine, chrome.packageName, wall0 + 135_000, elapsed0 + 135_000)
        val returnLate = enter(engine, reddit.packageName, wall0 + 140_000, elapsed0 + 140_000)
        assertTrue(returnLate.any { it is FrictionEffect.ShowOverlay && it.overlay is ActiveOverlay.LaunchPause })
    }

    @Test
    fun `go home, back, and healthy redirect log actual confirmed destination and never mislog failures`() {
        val engine = FrictionEngine(classifier)
        val withRedirect = settings.copy(redirectPackageName = kindle.packageName)

        // Failed Go Home -> keeps overlay active and logs NOTHING.
        enter(engine, reddit.packageName, 1_000_000, 1_000, s = withRedirect)
        assertTrue(engine.onPauseGoHome(1_000_500, 1_500, navigatedHome = false).isEmpty())
        assertTrue(engine.activeOverlay is ActiveOverlay.LaunchPause)

        // Confirmed Go Home -> logs CLOSED_GO_HOME and grants no grace window.
        val goHome = engine.onPauseGoHome(1_001_000, 2_000, navigatedHome = true)
        assertEquals(FrictionVocab.OUTCOME_CLOSED_GO_HOME, goHome.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)

        // Reopen immediately -> NO grace window, shows pause again!
        val reopen = enter(engine, reddit.packageName, 1_002_000, 3_000, s = withRedirect)
        assertTrue(reopen.any { it is FrictionEffect.ShowOverlay })

        // Failed redirect -> keeps overlay up; fallback to Home -> logs CLOSED_GO_HOME with null redirectPkgId.
        assertTrue(engine.onPauseRedirect(1_002_500, 3_500, RedirectLaunchResult.FAILED).isEmpty())
        val fallback = engine.onPauseRedirect(1_003_000, 4_000, RedirectLaunchResult.FELL_BACK_HOME)
        val fbLog = fallback.filterIsInstance<FrictionEffect.LogEvent>().single().record
        assertEquals(FrictionVocab.OUTCOME_CLOSED_GO_HOME, fbLog.outcome)
        assertNull(fbLog.redirectPkgId)

        // Successful redirect -> logs REDIRECTED with redirectPkgId.
        enter(engine, reddit.packageName, 1_004_000, 5_000, s = withRedirect)
        val redirect = engine.onPauseRedirect(1_004_500, 5_500, RedirectLaunchResult.LAUNCHED_REDIRECT)
        val rLog = redirect.filterIsInstance<FrictionEffect.LogEvent>().single().record
        assertEquals(FrictionVocab.OUTCOME_REDIRECTED, rLog.outcome)
        assertEquals(kindle.pkgId, rLog.redirectPkgId)

        // Back during pause -> logs CLOSED_SYSTEM_NAV.
        enter(engine, reddit.packageName, 1_006_000, 7_000, s = withRedirect)
        val back = engine.onPauseBackPressed(1_006_500, 7_500, navigatedHome = true)
        assertEquals(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, back.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)
    }

    @Test
    fun `brief launcher bounce keeps timer running, while home swipe and micro-lock cannot bypass unresolved pause`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L
        var e = 1_000L

        // 1. Active session + 2m timer -> bounce to Launcher for 500ms -> timer MUST keep counting down!
        enter(engine, reddit.packageName, w0, e)
        engine.onPauseTimerSelected(2)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)
        e += 30_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(90_000L, engine.liveStatus(e).activeTimerRemainingMs)

        // Bounce to Launcher and return 500ms later.
        enter(engine, "com.launcher", w0 + e, e)
        e += 500L
        val resumed = enter(engine, reddit.packageName, w0 + e, e)
        assertTrue(resumed.none { it is FrictionEffect.ShowOverlay })
        e += 30_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(60_000L, engine.liveStatus(e).activeTimerRemainingMs)

        // 2. Unresolved launch pause on YouTube -> Home swipe -> tap YouTube again in 500ms -> MUST re-show pause!
        e += 1_000L
        enter(engine, youtube.packageName, w0 + e, e)
        assertTrue(engine.activeOverlay is ActiveOverlay.LaunchPause)
        e += 500L
        val homeExit = enter(engine, "com.launcher", w0 + e, e)
        assertEquals(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, homeExit.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)
        e += 500L
        val reopen = enter(engine, youtube.packageName, w0 + e, e)
        assertTrue("Home swipe <1.5s must not bypass launch pause", reopen.any { it is FrictionEffect.ShowOverlay && it.overlay is ActiveOverlay.LaunchPause })

        // 3. Unresolved launch pause -> Power off + unlock in 5s (<15s micro-lock) -> MUST NOT bypass pause!
        e += 500L
        val screenOff = engine.onScreenOff(w0 + e, e)
        assertEquals(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, screenOff.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)
        e += 5_000L
        engine.onUserPresent(w0 + e, e, zone, settings, emptyList(), { apps[it]!! }, { usageOf() })
        val afterUnlock = enter(engine, youtube.packageName, w0 + e + 50, e + 50)
        assertTrue("Micro-lock <15s must not bypass unresolved launch pause", afterUnlock.any { it is FrictionEffect.ShowOverlay && it.overlay is ActiveOverlay.LaunchPause })
    }

    @Test
    fun `cross-app soft timer counts across distracting apps, pauses on utility-neutral-self, and respects snooze and disabled`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L
        var e = 1_000L

        enter(engine, reddit.packageName, w0, e)
        engine.onPauseTimerSelected(2)
        e += 3_000L
        engine.onPauseContinue(w0 + 3_000L, e, settings)

        e += 60_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(60_000L, engine.liveStatus(e).activeTimerRemainingMs)

        // Maps (UTILITY) & Lagrange (SELF_APP) pause the timer.
        enter(engine, maps.packageName, w0 + e, e)
        e += 300_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(60_000L, engine.liveStatus(e).activeTimerRemainingMs)

        enter(engine, own.packageName, w0 + e, e)
        e += 120_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(60_000L, engine.liveStatus(e).activeTimerRemainingMs)

        // YouTube (DISTRACTING) resumes and finishes the remaining 60s -> TimerExpired!
        enter(engine, youtube.packageName, w0 + e, e)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)
        e += 60_000L
        val tick = engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertTrue(tick.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay is ActiveOverlay.TimerExpired)

        // Snoozing while TimerExpired is showing dismisses it AND clears the expired timer so it never re-fires!
        val snoozed = engine.activateSnooze1h(w0 + e + 100, e + 100, "2026-11-23", youtube.pkgId)
        assertTrue(snoozed.any { it is FrictionEffect.DismissOverlay })
        assertNull(engine.liveStatus(e + 100).activeTimerRemainingMs)
        e += 2_000L
        assertTrue(engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }.none { it is FrictionEffect.ShowOverlay })

        // Cancel snooze, test +5m, Go Home, Back, and DismissSession.
        engine.cancelSnooze(e)
        enter(engine, maps.packageName, w0 + e, e)
        e += 1_000L
        enter(engine, reddit.packageName, w0 + e, e)
        engine.onPauseTimerSelected(2)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)
        e += 120_000L
        assertTrue(engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }.any { it is FrictionEffect.ShowOverlay })
        assertEquals(
            FrictionVocab.OUTCOME_EXTENDED_5M,
            engine.onTimerExtend5m(w0 + e + 100, e + 100).filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome,
        )
        e += 5 * 60_000L + 100L
        assertTrue(engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }.any { it is FrictionEffect.ShowOverlay })
        assertEquals(
            FrictionVocab.OUTCOME_DISMISSED_SESSION,
            engine.onTimerDismissSession(w0 + e + 100, e + 100).filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome,
        )
        assertNull(engine.liveStatus(e + 100).activeTimerRemainingMs)

        // Timer expiry suppressed when master frictionEnabled = false.
        enter(engine, maps.packageName, w0 + e + 200, e + 200)
        e += 200_000L // past grace
        enter(engine, reddit.packageName, w0 + e, e)
        engine.onPauseTimerSelected(2)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)
        e += 120_000L
        assertTrue(engine.onTick(w0 + e, e, zone, settings.copy(frictionEnabled = false), emptyList()) { usageOf() }.none { it is FrictionEffect.ShowOverlay })
    }

    @Test
    fun `timer expiry takes priority over launch pause on simultaneous transition`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L
        var e = 1_000L

        enter(engine, reddit.packageName, w0, e)
        engine.onPauseTimerSelected(2)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)

        e += 120_000L
        val effects = enter(engine, youtube.packageName, w0 + e, e)
        val overlay = effects.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay
        assertTrue("Expected TimerExpired to take priority over LaunchPause, got $overlay", overlay is ActiveOverlay.TimerExpired)
        val back = engine.onTimerBackPressed(w0 + e + 100, e + 100, navigatedHome = true)
        assertEquals(FrictionVocab.OUTCOME_CLOSED_SYSTEM_NAV, back.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)
    }

    @Test
    fun `time-blindness nudges track elapsed minutes across mid-session interval changes and fresh logical date`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L
        var e = 1_000L

        enter(engine, reddit.packageName, w0, e)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)

        // Start with 20m interval -> fires at 20m.
        val s20 = settings.copy(nudgeIntervalMin = 20)
        e += 20 * 60_000L
        val m1 = engine.onTick(w0 + e, e, zone, s20, emptyList()) { usageOf(date = "2026-11-23") }
        val n1 = m1.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay as ActiveOverlay.TimeBlindnessNudge
        assertEquals(20, n1.milestoneMinutes)
        assertEquals(FrictionVocab.OUTCOME_NUDGE_DISMISSED, engine.onNudgeDismissed(true).filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)

        // Switch mid-session to a 10m schedule at 22m -> must NOT emit a duplicate "20m" banner!
        val s10 = settings.copy(nudgeIntervalMin = 10)
        e += 2 * 60_000L
        assertTrue(engine.onTick(w0 + e, e, zone, s10, emptyList()) { usageOf(date = "2026-11-24") }.none { it is FrictionEffect.ShowOverlay })

        // At 30m -> fires 30m milestone with fresh logical date "2026-11-24"!
        e += 8 * 60_000L
        val m2 = engine.onTick(w0 + e, e, zone, s10, emptyList()) { usageOf(date = "2026-11-24") }
        val n2 = m2.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay as ActiveOverlay.TimeBlindnessNudge
        assertEquals(30, n2.milestoneMinutes)
        assertEquals("2026-11-24", n2.logicalDate)
        assertEquals(FrictionVocab.OUTCOME_NUDGE_IGNORED, engine.onNudgeDismissed(false).filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)
    }

    @Test
    fun `overlay attach failure clears overlay without logging false block and resumes tracking`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L
        enter(engine, reddit.packageName, w0, 1_000L)
        assertTrue(engine.activeOverlay is ActiveOverlay.LaunchPause)

        val recovery = engine.onOverlayAttachFailed(w0 + 50, 1_050L)
        assertNull(engine.activeOverlay)
        assertTrue(recovery.none { it is FrictionEffect.LogEvent })
        assertTrue(recovery.any { it is FrictionEffect.RecordUnpromptedOpen && it.pkgId == reddit.pkgId })

        // Leaving the app later must NOT log a phantom CLOSED_SYSTEM_NAV!
        val leave = enter(engine, maps.packageName, w0 + 10_000L, 11_000L)
        assertTrue(leave.none { it is FrictionEffect.LogEvent })
    }

    @Test
    fun `monotonic state checkpoints during ticks, preserves pending expiry, clears screen-off on micro-lock, and invalidates on reboot`() {
        val engineA = FrictionEngine(classifier)
        engineA.restore(null, currentBootCount = 4, nowElapsedMs = 1_000L)
        engineA.activateFocusBoost1h(1_000L)
        enter(engineA, reddit.packageName, 1_700_000_000_000L, 2_000L)
        engineA.onPauseTimerSelected(2)
        engineA.onPauseContinue(1_700_000_005_000L, 7_000L, settings) // Tier 1 (5s) due to Focus Boost

        // After 20s (> 15s checkpoint interval), onTick emits PersistState with updated timer remaining!
        val tickCheckpoint = engineA.onTick(1_700_000_025_000L, 27_000L, zone, settings, emptyList()) { usageOf() }
        val snap = tickCheckpoint.filterIsInstance<FrictionEffect.PersistState>().single().snapshot
        assertEquals(100_000L, snap.activeTimerRemainingMs)
        assertNotNull(snap.focusBoostUntilElapsedMs)

        // Same-boot restart preserves remaining timer and focus boost.
        val engineB = FrictionEngine(classifier)
        assertTrue(engineB.restore(snap, currentBootCount = 4, nowElapsedMs = 30_000L))
        assertEquals(100_000L, engineB.liveStatus(30_000L).activeTimerRemainingMs)

        // Pending expiry (0L remaining) is preserved across restart!
        val pendingExpirySnap = snap.copy(activeTimerRemainingMs = 0L)
        val enginePending = FrictionEngine(classifier)
        assertTrue(enginePending.restore(pendingExpirySnap, currentBootCount = 4, nowElapsedMs = 30_000L))
        assertEquals(0L, enginePending.liveStatus(30_000L).activeTimerRemainingMs)

        // Micro-lock resume persists cleared screenOffAtElapsedMs!
        engineA.onScreenOff(1_700_000_030_000L, 32_000L)
        val resumeEffects = engineA.onUserPresent(1_700_000_035_000L, 37_000L, zone, settings, emptyList(), { apps[it]!! }, { usageOf() })
        assertNull(resumeEffects.filterIsInstance<FrictionEffect.PersistState>().single().snapshot.screenOffAtElapsedMs)

        // Device reboot (bootCount changed to 5) -> discards stale snapshot!
        val engineC = FrictionEngine(classifier)
        assertFalse(engineC.restore(snap, currentBootCount = 5, nowElapsedMs = 5_000L))
        assertFalse(engineC.liveStatus(5_000L).isFocusBoostActive(5_000L))
        assertNull(engineC.liveStatus(5_000L).activeTimerRemainingMs)
    }
}
