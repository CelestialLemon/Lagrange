package com.celestiallemon.lagrange.data

import com.celestiallemon.lagrange.data.db.IngestionStateDao
import com.celestiallemon.lagrange.data.db.IngestionStateEntity

/**
 * The pending derived-rebuild range in `ingestion_state`. Writers call [mark] inside the same transaction that
 * commits the raw rows it covers, so a process death can never keep the rows and lose the request to derive them.
 */
object DerivedDirty {
    /** Merges `[fromMs, toMs]` into the pending range (min of starts, max of ends). Call inside a write transaction. */
    suspend fun mark(state: IngestionStateDao, fromMs: Long, toMs: Long = Long.MAX_VALUE) {
        val pf = state.get(IngestionKeys.DERIVED_PENDING_FROM_MS)
        val pt = state.get(IngestionKeys.DERIVED_PENDING_TO_MS)
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_PENDING_FROM_MS, minOf(pf ?: fromMs, fromMs)))
        state.put(IngestionStateEntity(IngestionKeys.DERIVED_PENDING_TO_MS, maxOf(pt ?: toMs, toMs)))
    }

    /** How far before newly harvested events a rebuild must reach: episodes still open then, late notification data. */
    const val HARVEST_LOOKBACK_MS = 12 * 60 * 60 * 1000L

    /** Every harvest also re-derives at least the last 28 hours (notification and listener data arrive outside it). */
    const val HARVEST_TRAIL_MS = 28 * 60 * 60 * 1000L
}
