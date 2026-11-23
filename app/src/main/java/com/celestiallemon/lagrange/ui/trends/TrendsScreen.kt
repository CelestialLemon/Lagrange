package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.QueryStats
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.runtime.Composable
import com.celestiallemon.lagrange.ui.components.ComingSoonCard
import com.celestiallemon.lagrange.ui.components.LagrangeScreen

@Composable
fun TrendsScreen(padding: PaddingValues) {
    LagrangeScreen(
        eyebrow = "Trends",
        title = "The long view",
        subtitle = "Weeks, months and years of history, with incomplete days shown honestly rather than as zeros.",
        contentPadding = padding,
    ) {
        item("horizons") {
            ComingSoonCard(
                "Phase 2",
                "Multi-horizon trends",
                "7D to all-time charts of distracting, active and total screen time, pickups and glances, with 7- and 28-day averages and coverage shading.",
                Icons.AutoMirrored.Outlined.ShowChart,
            )
        }
        item("baselines") {
            ComingSoonCard(
                "Phase 2",
                "Weekday & weekend baselines",
                "Fair comparisons against matched days, unlocked once at least seven covered days exist.",
                Icons.Outlined.CalendarMonth,
            )
        }
        item("deep-dive") {
            ComingSoonCard(
                "Phase 2",
                "Per-app deep dive",
                "Time-of-day profile, session length distribution, inflow and outflow apps, trigger evidence and friction stats for any app.",
                Icons.Outlined.QueryStats,
            )
        }
    }
}
