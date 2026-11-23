package com.celestiallemon.lagrange.domain.lab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewaysTest {
    private var t = 0L

    private fun ep(vararg segs: Pair<Long, Int>, rabbit: Boolean = false, blockedFirst: Long? = null, inProgress: Boolean = false): LabEpisode {
        t += 10_000_000
        return episode(t, *segs, rabbit = rabbit, blockedFirst = blockedFirst, inProgress = inProgress)
    }

    @Test
    fun `spiral means a rabbit hole or 15 minutes in other distracting apps`() {
        assertTrue(GatewayAnalyzer.spiraled(ep(INSTA to 2, rabbit = true), INSTA, testApps))
        assertTrue(GatewayAnalyzer.spiraled(ep(MAPS to 1, TIKTOK to 15), MAPS, testApps))
        // Time in the entry app itself, or in non-distracting apps, is not downstream.
        assertFalse(GatewayAnalyzer.spiraled(ep(INSTA to 40, KINDLE to 30), INSTA, testApps))
        assertFalse(GatewayAnalyzer.spiraled(ep(MAPS to 1, TIKTOK to 14), MAPS, testApps))
        assertEquals(15 * MIN, GatewayAnalyzer.downstreamMs(ep(INSTA to 5, TIKTOK to 10, INSTA to 5, YOUTUBE to 5), INSTA, testApps))
    }

    @Test
    fun `shrinkage pulls small samples toward the base rate`() {
        assertEquals((1 + 5 * 0.2) / (1 + 5), GatewayAnalyzer.shrink(1, 1, 0.2, 5.0), 1e-9)
        assertEquals(0.2, GatewayAnalyzer.shrink(0, 0, 0.2, 5.0), 1e-9)
        // With lots of data the raw rate wins.
        assertEquals(0.9, GatewayAnalyzer.shrink(900, 1000, 0.2, 5.0), 0.005)
    }

    @Test
    fun `apps under five entries are listed apart, the rest ranked by shrunk rate`() {
        val eps = buildList {
            // Maps: 6 entries, 3 spiral.
            repeat(3) { add(ep(MAPS to 1, TIKTOK to 20)) }
            repeat(3) { add(ep(MAPS to 2)) }
            // Instagram: 10 entries, 4 spiral (rabbit holes).
            repeat(4) { add(ep(INSTA to 25, rabbit = true)) }
            repeat(6) { add(ep(INSTA to 3)) }
            // YouTube: 2 entries, both spiral: a perfect raw rate on a tiny sample.
            repeat(2) { add(ep(YOUTUBE to 5, TIKTOK to 20)) }
            // Entry is the first *real* app: a blocked attempt doesn't count, nor does an episode still running.
            add(ep(KINDLE to 5, blockedFirst = INSTA))
            add(ep(TIKTOK to 30, rabbit = true, inProgress = true))
        }
        val r = GatewayAnalyzer.analyze(eps, testApps)
        assertEquals(19, r.totalEntries)
        assertEquals(9, r.totalSpirals)
        assertEquals(9.0 / 19, r.baseRate, 1e-9)
        assertEquals(listOf(MAPS, INSTA), r.ranked.map { it.pkgId })
        val maps = r.ranked[0]
        assertEquals(6, maps.entries)
        assertEquals(3, maps.spirals)
        assertEquals(0.5, maps.rawRate, 1e-9)
        assertEquals((3 + 5 * 9.0 / 19) / 11, maps.shrunkRate, 1e-9)
        assertEquals(10 * MIN, maps.avgDownstreamMs)
        assertEquals(listOf(YOUTUBE, KINDLE), r.lowSample.map { it.pkgId })
        assertEquals(1.0, r.lowSample[0].rawRate, 1e-9)
    }

    @Test
    fun `no episodes means an empty report with a zero base rate`() {
        val r = GatewayAnalyzer.analyze(emptyList(), testApps)
        assertTrue(r.ranked.isEmpty() && r.lowSample.isEmpty())
        assertEquals(0.0, r.baseRate, 0.0)
    }
}
