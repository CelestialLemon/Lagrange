package com.celestiallemon.lagrange.ui.lab

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.DoorFront
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.runtime.Composable
import com.celestiallemon.lagrange.ui.components.ComingSoonCard
import com.celestiallemon.lagrange.ui.components.LagrangeScreen

@Composable
fun LabScreen(padding: PaddingValues) {
    LagrangeScreen(
        eyebrow = "Behavior Lab",
        title = "Why it happens",
        subtitle = "Explanations, not just totals: what pulls you in, what keeps you there, and what actually helps.",
        contentPadding = padding,
    ) {
        item("rabbit") {
            ComingSoonCard("Phase 4", "Rabbit Hole Explorer", "Long binges, multi-app spirals and hijacked pickups, each with its trigger, hop chain and an optional \"worth it?\" rating.", Icons.Outlined.Cyclone)
        }
        item("gateway") {
            ComingSoonCard("Phase 4", "Gateway apps", "Which apps most often lead into spirals, with small-sample shrinkage so one-off sessions don't dominate.", Icons.Outlined.DoorFront)
        }
        item("notif") {
            ComingSoonCard("Phase 4", "Notification cost", "Volume, click rate, pickup lift and downstream minutes per app and channel, with a one-tap shortcut to mute a noisy channel.", Icons.Outlined.NotificationsActive)
        }
        item("heatmap") {
            ComingSoonCard("Phase 4", "Circadian heatmap", "Day-of-week by hour, late-night trend and the overnight phone-free gap.", Icons.Outlined.GridOn)
        }
        item("friction") {
            ComingSoonCard("Phase 4", "Honest friction effectiveness", "Immediate exits, sustained exits, substitution between apps and habituation over time.", Icons.Outlined.Psychology)
        }
    }
}
