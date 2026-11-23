package com.celestiallemon.lagrange.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * Records notification metadata (never content) and, while it keeps the process warm, triggers a usage harvest each
 * time the screen turns off.
 */
class LagrangeNotificationListenerService : NotificationListenerService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Callbacks arrive on the main thread; this queue keeps their database writes in arrival order. */
    private val work = Channel<suspend () -> Unit>(Channel.UNLIMITED)

    private val graph get() = (application as LagrangeApplication).graph
    private var screenOffRegistered = false

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) graph.harvestInBackground(HarvestTrigger.SCREEN_OFF)
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            for (job in work) {
                try {
                    job()
                } catch (e: Exception) {
                    Log.e(TAG, "notification write failed", e)
                }
            }
        }
    }

    override fun onListenerConnected() {
        val now = System.currentTimeMillis()
        val active = try {
            val ranking = currentRankingSafe()
            activeNotifications?.map { toMeta(it, ranking) }.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "could not read active notifications", e)
            emptyList()
        }
        work.trySend { graph.notificationRecorder.onConnected(active, now) }
        if (!screenOffRegistered) {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            screenOffRegistered = true
        }
    }

    override fun onListenerDisconnected() {
        val now = System.currentTimeMillis()
        work.trySend { graph.notificationRecorder.onDisconnected(now) }
        unregisterScreenOff()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val meta = toMeta(sbn, rankingMap)
        val now = System.currentTimeMillis()
        work.trySend { graph.notificationRecorder.onPosted(meta, now) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        val meta = toMeta(sbn, rankingMap)
        val now = System.currentTimeMillis()
        work.trySend { graph.notificationRecorder.onRemoved(meta, reason, now) }
    }

    override fun onDestroy() {
        unregisterScreenOff()
        // Closing (not cancelling) lets the consumer drain writes that are already queued, then finish.
        work.close()
        super.onDestroy()
    }

    private fun unregisterScreenOff() {
        if (screenOffRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffRegistered = false
        }
    }

    /** Reads only metadata. `sbn.notification.extras` (title, text, people) is never touched. */
    private fun toMeta(sbn: StatusBarNotification, rankingMap: RankingMap?): NotificationMeta {
        val n = sbn.notification
        val flags = n?.flags ?: 0
        val ranking = Ranking()
        val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
        return NotificationMeta(
            key = sbn.key,
            packageName = sbn.packageName,
            postTimeMs = sbn.postTime,
            channelId = n?.channelId,
            androidCategory = n?.category,
            groupKey = sbn.groupKey,
            isOngoing = flags and Notification.FLAG_ONGOING_EVENT != 0,
            isForegroundService = flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            isGroupSummary = flags and Notification.FLAG_GROUP_SUMMARY != 0,
            isOnlyAlertOnce = flags and Notification.FLAG_ONLY_ALERT_ONCE != 0,
            importance = if (ranked) ranking.importance else NotificationManager.IMPORTANCE_UNSPECIFIED,
            matchedInterruptionFilter = if (ranked) ranking.matchesInterruptionFilter() else true,
            isAmbient = ranked && ranking.isAmbient,
            isConversation = ranked && ranking.isConversation,
            dndOrSilent = isDndOrSilent(),
        )
    }

    private fun currentRankingSafe(): RankingMap? = try {
        currentRanking
    } catch (_: SecurityException) {
        null
    }

    private fun isDndOrSilent(): Boolean {
        val filter = try {
            currentInterruptionFilter
        } catch (_: SecurityException) {
            INTERRUPTION_FILTER_UNKNOWN
        }
        val dnd = filter != INTERRUPTION_FILTER_ALL && filter != INTERRUPTION_FILTER_UNKNOWN
        val ringer = getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        return dnd || ringer != AudioManager.RINGER_MODE_NORMAL
    }

    private companion object {
        const val TAG = "LagrangeNotifications"
    }
}
