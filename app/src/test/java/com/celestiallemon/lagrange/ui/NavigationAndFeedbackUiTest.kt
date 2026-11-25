package com.celestiallemon.lagrange.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasStateDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.Destination
import com.celestiallemon.lagrange.data.RecomputeProgress
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.system.SettingsHost
import com.celestiallemon.lagrange.ui.theme.LagrangeTheme
import com.celestiallemon.lagrange.ui.today.SessionDetailSheet
import com.celestiallemon.lagrange.ui.today.TodayActions
import com.celestiallemon.lagrange.ui.today.TodayContent
import com.celestiallemon.lagrange.ui.trends.TrendsActions
import com.celestiallemon.lagrange.ui.trends.TrendsContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = Application::class, qualifiers = "w411dp-h2400dp-xxhdpi")
class NavigationAndFeedbackUiTest {
    @get:Rule val compose = createComposeRule()

    private fun navTab(label: String) = compose.onNode(hasText(label) and isSelectable())

    @Test
    fun `bottom bar renders Today, Trends, Friction, Settings without Lab and shows attention badge`() {
        var attention by mutableStateOf(true)
        compose.setContent {
            LagrangeTheme {
                LagrangeShellContent(
                    attention = attention,
                    todayContent = { _, _ -> Text("Today body") },
                    trendsContent = { _, _, _, _ -> Text("Trends body") },
                    frictionContent = { _ -> Text("Friction body") },
                    settingsContent = { _, _, _, _ -> Text("Settings body") },
                )
            }
        }
        assertEquals(listOf("Today", "Trends", "Friction", "Settings"), Tab.entries.map { it.label })
        navTab("Today").assertIsDisplayed()
        navTab("Trends").assertIsDisplayed()
        navTab("Friction").assertIsDisplayed()
        navTab("Settings").assertIsDisplayed().assert(hasStateDescription("Needs attention"))
        compose.onNodeWithText("Lab").assertDoesNotExist()
        compose.onNodeWithContentDescription("Needs attention", useUnmergedTree = true).assertExists()

        attention = false
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Needs attention", useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText("Settings") and isSelectable() and hasStateDescription("Needs attention")).assertDoesNotExist()
    }

    @Test
    fun `Settings preserves scroll on ordinary tab switch while shortcuts and deep links reset to Permissions and dismiss inspector`() {
        val f = ScreenFixtures
        val needsSetup = f.health.copy(usageAccess = false)
        var destination by mutableStateOf<Destination?>(null)
        var consumed = 0
        var capturedSettingsListState: LazyListState? = null

        compose.setContent {
            LagrangeTheme {
                Box(Modifier.size(411.dp, 640.dp)) {
                    LagrangeShellContent(
                        attention = true,
                        destination = destination,
                        onConsumeDestination = {
                            consumed++
                            destination = null
                        },
                        todayContent = { padding, onOpenPermissions ->
                            TodayContent(
                                state = f.today(),
                                stats = f.stats,
                                health = needsSetup,
                                progress = RecomputeProgress(),
                                now = f.now,
                                padding = padding,
                                actions = TodayActions(onOpenSettings = onOpenPermissions),
                            )
                        },
                        trendsContent = { _, _, _, _ -> Text("Trends body") },
                        frictionContent = { _ -> Text("Friction body") },
                        settingsContent = { padding, inspecting, onInspectingChange, scrollToTopToken ->
                            SettingsHost(
                                inspecting = inspecting,
                                onInspectingChange = onInspectingChange,
                                scrollToTopToken = scrollToTopToken,
                                base = { listState ->
                                    capturedSettingsListState = listState
                                    LagrangeScreen(title = "Settings", contentPadding = padding, listState = listState) {
                                        item("summary") { Text("Recording health") }
                                        item("perms") { Text("Permissions") }
                                        items(14) { i ->
                                            Box(Modifier.fillMaxWidth().height(100.dp)) {
                                                Text("Settings item $i")
                                            }
                                        }
                                        item("tools") {
                                            Box(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .height(64.dp)
                                                    .clickable { onInspectingChange(true) },
                                            ) {
                                                Text("Timeline inspector")
                                            }
                                        }
                                    }
                                },
                                inspector = {
                                    LagrangeScreen(title = "Timeline inspector", contentPadding = padding, onBack = { onInspectingChange(false) }) {
                                        item("ledger") { Text("Inspector ledger") }
                                    }
                                },
                            )
                        },
                    )
                }
            }
        }

        fun currentListState(): LazyListState = checkNotNull(capturedSettingsListState)

        // Open Settings via the bottom bar and scroll down to Tools.
        navTab("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Permissions").assertIsDisplayed()
        assertEquals(0, currentListState().firstVisibleItemIndex)

        compose.runOnIdle { runBlocking { currentListState().scrollToItem(16) } }
        compose.waitForIdle()
        assertTrue(currentListState().firstVisibleItemIndex > 0)
        compose.onNodeWithText("Timeline inspector").assertIsDisplayed()
        compose.onNodeWithText("Permissions").assertDoesNotExist()

        // Ordinary tab switch back to Settings preserves the saved scroll position.
        navTab("Today").performClick()
        compose.waitForIdle()
        navTab("Settings").performClick()
        compose.waitForIdle()
        assertTrue(currentListState().firstVisibleItemIndex > 0)
        compose.onNodeWithText("Timeline inspector").assertIsDisplayed()
        compose.onNodeWithText("Permissions").assertDoesNotExist()

        // Opening the inspector and re-tapping Settings dismisses the inspector.
        compose.onNodeWithText("Timeline inspector").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Inspector ledger").assertIsDisplayed()
        navTab("Settings").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Inspector ledger").assertDoesNotExist()
        compose.onNodeWithText("Timeline inspector").assertIsDisplayed()

        // Opening the inspector, switching to Today, and tapping "Open permissions" dismisses the inspector
        // and resets Settings scroll to the top / Permissions section.
        compose.onNodeWithText("Timeline inspector").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Inspector ledger").assertIsDisplayed()
        navTab("Today").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Open permissions").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Inspector ledger").assertDoesNotExist()
        assertEquals(0, currentListState().firstVisibleItemIndex)
        compose.onNodeWithText("Permissions").assertIsDisplayed()

