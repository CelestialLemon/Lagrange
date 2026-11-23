package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.Archetype
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.FrictionVocab
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.data.db.UsageEventTypes as T

/**
 * Turns the raw usage ledger into behavioral episodes (spec §4.1). Pure and deterministic: the same input always
 * yields the same episodes, so derived tables can be dropped and rebuilt at any time.
 *
 * The passes, in order:
 *  1. **Screen intervals** from `SCREEN_INTERACTIVE` / `SCREEN_NON_INTERACTIVE`, unlocked by `KEYGUARD_HIDDEN`.
 *     Intervals left open by a missing event are clamped at the next screen-on, `DEVICE_SHUTDOWN`,
 *     `DEVICE_STARTUP` or the end of the ledger, never more than [ReconstructionRules.uncertainTailCapMs] past the
 *     last observed event.
 *  2. **Foreground timeline**: `ACTIVITY_RESUMED` paired with `ACTIVITY_PAUSED` / `ACTIVITY_STOPPED` by
 *     `(pkg, cls)`. When several activities are resumed at once (split screen, PiP) only the most recently resumed
 *     one is foreground, so foreground time never exceeds wall-clock time. Screen-off clears dangling activities.
 *  3. **Episodes**: lockscreen-only intervals are glances; unlocked intervals separated by a screen-off shorter than
 *     [ReconstructionRules.microLockMergeMs], or by a call's proximity screen-off, merge into one episode. Merged
 *     gaps are never counted as active time.
 *  4. **App chain cleaning**: Custom Tabs credited to the host app, short system hops dropped, same-app stretches
 *     around a brief dialog joined, launch-pause blocked attempts scrubbed.
 *  5. **Trigger attribution** for each episode and each mid-session app switch.
 *  6. **Totals and archetypes**, including the three rabbit-hole rules.
 */
object SessionReconstructor {

    fun reconstruct(input: ReconstructionInput, rules: ReconstructionRules = ReconstructionRules()): List<Episode> {
        val ctx = Context(input, rules)
        val timeline = ctx.sweep()
        val raw = ctx.mergeEpisodes(timeline)
        val consumed = HashSet<Long>()
        val frictionUsed = HashSet<Int>()
        return raw.map { ctx.finish(it, consumed, frictionUsed) }
    }

    // -----------------------------------------------------------------------------------------------------------------

    private data class ActKey(val pkgId: Long, val clsId: Long)

    /** A foreground stretch of one activity. */
    private data class Fg(val pkgId: Long, val clsId: Long, val span: Span)

    private class ScreenInterval(val onMs: Long) {
        var unlockMs: Long? = null
        var endMs: Long = onMs
        var uncertainTail = false
        var inProgress = false
    }

    private class Timeline(val intervals: List<ScreenInterval>, val foreground: List<Fg>, val keyguardObserved: Boolean)

    /** An unlocked interval or a glance, before merging. */
    private class Piece(
        val pickupAtMs: Long,
        val active: Span,
        val glance: Boolean,
        val slices: List<Fg>,
        val uncertainTail: Boolean,
        val inProgress: Boolean,
    )

    private class RawEpisode(
        val pickupAtMs: Long,
        val activeSpans: List<Span>,
        val glance: Boolean,
        val slices: List<Fg>,
        val pickups: Int,
        val uncertainTail: Boolean,
        val inProgress: Boolean,
    )

    private class Context(val input: ReconstructionInput, val rules: ReconstructionRules) {
        private val callPkgIds: Set<Long> = input.apps.values.filter { it.packageName in rules.callPackages }.map { it.pkgId }.toSet()

        fun isTransient(pkgId: Long): Boolean {
            val app = input.apps[pkgId] ?: return false
            return app.isSystemSurface || app.packageName in rules.transientPackages
        }

        fun category(pkgId: Long): AppCategory = input.apps[pkgId]?.category ?: AppCategory.NEUTRAL

        // -- Pass 1 + 2: one sweep over the ledger ----------------------------------------------------------------

