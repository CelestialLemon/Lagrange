package com.celestiallemon.lagrange.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.sp
import com.celestiallemon.lagrange.R

/*
 * Two voices.
 *
 * Fraunces, a soft "wonky" serif, is Lagrange's personality: screen titles, hero sentences and the numbers that answer
 * a question ("1h 12m"). It is used large and sparingly, so it reads as a voice, not decoration.
 *
 * Roboto (the platform sans) does all the work: labels, body text, controls and chart axes. Numbers in Roboto use
 * tabular figures so columns line up and animated values don't jitter.
 *
 * There are no all-caps eyebrows: hierarchy comes from size, weight and space.
 */

val Fraunces = FontFamily(
    Font(R.font.fraunces_regular, FontWeight.Normal),
    Font(R.font.fraunces_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.fraunces_medium, FontWeight.Medium),
    Font(R.font.fraunces_semibold, FontWeight.SemiBold),
)

private const val TABULAR = "tnum, lnum"

private val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)

private fun serif(size: Int, line: Int, weight: FontWeight = FontWeight.Medium, tracking: Double = 0.0) = TextStyle(
    fontFamily = Fraunces,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = trim,
)

private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.sp,
    lineHeightStyle = trim,
)

internal val LagrangeTypography = Typography(
    displayLarge = serif(57, 64, tracking = -0.5),
    displayMedium = serif(45, 52, tracking = -0.25),
    displaySmall = serif(36, 44),
    headlineLarge = serif(32, 40),
    headlineMedium = serif(28, 36),
    headlineSmall = serif(24, 32),
    titleLarge = sans(22, 28, FontWeight.Medium),
    titleMedium = sans(16, 24, FontWeight.Medium, 0.1),
    titleSmall = sans(14, 20, FontWeight.Medium, 0.1),
    bodyLarge = sans(16, 24, tracking = 0.2),
    bodyMedium = sans(14, 20, tracking = 0.2),
    bodySmall = sans(12, 16, tracking = 0.3),
    labelLarge = sans(14, 20, FontWeight.Medium, 0.1),
    labelMedium = sans(12, 16, FontWeight.Medium, 0.4),
    labelSmall = sans(11, 16, FontWeight.Medium, 0.4),
)

/** Number styles. Anything that shows a measurement uses one of these, so figures line up and roll smoothly. */
@Immutable
object NumberStyles {
    /** The one answer on a screen: Today's distracting time, Trends' average. */
    val hero = serif(52, 56, FontWeight.SemiBold, -0.5).copy(fontFeatureSettings = TABULAR)

    /** A secondary headline number (a stat in a row of three). */
    val stat = serif(26, 32, FontWeight.Medium).copy(fontFeatureSettings = TABULAR)

    /** Inline numbers in lists: durations on a row, counts on a tag. */
    val inline = sans(14, 20, FontWeight.Medium, 0.1).copy(fontFeatureSettings = TABULAR)

    /** Small numbers on chart axes and meters. */
    val axis = sans(11, 14, FontWeight.Normal, 0.2).copy(fontFeatureSettings = TABULAR)
}
