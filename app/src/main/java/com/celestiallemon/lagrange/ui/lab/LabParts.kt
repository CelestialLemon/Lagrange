package com.celestiallemon.lagrange.ui.lab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.celestiallemon.lagrange.domain.lab.LabApps
import com.celestiallemon.lagrange.domain.lab.LabEpisode
import com.celestiallemon.lagrange.ui.components.Panel
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.components.WorthScale
import com.celestiallemon.lagrange.ui.formatClock
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.theme.NumberStyles
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone
import com.celestiallemon.lagrange.ui.theme.rise
import com.celestiallemon.lagrange.ui.today.badge
import com.celestiallemon.lagrange.ui.today.icon
import com.celestiallemon.lagrange.ui.today.tone
import com.celestiallemon.lagrange.ui.today.label

/** "Instagram → Chrome → YouTube" for a Lab episode. */
fun hopText(e: LabEpisode, apps: LabApps): String = apps.hopChain(e).joinToString(" → ") { apps.name(it) }

/** One rabbit hole: when, how long, the hop chain, why it counts, and an inline worth-it scale. */
@Composable
fun RabbitHoleCard(e: LabEpisode, apps: LabApps, onOpen: () -> Unit, onRate: (Int?) -> Unit) {
    Panel(modifier = Modifier.rise(1), onClick = onOpen, onClickLabel = "Open session", spacing = Spacing.s) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${formatDay(e.logicalDate)} · ${formatClock(e.startMs)}", style = NumberStyles.inline, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text(formatDuration(e.activeDurationMs) + if (e.inProgress) " · now" else "", style = NumberStyles.inline)
        }
        val chain = hopText(e, apps)
        if (chain.isNotEmpty()) Text(chain, style = MaterialTheme.typography.titleMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            Tag("${formatDuration(e.totalDistractingMs)} distracting", tone = Tone.Ember)
            Tag(e.triggerEvidence.badge, tone = e.triggerEvidence.tone, icon = e.triggerEvidence.icon)
            e.rabbitHoleReasons.forEach { Tag(shortReason(it.label)) }
        }
        WorthScale(e.worthItRating, onRate, compact = true)
    }
}

private fun shortReason(label: String): String = label.substringBefore(',').let { if (it.length > 32) it.take(30) + "…" else it }

/** A titled block of a Lab section: a header and its content, separated from the next by space, not a box. */
@Composable
fun LabBlock(title: String, modifier: Modifier = Modifier, supporting: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.foundation.layout.Column(modifier.rise(1), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        com.celestiallemon.lagrange.ui.components.SectionHeader(title, supporting = supporting, topPadding = Spacing.l)
        content()
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall)
}

/** "+12m" / "−5m". */
fun signedDuration(ms: Long): String = (if (ms >= 0) "+" else "−") + formatDuration(kotlin.math.abs(ms))
