package com.celestiallemon.lagrange.data

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.TzHistoryEntity
import java.time.ZoneId
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps `tz_history` in step with the device zone. Events are never stamped with an offset at harvest time;
 * local time is resolved later from this history using the zone rules at each event's own instant.
 */
class TimezoneTracker(
    private val db: LagrangeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private val lock = Mutex()

    /** Records the current zone if it differs from the latest entry. Returns true when a row was written. */
    suspend fun check(): Boolean = lock.withLock {
        val now = clock()
        val current = zone().id
        db.withWriteTransaction {
            val dao = db.tzHistoryDao()
            val latest = dao.latest()
            when {
                latest == null -> {
                    // Backfilled days predate the app, so their zone is an assumption, marked as such.
                    dao.insert(TzHistoryEntity(effectiveFromUtcMs = now - ASSUMED_BACKFILL_MS, zoneId = current, isAssumed = true))
                    dao.insert(TzHistoryEntity(effectiveFromUtcMs = now, zoneId = current, isAssumed = false))
                    true
                }
                latest.zoneId != current -> {
                    dao.insert(TzHistoryEntity(effectiveFromUtcMs = now, zoneId = current, isAssumed = false))
                    true
                }
                else -> false
            }
        }
    }

    companion object {
        /** Slightly more than the 14-day backfill window. */
        const val ASSUMED_BACKFILL_MS = 15L * 24 * 60 * 60 * 1000
    }
}
