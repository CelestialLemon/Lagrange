package com.celestiallemon.lagrange.notifications

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.CoverageRepository
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawNotificationEntity
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Metadata of one posted notification. Deliberately has no field for title, text, sub-text, big text, sender or
 * extras, so content can't be stored by accident. Raw keys are hashed before they reach the database.
 */
data class NotificationMeta(
    val key: String,
    val packageName: String,
    val postTimeMs: Long,
    val channelId: String?,
    val androidCategory: String?,
    val groupKey: String?,
    val isOngoing: Boolean,
    val isForegroundService: Boolean,
    val isGroupSummary: Boolean,
    val isOnlyAlertOnce: Boolean,
    val importance: Int,
    val matchedInterruptionFilter: Boolean,
    val isAmbient: Boolean,
    val isConversation: Boolean,
    val dndOrSilent: Boolean,
    /** For a conversation channel, the app-defined channel it was created from (API 30+). */
    val parentChannelId: String? = null,
    /** True when [channelId] is a per-conversation channel, whose id the app may derive from a chat or account. */
    val isConversationChannel: Boolean = false,
) {
    /** Ongoing, foreground-service and group-summary notifications never count as pickup triggers. */
    val isTriggerEligible: Boolean get() = !isOngoing && !isForegroundService && !isGroupSummary
}

/**
 * Writes notification lifecycles into `raw_notifications`. Calls must be serialized by the caller.
 *
 * Conversation channels are created per chat, and apps often build their ids from account or contact identifiers.
 * Such an id is never stored: the parent channel is stored instead, or, without one, a keyed pseudonym that stays
 * stable on this device but can't be reversed or matched across installs.
 */
