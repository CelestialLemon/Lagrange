package com.celestiallemon.lagrange.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.BarRow
import com.celestiallemon.lagrange.ui.components.Choice
import com.celestiallemon.lagrange.ui.components.Dot
import com.celestiallemon.lagrange.ui.components.LeadingIcon
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.OrbitGauge
import com.celestiallemon.lagrange.ui.components.OrbitMark
import com.celestiallemon.lagrange.ui.components.Panel
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.SectionHeader
import com.celestiallemon.lagrange.ui.components.Segment
import com.celestiallemon.lagrange.ui.components.SplitMeter
import com.celestiallemon.lagrange.ui.components.Stat
import com.celestiallemon.lagrange.ui.components.StatStrip
import com.celestiallemon.lagrange.ui.components.SwitchRow
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.components.ValueRow
import com.celestiallemon.lagrange.ui.components.WorthScale
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The component specimen sheet: every building block of the design system on one page, light and dark. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h1800dp-xxhdpi")
class DesignSystemScreenshotTest {
    @get:Rule val compose = createComposeRule()

    @Test fun specimenLight() = compose.snapshot("design-system-light") { Specimen() }

    @Test fun specimenDark() = compose.snapshot("design-system-dark", dark = true) { Specimen() }
}

@Composable
private fun Specimen() {
    val c = Lagrange.colors
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(Spacing.gutter),
        verticalArrangement = Arrangement.spacedBy(Spacing.s),
    ) {
        Text("Lagrange", style = MaterialTheme.typography.displaySmall)
        Text("A lighter day so far", style = MaterialTheme.typography.headlineSmall)
        Text("Body text reads in Roboto with comfortable spacing, and numbers like 1h 42m use tabular figures.", style = MaterialTheme.typography.bodyLarge)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.l)) {
            OrbitGauge(0.72f, c.category.distracting) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("1h 05m", style = NumberStyles.hero.copy(fontSize = NumberStyles.stat.fontSize * 1.4f))
                    Text("of 1h 30m", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            OrbitMark(64.androidx())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Tag("Typical")
            Tag("Good day", tone = Tone.Good)
            Tag("Spike", tone = Tone.Ember)
            Tag("Gap", tone = Tone.Caution)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Tag("Notification", icon = Icons.Outlined.NotificationsActive, tone = Tone.Brand)
            Tag("Over", tone = Tone.Critical)
            Tag("Distracting", dot = c.category.distracting)
        }
        StatStrip {
            Stat("Active", "3h 12m", Modifier.weight(1f), caption = "+24m vs usual", captionTone = Tone.Caution)
            Stat("Pickups", "48", Modifier.weight(1f), caption = "usual 52")
            Stat("Phone-free", "2h 05m", Modifier.weight(1f), caption = "longest")
        }
        SplitMeter(listOf(Segment(3f, c.category.distracting), Segment(4f, c.category.neutral), Segment(2f, c.category.utility), Segment(0.5f, c.category.excluded)))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.m)) {
            listOf(c.category.distracting, c.category.neutral, c.category.utility, c.category.excluded).forEach { Dot(it) }
        }
        BarRow("Instagram", "52m", 0.9f, color = c.category.distracting)
        BarRow("Chrome", "31m", 0.55f, color = c.category.neutral)
        SectionHeader("Rules", supporting = "How the pause behaves")
        RowGroup {
            ValueRow("Daily budget", "1h 30m", onClick = {}, leading = { LeadingIcon(Icons.Outlined.Timer) })
            SwitchRow("Escalating pauses", checked = true, onCheckedChange = {}, supporting = "Longer pauses the more you open")
            ListRow("Instagram", supporting = "Distracting · pause on")
        }
        Banner("Finish setting up", Tone.Caution, Icons.Outlined.WarningAmber, body = "Notification Access is off, so triggers can't be traced.", action = "Open Permission Health" to {})
        Panel {
            Text("Was that worth it?", style = MaterialTheme.typography.titleMedium)
            WorthScale(current = 4, onRate = {})
        }
        Choice(listOf("7D", "30D", "90D", "1Y", "All"), "30D", {}, { it }, forceChips = true)
        Choice(listOf("Off", "1m", "2m", "5m"), "2m", {}, { it })
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Button(onClick = {}) { Text("Go home") }
            FilledTonalButton(onClick = {}) { Text("Snooze") }
            OutlinedButton(onClick = {}) { Text("Edit") }
        }
    }
}

private fun Int.androidx() = androidx.compose.ui.unit.Dp(this.toFloat())