        fun sweep(): Timeline {
            val events = input.events.sortedWith(compareBy<UsageEvent>({ it.timestampMs }, { it.order }))
            val keyguardObserved = events.any { it.type == T.KEYGUARD_SHOWN || it.type == T.KEYGUARD_HIDDEN }
            val intervals = ArrayList<ScreenInterval>()
            val fgOut = ArrayList<Fg>()

            var keyguardHidden = false
            var open: ScreenInterval? = null
            var lastEventMs = Long.MIN_VALUE

            val resumed = HashMap<ActKey, Long>()
            var resumeSeq = 0L
            var fgKey: ActKey? = null
            var fgSince = 0L
            // Call UI activities survive a proximity screen-off: they're carried across and restored at screen-on.
            var carry: Map<ActKey, Long> = emptyMap()
            var carryAtMs = 0L

            fun refreshFg(t: Long) {
                val top = resumed.maxByOrNull { it.value }?.key
                if (top != fgKey) {
                    fgKey?.let { if (t > fgSince) fgOut += Fg(it.pkgId, it.clsId, Span(fgSince, t)) }
                    fgKey = top
                    fgSince = t
                }
            }

            fun clampedEnd(iv: ScreenInterval, t: Long): Long {
                val anchor = maxOf(iv.onMs, lastEventMs)
                return minOf(t, anchor + rules.uncertainTailCapMs).coerceAtLeast(iv.onMs)
            }

            fun close(iv: ScreenInterval, endMs: Long, uncertain: Boolean) {
                iv.endMs = endMs
                iv.uncertainTail = uncertain
                intervals += iv
                open = null
            }

            fun screenOn(t: Long): ScreenInterval {
                val iv = ScreenInterval(t)
                if (keyguardHidden) iv.unlockMs = t
                open = iv
                if (carry.isNotEmpty() && t - carryAtMs <= rules.callMergeMaxGapMs) {
                    for ((k, _) in carry) resumed[k] = ++resumeSeq
                    refreshFg(t)
                }
                carry = emptyMap()
                return iv
            }

            fun screenOff(t: Long, keepCalls: Boolean) {
                carry = if (keepCalls) resumed.filterKeys { it.pkgId in callPkgIds } else emptyMap()
                carryAtMs = t
                resumed.clear()
                refreshFg(t)
            }

            for (e in events) {
                val t = e.timestampMs
                when (e.type) {
                    T.SCREEN_INTERACTIVE -> {
                        val iv = open
                        if (iv == null) {
                            screenOn(t)
                        } else if (t - maxOf(iv.onMs, lastEventMs) > rules.uncertainTailCapMs) {
                            // The screen-off was never recorded and nothing happened for a long time: close the old
                            // interval conservatively and start a new one.
                            val end = clampedEnd(iv, t)
                            screenOff(end, keepCalls = false)
                            close(iv, end, uncertain = true)
                            screenOn(t)
                        } // else: a duplicate screen-on inside a live interval; keep going.
                    }
                    T.KEYGUARD_HIDDEN -> {
                        keyguardHidden = true
                        val iv = open ?: screenOn(t)
                        if (iv.unlockMs == null) iv.unlockMs = t
                    }
                    T.KEYGUARD_SHOWN -> keyguardHidden = false
                    T.SCREEN_NON_INTERACTIVE -> {
                        screenOff(t, keepCalls = true)
                        open?.let { close(it, t, uncertain = false) }
                    }
                    T.DEVICE_SHUTDOWN -> {
                        keyguardHidden = false
                        screenOff(t, keepCalls = false)
                        open?.let { close(it, t, uncertain = false) }
                    }
                    T.DEVICE_STARTUP -> {
                        // A startup with the screen still "on" means the phone died without a shutdown event
                        // (battery): cap the dangling interval conservatively.
                        keyguardHidden = false
                        val iv = open
                        if (iv != null) {
                            val end = clampedEnd(iv, t)
                            screenOff(end, keepCalls = false)
                            close(iv, end, uncertain = true)
                        } else {
                            screenOff(t, keepCalls = false)
                        }
                    }
                    T.ACTIVITY_RESUMED -> {
                        resumed[ActKey(e.pkgId, e.clsId)] = ++resumeSeq
                        refreshFg(t)
                    }
                    T.ACTIVITY_PAUSED, T.ACTIVITY_STOPPED -> {
                        resumed.remove(ActKey(e.pkgId, e.clsId))
                        refreshFg(t)
                    }
                    else -> Unit
                }
                lastEventMs = maxOf(lastEventMs, t)
            }

            open?.let { iv ->
                val anchor = maxOf(iv.onMs, lastEventMs)
                val end = clampedEnd(iv, input.dataEndMs.coerceAtLeast(anchor))
                iv.inProgress = true
                resumed.clear()
                refreshFg(end)
                close(iv, end, uncertain = input.dataEndMs - anchor > rules.uncertainTailCapMs)
            }
            if (fgKey != null) {
                resumed.clear()
                refreshFg(maxOf(lastEventMs, fgSince))
            }
            return Timeline(intervals, fgOut, keyguardObserved)
        }

