package com.celestiallemon.lagrange.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createComposeRule
import com.celestiallemon.lagrange.data.db.IntentionTag
import com.celestiallemon.lagrange.domain.friction.ActiveOverlay
import com.celestiallemon.lagrange.domain.friction.LaunchPausePlan
import com.celestiallemon.lagrange.friction.LaunchPauseOverlayContent
import com.celestiallemon.lagrange.friction.TimeBlindnessNudgeContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The launch pause and the time nudge, as drawn over another app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h914dp-xxhdpi")
class OverlayScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private fun plan(tier: Int) = LaunchPausePlan(
        pkgId = 1, packageName = "com.instagram.android", displayName = "Instagram", logicalDate = "2026-11-24", tier = tier,
        pauseDurationSec = 8, nthOpenToday = 7, distractingMsToday = 71 * 60_000L, budgetMs = 90 * 60_000L,
        showTimerChips = true, encourageTimerSelection = tier >= 2, showIntentionChips = tier >= 2, showBudgetComparison = tier >= 2,
        timerPresetsMin = listOf(5, 10, 15), scheduleId = 1, scheduleName = "Work Hours", focusBoostActive = false,
        redirectPackageName = "com.amazon.kindle", redirectDisplayName = "Kindle", redirectPkgId = 9,
    )

    @Test fun launchPause() = compose.snapshot("overlay-pause-light") {
        // Shown two seconds into an eight-second pause, with an intention picked.
        val overlay = ActiveOverlay.LaunchPause(plan(2), 0, SystemClock.elapsedRealtime() - 2_000, selectedIntention = IntentionTag.BORED)
        LaunchPauseOverlayContent(overlay, {}, {}, {}, {}, {}, {})
    }

    @Test fun launchPauseDark() = compose.snapshot("overlay-pause-dark", dark = true) {
        val overlay = ActiveOverlay.LaunchPause(plan(2), 0, SystemClock.elapsedRealtime() - 2_000, selectedTimerMin = 10)
        LaunchPauseOverlayContent(overlay, {}, {}, {}, {}, {}, {})
    }

    @Test fun nudge() = compose.snapshot("overlay-nudge-light") {
        TimeBlindnessNudgeContent(ActiveOverlay.TimeBlindnessNudge(1, "com.instagram.android", "2026-11-24", 30 * 60_000L, 30, listOf("Instagram", "Reddit", "YouTube"), 0, 0, null), {})
    }
}
