package com.celestiallemon.lagrange.harvest

import com.celestiallemon.lagrange.DAY
import com.celestiallemon.lagrange.FakeUsageEventSource
import com.celestiallemon.lagrange.HOUR
import com.celestiallemon.lagrange.data.db.UsageEventTypes
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import androidx.test.ext.junit.runners.AndroidJUnit4
import android.app.Application

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class UsageEventProbeTest {
    private val now = 1_800_000_000_000L

    @Test
    fun `measures retention and notification interruptions without storing anything`() = runBlocking {
        val source = FakeUsageEventSource(
            listOf(
                RawUsageEvent(now - 40 * DAY, 1, "com.too.old", null),
                RawUsageEvent(now - 9 * DAY - 12 * HOUR, 1, "com.a", "A"),
                RawUsageEvent(now - 2 * DAY, UsageEventTypes.NOTIFICATION_INTERRUPTION, "com.chat", null),
                RawUsageEvent(now - 2 * DAY + 1, UsageEventTypes.NOTIFICATION_INTERRUPTION, "com.chat", null),
                RawUsageEvent(now - 1000, 2, "com.a", "A"),
            ),
        )
        val report = UsageEventProbe.run(source, now)!!
        assertEquals(now - 30 * DAY, report.scannedFromMs)
        assertEquals(now, report.scannedToMs)
        assertEquals(4L, report.totalEvents)
        assertEquals(now - 9 * DAY - 12 * HOUR, report.earliestEventMs)
        assertEquals(now - 1000, report.latestEventMs)
        assertEquals(9.5, report.retentionDays(report.scannedToMs)!!, 1e-9)
        assertTrue(report.notificationInterruptionSeen)
        assertEquals(2L, report.notificationInterruptionCount)
        assertTrue("day-sized windows", source.queries.all { (b, e) -> e - b <= DAY })
    }

    @Test
    fun `no access or a locked user returns nothing, an empty history reports no retention`() = runBlocking {
        assertNull(UsageEventProbe.run(FakeUsageEventSource().apply { access = false }, now))
        assertNull(UsageEventProbe.run(FakeUsageEventSource().apply { unlocked = false }, now))
        val empty = UsageEventProbe.run(FakeUsageEventSource(), now)!!
        assertEquals(0L, empty.totalEvents)
        assertNull(empty.retentionDays(now))
        assertFalse(empty.notificationInterruptionSeen)
    }
}
