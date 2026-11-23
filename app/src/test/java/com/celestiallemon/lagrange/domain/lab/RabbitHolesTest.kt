package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.HOUR_MS
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    // -- Check-in sampler ------------------------------------------------------------------------------------------------

    private val today = LocalDate.of(2026, 11, 24)
    private val now = base + 10 * HOUR_MS

    private fun candidates() = listOf(
        episode(base, INSTA to 25, rabbit = true, key = "old"),
        episode(base + 4 * HOUR_MS, TIKTOK to 25, rabbit = true, key = "mid"),
        episode(base + 8 * HOUR_MS, YOUTUBE to 25, rabbit = true, key = "new"),
    )

    @Test
    fun `offers the most recent unrated rabbit hole and keeps offering it until answered`() {
        val first = WorthCheckInSampler.select(CheckInLog(), today, candidates(), now, now)
        assertEquals("new", first.episodeKey)
        assertEquals(listOf("new"), first.log.shown)
        assertEquals(today.toString(), first.log.date)
        val again = WorthCheckInSampler.select(first.log, today, candidates(), now, now)
        assertEquals("new", again.episodeKey)
        assertEquals(first.log, again.log)
    }

    @Test
    fun `at most two different episodes a day, rated or skipped alike`() {
        var log = WorthCheckInSampler.select(CheckInLog(), today, candidates(), now, now).log
        // "new" gets rated → the next most recent is offered.
        val rated = candidates().map { if (it.episodeKey == "new") it.copy(worthItRating = 4) else it }
        val second = WorthCheckInSampler.select(log, today, rated, now, now)
        assertEquals("mid", second.episodeKey)
        log = WorthCheckInSampler.dismiss(second.log, "mid")
        // Cap reached: "old" is still unrated but nothing more is offered today.
        val third = WorthCheckInSampler.select(log, today, rated, now, now)
        assertNull(third.episodeKey)
        // A new logical day resets the cap but never brings back a skipped episode.
        val tomorrow = WorthCheckInSampler.select(third.log, today.plusDays(1), rated, now, now)
        assertEquals("old", tomorrow.episodeKey)
        assertEquals(listOf("old"), tomorrow.log.shown)
        assertTrue("mid" in tomorrow.log.dismissed)
    }

    @Test
    fun `skips episodes that are in progress, not yet derived, stale, or not rabbit holes`() {
        val list = listOf(
            episode(now - HOUR_MS, INSTA to 25, rabbit = true, inProgress = true, key = "live"),
            episode(now - 40 * HOUR_MS, INSTA to 25, rabbit = true, key = "stale"),
            episode(now - 2 * HOUR_MS, INSTA to 25, rabbit = false, key = "plain"),
            episode(now - 2 * HOUR_MS, INSTA to 25, rabbit = true, key = "beyond-data"),
        )
        val dataEnd = now - 2 * HOUR_MS // the last one ends after the derived tables caught up
        assertNull(WorthCheckInSampler.select(CheckInLog(), today, list, now, dataEnd).episodeKey)
    }

    @Test
    fun `dismissed keys stay bounded`() {
        var log = CheckInLog()
        repeat(250) { log = WorthCheckInSampler.dismiss(log, "k$it") }
        assertEquals(200, log.dismissed.size)
        assertEquals("k249", log.dismissed.last())
        log = WorthCheckInSampler.dismiss(log, "k100")
        assertEquals(200, log.dismissed.size)
        assertEquals("k100", log.dismissed.last())
    }
}
