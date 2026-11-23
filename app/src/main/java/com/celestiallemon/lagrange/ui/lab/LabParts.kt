package com.celestiallemon.lagrange.ui.lab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Cyclone
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.celestiallemon.lagrange.data.CheckInCard
import com.celestiallemon.lagrange.domain.lab.LabApps
import com.celestiallemon.lagrange.domain.lab.LabEpisode
import com.celestiallemon.lagrange.ui.components.SectionCard
import com.celestiallemon.lagrange.ui.formatClock
import com.celestiallemon.lagrange.ui.formatDay
import com.celestiallemon.lagrange.ui.formatDuration
import com.celestiallemon.lagrange.ui.theme.LocalStatusColors
import com.celestiallemon.lagrange.ui.theme.Space
import com.celestiallemon.lagrange.ui.today.MiniBadge
import com.celestiallemon.lagrange.ui.today.badge
import com.celestiallemon.lagrange.ui.today.isNotificationLed
import com.celestiallemon.lagrange.ui.today.label
import com.celestiallemon.lagrange.ui.today.ratingWords
import java.time.LocalDate

/** "Instagram → Chrome → YouTube" for a Lab episode. */
fun hopText(e: LabEpisode, apps: LabApps): String = apps.hopChain(e).joinToString(" → ") { apps.name(it) }

/**
 * The calm "Was that worth it?" card, on Today and in the Lab. Never more than a couple a day; "Not now" puts that
 * episode away for good.
 */
@Composable
fun WorthCheckInCard(card: CheckInCard, onRate: (Int) -> Unit, onDismiss: () -> Unit, onOpen: (() -> Unit)?, modifier: Modifier = Modifier) {
    val e = card.episode
    SectionCard(
        title = "Was that worth it?",
        eyebrow = "Quick check-in",
        icon = Icons.Outlined.Cyclone,
        container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f),
        modifier = modifier,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(MaterialTheme.shapes.medium)
                .then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open this session", onClick = onOpen) else Modifier)
                .padding(vertical = Space.xs),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Text(
                "${dayPrefix(e.logicalDate, null)}${formatClock(e.startMs)} · ${formatDuration(e.activeDurationMs)}",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val chain = hopText(e, card.apps)
            if (chain.isNotEmpty()) Text(chain, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                "No wrong answers. Your rating stays with this session and shapes the Worth-it view.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            InlineRating(current = null, onRate = { it?.let(onRate) }, modifier = Modifier.weight(1f))
            TextButton(onClick = onDismiss) { Text("Not now") }
        }
    }
}

private fun dayPrefix(date: LocalDate, today: LocalDate?): String = if (today != null && date == today) "" else "${formatDay(date)} · "

/** Five compact stars; tapping the current one clears it. */
@Composable
fun InlineRating(current: Int?, onRate: (Int?) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        (1..5).forEach { n ->
            val filled = current != null && n <= current
            IconButton(
                onClick = { onRate(if (current == n) null else n) },
                modifier = Modifier
                    .size(40.dp)
                    .semantics {
                        contentDescription = "Rate $n of 5: ${ratingWords[n - 1]}"
                        stateDescription = if (current == n) "Selected" else "Not selected"
                    },
            ) {
                Icon(
                    if (filled) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    null,
                    tint = if (filled) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.outline,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** One rabbit hole: trigger, active vs distracting time, the hop chain, and an inline rating. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RabbitHoleCard(e: LabEpisode, apps: LabApps, onOpen: () -> Unit, onRate: (Int?) -> Unit) {
    val colors = LocalStatusColors.current
    val shape = RoundedCornerShape(24.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.4f),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.tertiary.copy(alpha = 0.35f), shape)
            .clip(shape),
    ) {
        Column(Modifier.padding(Space.m), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(MaterialTheme.shapes.medium)
                    .clickable(onClick = onOpen),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${formatDay(e.logicalDate)} · ${formatClock(e.startMs)}",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatDuration(e.activeDurationMs) + if (e.inProgress) " · now" else "",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val chain = hopText(e, apps)
                if (chain.isNotEmpty()) Text(chain, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MiniBadge(e.triggerEvidence.badge, if (e.triggerEvidence.isNotificationLed) MaterialTheme.colorScheme.secondary else colors.neutral)
                    MiniBadge("${formatDuration(e.totalDistractingMs)} distracting", MaterialTheme.colorScheme.tertiary)
                    e.rabbitHoleReasons.forEach { MiniBadge(shortReason(it.label), colors.neutral) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    e.worthItRating?.let { ratingWords.getOrNull(it - 1) } ?: "Worth it?",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                InlineRating(e.worthItRating, onRate)
            }
        }
    }
}

private fun shortReason(label: String): String = label.substringBefore(',').let { if (it.length > 32) it.take(30) + "…" else it }

/** A label, a thin proportional bar and a value. */
@Composable
fun BarRow(label: String, value: String, fraction: Float, color: Color = MaterialTheme.colorScheme.primary, caption: String? = null, onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clip(MaterialTheme.shapes.small).clickable(onClick = onClick) else Modifier)
            .padding(vertical = 2.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            Text(value, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                    .clip(CircleShape)
                    .background(color),
            )
        }
        if (caption != null) Text(caption, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier)
}

@Composable
fun SubHeading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** "+12m" / "−5m". */
fun signedDuration(ms: Long): String = (if (ms >= 0) "+" else "−") + formatDuration(kotlin.math.abs(ms))