        // -- Pass 3: glances, unlocked pieces and merging -----------------------------------------------------------

        fun mergeEpisodes(timeline: Timeline): List<RawEpisode> {
            val fg = timeline.foreground.sortedBy { it.span.startMs }
            val pieces = timeline.intervals.sortedBy { it.onMs }.mapNotNull { iv ->
                if (iv.endMs <= iv.onMs) return@mapNotNull null
                val slicesAll = clipSlices(fg, iv.onMs, iv.endMs)
                val callUi = slicesAll.any { it.pkgId in callPkgIds }
                val activeStart = iv.unlockMs
                    ?: if (!timeline.keyguardObserved && slicesAll.any { !isTransient(it.pkgId) }) iv.onMs else null
                    ?: if (callUi) slicesAll.first { it.pkgId in callPkgIds }.span.startMs else null
                if (activeStart == null || activeStart >= iv.endMs) {
                    Piece(iv.onMs, Span(iv.onMs, iv.endMs), glance = true, slices = emptyList(), iv.uncertainTail, iv.inProgress)
                } else {
                    val active = Span(activeStart, iv.endMs)
                    Piece(iv.onMs, active, glance = false, slices = clipSlices(fg, active.startMs, active.endMs), iv.uncertainTail, iv.inProgress)
                }
            }

            val unlocked = pieces.filter { !it.glance }
            val glances = pieces.filter { it.glance }
            val merged = ArrayList<MutableList<Piece>>()
            val mergedPickups = ArrayList<Int>()
            for (p in unlocked) {
                val group = merged.lastOrNull()
                val prev = group?.last()
                if (prev != null) {
                    val gap = p.pickupAtMs - prev.active.endMs
                    val microLock = gap < rules.microLockMergeMs
                    val call = prev.slices.lastOrNull()?.let { it.pkgId in callPkgIds } == true &&
                        p.slices.firstOrNull()?.let { it.pkgId in callPkgIds } == true && gap <= rules.callMergeMaxGapMs
                    if (microLock || call) {
                        group += p
                        // A micro-lock is a real extra pickup; a proximity screen-off during a call is not.
                        if (microLock && !call) mergedPickups[mergedPickups.lastIndex] += 1
                        continue
                    }
                }
                merged += mutableListOf(p)
                mergedPickups += 1
            }

            val out = ArrayList<RawEpisode>()
            val absorbed = HashSet<Piece>()
            merged.forEachIndexed { i, group ->
                val start = group.first().active.startMs
                val end = group.last().active.endMs
                // Glances inside a merged gap belong to the episode (each is one more pickup, never active time).
                val inside = if (group.size < 2) emptyList() else glances.filter { it.pickupAtMs >= start && it.active.endMs <= end }
                absorbed += inside
                out += RawEpisode(
                    pickupAtMs = group.first().pickupAtMs,
                    activeSpans = group.map { it.active },
                    glance = false,
                    slices = group.flatMap { it.slices },
                    pickups = mergedPickups[i] + inside.size,
                    uncertainTail = group.any { it.uncertainTail },
                    inProgress = group.last().inProgress,
                )
            }
            for (g in glances) {
                if (g in absorbed) continue
                out += RawEpisode(g.pickupAtMs, listOf(g.active), glance = true, slices = emptyList(), pickups = 1, g.uncertainTail, g.inProgress)
            }
            return out.sortedBy { it.activeSpans.first().startMs }
        }

