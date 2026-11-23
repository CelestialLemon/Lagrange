package com.celestiallemon.lagrange.widget

import android.app.Application
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import androidx.room3.withReadTransaction
import com.celestiallemon.lagrange.AppGraph
import com.celestiallemon.lagrange.data.IngestionKeys
import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.domain.widget.WidgetInput
import com.celestiallemon.lagrange.domain.widget.WidgetState
import com.celestiallemon.lagrange.domain.widget.WidgetStateCalculator
import com.celestiallemon.lagrange.friction.LagrangeAccessibilityService
import com.celestiallemon.lagrange.harvest.AndroidUsageEventSource
import com.celestiallemon.lagrange.tile.FocusBoostTileService
import com.celestiallemon.lagrange.ui.formatClock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Keeps the home-screen widget and the Focus Boost tile in step with the data: today's rollup being rebuilt, a
 * harvest landing, the budget or rollover changing, Focus Boost or snooze starting or ending, the accessibility
 * service connecting, and the logical day rolling over. The widget reads one process-wide [state]; nothing is
 * persisted per widget, so a data reset leaves nothing behind.
 */
class SurfaceRefresher(private val app: Application, private val graph: AppGraph) {
    private val _state = MutableStateFlow<WidgetState?>(null)

    /** The latest computed widget state, or null before the first computation in this process. */
    val state: StateFlow<WidgetState?> = _state.asStateFlow()

    private val kicks = Channel<Unit>(Channel.CONFLATED)

    /** Re-renders soon (coalesced). Safe from any thread. */
    fun requestRefresh() {
        kicks.trySend(Unit)
    }

    @Volatile private var computedAtMs = -1L

    /**
     * [requestRefresh] unless the state was computed in the last [maxAgeMs]: for launcher-driven widget sessions,
     * which must not each trigger a refresh that starts the next session.
     */
    fun requestRefreshIfOlderThan(maxAgeMs: Long) {
        if (computedAtMs < 0 || android.os.SystemClock.elapsedRealtime() - computedAtMs > maxAgeMs) requestRefresh()
    }

    /**
     * Recomputes and pushes to the widget and tile now, returning once both were asked to redraw (bounded, and never
     * throwing). Broadcast receivers await this before `finish()`, so a freeze right after can't drop the redraw.
     */
    suspend fun refresh() {
        try {
            withTimeoutOrNull(REFRESH_TIMEOUT_MS) { refreshNow() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "widget refresh failed", e)
        }
    }

    /** The current state, computing it if nothing has been computed yet in this process. */
    suspend fun current(): WidgetState = _state.value ?: compute().also { _state.value = it }

