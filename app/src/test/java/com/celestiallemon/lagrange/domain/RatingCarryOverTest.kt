package com.celestiallemon.lagrange.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RatingCarryOverTest {
    private fun episodes(l: Ledger) = SessionReconstructor.reconstruct(l.input())

    @Test
    fun `ratings follow their episode key across a rebuild`() {
        val l = Ledger().listener(-HOUR_MS, 3 * HOUR_MS)
        l.session(0, P.WHATSAPP to 5 * MIN)
        l.session(HOUR_MS, P.INSTAGRAM to 5 * MIN)
        val before = episodes(l)
        val old = listOf(RatingCarryOver.OldRating(before[1].episodeKey, before[1].startMs, 2))
        val after = episodes(l)
        assertEquals(mapOf(1 to 2), RatingCarryOver.reattach(old, after))
    }

    @Test
    fun `a rating whose key changed moves to the episode that now contains its start`() {
        val l = Ledger().listener(-HOUR_MS, 3 * HOUR_MS)
        l.session(0, P.LAUNCHER to 4 * SEC, P.INSTAGRAM to 5 * MIN)
        val before = episodes(l).single()
        // Same pickup, but the old build had chosen a different entry app (e.g. a since-recategorized launcher).
        val old = listOf(RatingCarryOver.OldRating("${before.startMs}:${P.LAUNCHER}", before.startMs, 5))
        assertEquals(mapOf(0 to 5), RatingCarryOver.reattach(old, listOf(before)))
    }

    @Test
    fun `each episode takes at most one rating and glances take none`() {
        val l = Ledger().listener(-HOUR_MS, 3 * HOUR_MS)
        l.glance(0)
        l.session(MIN, P.WHATSAPP to 5 * MIN)
        val eps = episodes(l)
        val glance = eps.first { it.isGlanceOnly }
        val ep = eps.first { !it.isGlanceOnly }
        val old = listOf(
            RatingCarryOver.OldRating(ep.episodeKey, ep.startMs, 4),
            RatingCarryOver.OldRating("gone", ep.startMs + SEC, 1),
            RatingCarryOver.OldRating("gone2", glance.startMs, 3),
        )
        val map = RatingCarryOver.reattach(old, eps)
        assertEquals(mapOf(eps.indexOf(ep) to 4), map)
        assertTrue(RatingCarryOver.reattach(emptyList(), eps).isEmpty())
    }
}
