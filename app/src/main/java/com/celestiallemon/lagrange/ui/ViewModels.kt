package com.celestiallemon.lagrange.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.data.CatalogApp
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageIntervalEntity
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.domain.LogicalDay
import com.celestiallemon.lagrange.harvest.HarvestResult
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import com.celestiallemon.lagrange.harvest.ProbeReport
import com.celestiallemon.lagrange.harvest.UsageEventProbe
import com.celestiallemon.lagrange.harvest.UsageStatsHarvester
import com.celestiallemon.lagrange.harvest.WorkScheduler
import com.celestiallemon.lagrange.system.AppRestarter
import com.celestiallemon.lagrange.system.DataReset
import com.celestiallemon.lagrange.system.PermissionHealth
import com.celestiallemon.lagrange.system.PermissionHealthChecker
import java.time.ZoneId
import android.util.Log
import com.celestiallemon.lagrange.AppGraph
import com.celestiallemon.lagrange.StartupFailedException
import com.celestiallemon.lagrange.StartupGate
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

internal val Application.graph get() = (this as LagrangeApplication).graph

/** What the collectors have gathered so far, for Today and System & Data. */
data class CollectorStats(
    val rawEventsTotal: Long = 0,
    val rawEventsToday: Long = 0,
    val notificationsTotal: Long = 0,
    val notificationsToday: Long = 0,
    val earliestEventMs: Long? = null,
    val lastHarvestAtMs: Long? = null,
    val cursorMs: Long? = null,
    val listenerActive: Boolean = false,
    val gapCount: Int = 0,
    val backfill: CoverageIntervalEntity? = null,
) {
    fun harvestOverdue(nowMs: Long): Boolean =
        lastHarvestAtMs != null && nowMs - lastHarvestAtMs > UsageStatsHarvester.GAP_ALERT_MS
}

/**
 * Waits for startup (database verified, restored preferences applied), then mirrors [source]. If startup failed it
 * never emits: the UI shows the recovery error from [AppGraph.startupState] instead.
 */
internal fun <T> AppGraph.afterReady(source: AppGraph.() -> Flow<T>): Flow<T> = flow {
    try {
        awaitReady()
    } catch (_: StartupFailedException) {
        awaitCancellation()
    }
    emitAll(source())
}.flowOn(Dispatchers.IO)

/** App-wide state: preferences, permission health and collector statistics. Never touches the database on Main. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    /** Null until startup has applied any restored preferences, so the UI never flashes stale settings. */
    val prefs: StateFlow<UserPrefs?> = graph.afterReady { preferences.prefs }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _health = MutableStateFlow(PermissionHealthChecker.check(app))
    val health: StateFlow<PermissionHealth> = _health

    init {
        viewModelScope.launch {
            com.celestiallemon.lagrange.friction.LagrangeAccessibilityService.readyState.collect {
                _health.value = PermissionHealthChecker.check(getApplication())
            }
        }
    }

    val harvesting: StateFlow<Boolean> = graph.afterReady { harvester.running }.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val lastHarvest: StateFlow<HarvestResult?> = graph.afterReady { harvester.lastResult }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val minuteTicks = minuteTickFlow()

    val stats: StateFlow<CollectorStats> = graph.afterReady {
        // Start of the current logical day; moves forward when the day rolls over while the app is open.
        val dayStart = combine(preferences.prefs.map { it.rolloverHour }.distinctUntilChanged(), minuteTicks) { hour, now ->
            LogicalDay.startMs(now, hour, ZoneId.systemDefault())
        }.distinctUntilChanged()
        val db = database
        val counts = combine(
            db.rawUsageEventDao().observeCount(),
            dayStart.flatMapLatest { db.rawUsageEventDao().observeCountSince(it) },
            db.rawNotificationDao().observeCount(),
            dayStart.flatMapLatest { db.rawNotificationDao().observeCountSince(it) },
            db.rawUsageEventDao().observeEarliest(),
        ) { total, today, nTotal, nToday, earliest -> CollectorStats(total, today, nTotal, nToday, earliest) }
        combine(
            counts,
            db.ingestionStateDao().observe(IngestionKeys.LAST_HARVEST_AT_MS),
            db.ingestionStateDao().observe(IngestionKeys.USAGE_CURSOR_MS),
            db.coverageDao().observeAll(),
        ) { c, lastHarvest, cursor, coverage ->
            c.copy(
                lastHarvestAtMs = lastHarvest,
                cursorMs = cursor,
                listenerActive = coverage.any { it.serviceKind == ServiceKind.NOTIFICATION_LISTENER.name && it.endMs == null },
                gapCount = coverage.count { it.status == CoverageStatus.GAP.name },
                backfill = coverage.firstOrNull { it.status == CoverageStatus.BACKFILL.name },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectorStats())

    fun refreshHealth() {
        _health.value = PermissionHealthChecker.check(getApplication())
    }

    /** App-open catch-up: harvest whatever happened since the last run, record the zone, extend listener coverage. */
    fun onAppForeground() {
        refreshHealth()
        viewModelScope.launch {
            try {
                graph.awaitReady()
                withContext(Dispatchers.IO) {
                    graph.timezoneTracker.check()
                    graph.heartbeatListenerIfConnected()
                }
                // Usage Access may have been revoked or the day rolled over while away: redraw the widget and tile.
                graph.surfaces.requestRefresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "foreground catch-up failed", e)
            }
        }
        graph.harvestInBackground(HarvestTrigger.APP_OPEN)
    }

    fun harvestNow() = graph.harvestInBackground(HarvestTrigger.MANUAL)

    fun setRolloverHour(hour: Int) = launchIo { graph.preferences.setRolloverHour(hour) }

    fun setDynamicColor(enabled: Boolean) = launchIo { graph.preferences.setUseDynamicColor(enabled) }

    private fun launchIo(block: suspend () -> Unit) = viewModelScope.launch {
        try {
            graph.awaitReady()
            withContext(Dispatchers.IO) { block() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "preference update failed", e)
        }
    }

    val restoreNotice: StateFlow<String?> = graph.restoreNotice

    /** Starting, Ready, or Failed with a message: a failed recovery keeps every data screen closed. */
    val startupState: StateFlow<StartupGate.State> = graph.startupState

    fun restartApp() = AppRestarter.restart(getApplication())

    private val _destination = MutableStateFlow<com.celestiallemon.lagrange.Destination?>(null)

    /** A tab another surface asked to open (widget, tile, digest); the shell consumes it once. */
    val destination: StateFlow<com.celestiallemon.lagrange.Destination?> = _destination

    fun openDestination(destination: com.celestiallemon.lagrange.Destination) {
        _destination.value = destination
    }

    fun consumeDestination() {
        _destination.value = null
    }

    fun dismissRestoreNotice() {
        graph.restoreNotice.value = null
    }
}

