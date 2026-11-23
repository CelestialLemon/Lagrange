package com.celestiallemon.lagrange.notifications

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.CoverageRepository
import com.celestiallemon.lagrange.data.Interner
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.RawNotificationEntity
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import java.security.MessageDigest

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
) {
    /** Ongoing, foreground-service and group-summary notifications never count as pickup triggers. */
    val isTriggerEligible: Boolean get() = !isOngoing && !isForegroundService && !isGroupSummary
}

/** Writes notification lifecycles into `raw_notifications`. Calls must be serialized by the caller. */
class NotificationRecorder(
    private val db: LagrangeDatabase,
    private val interner: Interner,
    private val coverage: CoverageRepository,
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
                        channelId = meta.channelId,
                        androidCategory = meta.androidCategory,
                        groupKeyHash = meta.groupKey?.let(::sha256),
                        isOngoing = meta.isOngoing,
                        isForegroundService = meta.isForegroundService,
                        isGroupSummary = meta.isGroupSummary,
                        isOnlyAlertOnce = meta.isOnlyAlertOnce,
                        importance = meta.importance,
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
     * Called from `onListenerConnected`. Opens a coverage interval and reconciles the database against what is
     * actually showing: records whose notification vanished while we were disconnected are closed as
     * `DISCONNECT_RECONCILED` (time uncertain), and notifications posted during the downtime are added.
     */
    suspend fun onConnected(active: List<NotificationMeta>, nowMs: Long) {
        coverage.open(ServiceKind.NOTIFICATION_LISTENER, nowMs)
        val pkgIds = interner.packageIds(active.map { it.packageName })
        val activeByHash = active.associateBy { sha256(it.key) }
        db.withWriteTransaction {
            val open = dao.allOpen()
            val openHashes = HashSet<String>()
            for (row in open) {
                if (row.sbnKeyHash in activeByHash) {
                    openHashes += row.sbnKeyHash
                } else {
                    dao.update(row.copy(removedAtMs = nowMs, removalReason = RemovalReason.DISCONNECT_RECONCILED.name))
                }
            }
            for ((hash, meta) in activeByHash) {
                if (hash !in openHashes) {
                    dao.insert(meta.toEntity(pkgIds.getValue(meta.packageName), hash, postedAtMs = meta.postTimeMs, nowMs = nowMs))
                }
            }
        }
    }

    suspend fun onDisconnected(nowMs: Long) = coverage.close(ServiceKind.NOTIFICATION_LISTENER, nowMs)

    private fun NotificationMeta.toEntity(pkgId: Long, keyHash: String, postedAtMs: Long, nowMs: Long) =
        RawNotificationEntity(
            sbnKeyHash = keyHash,
            pkgId = pkgId,
            channelId = channelId,
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
        fun sha256(value: String): String =
            MessageDigest.getInstance("SHA-256").digest(value.encodeToByteArray()).joinToString("") { "%02x".format(it) }
    }
}
