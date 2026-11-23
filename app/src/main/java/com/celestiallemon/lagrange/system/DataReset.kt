package com.celestiallemon.lagrange.system

import android.content.Context
import android.content.Intent
import android.util.Log
import com.celestiallemon.lagrange.backup.RestoreCoordinator
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.db.IngestionStateEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.harvest.WorkScheduler
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Local Delete / Reset Data.
 *
 * Wiping tables under live writers (harvester, notification listener, backups, timezone tracker) would race them
 * and leave old pages in the SQLite freelist and WAL. Instead [requestReset] stops background work, releases
 * folder grants and journals a reset marker; the app then restarts, and [applyPendingIfAny] deletes the database
 * files, preferences, restore staging and caches at process start, before Room or DataStore open, so nothing can
 * write concurrently and no residual pages survive.
 *
 * The reset time becomes a permanent cutoff: harvests never re-import events from before it, even though Android
 * still holds about a week of them.
 */
class DataReset(
    private val context: Context,
    private val clock: () -> Long = System::currentTimeMillis,
    private val cancelWork: suspend () -> Unit = { WorkScheduler.cancelAllAndAwait(context) },
) {
    /** Stages the reset; the caller restarts the process to apply it. Returns the cutoff. */
    suspend fun requestReset(): Long = withContext(Dispatchers.IO) {
        cancelWork()
        releaseFolderGrants(context)
        val cutoff = clock()
        writeSynced(markerFile(context), cutoff.toString())
        cutoff
    }

    companion object {
        private const val TAG = "LagrangeReset"
        private const val PREFS_FILE = "datastore/lagrange_prefs.preferences_pb"

        private fun dir(context: Context) = File(context.noBackupFilesDir, "reset")
        private fun markerFile(context: Context) = File(dir(context), "RESET_PENDING")
        private fun cutoffFile(context: Context) = File(dir(context), "CUTOFF")

        fun isPending(context: Context): Boolean = markerFile(context).isFile

        /**
         * Runs at process start before anything opens the database or DataStore. Returns true when a reset was
         * applied. The cutoff is kept in a separate file until [consumeCutoff] has written it into the new database.
         */
        fun applyPendingIfAny(context: Context): Boolean {
            val marker = markerFile(context)
            if (!marker.isFile) return false
            val cutoff = marker.readText().trim().toLongOrNull() ?: System.currentTimeMillis()
            writeSynced(cutoffFile(context), cutoff.toString())
            context.deleteDatabase(LagrangeDatabase.FILE_NAME)
            File(context.filesDir, PREFS_FILE).delete()
            RestoreCoordinator(context).clearAll()
            File(context.cacheDir, "snapshots").deleteRecursively()
            File(context.noBackupFilesDir, "pseudonym.key").delete()
            marker.delete()
            Log.i(TAG, "all data deleted; cutoff $cutoff")
            return true
        }

        /** The cutoff of a reset that hasn't been recorded in the database yet. */
        fun pendingCutoff(context: Context): Long? = cutoffFile(context).takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull()

        /**
         * Writes a pending reset cutoff into the fresh database's `ingestion_state`, where every harvest reads it,
         * then forgets the file. Runs during startup, before any harvest may start.
         */
        suspend fun recordPendingCutoff(context: Context, db: LagrangeDatabase) {
            val cutoff = pendingCutoff(context) ?: return
            val dao = db.ingestionStateDao()
            val existing = dao.get(IngestionKeys.RESET_CUTOFF_MS)
            dao.put(IngestionStateEntity(IngestionKeys.RESET_CUTOFF_MS, maxOf(existing ?: cutoff, cutoff)))
            cutoffFile(context).delete()
        }

        /** Releases every persisted SAF grant (the weekly backup folder), so the reset app keeps no access. */
        fun releaseFolderGrants(context: Context) {
            val resolver = context.contentResolver
            for (permission in resolver.persistedUriPermissions.toList()) {
                var flags = 0
                if (permission.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
                if (permission.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                runCatching { resolver.releasePersistableUriPermission(permission.uri, flags) }
                    .onFailure { Log.w(TAG, "couldn't release ${permission.uri}", it) }
            }
        }

        private fun writeSynced(file: File, text: String) {
            file.parentFile?.mkdirs()
            FileOutputStream(file).use {
                it.write(text.encodeToByteArray())
                it.fd.sync()
            }
        }
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
