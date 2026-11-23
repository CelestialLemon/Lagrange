package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.WorthTotals
import java.time.LocalDate
import kotlinx.serialization.Serializable

// Rabbit Hole Explorer and the occasional "Was that worth it?" check-in (spec §4.2 C, §5.4).

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

/**
 * The check-in's memory, kept on the device only: which episodes were offered on [date] (the logical day), and which
 * the user chose to skip. Skipped episodes are never offered again.
 */
@Serializable
data class CheckInLog(
    val date: String? = null,
    val shown: List<String> = emptyList(),
    val dismissed: List<String> = emptyList(),
)

data class CheckInRules(
    /** At most this many different episodes are offered per logical day (rated, skipped or ignored alike). */
    val maxPerDay: Int = 2,
    /** Only rabbit holes that ended this recently are worth asking about: memory of how it felt fades. */
    val recentMs: Long = 36 * HOUR_MS,
    /** Skipped keys kept; older ones fall off (they are long past [recentMs] by then). */
    val maxDismissed: Int = 200,
)

data class CheckInSelection(val episodeKey: String?, val log: CheckInLog) {
    fun changed(previous: CheckInLog): Boolean = log != previous
}

/**
 * Samples at most [CheckInRules.maxPerDay] unrated, recently ended rabbit holes per logical day. Pure and
 * deterministic: given the same log and candidates it always offers the same episode, so the Today and Lab cards
 * agree, and an offer stays put until it is rated or skipped.
 */
object WorthCheckInSampler {
    fun select(log: CheckInLog, today: LocalDate, candidates: List<LabEpisode>, nowMs: Long, dataEndMs: Long, rules: CheckInRules = CheckInRules()): CheckInSelection {
        val day = today.toString()
        val current = if (log.date == day) log else log.copy(date = day, shown = emptyList())
        val dismissed = current.dismissed.toHashSet()
        val eligible = candidates.filter { e ->
            e.isRabbitHole && e.worthItRating == null && !e.inProgress && e.endMs <= dataEndMs && e.endMs <= nowMs &&
                nowMs - e.endMs <= rules.recentMs && e.episodeKey !in dismissed
        }
        val byKey = eligible.associateBy { it.episodeKey }
        // Keep offering what is already on screen today until it is answered.
        current.shown.lastOrNull { it in byKey }?.let { return CheckInSelection(it, current) }
        if (current.shown.size >= rules.maxPerDay) return CheckInSelection(null, current)
        val pick = eligible.filter { it.episodeKey !in current.shown }
            .maxWithOrNull(compareBy<LabEpisode> { it.endMs }.thenBy { it.episodeKey })
            ?: return CheckInSelection(null, current)
        return CheckInSelection(pick.episodeKey, current.copy(shown = current.shown + pick.episodeKey))
    }

    /** "Not now": never offered again; it still counts toward today's cap. */
    fun dismiss(log: CheckInLog, episodeKey: String, rules: CheckInRules = CheckInRules()): CheckInLog =
        log.copy(dismissed = (log.dismissed.filterNot { it == episodeKey } + episodeKey).takeLast(rules.maxDismissed))
}
