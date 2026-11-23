package com.celestiallemon.lagrange.ui

import com.celestiallemon.lagrange.domain.MINUTE_MS
import com.celestiallemon.lagrange.domain.MetricUnit
import com.celestiallemon.lagrange.ui.trends.formatMetric
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
    fun `metrics that round to zero carry no sign`() {
        assertEquals("<1m", formatMetric(-10_000.0, MetricUnit.DURATION))
        assertEquals("−5m", formatMetric(-5.0 * MINUTE_MS, MetricUnit.DURATION))
        assertEquals("0", formatMetric(-0.01, MetricUnit.COUNT))
        assertEquals("–", formatMetric(null, MetricUnit.COUNT))
    }
}
