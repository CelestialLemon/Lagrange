package com.celestiallemon.lagrange.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.ui.EpisodeUi
import com.celestiallemon.lagrange.ui.SessionDetail
import com.celestiallemon.lagrange.ui.formatClock
import com.celestiallemon.lagrange.ui.formatClockSeconds
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.theme.EyebrowStyle
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space

private val ratingWords = listOf("Regret it", "Mostly not", "Meh", "Mostly yes", "Worth it")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionDetailSheet(
    episode: EpisodeUi,
    packages: Map<Long, PackageEntity>,
    detail: SessionDetail?,
    onRate: (Int?) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = false)
    val s = episode.session
    val archetype = archetypeOf(s.archetype)
    val trigger = triggerOf(s.triggerEvidence)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.l)
                .navigationBarsPadding()
                .padding(bottom = Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            Column {
                Text(
                    "${formatClock(s.startMs)} – ${formatClock(s.endMs)}" + if (episode.isInProgress) " · in progress" else "",
                    style = EyebrowStyle,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "${archetype?.label ?: s.archetype} · ${formatDuration(s.activeDurationMs)}",
                    style = MaterialTheme.typography.headlineSmall,
                )
                val chain = hopChain(episode, packages)
                if (chain.isNotEmpty()) {
                    Text(chain, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (!s.isGlanceOnly) RatingRow(s.worthItRating, onRate)

            if (s.isRabbitHole) {
                Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.tertiaryContainer) {
                    Column(Modifier.padding(Space.m).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        Text("Why this is a rabbit hole", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        rabbitReasonsOf(s.rabbitHoleReasons).forEach {
                            Text("• ${it.label}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                        }
                    }
                }
            }

            // Trigger evidence.
            if (trigger != null) {
                SheetSection("What started it") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(trigger.icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.secondary)
                        Spacer(Modifier.width(Space.s))
                        Text(trigger.badge, style = MaterialTheme.typography.titleSmall)
                        s.triggerPkgId?.let { packages[it] }?.let {
                            Text(" · ${it.displayName}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text(trigger.explanation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    detail?.triggerNotification?.let { n ->
                        Text(
                            "Notification posted ${formatClockSeconds(n.postedAtMs)}" + (n.removedAtMs?.let { " · cleared ${formatClockSeconds(it)}" } ?: ""),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            // Segments.
            SheetSection("Apps in this session") {
                episode.segments.forEach { seg ->
                    val pkg = packages[seg.pkgId]
                    val cat = pkg?.category?.let { c -> AppCategory.entries.firstOrNull { it.name == c } } ?: AppCategory.NEUTRAL
                    val segTrigger = triggerOf(seg.segmentTriggerEvidence)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(categoryColor(cat)))
                        Spacer(Modifier.width(Space.s))
                        Column(Modifier.weight(1f)) {
                            Text(pkg?.displayName ?: "Unknown app", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                formatClockSeconds(seg.startMs) + (segTrigger?.let { " · ${it.badge}" } ?: "") +
                                    if (seg.isBlockedAttempt) " · blocked" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(formatDuration(seg.durationMs), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }

            // Blocked attempts.
            val blocked = detail?.blockedAttempts.orEmpty()
            if (s.blockedAttemptCount > 0 || blocked.isNotEmpty()) {
                SheetSection("Blocked attempts") {
                    if (blocked.isEmpty()) {
                        Text("${s.blockedAttemptCount} launch(es) were stopped by friction.", style = MaterialTheme.typography.bodyMedium)
                    }
                    blocked.forEach { f ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Block, null, Modifier.size(18.dp), tint = LocalStatusColors.current.good)
                            Spacer(Modifier.width(Space.s))
                            Text(
                                "${formatClockSeconds(f.timestampUtcMs)} · ${packages[f.pkgId]?.displayName ?: "app"} · ${f.pauseDurationSec}s pause · ${f.outcome.lowercase().replace('_', ' ')}",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }

            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val c = LocalStatusColors.current
                MiniBadge("${s.physicalPickupCount} pickup${if (s.physicalPickupCount == 1) "" else "s"}", c.neutral)
                MiniBadge("${s.distinctAppCount} app${if (s.distinctAppCount == 1) "" else "s"}", c.neutral)
                if (s.totalDistractingMs > 0) MiniBadge("${formatDuration(s.totalDistractingMs)} distracting", MaterialTheme.colorScheme.tertiary)
                if (episode.hasUncertainTail) MiniBadge("End time uncertain (screen-off not logged)", c.warn)
            }
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Text(title.uppercase(), style = EyebrowStyle, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/** "Was that worth it?" 1–5. Tapping the current rating again clears it. */
@Composable
private fun RatingRow(current: Int?, onRate: (Int?) -> Unit) {
    Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(Space.m).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Was that worth it?", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.Center) {
                (1..5).forEach { n ->
                    val filled = current != null && n <= current
                    IconToggleButton(
                        checked = current == n,
                        onCheckedChange = { onRate(if (current == n) null else n) },
                        modifier = Modifier.semantics {
                            contentDescription = "Rate $n of 5: ${ratingWords[n - 1]}"
                            stateDescription = if (current == n) "Selected" else "Not selected"
                        },
                    ) {
                        Icon(
                            if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                            null,
                            tint = if (filled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                            modifier = Modifier.size(32.dp),
                        )
                    }
                }
            }
            Text(
                current?.let { ratingWords[it - 1] } ?: "Your answer stays attached to this session, even when history is rebuilt.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
