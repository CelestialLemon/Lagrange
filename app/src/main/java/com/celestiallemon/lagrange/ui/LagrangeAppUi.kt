package com.celestiallemon.lagrange.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Science
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.WbTwilight
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.Destination
import com.celestiallemon.lagrange.StartupGate
import com.celestiallemon.lagrange.ui.components.DrillIn
import com.celestiallemon.lagrange.ui.components.OrbitMark
import com.celestiallemon.lagrange.ui.components.SettingsAction
import com.celestiallemon.lagrange.ui.friction.FrictionScreen
import com.celestiallemon.lagrange.ui.lab.LabScreen
import com.celestiallemon.lagrange.ui.onboarding.OnboardingScreen
import com.celestiallemon.lagrange.ui.system.SettingsScreen
import com.celestiallemon.lagrange.ui.theme.LagrangeTheme
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Transitions
import com.celestiallemon.lagrange.ui.today.TodayScreen
import com.celestiallemon.lagrange.ui.trends.TrendsTab

/**
 * The four places you go. Today and Trends answer "how am I doing", the Lab answers "why", Friction is "what to do
 * about it". Settings (permissions, data, appearance) is housekeeping: it lives behind the gear, not in the bar.
 */
enum class Tab(val label: String, val icon: ImageVector, val selectedIcon: ImageVector) {
    Today("Today", Icons.Outlined.WbTwilight, Icons.Filled.WbTwilight),
    Trends("Trends", Icons.Outlined.Insights, Icons.Filled.Insights),
    Lab("Lab", Icons.Outlined.Science, Icons.Filled.Science),
    Friction("Friction", Icons.Outlined.SelfImprovement, Icons.Filled.SelfImprovement),
}

@Composable
fun LagrangeRoot(main: MainViewModel = viewModel()) {
    val prefs by main.prefs.collectAsStateWithLifecycle()
    val startup by main.startupState.collectAsStateWithLifecycle()
    // The restore notice lives at the root so it shows on every screen, onboarding included.
    val snackbar = remember { SnackbarHostState() }
    val restoreNotice by main.restoreNotice.collectAsStateWithLifecycle()
    LaunchedEffect(restoreNotice) {
        restoreNotice?.let {
            snackbar.showSnackbar(it, withDismissAction = true)
            main.dismissRestoreNotice()
        }
    }
    LagrangeTheme(dynamicColor = prefs?.useDynamicColor == true) {
        Box(Modifier.fillMaxSize()) {
            val loaded = prefs
            val failed = startup as? StartupGate.State.Failed
            val stage = when {
                failed != null -> RootStage.Failed
                loaded == null -> RootStage.Loading
                !loaded.onboardingComplete -> RootStage.Onboarding
                else -> RootStage.App
            }
            AnimatedContent(stage, transitionSpec = { Transitions.fadeThrough() }, label = "root") { s ->
                when (s) {
                    RootStage.Failed -> RecoveryErrorScreen(failed?.message.orEmpty(), onRestart = main::restartApp)
                    RootStage.Loading -> Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {}
                    RootStage.Onboarding -> OnboardingScreen(main)
                    RootStage.App -> LagrangeShell(main)
                }
            }
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding(),
            )
        }
    }
}

private enum class RootStage { Failed, Loading, Onboarding, App }

@Composable
fun LagrangeShell(main: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    // The app open in the Trends deep dive, if any. It lives here so re-selecting Trends can return to the list.
    var deepDive by rememberSaveable { mutableStateOf<Long?>(null) }
    // One saveable scope per tab: switching tabs keeps each tab's scroll position and open state.
    val tabStates = rememberSaveableStateHolder()
    val trendsSaveable = rememberSaveableStateHolder()
    fun setDeepDive(next: Long?) {
        val previous = deepDive
        if (previous != null && previous != next) trendsSaveable.removeState("app-$previous")
        deepDive = next
    }

    val health by main.health.collectAsStateWithLifecycle()
    val stats by main.stats.collectAsStateWithLifecycle()
    val attention = !health.coreReady || stats.harvestOverdue(System.currentTimeMillis())
    val gear: @Composable RowScope.() -> Unit = { SettingsAction(attention) { settingsOpen = true } }

    // Predictive back: from any other tab, back returns to Today before leaving the app. Settings and the deep dive
    // register their own (later, so higher-priority) handlers.
    BackHandler(enabled = tab != Tab.Today && !settingsOpen) { tab = Tab.Today }
    val destination by main.destination.collectAsStateWithLifecycle()
    LaunchedEffect(destination) {
        val target = destination ?: return@LaunchedEffect
        settingsOpen = target == Destination.SYSTEM
        when (target) {
            Destination.TODAY -> tab = Tab.Today
            Destination.TRENDS -> {
                tab = Tab.Trends
                setDeepDive(null)
            }
            Destination.LAB -> tab = Tab.Lab
            Destination.FRICTION -> tab = Tab.Friction
            Destination.SYSTEM -> Unit
        }
        main.consumeDestination()
    }

    DrillIn(
        top = if (settingsOpen) Unit else null,
        onBack = { settingsOpen = false },
        base = {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.surface,
                contentWindowInsets = WindowInsets(0.dp),
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                        Tab.entries.forEach { t ->
                            val selected = t == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = {
                                    if (t == Tab.Trends && tab == Tab.Trends) setDeepDive(null)
                                    tab = t
                                },
                                icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = null) },
                                label = { Text(t.label) },
                            )
                        }
                    }
                },
            ) { padding ->
                AnimatedContent(tab, transitionSpec = { Transitions.fadeThrough() }, label = "tab") { current ->
                    tabStates.SaveableStateProvider(current.name) {
                        when (current) {
                            Tab.Today -> TodayScreen(main, padding, actions = gear, onOpenSettings = { settingsOpen = true })
                            Tab.Trends -> TrendsTab(padding, deepDive, onDeepDive = ::setDeepDive, saveable = trendsSaveable, actions = gear)
                            Tab.Lab -> LabScreen(padding, actions = gear)
                            Tab.Friction -> FrictionScreen(main, padding, actions = gear, onOpenSettings = { settingsOpen = true })
                        }
                    }
                }
            }
        },
        detail = { SettingsScreen(main, onBack = { settingsOpen = false }) },
    )
}

/**
 * Startup recovery (reset wipe, restore rollback, database open) failed. Nothing opens or records data in this
 * process; the recovery is retried on the next start.
 */
@Composable
fun RecoveryErrorScreen(message: String, onRestart: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier
                .fillMaxSize()
                .safeDrawingPadding()
                .padding(Spacing.xl),
            verticalArrangement = Arrangement.spacedBy(Spacing.l, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            OrbitMark(size = 72.dp)
            Text("Lagrange couldn't recover its data", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Text(message, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
            Text(
                "Recording and backups are paused so nothing is written over a half-recovered database. Your files are " +
                    "kept on the device. Restarting retries the recovery.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onRestart, modifier = Modifier.fillMaxWidth()) { Text("Restart Lagrange") }
        }
    }
}
