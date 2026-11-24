package com.celestiallemon.lagrange.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Motion
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Transitions
import com.celestiallemon.lagrange.ui.theme.pressScale

/**
 * Pick one of a few short options. Up to four short labels become a full-width segmented control (the choice is
 * part of the layout); anything longer scrolls as filter chips.
 */
@Composable
fun <T> Choice(
    options: List<T>,
    selected: T?,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    modifier: Modifier = Modifier,
    forceChips: Boolean = false,
) {
    val segmented = !forceChips && options.size in 2..4 && options.all { label(it).length <= 10 }
    if (segmented) {
        SingleChoiceSegmentedButtonRow(modifier.fillMaxWidth()) {
            options.forEachIndexed { i, o ->
                SegmentedButton(
                    selected = o == selected,
                    onClick = { onSelect(o) },
                    shape = SegmentedButtonDefaults.itemShape(i, options.size),
                ) { Text(label(o), maxLines = 1) }
            }
        }
    } else {
        Row(
            modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            options.forEach { o ->
                FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(label(o)) })
            }
        }
    }
}

/** A big value with − and + on either side: budgets, goals, rollover hour. */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    minusEnabled: Boolean = true,
    plusEnabled: Boolean = true,
    minusDescription: String = "Less",
    plusDescription: String = "More",
    increasing: Boolean = true,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        FilledTonalIconButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Outlined.Remove, minusDescription)
        }
        RollingText(value, NumberStyles.hero.copy(fontSize = NumberStyles.stat.fontSize * 1.6f), increasing = increasing)
        FilledTonalIconButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.size(56.dp)) {
            Icon(Icons.Outlined.Add, plusDescription)
        }
    }
}

val worthWords = listOf("Regret it", "Mostly not", "Meh", "Mostly yes", "Worth it")

/** The colour of a 1..5 rating, from regret to worth it, through neutral. */
@Composable
fun worthColor(rating: Int): Color {
    val c = Lagrange.colors
    val t = ((rating - 1) / 4f).coerceIn(0f, 1f)
    return if (t < 0.5f) lerp(c.critical.accent, c.neutral.accent, t * 2f) else lerp(c.neutral.accent, c.good.accent, (t - 0.5f) * 2f)
}

/**
 * "Was that worth it?" as a bipolar scale rather than stars: it is a judgement of value, not a review. Five beads
 * grow from small (regret) to large (worth it); the chosen bead fills and pops, its word appears below.
 * Tapping the chosen bead again clears the answer.
 */
@Composable
fun WorthScale(current: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier, compact: Boolean = false) {
    val target: Dp = if (compact) 40.dp else 48.dp
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            (1..5).forEach { n ->
                val selected = current == n
                val color = worthColor(n)
                val fill by animateColorAsState(if (selected) color else Color.Transparent, Motion.effects(), label = "worth-fill")
                val scale by animateFloatAsState(if (selected) 1.12f else 1f, Motion.spatialFast(), label = "worth-scale")
                val interactions = remember { MutableInteractionSource() }
                val bead = (if (compact) 14 else 16) + n * (if (compact) 3 else 4)
                Box(
                    Modifier
                        .size(target)
                        .pressScale(interactions, 0.9f)
                        .selectable(
                            selected = selected,
                            interactionSource = interactions,
                            indication = ripple(bounded = false, radius = target / 2),
                            role = Role.RadioButton,
                            onClick = { onRate(if (selected) null else n) },
                        )
                        .semantics { contentDescription = "${worthWords[n - 1]}, $n of 5" },
                    contentAlignment = Alignment.Center,
                ) {
                    Surface(
                        modifier = Modifier
                            .size(bead.dp)
                            .graphicsLayer {
                                scaleX = scale
                                scaleY = scale
                            },
                        shape = CircleShape,
                        color = fill,
                        border = BorderStroke(2.dp, color.copy(alpha = if (selected) 1f else 0.55f)),
                    ) {}
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Spacing.xs)) {
            Text("Regret", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            AnimatedContent(current, transitionSpec = { Transitions.roll(true) }, modifier = Modifier.weight(1f), label = "worth-word") { r ->
                Text(
                    r?.let { worthWords[it - 1] } ?: "",
                    style = MaterialTheme.typography.labelLarge,
                    color = r?.let { worthColor(it) } ?: Color.Unspecified,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Text("Worth it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
