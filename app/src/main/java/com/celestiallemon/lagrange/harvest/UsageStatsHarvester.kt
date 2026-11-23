package com.celestiallemon.lagrange.harvest

import android.util.Log
import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.DerivedDirty
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.db.CoverageIntervalEntity
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.IngestionStateEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawUsageEventEntity
import com.celestiallemon.lagrange.data.db.ServiceKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class HarvestTrigger { PERIODIC, SCREEN_OFF, APP_OPEN, TIME_CHANGED, MANUAL }

sealed interface HarvestResult {
    data object NoAccess : HarvestResult

    /** Nothing usable came back (Direct Boot, revoked access). The cursor did not move. */
    data class Unavailable(val reason: String) : HarvestResult

    data class Success(
        val eventsSeen: Long,
        val eventsInserted: Long,
        val fromMs: Long,
        val toMs: Long,
        val wasBackfill: Boolean,
        val gapDetected: Boolean,
    ) : HarvestResult
}

/**
 * Copies `UsageStatsManager` events into the permanent raw ledger before Android purges them.
 *
 * All runs are serialized behind one mutex. Each run re-reads the last minute before the cursor (the overlap is
 * deduplicated by the unique key), walks forward in bounded windows, and inserts in small batches so memory stays
 * flat on Android 17. The cursor (the newest ingested event timestamp) is written in the same transaction as each
 * batch, so a crash can never skip events.
 */
