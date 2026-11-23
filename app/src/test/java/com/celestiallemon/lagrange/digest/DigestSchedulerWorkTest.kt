package com.celestiallemon.lagrange.digest

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.domain.digest.DigestKind
import java.time.ZoneId
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class DigestSchedulerWorkTest {
    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val zone = ZoneId.of("Europe/Berlin")
    private val on = DigestScheduler.Settings.of(UserPrefs().copy(dailyDigestEnabled = true, weeklyDigestEnabled = false))

    @Before
    fun setUp() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    private fun pending(kind: DigestKind): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(DigestScheduler.uniqueName(kind)).get().filter { !it.state.isFinished }

    @Test
    fun `ensureScheduled keeps an up-to-date run, replaces a stale one, and cancels when disabled`() = runBlocking {
        DigestScheduler.ensureScheduled(context, on, zone = zone)
        val first = pending(DigestKind.DAILY).single()
        assertTrue(DigestScheduler.fingerprint(on, DigestKind.DAILY, zone) in first.tags)
        assertTrue(pending(DigestKind.WEEKLY).isEmpty())

        DigestScheduler.ensureScheduled(context, on, zone = zone)
        assertEquals(first.id, pending(DigestKind.DAILY).single().id)

        val moved = on.copy(dailyMinuteOfDay = 7 * 60)
        DigestScheduler.ensureScheduled(context, moved, zone = zone)
        val second = pending(DigestKind.DAILY).single()
        assertNotEquals(first.id, second.id)
        assertTrue(DigestScheduler.fingerprint(moved, DigestKind.DAILY, zone) in second.tags)

        // A forced replan (clock jump) replaces the queued run but still leaves exactly one.
        DigestScheduler.ensureScheduled(context, moved, force = true, zone = zone)
        assertEquals(1, pending(DigestKind.DAILY).size)

        DigestScheduler.ensureScheduled(context, moved.copy(dailyEnabled = false), zone = zone)
        assertTrue(pending(DigestKind.DAILY).isEmpty())
    }

    @Test
    fun `scheduleNext leaves exactly one run, carrying its due instant`() = runBlocking {
        DigestScheduler.ensureScheduled(context, on, zone = zone)
        DigestScheduler.scheduleNext(context, DigestKind.DAILY) { on }
        DigestScheduler.scheduleNext(context, DigestKind.DAILY) { on }
        assertEquals(1, pending(DigestKind.DAILY).size)
        // A run handed off while the digest was turned off retires the chain itself, so nothing is left RUNNING
        // for ensureScheduled to "keep" after the lock is released.
        DigestScheduler.scheduleNext(context, DigestKind.DAILY) { on.copy(dailyEnabled = false) }
        assertTrue(pending(DigestKind.DAILY).isEmpty())
    }

    @Test
    fun `the request carries the due instant of its occurrence`() {
        val now = 1_795_507_200_000L // 2026-11-24 09:00 Berlin
        val request = DigestScheduler.request(on, DigestKind.DAILY, now, zone)
        val due = request.workSpec.input.getLong(DigestWorker.KEY_DUE_AT_MS, -1)
        assertEquals(now + request.workSpec.initialDelay, due)
        assertEquals(DigestKind.DAILY.name, request.workSpec.input.getString(DigestWorker.KEY_KIND))
    }
}
