package com.celestiallemon.lagrange.ui

import android.app.Application
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.test.junit4.createComposeRule
import com.celestiallemon.lagrange.data.RecomputeProgress
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.ui.today.TodayContent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders each screen from fixtures, in light and dark, on a Pixel 7a-sized window (tall, so the whole scroll is
 * visible). Images land in app/build/screenshots; the test also fails if a screen throws while composing.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class ScreensScreenshotTest {
    @get:Rule val compose = createComposeRule()

    private val f = ScreenFixtures

    @Test fun todayLight() = compose.snapshot("today-light") {
        TodayContent(f.today(), f.stats, f.health, RecomputeProgress(), null, f.now, PaddingValues())
    }

    @Test fun trendsLight() = compose.snapshot("trends-light") {
        com.celestiallemon.lagrange.ui.trends.TrendsContent(f.trends(), PaddingValues(), com.celestiallemon.lagrange.ui.trends.TrendsActions())
    }

    @Test fun trendsDark() = compose.snapshot("trends-dark", dark = true) {
        com.celestiallemon.lagrange.ui.trends.TrendsContent(f.trends(), PaddingValues(), com.celestiallemon.lagrange.ui.trends.TrendsActions())
    }

    @Test fun frictionLight() = compose.snapshot("friction-light") {
        com.celestiallemon.lagrange.ui.friction.FrictionContent(f.friction("on"), PaddingValues(), null)
    }

    @Test fun frictionDark() = compose.snapshot("friction-dark", dark = true) {
        com.celestiallemon.lagrange.ui.friction.FrictionContent(f.friction("snoozed"), PaddingValues(), null)
    }

    @Test fun todayDark() = compose.snapshot("today-dark", dark = true) {
        TodayContent(f.today(status = DayStatus.SPIKE, distracting = 112 * 60_000L), f.stats, f.health, RecomputeProgress(), null, f.now, PaddingValues())
    }
}
