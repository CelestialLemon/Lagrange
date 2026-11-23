package com.celestiallemon.lagrange.system

import android.content.Context
import android.content.Intent
import com.celestiallemon.lagrange.AppGraph
import com.celestiallemon.lagrange.harvest.WorkScheduler
import androidx.room3.useWriterConnection

/** Local Delete / Reset Data: wipes every table, preference, staged restore and snapshot on this device. */
class DataReset(private val context: Context, private val graph: AppGraph) {
    suspend fun resetAll() {
        WorkScheduler.setWeeklyBackup(context, enabled = false)
        graph.harvester.exclusive {
            graph.database.clearAllTables()
            graph.database.useWriterConnection { connection ->
                connection.usePrepared("INSERT OR IGNORE INTO classes (class_name) VALUES ('')") { it.step() }
            }
            graph.interner.clearCaches()
        }
        graph.preferences.clearAll()
        graph.restore.clearAll()
        graph.backups.clearSnapshots()
    }
}

object AppRestarter {
    /** Relaunches the app in a fresh process (needed after reset and to apply a staged restore). */
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