    /** Starts watching; called once from [AppGraph.start] after startup succeeded. */
    fun start() {
        graph.applicationScope.launch {
            val ingestion = graph.database.ingestionStateDao()
            val triggers = combine(
                ingestion.observe(IngestionKeys.DERIVED_LAST_RUN_AT_MS).distinctUntilChanged(),
                ingestion.observe(IngestionKeys.LAST_HARVEST_AT_MS).distinctUntilChanged(),
                graph.preferences.prefs.map { Triple(it.dailyBudgetMs, it.rolloverHour, it.frictionEnabled) }.distinctUntilChanged(),
                graph.friction.liveStatus.map { it.focusBoostUntilElapsedMs to it.snoozeUntilElapsedMs }.distinctUntilChanged(),
                LagrangeAccessibilityService.readyState,
            ) { _, _, _, _, _ -> Unit }
            merge(triggers, kicks.receiveAsFlow()).collectLatest {
                delay(DEBOUNCE_MS)
                while (true) {
                    val next = try {
                        refreshNow()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "widget refresh failed", e)
                        null
                    }
                    // Re-render on its own when a boost/snooze ends or the logical day rolls over, at most every 30 min.
                    val now = System.currentTimeMillis()
                    val wakeAt = listOfNotNull(next?.first?.nextChangeAtMs, next?.second, now + MAX_IDLE_MS).min()
                    delay((wakeAt - now).coerceIn(MIN_WAIT_MS, MAX_IDLE_MS))
                }
            }
        }
    }

    /** Computes, publishes, and pushes to every placed widget and the tile. Returns the state and the next day start. */
    private suspend fun refreshNow(): Pair<WidgetState, Long> {
        val state = compute()
        _state.value = state
        pushToSurfaces()
        val nextDay = withContext(Dispatchers.IO) { graph.derived.windowOf(graph.derived.dateOf(System.currentTimeMillis())).endMs }
        return state to nextDay
    }

    private suspend fun pushToSurfaces() {
        FocusBoostTileService.requestUpdate(app)
        val manager = GlanceAppWidgetManager(app)
        if (manager.getGlanceIds(LagrangeWidget::class.java).isNotEmpty()) LagrangeWidget().updateAll(app)
    }

    private suspend fun compute(): WidgetState = withContext(Dispatchers.IO) {
        computedAtMs = android.os.SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        val nowElapsed = android.os.SystemClock.elapsedRealtime()
        val prefs = graph.preferences.current()
        val live = graph.friction.liveStatus.value
        val hasAccess = AndroidUsageEventSource.hasUsageAccess(app)
        // Rollup, sessions and the derived watermark from one snapshot, so freshness describes what is shown.
        val input = graph.database.withReadTransaction {
            val dao = graph.database.derivedDao()
            val ctx = graph.derived.analyticsContext()
            val today = ctx.zones.dateOf(now, ctx.rolloverHour).toString()
            val rollup = dao.dailyRollup(today)
            val unlocked = if (rollup == null) emptyList() else dao.sessionsFor(today).filter { !it.isGlanceOnly }.sortedBy { it.startMs }
            val last = unlocked.lastOrNull()
            val inProgress = last?.takeIf { it.contextFlags?.contains("IN_PROGRESS") == true }
            val lastEnded = unlocked.lastOrNull { it.contextFlags?.contains("IN_PROGRESS") != true }
            fun wall(untilElapsed: Long?) = untilElapsed?.takeIf { it > nowElapsed }?.let { now + (it - nowElapsed) }
            WidgetInput(
                nowMs = now,
                hasUsageAccess = hasAccess,
                hasRollup = rollup != null,
                distractingMs = rollup?.distractingMs ?: 0,
                budgetMs = prefs.dailyBudgetMs,
                dayCoverage = rollup?.let { r -> DayCoverage.entries.firstOrNull { it.name == r.coverageStatus } },
                dayStatus = rollup?.let { r -> DayStatus.entries.firstOrNull { it.name == r.dayStatus } },
                inProgressStartMs = inProgress?.startMs,
                lastEndedMs = lastEnded?.endMs,
                longestFocusGapMs = rollup?.longestFocusGapMs ?: 0,
                lastHarvestAtMs = graph.database.ingestionStateDao().get(IngestionKeys.LAST_HARVEST_AT_MS),
                dataThroughMs = ctx.derivedThroughMs.takeIf { it != Long.MIN_VALUE },
                frictionEnabled = prefs.frictionEnabled,
                accessibilityConnected = LagrangeAccessibilityService.readyState.value,
                focusBoostUntilMs = wall(live.focusBoostUntilElapsedMs),
                snoozeUntilMs = wall(live.snoozeUntilElapsedMs),
            )
        }
        WidgetStateCalculator.compute(input, clock = { formatClock(it) })
    }

    private companion object {
        const val TAG = "LagrangeWidget"
        const val DEBOUNCE_MS = 500L
        const val MIN_WAIT_MS = 1_000L
        const val MAX_IDLE_MS = 30 * 60_000L
        const val REFRESH_TIMEOUT_MS = 5_000L
    }
}

/**
 * `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`: starting the process runs startup (which re-checks digest scheduling);
 * this also asks for a widget and tile redraw once startup is ready, since boot invalidates boost and snooze.
 */
class BootRefreshReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: android.content.Context, intent: android.content.Intent) {
        val action = intent.action
        if (action != android.content.Intent.ACTION_BOOT_COMPLETED && action != android.content.Intent.ACTION_MY_PACKAGE_REPLACED) return
        val graph = (context.applicationContext as com.celestiallemon.lagrange.LagrangeApplication).graph
        val pending = goAsync()
        graph.applicationScope.launch {
            try {
                graph.awaitStarted()
                graph.surfaces.refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("LagrangeBoot", "boot refresh skipped", e)
            } finally {
                pending.finish()
            }
        }
    }
}
