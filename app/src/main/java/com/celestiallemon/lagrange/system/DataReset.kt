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
    private val backups: BackupDrain? = null,
) {
    /**
     * Stages the reset; the caller restarts the process to apply it. Returns the cutoff.
     *
     * Order matters: new backups are refused first, then WorkManager jobs are cancelled (a running weekly backup
     * stops and cleans up), then the backup lock is held while folder grants are revoked and the marker written, so
     * no ZIP copy or cleanup is still touching the backup folder when its grant goes away or the process exits.
     * The caller restarts while admissions stay closed. On failure, backups are admitted again.
     */
    suspend fun requestReset(): Long = withContext(Dispatchers.IO) {
        backups?.closeAdmissions()
        try {
            cancelWork()
            val stage: suspend () -> Long = {
                releaseFolderGrants(context)
                val cutoff = clock()
                writeSynced(markerFile(context), cutoff.toString())
                cutoff
            }
            backups?.whileDrained(stage) ?: stage()
        } catch (e: Throwable) {
            backups?.reopenAdmissions()
            throw e
        }
    }

    companion object {
        private const val TAG = "LagrangeReset"
        private const val PREFS_FILE = "datastore/lagrange_prefs.preferences_pb"
        private val DB_SUFFIXES = listOf("", "-wal", "-shm", "-journal")

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
            val dbFile = context.getDatabasePath(LagrangeDatabase.FILE_NAME)
            val dbFiles = DB_SUFFIXES.map { File(dbFile.path + it) }
            val prefs = File(context.filesDir, PREFS_FILE)
            val snapshots = File(context.cacheDir, "snapshots")
            val pseudonymKey = File(context.noBackupFilesDir, "pseudonym.key")
            val restore = RestoreCoordinator(context)

            context.deleteDatabase(LagrangeDatabase.FILE_NAME)
            dbFiles.forEach { it.delete() }
            prefs.delete()
            restore.clearAll()
            snapshots.deleteRecursively()
            pseudonymKey.delete()

            // The marker is the only record that a wipe is owed: keep it until every file is verifiably gone, so a
            // failed delete is retried on the next start instead of reopening the old data.
            val survivors = (dbFiles + prefs + snapshots + pseudonymKey).filter { it.exists() } + restore.leftovers()
            if (survivors.isNotEmpty()) {
                throw IllegalStateException(
                    "Reset couldn't delete ${survivors.joinToString { it.name }}. Lagrange will retry on the next start.",
                )
            }
            if (!marker.delete()) throw IllegalStateException("Reset couldn't be completed. Lagrange will retry on the next start.")
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

/** What a reset needs from the backup subsystem: stop admitting work, then wait out (and hold off) what is running. */
interface BackupDrain {
    fun closeAdmissions()
    fun reopenAdmissions()

    /** Runs [block] holding the backup lock, after any backup, export, restore stage or cleanup in flight finished. */
    suspend fun <T> whileDrained(block: suspend () -> T): T
}

object AppRestarter {
    /** Relaunches the app in a fresh process (needed after reset and to apply a staged restore). */
    fun restart(context: Context) {
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
        context.startActivity(Intent.makeRestartActivityTask(launch.component))
        Runtime.getRuntime().exit(0)
    }
}
