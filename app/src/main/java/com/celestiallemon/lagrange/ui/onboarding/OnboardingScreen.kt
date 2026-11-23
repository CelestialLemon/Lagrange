package com.celestiallemon.lagrange.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.ui.CatalogViewModel
import com.celestiallemon.lagrange.ui.MainViewModel
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.friction.AppCategoryRow
import com.celestiallemon.lagrange.ui.system.PermissionList
import com.celestiallemon.lagrange.ui.theme.Space

/**
 * First run: grant the two collector permissions, then a quick required review of app categories. Suggestions are
 * pre-selected but nothing counts as the user's choice until they confirm.
 */
@Composable
fun OnboardingScreen(main: MainViewModel, catalog: CatalogViewModel = viewModel()) {
    val health by main.health.collectAsStateWithLifecycle()
    val apps by catalog.apps.collectAsStateWithLifecycle()
    val synced by catalog.synced.collectAsStateWithLifecycle()
    Scaffold(
        containerColor = MaterialTheme.colorScheme.surface,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = Space.gutter, vertical = Space.m),
                ) {
                    Button(
                        onClick = { catalog.confirmAndFinishOnboarding() },
                        enabled = apps != null && synced,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = Space.m),
                    ) { Text("Confirm categories & start collecting") }
                }
            }
        },
    ) { padding ->
        LagrangeScreen(
            eyebrow = "Welcome",
            title = "Meet Lagrange",
            subtitle = "A private, on-device ledger of how you use your phone, and gentle help to use it the way you mean to. Nothing ever leaves this device.",
            contentPadding = padding,
            modifier = Modifier.fillMaxSize(),
        ) {
            item("perms") {
                SectionCard(title = "Let Lagrange collect", eyebrow = "Step 1", icon = Icons.Outlined.Key) {
                    PermissionList(health, compact = true, onChanged = main::refreshHealth)
                }
            }
            item("review") {
                SectionCard(title = "Review your apps", eyebrow = "Step 2", icon = Icons.Outlined.Category) {
                    Text(
                        "Thirty seconds: mark the apps that pull you in as Distracting. Messaging usually belongs in Neutral; maps, banking and 2FA in Utility. You can change any of this later.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            val list = apps
            if (list == null || !synced) {
                item("loading") { CircularProgressIndicator() }
            } else {
                items(list, key = { it.packageName }) { app ->
                    AppCategoryRow(app) { catalog.setCategory(app.packageName, it) }
                }
            }
            item("end") { Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {} }
        }
    }
}
