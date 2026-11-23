package com.celestiallemon.lagrange.notifications

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.util.Collections
import java.util.concurrent.Executors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class)
class ReconcileRetrierTest {
    /** Stands in for the main thread live callbacks arrive on. */
    private val mainExecutor = Executors.newSingleThreadExecutor()
    private val main = mainExecutor.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The ordered write queue: jobs run one at a time, in arrival order. */
    private val queue = Collections.synchronizedList(mutableListOf<suspend () -> Unit>())

    @After
    fun tearDown() {
        scope.cancel()
        mainExecutor.shutdownNow()
    }

    private fun meta(key: String) = NotificationMeta(
        key = key, packageName = "com.chat", postTimeMs = 1, channelId = "c", androidCategory = null, groupKey = null,
        isOngoing = false, isForegroundService = false, isGroupSummary = false, isOnlyAlertOnce = false, importance = 3,
        matchedInterruptionFilter = true, isAmbient = false, isConversation = false, dndOrSilent = false,
    )

    @Test
    fun `a live callback delivered after the retry snapshot is always queued after its reconcile`() = runBlocking {
        val applied = Collections.synchronizedList(mutableListOf<String>())
        val enqueued = CompletableDeferred<Unit>()
        lateinit var retrier: ReconcileRetrier
        retrier = ReconcileRetrier(
            scope = scope,
            main = main,
            isConnected = { true },
            snapshot = {
                // A removal callback lands on the main thread right after the snapshot was taken.
                scope.launch(main) {
                    queue += { applied += "live removal" }
                    enqueued.complete(Unit)
                }
                listOf(meta("a"))
            },
            clock = { 5_000 },
            enqueue = { queue += it },
            reconcile = { _, _, _ -> applied += "reconcile"; true },
            retryDelaysMs = listOf(1),
        )
        withContext(main) { retrier.schedule() }
        withTimeout(5_000) { enqueued.await() }
        queue.toList().forEach { it() }
        assertEquals(listOf("reconcile", "live removal"), applied)
    }

    @Test
    fun `an empty snapshot is retried and only a second empty one is treated as corroborated`() = runBlocking {
        val calls = Collections.synchronizedList(mutableListOf<Pair<Int, Boolean>>())
        val done = CompletableDeferred<Unit>()
        lateinit var retrier: ReconcileRetrier
        retrier = ReconcileRetrier(
            scope = scope,
            main = main,
            isConnected = { true },
            snapshot = { emptyList() },
            clock = { 5_000 },
            // Run each job as soon as it is queued (single consumer, in order).
            enqueue = { job -> scope.launch { job() } },
            reconcile = { active, _, corroborated ->
                calls += active.size to corroborated
                // Records are open: an uncorroborated empty snapshot is set aside.
                (corroborated || active.isNotEmpty()).also { if (it) done.complete(Unit) }
            },
            retryDelaysMs = listOf(1, 1, 1),
            corroborationDelayMs = 1,
        )
        withContext(main) { retrier.schedule() }
        withTimeout(5_000) { done.await() }
        assertEquals(listOf(0 to false, 0 to true), calls.toList())
    }
}
