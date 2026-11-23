package com.celestiallemon.lagrange.data

/** Keys in the `ingestion_state` table. */
object IngestionKeys {
    /** Timestamp of the latest usage event ingested. Advances only together with the rows it describes. */
    const val USAGE_CURSOR_MS = "usage_cursor_ms"
    const val LAST_HARVEST_AT_MS = "last_harvest_at_ms"

    /** Set once the initial backfill has fully completed; until then every run continues the backfill. */
    const val FIRST_HARVEST_AT_MS = "first_harvest_at_ms"
    const val LAST_HARVEST_EVENT_COUNT = "last_harvest_event_count"

    /** Earliest event seen while backfilling, kept across interrupted runs: where history really starts. */
    const val BACKFILL_EARLIEST_MS = "backfill_earliest_ms"

    /** Bumped each time the wall clock changes; a harvest rescans the last 24 hours while it is ahead of [RESCAN_DONE_GENERATION]. */
    const val RESCAN_GENERATION = "rescan_generation"

    /** The newest [RESCAN_GENERATION] a completed harvest has rescanned for. */
    const val RESCAN_DONE_GENERATION = "rescan_done_generation"

    /** Legacy rescan flag from the first build; still honoured if present. */
    const val RESCAN_REQUESTED_AT_MS = "rescan_requested_at_ms"

    /** "Delete all data" time: nothing that happened before it is ever harvested again. */
    const val RESET_CUTOFF_MS = "reset_cutoff_ms"

    /** Derived-table recompute requested from here (UTC ms); merged by min with later requests. */
    const val DERIVED_PENDING_FROM_MS = "derived_pending_from_ms"

    /** ...up to here (UTC ms; Long.MAX_VALUE for "through today"); merged by max. */
    const val DERIVED_PENDING_TO_MS = "derived_pending_to_ms"

    /** The range a recompute is working through; advanced chunk by chunk so a killed run resumes where it stopped. */
    const val DERIVED_ACTIVE_FROM_MS = "derived_active_from_ms"
    const val DERIVED_ACTIVE_TO_MS = "derived_active_to_ms"

    /** The reconstruction algorithm version the derived tables were built with. */
    const val DERIVED_ALGORITHM_VERSION = "derived_algorithm_version"
    const val DERIVED_LAST_RUN_AT_MS = "derived_last_run_at_ms"
}
