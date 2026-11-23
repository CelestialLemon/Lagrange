package com.celestiallemon.lagrange.ui.friction

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.celestiallemon.lagrange.data.CatalogApp
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.theme.Space

val AppCategory.label: String
    get() = when (this) {
        AppCategory.DISTRACTING -> "Distracting"
        AppCategory.NEUTRAL -> "Neutral"
        AppCategory.UTILITY -> "Utility"
        AppCategory.EXCLUDED -> "Excluded"
        AppCategory.SELF_APP -> "Lagrange"
    }

val AppCategory.description: String
    get() = when (this) {
        AppCategory.DISTRACTING -> "Counts toward your distracting budget; eligible for gentle friction."
        AppCategory.NEUTRAL -> "Tracked normally, e.g. messaging, browser, podcasts."
        AppCategory.UTILITY -> "Maps, banking, 2FA, camera. Never friction-gated."
        AppCategory.EXCLUDED -> "Left out of active phone time and baselines (still in raw logs)."
        AppCategory.SELF_APP -> "This app, tracked separately so checking stats doesn't skew them."
    }

@Composable
fun categoryColor(category: AppCategory): Color = when (category) {
    AppCategory.DISTRACTING -> MaterialTheme.colorScheme.tertiary
    AppCategory.NEUTRAL -> MaterialTheme.colorScheme.primary
    AppCategory.UTILITY -> MaterialTheme.colorScheme.secondary
    AppCategory.EXCLUDED -> MaterialTheme.colorScheme.outline
    AppCategory.SELF_APP -> MaterialTheme.colorScheme.outline
}

private val choosable = listOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY, AppCategory.EXCLUDED)

/** One app with its category choices laid out as chips. */
@Composable
fun AppCategoryRow(app: CatalogApp, onChange: (AppCategory) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.m)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(app.displayName, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        if (app.recentOpens > 0) "${app.recentOpens} opens in the last 14 days" else app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!app.confirmed) StatusPill("Suggested", MaterialTheme.colorScheme.outline)
            }
            Spacer(Modifier.height(Space.s))
            if (app.category == AppCategory.SELF_APP) {
                StatusPill(AppCategory.SELF_APP.label, categoryColor(AppCategory.SELF_APP))
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                    choosable.forEach { c ->
                        val color = categoryColor(c)
                        FilterChip(
                            selected = app.category == c,
                            onClick = { onChange(c) },
                            label = { Text(c.label) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = color.copy(alpha = 0.18f),
                                selectedLabelColor = color,
                            ),
                        )
                    }
                }
            }
        }
    }
}
