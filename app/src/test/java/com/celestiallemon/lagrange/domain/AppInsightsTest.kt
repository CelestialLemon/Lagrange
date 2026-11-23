package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.data.db.TriggerEvidence.BACKFILL
import com.celestiallemon.lagrange.data.db.TriggerEvidence.LIKELY_APP_CANCEL_CLICK
import com.celestiallemon.lagrange.data.db.TriggerEvidence.NO_OBSERVED_TRIGGER
import com.celestiallemon.lagrange.data.db.TriggerEvidence.OBSERVED_CLICK
import com.celestiallemon.lagrange.data.db.TriggerEvidence.PARTIAL_COVERAGE
import com.celestiallemon.lagrange.data.db.TriggerEvidence.RECENT_POSSIBLE_ALERT
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.ServiceKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    @Test
    fun `reopen loop across the window start is attributed to the first in-window open`() {
        val boundary = 4 * HOUR_MS
        // Leave at 03:59:40, reopen at 04:00:10 (30s gap across the 04:00 rollover).
        val before = session(boundary - 2 * MINUTE_MS, app to (2 * MINUTE_MS - 20_000))
        val after = session(boundary + 10_000, app to MINUTE_MS)
        val window = InsightWindow(boundary, boundary + 24 * HOUR_MS)

        // When both sessions are supplied, the preceding run provides loop context without inflating run count.
        val fromSessions = AppSessionAnalyzer.analyze(app, listOf(before, after), emptyList(), isTransient, window = window).shape
        assertEquals(1, fromSessions.runs)
        assertEquals(1, fromSessions.reopenLoops)

        // When only in-window sessions are loaded, priorRunEndMs provides the same context.
        val fromPrior = AppSessionAnalyzer.analyze(
            app, listOf(after), emptyList(), isTransient, window = window, priorRunEndMs = boundary - 20_000,
        ).shape
        assertEquals(1, fromPrior.runs)
        assertEquals(1, fromPrior.reopenLoops)

        // Over 60s across the boundary is not a loop.
        val tooLate = AppSessionAnalyzer.analyze(
            app, listOf(after), emptyList(), isTransient, window = window, priorRunEndMs = boundary - 51_000,
        ).shape
        assertEquals(0, tooLate.reopenLoops)
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

    @Test
    fun `a switch without evidence is labelled by coverage at its own moment, not the episode's`() {
        val listener = CoverageSpan(ServiceKind.NOTIFICATION_LISTENER, CoverageStatus.ACTIVE, 0, 2 * HOUR_MS)
        val backfill = CoverageSpan(ServiceKind.USAGE_HARVEST, CoverageStatus.BACKFILL, 5 * HOUR_MS, 6 * HOUR_MS)
        val sessions = listOf(
            // The episode started unobserved, but the listener was recording when the switch happened.
            session(HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = PARTIAL_COVERAGE),
            // The episode started observed, but the switch fell outside any coverage.
            session(3 * HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = OBSERVED_CLICK),
            session(5 * HOUR_MS, other to MINUTE_MS, app to MINUTE_MS, trigger = OBSERVED_CLICK),
        )
        val t = AppSessionAnalyzer.analyze(app, sessions, emptyList(), isTransient, switchCoverage = SwitchCoverage.of(listOf(listener, backfill))).triggers
        assertEquals(mapOf(NO_OBSERVED_TRIGGER to 1, PARTIAL_COVERAGE to 1, BACKFILL to 1), t.midSession)
    }

    @Test
    fun `a blocked attempt then a real run is one arrival carrying the attempt's evidence`() {
        val s = session(
            0, other to MINUTE_MS, app to 3_000L, app to MINUTE_MS,
            blockedIdx = setOf(1), segEvidence = mapOf(1 to OBSERVED_CLICK), trigger = NO_OBSERVED_TRIGGER,
        )
        val insights = analyze(listOf(s))
        assertEquals(mapOf(OBSERVED_CLICK to 1), insights.triggers.midSession)
        assertEquals(1, insights.shape.runs)
    }

    @Test
    fun `blocked arrival coverage checks the arrival end, not the later real run's end`() {
        // Blocked attempt lasts 1s at MINUTE_MS..MINUTE_MS+1s; real run lasts 1m after it.
        // Listener was active through MINUTE_MS+1s (covering the arrival's full click-after window capped by arrival.endMs)
        // and dropped right after: the arrival should still read NO_OBSERVED_TRIGGER, not PARTIAL_COVERAGE.
        val s = session(
            0, other to MINUTE_MS, app to 1_000L, app to MINUTE_MS,
            blockedIdx = setOf(1), trigger = NO_OBSERVED_TRIGGER,
        )
        val listener = CoverageSpan(ServiceKind.NOTIFICATION_LISTENER, CoverageStatus.ACTIVE, 0, MINUTE_MS + 1_000L)
        val t = AppSessionAnalyzer.analyze(app, listOf(s), emptyList(), isTransient, switchCoverage = SwitchCoverage.of(listOf(listener))).triggers
        assertEquals(mapOf(NO_OBSERVED_TRIGGER to 1), t.midSession)
    }

    @Test
    fun `runs belong to the window they open in, whatever day their episode started`() {
        val sessions = listOf(
            session(0, app to MINUTE_MS),
            // Started before the window; the app opened inside it.
            session(HOUR_MS - MINUTE_MS, other to 2 * MINUTE_MS, app to MINUTE_MS),
            // Started inside; the app only after the window.
            session(2 * HOUR_MS - MINUTE_MS, other to 2 * MINUTE_MS, app to MINUTE_MS),
        )
        val window = InsightWindow(HOUR_MS, 2 * HOUR_MS)
        val insights = AppSessionAnalyzer.analyze(app, sessions, emptyList(), isTransient, window = window)
        assertEquals(1, insights.shape.runs)
        assertEquals(1, insights.value.episodes)
        assertEquals(1, insights.triggers.total)
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

    @Test
    fun `a reopen at exactly ten minutes is not quick, and a late exit's follow-up past the window counts`() {
        val window = InsightWindow(0, 10 * HOUR_MS, dataEndMs = 10 * HOUR_MS + 20 * MINUTE_MS)
        val late = 10 * HOUR_MS - 2 * MINUTE_MS
        val events = listOf(
            FrictionRow(HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"),
            FrictionRow(late, "LAUNCH_PAUSE", "CLOSED_SYSTEM_NAV"),
            FrictionRow(11 * HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"), // after the window: not counted
        )
        val opens = listOf(HOUR_MS + 10 * MINUTE_MS, late + 5 * MINUTE_MS)
        val f = AppSessionAnalyzer.friction(events, opens, window = window)
        assertEquals(2, f.launchPauses)
        assertEquals(2, f.immediateExits)
        assertEquals(1, f.quickReopens)
        assertEquals(0, f.pendingExits)
        assertEquals(0.5, f.quickReopenRate!!, 1e-9)
    }

    @Test
    fun `an exit whose ten minutes are not collected yet is pending, not a miss`() {
        val window = InsightWindow(0, 10 * HOUR_MS, dataEndMs = 10 * HOUR_MS)
        val events = listOf(
            FrictionRow(HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"),
            FrictionRow(10 * HOUR_MS - 5 * MINUTE_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"),
        )
        val f = AppSessionAnalyzer.friction(events, emptyList(), window = window)
        assertEquals(2, f.immediateExits)
        assertEquals(1, f.pendingExits)
        assertEquals(1, f.resolvedExits)
        assertEquals(0.0, f.quickReopenRate!!, 1e-9)
    }

    @Test
    fun `missing quick reopens need continuous usage coverage to count as resolved`() {
        val window = InsightWindow(0, 10 * HOUR_MS, dataEndMs = 10 * HOUR_MS)
        val coverage = UsageCoverage.of(
            listOf(
                CoverageSpan(ServiceKind.USAGE_HARVEST, CoverageStatus.ACTIVE, 0, 5 * HOUR_MS),
                // A 2-minute harvest gap during the 2nd exit's 10-minute follow-up.
                CoverageSpan(ServiceKind.USAGE_HARVEST, CoverageStatus.GAP, 2 * HOUR_MS + 3 * MINUTE_MS, 2 * HOUR_MS + 5 * MINUTE_MS),
                // Continuous backfill covers the 4th exit.
                CoverageSpan(ServiceKind.USAGE_HARVEST, CoverageStatus.BACKFILL, 6 * HOUR_MS, 7 * HOUR_MS),
            ),
        )
        val events = listOf(
            FrictionRow(HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"), // fully covered -> resolved miss
            FrictionRow(2 * HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"), // gap in follow-up -> pending
            FrictionRow(5 * HOUR_MS - 4 * MINUTE_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"), // uncovered tail -> pending
            FrictionRow(6 * HOUR_MS + 10 * MINUTE_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"), // backfill covered -> resolved miss
            FrictionRow(8 * HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"), // uncovered, but reopen WAS observed -> quick reopen
        )
        val f = AppSessionAnalyzer.friction(events, opens = listOf(8 * HOUR_MS + 2 * MINUTE_MS), window = window, usageCoverage = coverage)
        assertEquals(5, f.immediateExits)
        assertEquals(1, f.quickReopens)
        assertEquals(2, f.pendingExits)
        assertEquals(3, f.resolvedExits)
        assertEquals(1.0 / 3, f.quickReopenRate!!, 1e-9)
    }

    @Test
    fun `stale derived runs past the derived cutoff do not confirm a quick reopen, while logged pauses still do`() {
        val window = InsightWindow(0, 10 * HOUR_MS, dataEndMs = 2 * HOUR_MS + 5 * MINUTE_MS)
        val events = listOf(
            // Exit at 02:00 with only a stale derived run at 02:07 (after dataEndMs 02:05) -> pending, not quick reopen.
            FrictionRow(2 * HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"),
            // Exit at 03:00 followed by a directly logged LAUNCH_PAUSE at 03:04 -> still confirmed quick reopen.
            FrictionRow(3 * HOUR_MS, "LAUNCH_PAUSE", "CLOSED_GO_HOME"),
            FrictionRow(3 * HOUR_MS + 4 * MINUTE_MS, "LAUNCH_PAUSE", "CONTINUED"),
        )
        val staleDerivedRuns = listOf(2 * HOUR_MS + 7 * MINUTE_MS)
        val f = AppSessionAnalyzer.friction(events, opens = staleDerivedRuns, window = window)
        assertEquals(2, f.immediateExits)
        assertEquals(1, f.quickReopens)
        assertEquals(1, f.pendingExits)
        assertEquals(1, f.resolvedExits)
        assertEquals(1.0, f.quickReopenRate!!, 1e-9)
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
    fun `on a travel day the repeated slot follows the zone in force at the fall-back`() {
        // Phoenix (no DST) at the day's start, then New York from 03:00 UTC, before its 06:00 UTC fall-back.
        val switch = Instant.parse("2026-11-01T03:00:00Z").toEpochMilli()
        val zones = ZoneTimeline(listOf(0L to ZoneId.of("America/Phoenix"), switch to ZoneId.of("America/New_York")), ZoneId.of("UTC"))
        val window = zones.window(LocalDate.parse("2026-10-31"), 4)
        assertNull(HourSlots.repeatedSlot(window, 4))
        assertEquals(21, HourSlots.repeatedSlot(window, 4, zones)) // 01:00 repeats, 21 slots after 04:00
    }

    @Test
    fun `landing in a new zone already inside its DST overlap still resolves the repeated slot`() {
        // New York falls back at 06:00 UTC (01:00–02:00 EST = 06:00–07:00 UTC). Switching at 06:30 UTC lands mid-overlap.
        val midOverlap = Instant.parse("2026-11-01T06:30:00Z").toEpochMilli()
        val zones = ZoneTimeline(listOf(0L to ZoneId.of("America/Phoenix"), midOverlap to ZoneId.of("America/New_York")), ZoneId.of("UTC"))
        val window = zones.window(LocalDate.parse("2026-10-31"), 4)
        assertEquals(HourSlots.REPEATED_SLOT, HourSlots.slotOf(midOverlap, 4, zones.zoneAt(midOverlap)))
        assertEquals(21, HourSlots.repeatedSlot(window, 4, zones))
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
    fun `apps with a goal stay listed at zero when unused, system surfaces never`() {
        val meta = mapOf(
            1L to AppMeta(1, "a", "A", AppCategory.DISTRACTING, null),
            2L to AppMeta(2, "b", "B", AppCategory.DISTRACTING, 30 * MINUTE_MS),
            3L to AppMeta(3, "c", "C", AppCategory.NEUTRAL, 30 * MINUTE_MS, isSystemSurface = true),
            4L to AppMeta(4, "d", "D", AppCategory.NEUTRAL, null),
        )
        val rows = AppsBreakdown.rows(listOf(AppWindowTotals(1, MINUTE_MS, 1, MINUTE_MS, 1)), emptyList(), 5, 5, meta, 3)
        assertEquals(listOf(1L, 2L), rows.map { it.meta.pkgId })
        val goal = rows.last()
        assertEquals(0L, goal.totalMs)
        assertEquals(0.0, goal.dailyAvgMs!!, 0.0)
        assertNull(goal.trendPct)

        // Unused this window after prior use over sufficient covered days is a -100% drop.
        val dropped = AppsBreakdown.rows(
            current = listOf(AppWindowTotals(1, MINUTE_MS, 1, MINUTE_MS, 1)),
            previous = listOf(AppWindowTotals(2, 50 * MINUTE_MS, 5, 50 * MINUTE_MS, 5)),
            coveredDays = 5,
            previousCoveredDays = 5,
            meta = meta,
            minCoveredDays = 3,
        ).single { it.meta.pkgId == 2L }
        assertEquals(-1.0, dropped.trendPct!!, 1e-9)

        // Too few covered days keeps the trend null.
        val thin = AppsBreakdown.rows(
            current = emptyList(),
            previous = listOf(AppWindowTotals(2, 50 * MINUTE_MS, 5, 50 * MINUTE_MS, 5)),
            coveredDays = 2,
            previousCoveredDays = 5,
            meta = meta,
            minCoveredDays = 3,
        ).single { it.meta.pkgId == 2L }
        assertNull(thin.trendPct)
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
