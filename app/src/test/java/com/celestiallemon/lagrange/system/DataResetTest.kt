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
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        WorkScheduler.cancelAllAndAwait(context)
        val wm = WorkManager.getInstance(context)
        for (name in listOf(WorkScheduler.HARVEST_PERIODIC, WorkScheduler.HARVEST_NOW, WorkScheduler.WEEKLY_BACKUP)) {
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
