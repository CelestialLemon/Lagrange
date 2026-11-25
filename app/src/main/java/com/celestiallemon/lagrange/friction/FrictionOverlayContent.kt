package com.celestiallemon.lagrange.friction

import android.os.SystemClock
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.HourglassBottom
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.db.IntentionTag
import com.celestiallemon.lagrange.domain.friction.ActiveOverlay
import com.celestiallemon.lagrange.ui.components.Meter
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.Lagrange
import com.celestiallemon.lagrange.ui.theme.Motion
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.Transitions
import kotlin.math.ceil

/** One breath: four seconds in, four seconds out. Slow enough to actually breathe along with. */
private const val BREATH_MS = 4_000

/**
 * The launch pause. It is a moment, not a form: a breathing orb holds the centre while the countdown runs around
 * it; the way out (Go home) is the primary action and sits under the thumb; continuing is always possible and the
 * Continue button itself fills up as the countdown runs, so it's clear when it will open and that nothing is locked.
 */
@Composable
internal fun LaunchPauseOverlayContent(
    overlay: ActiveOverlay.LaunchPause,
    onSelectIntention: (IntentionTag) -> Unit,
    onSelectTimer: (Int?) -> Unit,
    onGoHome: () -> Unit,
    onRedirect: () -> Unit,
    onContinue: () -> Unit,
    onSnooze1h: () -> Unit,
) {
    val plan = overlay.plan
    val totalMs = (plan.pauseDurationSec * 1_000L).coerceAtLeast(1_000L)
    var remainingMs by remember(overlay.shownAtElapsedMs) {
        mutableLongStateOf((overlay.countdownEndElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }
    LaunchedEffect(overlay.shownAtElapsedMs, overlay.countdownEndElapsedMs) {
        while (remainingMs > 0L) {
            kotlinx.coroutines.delay(50L)
            remainingMs = (overlay.countdownEndElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
    }
    val progress = (1f - (remainingMs.toFloat() / totalMs.toFloat())).coerceIn(0f, 1f)
    val unlocked = remainingMs <= 0L
    val secondsLeft = ceil(remainingMs / 1_000.0).toInt().coerceAtLeast(0)
    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.97f))
            .drawBehind {
                drawCircle(
                    Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width / 2, size.height * 0.32f), radius = size.width * 0.9f),
                    radius = size.width * 0.9f,
                    center = Offset(size.width / 2, size.height * 0.32f),
                )
            }
            .safeDrawingPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Spacing.l),
        ) {
            Spacer(Modifier.height(Spacing.l))
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text("Opening ${plan.displayName}", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                Text(
                    "${ordinal(plan.nthOpenToday)} time today",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (plan.scheduleName != null || plan.focusBoostActive) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally), modifier = Modifier.padding(top = Spacing.xs)) {
                        plan.scheduleName?.let { Tag(it, tone = Tone.Brand) }
                        if (plan.focusBoostActive) Tag("Focus Boost", tone = Tone.Brand)
                    }
                }
            }

            BreathingOrb(progress, secondsLeft, unlocked)

            if (plan.showBudgetComparison && plan.budgetMs > 0L) {
                val over = plan.distractingMsToday > plan.budgetMs
                val c = Lagrange.colors
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                    Row {
                        Text("Distracting today", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text(
                            "${formatDurationShort(plan.distractingMsToday)} of ${formatDurationShort(plan.budgetMs)}",
                            style = NumberStyles.inline,
                            color = if (over) c.critical.accent else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Meter(plan.distractingMsToday.toFloat() / plan.budgetMs, if (over) c.critical.accent else c.category.distracting, height = 6.dp)
                }
            }

            if (plan.showIntentionChips) {
                ChipQuestion("What brought you here?") {
                    listOf(IntentionTag.REPLY, IntentionTag.LOOKUP, IntentionTag.UNWIND, IntentionTag.BORED).forEach { tag ->
                        FilterChip(selected = overlay.selectedIntention == tag, onClick = { onSelectIntention(tag) }, label = { Text(tag.label) })
                    }
                }
            }
            if (plan.showTimerChips && plan.timerPresetsMin.isNotEmpty()) {
                ChipQuestion(if (plan.encourageTimerSelection) "Set a check-in timer?" else "A check-in timer, if you like") {
                    plan.timerPresetsMin.forEach { min ->
                        FilterChip(
                            selected = overlay.selectedTimerMin == min,
                            onClick = { onSelectTimer(if (overlay.selectedTimerMin == min) null else min) },
                            label = { Text("${min}m") },
                        )
                    }
                    FilterChip(selected = overlay.selectedTimerMin == null, onClick = { onSelectTimer(null) }, label = { Text("No timer") })
                }
            }

            Spacer(Modifier.height(Spacing.l))

            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
                Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text("Go home", style = MaterialTheme.typography.titleMedium)
                }
                if (plan.redirectDisplayName != null) {
                    FilledTonalButton(onClick = onRedirect, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                        Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(Spacing.s))
                        Text("Open ${plan.redirectDisplayName} instead", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                ContinueButton(
                    label = "Continue to ${plan.displayName}" + (overlay.selectedTimerMin?.let { " · ${it}m" }.orEmpty()),
                    progress = progress,
                    secondsLeft = secondsLeft,
                    unlocked = unlocked,
                    onClick = onContinue,
                )
                TextButton(onClick = onSnooze1h, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                    Text("Pause friction for an hour", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ChipQuestion(title: String, chips: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s, Alignment.CenterHorizontally), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) { chips() }
    }
}

/**
 * The orb breathes (in for four seconds, out for four) while the countdown ring closes around it. When the pause
 * is over the orb settles and the words change: the moment has been offered, the choice is yours.
 */
@Composable
private fun BreathingOrb(progress: Float, secondsLeft: Int, unlocked: Boolean) {
    val reduced = Lagrange.reducedMotion
    // An explicit loop rather than a reversing animation, so the words always match the direction of the breath.
    val orbScale = remember { Animatable(0.5f) }
    var inhaling by remember { mutableStateOf(true) }
    LaunchedEffect(unlocked, reduced) {
        if (unlocked || reduced) {
            orbScale.animateTo(0.5f, Motion.spatialSlow())
            return@LaunchedEffect
        }
        orbScale.snapTo(0f)
        while (true) {
            inhaling = true
            orbScale.animateTo(1f, tween(BREATH_MS, easing = Motion.Emphasized))
            inhaling = false
            orbScale.animateTo(0f, tween(BREATH_MS, easing = Motion.Emphasized))
        }
    }
    val breath = orbScale.value
    val settle by animateFloatAsState(if (unlocked) 1f else 0f, Motion.spatialSlow(), label = "settle")
    val ring = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val orb = MaterialTheme.colorScheme.primaryContainer
    val core = MaterialTheme.colorScheme.primary
    Box(
        Modifier
            .size(220.dp)
            .semantics { contentDescription = if (unlocked) "Pause over. Continue is available." else "$secondsLeft seconds left. Breathe." },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val r = size.minDimension / 2 - stroke
            drawCircle(track, radius = r, style = Stroke(stroke))
            drawArc(ring, -90f, 360f * progress, false, Offset(center.x - r, center.y - r), androidx.compose.ui.geometry.Size(r * 2, r * 2), style = Stroke(stroke, cap = StrokeCap.Round))
            val orbR = r * (0.52f + 0.22f * breath) * (1f - 0.08f * settle)
            drawCircle(Brush.radialGradient(listOf(orb, orb.copy(alpha = 0.35f)), center, orbR), radius = orbR)
            drawCircle(core.copy(alpha = 0.18f + 0.12f * breath), radius = orbR * 0.55f)
        }
        AnimatedContent(
            targetState = when {
                unlocked -> "Ready when you are"
                reduced -> "${secondsLeft}s"
                inhaling -> "Breathe in"
                else -> "Breathe out"
            },
            transitionSpec = { with(Transitions) { swap() } },
            label = "breath-word",
        ) { word ->
            Text(word, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, textAlign = TextAlign.Center)
        }
    }
}

/**
 * Continue is never hidden, only not yet ready: its fill grows with the countdown, then it becomes a normal button.
 * It is deliberately the quietest of the three choices.
 */
@Composable
private fun ContinueButton(label: String, progress: Float, secondsLeft: Int, unlocked: Boolean, onClick: () -> Unit) {
    val fill = MaterialTheme.colorScheme.surfaceContainerHighest
    OutlinedButton(
        onClick = onClick,
        enabled = unlocked,
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(MaterialTheme.shapes.extraLarge)
            .drawBehind { if (!unlocked) drawRect(fill, size = size.copy(width = size.width * progress)) },
    ) {
        Text(if (unlocked) label else "Continue in ${secondsLeft}s", maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A soft timer ran out. Three honest options, the gentlest first. */
@Composable
internal fun TimerExpiredOverlayContent(
    overlay: ActiveOverlay.TimerExpired,
    onGoHome: () -> Unit,
    onExtend5m: () -> Unit,
    onDismissSession: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.4f))
            .safeDrawingPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        ) {
            Column(Modifier.padding(Spacing.xl), verticalArrangement = Arrangement.spacedBy(Spacing.m)) {
                Icon(Icons.Outlined.HourglassBottom, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Text("Your ${overlay.timerMinutes} minutes are up", style = MaterialTheme.typography.headlineSmall)
                if (overlay.budgetMs > 0) {
                    val over = overlay.distractingMsToday > overlay.budgetMs
                    val c = Lagrange.colors
                    Row {
                        Text("Distracting today", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        Text("${formatDurationShort(overlay.distractingMsToday)} of ${formatDurationShort(overlay.budgetMs)}", style = NumberStyles.inline)
                    }
                    Meter(overlay.distractingMsToday.toFloat() / overlay.budgetMs, if (over) c.critical.accent else c.category.distracting, height = 6.dp)
                }
                Spacer(Modifier.height(Spacing.xs))
                Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text("Done, go home")
                }
                FilledTonalButton(onClick = onExtend5m, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Spacing.s))
                    Text("Five more minutes")
                }
                TextButton(onClick = onDismissSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Keep going without a timer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** A small banner at the top: how long it's been. It never blocks and goes away with a tap. */
@Composable
internal fun TimeBlindnessNudgeContent(
    overlay: ActiveOverlay.TimeBlindnessNudge,
    onDismiss: () -> Unit,
) {
    val trail = overlay.appsHopped.takeLast(4).joinToString(" → ")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.statusBars.union(WindowInsets.displayCutout))
            .padding(horizontal = Spacing.l, vertical = Spacing.s),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.inverseSurface,
            contentColor = MaterialTheme.colorScheme.inverseOnSurface,
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clickable(onClick = onDismiss),
        ) {
            Row(Modifier.padding(start = Spacing.l, end = Spacing.xs, top = Spacing.m, bottom = Spacing.m), verticalAlignment = Alignment.CenterVertically) {
                Text("${overlay.milestoneMinutes}m", style = NumberStyles.stat, color = Lagrange.colors.ember.container)
                Spacer(Modifier.width(Spacing.m))
                Column(Modifier.weight(1f)) {
                    Text("in distracting apps, without a break", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (trail.isNotEmpty()) Text(trail, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, contentDescription = "Dismiss", modifier = Modifier.size(18.dp)) }
            }
        }
    }
}

private fun ordinal(n: Int): String {
    val mod100 = n % 100
    val suffix = if (mod100 in 11..13) "th" else when (n % 10) {
        1 -> "st"
        2 -> "nd"
        3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
