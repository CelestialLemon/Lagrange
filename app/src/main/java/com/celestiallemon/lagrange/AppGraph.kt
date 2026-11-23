package com.celestiallemon.lagrange

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.celestiallemon.lagrange.backup.LagrangeBackups
import com.celestiallemon.lagrange.backup.RestoreCoordinator
import com.celestiallemon.lagrange.data.AndroidPackageMetaResolver
import com.celestiallemon.lagrange.data.AppCatalogRepository
import com.celestiallemon.lagrange.data.CoverageRepository
import com.celestiallemon.lagrange.data.DerivedRepository
import com.celestiallemon.lagrange.data.DerivedSettings
import com.celestiallemon.lagrange.data.FrictionRepository
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.TimezoneTracker
import com.celestiallemon.lagrange.data.TrendsRepository
import com.celestiallemon.lagrange.data.LabRepository
import com.celestiallemon.lagrange.data.CheckInStore
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.digest.DigestNotifier
import com.celestiallemon.lagrange.digest.DigestPoster
import com.celestiallemon.lagrange.digest.DigestRepository
import com.celestiallemon.lagrange.digest.DigestScheduler
import com.celestiallemon.lagrange.digest.DigestService
import com.celestiallemon.lagrange.domain.digest.Digest
import com.celestiallemon.lagrange.domain.digest.DigestKind
import com.celestiallemon.lagrange.friction.LagrangeAccessibilityService
import com.celestiallemon.lagrange.harvest.AndroidUsageEventSource
import com.celestiallemon.lagrange.harvest.HarvestResult
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import com.celestiallemon.lagrange.harvest.UsageStatsHarvester
import com.celestiallemon.lagrange.notifications.NotificationRecorder
import com.celestiallemon.lagrange.harvest.WorkScheduler
import com.celestiallemon.lagrange.notifications.LagrangeNotificationListenerService
import com.celestiallemon.lagrange.system.DataReset
import com.celestiallemon.lagrange.widget.SurfaceRefresher
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/**
 * Hand-wired dependencies, one instance per process.
 *
 * Nothing here may touch the database from the main thread. [start] runs filesystem recovery (a pending reset
 * wipe, a restore swap or rollback), opens (and, after a restore, verifies) the database, records a reset cutoff and
 * applies restored preferences, all on the IO dispatcher behind [gate]. Everything that reads or writes data first
 * calls [awaitReady]. If any recovery step fails the gate stays shut: Room is never opened over a partial
 * generation, workers and collectors stay idle, and the UI shows [startupState].
 */
