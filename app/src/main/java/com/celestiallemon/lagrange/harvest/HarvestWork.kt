package com.celestiallemon.lagrange.harvest

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
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
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Best-effort fallback harvest; the screen-off trigger and app opens do most of the work. */
class HarvestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LagrangeApplication).graph
        graph.awaitReady()
        val trigger = inputData.getString(KEY_TRIGGER)?.let { name -> HarvestTrigger.entries.firstOrNull { it.name == name } }
            ?: HarvestTrigger.PERIODIC
        return run(
            harvest = { graph.harvester.harvest(trigger) },
            sideTasks = listOf({ graph.timezoneTracker.check() }, { graph.heartbeatListenerIfConnected() }),
        )
    }

    companion object {
        const val KEY_TRIGGER = "trigger"
        private const val TAG = "LagrangeHarvest"

        /**
         * The worker body, separated from WorkManager for tests. Side tasks (timezone check, listener heartbeat)
         * never stop the harvest; cancellation always propagates so WorkManager can stop the job cleanly.
         */
        suspend fun run(harvest: suspend () -> HarvestResult, sideTasks: List<suspend () -> Unit> = emptyList()): Result {
            for (task in sideTasks) {
                try {
                    task()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "harvest side task failed", e)
                }
            }
            return try {
                when (harvest()) {
                    is HarvestResult.Success, HarvestResult.NoAccess -> Result.success()
                    // Usually Direct Boot or a transient platform hiccup; the next run picks it up.
                    is HarvestResult.Unavailable -> Result.success()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "harvest worker failed", e)
                Result.retry()
            }
        }
    }
}

object WorkScheduler {
    const val HARVEST_PERIODIC = "harvest-periodic"
    const val HARVEST_NOW = "harvest-now"
    const val WEEKLY_BACKUP = "weekly-backup"

    fun schedulePeriodicHarvest(context: Context) {
        val request = PeriodicWorkRequestBuilder<HarvestWorker>(1, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(false).build())
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(HARVEST_PERIODIC, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    /**
     * Queues a harvest. APPEND_OR_REPLACE runs it after any harvest already queued or running, so a request that
     * arrives mid-run (a clock change, say) is never dropped.
     */
    fun enqueueHarvestNow(context: Context, trigger: HarvestTrigger) {
        val request = OneTimeWorkRequestBuilder<HarvestWorker>()
            .setInputData(workDataOf(HarvestWorker.KEY_TRIGGER to trigger.name))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(HARVEST_NOW, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Turns the weekly folder backup on or off. The first run is a week out: turning it on always follows a backup
     * written there and then, so two backups never race.
     */
    fun setWeeklyBackup(context: Context, enabled: Boolean, replace: Boolean = false) {
        val wm = WorkManager.getInstance(context)
        if (!enabled) {
            wm.cancelUniqueWork(WEEKLY_BACKUP)
            return
        }
        val request = PeriodicWorkRequestBuilder<WeeklyBackupWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(7, TimeUnit.DAYS)
            .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).setRequiresStorageNotLow(true).build())
            .build()
        val policy = if (replace) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(WEEKLY_BACKUP, policy, request)
    }

    /** Cancels every Lagrange job and waits until WorkManager has recorded the cancellations. */
    suspend fun cancelAllAndAwait(context: Context) = withContext(Dispatchers.IO) {
        val wm = WorkManager.getInstance(context)
        listOf(HARVEST_PERIODIC, HARVEST_NOW, WEEKLY_BACKUP).map { wm.cancelUniqueWork(it) }.forEach { it.result.get() }
    }
}

/** `ACTION_TIME_CHANGED`: the wall clock jumped, so the next harvest rescans the last 24 hours. */
class TimeChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_TIME_CHANGED) return
        val app = context.applicationContext
        val graph = (app as LagrangeApplication).graph
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.awaitReady()
                graph.harvester.requestRescan()
                WorkScheduler.enqueueHarvestNow(app, HarvestTrigger.TIME_CHANGED)
                graph.timezoneTracker.check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "time change handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LagrangeTime"
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
                graph.awaitReady()
                graph.timezoneTracker.check()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "timezone change handling failed", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        const val TAG = "LagrangeTime"
    }
}
