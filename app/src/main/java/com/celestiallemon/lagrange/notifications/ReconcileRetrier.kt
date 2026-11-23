package com.celestiallemon.lagrange.notifications

import android.util.Log
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Takes another active-notification snapshot when the one from `onListenerConnected` was unusable: missing (the
 * system refused) or empty while records are open (possibly a failed binder call, see
 * [NotificationRecorder.reconcile]).
 *
 * Every attempt checks the connection, snapshots, timestamps and enqueues its reconcile in one turn of [main], the
 * thread live callbacks arrive on. A post or removal delivered after the snapshot is therefore always queued after
 * the reconcile built from it and can't be undone by it.
 *
 * [schedule] and [cancel] must be called on [main].
 */
internal class ReconcileRetrier(
    private val scope: CoroutineScope,
    private val main: CoroutineContext,
    private val isConnected: () -> Boolean,
    private val snapshot: () -> List<NotificationMeta>?,
    private val clock: () -> Long,
    /** Appends to the ordered write queue. */
    private val enqueue: (suspend () -> Unit) -> Unit,
    /** Returns false when an empty snapshot still needs corroborating. */
    private val reconcile: suspend (active: List<NotificationMeta>, nowMs: Long, emptyCorroborated: Boolean) -> Boolean,
    private val retryDelaysMs: List<Long> = RETRY_DELAYS_MS,
    private val corroborationDelayMs: Long = EMPTY_CORROBORATION_MS,
) {
    private var job: Job? = null

    /** [afterEmpty]: the previous snapshot was empty while records were open; an empty one now confirms it. */
    fun schedule(afterEmpty: Boolean = false) {
        job?.cancel()
        job = scope.launch {
            val delays = if (afterEmpty) listOf(corroborationDelayMs) + retryDelaysMs else retryDelaysMs
            for (delayMs in delays) {
                delay(delayMs)
                val done = withContext(main) {
                    if (!isConnected()) return@withContext true
                    val active = snapshot() ?: return@withContext false
                    val now = clock()
                    val corroborated = afterEmpty && active.isEmpty()
                    enqueue {
                        if (!reconcile(active, now, corroborated)) withContext(main) { if (isConnected()) schedule(afterEmpty = true) }
                    }
                    true
                }
                if (done) return@launch
            }
            Log.w(TAG, "active notifications never became available; open records stay as they were")
        }
    }

    /** Called when an empty snapshot was set aside: schedules its corroboration from any thread. */
    suspend fun corroborateEmpty() = withContext(main) { if (isConnected()) schedule(afterEmpty = true) }

    fun cancel() {
        job?.cancel()
    }

    companion object {
        private const val TAG = "LagrangeNotifications"
        val RETRY_DELAYS_MS = listOf(2_000L, 5_000L, 15_000L, 60_000L, 300_000L)

        /** Long enough for a transient binder failure to pass, short enough that real removals close promptly. */
        const val EMPTY_CORROBORATION_MS = 1_500L
    }
}
