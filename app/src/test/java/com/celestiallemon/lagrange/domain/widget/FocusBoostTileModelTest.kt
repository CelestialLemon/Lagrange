package com.celestiallemon.lagrange.domain.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class FocusBoostTileModelTest {
    private val min = 60_000L

    @Test
    fun `subtitle follows boost, snooze, off and setup`() {
        assertEquals(TileModel(active = true, unavailable = false, subtitle = "1h · 60m left"), FocusBoostTileModel.model(true, 60 * min, 0))
        assertEquals("1h · 45m left", FocusBoostTileModel.model(true, 44 * min + 1, 0).subtitle)
        assertEquals("1h · 1m left", FocusBoostTileModel.model(true, 1_000, 0).subtitle)
        assertEquals(TileModel(active = false, unavailable = false, subtitle = "Snoozed"), FocusBoostTileModel.model(true, 0, 30 * min))
        assertEquals(TileModel(active = false, unavailable = false, subtitle = "Off"), FocusBoostTileModel.model(true, 0, 0))
        assertEquals("Set up friction", FocusBoostTileModel.model(false, 30 * min, 0).subtitle)
    }

    @Test
    fun `clicks toggle the boost, a snooze is replaced by it, and no service opens setup`() {
        assertEquals(TileAction.ACTIVATE_BOOST, FocusBoostTileModel.onClick(frictionReady = true, boostRemainingMs = 0))
        assertEquals(TileAction.CANCEL_BOOST, FocusBoostTileModel.onClick(frictionReady = true, boostRemainingMs = 10 * min))
        assertEquals(TileAction.OPEN_SETUP, FocusBoostTileModel.onClick(frictionReady = false, boostRemainingMs = 10 * min))
    }
}
