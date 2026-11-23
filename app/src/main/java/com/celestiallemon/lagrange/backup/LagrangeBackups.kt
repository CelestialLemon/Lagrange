package com.celestiallemon.lagrange.backup

import android.content.Context
import android.net.Uri
import androidx.room3.useWriterConnection
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Builds verified backup bundles and raw database exports, and hands restores to [RestoreCoordinator].
 *
 * Every export starts from a consistent `VACUUM INTO` snapshot taken in app-private storage (the live database is
 * never copied file-by-file), which is integrity-checked before anything leaves the app.
 */
class LagrangeBackups(
    private val context: Context,
    private val db: LagrangeDatabase,
    private val preferences: LagrangePreferences,
    private val restore: RestoreCoordinator,
    private val appVersionName: String,
    private val appVersionCode: Long,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** One backup, export, restore stage or cleanup at a time, always on the IO dispatcher. */
    private val lock = Mutex()

    private val snapshotRoot get() = File(context.cacheDir, "snapshots")

    private suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** Writes a complete backup ZIP to [out] and returns its manifest. */
    suspend fun writeBackup(out: OutputStream): BackupManifest = exclusive { writeBackupLocked(out) }

    private suspend fun writeBackupLocked(out: OutputStream): BackupManifest = withSnapshot { snapshot, report ->
        val prefsBytes = preferences.exportJson().encodeToByteArray()
        val manifest = BackupManifest(
            schemaVersion = report.userVersion,
            roomIdentityHash = report.identityHash,
            appVersionName = appVersionName,
            appVersionCode = appVersionCode,
            createdAtUtcMs = clock(),
            files = listOf(
                BackupFileEntry(BackupFormat.DB_ENTRY, BackupFormat.sha256(snapshot), snapshot.length()),
                BackupFileEntry(BackupFormat.PREFS_ENTRY, BackupFormat.sha256(prefsBytes.inputStream()), prefsBytes.size.toLong()),
            ),
        )
        val zip = ZipOutputStream(out.buffered())
        zip.putNextEntry(ZipEntry(BackupFormat.DB_ENTRY))
        snapshot.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(BackupFormat.PREFS_ENTRY))
        zip.write(prefsBytes)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY))
        zip.write(BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray())
        zip.closeEntry()
        // finish() + flush, not close(): the caller owns [out] and closes it, which is what proves the write.
        zip.finish()
        zip.flush()
        manifest
    }

    /** Writes a standalone, integrity-checked `.sqlite` copy for DuckDB, Python or DB Browser. */
    suspend fun exportRawSqlite(out: OutputStream) = exclusive { exportRawSqliteLocked(out) }

    private suspend fun exportRawSqliteLocked(out: OutputStream) {
        withSnapshot { snapshot, _ ->
            val buffered = out.buffered()
            snapshot.inputStream().use { input -> input.copyTo(buffered) }
            buffered.flush()
        }
    }

    /** Manual "Save backup…" to a single document the user picked. */
    suspend fun backUpTo(uri: Uri): BackupManifest = try {
        exclusive {
            context.contentResolver.openOutputStream(uri, "wt")?.use { writeBackupLocked(it) }
                ?: throw BackupException("Couldn't open that file for writing.")
        }.also { preferences.recordBackup(clock(), LagrangePreferences.STATUS_OK) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        preferences.recordBackup(clock(), "Manual backup failed: ${e.message}")
        throw e
    }

    suspend fun exportRawSqliteTo(uri: Uri) = exclusive {
        context.contentResolver.openOutputStream(uri, "wt")?.use { exportRawSqliteLocked(it) }
            ?: throw BackupException("Couldn't open that file for writing.")
    }

    suspend fun backUpToFolder(treeUri: Uri, automatic: Boolean, keep: Int = KEEP_WEEKLY): String =
        backUpToFolder({ SafBackupFolder(context, treeUri) }, automatic, keep)

    /**
     * Writes a new dated backup into a backup folder and prunes it to the newest [keep] backups. Returns the created
     * file name. The whole flow (create, stream, record, prune) runs under the backup lock on the IO dispatcher.
     *
     * Only backups this app recorded as complete count toward [keep] or are ever deleted: unrelated files and
     * half-written exports from a killed run are never mistaken for backups. A half-written file left by a killed
     * run is recognised by the in-progress marker and removed on the next run.
     */
    suspend fun backUpToFolder(folderFactory: () -> BackupFolder, automatic: Boolean, keep: Int = KEEP_WEEKLY): String = try {
        exclusive {
            val folder = folderFactory()
            if (!folder.canWrite()) throw BackupException("Lagrange can no longer write to the backup folder.")
            preferences.backupInProgress()?.let { leftover ->
                if (folder.owns(leftover)) runCatching { folder.delete(leftover) }
                preferences.setBackupInProgress(null)
            }
            val name = BackupFormat.fileName(clock())
            val doc = folder.create(BackupFormat.MIME_ZIP, name) ?: throw BackupException("Couldn't create $name.")
            try {
                preferences.setBackupInProgress(doc.id)
                (folder.openOutput(doc) ?: throw BackupException("Couldn't open $name for writing.")).use { writeBackupLocked(it) }
                // Recorded only after the stream closed cleanly.
                preferences.recordCompletedBackup(doc.id)
            } catch (e: Throwable) {
                withContext(NonCancellable) {
                    runCatching { folder.delete(doc.id) }
                    runCatching { preferences.setBackupInProgress(null) }
                }
                throw e
            }
            prune(folder, keep)
            doc.name ?: name
        }.also { preferences.recordBackup(clock(), LagrangePreferences.STATUS_OK) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        val label = if (automatic) "Automatic backup failed" else "Backup to folder failed"
        preferences.recordBackup(clock(), "$label: ${e.message}")
        throw e
    }

    private suspend fun prune(folder: BackupFolder, keep: Int) {
        val recorded = preferences.completedBackups()
        val present = folder.list()
        val ours = present.filter { it.id in recorded }
        val presentIds = present.mapTo(HashSet()) { it.id }
        // Recorded backups the user deleted by hand are forgotten, so the record can't grow forever.
        val vanished = recorded.filter { folder.owns(it) && it !in presentIds }
        val excess = ours.sortedByDescending { it.name.orEmpty() }.drop(keep)
        val deleted = excess.filter { runCatching { folder.delete(it.id) }.getOrDefault(false) }.map { it.id }
        if (vanished.isNotEmpty() || deleted.isNotEmpty()) preferences.forgetBackups(vanished + deleted)
    }

    /** Validates a backup and stages it; it is applied the next time the app starts. */
    suspend fun stageRestore(input: InputStream): BackupManifest = exclusive {
        restore.stage(input, LagrangeDatabase.SCHEMA_VERSION, db.identityHash(), LagrangeDatabase.canonicalSchema(context))
    }

    suspend fun stageRestore(uri: Uri): BackupManifest = exclusive {
        context.contentResolver.openInputStream(uri)?.use { input ->
            restore.stage(input, LagrangeDatabase.SCHEMA_VERSION, db.identityHash(), LagrangeDatabase.canonicalSchema(context))
        } ?: throw BackupException("Couldn't open that file.")
    }

    private suspend fun <T> withSnapshot(block: suspend (File, SqliteFileChecks.Report) -> T): T {
        val dir = File(snapshotRoot, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            val snapshot = File(dir, BackupFormat.DB_ENTRY)
            db.useWriterConnection { connection ->
                connection.usePrepared("VACUUM INTO ?") { stmt ->
                    stmt.bindText(1, snapshot.absolutePath)
                    stmt.step()
                }
            }
            // Normalised to a single rollback-journal file BEFORE anything is hashed; never modified afterwards.
            val report = SqliteFileChecks.inspect(snapshot, normalizeJournal = true)
            if (report.integrity != "ok") throw BackupException("Snapshot failed its integrity check: ${report.integrity}")
            // Drop any sidecar the check created so the snapshot is one self-contained file.
            dir.listFiles()?.filter { it != snapshot }?.forEach { it.delete() }
            return block(snapshot, report)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Deletes leftover snapshots (at startup); waits for any backup in progress so its snapshot isn't pulled away. */
    suspend fun clearSnapshots() = exclusive { snapshotRoot.deleteRecursively() }

    companion object {
        const val KEEP_WEEKLY = 8
    }
}
