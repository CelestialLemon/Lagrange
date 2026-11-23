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

    private fun usageOf(opens: Int = 0, distractingMs: Long = 0L) = TodayUsageSnapshot(
        logicalDate = "2026-11-23",
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

        // Select intention & 5m soft timer.
        engine.onPauseIntentionSelected(IntentionTag.LOOKUP)
        engine.onPauseTimerSelected(5)

        // Attempt to continue before 8s countdown ends -> rejected.
        assertTrue(engine.onPauseContinue(wall0 + 5_000, elapsed0 + 5_000, settings).isEmpty())

        // Continue 420ms after countdown finishes -> records exact post_countdown_reaction_ms.
        val cont = engine.onPauseContinue(wall0 + 8_420, elapsed0 + 8_420, settings)
        val log = cont.filterIsInstance<FrictionEffect.LogEvent>().single().record
        assertEquals(FrictionVocab.LAUNCH_PAUSE, log.eventKind)
        assertEquals(FrictionVocab.OUTCOME_CONTINUED, log.outcome)
        assertEquals(wall0, log.timestampUtcMs) // anchored at launch start for reconstructor matching
        assertEquals(420L, log.postCountdownReactionMs)
        assertEquals(IntentionTag.LOOKUP.name, log.intentionTag)
        assertEquals(5, log.selectedTimerMin)

        // Switch to Chrome (OTP) and return to Reddit within 2 minutes -> grace window suppresses launch pause!
        enter(engine, chrome.packageName, wall0 + 30_000, elapsed0 + 30_000)
        val returnSoon = enter(engine, reddit.packageName, wall0 + 60_000, elapsed0 + 60_000)
        assertTrue(returnSoon.none { it is FrictionEffect.ShowOverlay })

        // Switch away and return AFTER 2m grace window expires -> launch pause triggers again!
        enter(engine, chrome.packageName, wall0 + 135_000, elapsed0 + 135_000)
        val returnLate = enter(engine, reddit.packageName, wall0 + 140_000, elapsed0 + 140_000)
        assertTrue(returnLate.any { it is FrictionEffect.ShowOverlay && it.overlay is ActiveOverlay.LaunchPause })
    }

    @Test
    fun `go home and healthy redirect log blocking outcomes and grant no grace window`() {
        val engine = FrictionEngine(classifier)
        val withRedirect = settings.copy(redirectPackageName = kindle.packageName)

        // Go Home.
        enter(engine, reddit.packageName, 1_000_000, 1_000, s = withRedirect)
        val goHome = engine.onPauseGoHome(1_001_000, 2_000)
        assertTrue(goHome.any { it is FrictionEffect.PerformGoHome })
        assertEquals(FrictionVocab.OUTCOME_CLOSED_GO_HOME, goHome.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)

        // Reopen immediately -> NO grace window, shows pause again!
        val reopen = enter(engine, reddit.packageName, 1_002_000, 3_000, s = withRedirect)
        assertTrue(reopen.any { it is FrictionEffect.ShowOverlay })

        // Healthy redirect -> launches Kindle & logs REDIRECTED with redirectPkgId.
        val redirect = engine.onPauseRedirect(1_003_000, 4_000)
        val log = redirect.filterIsInstance<FrictionEffect.LogEvent>().single().record
        assertEquals(FrictionVocab.OUTCOME_REDIRECTED, log.outcome)
        assertEquals(kindle.pkgId, log.redirectPkgId)
        assertEquals(kindle.packageName, redirect.filterIsInstance<FrictionEffect.LaunchRedirect>().single().packageName)
    }

    @Test
    fun `cross-app soft timer counts across distracting apps, pauses on utility-neutral-self, and clears past 15s lock`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L
        var e = 1_000L

        // Open Reddit, pick 2m soft timer, continue after 3s.
        enter(engine, reddit.packageName, w0, e)
        engine.onPauseTimerSelected(2)
        e += 3_000L
        engine.onPauseContinue(w0 + 3_000L, e, settings)

        // Spend 60s in Reddit -> 60s remaining.
        e += 60_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(60_000L, engine.liveStatus(e).activeTimerRemainingMs)

        // Switch to Maps (UTILITY) for 5 minutes -> timer MUST pause at 60s!
        enter(engine, maps.packageName, w0 + e, e)
        e += 300_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(60_000L, engine.liveStatus(e).activeTimerRemainingMs)

        // Switch to Lagrange (SELF_APP) for 2 minutes -> timer stays paused!
        enter(engine, own.packageName, w0 + e, e)
        e += 120_000L
        engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        assertEquals(60_000L, engine.liveStatus(e).activeTimerRemainingMs)

        // Switch to YouTube (another DISTRACTING app), pass pause, spend 60s -> timer expires!
        enter(engine, youtube.packageName, w0 + e, e)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)
        e += 60_000L
        val tick = engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        val expired = tick.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay as ActiveOverlay.TimerExpired
        assertEquals(youtube.packageName, expired.packageName)

        // Tap +5 min -> logs EXTENDED_5M and resets timer to 5m.
        val ext = engine.onTimerExtend5m(w0 + e + 500, e + 500)
        assertEquals(FrictionVocab.OUTCOME_EXTENDED_5M, ext.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)
        assertEquals(5 * 60_000L, engine.liveStatus(e + 500).activeTimerRemainingMs)

        // Screen off for 20s (> 15s micro-lock merge window) -> timer clears on unlock!
        e += 2_000L
        engine.onScreenOff(w0 + e, e)
        e += 20_000L
        engine.onUserPresent(w0 + e, e, zone, settings, emptyList(), { apps[it]!! }, { usageOf() })
        assertNull(engine.liveStatus(e).activeTimerRemainingMs)
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

        // Exact 2m later, user switches straight from Reddit into YouTube before a tick fires.
        e += 120_000L
        val effects = enter(engine, youtube.packageName, w0 + e, e)
        val overlay = effects.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay
        assertTrue("Expected TimerExpired to take priority over LaunchPause, got $overlay", overlay is ActiveOverlay.TimerExpired)
    }

    @Test
    fun `time-blindness nudges fire at 15m and 30m with app-hop trail and log dismiss outcomes`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L
        var e = 1_000L

        // Enter Reddit and pass pause.
        enter(engine, reddit.packageName, w0, e)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)

        // 10m in Reddit -> no nudge yet.
        e += 10 * 60_000L
        assertTrue(engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }.isEmpty())

        // Hop to YouTube, pass pause, spend 5m -> 15m cumulative distracting time -> milestone 1 nudge!
        enter(engine, youtube.packageName, w0 + e, e)
        e += 3_000L
        engine.onPauseContinue(w0 + e, e, settings)
        e += 5 * 60_000L
        val m1 = engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        val nudge1 = m1.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay as ActiveOverlay.TimeBlindnessNudge
        assertEquals(15, nudge1.milestoneMinutes)
        assertEquals(listOf("Reddit", "YouTube"), nudge1.appsHopped)

        // User taps to dismiss -> logs NUDGE_DISMISSED.
        val d1 = engine.onNudgeDismissed(userTapped = true)
        assertEquals(FrictionVocab.OUTCOME_NUDGE_DISMISSED, d1.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)

        // Another 15m -> 30m milestone!
        e += 15 * 60_000L
        val m2 = engine.onTick(w0 + e, e, zone, settings, emptyList()) { usageOf() }
        val nudge2 = m2.filterIsInstance<FrictionEffect.ShowOverlay>().single().overlay as ActiveOverlay.TimeBlindnessNudge
        assertEquals(30, nudge2.milestoneMinutes)
        // Auto-dismiss -> logs NUDGE_IGNORED.
        val d2 = engine.onNudgeDismissed(userTapped = false)
        assertEquals(FrictionVocab.OUTCOME_NUDGE_IGNORED, d2.filterIsInstance<FrictionEffect.LogEvent>().single().record.outcome)
    }

    @Test
    fun `1-hour snooze logs SNOOZE_1H and suppresses friction monotonically until expiry`() {
        val engine = FrictionEngine(classifier)
        val w0 = 1_700_000_000_000L

        enter(engine, reddit.packageName, w0, 1_000L)
        val snooze = engine.activateSnooze1h(w0 + 1_000L, 2_000L, "2026-11-23", reddit.pkgId)
        val rec = snooze.filterIsInstance<FrictionEffect.LogEvent>().single().record
        assertEquals(FrictionVocab.SNOOZE_1H, rec.eventKind)
        assertEquals(FrictionVocab.OUTCOME_SNOOZE_ACTIVATED, rec.outcome)
        assertTrue(engine.liveStatus(2_000L).isSnoozed(2_000L))

        // During the hour -> YouTube does not trigger launch pause.
        assertTrue(enter(engine, youtube.packageName, w0 + 10_000L, 10_000L).none { it is FrictionEffect.ShowOverlay })

        // After 1 hour -> friction automatically resumes!
        val afterSnooze = 2_000L + FrictionSettings.SNOOZE_DURATION_MS + 1_000L
        assertFalse(engine.liveStatus(afterSnooze).isSnoozed(afterSnooze))
        val resumed = enter(engine, reddit.packageName, w0 + afterSnooze, afterSnooze)
        assertTrue(resumed.any { it is FrictionEffect.ShowOverlay && it.overlay is ActiveOverlay.LaunchPause })
    }

    @Test
    fun `monotonic state snapshot restores across same-boot restart and invalidates on reboot`() {
        val engineA = FrictionEngine(classifier)
        engineA.restore(null, currentBootCount = 4, nowElapsedMs = 1_000L)
        engineA.activateFocusBoost1h(1_000L)
        enter(engineA, reddit.packageName, 1_700_000_000_000L, 2_000L)
        engineA.onPauseTimerSelected(10)
        engineA.onPauseContinue(1_700_000_005_000L, 7_000L, settings) // Tier 1 (5s) due to Focus Boost!

        val snap = engineA.snapshot(10_000L)
        assertNotNull(snap.activeTimerRemainingMs)
        assertNotNull(snap.focusBoostUntilElapsedMs)
        assertTrue(reddit.packageName in snap.graceUntilByPackage)

        // Same boot, later elapsed time -> restores cleanly!
        val engineB = FrictionEngine(classifier)
        assertTrue(engineB.restore(snap, currentBootCount = 4, nowElapsedMs = 20_000L))
        val statusB = engineB.liveStatus(20_000L)
        assertTrue(statusB.isFocusBoostActive(20_000L))
        assertEquals(snap.activeTimerRemainingMs, statusB.activeTimerRemainingMs)

        // Device rebooted (bootCount changed to 5) -> discards stale snapshot!
        val engineC = FrictionEngine(classifier)
        assertFalse(engineC.restore(snap, currentBootCount = 5, nowElapsedMs = 5_000L))
        assertFalse(engineC.liveStatus(5_000L).isFocusBoostActive(5_000L))
        assertNull(engineC.liveStatus(5_000L).activeTimerRemainingMs)
    }
}