        private fun clipSlices(fg: List<Fg>, fromMs: Long, toMs: Long): List<Fg> =
            fg.mapNotNull { f -> f.span.clip(fromMs, toMs)?.let { Fg(f.pkgId, f.clsId, it) } }

        // -- Pass 4-6: chain cleaning, triggers, classification ----------------------------------------------------

        fun finish(raw: RawEpisode, consumed: MutableSet<Long>, frictionUsed: MutableSet<Int>): Episode {
            val start = raw.activeSpans.first().startMs
            val end = raw.activeSpans.last().endMs
            val spanTotal = raw.activeSpans.sumOf { it.durationMs }
            if (raw.glance) {
                val trigger = attributeEpisode(raw.pickupAtMs, start, null, null, consumed)
                return Episode(
                    startMs = start, endMs = end, pickupAtMs = raw.pickupAtMs, activeSpans = raw.activeSpans,
                    activeDurationMs = spanTotal, physicalPickupCount = 1, isGlanceOnly = true,
                    triggerEvidence = trigger.evidence, triggerPkgId = trigger.pkgId, triggerNotificationId = trigger.notificationId,
                    entryPkgId = null, segments = emptyList(), totals = CategoryTotals(), blockedAttemptCount = 0,
                    distinctAppCount = 0, distinctDistractingCount = 0, archetype = Archetype.GLANCE, rabbitHoleReasons = emptyList(),
                    uncertainTail = raw.uncertainTail, inProgress = raw.inProgress,
                )
            }

            var segments = cleanChain(raw.slices)
            segments = scrubBlocked(segments, frictionUsed)

            val entry = segments.firstOrNull { !it.isBlockedAttempt && !isTransient(it.pkgId) }
                ?: segments.firstOrNull { !isTransient(it.pkgId) }
                ?: segments.firstOrNull()
            val trigger = attributeEpisode(raw.pickupAtMs, start, entry?.pkgId, entry?.startMs, consumed)
            segments = segments.mapIndexed { i, seg ->
                when {
                    seg === entry || (i == 0 && entry == null) -> seg.copy(triggerEvidence = trigger.evidence, triggerPkgId = trigger.pkgId)
                    i > 0 && !isTransient(seg.pkgId) && seg.pkgId != segments[i - 1].pkgId -> {
                        val t = attributeSwitch(seg, consumed)
                        if (t == null) seg else seg.copy(triggerEvidence = t.evidence, triggerPkgId = t.pkgId)
                    }
                    else -> seg
                }
            }

            var totals = CategoryTotals()
            for (s in segments) totals = totals.plus(category(s.pkgId), s.effectiveMs)
            val real = segments.filter { !isTransient(it.pkgId) && it.effectiveMs > 0 }
            val distinctApps = real.map { it.pkgId }.toSet()
            val distinctDistracting = distinctApps.count { category(it) == AppCategory.DISTRACTING }
            val blockedMs = segments.sumOf { it.blockedMs }
            val active = (spanTotal - blockedMs).coerceAtLeast(0)
            val blockedCount = segments.count { it.isBlockedAttempt }

            val reasons = rabbitHoleReasons(totals, distinctDistracting, active, entry?.pkgId, trigger.pkgId, trigger.evidence)
            val archetype = classify(segments, totals, active, reasons, blockedCount)
            return Episode(
                startMs = start, endMs = end, pickupAtMs = raw.pickupAtMs, activeSpans = raw.activeSpans,
                activeDurationMs = active, physicalPickupCount = raw.pickups, isGlanceOnly = false,
                triggerEvidence = trigger.evidence, triggerPkgId = trigger.pkgId, triggerNotificationId = trigger.notificationId,
                entryPkgId = entry?.pkgId, segments = segments, totals = totals, blockedAttemptCount = blockedCount,
                distinctAppCount = distinctApps.size, distinctDistractingCount = distinctDistracting,
                archetype = archetype, rabbitHoleReasons = if (archetype == Archetype.RABBIT_HOLE) reasons else emptyList(),
                uncertainTail = raw.uncertainTail, inProgress = raw.inProgress,
            )
        }

