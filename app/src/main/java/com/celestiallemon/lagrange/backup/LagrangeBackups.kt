package com.celestiallemon.lagrange.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import androidx.room3.useWriterConnection
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
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
    /** One backup, export or restore at a time. */
    private val lock = Mutex()

    private val snapshotRoot get() = File(context.cacheDir, "snapshots")

    /** Writes a complete backup ZIP to [out] and returns its manifest. */
    suspend fun writeBackup(out: OutputStream): BackupManifest = lock.withLock {
        withContext(Dispatchers.IO) {
            withSnapshot { snapshot, report ->
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
                ZipOutputStream(out.buffered()).use { zip ->
                    zip.putNextEntry(ZipEntry(BackupFormat.DB_ENTRY))
                    snapshot.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(BackupFormat.PREFS_ENTRY))
                    zip.write(prefsBytes)
                    zip.closeEntry()
                    zip.putNextEntry(ZipEntry(BackupFormat.MANIFEST_ENTRY))
                    zip.write(BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest).encodeToByteArray())
                    zip.closeEntry()
                }
                manifest
            }
        }
    }

    /** Writes a standalone, integrity-checked `.sqlite` copy for DuckDB, Python or DB Browser. */
    suspend fun exportRawSqlite(out: OutputStream) = lock.withLock {
        withContext(Dispatchers.IO) {
            withSnapshot { snapshot, _ -> snapshot.inputStream().use { input -> out.buffered().use { input.copyTo(it) } } }
        }
    }

    suspend fun backUpTo(uri: Uri): BackupManifest = try {
        val manifest = context.contentResolver.openOutputStream(uri, "wt")?.use { writeBackup(it) }
            ?: throw BackupException("Couldn't open that file for writing.")
        preferences.recordBackup(clock(), LagrangePreferences.STATUS_OK)
        manifest
    } catch (e: Exception) {
        preferences.recordBackup(clock(), "Manual backup failed: ${e.message}")
        throw e
    }

    suspend fun exportRawSqliteTo(uri: Uri) {
        context.contentResolver.openOutputStream(uri, "wt")?.use { exportRawSqlite(it) }
            ?: throw BackupException("Couldn't open that file for writing.")
    }

    /**
     * Writes a new dated backup into the persisted backup folder and prunes it to the newest [keep] backups.
     * Returns the created file name.
     */
    suspend fun backUpToFolder(treeUri: Uri, keep: Int = KEEP_WEEKLY): String = try {
        val tree = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw BackupException("The backup folder is no longer available.")
        if (!tree.canWrite()) throw BackupException("Lagrange can no longer write to the backup folder.")
        val name = BackupFormat.fileName(clock())
        val file = tree.createFile("application/zip", name) ?: throw BackupException("Couldn't create $name.")
        try {
            context.contentResolver.openOutputStream(file.uri, "w")?.use { writeBackup(it) }
                ?: throw BackupException("Couldn't open $name for writing.")
        } catch (e: Exception) {
            file.delete()
            throw e
        }
        val existing = tree.listFiles().filter { it.isFile }
        val prune = BackupFormat.toPrune(existing.mapNotNull { it.name }, keep).toSet()
        existing.filter { it.name in prune }.forEach { it.delete() }
        preferences.recordBackup(clock(), LagrangePreferences.STATUS_OK)
        file.name ?: name
    } catch (e: Exception) {
        preferences.recordBackup(clock(), "Automatic backup failed: ${e.message}")
        throw e
    }

    /** Validates a backup and stages it; it is applied the next time the app starts. */
    suspend fun stageRestore(input: InputStream): BackupManifest = lock.withLock {
        withContext(Dispatchers.IO) {
            restore.stage(input, LagrangeDatabase.SCHEMA_VERSION, db.identityHash())
        }
    }

    suspend fun stageRestore(uri: Uri): BackupManifest =
        context.contentResolver.openInputStream(uri)?.use { stageRestore(it) }
            ?: throw BackupException("Couldn't open that file.")

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
            val report = SqliteFileChecks.inspect(snapshot, normalizeJournal = true)
            if (report.integrity != "ok") throw BackupException("Snapshot failed its integrity check: ${report.integrity}")
            // Drop any sidecar the check created so the snapshot is one self-contained file.
            dir.listFiles()?.filter { it != snapshot }?.forEach { it.delete() }
            return block(snapshot, report)
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Deletes leftover snapshots (used by Reset Data and at startup). */
    fun clearSnapshots() {
        snapshotRoot.deleteRecursively()
    }

    companion object {
        const val KEEP_WEEKLY = 8
    }
}
