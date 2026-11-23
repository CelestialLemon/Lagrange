package com.celestiallemon.lagrange.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Fault injection and hostile-input tests for [RestoreCoordinator]. */
@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class RestoreSafetyTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "safety.preferences_pb") }
    private var db = LagrangeDatabase.create(context)
    private val restore = RestoreCoordinator(context)
    private val dbPath = context.getDatabasePath(LagrangeDatabase.FILE_NAME).path

    /** Stands in for the process dying at a checkpoint: an Error escapes the coordinator's exception handling. */
    private class SimulatedCrash : Error("simulated process death")

    private fun preferences() = LagrangePreferences(store, db)
    private fun backups() = LagrangeBackups(context, db, preferences(), restore, "test", 1, clock = { 1_800_000_000_000L })
    private suspend fun addPackage(name: String) = Interner(db, FakeResolver()).packageId(name)
    private suspend fun packageNames() = db.packageDao().all().map { it.packageName }.toSet() - ""
    private suspend fun backupBytes(): ByteArray = ByteArrayOutputStream().also { backups().writeBackup(it) }.toByteArray()

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        restore.clearAll()
        context.deleteDatabase(LagrangeDatabase.FILE_NAME)
    }

    /** A fresh coordinator, as a new process would build one. */
    private fun nextStart() = RestoreCoordinator(context)

    private fun reopen() {
        db = LagrangeDatabase.create(context)
    }

    private fun crashAt(coordinator: RestoreCoordinator, point: String) {
        coordinator.checkpoint = { if (it == point) throw SimulatedCrash() }
        try {
            coordinator.applyPendingIfAny()
            fail("expected a simulated crash at $point")
        } catch (_: SimulatedCrash) {
        }
    }

    @Test
    fun `a crash before anything moved leaves the live database untouched`() = runBlocking<Unit> {
        addPackage("com.kept")
        val bytes = backupBytes()
        addPackage("com.after_backup")
        backups().stageRestore(ByteArrayInputStream(bytes))
        db.close()

        crashAt(RestoreCoordinator(context), "before-move")
        nextStart().applyPendingIfAny()
        assertNull(nextStart().readJournal())
        reopen()
        assertEquals(setOf("com.kept", "com.after_backup"), packageNames())
        assertTrue(restore.consumeOutcome()!!.contains("interrupted"))
    }

    @Test
    fun `a crash between moving the database and its WAL keeps every committed row`() = runBlocking<Unit> {
        addPackage("com.kept")
        val bytes = backupBytes()
        backups().stageRestore(ByteArrayInputStream(bytes))
        addPackage("com.only_in_wal")

        // Capture the live generation with the new row still only in the WAL, as a killed process would leave it.
        val scratch = File(context.cacheDir, "wal-capture").apply { deleteRecursively(); mkdirs() }
        File(dbPath).copyTo(File(scratch, "main"), overwrite = true)
        File("$dbPath-wal").copyTo(File(scratch, "wal"), overwrite = true)
        db.close()
        File(scratch, "main").copyTo(File(dbPath), overwrite = true)
        File(scratch, "wal").copyTo(File("$dbPath-wal"), overwrite = true)
        File("$dbPath-shm").delete()
        assertTrue("precondition: the row lives in the WAL", File("$dbPath-wal").length() > 0)

        crashAt(RestoreCoordinator(context), "moved:${LagrangeDatabase.FILE_NAME}")
        assertTrue("WAL not moved yet", File("$dbPath-wal").isFile)
        assertFalse("main file already moved aside", File(dbPath).exists())

        nextStart().applyPendingIfAny()
        assertNull(nextStart().readJournal())
        reopen()
        assertEquals(setOf("com.kept", "com.only_in_wal"), packageNames())
        scratch.deleteRecursively()
    }

    @Test
    fun `a swap that fails before anything moved never touches the live database or its WAL`() = runBlocking<Unit> {
        addPackage("com.kept")
        val bytes = backupBytes()
        backups().stageRestore(ByteArrayInputStream(bytes))
        addPackage("com.only_in_wal")

        // Leave the live generation as a killed process would: the newest row only in the WAL.
        val scratch = File(context.cacheDir, "wal-capture-2").apply { deleteRecursively(); mkdirs() }
        File(dbPath).copyTo(File(scratch, "main"), overwrite = true)
        File("$dbPath-wal").copyTo(File(scratch, "wal"), overwrite = true)
        db.close()
        File(scratch, "main").copyTo(File(dbPath), overwrite = true)
        File(scratch, "wal").copyTo(File("$dbPath-wal"), overwrite = true)
        File("$dbPath-shm").delete()
        val mainBefore = File(dbPath).readBytes()
        val walBefore = File("$dbPath-wal").readBytes()
        assertTrue("precondition: the row lives in the WAL", walBefore.isNotEmpty())

        // A regular file where the rollback folder must go: creating previous-<generation>/ fails while STAGED.
        val generation = restore.readJournal()!!.generation
        val blocker = File(File(dbPath).parentFile, "lagrange-restore/previous-$generation").apply { writeText("in the way") }

        nextStart().applyPendingIfAny()

        assertTrue(File(dbPath).readBytes().contentEquals(mainBefore))
        assertTrue(File("$dbPath-wal").readBytes().contentEquals(walBefore))
        assertNull("journal cleared", nextStart().readJournal())
        assertFalse("staged restore discarded", restore.hasStagedRestore())
        assertFalse(blocker.exists())
        assertTrue(restore.consumeOutcome()!!.contains("unchanged"))
        reopen()
        assertEquals(setOf("com.kept", "com.only_in_wal"), packageNames())
        scratch.deleteRecursively()
    }

    @Test
    fun `an incomplete rollback throws instead of letting Room open a mixed generation`() = runBlocking<Unit> {
        addPackage("com.kept")
        val bytes = backupBytes()
        backups().stageRestore(ByteArrayInputStream(bytes))
        db.close()
        restore.applyPendingIfAny()
        restore.beginVerification()

        // A non-empty directory where the restored WAL would be deleted from: the rollback can't finish.
        val blocker = File("$dbPath-wal").apply { delete(); mkdirs() }
        File(blocker, "child").writeText("x")
        try {
            nextStart().applyPendingIfAny()
            fail("an incomplete rollback must not return normally")
        } catch (e: RestoreRecoveryException) {
            assertTrue(e.message!!.contains("Restart"))
        }
        assertNotNull("journal kept for the next start", nextStart().readJournal())

        // Once the obstacle is gone the next start completes the rollback.
        blocker.deleteRecursively()
        nextStart().applyPendingIfAny()
        assertNull(nextStart().readJournal())
        reopen()
        assertEquals(setOf("com.kept"), packageNames())
    }

    @Test
    fun `a crash after the swap, before verification, is verified on the next start`() = runBlocking<Unit> {
        addPackage("com.kept")
        val bytes = backupBytes()
        addPackage("com.after_backup")
        backups().stageRestore(ByteArrayInputStream(bytes))
        db.close()
        crashAt(RestoreCoordinator(context), "placed")
        // Placed but never journaled SWAPPED: the restore is undone.
        nextStart().applyPendingIfAny()
        reopen()
        assertEquals(setOf("com.kept", "com.after_backup"), packageNames())
    }

    @Test
    fun `a crash while opening the restored database rolls back instead of looping`() = runBlocking<Unit> {
        addPackage("com.kept")
        val bytes = backupBytes()
        addPackage("com.after_backup")
        backups().stageRestore(ByteArrayInputStream(bytes))
        db.close()

        restore.applyPendingIfAny()
        assertTrue(restore.needsVerification())
        restore.beginVerification()
        // The open crashes the process here. On the next start the restore must not be tried again.
        val next = nextStart()
        next.applyPendingIfAny()
        assertNull(next.readJournal())
        assertFalse(next.needsVerification())
        next.applyPendingIfAny() // idempotent
        reopen()
        assertEquals(setOf("com.kept", "com.after_backup"), packageNames())
        assertTrue(next.consumeOutcome()!!.contains("previous data"))
        assertNull(next.pendingPreferences())
    }

    @Test
    fun `a VERIFY_PENDING journal left by the first build is rolled back`() = runBlocking<Unit> {
        addPackage("com.kept")
        db.close()
        val root = File(context.noBackupFilesDir, "restore").apply { mkdirs() }
        val previous = File(root, "previous").apply { mkdirs() }
        assertTrue(File(dbPath).renameTo(File(previous, LagrangeDatabase.FILE_NAME)))
        File(dbPath).writeText("restored but never verified")
        File(root, "STATE").writeText("VERIFY_PENDING")
        nextStart().applyPendingIfAny()
        reopen()
        assertEquals(setOf("com.kept"), packageNames())
    }

    @Test
    fun `restored preferences belong to their generation`() = runBlocking<Unit> {
        addPackage("com.kept")
        preferences().setRolloverHour(7)
        val bytes = backupBytes()
        preferences().setRolloverHour(4)

        // Generation A is rolled back: its preferences must never be applied.
        backups().stageRestore(ByteArrayInputStream(bytes))
        db.close()
        restore.applyPendingIfAny()
        restore.beginVerification()
        restore.rollback("simulated")
        assertNull(restore.pendingPreferences())
        reopen()

        // Generation B is verified: its preferences are offered exactly once the journal is clear.
        backups().stageRestore(ByteArrayInputStream(bytes))
        db.close()
        restore.applyPendingIfAny()
        assertNull("not while verification is pending", restore.pendingPreferences())
        restore.beginVerification()
        reopen()
        restore.markVerified()
        val file = restore.pendingPreferences()
        assertNotNull(file)
        assertTrue(file!!.readText().contains("7"))

        // A stray file from another generation is discarded, the verified one kept.
        val stray = File(file.parentFile, "pending-preferences-not-a-real-generation.json").apply { writeText("{}") }
        assertEquals(file, restore.pendingPreferences())
        assertFalse(stray.exists())
    }

    // -- Hostile bundles -------------------------------------------------------------------------------------------

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes()
            }
        }
        return out
    }

    private fun zip(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { bos ->
        ZipOutputStream(bos).use { z ->
            entries.forEach { (name, data) ->
                z.putNextEntry(ZipEntry(name))
                z.write(data)
                z.closeEntry()
            }
        }
    }.toByteArray()

    /** Edits the database inside a good bundle and re-signs the manifest, as a careful attacker would. */
    private fun tamper(good: Map<String, ByteArray>, edit: (SQLiteDatabase) -> Unit): ByteArray {
        val file = File(context.cacheDir, "tamper.sqlite").apply { writeBytes(good.getValue(BackupFormat.DB_ENTRY)) }
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS).use(edit)
        File(file.path + "-journal").delete()
        val dbBytes = file.readBytes()
        file.delete()
        val manifest = BackupFormat.json.decodeFromString<BackupManifest>(good.getValue(BackupFormat.MANIFEST_ENTRY).decodeToString())
        val files = manifest.files.map {
            if (it.name == BackupFormat.DB_ENTRY) BackupFileEntry(it.name, BackupFormat.sha256(ByteArrayInputStream(dbBytes)), dbBytes.size.toLong()) else it
        }
        return zip(
            good + mapOf(
                BackupFormat.DB_ENTRY to dbBytes,
                BackupFormat.MANIFEST_ENTRY to BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest.copy(files = files)).encodeToByteArray(),
            ),
        )
    }

    private suspend fun assertRejected(bytes: ByteArray, expect: String) {
        try {
            backups().stageRestore(ByteArrayInputStream(bytes))
            fail("expected rejection: $expect")
        } catch (e: BackupException) {
            assertTrue("${e.message} should mention '$expect'", e.message!!.contains(expect, ignoreCase = true))
        }
        assertFalse(restore.hasStagedRestore())
    }

    @Test
    fun `a re-signed bundle missing a table but keeping the Room identity is rejected`() = runBlocking<Unit> {
        addPackage("com.kept")
        val good = unzip(backupBytes())
        assertRejected(tamper(good) { it.execSQL("DROP TABLE daily_notes") }, "layout")
    }

    @Test
    fun `a re-signed bundle with an extra table, trigger or altered column is rejected`() = runBlocking<Unit> {
        addPackage("com.kept")
        val good = unzip(backupBytes())
        assertRejected(tamper(good) { it.execSQL("CREATE TABLE exfil (x TEXT)") }, "layout")
        assertRejected(
            tamper(good) { it.execSQL("CREATE TRIGGER t AFTER INSERT ON packages BEGIN DELETE FROM packages; END") },
            "layout",
        )
        assertRejected(tamper(good) { it.execSQL("ALTER TABLE packages ADD COLUMN spy TEXT") }, "layout")
    }

    @Test
    fun `a re-signed bundle with broken foreign keys is rejected`() = runBlocking<Unit> {
        addPackage("com.kept")
        val good = unzip(backupBytes())
        val bytes = tamper(good) {
            it.execSQL("PRAGMA foreign_keys = OFF")
            it.execSQL("INSERT INTO raw_usage_events (timestamp_utc_ms, event_type, pkg_id, cls_id, seq_in_ms) VALUES (1, 1, 999, 999, 0)")
        }
        assertRejected(bytes, "broken links")
    }

    @Test
    fun `a WAL-mode database file is rejected rather than modified`() = runBlocking<Unit> {
        addPackage("com.kept")
        val good = unzip(backupBytes())
        val bytes = tamper(good) { it.rawQuery("PRAGMA journal_mode=WAL", null).use { c -> c.moveToFirst() } }
        assertRejected(bytes, "self-contained")
    }

    @Test
    fun `the staged database is never modified after its checksum was verified`() = runBlocking<Unit> {
        addPackage("com.kept")
        val good = unzip(backupBytes())
        backups().stageRestore(ByteArrayInputStream(zip(good)))
        val staged = File(context.getDatabasePath(LagrangeDatabase.FILE_NAME).parentFile, "lagrange-restore/staged/${BackupFormat.DB_ENTRY}")
        assertTrue(staged.isFile)
        assertEquals(BackupFormat.sha256(ByteArrayInputStream(good.getValue(BackupFormat.DB_ENTRY))), BackupFormat.sha256(staged))
        assertEquals(listOf(BackupFormat.DB_ENTRY, BackupFormat.MANIFEST_ENTRY).sorted(), staged.parentFile!!.list()!!.sorted())
    }
}
