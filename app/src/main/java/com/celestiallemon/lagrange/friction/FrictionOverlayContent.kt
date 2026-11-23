package com.celestiallemon.lagrange.friction

import android.os.SystemClock
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.db.IntentionTag
import com.celestiallemon.lagrange.domain.friction.ActiveOverlay
import com.celestiallemon.lagrange.ui.components.StatusPill
import com.celestiallemon.lagrange.ui.formatDurationShort
import com.celestiallemon.lagrange.ui.theme.EyebrowStyle
import com.celestiallemon.lagrange.ui.theme.Space
import kotlin.math.ceil
import kotlinx.coroutines.delay

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
            delay(50L)
            remainingMs = (overlay.countdownEndElapsedMs - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
        }
    }

    val progress = (1f - (remainingMs.toFloat() / totalMs.toFloat())).coerceIn(0f, 1f)
    val unlocked = remainingMs <= 0L
    val secondsLeft = ceil(remainingMs / 1_000.0).toInt().coerceAtLeast(0)

    val glow = MaterialTheme.colorScheme.primary.copy(alpha = 0.20f)
    val glow2 = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.14f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f))
            .drawBehind {
                drawCircle(
                    brush = Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width * 0.8f, size.height * 0.12f), radius = size.width * 0.85f),
                    radius = size.width * 0.85f,
                    center = Offset(size.width * 0.8f, size.height * 0.12f),
                )
                drawCircle(
                    brush = Brush.radialGradient(listOf(glow2, Color.Transparent), center = Offset(size.width * 0.15f, size.height * 0.85f), radius = size.width * 0.7f),
                    radius = size.width * 0.7f,
                    center = Offset(size.width * 0.15f, size.height * 0.85f),
                )
            }
            .safeDrawingPadding()
            .padding(horizontal = Space.l, vertical = Space.m),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Text(
                "MINDFUL PAUSE",
                style = EyebrowStyle,
                color = MaterialTheme.colorScheme.primary,
            )

            BreathingProgressRing(
                progress = progress,
                secondsLeft = secondsLeft,
                unlocked = unlocked,
            )

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Text(
                    "Opening ${plan.displayName}",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${ordinal(plan.nthOpenToday)} time today · ${formatDurationShort(plan.distractingMsToday)} / ${formatDurationShort(plan.budgetMs)} budget",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (plan.scheduleName != null || plan.focusBoostActive) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(Space.s, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    plan.scheduleName?.let { name ->
                        StatusPill(name, MaterialTheme.colorScheme.primary)
                    }
                    if (plan.focusBoostActive) {
                        StatusPill("Focus Boost", MaterialTheme.colorScheme.tertiary)
                    }
                }
            }

            if (plan.showBudgetComparison && plan.budgetMs > 0L) {
                val ratio = (plan.distractingMsToday.toFloat() / plan.budgetMs.toFloat()).coerceIn(0f, 1.5f)
                val over = plan.distractingMsToday > plan.budgetMs
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Today's distracting budget", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                if (over) "${formatDurationShort(plan.distractingMsToday - plan.budgetMs)} over" else "${formatDurationShort(plan.budgetMs - plan.distractingMsToday)} left",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondary,
                            )
                        }
                        LinearProgressIndicator(
                            progress = { ratio.coerceAtMost(1f) },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (over) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                    }
                }
            }

            if (plan.showIntentionChips) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(
                            "What brought you here?",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s),
                            verticalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            listOf(IntentionTag.REPLY, IntentionTag.LOOKUP, IntentionTag.UNWIND, IntentionTag.BORED).forEach { tag ->
                                FilterChip(
                                    selected = overlay.selectedIntention == tag,
                                    onClick = { onSelectIntention(tag) },
                                    label = { Text(tag.label) },
                                )
                            }
                        }
                    }
                }
            }

            if (plan.showTimerChips && plan.timerPresetsMin.isNotEmpty()) {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(
                            if (plan.encourageTimerSelection) "Set a soft check-in timer" else "Optional soft session timer",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(Space.s),
                            verticalArrangement = Arrangement.spacedBy(Space.xs),
                        ) {
                            plan.timerPresetsMin.forEach { min ->
                                FilterChip(
                                    selected = overlay.selectedTimerMin == min,
                                    onClick = { onSelectTimer(if (overlay.selectedTimerMin == min) null else min) },
                                    label = { Text("${min}m") },
                                )
                            }
                            FilterChip(
                                selected = overlay.selectedTimerMin == null,
                                onClick = { onSelectTimer(null) },
                                label = { Text("No timer") },
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Space.xs))

            Button(
                onClick = onGoHome,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(Space.s))
                Text("Go Home")
            }

            if (plan.redirectDisplayName != null) {
                FilledTonalButton(
                    onClick = onRedirect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.s))
                    Text("Open ${plan.redirectDisplayName} instead", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }

            OutlinedButton(
                onClick = onContinue,
                enabled = unlocked,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (unlocked) {
                        val timerNote = overlay.selectedTimerMin?.let { " · ${it}m timer" }.orEmpty()
                        "Continue to ${plan.displayName}$timerNote"
                    } else {
                        "Take a breath… ${secondsLeft}s"
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            TextButton(onClick = onSnooze1h) {
                Text(
                    "Pause friction for 1 hour",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun BreathingProgressRing(progress: Float, secondsLeft: Int, unlocked: Boolean) {
    val infinite = rememberInfiniteTransition(label = "breath")
    val pulse by infinite.animateFloat(
        initialValue = 0.86f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(tween(2_400), RepeatMode.Reverse),
        label = "breathPulse",
    )
    val trackColor = MaterialTheme.colorScheme.surfaceContainerHighest
    val activeColor = if (unlocked) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary
    val innerGlow = activeColor.copy(alpha = 0.14f)

    Box(Modifier.size(124.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 8.dp.toPx()
            val inset = stroke / 2f
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val innerRadius = (size.minDimension * 0.34f) * (if (unlocked) 1f else pulse)
            drawCircle(color = innerGlow, radius = innerRadius, center = center)
            drawArc(
                color = trackColor,
                startAngle = -90f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = activeColor,
                startAngle = -90f,
                sweepAngle = 360f * progress,
                useCenter = false,
                topLeft = Offset(inset, inset),
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        Text(
            text = if (unlocked) "Ready" else "${secondsLeft}s",
            style = if (unlocked) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

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
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.95f))
            .safeDrawingPadding()
            .padding(horizontal = Space.l, vertical = Space.m),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier
                .widthIn(max = 400.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            Column(
                modifier = Modifier.padding(Space.l),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Icon(
                    Icons.Outlined.HourglassBottom,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp),
                )
                Text("TIME CHECK-IN", style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
                Text(
                    "Your ${overlay.timerMinutes}m timer is up",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                )
                Text(
                    "${formatDurationShort(overlay.distractingMsToday)} / ${formatDurationShort(overlay.budgetMs)} distracting budget today",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.xs))
                Button(onClick = onGoHome, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.s))
                    Text("Done · Go Home")
                }
                FilledTonalButton(onClick = onExtend5m, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Outlined.Timer, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(Space.s))
                    Text("+5 min")
                }
                TextButton(onClick = onDismissSession, modifier = Modifier.fillMaxWidth()) {
                    Text("Continue without timer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

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
            .padding(horizontal = Space.m, vertical = Space.s),
        contentAlignment = Alignment.TopCenter,
    ) {
        Surface(
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            shadowElevation = 6.dp,
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .clickable(onClick = onDismiss),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = Space.m, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Outlined.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(22.dp),
                )
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    Text(
                        "${overlay.milestoneMinutes}m continuous distracting time",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (trail.isNotEmpty()) {
                        Text(
                            trail,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        Icons.Outlined.Close,
                        contentDescription = "Dismiss nudge",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
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
