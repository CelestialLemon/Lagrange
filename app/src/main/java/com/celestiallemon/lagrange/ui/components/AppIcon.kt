package com.celestiallemon.lagrange.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.ui.icons.AppIconLoader
import com.celestiallemon.lagrange.ui.icons.AppIconResult
import com.celestiallemon.lagrange.ui.theme.Lagrange

/**
 * Where [AppIcon] gets launcher icons. The default loads nothing, so previews, screenshots and tests show the letter
 * avatar; the activity provides the process-wide cache.
 */
val LocalAppIconLoader = staticCompositionLocalOf<AppIconLoader> { AppIconLoader.None }

/** The leading tile every app row uses, icon or letter. */
private val AppTileSize = 40.dp

/**
 * An app's real launcher icon at the start of a row, falling back to its [AppAvatar] letter (in its category
 * [color]) while loading, or when the app is uninstalled, has no icon, or is a test fixture.
 *
 * Both are the same 40dp tile, so the swap never moves the layout. An icon already in memory shows on the first
 * frame, with no letter in between. Adaptive icons are clipped to the Orbit tile shape; legacy icons keep their own
 * shape, inset on a light [com.celestiallemon.lagrange.ui.theme.IconPlate].
 */
@Composable
fun AppIcon(packageName: String?, name: String, color: Color, modifier: Modifier = Modifier) {
    val sizePx = with(LocalDensity.current) { AppTileSize.roundToPx() }.coerceAtLeast(1)
    when (val icon = rememberAppIcon(packageName, sizePx)) {
        is AppIconResult.Icon -> {
            val shape = MaterialTheme.shapes.small
            val plate = Lagrange.colors.iconPlate
            Image(
                bitmap = icon.bitmap,
                // The app's name is always written beside it.
                contentDescription = null,
                modifier = modifier
                    .size(AppTileSize)
                    .clip(shape)
                    // Legacy icons are inset on a light plate, as a launcher shows them: they're drawn for one, so a
                    // dark logo stays legible in dark theme. Adaptive icons fill the tile themselves.
                    .then(
                        if (icon.fullBleed) Modifier
                        else Modifier.background(plate.fill).border(Dp.Hairline, plate.edge, shape),
                    ),
            )
        }
        else -> AppAvatar(name, color, modifier)
    }
}

/**
 * The launcher icon of [packageName] at [sizePx]: from memory during composition when it's there, otherwise null
 * until [LocalAppIconLoader] has loaded it in the background. [AppIconResult.Missing] means use the letter.
 *
 * When the app is installed, updated or removed while on screen, the icon is looked up again: the one shown stays
 * until its replacement is ready. Only rows showing that app reload.
 */
@Composable
fun rememberAppIcon(packageName: String?, sizePx: Int): AppIconResult? {
    val loader = LocalAppIconLoader.current
    val pkg = packageName?.takeIf { it.isNotBlank() }
    val revisions = loader.revisions.collectAsState()
    // Derived, so a change to another package doesn't recompose this row.
    val revision by remember(revisions, pkg) { derivedStateOf { pkg?.let(revisions.value::of) ?: 0L } }
    // Keyed on the package, so a recycled slot never shows the previous app's icon while the new one loads.
    val state = remember(loader, pkg, sizePx) {
        mutableStateOf(if (pkg == null) AppIconResult.Missing else loader.cached(pkg, sizePx))
    }
    LaunchedEffect(state, revision) {
        // A new revision cancels the previous effect, so a decode started before the change never lands here.
        if (pkg != null) state.value = loader.cached(pkg, sizePx) ?: loader.load(pkg, sizePx)
    }
    return state.value
}

/** Marks [AppAvatar], whose letter is hidden from accessibility and so can't be found by its text. */
internal const val AppAvatarTestTag = "AppAvatar"

/** A leading letter avatar for an app, coloured by its category: [AppIcon]'s fallback. */
@Composable
fun AppAvatar(name: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(AppTileSize)
            .clip(MaterialTheme.shapes.small)
            .background(color.copy(alpha = 0.16f))
            // Decorative, like the icon it stands in for: the app's name is written beside it, so a screen reader
            // shouldn't read its initial out as well.
            .clearAndSetSemantics { testTag = AppAvatarTestTag },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().firstOrNull()?.uppercase() ?: "?",
            style = MaterialTheme.typography.titleMedium,
            color = color,
        )
    }
}
