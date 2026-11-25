package com.celestiallemon.lagrange.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.PauseCircleOutline
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.ui.EpisodeUi
import com.celestiallemon.lagrange.ui.SessionDetail
import com.celestiallemon.lagrange.ui.components.Banner
import com.celestiallemon.lagrange.ui.components.DetailSheet
import com.celestiallemon.lagrange.ui.components.Dot
import com.celestiallemon.lagrange.ui.components.LeadingIcon
import com.celestiallemon.lagrange.ui.components.ListRow
import com.celestiallemon.lagrange.ui.components.RowGroup
import com.celestiallemon.lagrange.ui.components.Segment
import com.celestiallemon.lagrange.ui.components.SplitMeter
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.formatClock
import com.celestiallemon.lagrange.ui.formatClockSeconds
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.friction.categoryColor
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone

/** One session, explained: when, what it was, what started it, and which apps. */
@Composable
fun SessionDetailSheet(
    episode: EpisodeUi,
    packages: Map<Long, PackageEntity>,
    detail: SessionDetail?,
    onDismiss: () -> Unit,
) {
    val s = episode.session
    val archetype = archetypeOf(s.archetype)
    val trigger = triggerOf(s.triggerEvidence)
    DetailSheet(onDismiss) {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Text(
                "${formatClock(s.startMs)} – ${formatClock(s.endMs)}" + if (episode.isInProgress) " · in progress" else "",
                style = NumberStyles.inline,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("${archetype?.label ?: s.archetype} · ${formatDuration(s.activeDurationMs)}", style = MaterialTheme.typography.headlineSmall)
            val chain = hopChain(episode, packages)
            if (chain.isNotEmpty()) Text(chain, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (s.isRabbitHole) {
            Banner(
                "Why this is a rabbit hole",
                Tone.Ember,
                Icons.Outlined.Cyclone,
                body = rabbitReasonsOf(s.rabbitHoleReasons).joinToString("\n") { "• ${it.label}" },
            )
        }

        if (trigger != null) {
            SheetSection("What started it") {
                RowGroup {
                    ListRow(
                        headline = trigger.badge + (s.triggerPkgId?.let { packages[it] }?.let { " · ${it.displayName}" } ?: ""),
                        supporting = trigger.explanation + (
                            detail?.triggerNotification?.let { n ->
                                "\nPosted ${formatClockSeconds(n.postedAtMs)}" + (n.removedAtMs?.let { " · cleared ${formatClockSeconds(it)}" } ?: "")
                            } ?: ""
                            ),
                        supportingLines = 5,
                        leading = { LeadingIcon(trigger.icon, trigger.tone) },
                    )
                }
            }
        }

        SheetSection("Apps in this session") {
            SplitMeter(
                episode.segments.map { seg -> Segment(seg.durationMs.toFloat(), categoryColor(packages[seg.pkgId].categoryOrNeutral())) },
                height = 10.dp,
            )
            episode.segments.forEach { seg ->
                val pkg = packages[seg.pkgId]
                val segTrigger = triggerOf(seg.segmentTriggerEvidence)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
                    Dot(categoryColor(pkg.categoryOrNeutral()))
                    Spacer(Modifier.width(Spacing.m))
                    Column(Modifier.weight(1f)) {
                        Text(pkg?.displayName ?: "Unknown app", style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            formatClockSeconds(seg.startMs) + (segTrigger?.let { " · ${it.badge}" } ?: "") + if (seg.isBlockedAttempt) " · paused" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(formatDuration(seg.durationMs), style = NumberStyles.inline)
                }
            }
        }

        val blocked = detail?.blockedAttempts.orEmpty()
        if (s.blockedAttemptCount > 0 || blocked.isNotEmpty()) {
            SheetSection("Launch pauses") {
                if (blocked.isEmpty()) {
                    Text("${s.blockedAttemptCount} open(s) were stopped at a pause.", style = MaterialTheme.typography.bodyMedium)
                }
                RowGroup {
                    blocked.forEach { f ->
                        ListRow(
                            headline = packages[f.pkgId]?.displayName ?: "App",
                            supporting = "${formatClockSeconds(f.timestampUtcMs)} · ${f.pauseDurationSec}s pause · ${f.outcome.lowercase().replace('_', ' ')}",
                            leading = { LeadingIcon(Icons.Outlined.PauseCircleOutline, Tone.Good) },
                        )
                    }
                }
            }
        }

        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Tag("${s.physicalPickupCount} pickup${if (s.physicalPickupCount == 1) "" else "s"}")
            Tag("${s.distinctAppCount} app${if (s.distinctAppCount == 1) "" else "s"}")
            if (s.totalDistractingMs > 0) Tag("${formatDuration(s.totalDistractingMs)} distracting", tone = Tone.Ember)
            if (episode.hasUncertainTail) Tag("End time uncertain", tone = Tone.Caution)
        }
    }
}

@Composable
private fun SheetSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        content()
    }
}
