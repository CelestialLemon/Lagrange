package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.Archetype
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.TriggerEvidence

// Plain-Kotlin inputs and outputs of the session reconstructor. Nothing here touches Android or Room, so every rule
// runs (and is tested) on the JVM.

/** A half-open span of time, `[startMs, endMs)`. */
data class Span(val startMs: Long, val endMs: Long) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)

    /** How much of this span falls inside `[fromMs, toMs)`. */
    fun overlapMs(fromMs: Long, toMs: Long): Long = (minOf(endMs, toMs) - maxOf(startMs, fromMs)).coerceAtLeast(0)

    fun clip(fromMs: Long, toMs: Long): Span? {
        val s = maxOf(startMs, fromMs)
        val e = minOf(endMs, toMs)
        return if (e > s) Span(s, e) else null
    }
}

object Spans {
    /** Length of the union of [spans] inside `[fromMs, toMs)`; overlapping spans are counted once. */
    fun coveredMs(spans: List<Span>, fromMs: Long, toMs: Long): Long {
        if (toMs <= fromMs) return 0
        var total = 0L
        var cursor = fromMs
        for (s in spans.mapNotNull { it.clip(fromMs, toMs) }.sortedBy { it.startMs }) {
            val start = maxOf(s.startMs, cursor)
            if (s.endMs > start) {
                total += s.endMs - start
                cursor = s.endMs
            }
        }
        return total
    }

    fun contains(spans: List<Span>, atMs: Long): Boolean = spans.any { atMs >= it.startMs && atMs < it.endMs }
}

/** One raw `UsageEvents.Event`, with [order] (the ledger row id) breaking ties inside a millisecond. */
data class UsageEvent(val timestampMs: Long, val type: Int, val pkgId: Long, val clsId: Long, val order: Long = 0)

/** What the reconstructor needs to know about a package: its category (applied retroactively) and whether it is a system surface. */
data class AppInfo(val pkgId: Long, val packageName: String, val category: AppCategory, val isSystemSurface: Boolean = false)

/** Notification metadata (never content). */
data class NotificationMeta(
    val id: Long,
    val pkgId: Long,
    val postedAtMs: Long,
    val removedAtMs: Long?,
    val removalReason: RemovalReason,
    val importance: Int = 3,
    val isOngoing: Boolean = false,
    val isForegroundService: Boolean = false,
    val isGroupSummary: Boolean = false,
    val isAmbient: Boolean = false,
    val matchedInterruptionFilter: Boolean = true,
    val dndOrSilentAtPost: Boolean = false,
) {
    /** Ongoing, foreground-service and group-summary notifications never count as triggers. */
    val isTriggerEligible: Boolean get() = !isOngoing && !isForegroundService && !isGroupSummary

    /** Could plausibly have buzzed, rung or lit the screen. */
    val isAlerting: Boolean
        get() = isTriggerEligible && importance >= IMPORTANCE_DEFAULT && matchedInterruptionFilter && !isAmbient && !dndOrSilentAtPost

    private companion object {
        const val IMPORTANCE_DEFAULT = 3
    }
}

/** A friction log row, as far as reconstruction cares. */
data class FrictionMeta(
    val timestampMs: Long,
    val pkgId: Long,
    val eventKind: String,
    val pauseDurationSec: Int,
    val postCountdownReactionMs: Long?,
    val outcome: String,
)

/** A collector coverage interval with its end resolved (open intervals end "now"). */
data class CoverageSpan(val kind: ServiceKind, val status: CoverageStatus, val startMs: Long, val endMs: Long) {
    val span: Span get() = Span(startMs, endMs)
}

data class ReconstructionInput(
    val events: List<UsageEvent>,
    val apps: Map<Long, AppInfo>,
    /** Class ids whose name ends in `CustomTabActivity`. */
    val customTabClassIds: Set<Long> = emptySet(),
    val notifications: List<NotificationMeta> = emptyList(),
    val friction: List<FrictionMeta> = emptyList(),
    val coverage: List<CoverageSpan> = emptyList(),
    /** The ledger is complete up to here (the last harvest). Anything still open at this instant is in progress. */
    val dataEndMs: Long,
)

/** Tunable thresholds. The defaults are the spec's; Advanced Settings may expose some later. */
data class ReconstructionRules(
    val microLockMergeMs: Long = 15_000,
    val callMergeMaxGapMs: Long = 2 * HOUR_MS,
    val transientHopMs: Long = 10_000,
    val customTabLaunchMs: Long = 2_000,
    /** Two activities of one app this close together (splash → main) are one open, not a reopen. */
    val activityHandoffMs: Long = 1_000,
    /** An interval with no closing event is never credited more than this past its last observed event. */
    val uncertainTailCapMs: Long = 30 * MINUTE_MS,
    val clickBeforeMs: Long = 5_000,
    val clickAfterMs: Long = 15_000,
    val appCancelAfterMs: Long = 10_000,
    val alertBeforeMs: Long = 60_000,
    val pendingMinAgeMs: Long = 60_000,
    val frictionMatchMs: Long = 5_000,
    val blockedGraceMs: Long = 2_000,
    val microCheckMs: Long = 30_000,
    val utilityShare: Double = 0.90,
    val utilityMaxDistractingMs: Long = 60_000,
    val longBingeMs: Long = 20 * MINUTE_MS,
    val spiralMs: Long = 10 * MINUTE_MS,
    val spiralDistinctApps: Int = 2,
    val hijackMs: Long = 10 * MINUTE_MS,
    val hijackShare: Double = 0.60,
    val focusedShare: Double = 0.85,
    val callPackages: Set<String> = setOf(
        "com.google.android.dialer",
        "com.android.dialer",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.samsung.android.incallui",
    ),
    /** Always treated as transient hops, in addition to packages flagged as launcher / system UI. */
    val transientPackages: Set<String> = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.intentresolver",
        "com.google.android.intentresolver",
    ),
)

