package com.celestiallemon.lagrange.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.PhonelinkLock
import androidx.compose.material.icons.outlined.SelfImprovement
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.ui.CatalogViewModel
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.components.LeadingIcon
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.OrbitMark
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.friction.AppCategoryRow
import com.celestiallemon.lagrange.ui.system.PermissionList
import com.celestiallemon.lagrange.ui.theme.Motion
import com.celestiallemon.lagrange.ui.theme.ProvideScreenArrival
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.Transitions
import com.celestiallemon.lagrange.ui.theme.rise

private const val STEPS = 3

/**
 * First run, one idea per step: what Lagrange is, letting it record, then sorting your apps. Suggestions are
 * pre-selected but nothing counts as your choice until you confirm on the last step.
 */
@Composable
fun OnboardingScreen(main: MainViewModel, catalog: CatalogViewModel = viewModel()) {
    val health by main.health.collectAsStateWithLifecycle()
    val apps by catalog.apps.collectAsStateWithLifecycle()
    val synced by catalog.synced.collectAsStateWithLifecycle()
    var step by rememberSaveable { mutableIntStateOf(0) }
    BackHandler(enabled = step > 0) { step-- }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        contentWindowInsets = WindowInsets(0.dp),
        topBar = { StepTrack(step, Modifier.statusBarsPadding().padding(horizontal = Spacing.gutter, vertical = Spacing.l)) },
        bottomBar = {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .padding(horizontal = Spacing.gutter, vertical = Spacing.l),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
                Spacer(Modifier.weight(1f))
                when (step) {
                    0 -> Button(onClick = { step = 1 }, contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.m)) { Text("Get started") }
                    1 -> Button(onClick = { step = 2 }, contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.m)) {
                        Text(if (health.coreReady) "Next" else "Continue anyway")
                    }
                    else -> Button(
                        onClick = { catalog.confirmAndFinishOnboarding() },
                        enabled = apps != null && synced,
                        contentPadding = PaddingValues(horizontal = Spacing.xl, vertical = Spacing.m),
                    ) { Text("Start recording") }
                }
            }
        },
    ) { padding ->
        AnimatedContent(step, transitionSpec = { Transitions.sharedAxisX(forward = targetState > initialState) }, label = "onboarding") { s ->
            ProvideScreenArrival {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Spacing.gutter, end = Spacing.gutter, top = padding.calculateTopPadding(), bottom = padding.calculateBottomPadding() + Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.s),
                ) {
                    when (s) {
                        0 -> item("welcome") { Welcome() }
                        1 -> {
                            item("perm-title") {
                                StepTitle("Let Lagrange record", "Two permissions, both read-only. Notification titles, text and senders are never stored.", Modifier.rise(0))
                            }
                            item("perms") { Box(Modifier.rise(1)) { PermissionList(health, compact = true, onChanged = main::refreshHealth) } }
                        }
                        else -> {
                            item("apps-title") {
                                StepTitle(
                                    "Which apps pull you in?",
                                    "Mark them Distracting. Messaging usually fits Neutral; maps, banking and 2FA fit Utility. You can change any of this later.",
                                    Modifier.rise(0),
                                )
                            }
                            val list = apps
                            if (list == null || !synced) {
                                item("loading") { Box(Modifier.fillMaxWidth().padding(Spacing.xxl), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
                            } else {
                                itemsIndexed(list, key = { _, app -> app.packageName }) { i, app ->
                                    AppCategoryRow(app, modifier = Modifier.rise(1 + i)) { catalog.setCategory(app.packageName, it) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Three segments that fill as you go: where you are and how much is left, without numbers. */
@Composable
private fun StepTrack(step: Int, modifier: Modifier) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        repeat(STEPS) { i ->
            val color by animateColorAsState(
                if (i <= step) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest,
                Motion.effects(),
                label = "step",
            )
            val h by animateDpAsState(if (i == step) 6.dp else 4.dp, Motion.spatialFast(), label = "step-h")
            Box(Modifier.weight(1f).height(h).clip(CircleShape).background(color))
        }
    }
}

@Composable
private fun StepTitle(title: String, body: String, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = Spacing.l, bottom = Spacing.l), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(body, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Welcome() {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.l)) {
        Box(Modifier.fillMaxWidth().padding(top = Spacing.xxl, bottom = Spacing.l).rise(0), contentAlignment = Alignment.Center) {
            OrbitMark(size = 160.dp, animate = true)
        }
        Column(Modifier.rise(1), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
            Text("Meet Lagrange", style = MaterialTheme.typography.displaySmall)
            Text(
                "An honest record of how you use your phone, and gentle help to use it the way you mean to.",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        RowGroup(Modifier.padding(top = Spacing.s).rise(2)) {
            ListRow("Stays on this phone", supporting = "No internet permission at all. Nothing leaves unless you save a file.", leading = { LeadingIcon(Icons.Outlined.PhonelinkLock, Tone.Brand) })
            ListRow("Honest numbers", supporting = "Gaps in recording are shown, never counted as a quiet day.", leading = { LeadingIcon(Icons.Outlined.Insights, Tone.Brand) })
            ListRow("Gentle, not locked", supporting = "Short pauses you can always pass, measured by what actually helps.", leading = { LeadingIcon(Icons.Outlined.SelfImprovement, Tone.Brand) })
        }
        Spacer(Modifier.width(1.dp))
    }
}