class NotificationRecorder(
    private val db: LagrangeDatabase,
    private val interner: Interner,
    private val coverage: CoverageRepository,
    private val pseudonymKey: ByteArray,
) {
    private val dao get() = db.rawNotificationDao()

    /** A post or an update. Updates to a notification that is still showing bump its counter instead of adding a row. */
    suspend fun onPosted(meta: NotificationMeta, nowMs: Long) {
        val pkgId = interner.packageId(meta.packageName)
        val keyHash = sha256(meta.key)
        db.withWriteTransaction {
            val open = dao.openByKey(keyHash)
            if (open != null) {
                dao.update(
                    open.copy(
                        lastUpdatedAtMs = nowMs,
                        updateCount = open.updateCount + 1,
                        channelId = storedChannelId(meta),
                        androidCategory = meta.androidCategory,
                        groupKeyHash = meta.groupKey?.let(::sha256),
                        isOngoing = meta.isOngoing,
                        isForegroundService = meta.isForegroundService,
                        isGroupSummary = meta.isGroupSummary,
                        isOnlyAlertOnce = meta.isOnlyAlertOnce,
                        importance = meta.importance,
                        matchedInterruptionFilter = meta.matchedInterruptionFilter,
                        isAmbient = meta.isAmbient,
                        isConversation = meta.isConversation,
                    ),
                )
            } else {
                dao.insert(meta.toEntity(pkgId, keyHash, postedAtMs = meta.postTimeMs, nowMs = nowMs))
            }
        }
        coverage.heartbeat(ServiceKind.NOTIFICATION_LISTENER, nowMs)
    }

    suspend fun onRemoved(meta: NotificationMeta, androidReason: Int, nowMs: Long) {
        val pkgId = interner.packageId(meta.packageName)
        val keyHash = sha256(meta.key)
        val reason = RemovalReason.fromAndroid(androidReason).name
        db.withWriteTransaction {
            val open = dao.openByKey(keyHash)
            if (open != null) {
                dao.update(open.copy(removedAtMs = nowMs, removalReason = reason))
            } else {
                // Posted before the listener was connected: record what we can, closed at once.
                dao.insert(
                    meta.toEntity(pkgId, keyHash, postedAtMs = meta.postTimeMs, nowMs = nowMs)
                        .copy(removedAtMs = nowMs, removalReason = reason),
                )
            }
        }
        coverage.heartbeat(ServiceKind.NOTIFICATION_LISTENER, nowMs)
    }

    /**
     * Called from `onListenerConnected`. Opens a coverage interval and, when the system handed over a snapshot of
     * what is showing ([active] non-null), reconciles against it. A null snapshot (the system refused or failed)
     * leaves every open record alone: the caller retries [reconcile] while connected.
     */
    suspend fun onConnected(active: List<NotificationMeta>?, nowMs: Long) {
        coverage.open(ServiceKind.NOTIFICATION_LISTENER, nowMs)
        if (active != null) reconcile(active, nowMs)
    }

    /**
     * Reconciles the database against what is actually showing: records whose notification vanished while we were
     * disconnected are closed as `DISCONNECT_RECONCILED` (time uncertain), notifications posted during the downtime
     * are added, and a key that is still present but was re-posted during the downtime (its post time is newer
     * than anything we saw) closes the old record as `DISCONNECT_RECONCILED` and opens a new one.
     */
    suspend fun reconcile(active: List<NotificationMeta>, nowMs: Long) {
        val pkgIds = interner.packageIds(active.map { it.packageName })
        val activeByHash = active.associateBy { sha256(it.key) }
        db.withWriteTransaction {
            val stillOpen = HashSet<String>()
            for (row in dao.allOpen()) {
                val meta = activeByHash[row.sbnKeyHash]
                val repostedWhileAway = meta != null && meta.postTimeMs > maxOf(row.postedAtMs, row.lastUpdatedAtMs)
                if (meta != null && !repostedWhileAway) {
                    stillOpen += row.sbnKeyHash
                } else {
                    dao.update(row.copy(removedAtMs = nowMs, removalReason = RemovalReason.DISCONNECT_RECONCILED.name))
                }
            }
            for ((hash, meta) in activeByHash) {
                if (hash !in stillOpen) {
                    dao.insert(meta.toEntity(pkgIds.getValue(meta.packageName), hash, postedAtMs = meta.postTimeMs, nowMs = nowMs))
                }
            }
        }
    }

    suspend fun onDisconnected(nowMs: Long) = coverage.close(ServiceKind.NOTIFICATION_LISTENER, nowMs)

    suspend fun heartbeat(nowMs: Long) = coverage.heartbeat(ServiceKind.NOTIFICATION_LISTENER, nowMs)

    /** The channel id that may be stored: the parent of a conversation channel, else a pseudonym for it. */
    fun storedChannelId(meta: NotificationMeta): String? {
        meta.parentChannelId?.let { return it }
        val channel = meta.channelId ?: return null
        return if (meta.isConversationChannel || meta.isConversation) pseudonym(meta.packageName, channel) else channel
    }

    private fun pseudonym(packageName: String, channelId: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(pseudonymKey, "HmacSHA256"))
        val digest = mac.doFinal("$packageName\u0000$channelId".encodeToByteArray())
        return CONVERSATION_PREFIX + digest.take(12).joinToString("") { "%02x".format(it) }
    }

    private fun NotificationMeta.toEntity(pkgId: Long, keyHash: String, postedAtMs: Long, nowMs: Long) =
        RawNotificationEntity(
            sbnKeyHash = keyHash,
            pkgId = pkgId,
            channelId = storedChannelId(this),
            androidCategory = androidCategory,
            groupKeyHash = groupKey?.let(::sha256),
            postedAtMs = postedAtMs,
            lastUpdatedAtMs = nowMs,
            updateCount = 0,
            importance = importance,
            matchedInterruptionFilter = matchedInterruptionFilter,
            isOngoing = isOngoing,
            isForegroundService = isForegroundService,
            isGroupSummary = isGroupSummary,
            isAmbient = isAmbient,
            isConversation = isConversation,
            isOnlyAlertOnce = isOnlyAlertOnce,
            dndOrSilentAtPost = dndOrSilent,
        )

    companion object {
        const val CONVERSATION_PREFIX = "conversation:"

        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }

        /** The per-install pseudonym key, created on first use. Deleted by Reset Data. */
        fun loadOrCreateKey(file: File): ByteArray {
            if (file.isFile) file.readBytes().takeIf { it.size == KEY_BYTES }?.let { return it }
            val key = ByteArray(KEY_BYTES).also { SecureRandom().nextBytes(it) }
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeBytes(key)
            if (!tmp.renameTo(file)) file.writeBytes(key)
            return key
        }

        private const val KEY_BYTES = 32
    }
}
