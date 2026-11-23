package com.celestiallemon.lagrange.data

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.FrictionEventEntity
import com.celestiallemon.lagrange.data.db.FrictionScheduleEntity
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
import com.celestiallemon.lagrange.domain.friction.AppFrictionOverride
import com.celestiallemon.lagrange.domain.friction.FrictionAppPolicy
import com.celestiallemon.lagrange.domain.friction.FrictionEventRecord
import com.celestiallemon.lagrange.domain.friction.FrictionLiveStatus
import com.celestiallemon.lagrange.domain.friction.FrictionRuntimeSnapshot
import com.celestiallemon.lagrange.domain.friction.FrictionSchedule
import com.celestiallemon.lagrange.domain.friction.FrictionSettings
import com.celestiallemon.lagrange.domain.friction.TodayUsageSnapshot
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** Commands sent from the UI (or future Quick Settings tile) to the running accessibility friction engine. */
sealed interface FrictionControlCommand {
    data object ActivateSnooze1h : FrictionControlCommand
    data object CancelSnooze : FrictionControlCommand
    data object ActivateFocusBoost1h : FrictionControlCommand
    data object CancelFocusBoost : FrictionControlCommand
}

/**
 * Persistence and live state bridge for the Gentle Friction engine:
 * - CRUD for `friction_schedules` (with `settings_history` logging),
 * - FK-safe transactional writes to `friction_events` + marking the logical day dirty for `SessionReconstructor`,
 * - real-time `TodayUsageSnapshot` using UTC day windows consistent with `DerivedRepository`,
 * - serialized monotonic runtime state & live control dispatch with zero lost commands during bind/teardown.
 */
