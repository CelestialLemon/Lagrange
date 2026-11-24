package com.celestiallemon.lagrange.ui.friction

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.CatalogApp
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.ui.components.AppAvatar
import com.celestiallemon.lagrange.ui.components.Dot
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Spacing

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
        AppCategory.DISTRACTING -> "Counts toward your distracting budget; can have a launch pause."
        AppCategory.NEUTRAL -> "Tracked normally, e.g. messaging, browser, podcasts."
        AppCategory.UTILITY -> "Maps, banking, 2FA, camera. Never paused."
        AppCategory.EXCLUDED -> "Left out of active time and baselines (still in raw logs)."
        AppCategory.SELF_APP -> "This app, tracked separately so checking stats doesn't skew them."
    }

/** The fixed colour of a category. It never follows the wallpaper: categories must look the same everywhere. */
@Composable
fun categoryColor(category: AppCategory): Color = Lagrange.colors.category.let {
    when (category) {
        AppCategory.DISTRACTING -> it.distracting
        AppCategory.NEUTRAL -> it.neutral
        AppCategory.UTILITY -> it.utility
        AppCategory.EXCLUDED, AppCategory.SELF_APP -> it.excluded
    }
}

val choosableCategories = listOf(AppCategory.DISTRACTING, AppCategory.NEUTRAL, AppCategory.UTILITY, AppCategory.EXCLUDED)

/**
 * One app: its name, how much it's used, its category (a menu, so a long list stays calm) and, where it applies,
 * whether it gets a launch pause. Unreviewed suggestions are marked so they're never mistaken for choices.
 */
@Composable
fun AppCategoryRow(
    app: CatalogApp,
    modifier: Modifier = Modifier,
    showFrictionControls: Boolean = false,
    onToggleFriction: ((Boolean) -> Unit)? = null,
    onCustomizeFriction: (() -> Unit)? = null,
    onChange: (AppCategory) -> Unit,
) {
    val color = categoryColor(app.category)
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(start = Spacing.l, end = Spacing.s, top = Spacing.m, bottom = Spacing.m),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppAvatar(app.displayName, color)
            Spacer(Modifier.width(Spacing.l))
            Column(Modifier.weight(1f)) {
                Text(app.displayName, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    buildString {
                        if (!app.confirmed) append("Suggested · ")
                        append(if (app.recentOpens > 0) "${app.recentOpens} opens in 14 days" else "Not opened lately")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (!app.confirmed) Lagrange.colors.brand.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(Spacing.s))
            CategoryMenu(app.category, onChange)
        }
        val canToggleFriction = showFrictionControls &&
            (app.category == AppCategory.DISTRACTING || app.category == AppCategory.NEUTRAL) &&
            onToggleFriction != null
        if (canToggleFriction) {
            HorizontalDivider(Modifier.padding(start = 56.dp, top = Spacing.m, bottom = Spacing.xs, end = Spacing.s), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))
            Row(Modifier.padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Launch pause", style = MaterialTheme.typography.labelLarge)
                    val summary = buildList {
                        app.frictionOverride?.fixedPauseSec?.let { add("${it}s fixed") }
                        when (app.frictionOverride?.requireTimerPrompt) {
                            true -> add("asks for a timer")
                            false -> add("no timer prompt")
                            null -> Unit
                        }
                    }.joinToString(" · ")
                    if (summary.isNotEmpty()) Text(summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onCustomizeFriction != null && app.isFrictionEligible) {
                    IconButton(onClick = onCustomizeFriction) { Icon(Icons.Outlined.Tune, contentDescription = "Customize ${app.displayName}'s pause") }
                }
                Switch(checked = app.isFrictionEligible, onCheckedChange = onToggleFriction, modifier = Modifier.padding(end = Spacing.s))
            }
        }
    }
}

@Composable
private fun CategoryMenu(category: AppCategory, onChange: (AppCategory) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val color = categoryColor(category)
    Box {
        AssistChip(
            onClick = { if (category != AppCategory.SELF_APP) open = true },
            label = { Text(category.label) },
            leadingIcon = { Dot(color, size = 8.dp) },
            trailingIcon = if (category != AppCategory.SELF_APP) ({ Icon(Icons.Outlined.ArrowDropDown, "Change category", Modifier.size(AssistChipDefaults.IconSize)) }) else null,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choosableCategories.forEach { c ->
                DropdownMenuItem(
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = Modifier.padding(vertical = Spacing.xs)) {
                            Text(c.label)
                            Text(c.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    leadingIcon = { Dot(categoryColor(c), size = 10.dp) },
                    trailingIcon = if (c == category) ({ Icon(Icons.Outlined.Check, contentDescription = "Current") }) else null,
                    // Always reported, even when unchanged: choosing a category is also how a suggestion is confirmed.
                    onClick = {
                        open = false
                        onChange(c)
                    },
                )
            }
        }
    }
}