data class CategoryTotals(
    val distractingMs: Long = 0,
    val neutralMs: Long = 0,
    val utilityMs: Long = 0,
    val excludedMs: Long = 0,
    val selfAppMs: Long = 0,
) {
    val categorizedMs: Long get() = distractingMs + neutralMs + utilityMs + excludedMs + selfAppMs

    fun plus(category: AppCategory, ms: Long): CategoryTotals = when (category) {
        AppCategory.DISTRACTING -> copy(distractingMs = distractingMs + ms)
        AppCategory.NEUTRAL -> copy(neutralMs = neutralMs + ms)
        AppCategory.UTILITY -> copy(utilityMs = utilityMs + ms)
        AppCategory.EXCLUDED -> copy(excludedMs = excludedMs + ms)
        AppCategory.SELF_APP -> copy(selfAppMs = selfAppMs + ms)
    }
}

/**
 * One app's stretch of foreground time inside an episode. [spans] can be more than one piece when the stretch
 * crossed a merged micro-lock (the screen-off gap is never counted). [blockedMs] is the launch-pause time scrubbed
 * from a blocked attempt; it is taken from the start of the segment, where the pause sits.
 */
data class Segment(
    val pkgId: Long,
    val spans: List<Span>,
    val isBlockedAttempt: Boolean = false,
    val blockedMs: Long = 0,
    val triggerEvidence: TriggerEvidence? = null,
    val triggerPkgId: Long? = null,
) {
    val startMs: Long get() = spans.first().startMs
    val endMs: Long get() = spans.last().endMs
    val durationMs: Long get() = spans.sumOf { it.durationMs }

    /** Real use: the foreground time left after scrubbing launch-pause time. */
    val effectiveMs: Long get() = (durationMs - blockedMs).coerceAtLeast(0)

    /** The pieces of [spans] that count as real use (the first [blockedMs] removed). */
    fun effectiveSpans(): List<Span> {
        var skip = blockedMs
        if (skip <= 0) return spans
        val out = ArrayList<Span>(spans.size)
        for (s in spans) {
            if (skip >= s.durationMs) {
                skip -= s.durationMs
                continue
            }
            out += Span(s.startMs + skip, s.endMs)
            skip = 0
        }
        return out
    }
}

/**
 * A behavioral episode: one unlocked stretch of phone use (with micro-locks and call screen-offs merged in), or a
 * lockscreen-only glance.
 */
data class Episode(
    val startMs: Long,
    val endMs: Long,
    /** When the screen first came on for this episode (can precede [startMs], the unlock). */
    val pickupAtMs: Long,
    /** Screen-on time that counts: unlocked spans for episodes, the lit lockscreen for glances. Never includes gaps. */
    val activeSpans: List<Span>,
    val activeDurationMs: Long,
    val physicalPickupCount: Int,
    val isGlanceOnly: Boolean,
    val triggerEvidence: TriggerEvidence,
    val triggerPkgId: Long?,
    val triggerNotificationId: Long?,
    val entryPkgId: Long?,
    val segments: List<Segment>,
    val totals: CategoryTotals,
    val blockedAttemptCount: Int,
    val distinctAppCount: Int,
    val distinctDistractingCount: Int,
    val archetype: Archetype,
    val rabbitHoleReasons: List<RabbitHoleReason>,
    /** The end was estimated (no closing event; capped conservatively). */
    val uncertainTail: Boolean = false,
    /** Still open when the ledger ends (the episode is happening now). */
    val inProgress: Boolean = false,
) {
    val isRabbitHole: Boolean get() = rabbitHoleReasons.isNotEmpty()
    val episodeKey: String get() = EpisodeKey.of(startMs, entryPkgId, isGlanceOnly)

    /** `context_flags`: comma-separated reconstruction notes, or null. */
    val contextFlags: String?
        get() = listOfNotNull(
            "UNCERTAIN_TAIL".takeIf { uncertainTail },
            "IN_PROGRESS".takeIf { inProgress },
        ).joinToString(",").ifEmpty { null }
}

/**
 * Deterministic episode identity: the start instant plus the entry app. Self-reports (`worth_it_rating`) are keyed
 * by it, so a rebuild that reproduces the same episode re-attaches the rating.
 */
object EpisodeKey {
    fun of(startMs: Long, entryPkgId: Long?, glance: Boolean): String =
        if (glance) "$startMs:g" else "$startMs:${entryPkgId ?: 0}"
}

const val MINUTE_MS = 60_000L
const val HOUR_MS = 60 * MINUTE_MS
const val DAY_MS = 24 * HOUR_MS