        /** Custom Tabs → host, same-app stretches joined, short system hops dropped. */
        fun cleanChain(slices: List<Fg>): List<Segment> {
            if (slices.isEmpty()) return emptyList()
            // Custom Tabs opened straight from an app belong to that app (Reddit → link → Reddit stays Reddit).
            val attributed = ArrayList<Pair<Long, Span>>(slices.size)
            for ((i, f) in slices.withIndex()) {
                var pkg = f.pkgId
                if (i > 0 && f.clsId in input.customTabClassIds) {
                    val (prevPkg, prevSpan) = attributed[i - 1]
                    if (prevPkg != pkg && !isTransient(prevPkg) && f.span.startMs - prevSpan.endMs <= rules.customTabLaunchMs) pkg = prevPkg
                }
                attributed += pkg to f.span
            }
            val joined = ArrayList<Segment>()
            for ((pkg, span) in attributed) {
                val last = joined.lastOrNull()
                if (last != null && last.pkgId == pkg) joined[joined.lastIndex] = last.copy(spans = last.spans + span)
                else joined += Segment(pkg, listOf(span))
            }
            if (joined.none { !isTransient(it.pkgId) }) return joined

            val out = ArrayList<Segment>()
            val hops = ArrayList<Span>()
            for (seg in joined) {
                if (isTransient(seg.pkgId) && seg.durationMs < rules.transientHopMs) {
                    hops += seg.spans
                    continue
                }
                val last = out.lastOrNull()
                if (last != null && last.pkgId == seg.pkgId) {
                    // The same app on both sides of a brief system dialog is one stretch, dialog included.
                    out[out.lastIndex] = last.copy(spans = mergeSpans(last.spans + hops + seg.spans))
                } else {
                    out += seg
                }
                hops.clear()
            }
            return out
        }

        private fun mergeSpans(spans: List<Span>): List<Span> {
            val sorted = spans.sortedBy { it.startMs }
            val out = ArrayList<Span>(sorted.size)
            for (s in sorted) {
                val last = out.lastOrNull()
                if (last != null && s.startMs <= last.endMs) out[out.lastIndex] = Span(last.startMs, maxOf(last.endMs, s.endMs)) else out += s
            }
            return out
        }

        /** Marks opens that ended at a launch pause (Go Home, system back/home, redirect) and scrubs the pause time. */
        fun scrubBlocked(segments: List<Segment>, used: MutableSet<Int>): List<Segment> {
            if (segments.isEmpty() || input.friction.isEmpty()) return segments
            val out = segments.toMutableList()
            input.friction.forEachIndexed { idx, f ->
                if (idx in used || f.eventKind != FrictionVocab.LAUNCH_PAUSE || f.outcome !in FrictionVocab.BLOCKING_OUTCOMES) return@forEachIndexed
                val match = out.withIndex()
                    .filter { (_, s) -> s.pkgId == f.pkgId && !s.isBlockedAttempt }
                    .filter { (_, s) -> kotlin.math.abs(s.startMs - f.timestampMs) <= rules.frictionMatchMs || f.timestampMs in s.startMs..s.endMs }
                    .minByOrNull { (_, s) -> kotlin.math.abs(s.startMs - f.timestampMs) }
                    ?: return@forEachIndexed
                val seg = match.value
                val windowEnd = f.timestampMs + f.pauseDurationSec * 1000L + (f.postCountdownReactionMs ?: 0L) + rules.blockedGraceMs
                val blocked = (windowEnd - seg.startMs).coerceIn(0, seg.durationMs)
                out[match.index] = seg.copy(isBlockedAttempt = true, blockedMs = blocked)
                used += idx
            }
            return out
        }

        // -- Triggers -------------------------------------------------------------------------------------------------

        class Trigger(val evidence: TriggerEvidence, val pkgId: Long?, val notificationId: Long?)

