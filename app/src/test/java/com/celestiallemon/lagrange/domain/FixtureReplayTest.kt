package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.RemovalReason
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replays every day fixture in `src/test/resources/fixtures` (exported from the Debug Timeline Inspector, or the
 * synthetic one below) and checks reconstruction still produces exactly what was recorded. A deliberate algorithm
 * change shows up here as a diff to review, then the fixtures are re-exported.
 */
class FixtureReplayTest {

    @Test
    fun `every committed fixture replays to its recorded episodes`() {
        val dir = fixturesDir()
        val files = dir.listFiles { f -> f.extension == "json" }.orEmpty().sortedBy { it.name }
        assertTrue("no fixtures found in $dir", files.isNotEmpty())
        for (f in files) {
            val fixture = DayFixture.decode(f.readText())
            assertEquals("${f.name}: format", DayFixture.FORMAT_VERSION, fixture.formatVersion)
            assertEquals("${f.name}: replay differs from recorded episodes", fixture.expected, fixture.replay())
        }
    }

    @Test
    fun `a fixture survives a JSON round trip unchanged`() {
        val fixture = syntheticMorning()
        val decoded = DayFixture.decode(fixture.encode())
        assertEquals(fixture, decoded)
        assertEquals(fixture.expected, decoded.replay())
    }

    @Test
    fun `the synthetic morning reconstructs to the expected story`() {
        val eps = syntheticMorning().expected
        val unlocked = eps.filter { !it.glance }
        assertEquals(1, eps.count { it.glance })
        assertEquals(listOf("OBSERVED_CLICK", "NO_OBSERVED_TRIGGER", "RECENT_POSSIBLE_ALERT"), unlocked.map { it.trigger })
        assertEquals(listOf("FOCUSED", "RABBIT_HOLE", "BLOCKED_ATTEMPT"), unlocked.map { it.archetype })
        assertEquals(listOf("HIJACKED_PICKUP"), unlocked[1].rabbitHoleReasons)
    }

    /** Regenerates the committed synthetic fixture: `LAGRANGE_WRITE_FIXTURES=1 ./gradlew testDebugUnitTest`. */
    @Test
    fun `write synthetic fixture when asked`() {
        if (System.getenv("LAGRANGE_WRITE_FIXTURES") != "1") return
        File(fixturesDir(), "synthetic-morning.json").writeText(syntheticMorning().encode())
    }

    private fun fixturesDir(): File {
        val candidates = listOf(File("src/test/resources/fixtures"), File("app/src/test/resources/fixtures"))
        return candidates.firstOrNull { it.isDirectory } ?: candidates.first().also { it.mkdirs() }
    }

    /** A believable morning in Kolkata: a glance, a tapped WhatsApp message, a Maps trip hijacked by YouTube, a blocked Instagram open. */
    private fun syntheticMorning(): DayFixture {
        val zone = ZoneId.of("Asia/Kolkata")
        val date = LocalDate.parse("2026-11-23")
        val origin = LocalDateTime.parse("2026-11-23T07:00").atZone(zone).toInstant().toEpochMilli()
        val l = Ledger(origin).listener(-4 * HOUR_MS, 20 * HOUR_MS).usageCoverage(-4 * HOUR_MS, 20 * HOUR_MS)
        l.glance(0)
        l.notification(P.WHATSAPP, postedAt = 5 * MIN, removedAt = 10 * MIN + 2 * SEC, reason = RemovalReason.CLICK)
        l.session(10 * MIN, P.WHATSAPP to 4 * MIN)
        l.session(HOUR_MS, P.MAPS to MIN, P.YOUTUBE to 14 * MIN)
        l.notification(P.INSTAGRAM, postedAt = 3 * HOUR_MS - 20 * SEC)
        l.session(3 * HOUR_MS, P.INSTAGRAM to 8 * SEC).blocked(P.INSTAGRAM, 3 * HOUR_MS + 2 * SEC)
        val window = ZoneTimeline.fixed(zone).window(date, 4)
        val input = l.input(dataEnd = 4 * HOUR_MS)
        val base = DayFixture(
            algorithmVersion = DerivedAlgorithm.VERSION,
            logicalDate = date.toString(),
            zoneId = zone.id,
            rolloverHour = 4,
            windowStartMs = window.startMs,
            windowEndMs = window.endMs,
            dataEndMs = input.dataEndMs,
            apps = P.apps.values.sortedBy { it.pkgId }.map { FixtureApp(it.pkgId, it.packageName, it.category.name, it.isSystemSurface) },
            classes = emptyList(),
            customTabClassIds = emptyList(),
            events = input.events.map { FixtureEvent(it.timestampMs, it.type, it.pkgId, it.clsId, it.order) },
            notifications = input.notifications.map {
                FixtureNotification(
                    it.id, it.pkgId, it.postedAtMs, it.removedAtMs, it.removalReason.name, it.importance, it.isOngoing,
                    it.isForegroundService, it.isGroupSummary, it.isAmbient, it.matchedInterruptionFilter, it.dndOrSilentAtPost,
                )
            },
            friction = input.friction.map { FixtureFriction(it.timestampMs, it.pkgId, it.eventKind, it.pauseDurationSec, it.postCountdownReactionMs, it.outcome) },
            coverage = input.coverage.map { FixtureCoverage(it.kind.name, it.status.name, it.startMs, it.endMs) },
            expected = emptyList(),
        )
        return base.copy(expected = base.replay())
    }
}
