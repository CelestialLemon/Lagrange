package com.celestiallemon.lagrange.data

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.CoverageIntervalEntity
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.ServiceKind

/** Records when long-running collectors (notification listener, later accessibility) were actually alive. */
class CoverageRepository(private val db: LagrangeDatabase) {
    private val dao get() = db.coverageDao()

    /**
     * Opens a fresh ACTIVE interval. Any interval still open from a previous run (the process was killed without a
     * disconnect callback) is closed at its last heartbeat, the last moment it was provably alive.
     */
    suspend fun open(kind: ServiceKind, nowMs: Long) = db.withWriteTransaction {
        closeDangling(kind)
        dao.insert(
            CoverageIntervalEntity(
                serviceKind = kind.name,
                startMs = nowMs,
                endMs = null,
                status = CoverageStatus.ACTIVE.name,
                lastSeenMs = nowMs,
            ),
        )
    }

    suspend fun close(kind: ServiceKind, nowMs: Long) = db.withWriteTransaction {
        for (row in dao.open(kind.name)) dao.update(row.copy(endMs = maxOf(nowMs, row.startMs), lastSeenMs = nowMs))
    }

    suspend fun heartbeat(kind: ServiceKind, nowMs: Long) = dao.heartbeat(kind.name, nowMs)

    private suspend fun closeDangling(kind: ServiceKind) {
        for (row in dao.open(kind.name)) dao.update(row.copy(endMs = maxOf(row.lastSeenMs, row.startMs)))
    }
}
