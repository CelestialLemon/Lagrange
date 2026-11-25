package com.celestiallemon.lagrange.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.pressScale
import kotlin.math.cos
import kotlin.math.sin

/**
 * A panel: the one container style for grouped content. Flat (no shadow), tonal, generously padded.
 * Use it for things that are a distinct object or need a boundary (a hero, a check-in, a chart). Plain sections of
 * a screen don't need one: a [SectionHeader] and space separate them.
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    shape: Shape = MaterialTheme.shapes.large,
    padding: PaddingValues = PaddingValues(Spacing.panel),
    spacing: Dp = Spacing.m,
    content: @Composable ColumnScope.() -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.pressScale(interactions) else Modifier),
        shape = shape,
        color = color,
        contentColor = contentColor,
    ) {
        Column(
            Modifier
                .then(
                    if (onClick != null) {
                        Modifier.clickable(interactionSource = interactions, indication = androidx.compose.material3.ripple(), onClickLabel = onClickLabel, onClick = onClick)
                    } else {
                        Modifier
                    },
                )
                .padding(padding),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
    }
}

/** Explanation behind an ⓘ: how a number is measured and what it leaves out. Honest, but out of the way. */
@Immutable
data class Info(val title: String, val body: String)

/**
 * The title of a section of a screen. Sections are separated by space, not boxes. An optional [info] puts the
 * measurement caveats one tap away; an optional [action] is a single text button ("See all").
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    info: Info? = null,
    action: Pair<String, () -> Unit>? = null,
    topPadding: Dp = Spacing.xl,
) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(top = topPadding, bottom = Spacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
                if (info != null) InfoButton(info)
            }
            if (supporting != null) {
                Text(supporting, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null) TextButton(onClick = action.second) { Text(action.first) }
    }
}

/** A small ⓘ that opens an [InfoSheet]. */
@Composable
fun InfoButton(info: Info, modifier: Modifier = Modifier) {
    var open by rememberSaveable { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = modifier.size(36.dp)) {
        Icon(
            Icons.Outlined.Info,
            contentDescription = "About ${info.title}",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    if (open) InfoSheet(info, onDismiss = { open = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(info: Info, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.xl)
                .padding(bottom = Spacing.xxl)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(Spacing.m),
        ) {
            Text(info.title, style = MaterialTheme.typography.headlineSmall)
            info.body.split("\n\n").forEach { para ->
                Text(para, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * An inline message that needs attention: setup is incomplete, a day has a coverage gap, something failed.
 * Tone carries severity; there is at most one banner at the top of a screen.
 */
@Composable
fun Banner(
    title: String,
    tone: Tone,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    body: String? = null,
    action: Pair<String, () -> Unit>? = null,
    onDismiss: (() -> Unit)? = null,
) {
    val t = Lagrange.colors.tone(tone)
    Surface(modifier = modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = t.container, contentColor = t.onContainer) {
        Row(Modifier.padding(start = Spacing.l, end = Spacing.xs, top = Spacing.m, bottom = if (action != null) Spacing.xs else Spacing.m)) {
            Icon(icon, contentDescription = null, modifier = Modifier.padding(top = 2.dp).size(20.dp))
            Spacer(Modifier.width(Spacing.m))
            Column(Modifier.weight(1f).padding(end = Spacing.s), verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (body != null) Text(body, style = MaterialTheme.typography.bodyMedium)
                if (action != null) {
                    TextButton(
                        onClick = action.second,
                        contentPadding = PaddingValues(horizontal = 0.dp),
                        colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = t.onContainer),
                    ) { Text(action.first) }
                }
            }
            if (onDismiss != null) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** Nothing to show yet. The orbit mark turns slowly to say "working on it", not "broken". */
@Composable
fun EmptyState(title: String, body: String, modifier: Modifier = Modifier, action: Pair<String, () -> Unit>? = null) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Spacing.m),
    ) {
        OrbitMark(size = 72.dp, animate = true)
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) TextButton(onClick = action.second) { Text(action.first) }
    }
}

/**
 * A stack of rows that belong together, in the Material "segmented list" style: each row is its own tonal tile,
 * the group has large outer corners and the tiles are separated by a hairline of background.
 */
@Composable
fun RowGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/**
 * The Lagrange mark: a star, a planet on its orbit, and the L4 point riding sixty degrees ahead of it.
 * When [animate] is on (and motion isn't reduced) the planet and its L4 point orbit slowly together, which is the
 * whole idea of a Lagrange point: a place that stays put relative to what moves.
 */
@Composable
fun OrbitMark(size: Dp, modifier: Modifier = Modifier, animate: Boolean = false) {
    val ring = MaterialTheme.colorScheme.outlineVariant
    val star = MaterialTheme.colorScheme.tertiary
    val planet = MaterialTheme.colorScheme.primary
    val point = Lagrange.colors.category.distracting
    val spin = if (animate && !Lagrange.reducedMotion) {
        val t = rememberInfiniteTransition(label = "orbit")
        t.animateFloat(0f, 360f, infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "orbit-angle").value
    } else {
        20f
    }
    Canvas(modifier.size(size)) {
        val r = this.size.minDimension * 0.36f
        val c = center
        drawCircle(ring, radius = r, center = c, style = Stroke(width = this.size.minDimension * 0.025f))
        drawCircle(star, radius = r * 0.34f, center = c)
        val a = Math.toRadians(spin.toDouble())
        drawCircle(planet, radius = r * 0.17f, center = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()))
        val l4 = a - Math.PI / 3
        drawCircle(point, radius = r * 0.10f, center = Offset(c.x + r * cos(l4).toFloat(), c.y + r * sin(l4).toFloat()))
    }
}

/** A filled dot, for legends and category markers. */
@Composable
fun Dot(color: Color, modifier: Modifier = Modifier, size: Dp = 10.dp) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}