        private val listenerSpans = input.coverage
            .filter { it.kind == ServiceKind.NOTIFICATION_LISTENER && it.status == CoverageStatus.ACTIVE }
            .map { it.span }
        private val backfillSpans = input.coverage.filter { it.status == CoverageStatus.BACKFILL }.map { it.span }
        private val eligible = input.notifications.filter { it.isTriggerEligible }

        /**
         * Positive evidence (an observed tap, an app self-cancel, a fresh alert, a waiting notification) is reported
         * whenever it was recorded. Only when nothing was seen does coverage decide between "nothing happened"
         * ([TriggerEvidence.NO_OBSERVED_TRIGGER]) and "we couldn't have seen it" (BACKFILL / PARTIAL_COVERAGE).
         */
        fun attributeEpisode(pickupAtMs: Long, unlockMs: Long, entryPkg: Long?, entryAtMs: Long?, consumed: MutableSet<Long>): Trigger {
            val click = eligible
                .filter { it.id !in consumed && it.removalReason == RemovalReason.CLICK }
                .filter { val r = it.removedAtMs ?: return@filter false; r >= pickupAtMs - rules.clickBeforeMs && r <= unlockMs + rules.clickAfterMs }
                .minWithOrNull(compareBy<NotificationMeta>({ if (it.pkgId == entryPkg) 0 else 1 }, { kotlin.math.abs(it.removedAtMs!! - unlockMs) }))
            if (click != null) {
                consumed += click.id
                return Trigger(TriggerEvidence.OBSERVED_CLICK, click.pkgId, click.id)
            }
            if (entryPkg != null && entryAtMs != null) {
                appCancel(entryPkg, entryAtMs, consumed)?.let { return it }
            }
            val alert = eligible
                .filter { it.id !in consumed && it.isAlerting && it.postedAtMs >= pickupAtMs - rules.alertBeforeMs && it.postedAtMs <= unlockMs }
                .maxWithOrNull(compareBy<NotificationMeta>({ if (it.pkgId == entryPkg) 1 else 0 }, { it.postedAtMs }))
            if (alert != null) {
                consumed += alert.id
                return Trigger(TriggerEvidence.RECENT_POSSIBLE_ALERT, alert.pkgId, alert.id)
            }
            if (entryPkg != null) {
                val pending = eligible
                    .filter { it.pkgId == entryPkg && it.postedAtMs <= unlockMs - rules.pendingMinAgeMs }
                    .filter { it.removedAtMs == null || it.removedAtMs > unlockMs }
                    .maxByOrNull { it.postedAtMs }
                if (pending != null) return Trigger(TriggerEvidence.PENDING_NOTIFICATION, entryPkg, pending.id)
            }
            val from = pickupAtMs - rules.alertBeforeMs
            val to = unlockMs + rules.clickAfterMs
            val covered = Spans.coveredMs(listenerSpans, from, to) >= to - from
            return when {
                covered -> Trigger(TriggerEvidence.NO_OBSERVED_TRIGGER, null, null)
                Spans.contains(backfillSpans, pickupAtMs) || Spans.contains(backfillSpans, unlockMs) -> Trigger(TriggerEvidence.BACKFILL, null, null)
                else -> Trigger(TriggerEvidence.PARTIAL_COVERAGE, null, null)
            }
        }

        private fun appCancel(pkg: Long, atMs: Long, consumed: MutableSet<Long>): Trigger? {
            val n = eligible
                .filter { it.id !in consumed && it.pkgId == pkg && it.removalReason == RemovalReason.APP_CANCEL && it.postedAtMs < atMs }
                .filter { val r = it.removedAtMs ?: return@filter false; r >= atMs && r <= atMs + rules.appCancelAfterMs }
                .minByOrNull { it.removedAtMs!! } ?: return null
            consumed += n.id
            return Trigger(TriggerEvidence.LIKELY_APP_CANCEL_CLICK, n.pkgId, n.id)
        }

