package com.celestiallemon.lagrange.harvest

import androidx.work.ListenableWorker
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class HarvestWorkerTest {
    private val ok = HarvestResult.Success(1, 1, 0, 1, wasBackfill = false, gapDetected = false)

    @Test
    fun `every harvest outcome is a success for WorkManager`() = runBlocking {
        for (result in listOf(ok, HarvestResult.NoAccess, HarvestResult.Unavailable("locked"))) {
            assertEquals(ListenableWorker.Result.success(), HarvestWorker.run({ result }))
        }
    }

    @Test
    fun `a failing side task never blocks the harvest`() = runBlocking {
        var harvested = false
        var heartbeat = false
        val result = HarvestWorker.run(
            harvest = { harvested = true; ok },
            sideTasks = listOf({ throw IOException("tz") }, { heartbeat = true }),
        )
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(harvested && heartbeat)
    }

    @Test
    fun `an unexpected harvest error retries, cancellation propagates`() = runBlocking {
        assertEquals(ListenableWorker.Result.retry(), HarvestWorker.run({ throw IllegalStateException("db") }))
        try {
            HarvestWorker.run({ throw CancellationException("stopped") })
            fail("cancellation must propagate")
        } catch (_: CancellationException) {
        }
    }
}
