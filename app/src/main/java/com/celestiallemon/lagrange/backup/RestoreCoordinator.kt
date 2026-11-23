package com.celestiallemon.lagrange.backup

import android.content.Context
import android.util.Log
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import java.util.UUID
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable

/**
 * Restores a backup without ever overwriting an open database, and without ever losing the live generation.
 *
 * 1. [stage] (on a background thread, while the app is live) streams the ZIP into a folder next to the live
 *    database, verifies checksums, then opens the staged file read-only for integrity, foreign-key, version and
 *    exact-schema checks. Only a fully verified bundle is moved to `staged/` and journaled as [State.STAGED].
 * 2. At the next process start, before Room opens, [applyPendingIfAny] performs the swap with renames only (same
 *    volume, so it is fast and each step is atomic):
 *    `STAGED` → `SAVING_PREVIOUS` (live files recorded, then renamed into `previous-<generation>/`) →
 *    `PREVIOUS_SAVED` → `SWAPPING` (staged file renamed into place) → `SWAPPED`.
 * 3. The app writes `VERIFYING` ([beginVerification]) right before it first opens the swapped database. A clean
 *    open calls [markVerified]; a failed open calls [rollback]. If the process dies while `VERIFYING` (or in any
 *    intermediate state), the next start rolls back instead of trying again, so a bad restore can't crash-loop.
 *
 * Rollback moves every saved file back and deletes only files that were not part of the recorded live
 * generation. The journal is removed only after every rename has succeeded; a failed rollback keeps it so the
 * next start retries.
 */
