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
 * Quick Settings "Focus Boost": one tap starts the persisted 1-hour boost from the Friction tab (cancelling any
 * snooze), a tap while it runs ends it. Without friction switched on and the accessibility service connected it says
 * "Set up friction" and opens the Friction tab instead. Nothing is read or written before startup recovery finished.
 */
class FocusBoostTileService : TileService() {
    private val graph get() = (application as LagrangeApplication).graph
    private var listening: CoroutineScope? = null

    /** The friction switch as last observed while listening; read on click without suspending. */
    @Volatile private var frictionEnabled = false

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
        val boost = graph.friction.liveStatus.value.focusBoostRemainingMs(SystemClock.elapsedRealtime())
        when (FocusBoostTileModel.onClick(frictionReady(), boost)) {
            TileAction.OPEN_SETUP -> openApp(Destination.FRICTION)
            TileAction.ACTIVATE_BOOST -> runCommand { graph.friction.activateFocusBoost1h() }
            TileAction.CANCEL_BOOST -> runCommand { graph.friction.cancelFocusBoost() }
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(destination.pendingIntent(this))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(destination.intent(this))
        }
    }

    private fun renderUnready(failed: Boolean) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_focus_boost_label)
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile_focus_boost)
        tile.state = Tile.STATE_INACTIVE
        tile.subtitle = if (failed) "Open Lagrange" else "Starting…"
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

        /** Asks System UI to rebind the tile so it re-renders (no-op when the tile isn't added). */
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, FocusBoostTileService::class.java)) }
        }
    }
}
