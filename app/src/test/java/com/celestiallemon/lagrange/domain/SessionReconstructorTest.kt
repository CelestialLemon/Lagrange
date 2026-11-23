package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.Archetype
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.data.db.UsageEventTypes as T
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReconstructorTest {

    private fun covered() = Ledger().listener(-3 * HOUR_MS, 6 * HOUR_MS)

    // -- Screen intervals and sanitization -----------------------------------------------------------------------------

    @Test
    fun `a lockscreen-only screen-on is a glance`() {
        val eps = Ledger().glance(0).reconstruct()
        val g = eps.single()
        assertTrue(g.isGlanceOnly)
        assertEquals(Archetype.GLANCE, g.archetype)
        assertEquals(1, g.physicalPickupCount)
        assertEquals("${g.startMs}:g", g.episodeKey)
        assertTrue(g.segments.isEmpty())
    }

    @Test
    fun `an unlocked pickup becomes one focused episode starting at unlock`() {
        val l = covered().session(0, P.INSTAGRAM to 5 * MIN)
        val ep = l.reconstruct().single()
        assertFalse(ep.isGlanceOnly)
        assertEquals(l.at(0), ep.pickupAtMs)
        assertEquals(l.at(SEC), ep.startMs)
        assertEquals(5 * MIN + 2 * SEC, ep.activeDurationMs)
        assertEquals(P.INSTAGRAM, ep.entryPkgId)
        assertEquals(5 * MIN, ep.segments.single().durationMs)
        assertEquals(5 * MIN, ep.totals.distractingMs)
        assertEquals(Archetype.FOCUSED, ep.archetype)
        assertEquals("${l.at(SEC)}:${P.INSTAGRAM}", ep.episodeKey)
    }

    @Test
    fun `multi-window - the most recently resumed activity is foreground and time never exceeds the wall clock`() {
        val l = covered()
        l.screenOn(0).unlock(SEC)
        l.resume(2 * SEC, P.WHATSAPP)
        l.resume(60 * SEC, P.YOUTUBE) // split screen: WhatsApp stays resumed underneath
        l.pause(120 * SEC, P.YOUTUBE)
        l.pause(180 * SEC, P.WHATSAPP)
        l.lock(181 * SEC).screenOff(181 * SEC)
        val ep = l.reconstruct().single()
        assertEquals(listOf(P.WHATSAPP, P.YOUTUBE, P.WHATSAPP), ep.segments.map { it.pkgId })
        assertEquals(listOf(58 * SEC, 60 * SEC, 60 * SEC), ep.segments.map { it.durationMs })
        assertTrue(ep.segments.sumOf { it.durationMs } <= ep.endMs - ep.startMs)
    }

    @Test
    fun `a picture-in-picture pause hands foreground back to the app underneath`() {
        val l = covered()
        l.screenOn(0).unlock(SEC)
        l.resume(2 * SEC, P.CHROME)
        l.resume(30 * SEC, P.YOUTUBE)
        l.pause(90 * SEC, P.YOUTUBE) // YouTube enters PiP: paused, no longer foreground
        l.pause(150 * SEC, P.CHROME)
        l.lock(151 * SEC).screenOff(151 * SEC)
        val ep = l.reconstruct().single()
        assertEquals(listOf(P.CHROME, P.YOUTUBE, P.CHROME), ep.segments.map { it.pkgId })
        assertEquals(60 * SEC, ep.segments.last().durationMs)
    }

    @Test
    fun `screen-off ends an activity that never logged a pause`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).resume(2 * SEC, P.INSTAGRAM).screenOff(60 * SEC)
        val ep = l.reconstruct().single()
        assertEquals(58 * SEC, ep.segments.single().durationMs)
        assertFalse(ep.uncertainTail)
    }

    @Test
    fun `events are sanitized - order in the list does not matter and orphan pauses are ignored`() {
        val l = covered().session(0, P.WHATSAPP to 2 * MIN)
        l.pause(10 * MIN, P.REDDIT) // orphan: no matching resume
        val ordered = SessionReconstructor.reconstruct(l.input())
        val shuffled = SessionReconstructor.reconstruct(l.input().copy(events = l.events.shuffled(java.util.Random(7))))
        assertEquals(ordered, shuffled)
        assertEquals(1, ordered.size)
    }

    @Test
    fun `a missing screen-off is clamped at the tail cap and flagged uncertain`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).use(P.INSTAGRAM, 2 * SEC, 10 * SEC)
        // Nothing for three hours, then a new screen-on.
        l.session(3 * HOUR_MS, P.WHATSAPP to MIN)
        val eps = l.reconstruct().unlocked()
        assertEquals(2, eps.size)
        val first = eps[0]
        assertTrue(first.uncertainTail)
        assertEquals(l.at(10 * SEC + 30 * MIN), first.endMs)
        assertTrue(UNCERTAIN_FLAG in first.contextFlags.orEmpty())
        assertFalse(eps[1].uncertainTail)
    }

    @Test
    fun `device shutdown closes the open interval exactly`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).resume(2 * SEC, P.INSTAGRAM).ev(5 * MIN, T.DEVICE_SHUTDOWN)
        val ep = l.reconstruct(dataEnd = HOUR_MS).single()
        assertEquals(l.at(5 * MIN), ep.endMs)
        assertEquals(5 * MIN - 2 * SEC, ep.segments.single().durationMs)
        assertFalse(ep.uncertainTail)
    }

    @Test
    fun `a startup after a dead battery caps the dangling interval`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).resume(2 * SEC, P.INSTAGRAM).ev(2 * HOUR_MS, T.DEVICE_STARTUP)
        val ep = l.reconstruct().single()
        assertTrue(ep.uncertainTail)
        assertEquals(l.at(2 * SEC + 30 * MIN), ep.endMs)
        assertEquals(30 * MIN, ep.segments.single().durationMs)
    }

    @Test
    fun `an interval still open at the end of the ledger is in progress, uncertain only past the cap`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).resume(2 * SEC, P.WHATSAPP)
        val fresh = l.reconstruct(dataEnd = 5 * MIN).single()
        assertTrue(fresh.inProgress)
        assertFalse(fresh.uncertainTail)
        assertEquals(l.at(5 * MIN), fresh.endMs)
        assertTrue(IN_PROGRESS_FLAG in fresh.contextFlags.orEmpty())
        val stale = l.reconstruct(dataEnd = 2 * HOUR_MS).single()
        assertTrue(stale.inProgress)
        assertTrue(stale.uncertainTail)
        assertEquals(l.at(2 * SEC + 30 * MIN), stale.endMs)
    }

    // -- Merging ---------------------------------------------------------------------------------------------------------

    @Test
    fun `a micro-lock under 15 seconds merges, counts a pickup and excludes the gap`() {
        val l = covered()
        l.session(0, P.INSTAGRAM to MIN) // off at 63 s
        l.session(73 * SEC, P.INSTAGRAM to MIN) // back 10 s later
        val ep = l.reconstruct().single()
        assertEquals(2, ep.physicalPickupCount)
        assertEquals(2, ep.activeSpans.size)
        assertEquals(62 * SEC + 62 * SEC, ep.activeDurationMs)
        assertTrue(ep.activeDurationMs < ep.endMs - ep.startMs)
        assertEquals(2 * MIN, ep.segments.single().effectiveMs)
    }

    @Test
    fun `a 20 second lock is two episodes`() {
        val l = covered()
        l.session(0, P.INSTAGRAM to MIN)
        l.session(83 * SEC, P.INSTAGRAM to MIN)
        assertEquals(2, l.reconstruct().unlocked().size)
    }

    @Test
    fun `a glance inside a micro-lock gap adds a pickup and still counts as a glance with its lit time`() {
        val l = covered()
        l.session(0, P.INSTAGRAM to MIN) // off at 63 s
        l.glance(66 * SEC, 3 * SEC)
        l.session(72 * SEC, P.INSTAGRAM to MIN)
        val eps = l.reconstruct()
        val ep = eps.single { !it.isGlanceOnly }
        assertEquals(3, ep.physicalPickupCount)
        val glance = eps.single { it.isGlanceOnly }
        assertEquals(l.at(66 * SEC), glance.startMs)
        assertEquals(3 * SEC, glance.activeDurationMs)
        // The merged gap is never active time, so the glance's lit span is not counted twice.
        assertTrue(ep.activeSpans.none { it.startMs < glance.endMs && glance.startMs < it.endMs })
    }

    @Test
    fun `a call's proximity screen-off is one episode with one pickup`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).resume(2 * SEC, P.DIALER)
        l.screenOff(60 * SEC) // held to the ear
        l.screenOn(6 * MIN) // no keyguard: the call kept it unlocked
        l.pause(8 * MIN, P.DIALER)
        l.lock(8 * MIN + SEC).screenOff(8 * MIN + SEC)
        val ep = l.reconstruct().single()
        assertEquals(1, ep.physicalPickupCount)
        assertEquals(59 * SEC + 2 * MIN + SEC, ep.activeDurationMs)
        assertEquals(P.DIALER, ep.segments.single().pkgId)
        assertEquals(Archetype.UTILITY, ep.archetype)
    }

    // -- Chain cleaning ----------------------------------------------------------------------------------------------------

    @Test
    fun `a short SystemUI dialog inside one app is joined into a single stretch`() {
        val l = covered().session(0, P.INSTAGRAM to MIN, P.SYSTEMUI to 3 * SEC, P.INSTAGRAM to MIN)
        val seg = l.reconstruct().single().segments.single()
        assertEquals(P.INSTAGRAM, seg.pkgId)
        assertEquals(2 * MIN + 3 * SEC, seg.durationMs)
    }

    @Test
    fun `a launcher hop under 10 seconds collapses, a longer one stays`() {
        val short = covered().session(0, P.WHATSAPP to MIN, P.LAUNCHER to 4 * SEC, P.YOUTUBE to MIN)
        assertEquals(listOf(P.WHATSAPP, P.YOUTUBE), short.reconstruct().single().segments.map { it.pkgId })
        val long = covered().session(0, P.WHATSAPP to MIN, P.LAUNCHER to 30 * SEC, P.YOUTUBE to MIN)
        assertEquals(listOf(P.WHATSAPP, P.LAUNCHER, P.YOUTUBE), long.reconstruct().single().segments.map { it.pkgId })
    }

    @Test
    fun `a permission dialog between the same app joins it`() {
        val l = covered().session(0, P.MAPS to 30 * SEC, P.PERMISSIONS to 5 * SEC, P.MAPS to MIN)
        val ep = l.reconstruct().single()
        assertEquals(listOf(P.MAPS), ep.segments.map { it.pkgId })
        assertEquals(95 * SEC, ep.segments.single().durationMs)
        assertEquals(1, ep.distinctAppCount)
    }

    @Test
    fun `a Chrome Custom Tab is credited to the app that opened it`() {
        val l = covered()
        l.customTabs += P.CCT_CLASS
        l.screenOn(0).unlock(SEC)
        l.use(P.REDDIT, 2 * SEC, 62 * SEC)
        l.use(P.CHROME, 62 * SEC, 122 * SEC, cls = P.CCT_CLASS)
        l.use(P.REDDIT, 122 * SEC, 182 * SEC)
        l.lock(183 * SEC).screenOff(183 * SEC)
        val ep = l.reconstruct().single()
        assertEquals(listOf(P.REDDIT), ep.segments.map { it.pkgId })
        assertEquals(3 * MIN, ep.totals.distractingMs)

        // The same hop to Chrome's normal UI is a real app switch.
        val plain = covered().session(0, P.REDDIT to MIN, P.CHROME to MIN, P.REDDIT to MIN)
        assertEquals(listOf(P.REDDIT, P.CHROME, P.REDDIT), plain.reconstruct().single().segments.map { it.pkgId })
    }

    @Test
    fun `the same app across activity classes is one segment`() {
        val l = covered()
        l.screenOn(0).unlock(SEC)
        l.use(P.INSTAGRAM, 2 * SEC, 30 * SEC, cls = 31)
        l.use(P.INSTAGRAM, 30 * SEC, 90 * SEC, cls = 32)
        l.lock(91 * SEC).screenOff(91 * SEC)
        assertEquals(88 * SEC, l.reconstruct().single().segments.single().durationMs)
    }

    // -- Blocked attempts ------------------------------------------------------------------------------------------------

    @Test
    fun `a launch pause that sent the user home is a blocked attempt with its pause scrubbed`() {
        val l = covered().session(0, P.INSTAGRAM to 8 * SEC).blocked(P.INSTAGRAM, 2 * SEC)
        val ep = l.reconstruct().single()
        val seg = ep.segments.single()
        assertTrue(seg.isBlockedAttempt)
        assertEquals(7_800L, seg.blockedMs) // 5 s pause + 0.8 s reaction + 2 s grace, from the segment start
        assertEquals(200L, seg.effectiveMs)
        assertEquals(10 * SEC - 7_800L, ep.activeDurationMs)
        assertEquals(1, ep.blockedAttemptCount)
        assertEquals(Archetype.BLOCKED_ATTEMPT, ep.archetype)
    }

    @Test
    fun `system navigation and redirects also block, proceeding does not`() {
        for (outcome in listOf("CLOSED_SYSTEM_NAV", "REDIRECTED")) {
            val ep = covered().session(0, P.INSTAGRAM to 8 * SEC).blocked(P.INSTAGRAM, 2 * SEC, outcome = outcome).reconstruct().single()
            assertTrue(outcome, ep.segments.single().isBlockedAttempt)
        }
        val proceeded = covered().session(0, P.INSTAGRAM to 8 * SEC).blocked(P.INSTAGRAM, 2 * SEC, outcome = "PROCEEDED").reconstruct().single()
        assertFalse(proceeded.segments.single().isBlockedAttempt)
        assertEquals(Archetype.MICRO_CHECK, proceeded.archetype)
    }

    @Test
    fun `a redirect to a utility keeps the redirect target as the entry app`() {
        val l = covered().session(0, P.INSTAGRAM to 8 * SEC, P.MAPS to MIN).blocked(P.INSTAGRAM, 2 * SEC, outcome = "REDIRECTED")
        val ep = l.reconstruct().single()
        assertEquals(P.MAPS, ep.entryPkgId)
        assertEquals(1, ep.blockedAttemptCount)
        assertEquals(Archetype.UTILITY, ep.archetype)
    }

    // -- Triggers ----------------------------------------------------------------------------------------------------------

    @Test
    fun `a notification tapped around the pickup is an observed click`() {
        val l = covered()
        val id = l.notification(P.WHATSAPP, postedAt = -2 * MIN, removedAt = 3 * SEC, reason = RemovalReason.CLICK)
        l.session(0, P.WHATSAPP to MIN)
        val ep = l.reconstruct().single()
        assertEquals(TriggerEvidence.OBSERVED_CLICK, ep.triggerEvidence)
        assertEquals(P.WHATSAPP, ep.triggerPkgId)
        assertEquals(id, ep.triggerNotificationId)
        assertEquals(TriggerEvidence.OBSERVED_CLICK, ep.segments.first().triggerEvidence)
    }

    @Test
    fun `a click outside the -5 to +15 second window is not evidence`() {
        val l = covered()
        l.notification(P.WHATSAPP, postedAt = -2 * MIN, removedAt = -10 * SEC, reason = RemovalReason.CLICK)
        l.notification(P.YOUTUBE, postedAt = -2 * MIN, removedAt = 20 * SEC, reason = RemovalReason.CLICK)
        l.session(0, P.WHATSAPP to MIN)
        assertEquals(TriggerEvidence.NO_OBSERVED_TRIGGER, l.reconstruct().single().triggerEvidence)
    }

    @Test
    fun `an app cancelling its own notification right after opening is a likely click`() {
        val l = covered()
        l.notification(P.WHATSAPP, postedAt = -5 * MIN, removedAt = 5 * SEC, reason = RemovalReason.APP_CANCEL)
        l.session(0, P.WHATSAPP to MIN)
        val ep = l.reconstruct().single()
        assertEquals(TriggerEvidence.LIKELY_APP_CANCEL_CLICK, ep.triggerEvidence)
        assertEquals(P.WHATSAPP, ep.triggerPkgId)
    }

    @Test
    fun `an alert in the minute before the pickup is a possible trigger, quiet ones are not`() {
        val loud = covered()
        loud.notification(P.INSTAGRAM, postedAt = -30 * SEC)
        loud.session(0, P.WHATSAPP to MIN)
        val ep = loud.reconstruct().single()
        assertEquals(TriggerEvidence.RECENT_POSSIBLE_ALERT, ep.triggerEvidence)
        assertEquals(P.INSTAGRAM, ep.triggerPkgId)

        for (quiet in listOf(
            covered().also { it.notification(P.INSTAGRAM, postedAt = -30 * SEC, dnd = true) },
            covered().also { it.notification(P.INSTAGRAM, postedAt = -30 * SEC, importance = 2) },
            covered().also { it.notification(P.INSTAGRAM, postedAt = -90 * SEC) },
            covered().also { it.notification(P.INSTAGRAM, postedAt = -30 * SEC, ongoing = true) },
        )) {
            quiet.session(0, P.WHATSAPP to MIN)
            assertEquals(TriggerEvidence.NO_OBSERVED_TRIGGER, quiet.reconstruct().single().triggerEvidence)
        }
    }

    @Test
    fun `an older unread notification from the entry app is pending`() {
        val l = covered()
        val id = l.notification(P.WHATSAPP, postedAt = -10 * MIN)
        l.session(0, P.WHATSAPP to MIN)
        val ep = l.reconstruct().single()
        assertEquals(TriggerEvidence.PENDING_NOTIFICATION, ep.triggerEvidence)
        assertEquals(id, ep.triggerNotificationId)

        // Under 60 seconds old is not "pending" (and a silent one is not an alert either).
        val young = covered()
        young.notification(P.WHATSAPP, postedAt = -30 * SEC, importance = 2)
        young.session(0, P.WHATSAPP to MIN)
        assertEquals(TriggerEvidence.NO_OBSERVED_TRIGGER, young.reconstruct().single().triggerEvidence)
    }

    @Test
    fun `without listener coverage the trigger is unknown, and backfilled history says so`() {
        val partial = Ledger().session(0, P.WHATSAPP to MIN)
        assertEquals(TriggerEvidence.PARTIAL_COVERAGE, partial.reconstruct().single().triggerEvidence)

        val backfill = Ledger().usageCoverage(-HOUR_MS, HOUR_MS, CoverageStatus.BACKFILL).session(0, P.WHATSAPP to MIN)
        assertEquals(TriggerEvidence.BACKFILL, backfill.reconstruct().single().triggerEvidence)

        // Coverage that ends mid-window is still partial.
        val gap = Ledger().listener(-HOUR_MS, 0).session(0, P.WHATSAPP to MIN)
        assertEquals(TriggerEvidence.PARTIAL_COVERAGE, gap.reconstruct().single().triggerEvidence)
    }

    @Test
    fun `a mid-session switch gets its own segment-level trigger`() {
        val l = covered()
        l.notification(P.WHATSAPP, postedAt = 30 * SEC, removedAt = 63 * SEC, reason = RemovalReason.CLICK)
        l.session(0, P.INSTAGRAM to MIN, P.WHATSAPP to MIN)
        val ep = l.reconstruct().single()
        assertEquals(TriggerEvidence.NO_OBSERVED_TRIGGER, ep.triggerEvidence)
        assertEquals(TriggerEvidence.OBSERVED_CLICK, ep.segments[1].triggerEvidence)
        assertEquals(P.WHATSAPP, ep.segments[1].triggerPkgId)
    }

    @Test
    fun `one notification is never credited twice`() {
        val l = covered()
        l.notification(P.WHATSAPP, postedAt = -MIN, removedAt = 12 * SEC, reason = RemovalReason.CLICK)
        // The tap at 12 s lies in the episode's window and in the window of the switch back to WhatsApp at 12 s.
        l.session(0, P.WHATSAPP to 5 * SEC, P.INSTAGRAM to 5 * SEC, P.WHATSAPP to MIN)
        val ep = l.reconstruct().unlocked().single()
        val credits = listOf(ep.triggerEvidence) + ep.segments.drop(1).map { it.triggerEvidence }
        assertEquals(1, credits.count { it == TriggerEvidence.OBSERVED_CLICK })
    }

    @Test
    fun `a click after an episode ended is never credited to it`() {
        val l = covered()
        l.notification(P.WHATSAPP, postedAt = -MIN, removedAt = 16 * SEC, reason = RemovalReason.CLICK)
        l.session(0, P.WHATSAPP to 2 * SEC) // off at 5 s
        l.session(21 * SEC, P.WHATSAPP to MIN) // unlock at 22 s: the 16 s tap is outside its window too
        assertTrue(l.reconstruct().none { it.triggerEvidence == TriggerEvidence.OBSERVED_CLICK })
    }

    @Test
    fun `a glance lit by an alert never uses up the tap that follows it`() {
        val l = covered()
        l.notification(P.WHATSAPP, postedAt = 0, removedAt = 21 * SEC, reason = RemovalReason.CLICK)
        l.glance(SEC, 5 * SEC)
        l.session(20 * SEC, P.WHATSAPP to MIN)
        val eps = l.reconstruct()
        val glance = eps.single { it.isGlanceOnly }
        val ep = eps.single { !it.isGlanceOnly }
        assertEquals(TriggerEvidence.OBSERVED_CLICK, ep.triggerEvidence)
        assertTrue(glance.triggerEvidence != TriggerEvidence.OBSERVED_CLICK)
        assertNull(glance.triggerNotificationId)
    }

    @Test
    fun `background events while the screen-off went missing do not keep the screen on all night`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).resume(2 * SEC, P.INSTAGRAM)
        // No SCREEN_NON_INTERACTIVE. Standby-bucket and foreground-service events keep arriving overnight.
        var t = 10 * MIN
        while (t < 5 * HOUR_MS) {
            l.ev(t, 11, P.YOUTUBE)
            l.ev(t + SEC, 19, P.MAPS)
            t += 15 * MIN
        }
        l.session(5 * HOUR_MS, P.WHATSAPP to MIN)
        val eps = l.reconstruct().unlocked()
        assertEquals(2, eps.size)
        assertTrue(eps[0].uncertainTail)
        assertTrue(eps[0].endMs <= l.at(2 * SEC) + 30 * MIN)
    }

    @Test
    fun `a call that ended while the screen was off does not come back at the next screen-on`() {
        val l = covered()
        l.screenOn(0).unlock(SEC).resume(2 * SEC, P.DIALER)
        l.screenOff(30 * SEC) // held to the ear
        l.pause(3 * MIN, P.DIALER) // the other side hung up with the screen off
        l.glance(HOUR_MS)
        val eps = l.reconstruct()
        assertEquals(2, eps.size)
        val call = eps.single { !it.isGlanceOnly }
        assertTrue(call.endMs <= l.at(30 * SEC))
        assertTrue(eps.single { it.isGlanceOnly }.startMs == l.at(HOUR_MS))
    }

    @Test
    fun `a slice that starts mid-use keeps the app time`() {
        val l = covered()
        // The read began after the screen-on: the first thing seen is the app.
        l.resume(0, P.INSTAGRAM).pause(5 * MIN, P.INSTAGRAM).lock(5 * MIN).screenOff(5 * MIN)
        val ep = l.reconstruct().unlocked().single()
        assertEquals(5 * MIN, ep.totals.distractingMs)

        // Screen on with no unlock in the slice, but the keyguard is only shown later: it was already unlocked.
        val m = covered()
        m.screenOn(0).resume(SEC, P.INSTAGRAM).pause(10 * MIN, P.INSTAGRAM).screenOff(10 * MIN).lock(10 * MIN)
        m.session(HOUR_MS, P.WHATSAPP to MIN)
        val first = m.reconstruct().unlocked().first()
        assertEquals(10 * MIN - SEC, first.totals.distractingMs)
    }

    @Test
    fun `a blocked reopen after a micro-lock never erases the use before it`() {
        val l = covered()
        l.session(0, P.INSTAGRAM to 20 * MIN) // off at 20m03s
        l.session(20 * MIN + 13 * SEC, P.INSTAGRAM to 6 * SEC) // reopened 10 s later and blocked
        l.blocked(P.INSTAGRAM, 20 * MIN + 15 * SEC)
        val ep = l.reconstruct().single()
        val insta = ep.segments.filter { it.pkgId == P.INSTAGRAM }
        assertEquals(2, insta.size)
        assertEquals(20 * MIN, insta[0].effectiveMs)
        assertTrue(insta[1].isBlockedAttempt)
        assertEquals(20 * MIN, ep.totals.distractingMs)
    }


    // -- Archetypes -----------------------------------------------------------------------------------------------------

    @Test
    fun `short use is a micro-check`() {
        assertEquals(Archetype.MICRO_CHECK, covered().session(0, P.WHATSAPP to 20 * SEC).reconstruct().single().archetype)
    }

    @Test
    fun `utility-only use is utility`() {
        assertEquals(Archetype.UTILITY, covered().session(0, P.MAPS to 3 * MIN).reconstruct().single().archetype)
    }

    @Test
    fun `20 minutes of distracting apps is a long-binge rabbit hole`() {
        val ep = covered().session(0, P.INSTAGRAM to 25 * MIN).reconstruct().single()
        assertEquals(Archetype.RABBIT_HOLE, ep.archetype)
        assertTrue(ep.isRabbitHole)
        assertEquals(listOf(RabbitHoleReason.LONG_BINGE), ep.rabbitHoleReasons)
    }

    @Test
    fun `hopping between distracting apps for 10 minutes is a spiral`() {
        val ep = covered().session(0, P.INSTAGRAM to 6 * MIN, P.TIKTOK to 6 * MIN).reconstruct().single()
        assertEquals(Archetype.RABBIT_HOLE, ep.archetype)
        assertEquals(listOf(RabbitHoleReason.MULTI_APP_SPIRAL), ep.rabbitHoleReasons)
        assertEquals(2, ep.distinctDistractingCount)
    }

    @Test
    fun `a utility pickup that turns into 10 minutes of YouTube is hijacked`() {
        val ep = covered().session(0, P.MAPS to MIN, P.YOUTUBE to 12 * MIN).reconstruct().single()
        assertEquals(P.MAPS, ep.entryPkgId)
        assertEquals(listOf(RabbitHoleReason.HIJACKED_PICKUP), ep.rabbitHoleReasons)
    }

    @Test
    fun `8 minutes of one distracting app is focused, not a rabbit hole`() {
        val ep = covered().session(0, P.INSTAGRAM to 8 * MIN).reconstruct().single()
        assertEquals(Archetype.FOCUSED, ep.archetype)
        assertTrue(ep.rabbitHoleReasons.isEmpty())
    }

    @Test
    fun `a mostly-one-app session is focused, an even split is mixed`() {
        assertEquals(Archetype.FOCUSED, covered().session(0, P.WHATSAPP to 10 * MIN).reconstruct().single().archetype)
        assertEquals(Archetype.MIXED, covered().session(0, P.WHATSAPP to 3 * MIN, P.CHROME to 3 * MIN).reconstruct().single().archetype)
    }

    // -- Determinism -------------------------------------------------------------------------------------------------

    @Test
    fun `episode keys are deterministic and independent of how much earlier history is loaded`() {
        val l = covered()
        l.session(0, P.WHATSAPP to MIN)
        l.glance(5 * MIN)
        l.session(10 * HOUR_MS, P.INSTAGRAM to 2 * MIN)
        val full = l.reconstruct()
        assertEquals(full.map { it.episodeKey }, l.reconstruct().map { it.episodeKey })
        // Rebuilding only the last chunk yields the same key for the later episode.
        val tail = SessionReconstructor.reconstruct(l.input().copy(events = l.events.filter { it.timestampMs >= l.at(9 * HOUR_MS) }))
        assertEquals(full.last().episodeKey, tail.single().episodeKey)
        assertEquals(full.map { it.episodeKey }.toSet().size, full.size)
    }

    @Test
    fun `no notification at all leaves the notification id empty`() {
        val ep = covered().session(0, P.WHATSAPP to MIN).reconstruct().single()
        assertNull(ep.triggerNotificationId)
        assertNull(ep.triggerPkgId)
    }

    private companion object {
        const val UNCERTAIN_FLAG = "UNCERTAIN_TAIL"
        const val IN_PROGRESS_FLAG = "IN_PROGRESS"
    }

    @Test
    fun `a shade tap that opens a later app goes to that switch, not to the pickup`() {
        val l = covered()
        // Unlock into Maps, then tap a WhatsApp notification 11 s after unlocking.
        l.notification(P.WHATSAPP, postedAt = -10 * MIN, removedAt = 12 * SEC, reason = RemovalReason.CLICK)
        l.session(0, P.MAPS to 10 * SEC, P.WHATSAPP to 2 * MIN)
        val ep = l.reconstruct().single()
        assertEquals(TriggerEvidence.NO_OBSERVED_TRIGGER, ep.triggerEvidence)
        assertEquals(TriggerEvidence.OBSERVED_CLICK, ep.segments[1].triggerEvidence)
        assertEquals(P.WHATSAPP, ep.segments[1].triggerPkgId)
    }

    @Test
    fun `a switch never borrows the tap that opened a later switch`() {
        val l = covered()
        // Unlock at 1 s into Maps, Chrome from 4 s (nothing tapped), WhatsApp from 12 s, opened by a shade tap at 11 s.
        l.notification(P.WHATSAPP, postedAt = -10 * MIN, removedAt = 11 * SEC, reason = RemovalReason.CLICK)
        l.session(0, P.MAPS to 2 * SEC, P.CHROME to 8 * SEC, P.WHATSAPP to 2 * MIN)
        val ep = l.reconstruct().single()
        assertEquals(TriggerEvidence.OBSERVED_CLICK, ep.segments.single { it.pkgId == P.WHATSAPP }.triggerEvidence)
        assertNull(ep.segments.single { it.pkgId == P.CHROME }.triggerEvidence)
        assertEquals(TriggerEvidence.NO_OBSERVED_TRIGGER, ep.triggerEvidence)
    }

    @Test
    fun `an alert counts only within a minute before the unlock, however long the lockscreen dwell`() {
        fun evidence(postedAt: Long): TriggerEvidence {
            val l = covered()
            l.notification(P.WHATSAPP, postedAt = postedAt)
            l.screenOn(0).unlock(2 * MIN).use(P.INSTAGRAM, 2 * MIN + SEC, 5 * MIN).lock(5 * MIN + SEC).screenOff(5 * MIN + SEC)
            return l.reconstruct().single().triggerEvidence
        }
        // Lit the screen 10 s before it came on, but 2 min 10 s before the unlock.
        assertEquals(TriggerEvidence.NO_OBSERVED_TRIGGER, evidence(-10 * SEC))
        assertEquals(TriggerEvidence.RECENT_POSSIBLE_ALERT, evidence(90 * SEC))
    }

    @Test
    fun `a long lockscreen dwell does not widen the click window before the unlock`() {
        val l = covered()
        l.notification(P.WHATSAPP, postedAt = -10 * MIN, removedAt = 2 * SEC, reason = RemovalReason.CLICK)
        l.screenOn(0).unlock(30 * SEC).use(P.WHATSAPP, 31 * SEC, 3 * MIN).lock(3 * MIN + SEC).screenOff(3 * MIN + SEC)
        val ep = l.reconstruct().single()
        assertEquals(l.at(30 * SEC), ep.startMs)
        assertTrue(ep.triggerEvidence != TriggerEvidence.OBSERVED_CLICK)
    }

    @Test
    fun `a blocked launch, a quick hop and a real reopen of the same app stay apart`() {
        val l = covered()
        l.session(0, P.REDDIT to 3 * SEC, P.LAUNCHER to 4 * SEC, P.REDDIT to 5 * MIN)
        l.blocked(P.REDDIT, 2 * SEC)
        val ep = l.reconstruct().single()
        val reddit = ep.segments.filter { it.pkgId == P.REDDIT }
        assertEquals(2, reddit.size)
        assertTrue(reddit[0].isBlockedAttempt)
        assertFalse(reddit[1].isBlockedAttempt)
        assertEquals(5 * MIN, reddit[1].effectiveMs)
        assertEquals(1, ep.blockedAttemptCount)
        assertTrue(ep.archetype != Archetype.BLOCKED_ATTEMPT)
    }
}
