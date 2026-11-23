package com.celestiallemon.lagrange

import android.app.Application
import android.util.Log
import com.celestiallemon.lagrange.backup.RestoreCoordinator
import com.celestiallemon.lagrange.harvest.WorkScheduler
import com.celestiallemon.lagrange.system.DataReset

class LagrangeApplication : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // Both must run before anything opens Room or DataStore. Each is file deletes and renames only (no copies),
        // so they are fast enough for the main thread; a failure is logged, never a crash loop.
        try {
            DataReset.applyPendingIfAny(this)
        } catch (e: Exception) {
            Log.e(TAG, "pending reset failed", e)
        }
        try {
            RestoreCoordinator(this).applyPendingIfAny()
        } catch (e: Exception) {
            Log.e(TAG, "pending restore failed", e)
        }
        graph = AppGraph(this)
        graph.start()
        WorkScheduler.schedulePeriodicHarvest(this)
    }

    private companion object {
        const val TAG = "Lagrange"
    }
}
