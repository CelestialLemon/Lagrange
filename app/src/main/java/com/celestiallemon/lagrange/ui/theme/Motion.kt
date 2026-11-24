package com.celestiallemon.lagrange.ui.theme

import android.provider.Settings
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/*
 * Motion in Lagrange is physical and calm: bodies settling into orbit, never bouncing around.
 *
 * Two families, as in Material 3 Expressive:
 *   - Spatial springs move things (position, size, scale, arcs). They may overshoot a touch, which is what makes a
 *     gauge feel like it "lands".
 *   - Effects springs change appearance (colour, alpha). They never overshoot: a colour flashing past its target
 *     reads as a glitch.
 * Each comes in Fast (small components, feedback), Default (cards, content) and Slow (heroes, one per screen).
 *
 * Choreography rules:
 *   1. One hero moves at a time. A screen's arrival is: content rises in (staggered), then the hero gauge fills.
 *   2. Motion explains change. A number that changes rolls; a bar that changes grows; content that is replaced
 *      fades through. Nothing animates that did not change.
 *   3. Ambient motion (a breathing ring, an orbiting dot) is reserved for moments of pause, and stops entirely when
 *      the system "Remove animations" setting is on.
 */
@Immutable
object Motion {
    // -- Spatial ------------------------------------------------------------------------------------------------------
    fun <T> spatialFast(): SpringSpec<T> = spring(dampingRatio = 0.9f, stiffness = 1400f)
    fun <T> spatial(): SpringSpec<T> = spring(dampingRatio = 0.8f, stiffness = 380f)
    fun <T> spatialSlow(): SpringSpec<T> = spring(dampingRatio = 0.72f, stiffness = 110f)

    // -- Effects ------------------------------------------------------------------------------------------------------
    fun <T> effectsFast(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 3800f)
    fun <T> effects(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 1600f)
    fun <T> effectsSlow(): SpringSpec<T> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 600f)

    // -- Easing & duration, for the few things that must be time-based (cross-fades, countdowns) ---------------------
    val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val EmphasizedDecelerate = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)
    val EmphasizedAccelerate = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f)

    const val SHORT = 150
    const val MEDIUM = 300
    const val LONG = 500

    /** Delay between items in a staggered entrance, and how many items stagger before the rest arrive together. */
    const val STAGGER_MS = 40L
    const val STAGGER_MAX = 8

    /** Items composed later than this after a screen appears (scrolled into view) do not play the entrance. */
    const val ENTRANCE_WINDOW_MS = 700L

    fun <T> fadeIn(durationMs: Int = 210, delayMs: Int = 0): FiniteAnimationSpec<T> = tween(durationMs, delayMs, EmphasizedDecelerate)
    fun <T> fadeOut(durationMs: Int = 90): FiniteAnimationSpec<T> = tween(durationMs, 0, EmphasizedAccelerate)
}

// -- Reduced motion ---------------------------------------------------------------------------------------------------

/** True when the user asked Android to remove animations. Ambient and decorative motion must stop. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
internal fun ProvideReducedMotion(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val reduced = remember(context) {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
            .getOrDefault(false)
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced, content = content)
}

// -- Patterns ---------------------------------------------------------------------------------------------------------

/** The moment the current screen appeared, so entrances only play for content present at arrival. */
val LocalScreenArrival = staticCompositionLocalOf { 0L }

@Composable
fun ProvideScreenArrival(key: Any? = Unit, content: @Composable () -> Unit) {
    val arrival = remember(key) { System.nanoTime() / 1_000_000L }
    CompositionLocalProvider(LocalScreenArrival provides arrival, content = content)
}

/**
 * Entrance: rise 24dp and fade in, staggered by [index]. Plays once when the screen arrives; content scrolled into
 * view later simply appears, so scrolling never feels sluggish.
 */
fun Modifier.rise(index: Int): Modifier = composed {
    val reduced = LocalReducedMotion.current
    val arrival = LocalScreenArrival.current
    val play = remember { !reduced && System.nanoTime() / 1_000_000L - arrival < Motion.ENTRANCE_WINDOW_MS }
    if (!play) return@composed this
    val distance = with(LocalDensity.current) { 24.dp.toPx() }
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(Motion.STAGGER_MS * index.coerceIn(0, Motion.STAGGER_MAX))
        launch { progress.animateTo(1f, Motion.spatial()) }
    }
    graphicsLayer {
        val p = progress.value
        alpha = p.coerceIn(0f, 1f)
        translationY = (1f - p) * distance
    }
}

/** Press feedback: a pressed surface sinks slightly and springs back. Pair with the surface's own ripple. */
fun Modifier.pressScale(interactions: InteractionSource, pressed: Float = 0.97f): Modifier = composed {
    val isPressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (isPressed) pressed else 1f, Motion.spatialFast(), label = "press")
    graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Transitions between whole destinations and between states of one region. */
object Transitions {
    /** Peer destinations (bottom tabs): fade through, the incoming screen growing from 96%. */
    fun fadeThrough(): ContentTransform =
        (fadeIn(Motion.fadeIn(210, 90)) + scaleIn(Motion.fadeIn(210, 90), initialScale = 0.96f)) togetherWith
            fadeOut(Motion.fadeOut(90))

    /** Drilling in and out (list → detail, onboarding steps): shared X axis, 10% travel. */
    fun sharedAxisX(forward: Boolean): ContentTransform {
        val dir = if (forward) 1 else -1
        val enter: EnterTransition = slideInHorizontally(Motion.spatial<IntOffset>()) { w -> dir * w / 10 } + fadeIn(Motion.fadeIn(210, 60))
        val exit: ExitTransition = slideOutHorizontally(Motion.spatial<IntOffset>()) { w -> -dir * w / 10 } + fadeOut(Motion.fadeOut(90))
        return enter togetherWith exit
    }

    /** Replacing the content of one region (loading → loaded, one Lab section → another). */
    fun <S> AnimatedContentTransitionScope<S>.swap(): ContentTransform =
        (fadeIn(Motion.fadeIn(220, 60)) + slideInVertically(Motion.spatial<IntOffset>()) { it / 24 }) togetherWith
            fadeOut(Motion.fadeOut(90))

    /** Revealing and hiding a block inside a list (an expanded detail, a banner). */
    val reveal: EnterTransition = expandVertically(Motion.spatial<IntSize>()) + fadeIn(Motion.fadeIn(200, 60))
    val conceal: ExitTransition = shrinkVertically(Motion.spatial<IntSize>()) + fadeOut(Motion.fadeOut(90))

    /** A number replaced by the next one: the new value rolls up (or down when it shrinks). */
    fun roll(up: Boolean): ContentTransform {
        val dir = if (up) 1 else -1
        return (slideInVertically(Motion.spatialFast<IntOffset>()) { h -> dir * h } + fadeIn(Motion.fadeIn(150))) togetherWith
            (slideOutVertically(Motion.spatialFast<IntOffset>()) { h -> -dir * h } + fadeOut(Motion.fadeOut(90)))
    }

    /** Small things popping into place (a selected rating, a badge). */
    val pop: EnterTransition = scaleIn(Motion.spatialFast(), initialScale = 0.6f) + fadeIn(Motion.fadeIn(120))
}
