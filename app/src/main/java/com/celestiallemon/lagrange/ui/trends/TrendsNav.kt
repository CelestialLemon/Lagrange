package com.celestiallemon.lagrange.ui.trends

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.SaveableStateHolder
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.CancellationException

/**
 * The Trends tab: the overview list, or one app's deep dive on top of it. The overview keeps its scroll position
 * while a deep dive is open, and the system back gesture previews the way back (the page shrinks towards the edge
 * the swipe started from) before returning to the list.
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
    var progress by remember { mutableFloatStateOf(0f) }
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    val cardShape = MaterialTheme.shapes.extraLarge

    PredictiveBackHandler(enabled = deepDive != null) { events ->
        try {
            events.collect {
                edge = it.swipeEdge
                progress = it.progress
            }
            onDeepDive(null)
        } catch (e: CancellationException) {
            // Cancelled gesture: spring back.
            progress = 0f
            throw e
        }
    }

    AnimatedContent(
        targetState = deepDive,
        transitionSpec = {
            val forward = targetState != null
            val dir = if (forward) 1 else -1
            (slideInHorizontally(tween(260)) { w -> dir * w / 5 } + fadeIn(tween(220))) togetherWith
                (slideOutHorizontally(tween(260)) { w -> -dir * w / 5 } + fadeOut(tween(180)))
        },
        label = "trends-nav",
    ) { pkgId ->
        if (pkgId == null) {
            saveable.SaveableStateProvider("overview") {
                TrendsScreen(padding, onOpenApp = { progress = 0f; onDeepDive(it) }, vm = trends)
            }
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = progress
                        val scale = 1f - 0.08f * p
                        scaleX = scale
                        scaleY = scale
                        val shift = size.width * 0.06f * p
                        translationX = if (edge == BackEventCompat.EDGE_RIGHT) -shift else shift
                        alpha = 1f - 0.25f * p
                        shape = cardShape
                        clip = p > 0f
                    },
            ) {
                saveable.SaveableStateProvider("app-$pkgId") {
                    AppDeepDiveScreen(pkgId, window, padding, onBack = { onDeepDive(null) })
                }
            }
        }
    }
}
