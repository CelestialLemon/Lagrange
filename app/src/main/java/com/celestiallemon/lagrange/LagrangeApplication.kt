package com.celestiallemon.lagrange

import android.app.Application
import android.util.Log
import com.celestiallemon.lagrange.backup.RestoreCoordinator
import com.celestiallemon.lagrange.harvest.WorkScheduler
import kotlinx.coroutines.launch

class LagrangeApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Must run before anything opens Room: a staged restore is swapped in here, never under an open database.
        RestoreCoordinator(this).applyPendingIfAny()
        graph = AppGraph(this)

        graph.applicationScope.launch {
            try {
                graph.database // opens (and verifies a fresh restore) off the main thread
                graph.restoreNotice.value = graph.restore.consumeOutcome()
                graph.applyRestoredPreferences()
                graph.backups.clearSnapshots()
                graph.timezoneTracker.check()
                graph.preferences.current().backupTreeUri?.let { WorkScheduler.setWeeklyBackup(this@LagrangeApplication, true) }
            } catch (e: Exception) {
                Log.e("Lagrange", "startup tasks failed", e)
            }
        }
        WorkScheduler.schedulePeriodicHarvest(this)
    }
}
