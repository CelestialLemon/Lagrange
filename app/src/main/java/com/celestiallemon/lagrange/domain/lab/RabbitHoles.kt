package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.WorthTotals

// Rabbit Hole Explorer (spec §4.2 C).

enum class RatingFilter(val label: String) {
    ALL("All"),
    UNRATED("Unrated"),
    REGRETTED("Regretted"),
    VALUED("Valued");

    fun matches(rating: Int?): Boolean = when (this) {
        ALL -> true
        UNRATED -> WorthTotals.bucketOf(rating) == WorthTotals.Companion.Bucket.UNRATED
        REGRETTED -> WorthTotals.bucketOf(rating) == WorthTotals.Companion.Bucket.REGRETTED
        VALUED -> WorthTotals.bucketOf(rating) == WorthTotals.Companion.Bucket.VALUED
    }
}

/** Every field narrows the feed; null means "any". */
data class RabbitFilter(
    val pkgId: Long? = null,
    val trigger: TriggerEvidence? = null,
    val reason: RabbitHoleReason? = null,
    val rating: RatingFilter = RatingFilter.ALL,
) {
    val isDefault: Boolean get() = this == RabbitFilter()
}

/** What the filters can offer: only values that occur in the window, so no filter ever leads to a guaranteed empty feed. */
data class RabbitFacets(
    val apps: List<Long>,
    val triggers: List<TriggerEvidence>,
    val reasons: List<RabbitHoleReason>,
)

data class RabbitHoleFeed(
    /** Rabbit holes matching the filter, newest first. */
    val episodes: List<LabEpisode>,
    /** Every rabbit hole in the window (before filtering). */
    val total: Int,
    val facets: RabbitFacets,
    /** Self-report over the filtered feed (by active time). */
    val worth: WorthTotals,
    val distractingMs: Long,
)

object RabbitHoleExplorer {
    /** [episodes] may contain any unlocked episodes; only rabbit holes are kept. */
    fun feed(episodes: List<LabEpisode>, filter: RabbitFilter, apps: LabApps): RabbitHoleFeed {
        val all = episodes.filter { it.isRabbitHole }.sortedByDescending { it.startMs }
        val kept = all.filter { matches(it, filter, apps) }
        val appCounts = all.flatMap { e -> apps.hopChain(e).distinct() }.groupingBy { it }.eachCount()
        val facets = RabbitFacets(
            apps = appCounts.entries.sortedWith(compareByDescending<Map.Entry<Long, Int>> { it.value }.thenBy { apps.name(it.key).lowercase() }).map { it.key },
            triggers = TriggerEvidence.entries.filter { t -> all.any { it.triggerEvidence == t } },
            reasons = RabbitHoleReason.entries.filter { r -> all.any { r in it.rabbitHoleReasons } },
        )
        return RabbitHoleFeed(
            episodes = kept,
            total = all.size,
            facets = facets,
            worth = WorthTotals.of(kept.map { it.worthItRating to it.activeDurationMs }),
            distractingMs = kept.sumOf { it.totalDistractingMs },
        )
    }

    fun matches(e: LabEpisode, f: RabbitFilter, apps: LabApps): Boolean =
        e.isRabbitHole &&
            (f.pkgId == null || apps.realSegments(e).any { it.pkgId == f.pkgId }) &&
            (f.trigger == null || e.triggerEvidence == f.trigger) &&
            (f.reason == null || f.reason in e.rabbitHoleReasons) &&
            f.rating.matches(e.worthItRating)
}
