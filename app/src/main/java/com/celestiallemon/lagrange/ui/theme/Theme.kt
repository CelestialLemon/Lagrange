package com.celestiallemon.lagrange.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Cosmic fallback palette: deep indigo night, teal orbit, amber star. */
private val CosmicLight = lightColorScheme(
    primary = Color(0xFF4B4DC8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF0B0A63),
    secondary = Color(0xFF006A62),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFA6F2E5),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = Color(0xFF8A5100),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDCBE),
    onTertiaryContainer = Color(0xFF2C1600),
    background = Color(0xFFFBF8FF),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FF),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE4E1EC),
    onSurfaceVariant = Color(0xFF47464F),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FC),
    surfaceContainer = Color(0xFFEFECF8),
    surfaceContainerHigh = Color(0xFFE9E6F3),
    surfaceContainerHighest = Color(0xFFE3E0ED),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC8C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val CosmicDark = darkColorScheme(
    primary = Color(0xFFC0C1FF),
    onPrimary = Color(0xFF1C1B94),
    primaryContainer = Color(0xFF3535B0),
    onPrimaryContainer = Color(0xFFE1E0FF),
    secondary = Color(0xFF84D6C9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF00504A),
    onSecondaryContainer = Color(0xFFA6F2E5),
    tertiary = Color(0xFFFFB870),
    onTertiary = Color(0xFF4A2800),
    tertiaryContainer = Color(0xFF693C00),
    onTertiaryContainer = Color(0xFFFFDCBE),
    background = Color(0xFF0E0D1A),
    onBackground = Color(0xFFE5E1F0),
    surface = Color(0xFF0E0D1A),
    onSurface = Color(0xFFE5E1F0),
    surfaceVariant = Color(0xFF464559),
    onSurfaceVariant = Color(0xFFC8C5D9),
    surfaceContainerLowest = Color(0xFF09080F),
    surfaceContainerLow = Color(0xFF16152A),
    surfaceContainer = Color(0xFF1B1A30),
    surfaceContainerHigh = Color(0xFF252439),
    surfaceContainerHighest = Color(0xFF302F45),
    outline = Color(0xFF918FA3),
    outlineVariant = Color(0xFF464559),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** Generous, consistent spacing for an airy layout. */
@Immutable
object Space {
    val xs = 4.dp
    val s = 8.dp
    val m = 16.dp
    val l = 24.dp
    val xl = 32.dp
    val xxl = 48.dp
    /** Side margin for every screen. */
    val gutter = 20.dp
}

private val base = Typography()

private val LagrangeTypography = Typography(
    displayLarge = base.displayLarge.copy(fontWeight = FontWeight.Light, letterSpacing = (-1).sp),
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.75).sp),
    displaySmall = base.displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
    headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.5).sp),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Medium),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp),
)

/** Small uppercase label above section titles. */
val EyebrowStyle = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = FontWeight.SemiBold,
    fontSize = 11.sp,
    letterSpacing = 1.6.sp,
)

private val LagrangeShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

/** Status colours that read well on both palettes. */
@Immutable
data class StatusColors(val good: Color, val warn: Color, val bad: Color, val neutral: Color)

val LocalStatusColors = staticCompositionLocalOf {
    StatusColors(good = Color(0xFF2E7D6B), warn = Color(0xFFB26A00), bad = Color(0xFFBA1A1A), neutral = Color(0xFF777680))
}

@Composable
fun LagrangeTheme(
    dynamicColor: Boolean = false,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && darkTheme -> dynamicDarkColorScheme(context)
        dynamicColor -> dynamicLightColorScheme(context)
        darkTheme -> CosmicDark
        else -> CosmicLight
    }
    val status = if (darkTheme) {
        StatusColors(good = Color(0xFF7DDBC0), warn = Color(0xFFFFB870), bad = Color(0xFFFFB4AB), neutral = Color(0xFF918FA3))
    } else {
        StatusColors(good = Color(0xFF1F7A63), warn = Color(0xFF9A5B00), bad = Color(0xFFBA1A1A), neutral = Color(0xFF6A6975))
    }
    androidx.compose.runtime.CompositionLocalProvider(LocalStatusColors provides status) {
        MaterialTheme(colorScheme = scheme, typography = LagrangeTypography, shapes = LagrangeShapes, content = content)
    }
}
