package com.celestiallemon.lagrange

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first

/** Thrown by [StartupGate.awaitReady] when startup recovery failed: nothing may open or write data this process. */
class StartupFailedException(message: String) : IllegalStateException(message)

/**
 * The single gate between process start and anything that reads or writes data (Room, workers, collectors, UI).
 *
 * [open] runs the startup steps (reset wipe, restore swap/rollback, database open, reset cutoff). The gate only
 * opens when every step succeeded. If any step fails it stays shut for the rest of the process and the failure is
 * published in [state] so the UI can explain it; recovery is retried on the next start.
 */
class StartupGate {
    sealed interface State {
        data object Starting : State
        data object Ready : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Starting)
    val state: StateFlow<State> = _state

    val isReady: Boolean get() = _state.value == State.Ready

    /** Runs [steps] once; opens the gate only if they all succeed. */
    suspend fun open(steps: suspend () -> Unit) {
        check(_state.value == State.Starting) { "startup already ran" }
        _state.value = try {
            steps()
            State.Ready
        } catch (e: CancellationException) {
            _state.value = State.Failed("Startup was interrupted. Restart Lagrange.")
            throw e
        } catch (e: Exception) {
            State.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Suspends until startup finished. Throws [StartupFailedException] when it failed, so nothing proceeds. */
    suspend fun awaitReady() {
        when (val s = _state.first { it != State.Starting }) {
            State.Ready -> Unit
            is State.Failed -> throw StartupFailedException(s.message)
            State.Starting -> error("unreachable")
        }
    }
}
