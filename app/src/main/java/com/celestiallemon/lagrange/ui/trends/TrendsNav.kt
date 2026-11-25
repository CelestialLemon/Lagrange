package com.celestiallemon.lagrange.ui.trends

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.celestiallemon.lagrange.ui.components.DrillIn

/**
 * The Trends tab: the overview, or one app's deep dive drilled into from it. The overview keeps its scroll
 * position while a deep dive is open, and the back gesture previews the way out (see [DrillIn]).
 */
@Composable
fun TrendsTab(
    padding: PaddingValues,
    deepDive: Long?,
    onDeepDive: (Long?) -> Unit,
    saveable: SaveableStateHolder = rememberSaveableStateHolder(),
) {
    val trends: TrendsViewModel = viewModel()
    val window by trends.window.collectAsStateWithLifecycle()
    DrillIn(
        top = deepDive,
        onBack = { onDeepDive(null) },
        base = { saveable.SaveableStateProvider("overview") { TrendsScreen(padding, onOpenApp = { onDeepDive(it) }, vm = trends) } },
        detail = { pkgId -> saveable.SaveableStateProvider("app-$pkgId") { AppDeepDiveScreen(pkgId, window, padding, onBack = { onDeepDive(null) }) } },
    )
}
