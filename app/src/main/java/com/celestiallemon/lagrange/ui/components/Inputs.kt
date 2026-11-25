package com.celestiallemon.lagrange.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing

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