        // Scrolling down and opening the inspector again, then receiving Destination.SETTINGS deep link
        // also dismisses the inspector and resets Settings scroll to Permissions.
        compose.runOnIdle { runBlocking { currentListState().scrollToItem(16) } }
        compose.waitForIdle()
        compose.onNodeWithText("Timeline inspector").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Inspector ledger").assertIsDisplayed()
        destination = Destination.SETTINGS
        compose.waitForIdle()
        assertEquals(1, consumed)
        compose.onNodeWithText("Inspector ledger").assertDoesNotExist()
        assertEquals(0, currentListState().firstVisibleItemIndex)
        compose.onNodeWithText("Permissions").assertIsDisplayed()

        // Subsequent ordinary tab switch after shortcut resets still preserves scroll position.
        compose.runOnIdle { runBlocking { currentListState().scrollToItem(16) } }
        compose.waitForIdle()
        navTab("Today").performClick()
        compose.waitForIdle()
        navTab("Settings").performClick()
        compose.waitForIdle()
        assertTrue(currentListState().firstVisibleItemIndex > 0)
        compose.onNodeWithText("Timeline inspector").assertIsDisplayed()
    }

    @Test
    fun `Today omits session feedback prompts, Rated filter, and top-bar Settings button`() {
        val f = ScreenFixtures
        compose.setContent {
            LagrangeTheme {
                TodayContent(f.today(), f.stats, f.health, RecomputeProgress(), f.now, PaddingValues())
            }
        }
        compose.onNodeWithText("Today").assertExists()
        compose.onNodeWithText("Was that worth it?").assertDoesNotExist()
        compose.onNodeWithText("Rated").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings, needs attention").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }

    @Test
    fun `SessionDetailSheet omits the worth-it rating prompt`() {
        val f = ScreenFixtures
        val rabbitHole = f.episodes().first { it.session.isRabbitHole }
        compose.setContent {
            LagrangeTheme {
                SessionDetailSheet(episode = rabbitHole, packages = f.packages, detail = null, onDismiss = {})
            }
        }
        compose.onNodeWithText("Apps in this session").assertExists()
        compose.onNodeWithText("Was it worth it?").assertDoesNotExist()
        compose.onNodeWithText("Regret").assertDoesNotExist()
        compose.onNodeWithText("Worth it").assertDoesNotExist()
    }

    @Test
    fun `Trends metric menu omits Valued vs regretted and top-bar Settings button`() {
        val f = ScreenFixtures
        compose.setContent {
            LagrangeTheme {
                TrendsContent(f.trends(), PaddingValues(), TrendsActions())
            }
        }
        compose.onNodeWithContentDescription("Settings").assertDoesNotExist()
        compose.onNodeWithText("Distracting time").performClick()
        compose.onNodeWithText("Rabbit holes").assertExists()
        compose.onNodeWithText("Valued vs regretted").assertDoesNotExist()
    }
}
