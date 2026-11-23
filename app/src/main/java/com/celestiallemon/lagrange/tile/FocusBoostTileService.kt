package com.celestiallemon.lagrange.tile

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.graphics.drawable.Icon
import android.os.Build
import android.os.SystemClock
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.celestiallemon.lagrange.Destination
import com.celestiallemon.lagrange.LagrangeApplication
import com.celestiallemon.lagrange.R
import com.celestiallemon.lagrange.StartupGate
import com.celestiallemon.lagrange.domain.widget.FocusBoostTileModel
import com.celestiallemon.lagrange.domain.widget.TileAction
import com.celestiallemon.lagrange.friction.LagrangeAccessibilityService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Quick Settings "Focus Boost 1h" (spec §5.5): a tap starts the persisted 1-hour boost (cancelling any snooze), a tap
 * while it runs switches to a 1-hour snooze, a tap while snoozed ends it (see [FocusBoostTileModel]). Without friction
 * switched on and the accessibility service connected it says "Set up friction" and opens the Friction tab instead.
 * Nothing is read or written before startup recovery finished.
 *
 * This is a standard (passive) tile: System UI binds it and calls [onStartListening] every time the Quick Settings
 * panel opens, which re-renders from the live state, and a 30 s tick keeps "Xm left" current while it stays open.
 * So a boost that changed in the app or expired while the shade was closed is always correct by the time the tile
 * is seen; [requestUpdate] is only a best-effort nudge (a no-op for passive tiles on most builds).
 */
class FocusBoostTileService : TileService() {
    private val graph get() = (application as LagrangeApplication).graph
    private var listening: CoroutineScope? = null

    /** The friction switch as last observed while listening; read on click without suspending. */
    @Volatile private var frictionEnabled = false

    /** True once [frictionEnabled] reflects a real preference emission (not the default). */
    @Volatile private var hydrated = false

    override fun onStartListening() {
        super.onStartListening()
        listening?.cancel()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        listening = scope
        if (graph.startupState.value != StartupGate.State.Ready) renderUnready(failed = false)
        scope.launch {
            try {
                graph.awaitReady()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                renderUnready(failed = true)
                return@launch
            }
            // The tick keeps "1h · Xm left" honest while the shade stays open.
            val ticks = flow {
                while (true) {
                    emit(Unit)
                    delay(TICK_MS)
                }
            }
            combine(
                graph.friction.liveStatus,
                graph.preferences.prefs.map { it.frictionEnabled }.distinctUntilChanged(),
                LagrangeAccessibilityService.readyState,
                ticks,
            ) { _, enabled, _, _ -> enabled }.collect { enabled ->
                frictionEnabled = enabled
                hydrated = true
                render()
            }
        }
    }

    override fun onStopListening() {
        listening?.cancel()
        listening = null
        super.onStopListening()
    }

    override fun onDestroy() {
        listening?.cancel()
        listening = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (isLocked) unlockAndRun { handleClick() } else handleClick()
    }

    private fun handleClick() {
        if (graph.startupState.value != StartupGate.State.Ready) {
            openApp(Destination.TODAY)
            return
        }
        // Normally the tile already observed the friction switch while listening, so the tap is handled right here
        // in the click callback (setup must launch while System UI's click token is valid). Only a tap that beats
        // the first preference emission reads it first, so a stale default "off" can't misroute a boost to setup.
        if (hydrated) {
            act(frictionEnabled)
            return
        }
        graph.applicationScope.launch(Dispatchers.Main.immediate) {
            val enabled = try {
                graph.preferences.current().frictionEnabled
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "friction setting unreadable", e)
                frictionEnabled
            }
            frictionEnabled = enabled
            hydrated = true
            act(enabled)
        }
    }

    private fun act(enabled: Boolean) {
        val live = graph.friction.liveStatus.value
        val elapsed = SystemClock.elapsedRealtime()
        val ready = enabled && LagrangeAccessibilityService.readyState.value
        when (FocusBoostTileModel.onClick(ready, live.focusBoostRemainingMs(elapsed), live.snoozeRemainingMs(elapsed))) {
            TileAction.OPEN_SETUP -> openApp(Destination.FRICTION)
            TileAction.ACTIVATE_BOOST -> runCommand { graph.friction.activateFocusBoost1h() }
            // Snoozing ends the boost in the same engine step (one persisted state change, never both active).
            TileAction.SNOOZE -> runCommand { graph.friction.activateSnooze1h() }
            TileAction.CANCEL_SNOOZE -> runCommand { graph.friction.cancelSnooze() }
        }
    }

    /**
     * Runs on the application scope, so the command completes even if the shade closes. The repository hands it to the
     * live accessibility engine (or persists it offline), and the widget and tile refresh from the new live status.
     */
    private fun runCommand(command: suspend () -> Unit) {
        val graph = graph
        val app = applicationContext
        graph.applicationScope.launch {
            try {
                command()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "focus boost toggle failed", e)
            }
            graph.surfaces.requestRefresh()
            requestUpdate(app)
        }
    }

    private fun frictionReady(): Boolean = frictionEnabled && LagrangeAccessibilityService.readyState.value

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openApp(destination: Destination) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startActivityAndCollapse(destination.pendingIntent(this))
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(destination.intent(this))
            }
        } catch (e: RuntimeException) {
            // The tile was unbound in the meantime (shade closed): nothing to collapse into.
            Log.w(TAG, "could not open $destination from the tile", e)
        }
    }

    private fun renderUnready(failed: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_focus_boost_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_focus_boost)
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = if (failed) "Open Lagrange" else "Starting…"
        tile.stateDescription = tile.subtitle
        tile.updateTile()
    }

    private fun render() {
        val tile = qsTile ?: return
        val elapsed = SystemClock.elapsedRealtime()
        val live = graph.friction.liveStatus.value
        val model = FocusBoostTileModel.model(frictionReady(), live.focusBoostRemainingMs(elapsed), live.snoozeRemainingMs(elapsed))
        tile.label = getString(R.string.tile_focus_boost_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_focus_boost)
        tile.state = if (model.active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.subtitle = model.subtitle
        tile.stateDescription = model.subtitle
        tile.updateTile()
    }

    companion object {
        private const val TAG = "LagrangeTile"
        private const val TICK_MS = 30_000L

        /** Best-effort nudge for System UI to rebind the tile (no-op when it isn't added, or for passive tiles). */
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, FocusBoostTileService::class.java)) }
        }
    }
}
