package com.celestiallemon.lagrange.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/*
 * Colour in Lagrange has two jobs that must never be confused:
 *
 * 1. Brand and interaction (the Material [ColorScheme]): "Night" indigo for primary actions and selection, a
 *    "Starlight" gold for rare celebratory accents. These follow the wallpaper when dynamic colour is on.
 * 2. Meaning ([Semantic]): what an app category is, whether a number is good or worrying, how a chart reads. These
 *    are fixed hues that do NOT follow the wallpaper, because a Distracting bar must look the same on every phone,
 *    every day, in every chart. Each meaning gets exactly one hue; no hue means two things.
 */

// -- Brand schemes ----------------------------------------------------------------------------------------------------

internal val NightLight: ColorScheme = lightColorScheme(
    primary = Color(0xFF4A4BC4),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE1E0FF),
    onPrimaryContainer = Color(0xFF14126B),
    inversePrimary = Color(0xFFC1C1FF),
    secondary = Color(0xFF5B5D7E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0E0FF),
    onSecondaryContainer = Color(0xFF181A37),
    tertiary = Color(0xFF7A5900),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFFDF9E),
    onTertiaryContainer = Color(0xFF261A00),
    background = Color(0xFFFBF8FD),
    onBackground = Color(0xFF1B1B21),
    surface = Color(0xFFFBF8FD),
    onSurface = Color(0xFF1B1B21),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    surfaceTint = Color(0xFF4A4BC4),
    inverseSurface = Color(0xFF303036),
    inverseOnSurface = Color(0xFFF2EFF7),
    surfaceBright = Color(0xFFFBF8FD),
    surfaceDim = Color(0xFFDCD9E0),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF5F2FA),
    surfaceContainer = Color(0xFFEFEDF4),
    surfaceContainerHigh = Color(0xFFEAE7EF),
    surfaceContainerHighest = Color(0xFFE4E1E9),
    outline = Color(0xFF777680),
    outlineVariant = Color(0xFFC7C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    scrim = Color(0xFF000000),
)

internal val NightDark: ColorScheme = darkColorScheme(
    primary = Color(0xFFC1C1FF),
    onPrimary = Color(0xFF211F93),
    primaryContainer = Color(0xFF3735AB),
    onPrimaryContainer = Color(0xFFE1E0FF),
    inversePrimary = Color(0xFF4A4BC4),
    secondary = Color(0xFFC4C4EA),
    onSecondary = Color(0xFF2D2F4D),
    secondaryContainer = Color(0xFF434565),
    onSecondaryContainer = Color(0xFFE0E0FF),
    tertiary = Color(0xFFF3C04F),
    onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5C4200),
    onTertiaryContainer = Color(0xFFFFDF9E),
    background = Color(0xFF111117),
    onBackground = Color(0xFFE4E1E9),
    surface = Color(0xFF111117),
    onSurface = Color(0xFFE4E1E9),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    surfaceTint = Color(0xFFC1C1FF),
    inverseSurface = Color(0xFFE4E1E9),
    inverseOnSurface = Color(0xFF303036),
    surfaceBright = Color(0xFF37363D),
    surfaceDim = Color(0xFF111117),
    surfaceContainerLowest = Color(0xFF0C0C12),
    surfaceContainerLow = Color(0xFF19191F),
    surfaceContainer = Color(0xFF1E1D24),
    surfaceContainerHigh = Color(0xFF28272E),
    surfaceContainerHighest = Color(0xFF333239),
    outline = Color(0xFF918F9A),
    outlineVariant = Color(0xFF46464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    scrim = Color(0xFF000000),
)

// -- Meaning ----------------------------------------------------------------------------------------------------------

/** A hue in three strengths: [accent] for marks and text on surfaces, [container] + [onContainer] for fills. */
@Immutable
data class ToneColors(val accent: Color, val container: Color, val onContainer: Color)

/**
 * The emotional register of a piece of information. Use the weakest tone that is true: most things are [Neutral].
 * [Ember] is reserved for distracting time and the things that grow it (rabbit holes, spikes).
 */
enum class Tone { Neutral, Brand, Good, Caution, Critical, Ember }

@Immutable
data class CategoryColors(
    val distracting: Color,
    val neutral: Color,
    val utility: Color,
    val excluded: Color,
)

