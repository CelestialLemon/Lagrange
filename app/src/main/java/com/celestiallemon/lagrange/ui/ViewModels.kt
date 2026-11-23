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
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

private val Application.graph get() = (this as LagrangeApplication).graph

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

/** App-wide state: preferences, permission health and collector statistics. */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    val prefs: StateFlow<UserPrefs?> = graph.preferences.prefs.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _health = MutableStateFlow(PermissionHealthChecker.check(app))
    val health: StateFlow<PermissionHealth> = _health

    val harvesting: StateFlow<Boolean> = graph.harvester.running
    val lastHarvest: StateFlow<HarvestResult?> = graph.harvester.lastResult

    private val minuteTicks = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(60_000)
        }
    }

    /** Start of the current logical day; moves forward when the day rolls over while the app is open. */
    private val dayStart = combine(graph.preferences.prefs.map { it.rolloverHour }.distinctUntilChanged(), minuteTicks) { hour, now ->
        LogicalDay.startMs(now, hour, ZoneId.systemDefault())
    }.distinctUntilChanged()

    val stats: StateFlow<CollectorStats> = run {
        val db = graph.database
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
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CollectorStats())
    }

    fun refreshHealth() {
        _health.value = PermissionHealthChecker.check(getApplication())
    }

    /** App-open catch-up: harvest whatever happened since the last run and record the current zone. */
    fun onAppForeground() {
        refreshHealth()
        viewModelScope.launch { graph.timezoneTracker.check() }
        graph.harvestInBackground(HarvestTrigger.APP_OPEN)
    }

    fun harvestNow() = graph.harvestInBackground(HarvestTrigger.MANUAL)

    fun setRolloverHour(hour: Int) = viewModelScope.launch { graph.preferences.setRolloverHour(hour) }

    fun setDynamicColor(enabled: Boolean) = viewModelScope.launch { graph.preferences.setUseDynamicColor(enabled) }

    val restoreNotice: StateFlow<String?> = graph.restoreNotice

    fun dismissRestoreNotice() {
        graph.restoreNotice.value = null
    }
}

/** The categorization review used by onboarding and the Friction tab. */
class CatalogViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val recentOpens = flow {
        emit(graph.catalog.recentOpens(System.currentTimeMillis() - UsageStatsHarvester.BACKFILL_MS))
    }

    val apps: StateFlow<List<CatalogApp>?> = graph.catalog.observeReviewList(recentOpens)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    init {
        viewModelScope.launch { runCatching { graph.catalog.syncLaunchable() } }
    }

    fun setCategory(packageName: String, category: AppCategory) = viewModelScope.launch {
        graph.catalog.setCategory(packageName, category)
    }

    fun confirmAndFinishOnboarding() = viewModelScope.launch {
        graph.catalog.confirmAll(apps.value.orEmpty().map { it.packageName })
        graph.preferences.setOnboardingComplete(true)
    }
}

/** Status of a long-running data operation (backup, restore, export, reset). */
data class DataOpState(val busy: Boolean = false, val message: String? = null, val isError: Boolean = false)

class DataViewModel(app: Application) : AndroidViewModel(app) {
    private val graph = app.graph

    private val _op = MutableStateFlow(DataOpState())
    val op: StateFlow<DataOpState> = _op

    private val _restoreStaged = MutableStateFlow(graph.restore.hasStagedRestore())
    val restoreStaged: StateFlow<Boolean> = _restoreStaged

    private val _probe = MutableStateFlow<ProbeReport?>(null)
    val probe: StateFlow<ProbeReport?> = _probe

    private fun run(start: String, block: suspend () -> String) {
        if (_op.value.busy) return
        _op.value = DataOpState(busy = true, message = start)
        viewModelScope.launch {
            _op.value = try {
                DataOpState(message = block())
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

    fun stageRestore(uri: Uri) = run("Validating backup…") {
        val m = graph.backups.stageRestore(uri)
        _restoreStaged.value = true
        "Backup verified (created ${formatDateTime(m.createdAtUtcMs)}). Restart to apply it."
    }

    fun cancelRestore() {
        graph.restore.cancelStaged()
        _restoreStaged.value = false
        _op.value = DataOpState(message = "Staged restore discarded")
    }

    fun restartToApplyRestore() = AppRestarter.restart(getApplication())

    fun setBackupFolder(uri: Uri) = run("Saving backup folder…") {
        val app = getApplication<Application>()
        app.contentResolver.takePersistableUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
        )
        graph.preferences.setBackupTreeUri(uri.toString())
        WorkScheduler.setWeeklyBackup(app, enabled = true, replace = true)
        val name = graph.backups.backUpToFolder(uri)
        "Weekly backups on · first backup written: $name"
    }

    fun backUpToFolderNow() = run("Writing backup to folder…") {
        val tree = graph.preferences.current().backupTreeUri ?: error("Pick a backup folder first.")
        "Backup written: ${graph.backups.backUpToFolder(Uri.parse(tree))}"
    }

    fun clearBackupFolder() = run("Turning off weekly backups…") {
        val app = getApplication<Application>()
        graph.preferences.current().backupTreeUri?.let { uri ->
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(uri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        graph.preferences.setBackupTreeUri(null)
        WorkScheduler.setWeeklyBackup(app, enabled = false)
        "Weekly backups off"
    }

    fun runProbe() = run("Scanning 30 days of usage history…") {
        val report = UsageEventProbe.run(graph.usageSource, System.currentTimeMillis())
            ?: error("Usage Access is needed to probe usage history.")
        _probe.value = report
        "Probe complete · ${report.totalEvents} events"
    }

    fun resetAll() = run("Deleting all data…") {
        val app = getApplication<Application>()
        DataReset(app, graph).resetAll()
        AppRestarter.restart(app)
        "Reset"
    }

    fun clearMessage() {
        if (!_op.value.busy) _op.value = DataOpState()
    }
}
