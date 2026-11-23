package com.celestiallemon.lagrange.data

/** Keys in the `ingestion_state` table. */
object IngestionKeys {
    /** Timestamp of the latest usage event ingested. Advances only together with the rows it describes. */
    const val USAGE_CURSOR_MS = "usage_cursor_ms"
    const val LAST_HARVEST_AT_MS = "last_harvest_at_ms"
    const val FIRST_HARVEST_AT_MS = "first_harvest_at_ms"
    const val LAST_HARVEST_EVENT_COUNT = "last_harvest_event_count"
    /** Present when the wall clock was changed and the next harvest must rescan the last 24 hours. */
    const val RESCAN_REQUESTED_AT_MS = "rescan_requested_at_ms"
}