class RestoreCoordinator(
    private val context: Context,
    private val databaseName: String = LagrangeDatabase.FILE_NAME,
) {
    /** Journal, outcome message and pending preferences: small files, kept out of every backup. */
    private val root = File(context.noBackupFilesDir, "restore")
    private val journalFile = File(root, "STATE")
    private val outcomeFile = File(root, "LAST_OUTCOME")
    private val verifiedGenerationFile = File(root, "VERIFIED_GENERATION")
    private val stagedPreferences = File(root, "staged-preferences.json")

    private val dbFile: File get() = context.getDatabasePath(databaseName)
    private val dbDir: File get() = dbFile.absoluteFile.parentFile!!

    /** Large files live beside the live database (same volume) so the startup swap is renames, never a copy. */
    private val workRoot: File get() = File(dbDir, "lagrange-restore")
    private val incoming: File get() = File(workRoot, "incoming")
    private val staged: File get() = File(workRoot, "staged")

    private val dbFileNames: List<String> get() = SUFFIXES.map { databaseName + it }

    /** Test hook for fault injection: called at named checkpoints; throwing an [Error] simulates process death. */
    internal var checkpoint: (String) -> Unit = {}

    enum class State {
        STAGED,
        SAVING_PREVIOUS,
        PREVIOUS_SAVED,
        SWAPPING,
        SWAPPED,
        VERIFYING,

        /** Written by the first build after a swap; treated like an unfinished verification. */
        VERIFY_PENDING,
    }

    @Serializable
    internal data class Journal(
        val state: State,
        val generation: String,
        /** Live database files present when the previous generation started moving aside. */
        val originals: List<String> = emptyList(),
    )

    // -- Staging (runs while the app is live, on a background thread) -----------------------------------------------

    /**
     * Validates [input] fully and stages it for the next start. Nothing live is touched; on failure nothing is
     * staged. [expectedSchema] is the exact set of tables, indices and triggers this version of the app creates.
     */
    fun stage(
        input: InputStream,
        currentSchemaVersion: Int,
        currentIdentityHash: String?,
        expectedSchema: List<SchemaObject>?,
    ): BackupManifest {
        when (readJournal()?.state) {
            null, State.STAGED -> Unit
            else -> throw BackupException("A restore is still being applied. Restart Lagrange first.")
        }
        incoming.deleteRecursively()
        if (!incoming.mkdirs()) throw BackupException("Couldn't prepare a folder for the restore.")
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
                    FileOutputStream(File(incoming, name)).use { out ->
                        BackupFormat.copyLimited(zip, out, limit)
                        out.fd.sync()
                    }
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

            // From here on the verified bytes are only ever read: the checks open the file read-only.
            val dbCopy = File(incoming, BackupFormat.DB_ENTRY)
            val report = SqliteFileChecks.inspectReadOnly(dbCopy)
            if (report.integrity != "ok") throw BackupException("The backup database failed its integrity check.")
            if (report.foreignKeyViolations > 0) {
                throw BackupException("The backup database has ${report.foreignKeyViolations} broken links between tables.")
            }
            if (report.userVersion != manifest.schemaVersion) throw BackupException("The backup database version doesn't match its manifest.")
            if (manifest.schemaVersion == currentSchemaVersion) {
                if (currentIdentityHash != null && report.identityHash != currentIdentityHash) {
                    throw BackupException("The backup's database layout doesn't match this version of Lagrange.")
                }
                if (expectedSchema != null && !sameSchema(report.schema, expectedSchema)) {
                    throw BackupException("The backup's database layout doesn't match this version of Lagrange (tables or indices differ).")
                }
            }
            try {
                LagrangePreferences.parsePortable(File(incoming, BackupFormat.PREFS_ENTRY).readText())
            } catch (e: IllegalArgumentException) {
                throw BackupException("The backup preferences are unreadable.", e)
            }
            // A read-only open never creates sidecars, but be certain the staged folder holds only the bundle files.
            incoming.listFiles()?.filter { it.name !in BackupFormat.ENTRIES }?.forEach { it.delete() }

            val generation = UUID.randomUUID().toString()
            discardStaged()
            moveFile(File(incoming, BackupFormat.PREFS_ENTRY), stagedPreferences)
            if (!incoming.renameTo(staged)) throw BackupException("Couldn't stage the restore.")
            fsyncDir(workRoot)
            writeJournal(Journal(State.STAGED, generation))
            return manifest
        } catch (e: Exception) {
            incoming.deleteRecursively()
            throw if (e is BackupException) e else BackupException("Couldn't read that backup.", e)
        }
    }

    fun hasStagedRestore(): Boolean = readJournal()?.state == State.STAGED

    fun cancelStaged() {
        if (readJournal()?.state == State.STAGED) {
            journalFile.delete()
            discardStaged()
        }
    }

    private fun discardStaged() {
        staged.deleteRecursively()
        stagedPreferences.delete()
    }

    // -- Applying (runs at process start, before Room opens) -------------------------------------------------------

    fun applyPendingIfAny() {
        val journal = readJournal() ?: return
        when (journal.state) {
            State.STAGED -> swap(journal)
            State.SWAPPED -> Unit // the app verifies it when it opens the database
            State.SAVING_PREVIOUS, State.PREVIOUS_SAVED, State.SWAPPING -> {
                Log.w(TAG, "found an interrupted swap (${journal.state}); rolling back")
                rollbackOrKeep(journal, "Restore was interrupted and has been rolled back. Your data is unchanged.")
            }
            State.VERIFYING, State.VERIFY_PENDING -> {
                Log.w(TAG, "the app stopped while opening a restored database; rolling back")
                rollbackOrKeep(journal, "The restored database stopped Lagrange while opening, so your previous data was put back.")
            }
        }
    }

    private fun swap(journal: Journal) {
        val stagedDb = File(staged, BackupFormat.DB_ENTRY)
        if (!stagedDb.isFile) {
            journalFile.delete()
            discardStaged()
            return
        }
        val generation = journal.generation
        val previous = previousDir(generation)
        var current = journal
        try {
            // Older rollback copies are superseded by the generation about to move aside.
            workRoot.listFiles()?.filter { it.name.startsWith(PREVIOUS_PREFIX) && it != previous }?.forEach { it.deleteRecursively() }
            if (!previous.isDirectory && !previous.mkdirs()) throw BackupException("Couldn't create the rollback folder")
            val originals = dbFileNames.filter { File(dbDir, it).exists() }
            current = Journal(State.SAVING_PREVIOUS, generation, originals)
            writeJournal(current)
            checkpoint("before-move")
            for (name in originals) {
                val target = File(previous, name)
                if (target.exists()) throw BackupException("Rollback copy of $name already exists")
                if (!File(dbDir, name).renameTo(target)) throw BackupException("Couldn't move $name aside")
                checkpoint("moved:$name")
            }
            fsyncDir(previous)
            fsyncDir(dbDir)
            current = current.copy(state = State.PREVIOUS_SAVED)
            writeJournal(current)
            current = current.copy(state = State.SWAPPING)
            writeJournal(current)
            checkpoint("before-place")
            if (!stagedDb.renameTo(dbFile)) throw BackupException("Couldn't place the restored database")
            fsyncDir(dbDir)
            checkpoint("placed")
            if (stagedPreferences.isFile) moveFile(stagedPreferences, pendingPreferencesFile(generation))
            staged.deleteRecursively()
            current = current.copy(state = State.SWAPPED)
            writeJournal(current)
        } catch (e: Exception) {
            if (current.state == State.STAGED) {
                // Failed before SAVING_PREVIOUS was journaled: no live file has moved, so there is nothing to roll
                // back. A rollback here would treat every live file as "not an original" and delete it.
                Log.e(TAG, "restore swap failed before anything moved; discarding the staged restore", e)
                discardStaged()
                previous.deleteRecursively()
                journalFile.delete()
                writeOutcome("Restore failed (${e.message}). Your data is unchanged.")
                return
            }
            Log.e(TAG, "restore swap failed; rolling back", e)
            rollbackOrKeep(current, "Restore failed (${e.message}). Your previous data was kept.")
        }
    }

    // -- Verification (the app's first open of the swapped database) -----------------------------------------------

    /** True when a swapped-in database is waiting for its first open. */
    fun needsVerification(): Boolean = readJournal()?.state == State.SWAPPED

    /** Journals that the swapped database is being opened, so a crash during the open rolls back next start. */
    fun beginVerification() {
        val journal = readJournal() ?: return
        writeJournal(journal.copy(state = State.VERIFYING))
    }

    /** The restored database opened cleanly. The previous generation stays on disk as a rollback copy. */
    fun markVerified() {
        val journal = readJournal() ?: return
        writeSynced(verifiedGenerationFile, journal.generation)
        journalFile.delete()
        writeOutcome("Backup restored.")
    }

    /** The restored database could not be opened: put the previous generation back. Throws if that is incomplete. */
    fun rollback(reason: String?) {
        val journal = readJournal() ?: return
        rollbackOrKeep(journal, "The restored database couldn't be opened (${reason ?: "unknown error"}). Your previous data was put back.")
    }

    /**
     * Puts the previous generation back. An incomplete rollback throws [RestoreRecoveryException]: the journal stays
     * (the next start retries before Room opens) and the caller must not open the database, which may now be a mix
     * of both generations.
     */
    private fun rollbackOrKeep(journal: Journal, message: String) {
        try {
            restorePreviousGeneration(journal)
        } catch (e: Exception) {
            Log.e(TAG, "rollback incomplete; will retry on next start", e)
            throw RestoreRecoveryException(
                "Lagrange couldn't finish putting your previous data back (${e.message}). Your data is kept on the " +
                    "device and nothing will be recorded until it is. Restart Lagrange to try again.",
                e,
            )
        }
        writeOutcome(message)
    }

    private fun restorePreviousGeneration(journal: Journal) {
        // Nothing was moved aside while STAGED: "restoring" would only delete live files that look like non-originals.
        if (journal.state == State.STAGED) throw BackupException("Refusing to roll back a restore that never started")
        val previous = previousDir(journal.generation)
        val saved = previous.listFiles()?.map { it.name }?.toSet().orEmpty()
        // A legacy journal has no list of originals: everything saved is an original.
        val originals = if (journal.generation == LEGACY_GENERATION) saved else journal.originals.toSet()
        val failures = ArrayList<String>()
        for (name in dbFileNames + (databaseName + LEGACY_TMP_SUFFIX)) {
            val live = File(dbDir, name)
            val copy = File(previous, name)
            when {
                name in saved -> {
                    if (live.exists() && !live.delete()) failures += "delete $name"
                    else if (!copy.renameTo(live)) failures += "restore $name"
                }
                // Not moved aside: either an untouched original (keep) or something the restored database created.
                name !in originals -> if (live.exists() && !live.delete()) failures += "delete $name"
            }
        }
        if (failures.isNotEmpty()) throw BackupException("Couldn't ${failures.joinToString()}")
        fsyncDir(dbDir)
        previous.deleteRecursively()
        discardStaged()
        pendingPreferencesFile(journal.generation).delete()
        journalFile.delete()
    }

    // -- Preferences that came with the restore --------------------------------------------------------------------

    private fun pendingPreferencesFile(generation: String) = File(root, "$PENDING_PREFS_PREFIX$generation.json")

    /**
     * The preferences file that belongs to the restore generation that was verified, or null. Files from any other
     * generation (one that was rolled back) are deleted. Never returns anything while a restore is in flight.
     */
    fun pendingPreferences(): File? {
        if (readJournal() != null) return null
        val verified = verifiedGenerationFile.takeIf { it.isFile }?.readText()?.trim()
        var match: File? = null
        root.listFiles()?.filter { it.name.startsWith(PENDING_PREFS_PREFIX) }?.forEach { file ->
            val generation = file.name.removePrefix(PENDING_PREFS_PREFIX).removeSuffix(".json")
            if (generation == verified) match = file else file.delete()
        }
        return match
    }

    // -- Outcome & housekeeping ------------------------------------------------------------------------------------

    /** Returns and clears the message about the last restore attempt, if any. */
    fun consumeOutcome(): String? {
        if (!outcomeFile.isFile) return null
        return outcomeFile.readText().also { outcomeFile.delete() }
    }

    /** Wipes restore staging and the kept previous generation (used by Reset Data). */
    fun clearAll() {
        root.deleteRecursively()
        workRoot.deleteRecursively()
    }

    /** Restore state that [clearAll] should have removed but is still on disk. */
    fun leftovers(): List<File> = listOf(root, workRoot).filter { it.exists() }

    private fun previousDir(generation: String): File =
        if (generation == LEGACY_GENERATION) File(root, "previous") else File(workRoot, PREVIOUS_PREFIX + generation)

    internal fun readJournal(): Journal? {
        val text = journalFile.takeIf { it.isFile }?.readText()?.trim() ?: return null
        if (text.startsWith("{")) {
            return try {
                BackupFormat.json.decodeFromString<Journal>(text)
            } catch (e: Exception) {
                Log.e(TAG, "unreadable restore journal", e)
                null
            }
        }
        // Plain-text journal written by the first build.
        val state = State.entries.firstOrNull { it.name == text } ?: return null
        return Journal(if (state == State.SWAPPED) State.VERIFY_PENDING else state, LEGACY_GENERATION)
    }

    private fun writeJournal(journal: Journal) {
        writeSynced(journalFile, BackupFormat.json.encodeToString(Journal.serializer(), journal))
    }

    private fun writeSynced(target: File, text: String) {
        target.parentFile?.mkdirs()
        val tmp = File(target.path + ".tmp")
        FileOutputStream(tmp).use {
            it.write(text.encodeToByteArray())
            it.fd.sync()
        }
        if (!tmp.renameTo(target)) throw BackupException("Couldn't record restore state")
        target.parentFile?.let(::fsyncDir)
    }

    private fun writeOutcome(message: String) {
        root.mkdirs()
        outcomeFile.writeText(message)
    }

    /** Renames, or copies (synced) and deletes when the two folders are on different volumes. */
    private fun moveFile(from: File, to: File) {
        to.parentFile?.mkdirs()
        if (from.renameTo(to)) return
        from.inputStream().use { input ->
            FileOutputStream(to).use { out ->
                input.copyTo(out)
                out.fd.sync()
            }
        }
        from.delete()
    }

    private fun fsyncDir(dir: File) {
        try {
            FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
        } catch (_: Exception) {
            // Some file systems refuse to sync a directory; the renames are still atomic.
        }
    }

    private companion object {
        const val TAG = "LagrangeRestore"
        val SUFFIXES = listOf("", "-wal", "-shm", "-journal")
        const val LEGACY_TMP_SUFFIX = ".restore-tmp"
        const val PREVIOUS_PREFIX = "previous-"
        const val PENDING_PREFS_PREFIX = "pending-preferences-"
        const val LEGACY_GENERATION = "legacy"

        fun sameSchema(actual: List<SchemaObject>, expected: List<SchemaObject>): Boolean {
            fun normalize(list: List<SchemaObject>) = list
                .filterNot { SqliteFileChecks.isInternal(it.name) }
                .map { it.copy(sql = it.sql?.replace(Regex("\\s+"), " ")?.trim()) }
                .toSet()
            return normalize(actual) == normalize(expected)
        }
    }
}

/** A restore rollback could not finish; the database must stay closed until a later start completes it. */
class RestoreRecoveryException(message: String, cause: Throwable) : Exception(message, cause)
