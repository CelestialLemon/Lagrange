package com.celestiallemon.lagrange.digest

import com.celestiallemon.lagrange.domain.digest.Digest
import com.celestiallemon.lagrange.domain.digest.DigestKind
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DigestServiceTest {
    private class Poster(var allowed: Boolean = true, var fail: Boolean = false) : DigestPoster {
        val posted = mutableListOf<Digest>()
        val alerts = mutableListOf<Long>()
        override fun canPost() = allowed
        override fun post(digest: Digest): Boolean {
            if (fail) return false
            posted += digest
            return true
        }
        override fun postCoverageAlert(silentHours: Long): Boolean {
            alerts += silentHours
            return true
        }
    }

    private val hour = 3_600_000L
    private val claims = mutableMapOf<DigestKind, String>()
    private var coverageClaim: Long? = null
    private var lastHarvest: Long? = 0L
    private var now = 100 * hour
    private var digest: Digest? = Digest(DigestKind.DAILY, "2026-11-24", "t", "s", listOf("s"))

    private fun service(poster: Poster) = DigestService(
        compose = { kind -> digest?.copy(kind = kind) },
        claim = { kind, key -> if (claims[kind] == key) false else true.also { claims[kind] = key } },
        release = { kind, key -> if (claims[kind] == key) claims.remove(kind) },
        poster = poster,
        lastHarvestAtMs = { lastHarvest },
        claimCoverageAlert = { at -> if (coverageClaim == at) false else true.also { coverageClaim = at } },
        clock = { now },
    )

    @Test
    fun `a digest is posted once per key, and previews never consume the claim`() = runBlocking {
        val poster = Poster()
        val s = service(poster)
        assertEquals(DigestService.Outcome.POSTED, s.sendTest(DigestKind.DAILY))
        assertEquals(DigestService.Outcome.POSTED, s.runScheduled(DigestKind.DAILY))
        assertEquals(DigestService.Outcome.ALREADY_POSTED, s.runScheduled(DigestKind.DAILY))
        assertEquals(DigestService.Outcome.POSTED, s.runScheduled(DigestKind.WEEKLY))
        assertEquals(3, poster.posted.size)
    }

    @Test
    fun `notifications off or a failed post leaves the day unclaimed`() = runBlocking {
        val poster = Poster(allowed = false)
        val s = service(poster)
        assertEquals(DigestService.Outcome.NOTIFICATIONS_OFF, s.runScheduled(DigestKind.DAILY))
        assertTrue(claims.isEmpty())
        poster.allowed = true
        poster.fail = true
        assertEquals(DigestService.Outcome.NOTIFICATIONS_OFF, s.runScheduled(DigestKind.DAILY))
        assertTrue("released after the failed post", claims.isEmpty())
        poster.fail = false
        assertEquals(DigestService.Outcome.POSTED, s.runScheduled(DigestKind.DAILY))
    }

    @Test
    fun `nothing to say posts nothing`() = runBlocking {
        digest = null
        val poster = Poster()
        assertEquals(DigestService.Outcome.NOTHING_TO_SAY, service(poster).runScheduled(DigestKind.WEEKLY))
        assertTrue(poster.posted.isEmpty())
    }

    @Test
    fun `coverage alert fires once per silence after 48 hours`() = runBlocking {
        val poster = Poster()
        val s = service(poster)
        now = lastHarvest!! + 47 * hour
        assertFalse(s.checkCoverage())
        now = lastHarvest!! + 50 * hour
        assertTrue(s.checkCoverage())
        assertFalse("same silence, no second alert", s.checkCoverage())
        assertEquals(listOf(50L), poster.alerts)
        lastHarvest = now
        now += 49 * hour
        assertTrue("a new silence alerts again", s.checkCoverage())
        lastHarvest = null
        assertFalse("never harvested yet: nothing to alert about", s.checkCoverage())
    }
}
