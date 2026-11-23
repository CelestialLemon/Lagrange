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
        assertEquals(start - UsageStatsHarvester.BACKFILL_MS, backfill.startMs)
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
        assertNull(db.ingestionStateDao().get(IngestionKeys.RESCAN_REQUESTED_AT_MS))
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
}
