package com.celestiallemon.lagrange.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.RadioButtonChecked
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.components.ComingSoonCard
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatRow
import com.celestiallemon.lagrange.ui.components.StatTile
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatAgo
import com.celestiallemon.lagrange.ui.formatCount
import com.celestiallemon.lagrange.ui.formatDate
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space

@Composable
fun TodayScreen(main: MainViewModel, padding: PaddingValues, onOpenSystem: () -> Unit) {
    val stats by main.stats.collectAsStateWithLifecycle()
    val health by main.health.collectAsStateWithLifecycle()
    val status = LocalStatusColors.current
    val now = System.currentTimeMillis()

    LagrangeScreen(
        eyebrow = "Today",
        title = "Building your baseline",
        subtitle = "Lagrange is quietly recording. Friction stays off for the first week or two so you get an honest before-and-after.",
        contentPadding = padding,
    ) {
        if (!health.coreReady) {
            item("setup") {
                SectionCard(
                    title = "Finish setting up",
                    eyebrow = "Action needed",
                    icon = Icons.Outlined.WarningAmber,
                    container = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Text(
                        "Usage Access and Notification Access let Lagrange collect. Until both are on, days are marked as partial coverage.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                    )
                    FilledTonalButton(onClick = onOpenSystem) { Text("Open Permission Health") }
                }
            }
        }
        item("collected") {
            SectionCard(title = "Collected today", eyebrow = "Since your day began", icon = Icons.Outlined.RadioButtonChecked) {
                StatRow {
                    StatTile("Usage events", formatCount(stats.rawEventsToday), Modifier.weight(1f))
                    StatTile("Notifications", formatCount(stats.notificationsToday), Modifier.weight(1f))
                }
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    CoverageLine(
                        "Usage harvest",
                        when {
                            stats.lastHarvestAtMs == null -> "Waiting for first harvest"
                            else -> "Last harvest ${formatAgo(stats.lastHarvestAtMs, now)}"
                        },
                        if (stats.harvestOverdue(now) || stats.lastHarvestAtMs == null) status.warn else status.good,
                    )
                    CoverageLine(
                        "Notification listener",
                        if (stats.listenerActive) "Recording metadata" else "Not connected",
                        if (stats.listenerActive) status.good else status.warn,
                    )
                    stats.earliestEventMs?.let {
                        CoverageLine("History begins", formatDate(it), status.neutral)
                    }
                }
            }
        }
        item("budget") {
            ComingSoonCard(
                "Phase 2",
                "Distracting time vs. budget",
                "Today's distracting minutes against your budget and your weekday/weekend baseline, with spike and good-day callouts.",
                Icons.Outlined.Insights,
            )
        }
        item("timeline") {
            ComingSoonCard(
                "Phase 2",
                "Session timeline",
                "Every pickup reconstructed into honest episodes: glances, micro-checks, rabbit holes and what triggered them.",
                Icons.Outlined.Timeline,
            )
        }
        item("focus") {
            ComingSoonCard(
                "Phase 2",
                "Phone-free streaks",
                "Your current and longest focus stretch, plus the overnight gap as a sleep proxy.",
                Icons.Outlined.Bolt,
            )
        }
    }
}

@Composable
private fun CoverageLine(label: String, detail: String, color: androidx.compose.ui.graphics.Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        StatusPill(detail, color)
    }
}
