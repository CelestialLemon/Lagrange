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
import com.celestiallemon.lagrange.StartupFailedException
import com.celestiallemon.lagrange.harvest.HarvestTrigger
import kotlinx.coroutines.CancellationException
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

    private val retrier = ReconcileRetrier(
        scope = scope,
        main = Dispatchers.Main,
        isConnected = { connected },
        snapshot = ::snapshotActive,
        clock = System::currentTimeMillis,
        enqueue = { work.trySend(it) },
        reconcile = { active, now, corroborated -> graph.notificationRecorder.reconcile(active, now, corroborated) },
    )

    private val screenOffReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_SCREEN_OFF) return
            val now = System.currentTimeMillis()
            // The listener is alive even when no notification arrives; keep its coverage honest overnight.
            work.trySend { graph.notificationRecorder.heartbeat(now) }
            graph.harvestInBackground(HarvestTrigger.SCREEN_OFF)
        }
    }

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            try {
                graph.awaitReady()
            } catch (e: StartupFailedException) {
                // Recovery failed: nothing may be written this process. Drop queued and future callbacks.
                Log.e(TAG, "startup failed; notification recording is off until the next start", e)
                work.cancel()
                return@launch
            }
            for (job in work) {
                try {
                    job()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "notification write failed", e)
                }
            }
        }
    }

    override fun onListenerConnected() {
        connected = true
        val now = System.currentTimeMillis()
        val active = snapshotActive()
        work.trySend { if (!graph.notificationRecorder.onConnected(active, now)) retrier.corroborateEmpty() }
        if (active == null) retrier.schedule()
        if (!screenOffRegistered) {
            registerReceiver(screenOffReceiver, IntentFilter(Intent.ACTION_SCREEN_OFF), RECEIVER_NOT_EXPORTED)
            screenOffRegistered = true
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        retrier.cancel()
        val now = System.currentTimeMillis()
        work.trySend { graph.notificationRecorder.onDisconnected(now) }
        unregisterScreenOff()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification, rankingMap: RankingMap?) {
        val meta = toMeta(sbn, rankingMap, isDndOrSilent())
        val now = System.currentTimeMillis()
        work.trySend { graph.notificationRecorder.onPosted(meta, now) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap?, reason: Int) {
        val meta = toMeta(sbn, rankingMap, isDndOrSilent())
        val now = System.currentTimeMillis()
        work.trySend { graph.notificationRecorder.onRemoved(meta, reason, now) }
    }

    override fun onDestroy() {
        unregisterScreenOff()
        retrier.cancel()
        if (connected) {
            // Destroyed without a disconnect callback: close the coverage interval cleanly, not at a stale heartbeat.
            connected = false
            val now = System.currentTimeMillis()
            work.trySend { graph.notificationRecorder.onDisconnected(now) }
        }
        // Closing (not cancelling) lets the consumer drain writes that are already queued, then finish.
        work.close()
        super.onDestroy()
    }

    /** What is showing now, or null when the system won't say (never "nothing", which would close every record). */
    private fun snapshotActive(): List<NotificationMeta>? = try {
        val ranking = currentRankingSafe()
        val silent = isDndOrSilent()
        activeNotifications?.map { toMeta(it, ranking, silent) }
    } catch (e: SecurityException) {
        Log.w(TAG, "could not read active notifications", e)
        null
    } catch (e: RuntimeException) {
        Log.w(TAG, "active notifications unavailable", e)
        null
    }

    private fun unregisterScreenOff() {
        if (screenOffRegistered) {
            runCatching { unregisterReceiver(screenOffReceiver) }
            screenOffRegistered = false
        }
    }

    /** Reads only metadata. `sbn.notification.extras` (title, text, people) is never touched. */
    private fun toMeta(sbn: StatusBarNotification, rankingMap: RankingMap?, dndOrSilent: Boolean): NotificationMeta {
        val n = sbn.notification
        val flags = n?.flags ?: 0
        val ranking = Ranking()
        val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
        val channel = if (ranked) ranking.channel else null
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
            dndOrSilent = dndOrSilent,
            parentChannelId = channel?.parentChannelId,
            isConversationChannel = channel?.conversationId != null,
            channelClassKnown = ranked,
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
        val ringer = getSystemService(AudioManager::class.java)?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        return isDndOrSilent(filter, ringer)
    }

    companion object {
        private const val TAG = "LagrangeNotifications"

        /** True while the system has this listener bound and connected in this process. */
        @Volatile
        var connected: Boolean = false
            private set

        /**
         * DND (any interruption filter other than "all") or a silent ringer. Vibrate is not silent: the phone still
         * buzzes, so the notification still interrupts.
         */
        fun isDndOrSilent(interruptionFilter: Int, ringerMode: Int): Boolean {
            val dnd = interruptionFilter != INTERRUPTION_FILTER_ALL && interruptionFilter != INTERRUPTION_FILTER_UNKNOWN
            return dnd || ringerMode == AudioManager.RINGER_MODE_SILENT
        }
    }
}
