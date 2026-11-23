package com.celestiallemon.lagrange.system

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.celestiallemon.lagrange.DAY
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.FakeUsageEventSource
import com.celestiallemon.lagrange.HOUR
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.harvest.HarvestResult
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import com.celestiallemon.lagrange.harvest.RawUsageEvent
import com.celestiallemon.lagrange.harvest.UsageStatsHarvester
import com.celestiallemon.lagrange.harvest.WorkScheduler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.celestiallemon.lagrange.backup.BackupDocument
import com.celestiallemon.lagrange.backup.BackupException
import com.celestiallemon.lagrange.backup.BackupFolder
import com.celestiallemon.lagrange.backup.FileBackupFolder
import com.celestiallemon.lagrange.backup.LagrangeBackups
import com.celestiallemon.lagrange.backup.RestoreCoordinator
import com.celestiallemon.lagrange.data.LagrangePreferences
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
class DataResetTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000_000L
    private val grant = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION

    @After
    fun tearDown() {
        context.deleteDatabase(LagrangeDatabase.FILE_NAME)
        File(context.noBackupFilesDir, "reset").deleteRecursively()
    }

    private fun reset(cancel: suspend () -> Unit = {}) = DataReset(context, clock = { now }, cancelWork = cancel)

    @Test
    fun `requesting a reset cancels work, releases the backup folder grant and stages the wipe`() = runBlocking {
        val folder = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABackups")
        context.contentResolver.takePersistableUriPermission(folder, grant)
        assertEquals(1, context.contentResolver.persistedUriPermissions.size)
        var cancelled = false
        assertEquals(now, reset { cancelled = true }.requestReset())
        assertTrue(cancelled)
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        assertTrue(DataReset.isPending(context))
    }

    @Test
    fun `cancelling work waits for every Lagrange job to be cancelled`() = runBlocking {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        WorkScheduler.schedulePeriodicHarvest(context)
        WorkScheduler.setWeeklyBackup(context, enabled = true)
        WorkScheduler.enqueueHarvestNow(context, HarvestTrigger.MANUAL)
        com.celestiallemon.lagrange.digest.DigestScheduler.ensureScheduled(
            context,
            com.celestiallemon.lagrange.digest.DigestScheduler.Settings(true, 21 * 60, true, 7, 20 * 60),
        )
        val wm = WorkManager.getInstance(context)
        assertTrue(WorkScheduler.DIGEST_WORK.all { name -> wm.getWorkInfosForUniqueWork(name).get().any { !it.state.isFinished } })
        WorkScheduler.cancelAllAndAwait(context)
        for (name in listOf(WorkScheduler.HARVEST_PERIODIC, WorkScheduler.HARVEST_NOW, WorkScheduler.WEEKLY_BACKUP) + WorkScheduler.DIGEST_WORK) {
            val infos = wm.getWorkInfosForUniqueWork(name).get()
            assertTrue("$name: $infos", infos.all { it.state == WorkInfo.State.CANCELLED || it.state.isFinished })
        }
    }

    @Test
    fun `the wipe runs before Room opens and leaves no database, preferences, staging or caches`() = runBlocking {
        val db = LagrangeDatabase.create(context)
        Interner(db, FakeResolver()).packageId("com.secret")
        db.close()
        val files = listOf(
            File(context.filesDir, "datastore/lagrange_prefs.preferences_pb"),
            File(context.noBackupFilesDir, "restore/STATE"),
            File(context.getDatabasePath(LagrangeDatabase.FILE_NAME).parentFile, "lagrange-restore/staged/lagrange.sqlite"),
            File(context.cacheDir, "snapshots/x/lagrange.sqlite"),
            File(context.noBackupFilesDir, "pseudonym.key"),
        )
        files.forEach { it.parentFile!!.mkdirs(); it.writeText("x") }
        File(context.getDatabasePath(LagrangeDatabase.FILE_NAME).path + "-wal").writeText("stale wal")

        reset().requestReset()
        assertTrue(DataReset.applyPendingIfAny(context))
        assertFalse(context.getDatabasePath(LagrangeDatabase.FILE_NAME).exists())
        assertFalse(File(context.getDatabasePath(LagrangeDatabase.FILE_NAME).path + "-wal").exists())
        files.forEach { assertFalse("${it.path} survived", it.exists()) }
        assertFalse(DataReset.isPending(context))
        assertEquals(now, DataReset.pendingCutoff(context))
        assertFalse("applied once", DataReset.applyPendingIfAny(context))

        val fresh = LagrangeDatabase.create(context)
        assertTrue(fresh.packageDao().all().none { it.packageName == "com.secret" })
        fresh.close()
    }

    @Test
    fun `the reset marker stays until every file is verifiably gone`() = runBlocking {
        // Something that survives a plain delete where the preferences file lives (a non-empty directory).
        val prefs = File(context.filesDir, "datastore/lagrange_prefs.preferences_pb").apply { mkdirs() }
        File(prefs, "stuck").writeText("x")
        reset().requestReset()
        try {
            DataReset.applyPendingIfAny(context)
            fail("an unverified wipe must not complete")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("lagrange_prefs"))
        }
        assertTrue("marker kept so the next start retries", DataReset.isPending(context))

        prefs.deleteRecursively()
        assertTrue(DataReset.applyPendingIfAny(context))
        assertFalse(DataReset.isPending(context))
    }

    @Test
    fun `reset refuses new backups and waits for a running one before releasing grants`() = runBlocking<Unit> {
        val folderUri = Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ABackups")
        context.contentResolver.takePersistableUriPermission(folderUri, grant)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val store = PreferenceDataStoreFactory.create(scope = scope) { File(context.filesDir, "reset-drain.preferences_pb") }
        val db = LagrangeDatabase.inMemory(context)
        val backups = LagrangeBackups(context, db, LagrangePreferences(store, db), RestoreCoordinator(context), "test", 1, { now })
        val dir = File(context.cacheDir, "reset-drain-folder").apply { deleteRecursively(); mkdirs() }
        val writing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val slow = object : BackupFolder by FileBackupFolder(dir) {
            override fun openOutput(doc: BackupDocument): OutputStream {
                val real = FileOutputStream(doc.id)
                return object : OutputStream() {
                    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)
                    override fun write(b: ByteArray, off: Int, len: Int) {
                        writing.countDown()
                        release.await()
                        real.write(b, off, len)
                    }
                    override fun close() = real.close()
                }
            }
        }
        val running = async(Dispatchers.IO) { backups.backUpToFolder({ slow }, automatic = true) }
        assertTrue(writing.await(10, TimeUnit.SECONDS))

        var cancelled = false
        val resetting = async(Dispatchers.IO) { DataReset(context, { now }, { cancelled = true }, backups).requestReset() }
        delay(300)
        assertTrue("work cancelled before draining", cancelled)
        assertFalse("still waiting for the running backup", resetting.isCompleted)
        assertEquals("grant kept while the ZIP is still being written", 1, context.contentResolver.persistedUriPermissions.size)
        assertFalse(DataReset.isPending(context))
        try {
            backups.writeBackup(ByteArrayOutputStream())
            fail("new backups must be refused once a reset began")
        } catch (e: BackupException) {
            assertTrue(e.message!!.contains("resetting"))
        }

        release.countDown()
        running.await()
        assertEquals(now, resetting.await())
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        assertTrue(DataReset.isPending(context))
        db.close()
        scope.cancel()
        dir.deleteRecursively()
    }

    @Test
    fun `after a reset the next harvest does not re-import the old 14 days`() = runBlocking {
        reset().requestReset()
        DataReset.applyPendingIfAny(context)
        val db = LagrangeDatabase.create(context)
        DataReset.recordPendingCutoff(context, db)
        assertEquals(now, db.ingestionStateDao().get(IngestionKeys.RESET_CUTOFF_MS))
        assertNull(DataReset.pendingCutoff(context))

        val source = FakeUsageEventSource(
            listOf(
                RawUsageEvent(now - 10 * DAY, 1, "com.old", null),
                RawUsageEvent(now - 2 * HOUR, 1, "com.old", null),
                RawUsageEvent(now + 5 * 60_000, 1, "com.new", null),
            ),
        )
        val clock = now + HOUR
        val r = UsageStatsHarvester(db, source, Interner(db, FakeResolver()), { clock }).harvest(HarvestTrigger.APP_OPEN)
        assertEquals(now, (r as HarvestResult.Success).fromMs)
        assertEquals(listOf(now + 5 * 60_000), db.rawUsageEventDao().allForTest().map { it.timestampUtcMs })
        db.close()
    }
}
