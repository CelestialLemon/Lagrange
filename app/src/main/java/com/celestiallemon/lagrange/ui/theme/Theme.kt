package com.celestiallemon.lagrange.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext

internal val LocalSemantic = staticCompositionLocalOf { semanticLight(NightLight) }

/**
 * Lagrange's theme: Material 3 colour, type and shape, plus the fixed [Semantic] colours and the motion settings.
 * Read semantic colours through [Lagrange.colors]; read everything else through [MaterialTheme] as usual.
 */
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
        darkTheme -> NightDark
        else -> NightLight
    }
    val semantic = remember(scheme, darkTheme) { if (darkTheme) semanticDark(scheme) else semanticLight(scheme) }
    CompositionLocalProvider(LocalSemantic provides semantic) {
        ProvideReducedMotion {
            MaterialTheme(colorScheme = scheme, typography = LagrangeTypography, shapes = LagrangeShapes, content = content)
        }
    }
}

/** Entry point for Lagrange-specific tokens: `Lagrange.colors.category.distracting`. */
object Lagrange {
    val colors: Semantic
        @Composable @ReadOnlyComposable
        get() = LocalSemantic.current

    val reducedMotion: Boolean
        @Composable @ReadOnlyComposable
        get() = LocalReducedMotion.current
}
