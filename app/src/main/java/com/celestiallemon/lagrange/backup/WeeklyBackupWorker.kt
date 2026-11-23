package com.celestiallemon.lagrange.backup

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.celestiallemon.lagrange.LagrangeApplication

/** Writes the weekly backup into the folder the user picked, keeping the newest eight. */
class WeeklyBackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val graph = (applicationContext as LagrangeApplication).graph
        val tree = graph.preferences.current().backupTreeUri ?: return Result.success()
        return try {
            val name = graph.backups.backUpToFolder(Uri.parse(tree))
            Log.i(TAG, "weekly backup written: $name")
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "weekly backup failed", e)
            if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
    }

    private companion object {
        const val TAG = "LagrangeBackup"
    }
}
