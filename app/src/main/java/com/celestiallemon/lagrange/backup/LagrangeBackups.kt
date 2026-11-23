package com.celestiallemon.lagrange.backup

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room3.useWriterConnection
import com.celestiallemon.lagrange.data.BackupFingerprint
import com.celestiallemon.lagrange.data.InProgressBackup
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.system.BackupDrain
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
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
) : BackupDrain {
    /** One backup, export, restore stage or cleanup at a time, always on the IO dispatcher. */
    private val lock = Mutex()

    /** False once a reset began: nothing new may start writing to a folder whose grant is about to go away. */
    @Volatile
    private var admitting = true

    private val snapshotRoot get() = File(context.cacheDir, "snapshots")

    private suspend fun <T> exclusive(block: suspend () -> T): T {
        checkAdmitting()
        return lock.withLock {
            checkAdmitting() // waited behind a drain: still refused
            withContext(Dispatchers.IO) { block() }
        }
    }

    private fun checkAdmitting() {
        if (!admitting) throw BackupException("Lagrange is resetting; backups are paused.")
    }

    override fun closeAdmissions() {
        admitting = false
    }

    override fun reopenAdmissions() {
        admitting = true
    }

    override suspend fun <T> whileDrained(block: suspend () -> T): T = lock.withLock { withContext(Dispatchers.IO) { block() } }

    /** Discards a staged restore under the backup lock, so it can't race a stage that is still being written. */
    suspend fun cancelStagedRestore() = exclusive { restore.cancelStaged() }

    /**
     * Writes a complete backup ZIP to [out] and returns its manifest. A [writeToken] is embedded in the first local
     * header (see [BackupFormat.writeTokenExtra]) so a copy of this exact write can later be recognised.
     */
    suspend fun writeBackup(out: OutputStream, writeToken: String? = null): BackupManifest = exclusive { writeBackupLocked(out, writeToken) }

    private suspend fun writeBackupLocked(out: OutputStream, writeToken: String? = null): BackupManifest = withSnapshot { snapshot, report ->
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
        zip.putNextEntry(ZipEntry(BackupFormat.DB_ENTRY).apply { if (writeToken != null) extra = BackupFormat.writeTokenExtra(writeToken) })
        // Push the local header (and its token) out at once, so even a write killed early is identifiable.
        zip.flush()
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

    /**
     * Writes the tidy CSV bundle (six RFC 4180 files, see [CsvExporter]) to [out], read from a consistent,
     * integrity-checked snapshot that is deleted afterwards (also on failure or cancellation).
     */
    suspend fun exportCsvBundle(out: OutputStream, pageSize: Int = CsvExporter.DEFAULT_PAGE_SIZE): CsvExportSummary =
        exclusive { exportCsvBundleLocked(out, pageSize) }

    private suspend fun exportCsvBundleLocked(out: OutputStream, pageSize: Int): CsvExportSummary = withSnapshot { snapshot, _ ->
        val snap = SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        snap.use {
            val buffered = out.buffered(64 * 1024)
            val summary = CsvExporter(it, CsvExporter.zoneTimeline(it), pageSize).writeZip(buffered)
            buffered.flush()
            summary
        }
    }

    /**
     * "Export CSV bundle (.zip)" into a document the user just created. A failed or cancelled export removes the
     * half-written document rather than leaving a truncated ZIP behind.
     */
    suspend fun exportCsvBundleTo(uri: Uri): CsvExportSummary = exclusive {
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { exportCsvBundleLocked(it, CsvExporter.DEFAULT_PAGE_SIZE) }
                ?: throw BackupException("Couldn't open that file for writing.")
        } catch (e: Throwable) {
            withContext(NonCancellable) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
            }
            throw e
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
     * run is removed on the next run only if its bytes still begin with that run's random write token; otherwise
     * (empty, replaced, or a record from an older build) the file is kept and only the record is cleared.
     */
    suspend fun backUpToFolder(folderFactory: () -> BackupFolder, automatic: Boolean, keep: Int = KEEP_WEEKLY): String = try {
        exclusive {
            val folder = folderFactory()
            if (!folder.canWrite()) throw BackupException("Lagrange can no longer write to the backup folder.")
            preferences.backupInProgress()?.let { leftover ->
                if (folder.owns(leftover.id) && isOurPartialWrite(folder, leftover)) runCatching { folder.delete(leftover.id) }
                preferences.setBackupInProgress(null)
            }
            val name = BackupFormat.fileName(clock())
            val doc = folder.create(BackupFormat.MIME_ZIP, name) ?: throw BackupException("Couldn't create $name.")
            try {
                val token = BackupFormat.newWriteToken()
                preferences.setBackupInProgress(doc.id, doc.name ?: name, token)
                val fingerprint = (folder.openOutput(doc) ?: throw BackupException("Couldn't open $name for writing.")).use { out ->
                    val sink = FingerprintingOutputStream(out)
                    writeBackupLocked(sink, token)
                    sink.flush()
                    sink.fingerprint()
                }
                // Recorded only after the stream closed cleanly.
                preferences.recordCompletedBackup(doc.id, fingerprint)
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

    /**
     * Deletes recorded backups beyond the newest [keep]. A file is only deleted while its bytes still match the
     * fingerprint recorded when Lagrange wrote it: a file the user or a sync tool replaced at the same id is no
     * longer ours, so it is forgotten instead. Records without a fingerprint (older builds) are never deleted.
     */
    private suspend fun prune(folder: BackupFolder, keep: Int) {
        val recorded = preferences.completedBackups()
        val present = folder.list()
        val ours = present.filter { it.id in recorded }
        val presentIds = present.mapTo(HashSet()) { it.id }
        // Recorded backups the user deleted by hand are forgotten, so the record can't grow forever.
        val vanished = recorded.keys.filter { folder.owns(it) && it !in presentIds }
        val excess = ours.sortedByDescending { it.name.orEmpty() }.drop(keep)
        val forgotten = excess.filter { doc ->
            val expected = recorded[doc.id]
            if (expected == null || !matches(folder, doc.id, expected)) {
                true // not provably ours any more: forget it, never delete it
            } else {
                runCatching { folder.delete(doc.id) }.getOrDefault(false)
            }
        }.map { it.id }
        if (vanished.isNotEmpty() || forgotten.isNotEmpty()) preferences.forgetBackups(vanished + forgotten)
    }

    private fun matches(folder: BackupFolder, id: String, expected: BackupFingerprint): Boolean =
        runCatching { folder.openInput(id)?.use(BackupFormat::fingerprint) == expected }.getOrDefault(false)

    /**
     * A leftover in-progress document is deleted only when it is positively the interrupted write: listed under the
     * name Lagrange created it with, its bytes beginning with that run's random write token, and not a complete
     * bundle. Failing to parse as a bundle proves nothing on its own (a replaced or empty file fails too), so a
     * record without a token, an empty file, or anything the token can't be found in is always kept.
     */
    private fun isOurPartialWrite(folder: BackupFolder, leftover: InProgressBackup): Boolean = runCatching {
        val token = leftover.token ?: return@runCatching false
        val doc = folder.list().firstOrNull { it.id == leftover.id } ?: return@runCatching false
        if (leftover.name != null && doc.name != leftover.name) return@runCatching false
        val tokenMatches = folder.openInput(leftover.id)?.use { BackupFormat.startsWithWriteToken(it, token) } ?: false
        tokenMatches && folder.openInput(leftover.id)?.use { !BackupFormat.isCompleteBundle(it) } == true
    }.getOrDefault(false)

    /** Passes bytes through while measuring exactly what was written. */
    private class FingerprintingOutputStream(private val out: OutputStream) : OutputStream() {
        private val digest = MessageDigest.getInstance("SHA-256")
        private var size = 0L

        override fun write(b: Int) {
            out.write(b)
            digest.update(b.toByte())
            size++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            digest.update(b, off, len)
            size += len
        }

        override fun flush() = out.flush()

        // The caller owns and closes [out].
        override fun close() = flush()

        fun fingerprint() = BackupFingerprint(size, with(BackupFormat) { digest.digest().toHex() })
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