        /** A mid-session switch: a shade tap, an app self-cancel or a fresh alert for the app switched to. Null when nothing was seen. */
        fun attributeSwitch(seg: Segment, consumed: MutableSet<Long>): Trigger? {
            val at = seg.startMs
            val click = eligible
                .filter { it.id !in consumed && it.removalReason == RemovalReason.CLICK }
                .filter { val r = it.removedAtMs ?: return@filter false; r >= at - rules.clickBeforeMs && r <= at + rules.clickAfterMs }
                .minWithOrNull(compareBy<NotificationMeta>({ if (it.pkgId == seg.pkgId) 0 else 1 }, { kotlin.math.abs(it.removedAtMs!! - at) }))
            if (click != null) {
                consumed += click.id
                return Trigger(TriggerEvidence.OBSERVED_CLICK, click.pkgId, click.id)
            }
            appCancel(seg.pkgId, at, consumed)?.let { return it }
            val alert = eligible
                .filter { it.id !in consumed && it.isAlerting && it.pkgId == seg.pkgId && it.postedAtMs >= at - rules.alertBeforeMs && it.postedAtMs <= at }
                .maxByOrNull { it.postedAtMs }
            if (alert != null) {
                consumed += alert.id
                return Trigger(TriggerEvidence.RECENT_POSSIBLE_ALERT, alert.pkgId, alert.id)
            }
            return null
        }

        // -- Classification -----------------------------------------------------------------------------------------

        fun rabbitHoleReasons(
            totals: CategoryTotals,
            distinctDistracting: Int,
            activeMs: Long,
            entryPkg: Long?,
            triggerPkg: Long?,
            evidence: TriggerEvidence,
        ): List<RabbitHoleReason> {
            val d = totals.distractingMs
            val out = ArrayList<RabbitHoleReason>()
            if (d >= rules.longBingeMs) out += RabbitHoleReason.LONG_BINGE
            if (distinctDistracting >= rules.spiralDistinctApps && d >= rules.spiralMs) out += RabbitHoleReason.MULTI_APP_SPIRAL
            val calmEntry = entryPkg != null && category(entryPkg) in CALM
            val calmTrigger = triggerPkg != null && evidence in NOTIFICATION_EVIDENCE && category(triggerPkg) in CALM
            if ((calmEntry || calmTrigger) && d >= rules.hijackMs && activeMs > 0 && d.toDouble() / activeMs >= rules.hijackShare) {
                out += RabbitHoleReason.HIJACKED_PICKUP
            }
            return out
        }

        fun classify(segments: List<Segment>, totals: CategoryTotals, activeMs: Long, reasons: List<RabbitHoleReason>, blockedCount: Int): Archetype {
            // Only blocked opens (plus system hops): the pickup never became real use, so it is no micro-check either.
            if (blockedCount > 0 && segments.none { !it.isBlockedAttempt && !isTransient(it.pkgId) }) return Archetype.BLOCKED_ATTEMPT
            if (activeMs < rules.microCheckMs) return Archetype.MICRO_CHECK
            if (reasons.isNotEmpty()) return Archetype.RABBIT_HOLE
            val categorized = totals.categorizedMs
            if (categorized > 0 &&
                (totals.utilityMs + totals.excludedMs).toDouble() / categorized >= rules.utilityShare &&
                totals.distractingMs < rules.utilityMaxDistractingMs
            ) return Archetype.UTILITY
            val perApp = segments.groupBy { it.pkgId }.mapValues { (_, s) -> s.sumOf { it.effectiveMs } }
            val top = perApp.values.maxOrNull() ?: 0
            if (activeMs > 0 && top.toDouble() / activeMs >= rules.focusedShare) return Archetype.FOCUSED
            return Archetype.MIXED
        }
    }

    private val CALM = setOf(AppCategory.UTILITY, AppCategory.NEUTRAL)
    private val NOTIFICATION_EVIDENCE = setOf(
        TriggerEvidence.OBSERVED_CLICK,
        TriggerEvidence.LIKELY_APP_CANCEL_CLICK,
        TriggerEvidence.RECENT_POSSIBLE_ALERT,
        TriggerEvidence.PENDING_NOTIFICATION,
    )
}
