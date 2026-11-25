package com.celestiallemon.lagrange.ui.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.ui.EpisodeUi
import com.celestiallemon.lagrange.ui.components.Tag
import com.celestiallemon.lagrange.ui.theme.Spacing
import com.celestiallemon.lagrange.ui.theme.Tone

/** "Instagram → Chrome → YouTube", collapsing repeats and leaving out system surfaces. */
fun hopChain(ep: EpisodeUi, packages: Map<Long, PackageEntity>): String {
    val names = ArrayList<String>()
    for (seg in ep.segments) {
        val p = packages[seg.pkgId]
        if (p?.isLauncherOrSystemUi == true) continue
        val name = p?.displayName ?: continue
        if (names.lastOrNull() != name) names += name
    }
    return names.joinToString(" → ")
}

/** Notification-led starts are information, not a verdict, so they get the brand tone; self-started stays neutral. */
val TriggerEvidence.tone: Tone
    get() = when {
        isNotificationLed -> Tone.Brand
        this == TriggerEvidence.PARTIAL_COVERAGE -> Tone.Caution
        else -> Tone.Neutral
    }

val DayStatus.tone: Tone
    get() = when (this) {
        DayStatus.NORMAL -> Tone.Neutral
        DayStatus.SPIKE -> Tone.Ember
        DayStatus.GOOD_DAY -> Tone.Good
        DayStatus.INSUFFICIENT_BASELINE -> Tone.Neutral
        DayStatus.UNCERTAIN_COVERAGE -> Tone.Caution
    }

/** The tags under a session: what started it, paused opens, and anything uncertain. */
@Composable
fun SessionTags(ep: EpisodeUi, modifier: Modifier = Modifier) {
    val s = ep.session
    val trigger = triggerOf(s.triggerEvidence)
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Spacing.xs), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        if (s.isRabbitHole && archetypeOf(s.archetype) != com.celestiallemon.lagrange.data.db.Archetype.RABBIT_HOLE) Tag("Rabbit hole", tone = Tone.Ember)
        if (trigger != null && trigger != TriggerEvidence.NO_OBSERVED_TRIGGER) Tag(trigger.badge, tone = trigger.tone, icon = trigger.icon)
        if (s.blockedAttemptCount > 0) Tag("${s.blockedAttemptCount} paused", tone = Tone.Good)
        if (ep.hasUncertainTail) Tag("End uncertain", tone = Tone.Caution)
    }
}
