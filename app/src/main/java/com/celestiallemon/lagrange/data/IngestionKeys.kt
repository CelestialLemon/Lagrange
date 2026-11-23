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

    /**
     * Where the claimed run's baseline refresh must start (UTC ms). Set when a range is claimed and kept until the
     * refresh commits, so a run killed after its last chunk still refreshes every rebuilt day's baselines.
     */
    const val DERIVED_BASELINE_FROM_MS = "derived_baseline_from_ms"

    /**
     * The settings the derived tables were last reconciled against. Compared at startup and on every change, so an
     * edit made while the pipeline was down (or the process died before reacting) still queues its rebuild.
     */
    const val DERIVED_ROLLOVER_HOUR = "derived_rollover_hour"
    const val DERIVED_BUDGET_MS = "derived_budget_ms"
    /** Hash of (package id, category) for every package up to [DERIVED_CATEGORY_MAX_ID]: a change means a recategorization. */
    const val DERIVED_CATEGORY_HASH = "derived_category_hash"
    const val DERIVED_CATEGORY_MAX_ID = "derived_category_max_id"

    /** The reconstruction algorithm version the derived tables were built with. */
    const val DERIVED_ALGORITHM_VERSION = "derived_algorithm_version"
    const val DERIVED_LAST_RUN_AT_MS = "derived_last_run_at_ms"

    /** Collection cutoff (`dataEnd`) up to which the derived tables were last rebuilt through the tail. */
    const val DERIVED_DATA_END_MS = "derived_data_end_ms"

    /** The rollover hour the currently stored derived tables were completely rebuilt under. */
    const val DERIVED_APPLIED_ROLLOVER_HOUR = "derived_applied_rollover_hour"
}
