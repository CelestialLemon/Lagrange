package com.celestiallemon.lagrange.ui

import com.celestiallemon.lagrange.domain.DayQuality
import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.MetricUnit
import com.celestiallemon.lagrange.ui.trends.debounceAfterFirst
import com.celestiallemon.lagrange.ui.trends.formatMetric
import com.celestiallemon.lagrange.ui.trends.label
import com.celestiallemon.lagrange.ui.trends.scanRetaining
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

class FormatTest {
    @Test
    fun `a confirmed zero reads as zero minutes, a sliver as under a minute`() {
        assertEquals("0m", formatDurationShort(0))
        assertEquals("<1m", formatDurationShort(10_000))
        assertEquals("1h 5m", formatDurationShort(65 * MINUTE_MS))
    }

    @Test
    fun `negative sub-minute durations keep their minus sign and zeros carry none`() {
        assertEquals("0m", formatMetric(0.0, MetricUnit.DURATION))
        assertEquals("0m", formatMetric(-0.4, MetricUnit.DURATION))
        assertEquals("−<1m", formatMetric(-1.0, MetricUnit.DURATION))
        assertEquals("−<1m", formatMetric(-10_000.0, MetricUnit.DURATION))
        assertEquals("−<1m", formatMetric(-29_999.0, MetricUnit.DURATION))
        assertEquals("−1m", formatMetric(-30_000.0, MetricUnit.DURATION))
        assertEquals("−5m", formatMetric(-5.0 * MINUTE_MS, MetricUnit.DURATION))
        assertEquals("–", formatMetric(null, MetricUnit.DURATION))
        assertEquals("–", formatMetric(Double.NaN, MetricUnit.DURATION))
    }

    @Test
    fun `counts round to one decimal first, drop trailing zeros and use unicode minus`() {
        assertEquals("0", formatMetric(-0.01, MetricUnit.COUNT))
        assertEquals("0", formatMetric(-0.04, MetricUnit.COUNT))
        assertEquals("−0.1", formatMetric(-0.05, MetricUnit.COUNT))
        assertEquals("2", formatMetric(1.99, MetricUnit.COUNT))
        assertEquals("−2", formatMetric(-1.99, MetricUnit.COUNT))
        assertEquals("3.4", formatMetric(3.4, MetricUnit.COUNT))
        assertEquals("−3.4", formatMetric(-3.4, MetricUnit.COUNT))
        assertEquals("9.9", formatMetric(9.94, MetricUnit.COUNT))
        assertEquals("10", formatMetric(9.96, MetricUnit.COUNT))
        assertEquals("−10", formatMetric(-9.96, MetricUnit.COUNT))
        assertEquals("−12", formatMetric(-12.3, MetricUnit.COUNT))
        assertEquals("–", formatMetric(null, MetricUnit.COUNT))
    }

    @Test
    fun `in progress quality label is not tied to today`() {
        assertEquals("In progress", DayQuality.IN_PROGRESS.label)
    }

    @Test
    fun `scanRetaining keeps previous state across resubscribes without emitting a blank seed`() = runBlocking {
        val upstream = MutableSharedFlow<Int>()
        val retained = upstream.scanRetaining("initial") { acc, v -> "$acc+$v" }

        val first = mutableListOf<String>()
        val job1 = launch { retained.collect { first += it } }
        delay(20)
        upstream.emit(1)
        upstream.emit(2)
        delay(20)
        job1.cancel()
        assertEquals(listOf("initial+1", "initial+1+2"), first)

        // Resubscribing starts from the last retained state, never resetting to "initial".
        val second = mutableListOf<String>()
        val job2 = launch { retained.collect { second += it } }
        delay(20)
        upstream.emit(3)
        delay(20)
        job2.cancel()
        assertEquals(listOf("initial+1+2+3"), second)
    }

    @Test
    fun `debounceAfterFirst emits the first value immediately and debounces subsequent bursts`() = runBlocking {
        val values = flow {
            emit(1)
            delay(30)
            emit(2)
            delay(20)
            emit(3)
        }.debounceAfterFirst(120).toList()
        assertEquals(listOf(1, 3), values)

        // On a live open flow, the first value must arrive immediately without waiting for the 5s debounce window.
        val live = MutableSharedFlow<Int>(replay = 1)
        live.tryEmit(42)
        val firstValue = withTimeout(500) { live.debounceAfterFirst(5_000).first() }
        assertEquals(42, firstValue)
    }
}
