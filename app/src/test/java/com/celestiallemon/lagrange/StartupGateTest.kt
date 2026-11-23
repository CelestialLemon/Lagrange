package com.celestiallemon.lagrange

import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StartupGateTest {
    @Test
    fun `waiters are held until every startup step succeeded`() = runBlocking {
        val gate = StartupGate()
        val steps = mutableListOf<String>()
        val waiter = async { gate.awaitReady(); steps += "data" }
        yield()
        assertFalse(waiter.isCompleted)
        gate.open {
            steps += "recover"
            steps += "open database"
            steps += "record cutoff"
        }
        waiter.await()
        assertEquals(listOf("recover", "open database", "record cutoff", "data"), steps)
        assertTrue(gate.isReady)
    }

    @Test
    fun `a failed recovery keeps the gate shut and never opens the database`() = runBlocking {
        val gate = StartupGate()
        var databaseOpened = false
        val waiter = async {
            try {
                gate.awaitReady()
                "collector ran"
            } catch (e: StartupFailedException) {
                "gated: ${e.message}"
            }
        }
        val recover: () -> Unit = { throw IllegalStateException("Restore couldn't be rolled back") }
        gate.open {
            recover()
            databaseOpened = true
        }
        assertFalse(databaseOpened)
        assertEquals("gated: Restore couldn't be rolled back", waiter.await())
        assertFalse(gate.isReady)
        assertEquals(StartupGate.State.Failed("Restore couldn't be rolled back"), gate.state.value)
        // Late arrivals (a worker, the notification listener) are refused too.
        try {
            gate.awaitReady()
            fail("must stay gated")
        } catch (_: StartupFailedException) {
        }
    }

    @Test
    fun `failing to persist the reset cutoff keeps collectors gated`() = runBlocking {
        val gate = StartupGate()
        gate.open {
            // recovery and database open succeeded ...
            throw java.io.IOException("disk full while writing RESET_CUTOFF_MS")
        }
        assertTrue(gate.state.value is StartupGate.State.Failed)
        try {
            gate.awaitReady()
            fail("a harvest must never run without the reset cutoff")
        } catch (_: StartupFailedException) {
        }
    }
}
