package com.celestiallemon.lagrange.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Sizes
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone

/**
 * The workhorse row: leading visual, a headline, an optional supporting line, and a trailing element.
 * Rows sit inside a [RowGroup] (tonal tiles) or directly on the screen ([tonal] = false).
 */
@Composable
fun ListRow(
    headline: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    tonal: Boolean = true,
    supportingColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    supportingLines: Int = 2,
) {
    val interactions = remember { MutableInteractionSource() }
    Row(
        modifier
            .fillMaxWidth()
            .then(if (tonal) Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow) else Modifier)
            .then(if (onClick != null) Modifier.clickable(interactions, ripple(), onClick = onClick) else Modifier)
            .heightIn(min = Sizes.rowMin)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.l))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = supportingColor, maxLines = supportingLines, overflow = TextOverflow.Ellipsis)
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(Spacing.m))
            trailing()
        }
    }
}

/** A setting with its current value on the right; tapping opens its editor. */
@Composable
fun ValueRow(
    headline: String,
    value: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    valueColor: Color = MaterialTheme.colorScheme.primary,
) {
    ListRow(
        headline = headline,
        supporting = supporting,
        leading = leading,
        onClick = onClick,
        modifier = modifier,
        trailing = {
            Text(value, style = NumberStyles.inline, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 140.dp))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        },
    )
}

/** An on/off setting. The whole row toggles, so the target is large. */
@Composable
fun SwitchRow(
    headline: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    tonal: Boolean = true,
) {
    Row(
        modifier
            .fillMaxWidth()
            .then(if (tonal) Modifier.background(MaterialTheme.colorScheme.surfaceContainerLow) else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
            .heightIn(min = Sizes.rowMin)
            .padding(horizontal = Spacing.l, vertical = Spacing.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.l))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
            Text(headline, style = MaterialTheme.typography.bodyLarge)
            if (supporting != null) Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Spacing.m))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

/** A 40dp rounded-square icon tile at the start of a row, tinted by [tone]. */
@Composable
fun LeadingIcon(icon: ImageVector, tone: Tone = Tone.Neutral, modifier: Modifier = Modifier) {
    val t = Lagrange.colors.tone(tone)
    val container = if (tone == Tone.Neutral) MaterialTheme.colorScheme.surfaceContainerHighest else t.container
    val tint = if (tone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else t.onContainer
    Box(
        modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
    }
}

/** A leading letter avatar for an app, coloured by its category. */
@Composable
fun AppAvatar(name: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(40.dp)
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}
