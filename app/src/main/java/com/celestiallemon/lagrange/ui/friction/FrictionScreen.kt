package com.celestiallemon.lagrange.ui.friction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Category
import androidx.compose.material.icons.outlined.HourglassTop
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Spa
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.ui.CatalogViewModel
import com.celestiallemon.lagrange.ui.components.ComingSoonCard
import com.celestiallemon.lagrange.ui.components.LagrangeScreen
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.theme.Space

@Composable
fun FrictionScreen(padding: PaddingValues, catalog: CatalogViewModel = viewModel()) {
    val apps by catalog.apps.collectAsStateWithLifecycle()
    LagrangeScreen(
        eyebrow = "Friction",
        title = "Gentle by design",
        subtitle = "Categories shape every number Lagrange shows. Friction itself arrives later, on just the apps you choose.",
        contentPadding = padding,
    ) {
        item("categories") {
            SectionCard(title = "App categories", eyebrow = "Applies to all history", icon = Icons.Outlined.Category) {
                Text(
                    "Recategorizing an app updates past trends too, because history is always recomputed from the raw ledger.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val list = apps.orEmpty()
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    listOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY, AppCategory.EXCLUDED).forEach { c ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(c.label, style = MaterialTheme.typography.titleSmall)
                                Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            StatusPill("${list.count { it.category == c }}", categoryColor(c))
                        }
                    }
                }
            }
        }
        val list = apps
        if (list == null) {
            item("loading") { CircularProgressIndicator() }
        } else {
            items(list, key = { it.packageName }) { app ->
                AppCategoryRow(app) { catalog.setCategory(app.packageName, it) }
            }
        }
        item("budget") {
            ComingSoonCard("Phase 3", "Budgets & goals", "A daily distracting-time budget and optional per-app goals, drawn as reference lines on every chart.", Icons.Outlined.HourglassTop)
        }
        item("pause") {
            ComingSoonCard("Phase 3", "Mindful launch pause", "An escalating pause card with intention chips, a healthy redirect and a calm \"Go Home\". Blocked attempts never count against you.", Icons.Outlined.Spa)
        }
        item("schedules") {
            ComingSoonCard("Phase 3", "Contextual schedules", "Work hours or wind-down windows that boost escalation, encourage timers or tighten nudges.", Icons.Outlined.Schedule)
        }
    }
}
