package com.celestiallemon.lagrange.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * A 4dp grid. Use the named steps; never a raw dp in a screen. Larger gaps separate ideas, smaller gaps group them:
 * a section is [xxl] from the next, its rows [s] from each other, a label [xs] from its value.
 */
@Immutable
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
    val xxxl = 48.dp

    /** Left and right margin of every screen (Material compact window). */
    val gutter = 16.dp

    /** Inner padding of a [com.celestiallemon.lagrange.ui.components.Panel]. */
    val panel = 20.dp
}

/**
 * Shape scale. Corner size grows with the size and importance of a container: chips and meters are pills, rows sit in
 * [Shapes.medium] groups, panels use [Shapes.large] and the one hero on a screen uses [Shapes.extraLarge].
 */
internal val LagrangeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Sizes that recur across components. */
@Immutable
object Sizes {
    val minTouch = 48.dp
    val icon = 24.dp
    val iconSmall = 18.dp
    val dot = 10.dp
    val meter = 8.dp
    val meterThin = 4.dp
    val rowMin = 56.dp
    val heroGauge = 200.dp
}
