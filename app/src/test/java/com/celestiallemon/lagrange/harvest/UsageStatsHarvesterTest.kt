package com.celestiallemon.lagrange.harvest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.DAY
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.FakeUsageEventSource
import com.celestiallemon.lagrange.HOUR
import com.celestiallemon.lagrange.MutableClock
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.ServiceKind
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class UsageStatsHarvesterTest {
    private val db = LagrangeDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val start = 1_800_000_000_000L
    private val clock = MutableClock(start)
    private val source = FakeUsageEventSource()
    private fun harvester(windowMs: Long = UsageStatsHarvester.DEFAULT_WINDOW_MS, batch: Int = 1000) =
        UsageStatsHarvester(db, source, Interner(db, FakeResolver()), clock.fn, windowMs, batch)

    @After
    fun close() = db.close()

    private fun ev(ts: Long, type: Int = 1, pkg: String = "com.a", cls: String? = "com.a.Main") = RawUsageEvent(ts, type, pkg, cls)
    private suspend fun count() = db.rawUsageEventDao().count()
    private suspend fun cursor() = db.ingestionStateDao().get(IngestionKeys.USAGE_CURSOR_MS)

    @Test
    fun `no usage access writes nothing`() = runBlocking {
        source.access = false
        assertEquals(HarvestResult.NoAccess, harvester().harvest(HarvestTrigger.MANUAL))
        assertNull(cursor())
    }

    @Test
    fun `direct boot null and empty answers never move the cursor`() = runBlocking {
        val h = harvester()
        source.unlocked = false
        assertTrue(h.harvest(HarvestTrigger.PERIODIC) is HarvestResult.Unavailable)
        source.unlocked = true
        source.returnNull = true
        assertTrue(h.harvest(HarvestTrigger.PERIODIC) is HarvestResult.Unavailable)
        source.returnNull = false
        assertTrue(h.harvest(HarvestTrigger.PERIODIC) is HarvestResult.Unavailable) // empty
        assertNull(cursor())
        assertNull(db.ingestionStateDao().get(IngestionKeys.LAST_HARVEST_AT_MS))
        assertTrue(db.coverageDao().all().isEmpty())
    }

    @Test
    fun `first harvest backfills 14 days and records BACKFILL coverage`() = runBlocking {
        source.events += listOf(ev(start - 20 * DAY), ev(start - 13 * DAY), ev(start - 2 * HOUR), ev(start - 1000))
        val result = harvester().harvest(HarvestTrigger.APP_OPEN) as HarvestResult.Success
        assertTrue(result.wasBackfill)
        assertEquals(start - UsageStatsHarvester.BACKFILL_MS, result.fromMs)
        assertEquals(3L, count()) // the 20-day-old event is outside the backfill window
        assertEquals(start - 1000, cursor())
        val coverage = db.coverageDao().all()
        val backfill = coverage.single { it.status == CoverageStatus.BACKFILL.name }
        assertEquals(ServiceKind.USAGE_HARVEST.name, backfill.serviceKind)
        // History really starts at the earliest event the OS still had, not at "now - 14 days".
        assertEquals(start - 13 * DAY, backfill.startMs)
        assertEquals(start, db.ingestionStateDao().get(IngestionKeys.FIRST_HARVEST_AT_MS))
    }

    @Test
    fun `overlapping re-queries never duplicate, including null-class events`() = runBlocking {
        val h = harvester()
        source.events += listOf(ev(start - 5000, type = 15, pkg = "android", cls = null), ev(start - 4000), ev(start - 10))
        h.harvest(HarvestTrigger.MANUAL)
        assertEquals(3L, count())
        clock.now = start + 30_000
        source.events += ev(start + 20_000, type = 16, pkg = "android", cls = null)
        val second = h.harvest(HarvestTrigger.SCREEN_OFF) as HarvestResult.Success
        assertEquals(start - 10 - UsageStatsHarvester.OVERLAP_MS, second.fromMs)
        assertEquals(1L, second.eventsInserted)
        assertEquals(4L, count())
        assertEquals(start + 20_000, cursor())
    }

    @Test
    fun `identical events in one millisecond are both kept, once`() = runBlocking {
        val h = harvester()
        source.events += listOf(ev(start - 100, type = 12, pkg = "com.chat", cls = null), ev(start - 100, type = 12, pkg = "com.chat", cls = null))
        h.harvest(HarvestTrigger.MANUAL)
        clock.now = start + 1000
        h.harvest(HarvestTrigger.MANUAL)
        val rows = db.rawUsageEventDao().allForTest()
        assertEquals(listOf(0, 1), rows.map { it.seqInMs })
    }

    @Test
    fun `small windows and batches ingest everything and commit the cursor with the rows`() = runBlocking {
        val h = harvester(windowMs = HOUR, batch = 3)
        repeat(50) { i -> source.events += ev(start - 10 * HOUR + i * 10 * 60_000L, pkg = "com.p${i % 7}", cls = if (i % 3 == 0) null else "C$i") }
        val r = h.harvest(HarvestTrigger.MANUAL) as HarvestResult.Success
        assertEquals(50L, r.eventsSeen)
        assertEquals(50L, count())
        assertEquals(source.events.maxOf { it.timestampMs }, cursor())
        assertTrue(source.queries.all { (b, e) -> e - b <= HOUR })
    }

    @Test
    fun `a failure mid-run keeps committed windows and their cursor`() = runBlocking {
        val h = harvester(windowMs = HOUR)
        source.events += listOf(ev(start - 14 * DAY + 10), ev(start - 14 * DAY + 20), ev(start - 2 * HOUR))
        source.failFromMs = start - 14 * DAY + HOUR
        assertTrue(h.harvest(HarvestTrigger.MANUAL) is HarvestResult.Unavailable)
        assertEquals(2L, count())
        assertEquals(start - 14 * DAY + 20, cursor())
        source.failFromMs = null
        h.harvest(HarvestTrigger.MANUAL)
        assertEquals(3L, count())
    }

    @Test
    fun `time change rescans the last 24 hours once`() = runBlocking {
        val h = harvester()
        source.events += ev(start - 1000)
        h.harvest(HarvestTrigger.MANUAL)
        clock.now = start + 10 * 60_000
        source.events += ev(start - 20 * HOUR, pkg = "com.late") // e.g. revealed after the clock moved back
        h.requestRescan()
        val r = h.harvest(HarvestTrigger.TIME_CHANGED) as HarvestResult.Success
        assertTrue(r.fromMs <= clock.now - UsageStatsHarvester.RESCAN_MS)
        assertEquals(2L, count())
        assertEquals(1L, db.ingestionStateDao().get(IngestionKeys.RESCAN_DONE_GENERATION))
        clock.now += 60_000
        val next = h.harvest(HarvestTrigger.PERIODIC) as HarvestResult.Success
        assertTrue(next.fromMs > clock.now - HOUR)
    }

    @Test
    fun `a silence over 48 hours is recorded as a GAP interval`() = runBlocking {
        val h = harvester()
        source.events += ev(start - 1000)
        h.harvest(HarvestTrigger.MANUAL)
        clock.now = start + 3 * DAY
        source.events += ev(start + 3 * DAY - 500)
        val r = h.harvest(HarvestTrigger.APP_OPEN) as HarvestResult.Success
        assertTrue(r.gapDetected)
        val gap = db.coverageDao().all().single { it.status == CoverageStatus.GAP.name }
        assertEquals(start, gap.startMs)
        assertEquals(start + 3 * DAY, gap.endMs)
    }

    @Test
    fun `consecutive harvests extend one ACTIVE coverage interval`() = runBlocking {
        val h = harvester()
        source.events += ev(start - 1000)
        h.harvest(HarvestTrigger.MANUAL)
        clock.now = start + HOUR
        source.events += ev(start + HOUR - 10)
        h.harvest(HarvestTrigger.PERIODIC)
        val active = db.coverageDao().all().filter { it.status == CoverageStatus.ACTIVE.name }
        assertEquals(1, active.size)
        assertEquals(start + HOUR, active.single().endMs)
    }

    @Test
    fun `concurrent triggers are serialized and never duplicate`() = runBlocking {
        val h = harvester(batch = 2)
        repeat(20) { source.events += ev(start - 1000 + it) }
        listOf(HarvestTrigger.SCREEN_OFF, HarvestTrigger.PERIODIC, HarvestTrigger.APP_OPEN).map { t ->
            async(kotlinx.coroutines.Dispatchers.IO) { h.harvest(t) }
        }.awaitAll()
        assertEquals(20L, count())
    }

    @Test
    fun `an interrupted backfill is still a backfill when it resumes`() = runBlocking {
        val h = harvester(windowMs = HOUR)
        source.events += listOf(ev(start - 6 * DAY), ev(start - 6 * DAY + 10), ev(start - 2 * HOUR))
        source.failFromMs = start - 5 * DAY
        assertTrue(h.harvest(HarvestTrigger.APP_OPEN) is HarvestResult.Unavailable)
        assertEquals(start - 6 * DAY + 10, cursor())
        assertNull(db.ingestionStateDao().get(IngestionKeys.FIRST_HARVEST_AT_MS))

        source.failFromMs = null
        clock.now = start + 10 * 60_000
        val r = h.harvest(HarvestTrigger.APP_OPEN) as HarvestResult.Success
        assertTrue("resumed run must still count as the backfill", r.wasBackfill)
        assertEquals(clock.now, db.ingestionStateDao().get(IngestionKeys.FIRST_HARVEST_AT_MS))
        val coverage = db.coverageDao().all()
        val backfill = coverage.single { it.status == CoverageStatus.BACKFILL.name }
        assertEquals("earliest event from the first, interrupted run", start - 6 * DAY, backfill.startMs)
        val active = coverage.single { it.status == CoverageStatus.ACTIVE.name }
        assertEquals(clock.now, active.startMs)
        assertEquals(3L, count())

        // The next run is an ordinary incremental harvest.
        clock.now += HOUR
        source.events += ev(clock.now - 10)
        assertTrue(!(h.harvest(HarvestTrigger.PERIODIC) as HarvestResult.Success).wasBackfill)
        assertEquals(1, db.coverageDao().all().count { it.status == CoverageStatus.BACKFILL.name })
    }

    @Test
    fun `live coverage after a GAP starts at now and never overlaps it`() = runBlocking {
        val h = harvester()
        source.events += ev(start - 1000)
        h.harvest(HarvestTrigger.MANUAL)
        clock.now = start + 3 * DAY
        source.events += ev(start + 3 * DAY - 500)
        h.harvest(HarvestTrigger.APP_OPEN)
        val coverage = db.coverageDao().all()
        val gap = coverage.single { it.status == CoverageStatus.GAP.name }
        val actives = coverage.filter { it.status == CoverageStatus.ACTIVE.name }.sortedBy { it.startMs }
        assertEquals(2, actives.size)
        val after = actives.last()
        assertEquals(clock.now, after.startMs)
        assertTrue("ACTIVE must not overlap GAP", after.startMs >= gap.endMs!!)
    }

    @Test
    fun `a backward clock jump brings a future cursor back to the present`() = runBlocking {
        val h = harvester()
        source.events += listOf(ev(start - 2 * HOUR), ev(start - 1000))
        h.harvest(HarvestTrigger.MANUAL)
        assertEquals(start - 1000, cursor())
        // The clock is set back three hours; nothing new was rescanned yet.
        clock.now = start - 3 * HOUR
        source.events += ev(start - 3 * HOUR - 5_000, pkg = "com.after_jump")
        val r = h.harvest(HarvestTrigger.TIME_CHANGED) as HarvestResult.Success
        assertTrue(r.fromMs <= clock.now - UsageStatsHarvester.RESCAN_MS)
        assertTrue("cursor ${cursor()} must not stay ahead of now ${clock.now}", cursor()!! <= clock.now)
        assertEquals(start - 3 * HOUR - 5_000, cursor())
        val active = db.coverageDao().all().single { it.status == CoverageStatus.ACTIVE.name }
        assertTrue(active.endMs!! >= active.startMs)

        // Time moves on normally: an incremental harvest starts from the corrected cursor, not 24 hours back.
        clock.now += HOUR
        source.events += ev(clock.now - 100)
        val next = h.harvest(HarvestTrigger.PERIODIC) as HarvestResult.Success
        assertEquals(start - 3 * HOUR - 5_000 - UsageStatsHarvester.OVERLAP_MS, next.fromMs)
    }

    @Test
    fun `a cursor older than the backfill window never queries beyond it`() = runBlocking {
        val h = harvester()
        source.events += ev(start - 1000)
        h.harvest(HarvestTrigger.MANUAL)
        clock.now = start + 40 * DAY
        source.events += ev(clock.now - 1000)
        source.queries.clear()
        val r = h.harvest(HarvestTrigger.APP_OPEN) as HarvestResult.Success
        assertEquals(clock.now - UsageStatsHarvester.BACKFILL_MS, r.fromMs)
        assertTrue(source.queries.all { (b, _) -> b >= clock.now - UsageStatsHarvester.BACKFILL_MS })
    }

    @Test
    fun `a rescan requested mid-run is not swallowed by that run`() = runBlocking {
        lateinit var h: UsageStatsHarvester
        var requested = false
        val slowSource = object : UsageEventSource by source {
            override fun query(beginMs: Long, endMs: Long): Sequence<RawUsageEvent>? {
                if (!requested && beginMs > start) {
                    requested = true
                    // The clock changed while this harvest was running.
                    runBlocking { h.requestRescan() }
                }
                return source.query(beginMs, endMs)
            }
        }
        h = UsageStatsHarvester(db, slowSource, Interner(db, FakeResolver()), clock.fn, HOUR, 1000)
        source.events += ev(start - 1000)
        h.harvest(HarvestTrigger.MANUAL)
        clock.now = start + 3 * HOUR
        source.events += ev(start + 2 * HOUR)
        val during = h.harvest(HarvestTrigger.PERIODIC) as HarvestResult.Success
        assertTrue(requested)
        assertTrue("this run started before the request", during.fromMs > clock.now - UsageStatsHarvester.RESCAN_MS)
        assertEquals(1L, db.ingestionStateDao().get(IngestionKeys.RESCAN_GENERATION))
        assertNull(db.ingestionStateDao().get(IngestionKeys.RESCAN_DONE_GENERATION))

        clock.now += 60_000
        val followUp = h.harvest(HarvestTrigger.TIME_CHANGED) as HarvestResult.Success
        assertTrue(followUp.fromMs <= clock.now - UsageStatsHarvester.RESCAN_MS)
        assertEquals(1L, db.ingestionStateDao().get(IngestionKeys.RESCAN_DONE_GENERATION))
    }

    @Test
    fun `nothing from before a reset is ever harvested again`() = runBlocking {
        val cutoff = start - 30 * 60_000
        db.ingestionStateDao().put(com.celestiallemon.lagrange.data.db.IngestionStateEntity(IngestionKeys.RESET_CUTOFF_MS, cutoff))
        source.events += listOf(ev(start - 3 * DAY), ev(start - 2 * HOUR), ev(cutoff - 1), ev(cutoff + 1), ev(start - 10))
        val r = harvester().harvest(HarvestTrigger.APP_OPEN) as HarvestResult.Success
        assertEquals(cutoff, r.fromMs)
        assertEquals(listOf(cutoff + 1, start - 10), db.rawUsageEventDao().allForTest().map { it.timestampUtcMs })
        // A clock-change rescan doesn't reach behind the cutoff either.
        clock.now += 60_000
        harvester().requestRescan()
        harvester().harvest(HarvestTrigger.TIME_CHANGED)
        assertEquals(2L, count())
    }
}
