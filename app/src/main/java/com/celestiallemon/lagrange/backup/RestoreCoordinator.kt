package com.celestiallemon.lagrange.backup

import android.content.Context
import android.util.Log
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Restores a backup without ever overwriting an open database.
 *
 * 1. [stage] validates the ZIP (entry names, manifest, schema, checksums, SQLite integrity) into a staging folder.
 * 2. On the next process start, [applyPendingIfAny] runs before Room opens: the live database files move aside as
 *    the previous generation and the staged file takes their place, with WAL/SHM sidecars removed.
 * 3. The first successful open calls [markVerified]; a failed open calls [rollback], which puts the previous
 *    generation back.
 *
 * A small STATE file makes every step restartable: a crash mid-swap is rolled back on the next start.
 */
class RestoreCoordinator(
    private val context: Context,
    private val databaseName: String = LagrangeDatabase.FILE_NAME,
) {
    private val root = File(context.noBackupFilesDir, "restore")
    private val incoming = File(root, "incoming")
    private val staged = File(root, "staged")
    private val previous = File(root, "previous")
    private val stateFile = File(root, "STATE")
    private val outcomeFile = File(root, "LAST_OUTCOME")
    val pendingPreferencesFile = File(root, "pending-preferences.json")

    private val dbFile: File get() = context.getDatabasePath(databaseName)
    private val dbFiles: List<File>
        get() = listOf("", "-wal", "-shm", "-journal").map { File(dbFile.path + it) }

    enum class State { STAGED, SWAPPING, VERIFY_PENDING }

    // -- Staging (runs while the app is live) ----------------------------------------------------------------------

    /** Validates [input] fully and stages it for the next start. Nothing live is touched; on failure nothing is staged. */
    fun stage(input: InputStream, currentSchemaVersion: Int, currentIdentityHash: String?): BackupManifest {
        incoming.deleteRecursively()
        incoming.mkdirs()
        try {
            val seen = HashSet<String>()
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name
                    if (entry.isDirectory || name !in BackupFormat.ENTRIES) {
                        throw BackupException("Unexpected file in backup: $name")
                    }
                    if (!seen.add(name)) throw BackupException("Duplicate file in backup: $name")
                    val limit = if (name == BackupFormat.DB_ENTRY) BackupFormat.MAX_DB_BYTES else BackupFormat.MAX_JSON_BYTES
                    FileOutputStream(File(incoming, name)).use { out -> BackupFormat.copyLimited(zip, out, limit) }
                }
            }
            if (seen != BackupFormat.ENTRIES) throw BackupException("This isn't a complete Lagrange backup.")

            val manifest = try {
                BackupFormat.json.decodeFromString<BackupManifest>(File(incoming, BackupFormat.MANIFEST_ENTRY).readText())
            } catch (e: Exception) {
                throw BackupException("The backup manifest is unreadable.", e)
            }
            if (manifest.format != BackupFormat.FORMAT || manifest.formatVersion != BackupFormat.FORMAT_VERSION) {
                throw BackupException("Unsupported backup format.")
            }
            if (manifest.schemaVersion !in 1..currentSchemaVersion) {
                throw BackupException("This backup comes from a newer version of Lagrange (schema ${manifest.schemaVersion}).")
            }
            for (name in listOf(BackupFormat.DB_ENTRY, BackupFormat.PREFS_ENTRY)) {
                val entry = manifest.files.singleOrNull { it.name == name }
                    ?: throw BackupException("The manifest has no checksum for $name.")
                val file = File(incoming, name)
                if (file.length() != entry.size || BackupFormat.sha256(file) != entry.sha256) {
                    throw BackupException("Checksum mismatch for $name: the backup is damaged.")
                }
            }
            val report = SqliteFileChecks.inspect(File(incoming, BackupFormat.DB_ENTRY))
            if (report.integrity != "ok") throw BackupException("The backup database failed its integrity check.")
            if (report.userVersion != manifest.schemaVersion) throw BackupException("The backup database version doesn't match its manifest.")
            if (manifest.schemaVersion == currentSchemaVersion && currentIdentityHash != null &&
                report.identityHash != currentIdentityHash
            ) {
                throw BackupException("The backup's database layout doesn't match this version of Lagrange.")
            }
            try {
                LagrangePreferences.parsePortable(File(incoming, BackupFormat.PREFS_ENTRY).readText())
            } catch (e: IllegalArgumentException) {
                throw BackupException("The backup preferences are unreadable.", e)
            }
            // Checks above may have left sidecars next to the copy; the staged folder holds only the bundle files.
            incoming.listFiles()?.filter { it.name !in BackupFormat.ENTRIES }?.forEach { it.delete() }

            staged.deleteRecursively()
            if (!incoming.renameTo(staged)) throw BackupException("Couldn't stage the restore.")
            writeState(State.STAGED)
            return manifest
        } catch (e: Exception) {
            incoming.deleteRecursively()
            throw if (e is BackupException) e else BackupException("Couldn't read that backup.", e)
        }
    }

    fun hasStagedRestore(): Boolean = readState() == State.STAGED

    fun cancelStaged() {
        if (readState() == State.STAGED) {
            stateFile.delete()
            staged.deleteRecursively()
        }
    }

    // -- Applying (runs at process start, before Room opens) -------------------------------------------------------

    fun applyPendingIfAny() {
        when (readState()) {
            null, State.VERIFY_PENDING -> Unit
            State.STAGED -> swap()
            State.SWAPPING -> {
                Log.w(TAG, "found an interrupted swap; rolling back")
                restorePreviousGeneration()
                writeOutcome("Restore was interrupted and has been rolled back. Your data is unchanged.")
            }
        }
    }

    private fun swap() {
        val stagedDb = File(staged, BackupFormat.DB_ENTRY)
        if (!stagedDb.isFile) {
            stateFile.delete()
            staged.deleteRecursively()
            return
        }
        try {
            previous.deleteRecursively()
            previous.mkdirs()
            writeState(State.SWAPPING)
            dbFile.parentFile?.mkdirs()
            for (file in dbFiles) {
                if (file.exists() && !file.renameTo(File(previous, file.name))) throw BackupException("Couldn't move ${file.name}")
            }
            val tmp = File(dbFile.path + ".restore-tmp")
            stagedDb.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            if (!tmp.renameTo(dbFile)) throw BackupException("Couldn't place the restored database")
            File(staged, BackupFormat.PREFS_ENTRY).takeIf { it.isFile }?.let { prefs ->
                pendingPreferencesFile.delete()
                prefs.renameTo(pendingPreferencesFile)
            }
            staged.deleteRecursively()
            writeState(State.VERIFY_PENDING)
        } catch (e: Exception) {
            Log.e(TAG, "restore swap failed; rolling back", e)
            restorePreviousGeneration()
            staged.deleteRecursively()
            writeOutcome("Restore failed (${e.message}). Your previous data was kept.")
        }
    }

    fun isVerifyPending(): Boolean = readState() == State.VERIFY_PENDING

    /** The restored database opened cleanly. The previous generation stays on disk as a rollback copy. */
    fun markVerified() {
        stateFile.delete()
        writeOutcome("Backup restored.")
    }

    /** The restored database could not be opened: put the previous generation back. */
    fun rollback(reason: String?) {
        restorePreviousGeneration()
        pendingPreferencesFile.delete()
        writeOutcome("The restored database couldn't be opened (${reason ?: "unknown error"}). Your previous data was put back.")
    }

    private fun restorePreviousGeneration() {
        for (file in dbFiles + File(dbFile.path + ".restore-tmp")) file.delete()
        previous.listFiles()?.forEach { it.renameTo(File(dbFile.parentFile, it.name)) }
        stateFile.delete()
    }

    /** Returns and clears the message about the last restore attempt, if any. */
    fun consumeOutcome(): String? {
        if (!outcomeFile.isFile) return null
        return outcomeFile.readText().also { outcomeFile.delete() }
    }

    /** Wipes restore staging and the kept previous generation (used by Reset Data). */
    fun clearAll() {
        root.deleteRecursively()
    }

    private fun readState(): State? =
        stateFile.takeIf { it.isFile }?.readText()?.trim()?.let { text -> State.entries.firstOrNull { it.name == text } }

    private fun writeState(state: State) {
        root.mkdirs()
        val tmp = File(root, "STATE.tmp")
        FileOutputStream(tmp).use {
            it.write(state.name.encodeToByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(stateFile)) throw BackupException("Couldn't record restore state")
    }

    private fun writeOutcome(message: String) {
        root.mkdirs()
        outcomeFile.writeText(message)
    }

    private companion object {
        const val TAG = "LagrangeRestore"
    }
}
