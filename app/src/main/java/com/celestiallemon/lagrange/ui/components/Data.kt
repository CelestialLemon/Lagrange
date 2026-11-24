package com.celestiallemon.lagrange.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Motion
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Sizes
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.Transitions
import kotlin.math.cos
import kotlin.math.sin

// -- Numbers ----------------------------------------------------------------------------------------------------------

/**
 * Text that changes like an odometer: only the characters that changed roll, upwards when the value grew.
 * Use it for any number that can update while visible. Screen readers get the whole string.
 */
@Composable
fun RollingText(
    text: String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    increasing: Boolean = true,
) {
    if (Lagrange.reducedMotion) {
        Text(text, style = style, color = color, modifier = modifier, maxLines = 1)
        return
    }
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { i, c ->
            // Keyed from the end so "9m" → "10m" keeps the "m" still and rolls the digits.
            key(text.length - i) {
                AnimatedContent(targetState = c, transitionSpec = { Transitions.roll(increasing) }, label = "roll") { ch ->
                    Text(ch.toString(), style = style, color = color, maxLines = 1)
                }
            }
        }
    }
}

/**
 * A number that counts up from zero when it first appears and glides to new values. For heroes only: one per
 * screen, so the count-up reads as an answer arriving rather than noise.
 */
@Composable
fun CountUpText(
    value: Long,
    format: (Long) -> String,
    style: TextStyle,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
) {
    val reduced = Lagrange.reducedMotion
    val anim = remember { Animatable(if (reduced) value.toFloat() else 0f) }
    LaunchedEffect(value) {
        if (reduced) anim.snapTo(value.toFloat()) else anim.animateTo(value.toFloat(), tween(900, easing = Motion.EmphasizedDecelerate))
    }
    val shown = format(anim.value.toLong())
    Text(shown, style = style, color = color, maxLines = 1, modifier = modifier.clearAndSetSemantics { contentDescription = format(value) })
}

/** A label above a number, with an optional caption below. [captionTone] colours the caption (e.g. "+12m · worse"). */
@Composable
fun Stat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    captionTone: Tone = Tone.Neutral,
    valueStyle: TextStyle = NumberStyles.stat,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        RollingText(value, valueStyle, color = MaterialTheme.colorScheme.onSurface)
        if (caption != null) {
            Text(
                caption,
                style = MaterialTheme.typography.bodySmall,
                color = if (captionTone == Tone.Neutral) MaterialTheme.colorScheme.onSurfaceVariant else Lagrange.colors.tone(captionTone).accent,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Two to four [Stat]s in a row, divided by hairlines rather than boxed. */
@Composable
fun StatStrip(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.l), content = content)
}

@Composable
fun StripDivider() {
    VerticalDivider(Modifier.height(40.dp).padding(top = Spacing.xs), color = MaterialTheme.colorScheme.outlineVariant)
}

// -- Tags -------------------------------------------------------------------------------------------------------------

/**
 * A small label with a tone: a session's trigger, a day's status, a rating. Replaces every ad-hoc pill and badge.
 * Tags are not buttons; anything tappable is a chip.
 */