class AppGraph(private val app: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val restore = RestoreCoordinator(app)

    private val gate = StartupGate()

    /** Starting, Ready, or Failed with a message for the user. */
    val startupState: StateFlow<StartupGate.State> = gate.state

    /**
     * Suspends until startup (recovery, database open/verify, restored preferences) has finished. Throws
     * [StartupFailedException] when it failed, so callers never touch data from a generation that wasn't recovered.
     */
    suspend fun awaitReady() = gate.awaitReady()

    private val housekeepingDone = CompletableDeferred<Unit>()

    /**
     * [awaitReady], then waits for startup housekeeping too: friction live status read from disk, the widget/tile
     * refresher started and the digest schedule checked once. Broadcast receivers await this before they finish, so
     * the process isn't frozen with that work half done.
     */
    suspend fun awaitStarted() {
        awaitReady()
        housekeepingDone.await()
    }

    /** First touched by [start] on the IO dispatcher; other callers come after [awaitReady]. */
    val database: LagrangeDatabase by lazy { openDatabase() }

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            app.preferencesDataStoreFile("lagrange_prefs")
        }
    }

    val preferences: LagrangePreferences by lazy { LagrangePreferences(dataStore, database) }

    val usageSource by lazy { AndroidUsageEventSource(app) }

    private val packageResolver by lazy { AndroidPackageMetaResolver(app) }

    val interner by lazy { Interner(database, packageResolver) }

    val harvester by lazy { UsageStatsHarvester(database, usageSource, interner) }

    val coverage by lazy { CoverageRepository(database) }

    val notificationRecorder by lazy {
        NotificationRecorder(database, interner, coverage, NotificationRecorder.loadOrCreateKey(File(app.noBackupFilesDir, "pseudonym.key")))
    }

    val timezoneTracker by lazy { TimezoneTracker(database) }

    /** Sessions, segments and rollups, rebuilt from the raw ledger whenever something they depend on changes. */
    private val derivedSettings: suspend () -> DerivedSettings = { preferences.current().let { DerivedSettings(it.rolloverHour, it.dailyBudgetMs) } }

    val derived by lazy { DerivedRepository(database, settings = derivedSettings) }

    /** Trends, the apps breakdown and the per-app deep dive, read from the derived tables. */
    val trends by lazy { TrendsRepository(database, derived) }

    /** The Behavior Lab's sections and the "Was that worth it?" check-in. */
    val lab by lazy {
        LabRepository(
            database,
            derived,
            checkInStore = object : CheckInStore {
                override val changes = preferences.worthCheckInLog
                override suspend fun update(transform: (String?) -> String?) {
                    preferences.updateWorthCheckInLog(transform)
                }
            },
        )
    }

    /** Schedules, friction event logs, and monotonic live friction state (snooze, focus boost, timers). */
    val friction by lazy {
        FrictionRepository(
            db = database,
            preferences = preferences,
            interner = interner,
            derived = derived,
            ownPackage = app.packageName,
            onDirty = ::requestRecompute,
            bootCountProvider = {
                runCatching { Settings.Global.getInt(app.contentResolver, Settings.Global.BOOT_COUNT, 0) }.getOrDefault(0)
            },
        )
    }

    private val recomputeKicks = Channel<Unit>(Channel.CONFLATED)

    private val lastScreenOffHarvestElapsedMs = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)

    val catalog by lazy { AppCatalogRepository(database, interner, ::launchablePackages, packageResolver) }

    val backups by lazy {
        LagrangeBackups(app, database, preferences, restore, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    }

    /** Keeps the home-screen widget and the Focus Boost tile in step with the data and live friction state. */
    val surfaces by lazy { SurfaceRefresher(app, this) }

    val digestNotifier by lazy { DigestNotifier(app) }

    private val digestRepository by lazy { DigestRepository(database, derived, lab, preferences) }

    /** Reflection digests and the coverage-gap alert: composed, claimed once per day / week, then posted. */
    val digests by lazy {
        DigestService(
            compose = { kind, anchor ->
                when (kind) {
                    DigestKind.DAILY -> digestRepository.composeDaily(anchor)
                    DigestKind.WEEKLY -> digestRepository.composeWeekly(anchor)
                }
            },
            claim = { kind, key ->
                when (kind) {
                    DigestKind.DAILY -> preferences.claimDailyDigest(key)
                    DigestKind.WEEKLY -> preferences.claimWeeklyDigest(key)
                }
            },
            release = { kind, key ->
                when (kind) {
                    DigestKind.DAILY -> preferences.releaseDailyDigest(key)
                    DigestKind.WEEKLY -> preferences.releaseWeeklyDigest(key)
                }
            },
            poster = object : DigestPoster {
                override fun canPost() = digestNotifier.canPost()
                override fun post(digest: Digest) = digestNotifier.post(digest)
                override fun postCoverageAlert(silentHours: Long) = digestNotifier.postCoverageAlert(silentHours)
            },
            lastHarvestAtMs = { database.ingestionStateDao().get(com.celestiallemon.lagrange.data.IngestionKeys.LAST_HARVEST_AT_MS) },
            claimCoverageAlert = preferences::claimCoverageAlert,
            releaseCoverageAlert = preferences::releaseCoverageAlert,
        )
    }

    /** Message about the last restore attempt, shown once wherever the user is (onboarding included). */
    val restoreNotice = MutableStateFlow<String?>(null)

    /** Startup, off the main thread. Called once from [LagrangeApplication.onCreate]. */
    fun start() {
        applicationScope.launch(Dispatchers.IO) {
            gate.open {
                // Both are file deletes and renames only, and must finish before anything opens Room or DataStore.
                // A failure (wipe not verified, rollback incomplete) throws and keeps the gate shut.
                DataReset.applyPendingIfAny(app)
                restore.applyPendingIfAny()
                database // opens, and verifies a fresh restore
                restoreNotice.value = restore.consumeOutcome()
                // Collectors must never run without the reset cutoff persisted: a failure here keeps them gated.
                DataReset.recordPendingCutoff(app, database)
                applyRestoredPreferences()
            }
            (startupState.value as? StartupGate.State.Failed)?.let {
                Log.e(TAG, "startup recovery failed; data stays closed until the next start: ${it.message}")
                return@launch
            }
            try {
                backups.clearSnapshots()
                timezoneTracker.check()
                friction.refreshLiveStatusFromDisk()
                if (preferences.current().backupTreeUri != null) WorkScheduler.setWeeklyBackup(app, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startup housekeeping failed", e)
            }
            try {
                startDerivedPipeline()
                startSurfacesAndDigests()
            } finally {
                housekeepingDone.complete(Unit)
            }
        }
    }

    /**
     * Widget/tile refresher, and digest scheduling that follows the digest settings (startup, restore, toggles and
     * time changes all land here). Scheduling is idempotent: an already queued run for the same settings and zone
     * is kept, so a process started by WorkManager to run a digest never cancels it.
     */
    private suspend fun startSurfacesAndDigests() {
        try {
            digestNotifier.ensureChannels()
            surfaces.start()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "surface refresher failed to start", e)
        }
        // Once synchronously, so [awaitStarted] covers it; the collector below then follows every change.
        try {
            DigestScheduler.ensureScheduled(app, DigestScheduler.Settings.of(preferences.current()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "digest scheduling failed", e)
        }
        applicationScope.launch(Dispatchers.IO) {
            preferences.prefs.map { DigestScheduler.Settings.of(it) }.distinctUntilChanged().collect { settings ->
                try {
                    DigestScheduler.ensureScheduled(app, settings)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "digest scheduling failed", e)
                }
            }
        }
    }

    /** Asks for a rebuild of whatever is marked dirty. Coalesced: bursts of requests run once. */
    fun requestRecompute() {
        recomputeKicks.trySend(Unit)
    }

    private suspend fun startDerivedPipeline() {
        try {
            preferences.repairFromHistory()
            derived.ensureAlgorithmVersion()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "derived version check failed", e)
        }
        applicationScope.launch(Dispatchers.IO) {
            for (kick in recomputeKicks) {
                delay(RECOMPUTE_DEBOUNCE_MS)
                try {
                    derived.runPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "derived recompute failed; it stays queued", e)
                }
            }
        }
        // Rollover hour, budget and categories are reconciled against a persisted fingerprint (DerivedRepository
        // .reconcileSettings), so a change made while nothing was listening, or just before the process died, still
        // queues its rebuild. These watchers only make it prompt; tz changes mark dirty where tz_history is written.
        applicationScope.launch {
            combine(
                preferences.prefs.map { it.rolloverHour to it.dailyBudgetMs }.distinctUntilChanged(),
                database.packageDao().observeAll().map { rows -> rows.associate { it.id to it.category } }.distinctUntilChanged(),
            ) { _, _ -> Unit }.collect {
                try {
                    if (derived.reconcileSettings()) requestRecompute()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "derived settings reconcile failed", e)
                }
            }
        }
        if (derived.hasPending()) requestRecompute()
    }

    /**
     * Harvests, then rebuilds the stretch the new events touch. The harvester records that range in the same
     * transactions as the rows themselves (reaching back far enough for episodes still open at the previous harvest
     * and late notification metadata); this only wakes the processor.
     */
    suspend fun harvestAndDerive(trigger: HarvestTrigger, recomputeInline: Boolean = false): HarvestResult {
        val result = harvester.harvest(trigger)
        if (result is HarvestResult.Success) {
            // Listener coverage only counts up to its last heartbeat; extend it to now before deriving.
            runCatching { heartbeatListenerIfConnected() }.onFailure { if (it is CancellationException) throw it }
            if (!recomputeInline) {
                requestRecompute()
            } else {
                try {
                    derived.runPending()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The harvest itself succeeded; the range stays queued for the next run.
                    Log.e(TAG, "inline derived recompute failed", e)
                }
            }
        }
        return result
    }

    fun harvestInBackground(trigger: HarvestTrigger) {
        if (trigger == HarvestTrigger.SCREEN_OFF) {
            // Both NotificationListenerService and AccessibilityService receive ACTION_SCREEN_OFF; coalesce
            // simultaneous triggers so we never run two redundant harvests back-to-back on every screen lock.
            val now = android.os.SystemClock.elapsedRealtime()
            val prev = lastScreenOffHarvestElapsedMs.get()
            if (prev != Long.MIN_VALUE && now - prev in 0 until SCREEN_OFF_HARVEST_COALESCE_MS) return
            if (!lastScreenOffHarvestElapsedMs.compareAndSet(prev, now)) return
        }
        applicationScope.launch {
            try {
                awaitReady()
                harvestAndDerive(trigger)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "harvest failed", e)
            }
        }
    }

    /** Extends live collector coverage (notification listener and accessibility), only while ready in this process. */
    suspend fun heartbeatListenerIfConnected() {
        val now = System.currentTimeMillis()
        if (LagrangeNotificationListenerService.connected) {
            withContext(Dispatchers.IO) { notificationRecorder.heartbeat(now) }
        }
        if (LagrangeAccessibilityService.readyState.value) {
            withContext(Dispatchers.IO) { coverage.heartbeat(ServiceKind.ACCESSIBILITY, now) }
        }
    }

    /**
     * Applies preferences that came with a restored backup, but only those of the restore generation that was
     * verified (a rolled-back generation's preferences are discarded). If the import fails the file is kept and
     * retried on the next start.
     */
    suspend fun applyRestoredPreferences() {
        val file = restore.pendingPreferences() ?: return
        try {
            preferences.importJson(file.readText())
            file.delete()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "restored preferences not applied yet; will retry", e)
        }
    }

    private fun launchablePackages(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return app.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
    }

    /**
     * Opens Room. If a restore was just swapped in, the first open is the verification step: `VERIFYING` is
     * journaled first (so a crash during the open rolls back on the next start instead of looping), success
     * commits the restore, failure rolls back to the previous database generation and opens that instead.
     * Blocking: only ever reached from [start] on the IO dispatcher.
     */
    private fun openDatabase(): LagrangeDatabase {
        val db = LagrangeDatabase.create(app)
        if (!restore.needsVerification()) return db
        restore.beginVerification()
        return try {
            runBlocking { db.identityHash() ?: error("the restored database has no Room identity") }
            restore.markVerified()
            db
        } catch (e: Exception) {
            Log.e(TAG, "restored database failed to open; rolling back", e)
            db.close()
            restore.rollback(e.message)
            LagrangeDatabase.create(app)
        }
    }

    private companion object {
        const val TAG = "Lagrange"
        const val RECOMPUTE_DEBOUNCE_MS = 1_500L
        const val SCREEN_OFF_HARVEST_COALESCE_MS = 3_000L
    }
}
