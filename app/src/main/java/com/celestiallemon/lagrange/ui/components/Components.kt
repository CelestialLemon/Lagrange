package com.celestiallemon.lagrange.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.theme.EyebrowStyle
import com.celestiallemon.lagrange.ui.theme.Space
import kotlin.math.cos
import kotlin.math.sin

/**
 * A screen: a soft nebula glow behind a large title, then airy cards. Content scrolls under the system bars
 * (edge-to-edge) and [contentPadding] keeps it clear of the insets.
 */
@Composable
fun LagrangeScreen(
    eyebrow: String,
    title: String,
    subtitle: String?,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    content: LazyListScope.() -> Unit,
) {
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    val glow2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.10f)
    LazyColumn(
        modifier = modifier.drawBehind {
            drawCircle(
                brush = Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width * 0.85f, 0f), radius = size.width * 0.9f),
                radius = size.width * 0.9f,
                center = Offset(size.width * 0.85f, 0f),
            )
            drawCircle(
                brush = Brush.radialGradient(listOf(glow2, Color.Transparent), center = Offset(0f, size.width * 0.35f), radius = size.width * 0.6f),
                radius = size.width * 0.6f,
                center = Offset(0f, size.width * 0.35f),
            )
        },
        contentPadding = PaddingValues(
            start = Space.gutter,
            end = Space.gutter,
            top = contentPadding.calculateTopPadding() + Space.l,
            bottom = contentPadding.calculateBottomPadding() + Space.xl,
        ),
        verticalArrangement = Arrangement.spacedBy(Space.m),
    ) {
        if (onBack != null) {
            item(key = "back") {
                FilledTonalIconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                }
            }
        }
        item(key = "header") { ScreenHeader(eyebrow, title, subtitle) }
        content()
    }
}

@Composable
fun ScreenHeader(eyebrow: String, title: String, subtitle: String?) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = Space.s)) {
        Column(Modifier.weight(1f)) {
            Text(eyebrow.uppercase(), style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(Space.xs))
            Text(title, style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.height(Space.s))
                Text(subtitle, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.width(Space.m))
        OrbitMark(size = 56.dp)
    }
}

/** The Lagrange mark: a body, its orbiting companion and the L4 point riding sixty degrees ahead. */
@Composable
fun OrbitMark(size: Dp, modifier: Modifier = Modifier) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val star = MaterialTheme.colorScheme.tertiary
    val planet = MaterialTheme.colorScheme.secondary
    val point = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension * 0.36f
        val c = center
        drawCircle(ring, radius = r, center = c, style = Stroke(width = this.size.minDimension * 0.03f))
        drawCircle(star, radius = r * 0.36f, center = c)
        drawCircle(planet, radius = r * 0.18f, center = Offset(c.x + r, c.y))
        val a = Math.toRadians(-60.0)
        drawCircle(point, radius = r * 0.12f, center = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
    }
}

/** An airy tonal card with an optional eyebrow, icon and title. */
@Composable
fun SectionCard(
    title: String?,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    icon: ImageVector? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = container,
        tonalElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = Space.l, vertical = Space.l), verticalArrangement = Arrangement.spacedBy(Space.m)) {
            if (title != null || eyebrow != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (icon != null) {
                        IconBadge(icon)
                        Spacer(Modifier.width(Space.m))
                    }
                    Column(Modifier.weight(1f)) {
                        if (eyebrow != null) {
                            Text(eyebrow.uppercase(), style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
                        }
                        if (title != null) Text(title, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
            content()
        }
    }
}

@Composable
fun IconBadge(icon: ImageVector, tint: Color = MaterialTheme.colorScheme.onSecondaryContainer, background: Color = MaterialTheme.colorScheme.secondaryContainer) {
    Box(
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(background),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

/** A calm card describing something that arrives in a later phase. */
@Composable
fun ComingSoonCard(phase: String, title: String, body: String, icon: ImageVector) {
    SectionCard(title = title, eyebrow = phase, icon = icon, container = MaterialTheme.colorScheme.surfaceContainer) {
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, caption: String? = null) {
    Surface(modifier = modifier, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(horizontal = Space.m, vertical = Space.m)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(Space.xs))
            Text(value, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1)
            if (caption != null) {
                Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun StatRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.s), content = content)
}

/** A small coloured dot plus label, e.g. "Granted". */
@Composable
fun StatusPill(text: String, color: Color) {
    Surface(shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(color),
            )
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}