/** The categorization review used by onboarding and the Friction tab. */
@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val _synced = MutableStateFlow(false)

    /** True once the launchable-app sync has finished (successfully or not): the list is as complete as it gets. */
    val synced: StateFlow<Boolean> = _synced

    /** Recomputed whenever the ledger grows (the first backfill lands while onboarding is open). */
    private val recentOpens: Flow<Map<Long, Long>> = graph.afterReady { database.rawUsageEventDao().observeCount() }
        .conflate()
        .mapLatest { graph.catalog.recentOpens(System.currentTimeMillis() - UsageStatsHarvester.BACKFILL_MS) }
        .flowOn(Dispatchers.IO)

    val apps: StateFlow<List<CatalogApp>?> = graph.afterReady { catalog.observeReviewList(recentOpens) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch {
            try {
                graph.awaitReady()
                withContext(Dispatchers.IO) { graph.catalog.syncLaunchable() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "app list sync failed", e)
            }
            _synced.value = true
        }
    }

    fun setCategory(packageName: String, category: AppCategory) = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.catalog.setCategory(packageName, category) }
    }

    fun setFrictionOverride(packageName: String, override: com.celestiallemon.lagrange.domain.friction.AppFrictionOverride?) = viewModelScope.launch {
        withContext(Dispatchers.IO) { graph.catalog.setFrictionOverride(packageName, override) }
    }

    fun confirmAndFinishOnboarding() = viewModelScope.launch {
        if (!_synced.value) return@launch
        withContext(Dispatchers.IO) {
            graph.catalog.confirmAll(apps.value.orEmpty().map { it.packageName })
            graph.preferences.setOnboardingComplete(true)
        }
    }
}

/** Status of a long-running data operation (backup, restore, export, reset). */
data class DataOpState(val busy: Boolean = false, val message: String? = null, val isError: Boolean = false)

class DataViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val _op = MutableStateFlow(DataOpState())
    val op: StateFlow<DataOpState> = _op

    private val _restoreStaged = MutableStateFlow(false)
    val restoreStaged: StateFlow<Boolean> = _restoreStaged

    private val _probe = MutableStateFlow<ProbeReport?>(null)
    val probe: StateFlow<ProbeReport?> = _probe

    init {
        viewModelScope.launch { _restoreStaged.value = withContext(Dispatchers.IO) { graph.restore.hasStagedRestore() } }
    }

    private fun run(start: String, block: suspend () -> String) {
        if (_op.value.busy) return
        _op.value = DataOpState(busy = true, message = start)
        viewModelScope.launch {
            _op.value = try {
                graph.awaitReady()
                DataOpState(message = block())
            } catch (e: CancellationException) {
                _op.value = DataOpState()
                throw e
            } catch (e: Exception) {
                DataOpState(message = e.message ?: e.javaClass.simpleName, isError = true)
            }
        }
    }

    fun backUpTo(uri: Uri) = run("Creating backup…") {
        val m = graph.backups.backUpTo(uri)
        "Backup saved · schema v${m.schemaVersion} · ${formatBytes(m.files.sumOf { it.size })}"
    }

    fun exportSqlite(uri: Uri) = run("Exporting database…") {
        graph.backups.exportRawSqliteTo(uri)
        "Raw .sqlite exported"
    }

    fun exportCsv(uri: Uri) = run("Exporting CSV bundle…") {
        val summary = graph.backups.exportCsvBundleTo(uri)
        "CSV bundle exported · ${summary.rowsPerFile.size} files · ${formatCount(summary.totalRows)} rows"
    }

    fun stageRestore(uri: Uri) = run("Validating backup…") {
        val m = graph.backups.stageRestore(uri)
        _restoreStaged.value = true
        "Backup verified (created ${formatDateTime(m.createdAtUtcMs)}). Restart to apply it."
    }

    /** Runs through [run] (one data operation at a time) and the backup lock, so it can't race a stage in flight. */
    fun cancelRestore() = run("Discarding staged restore…") {
        graph.backups.cancelStagedRestore()
        _restoreStaged.value = false
        "Staged restore discarded"
    }

    fun restartToApplyRestore() = AppRestarter.restart(getApplication())

    /**
     * Writes the first backup into the newly picked folder before anything else changes. Only once it succeeded is
     * the old folder's grant released, the new folder saved, and the weekly job (re)scheduled a week out.
     */
    fun setBackupFolder(uri: Uri) = run("Writing first backup to the new folder…") {
        val app = getApplication<Application>()
        val resolver = app.contentResolver
        val old = graph.preferences.current().backupTreeUri
        val isNew = old != uri.toString()
        withContext(Dispatchers.IO) { resolver.takePersistableUriPermission(uri, GRANT_FLAGS) }
        val name = try {
            graph.backups.backUpToFolder(uri, automatic = false)
        } catch (e: Throwable) {
            if (isNew) withContext(NonCancellable + Dispatchers.IO) { runCatching { resolver.releasePersistableUriPermission(uri, GRANT_FLAGS) } }
            throw e
        }
        if (old != null && isNew) withContext(Dispatchers.IO) { runCatching { resolver.releasePersistableUriPermission(Uri.parse(old), GRANT_FLAGS) } }
        graph.preferences.setBackupTreeUri(uri.toString())
        WorkScheduler.setWeeklyBackup(app, enabled = true, replace = true)
        "Weekly backups on · first backup written: $name"
    }

    fun backUpToFolderNow() = run("Writing backup to folder…") {
        val tree = graph.preferences.current().backupTreeUri ?: error("Pick a backup folder first.")
        "Backup written: ${graph.backups.backUpToFolder(Uri.parse(tree), automatic = false)}"
    }

    fun clearBackupFolder() = run("Turning off weekly backups…") {
        val app = getApplication<Application>()
        WorkScheduler.setWeeklyBackup(app, enabled = false)
        graph.preferences.current().backupTreeUri?.let { uri ->
            withContext(Dispatchers.IO) { runCatching { app.contentResolver.releasePersistableUriPermission(Uri.parse(uri), GRANT_FLAGS) } }
        }
        graph.preferences.setBackupTreeUri(null)
        "Weekly backups off"
    }

    fun runProbe() = run("Scanning 30 days of usage history…") {
        val report = UsageEventProbe.run(graph.usageSource, System.currentTimeMillis())
            ?: error("Usage Access is needed to probe usage history.")
        _probe.value = report
        "Probe complete · ${report.totalEvents} events"
    }

    /** Stops background work, releases folder grants and restarts; the wipe itself happens before Room reopens. */
    fun resetAll() = run("Deleting all data…") {
        val app = getApplication<Application>()
        // Refuses new backups, cancels jobs and waits out any backup still writing before grants go and we exit.
        DataReset(app, backups = graph.backups).requestReset()
        AppRestarter.restart(app)
        "Reset"
    }

    fun clearMessage() {
        if (!_op.value.busy) _op.value = DataOpState()
    }

    private companion object {
        const val GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }
}

/** The wall clock, once a minute (and immediately on collection). */
internal fun minuteTickFlow(): Flow<Long> = flow {
    while (true) {
        emit(System.currentTimeMillis())
        delay(60_000)
    }
}

private const val TAG = "LagrangeUi"
