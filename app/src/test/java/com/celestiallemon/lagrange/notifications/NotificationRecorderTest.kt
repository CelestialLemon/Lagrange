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
    private val recorder = NotificationRecorder(db, Interner(db, FakeResolver()), coverage, ByteArray(32) { it.toByte() })

    @After
    fun close() = db.close()

    private fun meta(
        key: String = "0|com.chat|7|tag|10001",
        flags: Set<String> = emptySet(),
        post: Long = 1_000,
        channel: String? = "messages",
        conversation: Boolean = true,
        parent: String? = null,
        conversationChannel: Boolean = false,
        matched: Boolean = true,
        ambient: Boolean = false,
        ranked: Boolean = true,
    ) = NotificationMeta(
        key = key,
        packageName = "com.chat",
        postTimeMs = post,
        channelId = channel,
        androidCategory = "msg",
        groupKey = "g|com.chat",
        isOngoing = "ongoing" in flags,
        isForegroundService = "fgs" in flags,
        isGroupSummary = "summary" in flags,
        isOnlyAlertOnce = false,
        importance = 4,
        matchedInterruptionFilter = matched,
        isAmbient = ambient,
        isConversation = conversation,
        dndOrSilent = false,
        parentChannelId = parent,
        isConversationChannel = conversationChannel,
        channelClassKnown = ranked,
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

    @Test
    fun `an unavailable snapshot on connect never closes open records`() = runBlocking {
        recorder.onPosted(meta(key = "a", post = 1_000), 1_000)
        recorder.onPosted(meta(key = "b", post = 1_100), 1_100)
        recorder.onConnected(null, 5_000)
        val rows = db.rawNotificationDao().all()
        assertTrue(rows.all { it.removedAtMs == null && it.removalReason == RemovalReason.ACTIVE.name })
        assertEquals(1, db.coverageDao().all().count { it.endMs == null })

        // The retry later gets a real snapshot and reconciles then.
        recorder.reconcile(listOf(meta(key = "b", post = 1_100)), 6_000)
        val after = db.rawNotificationDao().all().associateBy { it.postedAtMs }
        assertEquals(RemovalReason.DISCONNECT_RECONCILED.name, after.getValue(1_000).removalReason)
        assertNull(after.getValue(1_100).removedAtMs)
    }

    @Test
    fun `an empty snapshot closes open records only once a second one corroborates it`() = runBlocking {
        recorder.onPosted(meta(key = "a", post = 1_000), 1_000)
        recorder.onPosted(meta(key = "b", post = 1_100), 1_100)
        // getActiveNotifications() answered [] (possibly a failed binder call): nothing is closed yet.
        assertFalse(recorder.onConnected(emptyList(), 5_000))
        assertTrue(db.rawNotificationDao().all().all { it.removedAtMs == null })
        assertFalse(recorder.reconcile(emptyList(), 5_500))
        assertTrue(db.rawNotificationDao().all().all { it.removedAtMs == null })

        // A second empty snapshot a moment later confirms it.
        assertTrue(recorder.reconcile(emptyList(), 6_500, emptyCorroborated = true))
        assertTrue(db.rawNotificationDao().all().all { it.removalReason == RemovalReason.DISCONNECT_RECONCILED.name })

        // With nothing open, an empty snapshot has nothing to close and needs no corroboration.
        assertTrue(recorder.reconcile(emptyList(), 7_000))
    }

    @Test
    fun `a removal without ranking never stores the raw channel id`() = runBlocking {
        // Never seen before, removed with no ranking: it might be a per-chat conversation channel.
        recorder.onRemoved(meta(key = "unseen", channel = "chat-with-alice", conversation = false, ranked = false), 2, 2_000)
        val stored = db.rawNotificationDao().all().single().channelId!!
        assertTrue(stored, stored.startsWith(NotificationRecorder.UNCLASSIFIED_PREFIX))
        assertFalse(stored.contains("alice"))
        assertEquals(stored, recorder.storedChannelId(meta(channel = "chat-with-alice", conversation = false, ranked = false)))

        // With ranking, an ordinary channel is stored as is; an unranked update keeps it.
        recorder.onPosted(meta(key = "seen", channel = "promos", conversation = false), 3_000)
        recorder.onPosted(meta(key = "seen", channel = "promos", conversation = false, ranked = false), 3_100)
        assertEquals("promos", db.rawNotificationDao().all().single { it.postedAtMs == 1_000L && it.removedAtMs == null }.channelId)
    }

    @Test
    fun `a key re-posted while disconnected closes the old record and opens a new one`() = runBlocking {
        recorder.onPosted(meta(key = "k", post = 1_000), 1_000)
        recorder.onDisconnected(2_000)
        // While disconnected the app cancelled and re-posted the same key at 4_000.
        recorder.onConnected(listOf(meta(key = "k", post = 4_000)), 9_000)
        val rows = db.rawNotificationDao().all().sortedBy { it.id }
        assertEquals(2, rows.size)
        assertEquals(RemovalReason.DISCONNECT_RECONCILED.name, rows[0].removalReason)
        assertEquals(9_000L, rows[0].removedAtMs)
        assertEquals(4_000L, rows[1].postedAtMs)
        assertNull(rows[1].removedAtMs)

        // An unchanged key across a reconnect stays one record.
        recorder.onConnected(listOf(meta(key = "k", post = 4_000)), 12_000)
        assertEquals(2, db.rawNotificationDao().all().size)
    }

    @Test
    fun `conversation channels store the parent channel or a keyed pseudonym, never the raw id`() = runBlocking {
        val raw = "chat_+15551234567"
        recorder.onPosted(meta(key = "p", channel = raw, parent = "messages", conversationChannel = true), 1_000)
        recorder.onPosted(meta(key = "q", channel = raw, conversationChannel = true), 1_100)
        recorder.onPosted(meta(key = "r", channel = "promo", conversation = false), 1_200)
        val rows = db.rawNotificationDao().all().sortedBy { it.id }
        assertEquals("messages", rows[0].channelId)
        val pseudonym = rows[1].channelId!!
        assertTrue(pseudonym.startsWith(NotificationRecorder.CONVERSATION_PREFIX))
        assertFalse(pseudonym.contains("5551234567"))
        assertEquals("promo", rows[2].channelId)
        // Stable per install, different under another install's key.
        assertEquals(pseudonym, recorder.storedChannelId(meta(channel = raw, conversationChannel = true)))
        val other = NotificationRecorder(db, Interner(db, FakeResolver()), coverage, ByteArray(32) { 7 })
        assertTrue(other.storedChannelId(meta(channel = raw, conversationChannel = true)) != pseudonym)
        // An update never writes the raw id either.
        recorder.onPosted(meta(key = "q", channel = raw, conversationChannel = true), 1_300)
        assertEquals(pseudonym, db.rawNotificationDao().all().single { it.id == rows[1].id }.channelId)
    }

    @Test
    fun `an update refreshes interruption-filter match and ambient state`() = runBlocking {
        recorder.onPosted(meta(matched = true, ambient = false), 1_000)
        recorder.onPosted(meta(matched = false, ambient = true), 2_000)
        val row = db.rawNotificationDao().all().single()
        assertFalse(row.matchedInterruptionFilter)
        assertTrue(row.isAmbient)
    }

    @Test
    fun `vibrate is not silent, the silent ringer and DND are`() {
        val all = android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_ALL
        val priority = android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_PRIORITY
        val unknown = android.service.notification.NotificationListenerService.INTERRUPTION_FILTER_UNKNOWN
        assertFalse(LagrangeNotificationListenerService.isDndOrSilent(all, android.media.AudioManager.RINGER_MODE_NORMAL))
        assertFalse(LagrangeNotificationListenerService.isDndOrSilent(all, android.media.AudioManager.RINGER_MODE_VIBRATE))
        assertFalse(LagrangeNotificationListenerService.isDndOrSilent(unknown, android.media.AudioManager.RINGER_MODE_VIBRATE))
        assertTrue(LagrangeNotificationListenerService.isDndOrSilent(all, android.media.AudioManager.RINGER_MODE_SILENT))
        assertTrue(LagrangeNotificationListenerService.isDndOrSilent(priority, android.media.AudioManager.RINGER_MODE_NORMAL))
    }

    @Test
    fun `heartbeats extend only an open listener interval and destroy closes it`() = runBlocking {
        recorder.onConnected(emptyList(), 1_000)
        recorder.heartbeat(50_000)
        assertEquals(50_000L, db.coverageDao().all().single().lastSeenMs)
        recorder.onDisconnected(60_000)
        recorder.heartbeat(70_000)
        val closed = db.coverageDao().all().single()
        assertEquals(60_000L, closed.endMs)
        assertEquals(60_000L, closed.lastSeenMs)
    }

    @Test
    fun `the pseudonym key is created once and reused`() {
        val dir = java.nio.file.Files.createTempDirectory("key").toFile()
        val file = java.io.File(dir, "pseudonym.key")
        val first = NotificationRecorder.loadOrCreateKey(file)
        assertEquals(32, first.size)
        assertTrue(first.contentEquals(NotificationRecorder.loadOrCreateKey(file)))
        dir.deleteRecursively()
    }
}
