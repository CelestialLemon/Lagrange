package com.celestiallemon.lagrange.ui.components

import androidx.activity.BackEventCompat
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Transitions
import kotlin.coroutines.cancellation.CancellationException

/**
 * A stack of two layers: [base] and, when [top] is non-null, a detail drawn over it. Drilling in and out uses the
 * shared X axis; the system back gesture previews the way out (the detail shrinks toward the edge the swipe
 * started from) before committing, and springs back if cancelled.
 */
@Composable
fun <T : Any> DrillIn(
    top: T?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    base: @Composable () -> Unit,
    detail: @Composable (T) -> Unit,
) {
    var progress by remember { mutableFloatStateOf(0f) }
    var edge by remember { mutableIntStateOf(BackEventCompat.EDGE_LEFT) }
    PredictiveBackHandler(enabled = top != null) { events ->
        try {
            events.collect {
                edge = it.swipeEdge
                progress = it.progress
            }
            onBack()
        } catch (e: CancellationException) {
            progress = 0f
            throw e
        }
    }
    // A new destination starts unshrunk, whatever the last gesture left behind.
    LaunchedEffect(top) { progress = 0f }
    val shape = MaterialTheme.shapes.extraLarge
    AnimatedContent(
        targetState = top,
        transitionSpec = { Transitions.sharedAxisX(forward = targetState != null) },
        modifier = modifier,
        label = "drill-in",
    ) { current ->
        if (current == null) {
            base()
        } else {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val p = progress
                        val scale = 1f - 0.1f * p
                        scaleX = scale
                        scaleY = scale
                        val shift = size.width * 0.05f * p
                        translationX = if (edge == BackEventCompat.EDGE_RIGHT) -shift else shift
                        this.shape = shape
                        clip = p > 0f
                    },
            ) { detail(current) }
        }
    }
}

/** The settings gear in every tab's top bar. A dot means something in Settings needs attention. */
@Composable
fun SettingsAction(attention: Boolean, onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        BadgedBox(badge = { if (attention) Badge(containerColor = Lagrange.colors.caution.accent) }) {
            Icon(Icons.Outlined.Settings, contentDescription = if (attention) "Settings, needs attention" else "Settings")
        }
    }
}
