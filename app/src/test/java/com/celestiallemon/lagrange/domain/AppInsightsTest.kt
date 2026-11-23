package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.data.db.TriggerEvidence.BACKFILL
import com.celestiallemon.lagrange.data.db.TriggerEvidence.LIKELY_APP_CANCEL_CLICK
import com.celestiallemon.lagrange.data.db.TriggerEvidence.NO_OBSERVED_TRIGGER
import com.celestiallemon.lagrange.data.db.TriggerEvidence.OBSERVED_CLICK
import com.celestiallemon.lagrange.data.db.TriggerEvidence.PARTIAL_COVERAGE
import com.celestiallemon.lagrange.data.db.TriggerEvidence.RECENT_POSSIBLE_ALERT
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppInsightsTest {
    private val date = LocalDate.parse("2026-11-20")
    private val app = 10L
    private val other = 11L
    private val third = 12L
    private val launcher = 1L
    private val isTransient: (Long) -> Boolean = { it == launcher }

    private var nextSession = 1L

    private fun seg(pkg: Long, seq: Int, start: Long, durMs: Long, blocked: Boolean = false, ev: TriggerEvidence? = null) =
        SegmentRow(pkg, seq, start, start + durMs, if (blocked) 0 else durMs, blocked, ev)

    /** A session of consecutive segments starting at [start], each `pkg to duration`. */
    private fun session(
        start: Long,
        vararg parts: Pair<Long, Long>,
        trigger: TriggerEvidence = NO_OBSERVED_TRIGGER,
        rating: Int? = null,
        blockedIdx: Set<Int> = emptySet(),
        segEvidence: Map<Int, TriggerEvidence> = emptyMap(),
    ): SessionRow {
        var t = start
        val segs = parts.mapIndexed { i, (pkg, dur) ->
            seg(pkg, i, t, dur, blocked = i in blockedIdx, ev = segEvidence[i]).also { t += dur }
        }
        val entry = segs.firstOrNull { !it.isBlockedAttempt && !isTransient(it.pkgId) }?.pkgId
        return SessionRow(nextSession++, date, start, t, segs.sumOf { it.durationMs }, trigger, entry, rating, segs)
    }

    private fun analyze(sessions: List<SessionRow>, friction: List<FrictionRow> = emptyList()) =
        AppSessionAnalyzer.analyze(app, sessions, friction, isTransient)

    // -- Session shape -----------------------------------------------------------------------------------------------

    @Test
    fun `length buckets have the spec boundaries`() {
        assertEquals(LengthBucket.UNDER_1M, LengthBucket.of(0))
        assertEquals(LengthBucket.UNDER_1M, LengthBucket.of(MINUTE_MS - 1))
        assertEquals(LengthBucket.M1_5, LengthBucket.of(MINUTE_MS))
        assertEquals(LengthBucket.M5_15, LengthBucket.of(5 * MINUTE_MS))
        assertEquals(LengthBucket.M15_30, LengthBucket.of(15 * MINUTE_MS))
        assertEquals(LengthBucket.OVER_30M, LengthBucket.of(30 * MINUTE_MS))
        assertEquals(LengthBucket.OVER_30M, LengthBucket.of(10 * HOUR_MS))
    }

    @Test
    fun `histogram counts real runs and never blocked attempts or system surfaces`() {
        val sessions = listOf(
            session(0, app to 30_000L),
            session(HOUR_MS, app to 3 * MINUTE_MS, launcher to 20_000L, app to 40 * MINUTE_MS),
            // A launch-pause close: the app appears but was never used.
            session(3 * HOUR_MS, app to 8_000L, blockedIdx = setOf(0)),
            session(4 * HOUR_MS, other to 10 * MINUTE_MS),
        )
        val shape = analyze(sessions).shape
        assertEquals(3, shape.runs)
        assertEquals(1, shape.counts[LengthBucket.UNDER_1M])
        assertEquals(1, shape.counts[LengthBucket.M1_5])
        assertEquals(0, shape.counts[LengthBucket.M5_15])
        assertEquals(1, shape.counts[LengthBucket.OVER_30M])
        assertEquals(3 * MINUTE_MS, shape.medianRunMs)
    }

    @Test
    fun `reopening within 60 seconds is a loop, across pickups too`() {
        val a = session(0, app to MINUTE_MS) // ends at 60s
        val b = session(MINUTE_MS + 30_000, app to MINUTE_MS) // 30s after: loop; ends at 150s
        val c = session(150_000 + 61_000, app to MINUTE_MS) // 61s after: not a loop
        val d = session(10 * MINUTE_MS, app to MINUTE_MS, other to 20_000L, app to MINUTE_MS) // 20s via another app: loop
        val shape = analyze(listOf(c, a, d, b)).shape
        assertEquals(5, shape.runs)
        assertEquals(2, shape.reopenLoops)
        assertEquals(0.4, shape.loopRate!!, 1e-9)
    }

    @Test
    fun `blocked attempts are not loops`() {
        // Leave the app, try to reopen 10s later and close at the pause, then nothing.
        val a = session(0, app to MINUTE_MS)
        val blocked = session(MINUTE_MS + 10_000, app to 5_000L, blockedIdx = setOf(0))
        val shape = analyze(listOf(a, blocked)).shape
        assertEquals(1, shape.runs)
        assertEquals(0, shape.reopenLoops)
        assertNull(analyze(emptyList()).shape.loopRate)
    }

    // -- Sequence flows ----------------------------------------------------------------------------------------------

    @Test
    fun `inflow names the app before, a notification start or an idle start`() {
        val sessions = listOf(
            session(0, app to MINUTE_MS, trigger = OBSERVED_CLICK),
            session(HOUR_MS, app to MINUTE_MS, trigger = LIKELY_APP_CANCEL_CLICK),
            session(2 * HOUR_MS, app to MINUTE_MS, trigger = RECENT_POSSIBLE_ALERT),
            session(3 * HOUR_MS, app to MINUTE_MS),
            session(4 * HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = OBSERVED_CLICK),
            // A notification that opened another app does not make this app's later run notification-led.
            session(5 * HOUR_MS, other to MINUTE_MS, launcher to 15_000L, app to MINUTE_MS, trigger = OBSERVED_CLICK),
        )
        val flows = analyze(sessions).flows
        assertEquals(6, flows.runs)
        val inflow = flows.inflow.associate { it.node to it.count }
        assertEquals(2, inflow[FlowNode.NotificationStart])
        assertEquals(2, inflow[FlowNode.SessionStart])
        assertEquals(2, inflow[FlowNode.App(other)])
        assertEquals(1.0, flows.inflow.sumOf { it.share }, 1e-9)
        // Ties rank in a stable order: notification start, then idle start, then apps.
        assertEquals(listOf(FlowNode.NotificationStart, FlowNode.SessionStart, FlowNode.App(other)), flows.inflow.map { it.node })
    }

    @Test
    fun `outflow names the next real app, the same app again or the end of the session`() {
        val sessions = listOf(
            session(0, app to MINUTE_MS, other to MINUTE_MS),
            session(HOUR_MS, app to MINUTE_MS, launcher to 20_000L, third to MINUTE_MS),
            session(2 * HOUR_MS, app to MINUTE_MS),
            // A blocked attempt after the run is skipped: the session really ended there.
            session(3 * HOUR_MS, app to MINUTE_MS, other to 3_000L, blockedIdx = setOf(1)),
        )
        val outflow = analyze(sessions).flows.outflow.associate { it.node to it.count }
        assertEquals(1, outflow[FlowNode.App(other)])
        assertEquals(1, outflow[FlowNode.App(third)])
        assertEquals(2, outflow[FlowNode.SessionEnd])
        assertNull(outflow[FlowNode.App(launcher)])
    }

    @Test
    fun `a micro-lock reopen of the same app flows from and to the same app`() {
        val s = session(0, app to MINUTE_MS, launcher to 12_000L, app to MINUTE_MS)
        val flows = analyze(listOf(s)).flows
        assertEquals(2, flows.runs)
        assertEquals(1, flows.inflow.first { it.node == FlowNode.SameApp }.count)
        assertEquals(1, flows.outflow.first { it.node == FlowNode.SameApp }.count)
    }

    @Test
    fun `flows keep only the top entries`() {
        val sessions = (0 until 8).map { i -> session(i * HOUR_MS, (100L + i) to MINUTE_MS, app to MINUTE_MS) }
        val flows = AppSessionAnalyzer.analyze(app, sessions, emptyList(), isTransient, AppInsightRules(topFlows = 3)).flows
        assertEquals(3, flows.inflow.size)
        assertEquals(8, flows.runs)
    }

    // -- Trigger evidence --------------------------------------------------------------------------------------------

    @Test
    fun `episode starts carry the session trigger and switches carry their own`() {
        val sessions = listOf(
            session(0, app to MINUTE_MS, trigger = OBSERVED_CLICK),
            // Entry after a launcher stop still counts as the episode start.
            session(HOUR_MS, launcher to 15_000L, app to MINUTE_MS, trigger = RECENT_POSSIBLE_ALERT),
            // A shade tap mid-session.
            session(2 * HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = NO_OBSERVED_TRIGGER, segEvidence = mapOf(1 to OBSERVED_CLICK)),
            // A switch without evidence while the listener was recording.
            session(3 * HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = OBSERVED_CLICK),
            // A switch without evidence while it wasn't.
            session(4 * HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = PARTIAL_COVERAGE),
            session(5 * HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = BACKFILL),
        )
        val t = analyze(sessions).triggers
        assertEquals(mapOf(OBSERVED_CLICK to 1, RECENT_POSSIBLE_ALERT to 1), t.episodeStarts)
        assertEquals(mapOf(OBSERVED_CLICK to 1, NO_OBSERVED_TRIGGER to 1, PARTIAL_COVERAGE to 1, BACKFILL to 1), t.midSession)
        assertEquals(6, t.total)
        assertEquals(4, t.observable)
        assertEquals(2, t.combined[OBSERVED_CLICK])
    }

    @Test
    fun `blocked attempts and same-app continuations are not arrivals`() {
        val sessions = listOf(
            // A blocked attempt first: the real entry is the next app; the target's real run later is a switch.
            session(0, app to 4_000L, other to MINUTE_MS, app to MINUTE_MS, blockedIdx = setOf(0), trigger = OBSERVED_CLICK),
            // Back-to-back segments of the same app (a split) are one arrival.
            session(HOUR_MS, app to MINUTE_MS, app to MINUTE_MS, trigger = NO_OBSERVED_TRIGGER),
        )
        val t = analyze(sessions).triggers
        assertEquals(mapOf(NO_OBSERVED_TRIGGER to 1), t.episodeStarts)
        assertEquals(mapOf(NO_OBSERVED_TRIGGER to 1), t.midSession)
        assertEquals(2, t.total)
    }

    @Test
    fun `coming back through the launcher is one start plus one mid-session arrival`() {
        // The same app straight after a launcher stop: like the reconstructor, the return is a switch (its predecessor
        // segment is another package) and carries switch evidence, here none.
        val s = session(0, app to MINUTE_MS, launcher to 12_000L, app to MINUTE_MS, trigger = OBSERVED_CLICK)
        val t = analyze(listOf(s)).triggers
        assertEquals(mapOf(OBSERVED_CLICK to 1), t.episodeStarts)
        assertEquals(mapOf(NO_OBSERVED_TRIGGER to 1), t.midSession)
    }

    // -- Value -------------------------------------------------------------------------------------------------------

    @Test
    fun `value averages ratings of episodes the app really took part in`() {
        val sessions = listOf(
            session(0, app to MINUTE_MS, rating = 5),
            session(HOUR_MS, app to MINUTE_MS, other to MINUTE_MS, rating = 2),
            session(2 * HOUR_MS, app to MINUTE_MS, rating = null),
            session(3 * HOUR_MS, app to MINUTE_MS, rating = 7), // never trusted
            session(4 * HOUR_MS, app to 3_000L, other to MINUTE_MS, rating = 1, blockedIdx = setOf(0)), // blocked only
            session(5 * HOUR_MS, other to MINUTE_MS, rating = 5),
        )
        val v = analyze(sessions).value
        assertEquals(4, v.episodes)
        assertEquals(2, v.rated)
        assertEquals(3.5, v.averageRating!!, 1e-9)
        assertEquals(1, v.worth.valuedCount)
        assertEquals(1, v.worth.regrettedCount)
        assertEquals(2, v.worth.unratedCount)
        assertEquals(2 * MINUTE_MS, v.worth.regrettedMs)
    }

    // -- Friction ----------------------------------------------------------------------------------------------------

    @Test
    fun `friction is empty until launch pauses or timers exist`() {
        val f = analyze(listOf(session(0, app to MINUTE_MS))).friction
        assertFalse(f.hasData)
        assertNull(f.exitRate)
        assertNull(f.quickReopenRate)
        assertNull(f.extensionRate)
    }

    @Test
    fun `immediate exits and quick reopens within ten minutes`() {
        val t0 = 10 * HOUR_MS
        val events = listOf(
            FrictionRow(t0, "LAUNCH_PAUSE", "CLOSED_GO_HOME"),
            FrictionRow(t0 + HOUR_MS, "LAUNCH_PAUSE", "REDIRECTED"),
            FrictionRow(t0 + HOUR_MS + 9 * MINUTE_MS, "LAUNCH_PAUSE", "CONTINUED"), // reopen of the second exit
            FrictionRow(t0 + 3 * HOUR_MS, "LAUNCH_PAUSE", "CLOSED_SYSTEM_NAV"),
            FrictionRow(t0 + 5 * HOUR_MS, AppSessionAnalyzer.TIMER_EXPIRED, AppSessionAnalyzer.EXTENDED_5M),
            FrictionRow(t0 + 6 * HOUR_MS, AppSessionAnalyzer.TIMER_EXPIRED, "CLOSED_GO_HOME"),
        )
        // A real run 5 minutes after the first exit; the third exit's next open is 11 minutes later.
        val runs = listOf(t0 + 5 * MINUTE_MS, t0 + 3 * HOUR_MS + 11 * MINUTE_MS)
        val f = AppSessionAnalyzer.friction(events, runs)
        assertTrue(f.hasData)
        assertEquals(4, f.launchPauses)
        assertEquals(3, f.immediateExits)
        assertEquals(2, f.quickReopens)
        assertEquals(0.75, f.exitRate!!, 1e-9)
        assertEquals(2.0 / 3, f.quickReopenRate!!, 1e-9)
        assertEquals(2, f.timerCheckIns)
        assertEquals(1, f.extensions)
        assertEquals(0.5, f.extensionRate!!, 1e-9)
    }

    // -- Time-of-day profile -----------------------------------------------------------------------------------------

    @Test
    fun `profile starts at the rollover hour and averages over covered days`() {
        val p = TimeOfDayProfile.build(
            rolloverHour = 4,
            slots = listOf(SlotTotal(0, 20 * MINUTE_MS, 2), SlotTotal(18, 60 * MINUTE_MS, 6), SlotTotal(25, 999, 9)),
            repeated = emptyList(),
            repeatedSlotOf = { null },
            coveredDays = 2,
        )
        assertEquals(24, p.bins.size)
        assertEquals(4, p.bins[0].wallHour)
        assertEquals(3, p.bins[23].wallHour)
        assertEquals(22, p.bins[18].wallHour)
        assertEquals(10.0 * MINUTE_MS, p.bins[0].avgMs, 1e-9)
        assertEquals(3.0, p.bins[18].avgOpens, 1e-9)
        assertEquals(18, p.peak!!.slot)
        assertFalse(p.isEmpty)
    }

    @Test
    fun `the repeated fall-back hour folds into the wall hour it repeats`() {
        val fallBack = LocalDate.parse("2026-11-01")
        val p = TimeOfDayProfile.build(
            rolloverHour = 4,
            slots = listOf(SlotTotal(21, 30 * MINUTE_MS, 1)), // 01:00 first occurrence
            repeated = listOf(RepeatedHourCell(fallBack, 30 * MINUTE_MS, 2), RepeatedHourCell(date, 5 * MINUTE_MS, 1)),
            repeatedSlotOf = { if (it == fallBack) 21 else null },
            coveredDays = 1,
        )
        assertEquals(1, p.bins[21].wallHour)
        assertEquals(60.0 * MINUTE_MS, p.bins[21].avgMs, 1e-9)
        assertEquals(3.0, p.bins[21].avgOpens, 1e-9)
        assertEquals(5 * MINUTE_MS, p.unplacedRepeatedMs)
        assertEquals(24, p.bins.size)
    }

    @Test
    fun `the repeated slot of a real fall-back day is the wall hour that repeats`() {
        // 2026-11-01 01:00–02:00 repeats in New York; with a 04:00 rollover it belongs to logical 2026-10-31, slot 21.
        val window = ZoneTimeline.fixed(java.time.ZoneId.of("America/New_York")).window(LocalDate.parse("2026-10-31"), 4)
        assertEquals(21, HourSlots.repeatedSlot(window, 4))
        val p = TimeOfDayProfile.build(4, emptyList(), listOf(RepeatedHourCell(window.date, MINUTE_MS, 1)), { HourSlots.repeatedSlot(window, 4) }, 1)
        assertEquals(1, p.bins.first { it.avgMs > 0 }.wallHour)
    }

    @Test
    fun `a profile without covered days is empty, not zero-divided`() {
        val p = TimeOfDayProfile.build(4, listOf(SlotTotal(3, MINUTE_MS, 1)), emptyList(), { null }, 0)
        assertTrue(p.isEmpty)
        assertNull(p.peak)
        assertEquals(0, p.coveredDays)
    }

    // -- Apps breakdown ----------------------------------------------------------------------------------------------

    private fun meta(id: Long, name: String, cat: AppCategory = AppCategory.DISTRACTING, goal: Long? = null, system: Boolean = false) =
        AppMeta(id, "pkg.$name", name, cat, goal, system)

    @Test
    fun `app averages divide by every covered day and trends need both sides covered`() {
        val metas = mapOf(1L to meta(1, "Reddit", goal = 30 * MINUTE_MS), 2L to meta(2, "Maps", AppCategory.UTILITY), 3L to meta(3, "Launcher", system = true), 4L to meta(4, "New"))
        val rows = AppsBreakdown.rows(
            current = listOf(
                AppWindowTotals(1, totalMs = 70 * MINUTE_MS, opens = 14, coveredMs = 60 * MINUTE_MS, coveredOpens = 12),
                AppWindowTotals(2, 10 * MINUTE_MS, 2, 10 * MINUTE_MS, 2),
                AppWindowTotals(3, HOUR_MS, 50, HOUR_MS, 50),
                AppWindowTotals(4, 5 * MINUTE_MS, 1, 5 * MINUTE_MS, 1),
                AppWindowTotals(5, 5 * MINUTE_MS, 1, 5 * MINUTE_MS, 1), // unknown package
            ),
            previous = listOf(AppWindowTotals(1, 40 * MINUTE_MS, 8, 30 * MINUTE_MS, 6), AppWindowTotals(2, 0, 0, 0, 0)),
            coveredDays = 4,
            previousCoveredDays = 3,
            meta = metas,
            minCoveredDays = 3,
        ).associateBy { it.meta.pkgId }
        assertEquals(setOf(1L, 2L, 4L), rows.keys)
        val reddit = rows.getValue(1)
        assertEquals(15.0 * MINUTE_MS, reddit.dailyAvgMs!!, 1e-9)
        assertEquals(3.0, reddit.dailyAvgOpens!!, 1e-9)
        assertEquals(10.0 * MINUTE_MS, reddit.previousAvgMs!!, 1e-9)
        assertEquals(0.5, reddit.trendPct!!, 1e-9)
        assertEquals(0.5, reddit.goalFraction!!, 1e-9)
        assertNull(rows.getValue(2).trendPct) // previous average was zero
        assertNull(rows.getValue(4).trendPct)
        assertNull(rows.getValue(2).goalFraction)

        val thin = AppsBreakdown.rows(
            listOf(AppWindowTotals(1, 10, 1, 10, 1)), listOf(AppWindowTotals(1, 10, 1, 10, 1)), 4, 2, metas, minCoveredDays = 3,
        ).single()
        assertNull(thin.trendPct)
        val none = AppsBreakdown.rows(listOf(AppWindowTotals(1, 10, 1, 0, 0)), emptyList(), 0, 0, metas, 3).single()
        assertNull(none.dailyAvgMs)
    }

    @Test
    fun `apps filter by search and category and sort four ways`() {
        fun row(id: Long, name: String, cat: AppCategory, total: Long, opens: Int, trend: Double?) =
            AppRow(meta(id, name, cat), total, opens, null, null, trend, null)
        val rows = listOf(
            row(1, "Reddit", AppCategory.DISTRACTING, 100, 5, 0.2),
            row(2, "Maps", AppCategory.UTILITY, 50, 9, null),
            row(3, "Instagram", AppCategory.DISTRACTING, 300, 2, -0.5),
            row(4, "chrome", AppCategory.NEUTRAL, 50, 1, 0.9),
        )
        assertEquals(listOf(3L, 1L, 4L, 2L), AppsBreakdown.filter(rows, "", null, AppSort.TIME).map { it.meta.pkgId })
        assertEquals(listOf(2L, 1L, 3L, 4L), AppsBreakdown.filter(rows, "", null, AppSort.OPENS).map { it.meta.pkgId })
        assertEquals(listOf(4L, 1L, 3L, 2L), AppsBreakdown.filter(rows, "", null, AppSort.TREND).map { it.meta.pkgId })
        assertEquals(listOf(4L, 3L, 2L, 1L), AppsBreakdown.filter(rows, "", null, AppSort.NAME).map { it.meta.pkgId })
        assertEquals(listOf(3L, 1L), AppsBreakdown.filter(rows, "", AppCategory.DISTRACTING, AppSort.TIME).map { it.meta.pkgId })
        assertEquals(listOf(1L), AppsBreakdown.filter(rows, "  redd ", null, AppSort.TIME).map { it.meta.pkgId })
        assertEquals(listOf(3L), AppsBreakdown.filter(rows, "pkg.insta", null, AppSort.TIME).map { it.meta.pkgId })
        assertTrue(AppsBreakdown.filter(rows, "zzz", null, AppSort.TIME).isEmpty())
    }
}
