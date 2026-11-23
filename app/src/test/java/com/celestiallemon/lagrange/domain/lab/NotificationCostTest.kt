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
    fun `two notifications in one episode each own the stretch they started`() {
        val h = HOUR_MS
        // A tap on an Instagram DM opens the episode; 10 minutes in, a YouTube alert prompts a hop to YouTube.
        val dm = n(1, INSTA, h - 10_000, channel = "dm", removed = h, reason = RemovalReason.CLICK)
        val alert = n(2, YOUTUBE, h + 10 * MIN - 20_000, channel = "uploads")
        // A second Instagram tap near the start: the entry segment mirrors the episode trigger and must not grab it.
        val other = n(3, INSTA, h - 8_000, channel = "promo", removed = h + 2_000, reason = RemovalReason.CLICK)
        val e = episode(h, INSTA to 10, YOUTUBE to 20, TIKTOK to 5, trigger = TriggerEvidence.OBSERVED_CLICK, triggerNotificationId = 1).let { ep ->
            ep.copy(
                segments = ep.segments.mapIndexed { i, sg ->
                    when (i) {
                        0 -> sg.copy(triggerEvidence = TriggerEvidence.OBSERVED_CLICK, triggerPkgId = INSTA)
                        1 -> sg.copy(triggerEvidence = TriggerEvidence.RECENT_POSSIBLE_ALERT, triggerPkgId = YOUTUBE)
                        else -> sg
                    }
                },
            )
        }
        val a = NotificationCostAnalyzer.attribute(listOf(dm, alert, other), listOf(e), testApps).associateBy { it.notificationId }
        assertEquals(setOf(1L, 2L), a.keys)
        assertEquals(10 * MIN, a.getValue(1).downstreamMs)
        assertEquals(25 * MIN, a.getValue(2).downstreamMs)
        // Every distracting minute has exactly one owner.
        assertEquals(e.totalDistractingMs, a.values.sumOf { it.downstreamMs })
    }

    @Test
    fun `a notification credited to one episode's hop is not credited again to the next`() {
        val h = HOUR_MS
        val alert = n(1, YOUTUBE, h - 30_000)
        fun hop(start: Long) = episode(start, KINDLE to 1, YOUTUBE to 5).let { ep ->
            ep.copy(segments = ep.segments.mapIndexed { i, sg -> if (i == 1) sg.copy(triggerEvidence = TriggerEvidence.RECENT_POSSIBLE_ALERT, triggerPkgId = YOUTUBE) else sg })
        }
        // Both hops (at +5 s and +20 s) fall within 60 s after the alert; only the earlier one may take it.
        val first = hop(h - MIN + 5_000)
        val second = hop(h - MIN + 20_000)
        val a = NotificationCostAnalyzer.attribute(listOf(alert), listOf(second, first), testApps)
        assertEquals(1, a.size)
        assertEquals(first.id, a.single().episodeId)
    }

    @Test
    fun `credit goes to the notification the reconstructor saw first, even one posted before the window`() {
        val h = HOUR_MS
        val start = 2 * h + 3 * MIN
        val at = start + 5 * MIN // the hop into Instagram
        val notifs = listOf(
            n(1, INSTA, 2 * h - MIN, removed = at + 2_000, reason = RemovalReason.APP_CANCEL), // before the window, canceled first
            n(2, INSTA, 2 * h + MIN, removed = at + 5_000, reason = RemovalReason.APP_CANCEL),
        )
        val e = episode(start, KINDLE to 5, INSTA to 10).let { ep ->
            ep.copy(segments = ep.segments.mapIndexed { i, s -> if (i == 1) s.copy(triggerEvidence = TriggerEvidence.LIKELY_APP_CANCEL_CLICK) else s })
        }
        val r = NotificationCostAnalyzer.analyze(notifs, listener, listOf(e), 2 * h, 10 * h, 10 * h, testApps)
        val insta = r.rows.single()
        assertEquals(1, insta.volume) // only the one posted in the window is reported…
        assertEquals(0, insta.clicks) // …and it was not the one tapped
        assertEquals(0L, insta.downstreamMs)
        assertEquals(0, insta.attributed)
    }

    @Test
    fun `a session still open counts as the pickup after an alert but earns no minutes yet`() {
        val h = HOUR_MS
        val alertAt = h + 20 * MIN
        val notifs = listOf(n(1, INSTA, alertAt))
        val eps = listOf(
            episode(h, INSTA to 5),
            episode(alertAt + 30_000, INSTA to 10, trigger = TriggerEvidence.RECENT_POSSIBLE_ALERT, triggerNotificationId = 1, inProgress = true),
        )
        val r = NotificationCostAnalyzer.analyze(notifs, listener, eps, 0, 10 * h, alertAt + 10 * MIN + 30_000, testApps)
        assertEquals(1, r.overall.alerts)
        assertEquals(1, r.overall.alertPickups)
        assertEquals(0, r.rows.single().attributed)
        assertEquals(0L, r.rows.single().downstreamMs)
    }

    @Test
    fun `a tap observed later keeps its notification from an earlier alert match`() {
        val h = HOUR_MS
        val start = 3 * h
        val at = start + 5 * MIN // first hop into Instagram (alert evidence)
        val at2 = at + 15 * MIN // second hop into Instagram (observed tap)
        val notifs = listOf(
            n(1, INSTA, at - 30_000, channel = "a"),
            n(2, INSTA, at - 10_000, channel = "b", removed = at2 + 1_000, reason = RemovalReason.CLICK),
        )
        val e = episode(start, KINDLE to 5, INSTA to 10, KINDLE to 5, INSTA to 10).let { ep ->
            ep.copy(
                segments = ep.segments.mapIndexed { i, s ->
                    when (i) {
                        1 -> s.copy(triggerEvidence = TriggerEvidence.RECENT_POSSIBLE_ALERT)
                        3 -> s.copy(triggerEvidence = TriggerEvidence.OBSERVED_CLICK, triggerPkgId = INSTA)
                        else -> s
                    }
                },
            )
        }
        val a = NotificationCostAnalyzer.attribute(notifs, listOf(e), testApps)
        // The tap is direct evidence and is matched first; the alert rule then takes the older notification.
        assertEquals(listOf(1L to at, 2L to at2), a.map { it.notificationId to it.fromMs })
        assertEquals(listOf(10 * MIN, 10 * MIN), a.map { it.downstreamMs })
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
    fun `an alert outcome needs usage observed until the pickup or the whole minute`() {
        val usage = listOf(Span(0, 3 * 30 * MIN + 10_000), Span(3 * 30 * MIN + 40_000, 10 * HOUR_MS))
        val base = NotificationCostAnalyzer.PickupBase.of(regular, listener, 0, 10 * HOUR_MS, NotificationRules(), usage)
        // Pickup at 1:30:00; usage went dark 1:30:10–1:30:40. An alert at 1:29:30 saw its pickup before the gap.
        assertEquals(true, base.alertOutcome(3 * 30 * MIN - 30_000))
        // An alert at 1:53:50 with no pickup for six minutes, and its whole minute observed, is a miss.
        assertEquals(false, base.alertOutcome(3 * 30 * MIN + 25 * MIN - 60_000 - 10_000))
        // Idle time inside the usage gap doesn't count toward exposure.
        val full = NotificationCostAnalyzer.PickupBase.of(regular, listener, 0, 10 * HOUR_MS, NotificationRules())
        assertEquals(full.exposureMs, base.exposureMs)
        val dark = listOf(Span(0, 3 * 30 * MIN + 5 * MIN + 10_000), Span(3 * 30 * MIN + 5 * MIN + 40_000, 10 * HOUR_MS))
        val gapped = NotificationCostAnalyzer.PickupBase.of(regular, listener, 0, 10 * HOUR_MS, NotificationRules(), dark)
        assertEquals(full.exposureMs - 30_000, gapped.exposureMs)
        // An alert just before that gap can't be scored: the minute after it wasn't fully observed and no pickup came first.
        assertNull(gapped.alertOutcome(3 * 30 * MIN + 5 * MIN + 1_000))
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
