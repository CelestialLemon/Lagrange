package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.HOUR_MS
import com.celestiallemon.lagrange.domain.Span
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationCostTest {
    private val listener = listOf(Span(0, 10 * HOUR_MS))

    private fun n(
        id: Long,
        pkg: Long,
        posted: Long,
        channel: String? = "general",
        removed: Long? = null,
        reason: RemovalReason = RemovalReason.ACTIVE,
        ongoing: Boolean = false,
        summary: Boolean = false,
        importance: Int = 4,
    ) = LabNotification(id, pkg, channel, posted, removed, reason, importance = importance, isOngoing = ongoing, isGroupSummary = summary)

    @Test
    fun `volume, clicks and downstream only over listener-covered notifications`() {
        val h1 = HOUR_MS
        val notifs = listOf(
            n(1, INSTA, h1, removed = h1 + 20_000, reason = RemovalReason.CLICK),
            n(2, INSTA, 2 * h1, removed = 2 * h1 + 5_000, reason = RemovalReason.APP_CANCEL), // attributed as a likely tap below
            n(3, INSTA, 3 * h1, removed = 3 * h1 + 5_000, reason = RemovalReason.APP_CANCEL), // no episode: not a tap
            n(4, INSTA, 4 * h1, ongoing = true),
            n(5, INSTA, 4 * h1, summary = true),
            n(6, INSTA, 11 * h1), // listener wasn't recording
            n(7, YOUTUBE, 5 * h1 - 30_000, channel = "conversation:ab12"),
            n(8, INSTA, 6 * h1, channel = "promo"),
        )
        val eps = listOf(
            episode(h1 + 20_000, INSTA to 10, TIKTOK to 5, trigger = TriggerEvidence.OBSERVED_CLICK, triggerNotificationId = 1),
            episode(2 * h1 + 5_000, INSTA to 4, trigger = TriggerEvidence.LIKELY_APP_CANCEL_CLICK, triggerNotificationId = 2),
            // A hop prompted by an alert from the app hopped to (segment-level attribution).
            episode(5 * h1 - 5 * 60_000, KINDLE to 5, YOUTUBE to 20).let { e ->
                e.copy(segments = e.segments.mapIndexed { i, s -> if (i == 1) s.copy(triggerEvidence = TriggerEvidence.RECENT_POSSIBLE_ALERT, triggerPkgId = YOUTUBE) else s })
            },
        )
        val r = NotificationCostAnalyzer.analyze(notifs, listener, eps, 0, 12 * h1, 12 * h1, testApps)
        // 1, 2, 3, 7 and 8: the ongoing one, the group summary and the one posted while the listener was off are left out.
        assertEquals(5, r.totalVolume)
        val insta = r.rows.first { it.pkgId == INSTA }
        assertEquals(4, insta.volume)
        assertEquals(2, insta.clicks)
        assertEquals(0.5, insta.clickRate!!, 1e-9)
        assertEquals(19 * MIN, insta.downstreamMs)
        assertEquals(2, insta.attributed)
        val yt = r.rows.first { it.pkgId == YOUTUBE }
        assertEquals(20 * MIN, yt.downstreamMs)
        assertEquals(NotificationSettingsTarget.App("com.google.android.youtube"), yt.channels.single().target)
        val promo = insta.channels.first { it.channelId == "promo" }
        assertEquals(NotificationSettingsTarget.Channel("com.instagram.android", "promo"), promo.target)
        assertEquals(10 * HOUR_MS, r.coveredMs)
    }

    @Test
    fun `a notification credited twice in one episode counts its minutes once`() {
        val notifs = listOf(n(1, INSTA, 1_000, removed = 2_000, reason = RemovalReason.CLICK))
        val e = episode(2_000, INSTA to 10, TIKTOK to 10, trigger = TriggerEvidence.OBSERVED_CLICK, triggerNotificationId = 1).let { ep ->
            ep.copy(segments = ep.segments.map { it.copy(triggerEvidence = TriggerEvidence.OBSERVED_CLICK, triggerPkgId = INSTA) })
        }
        val a = NotificationCostAnalyzer.attribute(notifs, listOf(e), testApps)
        assertEquals(1, a.size)
        assertEquals(20 * MIN, a.single().downstreamMs)
    }

    @Test
    fun `pseudonymized or missing channel ids fall back to the app's notification settings`() {
        val pkg = "com.example"
        assertEquals(NotificationSettingsTarget.Channel(pkg, "promotions"), ChannelSettings.target(pkg, "promotions"))
        listOf(null, "", "  ", "conversation:9f3a", "conv:9f3a", "channel:77aa01").forEach { id ->
            assertEquals("channel '$id'", NotificationSettingsTarget.App(pkg), ChannelSettings.target(pkg, id))
        }
    }

    // -- Pickup lift ------------------------------------------------------------------------------------------------------

    /** 5-minute episodes every 30 minutes from 0 to 10 h: 19 idle gaps of 25 min. */
    private val regular = (0 until 20).map { episode(it * 30 * MIN, INSTA to 5) }

    @Test
    fun `base rate comes from waking idle time and alerts are compared with it`() {
        val base = NotificationCostAnalyzer.PickupBase.of(regular, listener, 0, 10 * HOUR_MS, NotificationRules())
        assertEquals(19, base.pickups)
        assertEquals(19 * 25 * MIN, base.exposureMs)
        val expected = 1 - exp(-(19.0 / (19 * 25 * MIN)) * 60_000)
        assertEquals(expected, base.baseRate!!, 1e-12)
        // Ten alerts, each 30 s before a pickup: every one was followed by a pickup.
        val hits = (1..10).map { base.alertOutcome(it * 30 * MIN - 30_000) }
        assertTrue(hits.all { it == true })
        val lift = base.lift(10, 10, NotificationRules())
        assertEquals(1.0 / expected, lift.lift!!, 1e-9)
        assertNull(lift.needMoreData)
        // An alert mid-episode or after the data ends says nothing.
        assertNull(base.alertOutcome(2 * MIN))
        assertNull(base.alertOutcome(10 * HOUR_MS - 10_000))
    }

    @Test
    fun `small samples and empty denominators need more data`() {
        val base = NotificationCostAnalyzer.PickupBase.of(regular, listener, 0, 10 * HOUR_MS, NotificationRules())
        val few = base.lift(9, 9, NotificationRules())
        assertNull(few.lift)
        assertNotNull(few.needMoreData)
        // No idle time observed at all: no base rate, never a division by zero.
        val none = NotificationCostAnalyzer.PickupBase.of(emptyList(), listener, 0, 10 * HOUR_MS, NotificationRules())
        assertEquals(0L, none.exposureMs)
        assertNull(none.baseRate)
        assertNull(none.lift(50, 10, NotificationRules()).lift)
        // Listener off: the idle time isn't observed, so it doesn't count.
        val dark = NotificationCostAnalyzer.PickupBase.of(regular, emptyList(), 0, 10 * HOUR_MS, NotificationRules())
        assertEquals(0, dark.pickups)
        assertNull(dark.baseRate)
        // Zero alerts never divides by zero either.
        assertNull(base.lift(0, 0, NotificationRules()).lift)
    }

    @Test
    fun `sleep-length gaps are not waking idle time`() {
        val eps = listOf(episode(0, INSTA to 5), episode(5 * HOUR_MS, INSTA to 5))
        val base = NotificationCostAnalyzer.PickupBase.of(eps, listener, 0, 10 * HOUR_MS, NotificationRules())
        assertEquals(0L, base.exposureMs)
        assertEquals(0, base.pickups)
        assertNull(base.alertOutcome(3 * HOUR_MS))
    }
}