/** Chart ink. Bars take a category colour; everything else in a chart is drawn from these, never from the brand. */
@Immutable
data class VizColors(
    /** The 7-day average line: high-contrast ink so the trend reads over any bar colour. */
    val trend: Color,
    /** The 28-day average: quieter, dashed. */
    val trendSlow: Color,
    /** Budget and goal lines. */
    val reference: Color,
    val grid: Color,
    val axis: Color,
    /** Hatching for partial and missing days. */
    val hatch: Color,
    val selection: Color,
)

/**
 * The light plate a legacy (non-adaptive) launcher icon sits on, as launchers set them: such icons are drawn for a
 * light backing, so a dark glyph (a black logo) stays legible in dark theme too. Fixed, like the other meanings, so
 * an app's icon looks the same on every phone. [edge] is a hairline that separates the plate from a light surface.
 */
@Immutable
data class IconPlate(val fill: Color, val edge: Color)

@Immutable
data class Semantic(
    val neutral: ToneColors,
    val brand: ToneColors,
    val good: ToneColors,
    val caution: ToneColors,
    val critical: ToneColors,
    val ember: ToneColors,
    val category: CategoryColors,
    val viz: VizColors,
    val iconPlate: IconPlate,
) {
    fun tone(t: Tone): ToneColors = when (t) {
        Tone.Neutral -> neutral
        Tone.Brand -> brand
        Tone.Good -> good
        Tone.Caution -> caution
        Tone.Critical -> critical
        Tone.Ember -> ember
    }
}

private val EmberLight = ToneColors(Color(0xFFC9512A), Color(0xFFFFE1D5), Color(0xFF4F1803))
private val EmberDark = ToneColors(Color(0xFFFF9C77), Color(0xFF5E2311), Color(0xFFFFDBCD))

internal fun semanticLight(scheme: ColorScheme) = Semantic(
    neutral = ToneColors(scheme.onSurfaceVariant, scheme.surfaceContainerHigh, scheme.onSurface),
    brand = ToneColors(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer),
    good = ToneColors(Color(0xFF2C7A4B), Color(0xFFD5F0DD), Color(0xFF07341A)),
    caution = ToneColors(Color(0xFF9A5C00), Color(0xFFFFE5C0), Color(0xFF3B2200)),
    critical = ToneColors(Color(0xFFB8302A), Color(0xFFFFDAD5), Color(0xFF470604)),
    ember = EmberLight,
    category = CategoryColors(
        distracting = EmberLight.accent,
        neutral = Color(0xFF5866B8),
        utility = Color(0xFF1F857A),
        excluded = Color(0xFF8E8D99),
    ),
    viz = VizColors(
        trend = Color(0xFF1B1B21),
        trendSlow = Color(0xFF6E6D78),
        reference = Color(0xFF9A5C00),
        grid = Color(0xFFE4E1E9),
        axis = Color(0xFF6E6D78),
        hatch = Color(0xFF8E8D99),
        selection = Color(0x0F1B1B21),
    ),
    // White, edged with 12% ink so it doesn't melt into the near-white surfaces.
    iconPlate = IconPlate(fill = Color(0xFFFFFFFF), edge = Color(0x1F1B1B21)),
)

internal fun semanticDark(scheme: ColorScheme) = Semantic(
    neutral = ToneColors(scheme.onSurfaceVariant, scheme.surfaceContainerHigh, scheme.onSurface),
    brand = ToneColors(scheme.primary, scheme.primaryContainer, scheme.onPrimaryContainer),
    good = ToneColors(Color(0xFF82D6A1), Color(0xFF0F4627), Color(0xFFC6F1D3)),
    caution = ToneColors(Color(0xFFFFC46B), Color(0xFF543500), Color(0xFFFFE2BD)),
    critical = ToneColors(Color(0xFFFFB1A7), Color(0xFF6B1712), Color(0xFFFFDAD5)),
    ember = EmberDark,
    category = CategoryColors(
        distracting = EmberDark.accent,
        neutral = Color(0xFFAAB5FF),
        utility = Color(0xFF6FD4C5),
        excluded = Color(0xFF9C9BA7),
    ),
    viz = VizColors(
        trend = Color(0xFFF2EFF7),
        trendSlow = Color(0xFFA09EAA),
        reference = Color(0xFFFFC46B),
        grid = Color(0xFF2E2D34),
        axis = Color(0xFF9C9BA7),
        hatch = Color(0xFF9C9BA7),
        selection = Color(0x14F2EFF7),
    ),
    // Orbit's soft off-white (dark onSurface), so the plate reads as light without glaring; no edge needed.
    iconPlate = IconPlate(fill = Color(0xFFE4E1E9), edge = Color.Transparent),
)