class FrictionRepository(
    private val db: LagrangeDatabase,
    private val preferences: LagrangePreferences,
    private val interner: Interner,
    private val derived: DerivedRepository,
    private val ownPackage: String,
    private val onDirty: () -> Unit = {},
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val elapsedClock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val bootCountProvider: () -> Int = { 0 },
) {
    private val runtimeMutex = Mutex()
    /**
     * Registration of the bound service's command sink. Its handler returns `true` when the service applied the
     * command to its engine, or `false` while it is tearing down; declined commands are deferred and applied offline
     * on [detachLiveService], after the service's final queued snapshot, so they are neither lost nor overwritten.
     */
    class LiveRegistration internal constructor(internal val handler: suspend (FrictionControlCommand) -> Boolean)

    private var liveRegistration: LiveRegistration? = null
    private val deferredCommands = ArrayList<FrictionControlCommand>()

    private val _liveStatus = MutableStateFlow(FrictionLiveStatus())
    val liveStatus: StateFlow<FrictionLiveStatus> = _liveStatus.asStateFlow()

    // -- Schedules CRUD -----------------------------------------------------------------------------------------------

    fun observeSchedules(): Flow<List<FrictionScheduleEntity>> = db.derivedDao().observeSchedules()

    fun observeDomainSchedules(): Flow<List<FrictionSchedule>> =
        observeSchedules().map { list -> list.map { it.toDomain() } }

    suspend fun allSchedules(): List<FrictionSchedule> =
        db.derivedDao().allSchedules().map { it.toDomain() }

    suspend fun createSchedule(schedule: FrictionScheduleEntity): Long {
        validateSchedule(schedule)
        return db.withWriteTransaction {
            val id = db.derivedDao().insertSchedule(schedule.copy(id = 0))
            logScheduleHistory("create", schedule.copy(id = id))
            id
        }
    }

    suspend fun updateSchedule(schedule: FrictionScheduleEntity) {
        require(schedule.id > 0) { "schedule id required for update" }
        validateSchedule(schedule)
        db.withWriteTransaction {
            db.derivedDao().updateSchedule(schedule)
            logScheduleHistory("update", schedule)
        }
    }

    suspend fun setScheduleEnabled(id: Long, enabled: Boolean) {
        db.withWriteTransaction {
            val existing = db.derivedDao().scheduleById(id) ?: return@withWriteTransaction
            if (existing.enabled == enabled) return@withWriteTransaction
            db.derivedDao().setScheduleEnabled(id, enabled)
            logScheduleHistory("toggle", existing.copy(enabled = enabled))
        }
    }

    suspend fun deleteSchedule(id: Long) {
        db.withWriteTransaction {
            val existing = db.derivedDao().scheduleById(id) ?: return@withWriteTransaction
            db.derivedDao().deleteSchedule(id)
            logScheduleHistory("delete", existing)
        }
    }

    // -- Friction event logging ---------------------------------------------------------------------------------------

    /**
     * Writes a friction event and marks the affected time range dirty in `ingestion_state` within the same
     * write transaction so Phase 2's `SessionReconstructor` scrubs blocked attempts cleanly.
     *
     * Resolves [FrictionEventRecord.scheduleId] inside the transaction so deleting a schedule while an overlay is
     * open never trips the foreign-key constraint or drops the blocked-attempt record.
     */
    suspend fun recordEvent(record: FrictionEventRecord): Long {
        val id = db.withWriteTransaction {
            val dao = db.derivedDao()
            val validScheduleId = record.scheduleId?.takeIf { dao.scheduleById(it) != null }
            val inserted = dao.insertFrictionEvent(
                FrictionEventEntity(
                    timestampUtcMs = record.timestampUtcMs,
                    logicalDate = record.logicalDate,
                    pkgId = record.pkgId,
                    eventKind = record.eventKind,
                    scheduleId = validScheduleId,
                    escalationTier = record.escalationTier,
                    pauseDurationSec = record.pauseDurationSec,
                    postCountdownReactionMs = record.postCountdownReactionMs,
                    intentionTag = record.intentionTag,
                    selectedTimerMin = record.selectedTimerMin,
                    redirectPkgId = record.redirectPkgId,
                    outcome = record.outcome,
                ),
            )
            // Reach back slightly before the event so the session containing it is always rebuilt cleanly.
            DerivedDirty.mark(db.ingestionStateDao(), record.timestampUtcMs - DIRTY_LOOKBACK_MS)
            inserted
        }
        onDirty()
        return id
    }

    // -- Real-time usage context & package resolution -----------------------------------------------------------------

    suspend fun resolveAppPolicy(packageName: String): FrictionAppPolicy {
        val id = interner.packageId(packageName)
        val row = db.packageDao().byId(id)
        return row?.toAppPolicy() ?: FrictionAppPolicy(
            pkgId = id,
            packageName = packageName,
            displayName = packageName,
            category = AppCategory.NEUTRAL,
            override = null,
            isSystemSurface = false,
            categoryConfirmed = false,
            isInstalled = true,
        )
    }

    suspend fun allAppPolicies(): Map<String, FrictionAppPolicy> =
        db.packageDao().all().associate { it.packageName to it.toAppPolicy() }

    /**
     * Computes today's real opens for [pkgId] (excluding blocked attempts) and today's distracting time using the
     * logical day's UTC window (consistent with [DerivedRepository] across rollover/timezone changes).
     */
    suspend fun todayUsageSnapshot(
        pkgId: Long,
        nowMs: Long = wallClock(),
        unsyncedDistractingMs: Long = 0L,
    ): TodayUsageSnapshot {
        val prefs = preferences.current()
        val date = derived.dateOf(nowMs)
        val logicalDate = date.toString()
        val window = derived.windowOf(date)
        val dao = db.derivedDao()
        val dayRollup = dao.dailyRollup(logicalDate)
        val appRollup = dao.appRollup(logicalDate, pkgId)
        val derivedEndMs = db.ingestionStateDao().get(IngestionKeys.DERIVED_DATA_END_MS) ?: 0L
        val sinceMs = maxOf(window.startMs, derivedEndMs)
        val recentContinued = if (sinceMs < window.endMs) {
            dao.continuedLaunchPausesBetween(sinceMs, window.endMs, pkgId)
        } else {
            0
        }
        val realOpensToday = (appRollup?.openCount ?: 0) + recentContinued
        val distractingMs = (dayRollup?.distractingMs ?: 0L) + unsyncedDistractingMs.coerceAtLeast(0L)
        val promptsToday = dao.launchPauseCountBetween(window.startMs, window.endMs)
        return TodayUsageSnapshot(
            logicalDate = logicalDate,
            appRealOpensToday = realOpensToday,
            distractingMsToday = distractingMs,
            promptsShownToday = promptsToday,
            budgetMs = prefs.dailyBudgetMs,
        )
    }

    // -- Live status & serialized monotonic runtime controls ----------------------------------------------------------

    fun publishLiveStatus(status: FrictionLiveStatus) {
        _liveStatus.value = status
    }

    suspend fun loadRuntimeSnapshot(): FrictionRuntimeSnapshot? = preferences.loadFrictionRuntime()

    suspend fun saveRuntimeSnapshot(snapshot: FrictionRuntimeSnapshot?) {
        preferences.saveFrictionRuntime(snapshot)
    }

    /**
     * Under [runtimeMutex], loads the persisted monotonic snapshot, lets the service restore from it and registers
     * its command [handler] atomically, so no UI command can be dropped or race restore. Offline commands that ran
     * before this call are already on disk and therefore part of the restored snapshot.
     */
    suspend fun attachLiveService(
        handler: suspend (FrictionControlCommand) -> Boolean,
        restoreFromDisk: suspend (FrictionRuntimeSnapshot?) -> Unit,
    ): LiveRegistration = runtimeMutex.withLock {
        val saved = preferences.loadFrictionRuntime()
        restoreFromDisk(saved)
        LiveRegistration(handler).also { liveRegistration = it }
    }

    /**
     * Unregisters [registration] (a stale registration from an earlier connection is ignored) and applies any commands
     * the service declined while tearing down. Callers must invoke this after the service's last snapshot write.
     */
    suspend fun detachLiveService(registration: LiveRegistration) = runtimeMutex.withLock {
        if (liveRegistration !== registration) return@withLock
        liveRegistration = null
        val pending = deferredCommands.toList()
        deferredCommands.clear()
        pending.forEach { applyOffline(it) }
    }

    /** Hydrates [liveStatus] from persisted monotonic state (used at startup or when service isn't running). */
    suspend fun refreshLiveStatusFromDisk() = runtimeMutex.withLock {
        if (liveRegistration != null) return@withLock
        val boot = bootCountProvider()
        val nowElapsed = elapsedClock()
        val saved = preferences.loadFrictionRuntime()
        if (saved == null || saved.bootCount != boot || nowElapsed < saved.savedAtElapsedMs) {
            _liveStatus.value = FrictionLiveStatus()
            return@withLock
        }
        val snooze = saved.snoozeUntilElapsedMs?.takeIf { it > nowElapsed }
        val boost = saved.focusBoostUntilElapsedMs?.takeIf { it > nowElapsed }
        val sessionExpired = saved.screenOffAtElapsedMs != null &&
            (nowElapsed - saved.screenOffAtElapsedMs) >= FrictionSettings.MICRO_LOCK_MERGE_MS
        _liveStatus.value = FrictionLiveStatus(
            snoozeUntilElapsedMs = snooze,
            focusBoostUntilElapsedMs = boost,
            activeTimerRemainingMs = if (sessionExpired) null else saved.activeTimerRemainingMs,
            activeTimerInitialMin = if (sessionExpired) null else saved.activeTimerInitialMin,
            sessionDistractingMs = if (sessionExpired) 0L else saved.sessionDistractingMs,
            currentForegroundPackage = _liveStatus.value.currentForegroundPackage,
        )
    }

    /** Activates 1-hour snooze, serialized across live service and offline disk persistence. */
    suspend fun activateSnooze1h() = dispatch(FrictionControlCommand.ActivateSnooze1h)

    suspend fun cancelSnooze() = dispatch(FrictionControlCommand.CancelSnooze)

    suspend fun activateFocusBoost1h() = dispatch(FrictionControlCommand.ActivateFocusBoost1h)

    suspend fun cancelFocusBoost() = dispatch(FrictionControlCommand.CancelFocusBoost)

    private suspend fun dispatch(cmd: FrictionControlCommand) = runtimeMutex.withLock {
        val registration = liveRegistration
        when {
            registration == null -> applyOffline(cmd)
            registration.handler(cmd) -> Unit
            // Service is tearing down: apply after its final snapshot lands (see detachLiveService).
            else -> deferredCommands += cmd
        }
    }

    private suspend fun applyOffline(cmd: FrictionControlCommand) = when (cmd) {
        FrictionControlCommand.ActivateSnooze1h -> activateSnooze1hOffline()
        FrictionControlCommand.CancelSnooze -> cancelSnoozeOffline()
        FrictionControlCommand.ActivateFocusBoost1h -> activateFocusBoost1hOffline()
        FrictionControlCommand.CancelFocusBoost -> cancelFocusBoostOffline()
    }

    private suspend fun activateSnooze1hOffline() {
        val nowWall = wallClock()
        val nowElapsed = elapsedClock()
        val boot = bootCountProvider()
        val expiry = nowElapsed + FrictionSettings.SNOOZE_DURATION_MS
        val base = validSavedOrFresh(boot, nowElapsed)
        val clearExpiredTimer = base.activeTimerRemainingMs != null && base.activeTimerRemainingMs <= 0L
        val updated = base.copy(
            bootCount = boot,
            savedAtElapsedMs = nowElapsed,
            snoozeUntilElapsedMs = expiry,
            focusBoostUntilElapsedMs = null,
            activeTimerRemainingMs = if (clearExpiredTimer) null else base.activeTimerRemainingMs,
            activeTimerInitialMin = if (clearExpiredTimer) null else base.activeTimerInitialMin,
        )
        preferences.saveFrictionRuntime(updated)
        _liveStatus.value = _liveStatus.value.copy(
            snoozeUntilElapsedMs = expiry,
            focusBoostUntilElapsedMs = null,
            activeTimerRemainingMs = updated.activeTimerRemainingMs,
            activeTimerInitialMin = updated.activeTimerInitialMin,
        )
        val pkgId = interner.packageId(ownPackage)
        recordEvent(
            FrictionEventRecord(
                timestampUtcMs = nowWall,
                logicalDate = derived.dateOf(nowWall).toString(),
                pkgId = pkgId,
                eventKind = FrictionVocab.SNOOZE_1H,
                scheduleId = null,
                escalationTier = 0,
                pauseDurationSec = 0,
                postCountdownReactionMs = null,
                intentionTag = null,
                selectedTimerMin = null,
                redirectPkgId = null,
                outcome = FrictionVocab.OUTCOME_SNOOZE_ACTIVATED,
            ),
        )
    }

    private suspend fun cancelSnoozeOffline() {
        val nowElapsed = elapsedClock()
        val boot = bootCountProvider()
        val base = validSavedOrFresh(boot, nowElapsed)
        preferences.saveFrictionRuntime(base.copy(bootCount = boot, savedAtElapsedMs = nowElapsed, snoozeUntilElapsedMs = null))
        _liveStatus.value = _liveStatus.value.copy(snoozeUntilElapsedMs = null)
    }

    private suspend fun activateFocusBoost1hOffline() {
        val nowElapsed = elapsedClock()
        val boot = bootCountProvider()
        val expiry = nowElapsed + FrictionSettings.FOCUS_BOOST_DURATION_MS
        val base = validSavedOrFresh(boot, nowElapsed)
        val updated = base.copy(
            bootCount = boot,
            savedAtElapsedMs = nowElapsed,
            focusBoostUntilElapsedMs = expiry,
            snoozeUntilElapsedMs = null,
        )
        preferences.saveFrictionRuntime(updated)
        _liveStatus.value = _liveStatus.value.copy(focusBoostUntilElapsedMs = expiry, snoozeUntilElapsedMs = null)
    }

    private suspend fun cancelFocusBoostOffline() {
        val nowElapsed = elapsedClock()
        val boot = bootCountProvider()
        val base = validSavedOrFresh(boot, nowElapsed)
        preferences.saveFrictionRuntime(base.copy(bootCount = boot, savedAtElapsedMs = nowElapsed, focusBoostUntilElapsedMs = null))
        _liveStatus.value = _liveStatus.value.copy(focusBoostUntilElapsedMs = null)
    }

    private suspend fun validSavedOrFresh(boot: Int, nowElapsed: Long): FrictionRuntimeSnapshot {
        val saved = preferences.loadFrictionRuntime()
        return if (saved != null && saved.bootCount == boot && nowElapsed >= saved.savedAtElapsedMs) saved
        else FrictionRuntimeSnapshot(bootCount = boot, savedAtElapsedMs = nowElapsed)
    }

    private suspend fun logScheduleHistory(action: String, s: FrictionScheduleEntity) {
        val payload = buildJsonObject {
            put("action", JsonPrimitive(action))
            put("id", JsonPrimitive(s.id))
            put("name", JsonPrimitive(s.name))
            put("enabled", JsonPrimitive(s.enabled))
            put("days_mask", JsonPrimitive(s.daysOfWeekMask))
            put("start_min", JsonPrimitive(s.startMinuteOfDay))
            put("end_min", JsonPrimitive(s.endMinuteOfDay))
            put("escalation_boost", JsonPrimitive(s.escalationBoost))
            put("require_timer_prompt", JsonPrimitive(s.requireTimerPrompt))
            s.nudgeIntervalMin?.let { put("nudge_interval_min", JsonPrimitive(it)) }
        }
        db.settingsHistoryDao().insert(
            SettingsHistoryEntity(timestampUtcMs = wallClock(), settingKey = HISTORY_SCHEDULES, valueJson = payload.toString()),
        )
    }

    private fun validateSchedule(s: FrictionScheduleEntity) {
        require(s.name.isNotBlank()) { "Schedule name cannot be blank" }
        require(s.daysOfWeekMask in 1..FrictionSchedule.MASK_EVERY_DAY) { "Select at least one day of the week" }
        require(s.startMinuteOfDay in 0..1439 && s.endMinuteOfDay in 0..1439) { "Minutes must be 0..1439" }
        require(s.escalationBoost in 0..3) { "Escalation boost must be 0..3" }
        s.nudgeIntervalMin?.let { require(it in 1..120) { "Nudge interval override must be 1..120 minutes" } }
    }

    companion object {
        const val HISTORY_SCHEDULES = "friction_schedules"
        private const val DIRTY_LOOKBACK_MS = 60_000L

        fun FrictionScheduleEntity.toDomain() = FrictionSchedule(
            id = id,
            name = name,
            enabled = enabled,
            daysOfWeekMask = daysOfWeekMask,
            startMinuteOfDay = startMinuteOfDay,
            endMinuteOfDay = endMinuteOfDay,
            escalationBoost = escalationBoost,
            requireTimerPrompt = requireTimerPrompt,
            nudgeIntervalMin = nudgeIntervalMin,
        )

        fun PackageEntity.toAppPolicy() = FrictionAppPolicy(
            pkgId = id,
            packageName = packageName,
            displayName = displayName,
            category = runCatching { AppCategory.valueOf(category) }.getOrDefault(AppCategory.NEUTRAL),
            override = AppFrictionOverride.parse(frictionOverrideJson),
            isSystemSurface = isLauncherOrSystemUi,
            categoryConfirmed = categoryConfirmed,
            isInstalled = isInstalled,
        )
    }
}
