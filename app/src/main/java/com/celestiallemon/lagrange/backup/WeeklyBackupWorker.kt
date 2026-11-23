package com.celestiallemon.lagrange.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker.Result
import androidx.work.WorkerParameters
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.StartupFailedException
import kotlinx.coroutines.CancellationException

/** Writes the weekly backup into the folder the user picked, keeping the newest eight. */
class WeeklyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LagrangeApplication).graph
        try {
            graph.awaitReady()
        } catch (e: StartupFailedException) {
            return Result.failure() // recovery failed: the database stays closed
        }
        val tree = graph.preferences.current().backupTreeUri
        return run(tree, runAttemptCount) { graph.backups.backUpToFolder(Uri.parse(it), automatic = true) }
    }

    companion object {
        private const val TAG = "LagrangeBackup"
        const val MAX_ATTEMPTS = 3

        /** The worker body, separated from WorkManager for tests. Cancellation propagates and is never a failure. */
        suspend fun run(treeUri: String?, attempt: Int, backUp: suspend (String) -> String): Result {
            if (treeUri == null) return Result.success()
            return try {
                val name = backUp(treeUri)
                Log.i(TAG, "weekly backup written: $name")
                Result.success()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "weekly backup failed", e)
                if (attempt < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }
        }
    }
}
