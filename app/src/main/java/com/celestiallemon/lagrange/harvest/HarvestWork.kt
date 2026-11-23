package com.celestiallemon.lagrange.harvest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.backup.WeeklyBackupWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

/** Best-effort fallback harvest; the screen-off trigger and app opens do most of the work. */
class HarvestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LagrangeApplication).graph
        val trigger = inputData.getString(KEY_TRIGGER)?.let { name -> HarvestTrigger.entries.firstOrNull { it.name == name } }
            ?: HarvestTrigger.PERIODIC
        graph.timezoneTracker.check()
        return when (graph.harvester.harvest(trigger)) {
            is HarvestResult.Success, HarvestResult.NoAccess -> Result.success()
            // Usually Direct Boot or a transient platform hiccup; the next run picks it up.
            is HarvestResult.Unavailable -> Result.success()
        }
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
    }
}

object WorkScheduler {
    private const val HARVEST_PERIODIC = "harvest-periodic"
    private const val HARVEST_NOW = "harvest-now"
    private const val WEEKLY_BACKUP = "weekly-backup"

    fun schedulePeriodicHarvest(context: Context) {
        val request = PeriodicWorkRequestBuilder<HarvestWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HARVEST_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun enqueueHarvestNow(context: Context, trigger: HarvestTrigger) {
        val request = OneTimeWorkRequestBuilder<HarvestWorker>()
            .setInputData(workDataOf(HarvestWorker.KEY_TRIGGER to trigger.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(HARVEST_NOW, ExistingWorkPolicy.KEEP, request)
    }

    fun setWeeklyBackup(context: Context, enabled: Boolean, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WEEKLY_BACKUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeeklyBackupWorker>(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(WEEKLY_BACKUP, policy, request)
    }
}

/** `ACTION_TIME_CHANGED`: the wall clock jumped, so the next harvest rescans the last 24 hours. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED) return
        val graph = (context.applicationContext as LagrangeApplication).graph
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.harvester.requestRescan()
                graph.timezoneTracker.check()
            } finally {
                pending.finish()
            }
            WorkScheduler.enqueueHarvestNow(context, HarvestTrigger.TIME_CHANGED)
        }
    }
}

/** `ACTION_TIMEZONE_CHANGED`: append the new zone to `tz_history`. */
class TimezoneChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIMEZONE_CHANGED) return
        val graph = (context.applicationContext as LagrangeApplication).graph
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.timezoneTracker.check()
            } finally {
                pending.finish()
            }
        }
    }
}