class UsageStatsHarvester(
    private val db: LagrangeDatabase,
    private val source: UsageEventSource,
    private val interner: Interner,
    private val clock: () -> Long = System::currentTimeMillis,
    private val windowMs: Long = DEFAULT_WINDOW_MS,
    private val batchSize: Int = DEFAULT_BATCH_SIZE,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private val _lastResult = MutableStateFlow<HarvestResult?>(null)
    val lastResult: StateFlow<HarvestResult?> = _lastResult

    /** Called from coroutines; never blocks the caller's thread (all database and system work runs on IO). */
    suspend fun harvest(trigger: HarvestTrigger): HarvestResult = mutex.withLock {
        _running.value = true
        try {
            withContext(Dispatchers.IO) { runLocked() }.also {
                _lastResult.value = it
                Log.i(TAG, "harvest($trigger) -> $it")
            }
        } finally {
            _running.value = false
        }
    }

    /** Runs [block] while no harvest can start, e.g. to wipe the tables the harvester writes. */
    suspend fun <T> exclusive(block: suspend () -> T): T = mutex.withLock { block() }

    /**
     * Asks for a rescan of the last 24 hours (the wall clock moved, so old cursors can't be trusted). Each request
     * bumps a generation; a harvest only marks done the generation it saw when it started, so a request arriving
     * mid-run is never swallowed.
     */
    suspend fun requestRescan() {
        db.withWriteTransaction {
            val state = db.ingestionStateDao()
            val next = (state.get(IngestionKeys.RESCAN_GENERATION) ?: 0) + 1
            state.put(IngestionStateEntity(IngestionKeys.RESCAN_GENERATION, next))
        }
    }

    private suspend fun runLocked(): HarvestResult {
        if (!source.hasUsageAccess()) return HarvestResult.NoAccess
        if (!source.isUserUnlocked()) return HarvestResult.Unavailable("user locked (Direct Boot)")

        val state = db.ingestionStateDao()
        val now = clock()
        val cursor = state.get(IngestionKeys.USAGE_CURSOR_MS)
        val lastHarvestAt = state.get(IngestionKeys.LAST_HARVEST_AT_MS)
        // A backfill interrupted part-way leaves a cursor behind but no FIRST_HARVEST_AT_MS: it is still a backfill.
        val isBackfill = state.get(IngestionKeys.FIRST_HARVEST_AT_MS) == null
        val rescanGeneration = state.get(IngestionKeys.RESCAN_GENERATION) ?: 0
        val rescanDone = state.get(IngestionKeys.RESCAN_DONE_GENERATION) ?: 0
        val legacyRescan = state.get(IngestionKeys.RESCAN_REQUESTED_AT_MS) != null
        val resetCutoff = state.get(IngestionKeys.RESET_CUTOFF_MS)
        val clockWentBack = cursor != null && cursor > now
        val rescan = cursor != null && (rescanGeneration > rescanDone || legacyRescan || clockWentBack)

        var begin = if (cursor == null) now - BACKFILL_MS else cursor - OVERLAP_MS
        if (rescan) begin = minOf(begin, now - RESCAN_MS)
        begin = minOf(begin, now - OVERLAP_MS)
        // Android keeps about a week; a cursor older than the backfill window has nothing more to give.
        begin = maxOf(begin, now - BACKFILL_MS)
        // History from before "Delete all data" is never re-imported.
        if (resetCutoff != null) begin = maxOf(begin, resetCutoff)

        val run = RunState(rescan = rescan, backfill = isBackfill)
        var seen = 0L
        var inserted = 0L
        var windowStart = begin
        while (windowStart < now) {
            val windowEnd = minOf(windowStart + windowMs, now)
            val events = source.query(windowStart, windowEnd)
                ?: return HarvestResult.Unavailable("queryEvents returned null")
            val seq = SeqAssigner()
            val batch = ArrayList<RawUsageEvent>(batchSize)
            for (event in events) {
                batch += event
                if (batch.size >= batchSize) {
                    inserted += commitBatch(batch, seq, run)
                    seen += batch.size
                    batch.clear()
                }
            }
            if (batch.isNotEmpty()) {
                inserted += commitBatch(batch, seq, run)
                seen += batch.size
            }
            windowStart = windowEnd
        }

        // The overlap window always re-reads the event at the cursor, so an empty answer means the system had nothing
        // to give (for example, still locked). Leave every bit of state alone and try again later.
        if (seen == 0L) return HarvestResult.Unavailable("queryEvents returned no events")

        val gap = !isBackfill && lastHarvestAt != null && now - lastHarvestAt > GAP_ALERT_MS
        db.withWriteTransaction {
            state.put(IngestionStateEntity(IngestionKeys.LAST_HARVEST_AT_MS, now))
            state.put(IngestionStateEntity(IngestionKeys.LAST_HARVEST_EVENT_COUNT, seen))
            if (rescanGeneration > rescanDone) state.put(IngestionStateEntity(IngestionKeys.RESCAN_DONE_GENERATION, rescanGeneration))
            if (legacyRescan) state.remove(IngestionKeys.RESCAN_REQUESTED_AT_MS)
            val backfillFrom = if (isBackfill) state.get(IngestionKeys.BACKFILL_EARLIEST_MS) ?: begin else null
            if (isBackfill) state.put(IngestionStateEntity(IngestionKeys.FIRST_HARVEST_AT_MS, now))
            recordCoverage(begin, now, backfillFrom, if (gap) lastHarvestAt else null)
            // Coverage changed and the trailing day may hold late notification data: derive it with this commit.
            DerivedDirty.mark(state, minOf(begin - DerivedDirty.HARVEST_LOOKBACK_MS, now - DerivedDirty.HARVEST_TRAIL_MS))
        }
        return HarvestResult.Success(seen, inserted, begin, now, isBackfill, gap)
    }

    /** Per-run bookkeeping for the cursor and the backfill start. */
    private class RunState(val rescan: Boolean, val backfill: Boolean) {
        var maxSeen: Long? = null
    }

    private suspend fun commitBatch(batch: List<RawUsageEvent>, seq: SeqAssigner, run: RunState): Long {
        val pkgIds = interner.packageIds(batch.map { it.packageName })
        val clsIds = interner.classIds(batch.map { it.className.orEmpty() })
        val rows = batch.map { e ->
            val pkgId = pkgIds.getValue(e.packageName)
            val clsId = clsIds.getValue(e.className.orEmpty())
            RawUsageEventEntity(
                timestampUtcMs = e.timestampMs,
                eventType = e.eventType,
                pkgId = pkgId,
                clsId = clsId,
                seqInMs = seq.next(e.timestampMs, e.eventType, pkgId, clsId),
            )
        }
        val batchMax = batch.maxOf { it.timestampMs }
        val batchMin = batch.minOf { it.timestampMs }
        run.maxSeen = maxOf(run.maxSeen ?: batchMax, batchMax)
        return db.withWriteTransaction {
            val ids = db.rawUsageEventDao().insertAll(rows)
            val state = db.ingestionStateDao()
            val current = state.get(IngestionKeys.USAGE_CURSOR_MS)
            // A rescan re-derives the cursor from what it actually read, so a cursor stranded in the future by a
            // backward clock jump comes back to the present. Lowering the cursor is always safe: it only re-reads.
            val next = if (run.rescan) run.maxSeen!! else maxOf(current ?: batchMax, batchMax)
            state.put(IngestionStateEntity(IngestionKeys.USAGE_CURSOR_MS, next))
            // The rebuild request commits with the rows, so a death right after this batch can't strand them underived.
            DerivedDirty.mark(state, batchMin - DerivedDirty.HARVEST_LOOKBACK_MS)
            if (run.backfill) {
                val earliest = state.get(IngestionKeys.BACKFILL_EARLIEST_MS)
                state.put(IngestionStateEntity(IngestionKeys.BACKFILL_EARLIEST_MS, minOf(earliest ?: batchMin, batchMin)))
            }
            ids.count { it != -1L }.toLong()
        }
    }

    private suspend fun recordCoverage(begin: Long, now: Long, backfillFrom: Long?, gapFrom: Long?) {
        val dao = db.coverageDao()
        val kind = ServiceKind.USAGE_HARVEST.name
        if (backfillFrom != null) {
            dao.insert(CoverageIntervalEntity(serviceKind = kind, startMs = minOf(backfillFrom, now), endMs = now, status = CoverageStatus.BACKFILL.name, lastSeenMs = now))
        }
        if (gapFrom != null) {
            dao.insert(CoverageIntervalEntity(serviceKind = kind, startMs = gapFrom, endMs = now, status = CoverageStatus.GAP.name, lastSeenMs = now))
        }
        val latest = dao.latest(kind, CoverageStatus.ACTIVE.name)
        if (backfillFrom == null && gapFrom == null && latest != null && (latest.endMs ?: latest.lastSeenMs) >= begin) {
            // maxOf: after a backward clock jump "now" can be earlier than the interval's start or its recorded end.
            // A correction never shrinks coverage that was already recorded (09:00–12:00 stays 09:00–12:00).
            dao.update(latest.copy(endMs = maxOf(now, latest.startMs, latest.endMs ?: now), lastSeenMs = now))
        } else {
            // After a backfill or a gap, live coverage starts now; it never overlaps the BACKFILL or GAP interval.
            val start = if (backfillFrom != null || gapFrom != null) now else begin
            dao.insert(CoverageIntervalEntity(serviceKind = kind, startMs = start, endMs = now, status = CoverageStatus.ACTIVE.name, lastSeenMs = now))
        }
    }

    /**
     * Numbers identical events inside one millisecond, so two genuinely repeated events both survive while a
     * re-read of the same window maps onto the same keys. Scoped to one query window: windows split on timestamps,
     * so a millisecond is never split between two of them.
     */
    private class SeqAssigner {
        private val counts = HashMap<Key, Int>()

        fun next(ts: Long, type: Int, pkgId: Long, clsId: Long): Int {
            val key = Key(ts, type, pkgId, clsId)
            val n = counts[key] ?: 0
            counts[key] = n + 1
            return n
        }

        private data class Key(val ts: Long, val type: Int, val pkgId: Long, val clsId: Long)
    }

    companion object {
        private const val TAG = "LagrangeHarvest"
        private const val HOUR = 60L * 60 * 1000
        const val OVERLAP_MS = 60_000L
        const val BACKFILL_MS = 14L * 24 * HOUR
        const val RESCAN_MS = 24L * HOUR
        const val GAP_ALERT_MS = 48L * HOUR
        const val DEFAULT_WINDOW_MS = 6L * HOUR
        const val DEFAULT_BATCH_SIZE = 1_000
    }
}
