package com.celestiallemon.lagrange.digest

import androidx.work.ExistingWorkPolicy
import com.celestiallemon.lagrange.data.UserPrefs
import com.celestiallemon.lagrange.digest.DigestScheduler.Job
import com.celestiallemon.lagrange.digest.DigestScheduler.Plan
import com.celestiallemon.lagrange.domain.digest.DigestKind
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class DigestSchedulerPlanTest {
    private val fp = "digest-fp:daily:1290:Europe/Berlin"

    @Test
    fun `disabled cancels, an up-to-date queued run is kept`() {
        assertEquals(Plan.CancelAll, DigestScheduler.plan(false, listOf(Job(false, fp)), fp))
        assertEquals(Plan.Keep, DigestScheduler.plan(true, listOf(Job(false, fp)), fp))
    }

    @Test
    fun `nothing queued, or a stale run, is (re)placed`() {
        assertEquals(Plan.Enqueue(emptyList(), ExistingWorkPolicy.REPLACE), DigestScheduler.plan(true, emptyList(), fp))
        assertEquals(Plan.Enqueue(listOf(0), ExistingWorkPolicy.REPLACE), DigestScheduler.plan(true, listOf(Job(false, "digest-fp:daily:600:Europe/Berlin")), fp))
    }

    @Test
    fun `a running digest is never cancelled`() {
        // Running alone: it queues its own successor with the current settings when it finishes.
        assertEquals(Plan.Keep, DigestScheduler.plan(true, listOf(Job(true, "old")), fp))
        // Running with a stale successor behind it: only the successor is replaced, appended after the running job.
        assertEquals(
            Plan.Enqueue(listOf(1), ExistingWorkPolicy.APPEND_OR_REPLACE),
            DigestScheduler.plan(true, listOf(Job(true, fp), Job(false, "old")), fp),
        )
        // A forced replan (clock jump) also appends rather than replacing the running job.
        assertEquals(Plan.Enqueue(emptyList(), ExistingWorkPolicy.APPEND_OR_REPLACE), DigestScheduler.plan(true, listOf(Job(true, fp)), fp, force = true))
    }

    @Test
    fun `fingerprints change with the time, the weekday and the zone`() {
        val s = DigestScheduler.Settings.of(UserPrefs().copy(dailyDigestEnabled = true, weeklyDigestEnabled = true))
        val berlin = ZoneId.of("Europe/Berlin")
        val a = DigestScheduler.fingerprint(s, DigestKind.WEEKLY, berlin)
        assertNotEquals(a, DigestScheduler.fingerprint(s.copy(weeklyDayOfWeek = 1), DigestKind.WEEKLY, berlin))
        assertNotEquals(a, DigestScheduler.fingerprint(s, DigestKind.WEEKLY, ZoneId.of("Asia/Kolkata")))
        assertNotEquals(
            DigestScheduler.fingerprint(s, DigestKind.DAILY, berlin),
            DigestScheduler.fingerprint(s.copy(dailyMinuteOfDay = 600), DigestKind.DAILY, berlin),
        )
    }
}
