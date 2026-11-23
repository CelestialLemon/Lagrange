package com.celestiallemon.lagrange.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AutoGraph
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.ui.friction.FrictionScreen
import com.celestiallemon.lagrange.ui.lab.LabScreen
import com.celestiallemon.lagrange.ui.onboarding.OnboardingScreen
import com.celestiallemon.lagrange.ui.system.SystemScreen
import com.celestiallemon.lagrange.ui.theme.LagrangeTheme
import com.celestiallemon.lagrange.ui.today.TodayScreen
import com.celestiallemon.lagrange.ui.trends.TrendsScreen

enum class Tab(val label: String, val icon: ImageVector) {
    Today("Today", Icons.Outlined.WbTwilight),
    Trends("Trends", Icons.Outlined.AutoGraph),
    Lab("Lab", Icons.Outlined.Science),
    Friction("Friction", Icons.Outlined.SelfImprovement),
    System("System", Icons.Outlined.Tune),
}

@Composable
fun LagrangeRoot(main: MainViewModel = viewModel()) {
    val prefs by main.prefs.collectAsStateWithLifecycle()
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
            when {
                loaded == null -> Unit
                !loaded.onboardingComplete -> OnboardingScreen(main)
                else -> LagrangeShell(main)
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

@Composable
fun LagrangeShell(main: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    // Predictive back: from any other tab, back returns to Today before leaving the app.
    BackHandler(enabled = tab != Tab.Today) { tab = Tab.Today }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = t == tab,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
                    )
                }
            }
        },
    ) { padding ->
        Crossfade(targetState = tab, label = "tab") { current ->
            when (current) {
                Tab.Today -> TodayScreen(main, padding, onOpenSystem = { tab = Tab.System })
                Tab.Trends -> TrendsScreen(padding)
                Tab.Lab -> LabScreen(padding)
                Tab.Friction -> FrictionScreen(padding)
                Tab.System -> SystemScreen(main, padding)
            }
        }
    }
}
