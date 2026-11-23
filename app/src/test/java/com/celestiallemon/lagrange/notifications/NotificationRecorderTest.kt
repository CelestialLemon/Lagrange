package com.celestiallemon.lagrange.notifications

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.celestiallemon.lagrange.FakeResolver
import com.celestiallemon.lagrange.data.CoverageRepository
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
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
class NotificationRecorderTest {
    private val db = LagrangeDatabase.inMemory(ApplicationProvider.getApplicationContext())
    private val coverage = CoverageRepository(db)
    private val recorder = NotificationRecorder(db, Interner(db, FakeResolver()), coverage)

    @After
    fun close() = db.close()

    private fun meta(key: String = "0|com.chat|7|tag|10001", flags: Set<String> = emptySet(), post: Long = 1_000) = NotificationMeta(
        key = key,
        packageName = "com.chat",
        postTimeMs = post,
        channelId = "messages",
        androidCategory = "msg",
        groupKey = "g|com.chat",
        isOngoing = "ongoing" in flags,
        isForegroundService = "fgs" in flags,
        isGroupSummary = "summary" in flags,
        isOnlyAlertOnce = false,
        importance = 4,
        matchedInterruptionFilter = true,
        isAmbient = false,
        isConversation = true,
        dndOrSilent = false,
    )

    @Test
    fun `updates to a live notification bump update_count instead of adding rows`() = runBlocking {
        recorder.onPosted(meta(), 1_000)
        recorder.onPosted(meta(), 2_000)
        recorder.onPosted(meta(), 3_000)
        val row = db.rawNotificationDao().all().single()
        assertEquals(2, row.updateCount)
        assertEquals(1_000, row.postedAtMs)
        assertEquals(3_000, row.lastUpdatedAtMs)
        assertEquals(RemovalReason.ACTIVE.name, row.removalReason)
    }

    @Test
    fun `removal reasons map, including bundle dismissal, and a repost opens a new record`() = runBlocking {
        recorder.onPosted(meta(), 1_000)
        recorder.onRemoved(meta(), 1, 2_000)
        recorder.onPosted(meta(), 3_000)
        recorder.onRemoved(meta(), RemovalReason.ANDROID_REASON_BUNDLE_DISMISSED, 4_000)
        recorder.onPosted(meta(key = "k2"), 5_000)
        recorder.onRemoved(meta(key = "k2"), 8, 6_000)
        val rows = db.rawNotificationDao().all()
        assertEquals(listOf("CLICK", "BUNDLE_DISMISSED", "APP_CANCEL"), rows.map { it.removalReason })
        assertEquals(listOf(2_000L, 4_000L, 6_000L), rows.map { it.removedAtMs })
    }

    @Test
    fun `only metadata is stored and keys are hashed`() = runBlocking {
        recorder.onPosted(meta(flags = setOf("ongoing", "fgs", "summary")), 1_000)
        val row = db.rawNotificationDao().all().single()
        assertEquals(64, row.sbnKeyHash.length)
        assertFalse(row.sbnKeyHash.contains("com.chat"))
        assertTrue(row.isOngoing && row.isForegroundService && row.isGroupSummary)
        assertFalse(meta(flags = setOf("fgs")).isTriggerEligible)
        assertTrue(meta().isTriggerEligible)
        val forbidden = setOf("title", "text", "subtext", "bigtext", "sender", "extras", "tickertext")
        val fields = NotificationMeta::class.java.declaredFields.map { it.name.lowercase() }
        assertTrue("content field present: $fields", fields.none { it in forbidden })
    }

    @Test
    fun `connect reconciles missed removals and posts, and tracks coverage`() = runBlocking {
        coverage.open(ServiceKind.NOTIFICATION_LISTENER, 100)
        recorder.onPosted(meta(key = "gone", post = 1_000), 1_000)
        recorder.onPosted(meta(key = "still", post = 1_100), 1_100)
        coverage.heartbeat(ServiceKind.NOTIFICATION_LISTENER, 1_500)
        // Process killed: no disconnect callback. On reconnect the system reports what is showing now.
        recorder.onConnected(listOf(meta(key = "still", post = 1_100), meta(key = "new", post = 3_000)), 5_000)

        val rows = db.rawNotificationDao().all().associateBy { it.postedAtMs }
        assertEquals(RemovalReason.DISCONNECT_RECONCILED.name, rows.getValue(1_000).removalReason)
        assertEquals(5_000L, rows.getValue(1_000).removedAtMs)
        assertNull(rows.getValue(1_100).removedAtMs)
        assertEquals(RemovalReason.ACTIVE.name, rows.getValue(3_000).removalReason)

        val intervals = db.coverageDao().all()
        assertEquals(2, intervals.size)
        assertEquals(1_500L, intervals[0].endMs) // closed at its last heartbeat
        assertNull(intervals[1].endMs)
        assertEquals(CoverageStatus.ACTIVE.name, intervals[1].status)

        recorder.onDisconnected(9_000)
        assertEquals(9_000L, db.coverageDao().all()[1].endMs)
    }

    @Test
    fun `android removal reasons map onto the stored vocabulary`() {
        assertEquals(RemovalReason.CLICK, RemovalReason.fromAndroid(1))
        assertEquals(RemovalReason.CANCEL, RemovalReason.fromAndroid(2))
        assertEquals(RemovalReason.CANCEL_ALL, RemovalReason.fromAndroid(3))
        assertEquals(RemovalReason.APP_CANCEL, RemovalReason.fromAndroid(8))
        assertEquals(RemovalReason.TIMEOUT, RemovalReason.fromAndroid(19))
        assertEquals(RemovalReason.BUNDLE_DISMISSED, RemovalReason.fromAndroid(24))
        assertEquals(RemovalReason.OTHER, RemovalReason.fromAndroid(18))
    }
}
