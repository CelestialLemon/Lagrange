package com.celestiallemon.lagrange.backup

import android.app.Application
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

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class BackupRestoreTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val store = PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "t.preferences_pb") }
    private var db = LagrangeDatabase.create(context)
    private val restore = RestoreCoordinator(context)

    private fun preferences() = LagrangePreferences(store, db)
    private fun backups() = LagrangeBackups(context, db, preferences(), restore, "test", 1, clock = { 1_800_000_000_000L })
    private suspend fun addPackage(name: String) = Interner(db, FakeResolver()).packageId(name)
    private suspend fun packageNames() = db.packageDao().all().map { it.packageName }.toSet()

    @After
    fun tearDown() {
        db.close()
        scope.cancel()
        restore.clearAll()
        context.deleteDatabase(LagrangeDatabase.FILE_NAME)
    }

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

    private suspend fun backupBytes(): ByteArray = ByteArrayOutputStream().also { backups().writeBackup(it) }.toByteArray()

    @Test
    fun `backup bundles a verified snapshot, preferences and a checksummed manifest`() = runBlocking {
        addPackage("com.kept")
        preferences().setRolloverHour(6)
        val entries = unzip(backupBytes())
        assertEquals(BackupFormat.ENTRIES, entries.keys)
        val manifest = BackupFormat.json.decodeFromString<BackupManifest>(entries.getValue(BackupFormat.MANIFEST_ENTRY).decodeToString())
        assertEquals(LagrangeDatabase.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals(db.identityHash(), manifest.roomIdentityHash)
        for (f in manifest.files) {
            val data = entries.getValue(f.name)
            assertEquals(f.size, data.size.toLong())
            assertEquals(f.sha256, BackupFormat.sha256(ByteArrayInputStream(data)))
        }
        assertTrue(entries.getValue(BackupFormat.PREFS_ENTRY).decodeToString().contains("6"))
    }

    @Test
    fun `staged restore swaps the database before Room opens and drops sidecars`() = runBlocking {
        addPackage("com.kept")
        val bytes = backupBytes()
        addPackage("com.after_backup")
        backups().stageRestore(ByteArrayInputStream(bytes))
        assertTrue(restore.hasStagedRestore())
        assertEquals(setOf("", "com.kept", "com.after_backup").minus(""), packageNames().minus(""))

        db.close()
        val path = context.getDatabasePath(LagrangeDatabase.FILE_NAME).path
        File("$path-wal").writeText("stale")
        File("$path-shm").writeText("stale")
        restore.applyPendingIfAny()
        assertFalse(File("$path-wal").exists())
        assertFalse(File("$path-shm").exists())
        assertTrue(restore.isVerifyPending())

        db = LagrangeDatabase.create(context)
        assertEquals(setOf("com.kept"), packageNames().minus(""))
        restore.markVerified()
        assertFalse(restore.isVerifyPending())
        assertEquals("Backup restored.", restore.consumeOutcome())
        assertTrue(restore.pendingPreferencesFile.isFile)
    }

    @Test
    fun `a failed open rolls back to the previous generation`() = runBlocking {
        addPackage("com.kept")
        val bytes = backupBytes()
        addPackage("com.after_backup")
        backups().stageRestore(ByteArrayInputStream(bytes))
        db.close()
        restore.applyPendingIfAny()
        restore.rollback("simulated")
        db = LagrangeDatabase.create(context)
        assertEquals(setOf("com.kept", "com.after_backup"), packageNames().minus(""))
        assertNotNull(restore.consumeOutcome())
        assertFalse(restore.pendingPreferencesFile.exists())
    }

    @Test
    fun `cancelled staging leaves nothing to apply`() = runBlocking {
        val bytes = backupBytes()
        backups().stageRestore(ByteArrayInputStream(bytes))
        restore.cancelStaged()
        assertFalse(restore.hasStagedRestore())
        db.close()
        restore.applyPendingIfAny()
        assertFalse(restore.isVerifyPending())
        db = LagrangeDatabase.create(context)
        assertNull(restore.consumeOutcome())
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
    fun `tampered, traversing, incomplete and newer-schema bundles are rejected`() = runBlocking {
        addPackage("com.kept")
        val good = unzip(backupBytes())

        val tampered = good.toMutableMap()
        tampered[BackupFormat.PREFS_ENTRY] = """{"format_version":1,"rollover_hour":9}""".encodeToByteArray()
        assertRejected(zip(tampered), "checksum")

        assertRejected(zip(good + ("../evil" to byteArrayOf(1))), "unexpected")
        assertRejected(zip(good - BackupFormat.PREFS_ENTRY), "complete")

        val manifest = BackupFormat.json.decodeFromString<BackupManifest>(good.getValue(BackupFormat.MANIFEST_ENTRY).decodeToString())
        val newer = good.toMutableMap()
        newer[BackupFormat.MANIFEST_ENTRY] = BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest.copy(schemaVersion = 99)).encodeToByteArray()
        assertRejected(zip(newer), "newer")

        assertRejected("not a zip".encodeToByteArray(), "complete")
    }

    @Test
    fun `an interrupted swap rolls back on the next start`() = runBlocking {
        addPackage("com.kept")
        db.close()
        val root = File(context.noBackupFilesDir, "restore").apply { mkdirs() }
        val previous = File(root, "previous").apply { mkdirs() }
        val dbFile = context.getDatabasePath(LagrangeDatabase.FILE_NAME)
        assertTrue(dbFile.renameTo(File(previous, dbFile.name)))
        dbFile.writeText("half-written")
        File(root, "STATE").writeText("SWAPPING")
        restore.applyPendingIfAny()
        db = LagrangeDatabase.create(context)
        assertEquals(setOf("com.kept"), packageNames().minus(""))
        assertTrue(restore.consumeOutcome()!!.contains("interrupted"))
    }

    @Test
    fun `weekly pruning keeps the newest backups only`() {
        val names = (1..10).map { BackupFormat.fileName(1_800_000_000_000L + it * 86_400_000L) } + "notes.txt"
        val prune = BackupFormat.toPrune(names.shuffled(), 8)
        assertEquals(names.take(2).toSet(), prune.toSet())
    }
}
