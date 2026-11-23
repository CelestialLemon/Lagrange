package com.celestiallemon.lagrange.backup

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.MutableClock
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.LagrangePreferences
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BackupFolderTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "folder.preferences_pb") }
    private val db = LagrangeDatabase.inMemory(context)
    private val preferences = LagrangePreferences(store, db)
    private val clock = MutableClock(1_800_000_000_000L)
    private val backups = LagrangeBackups(context, db, preferences, RestoreCoordinator(context), "test", 1, clock.fn)
    private val dir = File(context.cacheDir, "backup-folder").apply { deleteRecursively(); mkdirs() }
    private val folder = FileBackupFolder(dir)

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    private suspend fun backUp() = backups.backUpToFolder({ folder }, automatic = true, keep = 3)

    @Test
    fun `retention counts and prunes only completed app backups`() = runBlocking<Unit> {
        Interner(db, FakeResolver()).packageId("com.kept")
        // Things in the folder Lagrange did not write, including one that looks exactly like a backup name.
        val foreign = listOf("notes.txt", BackupFormat.fileName(1_000L), "${BackupFormat.FILE_PREFIX}draft.zip")
        foreign.forEach { File(dir, it).writeText("user file") }

        val written = (0 until 5).map {
            clock.now += 86_400_000L
            backUp()
        }
        val names = dir.list()!!.toSet()
        assertTrue("user files untouched: $names", names.containsAll(foreign))
        assertEquals(written.takeLast(3).toSet(), names - foreign.toSet())
        assertEquals(3, preferences.completedBackups().size)
        assertEquals(LagrangePreferences.STATUS_OK, preferences.current().lastBackupStatus)
    }

    @Test
    fun `a failed write is deleted, never counted, and reported with the right label`() = runBlocking<Unit> {
        val failing = object : BackupFolder by folder {
            override fun openOutput(doc: BackupDocument): OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw IOException("disk full")
            }
        }
        try {
            backups.backUpToFolder({ failing }, automatic = false)
            fail("expected failure")
        } catch (e: IOException) {
            assertEquals("disk full", e.message)
        }
        assertTrue(dir.list()!!.isEmpty())
        assertTrue(preferences.completedBackups().isEmpty())
        assertNull(preferences.backupInProgress())
        assertTrue(preferences.current().lastBackupStatus!!.startsWith("Backup to folder failed"))

        try {
            backups.backUpToFolder({ failing }, automatic = true)
        } catch (_: IOException) {
        }
        assertTrue(preferences.current().lastBackupStatus!!.startsWith("Automatic backup failed"))
    }

    @Test
    fun `a backup half-written by a killed process is cleaned up and never counted`() = runBlocking<Unit> {
        val partial = File(dir, BackupFormat.fileName(5_000L)).apply { writeText("trunc") }
        preferences.setBackupInProgress(partial.path)
        backUp()
        assertFalse(partial.exists())
        assertEquals(1, dir.list()!!.size)
        assertNull(preferences.backupInProgress())
    }

    @Test
    fun `cancellation removes the partial file and records no failure`() = runBlocking<Unit> {
        val cancelling = object : BackupFolder by folder {
            override fun openOutput(doc: BackupDocument): OutputStream = object : OutputStream() {
                override fun write(b: Int) = throw CancellationException("stopped")
            }
        }
        try {
            backups.backUpToFolder({ cancelling }, automatic = true)
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertTrue(dir.list()!!.isEmpty())
        assertNull(preferences.current().lastBackupStatus)
    }

    @Test
    fun `concurrent backups and snapshot cleanup are serialized`() = runBlocking<Unit> {
        Interner(db, FakeResolver()).packageId("com.kept")
        (0 until 3).map { i ->
            async(Dispatchers.IO) {
                if (i == 1) backups.clearSnapshots() else {
                    clock.now += 1_000L * (i + 1)
                    backUp()
                }
            }
        }.awaitAll()
        assertEquals(2, preferences.completedBackups().size)
    }

    @Test
    fun `raw export is a self-contained, integrity-checked SQLite file`() = runBlocking<Unit> {
        Interner(db, FakeResolver()).packageId("com.kept")
        val bytes = ByteArrayOutputStream().also { backups.exportRawSqlite(it) }.toByteArray()
        val file = File(context.cacheDir, "export.sqlite").apply { writeBytes(bytes) }
        assertEquals(1, bytes[18].toInt())
        val report = SqliteFileChecks.inspectReadOnly(file)
        assertEquals("ok", report.integrity)
        assertEquals(LagrangeDatabase.SCHEMA_VERSION, report.userVersion)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use {
            it.rawQuery("SELECT COUNT(*) FROM packages WHERE package_name = 'com.kept'", null).use { c ->
                c.moveToFirst()
                assertEquals(1, c.getInt(0))
            }
        }
        file.delete()
    }

    @Test
    fun `the weekly worker succeeds, retries, gives up and propagates cancellation`() = runBlocking<Unit> {
        assertEquals(ListenableWorker.Result.success(), WeeklyBackupWorker.run(null, 0) { error("no folder, no call") })
        assertEquals(ListenableWorker.Result.success(), WeeklyBackupWorker.run("tree", 0) { "ok.zip" })
        assertEquals(ListenableWorker.Result.retry(), WeeklyBackupWorker.run("tree", 0) { throw IOException("x") })
        assertEquals(ListenableWorker.Result.failure(), WeeklyBackupWorker.run("tree", WeeklyBackupWorker.MAX_ATTEMPTS) { throw IOException("x") })
        try {
            WeeklyBackupWorker.run("tree", 0) { throw CancellationException("stopped") }
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
