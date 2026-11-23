package com.celestiallemon.lagrange.domain

/**
 * Re-attaches "Was that worth it?" ratings when derived sessions are rebuilt. A rating follows its exact
 * [EpisodeKey] first; a rating whose key no longer exists (the entry app changed because a hop was reclassified, say)
 * moves to the rebuilt unlocked episode that now contains its old start instant. Each rating lands at most once.
 */
object RatingCarryOver {
    data class OldRating(val episodeKey: String, val startMs: Long, val rating: Int)

    /** Returns episode index → rating. */
    fun reattach(old: List<OldRating>, episodes: List<Episode>): Map<Int, Int> {
        if (old.isEmpty()) return emptyMap()
        val out = HashMap<Int, Int>()
        val byKey = episodes.withIndex().associate { it.value.episodeKey to it.index }
        val leftovers = ArrayList<OldRating>()
        for (r in old) {
            val idx = byKey[r.episodeKey]
            if (idx != null && idx !in out) out[idx] = r.rating else leftovers += r
        }
        for (r in leftovers) {
            val idx = episodes.indices.firstOrNull { i ->
                val e = episodes[i]
                i !in out && !e.isGlanceOnly && r.startMs >= e.pickupAtMs && r.startMs <= e.endMs
            } ?: continue
            out[idx] = r.rating
        }
        return out
    }
}