@Composable
fun Tag(text: String, modifier: Modifier = Modifier, tone: Tone = Tone.Neutral, icon: ImageVector? = null, dot: Color? = null) {
    val t = Lagrange.colors.tone(tone)
    Surface(modifier = modifier, shape = CircleShape, color = t.container, contentColor = t.onContainer) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(14.dp))
                Spacer(Modifier.width(Spacing.xs))
            } else if (dot != null) {
                Dot(dot, size = 8.dp)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

// -- Meters -----------------------------------------------------------------------------------------------------------

/** Fills from zero when first shown, then follows [target] with a spring. */
@Composable
private fun animatedFraction(target: Float): Float {
    val reduced = Lagrange.reducedMotion
    val anim = remember { Animatable(if (reduced) target else 0f) }
    LaunchedEffect(target) { if (reduced) anim.snapTo(target) else anim.animateTo(target, Motion.spatialSlow()) }
    return anim.value
}

/**
 * A horizontal proportion bar. An optional [marker] (0..1) draws a short tick, e.g. "usual" or a goal.
 */
@Composable
fun Meter(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = Sizes.meter,
    track: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    marker: Float? = null,
    markerColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val f = animatedFraction(fraction.coerceIn(0f, 1f))
    Canvas(modifier.fillMaxWidth().height(height)) {
        val r = size.height / 2
        drawRoundRect(track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        if (f > 0f) {
            val w = (size.width * f).coerceAtLeast(size.height)
            drawRoundRect(color, size = Size(w, size.height), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
        }
        if (marker != null) {
            val x = size.width * marker.coerceIn(0f, 1f)
            drawLine(markerColor, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        }
    }
}

@Immutable
data class Segment(val weight: Float, val color: Color)

/** Parts of a whole side by side (time by category). Revealed left to right on first appearance. */
@Composable
fun SplitMeter(segments: List<Segment>, modifier: Modifier = Modifier, height: Dp = 14.dp) {
    val reveal = animatedFraction(1f)
    val total = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .drawWithContent { clipRect(right = size.width * reveal) { this@drawWithContent.drawContent() } },
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.filter { it.weight > 0f }.forEach { s ->
            Box(
                Modifier
                    .weight((s.weight / total).coerceAtLeast(0.008f))
                    .fillMaxHeight()
                    .background(s.color),
            )
        }
    }
}

/** A label and value over a thin meter: the standard way to rank things (apps, triggers, hours). */
@Composable
fun BarRow(
    label: String,
    value: String,
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    caption: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth().padding(vertical = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        if (leading != null) {
            leading()
            Spacer(Modifier.width(Spacing.m))
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Spacing.s))
                Text(value, style = NumberStyles.inline, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Meter(fraction, color, height = Sizes.meterThin + 2.dp)
            if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// -- The orbit gauge --------------------------------------------------------------------------------------------------

/**
 * Lagrange's signature hero: a budget drawn as an orbit. The arc is how much of today's budget is used, with a
 * small body riding its tip; a second, inner lap appears when the budget is exceeded. It fills with a slow spring,
 * so it settles like a body arriving in orbit. [center] holds the hero number.
 */
@Composable
fun OrbitGauge(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = Sizes.heroGauge,
    strokeWidth: Dp = 14.dp,
    center: @Composable () -> Unit,
) {
    val f = animatedFraction(fraction.coerceIn(0f, 2f))
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val body = MaterialTheme.colorScheme.surface
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val stroke = strokeWidth.toPx()
            val r = this.size.minDimension / 2 - stroke / 2 - 4.dp.toPx()
            val c = this.center
            val topLeft = Offset(c.x - r, c.y - r)
            val arc = Size(r * 2, r * 2)
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.10f), Color.Transparent), c, r), radius = r, center = c)
            drawCircle(track, radius = r, center = c, style = Stroke(stroke))
            val first = f.coerceIn(0f, 1f)
            if (first > 0.002f) drawArc(color, -90f, 360f * first, false, topLeft, arc, style = Stroke(stroke, cap = StrokeCap.Round))
            if (f > 1f) {
                val ri = r - stroke * 1.25f
                drawArc(
                    color.copy(alpha = 0.55f), -90f, 360f * (f - 1f).coerceAtMost(1f), false,
                    Offset(c.x - ri, c.y - ri), Size(ri * 2, ri * 2), style = Stroke(stroke * 0.5f, cap = StrokeCap.Round),
                )
            }
            // The body: a surface-coloured bead with an accent core at the tip of the arc.
            val a = Math.toRadians(-90.0 + 360.0 * first)
            val tip = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
            drawCircle(body, radius = stroke * 0.42f, center = tip)
            drawCircle(color, radius = stroke * 0.22f, center = tip)
        }
        center()
    }
}
