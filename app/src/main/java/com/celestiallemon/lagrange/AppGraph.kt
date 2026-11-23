package com.celestiallemon.lagrange

import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.preferencesDataStoreFile
import com.celestiallemon.lagrange.backup.LagrangeBackups
import com.celestiallemon.lagrange.backup.RestoreCoordinator
import com.celestiallemon.lagrange.data.AndroidPackageMetaResolver
import com.celestiallemon.lagrange.data.AppCatalogRepository
import com.celestiallemon.lagrange.data.CoverageRepository
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.TimezoneTracker
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.harvest.AndroidUsageEventSource
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import com.celestiallemon.lagrange.harvest.UsageStatsHarvester
import com.celestiallemon.lagrange.notifications.NotificationRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Hand-wired dependencies, one instance per process. */
class AppGraph(private val app: Application) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val restore = RestoreCoordinator(app)

    val database: LagrangeDatabase by lazy { openDatabase() }

    private val dataStore by lazy {
        PreferenceDataStoreFactory.create(scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)) {
            app.preferencesDataStoreFile("lagrange_prefs")
        }
    }

    val preferences: LagrangePreferences by lazy { LagrangePreferences(dataStore, database) }

    val usageSource by lazy { AndroidUsageEventSource(app) }

    val interner by lazy { Interner(database, AndroidPackageMetaResolver(app)) }

    val harvester by lazy { UsageStatsHarvester(database, usageSource, interner) }

    val coverage by lazy { CoverageRepository(database) }

    val notificationRecorder by lazy { NotificationRecorder(database, interner, coverage) }

    val timezoneTracker by lazy { TimezoneTracker(database) }

    val catalog by lazy { AppCatalogRepository(database, interner, ::launchablePackages) }

    val backups by lazy {
        LagrangeBackups(app, database, preferences, restore, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toLong())
    }

    /** Message about the last restore attempt, shown once in System & Data. */
    val restoreNotice = MutableStateFlow<String?>(null)

    fun harvestInBackground(trigger: HarvestTrigger) {
        applicationScope.launch {
            try {
                harvester.harvest(trigger)
            } catch (e: Exception) {
                Log.e(TAG, "harvest failed", e)
            }
        }
    }

    /** Applies preferences that came with a restored backup, once the restored database is confirmed good. */
    suspend fun applyRestoredPreferences() {
        val file = restore.pendingPreferencesFile
        if (!file.isFile) return
        runCatching { preferences.importJson(file.readText()) }.onFailure { Log.w(TAG, "restored preferences ignored", it) }
        file.delete()
    }

    private fun launchablePackages(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return app.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            .mapNotNull { it.activityInfo?.packageName }
            .distinct()
    }

    /**
     * Opens Room. If a restore was just swapped in, the first open is the verification step: success commits the
     * restore, failure rolls back to the previous database generation and opens that instead.
     */
    private fun openDatabase(): LagrangeDatabase {
        val db = LagrangeDatabase.create(app)
        if (!restore.isVerifyPending()) return db
        return try {
            runBlocking(Dispatchers.IO) { db.identityHash() }
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
    }
}
