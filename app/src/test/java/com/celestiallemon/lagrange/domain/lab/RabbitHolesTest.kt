package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.HOUR_MS
import org.junit.Assert.assertEquals
import org.junit.Test

class RabbitHolesTest {
    private val base = 1_800_000_000_000L

    private val holes = listOf(
        episode(base, INSTA to 25, rabbit = true, reasons = listOf(RabbitHoleReason.LONG_BINGE), rating = 1, key = "a"),
        episode(base + HOUR_MS, MAPS to 2, TIKTOK to 10, YOUTUBE to 12, rabbit = true, reasons = listOf(RabbitHoleReason.MULTI_APP_SPIRAL, RabbitHoleReason.HIJACKED_PICKUP), trigger = TriggerEvidence.OBSERVED_CLICK, key = "b"),
        episode(base + 2 * HOUR_MS, YOUTUBE to 30, rabbit = true, reasons = listOf(RabbitHoleReason.LONG_BINGE), rating = 5, key = "c"),
        episode(base + 3 * HOUR_MS, INSTA to 3, key = "not-a-hole"),
    )

    @Test
    fun `the feed keeps rabbit holes only, newest first, with facets from the window`() {
        val feed = RabbitHoleExplorer.feed(holes, RabbitFilter(), testApps)
        assertEquals(listOf("c", "b", "a"), feed.episodes.map { it.episodeKey })
        assertEquals(3, feed.total)
        assertEquals(listOf(TriggerEvidence.OBSERVED_CLICK, TriggerEvidence.NO_OBSERVED_TRIGGER), feed.facets.triggers)
        assertEquals(RabbitHoleReason.entries, feed.facets.reasons)
        assertEquals(YOUTUBE, feed.facets.apps.first())
        assertEquals(1, feed.worth.valuedCount)
        assertEquals(1, feed.worth.regrettedCount)
        assertEquals(1, feed.worth.unratedCount)
    }

    @Test
    fun `filters narrow by app anywhere in the chain, trigger, reason and rating`() {
        fun keys(f: RabbitFilter) = RabbitHoleExplorer.feed(holes, f, testApps).episodes.map { it.episodeKey }
        assertEquals(listOf("c", "b"), keys(RabbitFilter(pkgId = YOUTUBE)))
        assertEquals(listOf("b"), keys(RabbitFilter(pkgId = MAPS)))
        assertEquals(listOf("b"), keys(RabbitFilter(trigger = TriggerEvidence.OBSERVED_CLICK)))
        assertEquals(listOf("b"), keys(RabbitFilter(reason = RabbitHoleReason.HIJACKED_PICKUP)))
        assertEquals(listOf("b"), keys(RabbitFilter(rating = RatingFilter.UNRATED)))
        assertEquals(listOf("a"), keys(RabbitFilter(rating = RatingFilter.REGRETTED)))
        assertEquals(listOf("c"), keys(RabbitFilter(rating = RatingFilter.VALUED)))
        assertEquals(emptyList<String>(), keys(RabbitFilter(pkgId = INSTA, rating = RatingFilter.VALUED)))
    }
}
