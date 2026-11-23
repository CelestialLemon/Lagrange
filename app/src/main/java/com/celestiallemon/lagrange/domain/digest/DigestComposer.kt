package com.celestiallemon.lagrange.domain.digest

import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.domain.DayQuality
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.TextStyle
import java.time.temporal.IsoFields
import java.util.Locale

// Reflection digests (spec §5.5): a calm local summary of a day or a week. Everything here is plain Kotlin over
// already-derived facts; the repository gathers them and the notifier posts the result. Honesty rules: a day without
// a rollup is never reported as "0m", a day with a collection gap is labelled as a lower bound and never compared,
// and week-over-week deltas appear only when both weeks have enough fully covered days.

enum class DigestKind { DAILY, WEEKLY }

/** One logical day as a digest sees it (a `daily_rollups` row plus how far it can be trusted). */
data class DigestDay(
    val date: LocalDate,
    val quality: DayQuality,
    val distractingMs: Long = 0,
    val activeMs: Long = 0,
    /** The budget that applied that day (null: none recorded). */
    val budgetMs: Long? = null,
    /** Full-day weekday/weekend median of active time from covered prior days (null until a baseline exists). */
    val baselineMedianMs: Long? = null,
    val dayStatus: DayStatus? = null,
    val longestFocusGapMs: Long = 0,
) {
    /** Collection was continuous: numbers are the whole day and may be compared. */
    val covered: Boolean get() = quality.countsAsCovered && dayStatus != DayStatus.UNCERTAIN_COVERAGE

    /** What was seen is real, but the day isn't whole: a lower bound, never compared. */
    val partial: Boolean get() = quality == DayQuality.PARTIAL || dayStatus == DayStatus.UNCERTAIN_COVERAGE
}

/** The longest-distracting rabbit hole in the digest's period. */
data class DigestRabbitHole(
    val date: LocalDate,
    val startLocal: LocalTime,
    val distractingMs: Long,
    /** Display names of the hop chain, in order. */
    val apps: List<String>,
)

/** Launch-pause exits in the period: immediate exits, and those sustained ≥10 observed minutes. */
data class DigestExits(val exits: Int = 0, val sustained: Int = 0)

data class DailyDigestInput(
    /** The logical day today (the device's current logical date). */
    val today: LocalDate,
    /** The day being summarized; null when no rollup exists for it at all. */
    val day: DigestDay?,
    val biggestRabbitHole: DigestRabbitHole? = null,
    val exits: DigestExits = DigestExits(),
    /** The budget in force now, used when the day has none recorded. */
    val currentBudgetMs: Long,
    /** Set when nothing was recorded after this local time (a failed or late harvest): the day's tail is unobserved. */
    val recordedThrough: LocalDateTime? = null,
)

data class WeeklyDigestInput(
    /** The logical day the digest runs on; the week summarized is the seven days before it. */
    val today: LocalDate,
    /** Seven days, oldest first, ending the day before [today]. Missing dates count as no data. */
    val current: List<DigestDay>,
    /** The seven days before [current]. */
    val previous: List<DigestDay>,
    val biggestRabbitHole: DigestRabbitHole? = null,
    val exits: DigestExits = DigestExits(),
)

/** A composed digest. [key] is the dedupe key: the logical date (daily) or ISO week (weekly) it covers. */
data class Digest(
    val kind: DigestKind,
    val key: String,
    val title: String,
    /** One-line collapsed text. */
    val summary: String,
    /** Expanded body lines. */
    val lines: List<String>,
    /** Set when some of the period had a collection gap. */
    val coverageNote: String? = null,
)

object DigestComposer {
    /** A daily digest fired this long after the logical day began summarizes that day ("so far"); earlier, the day before. */
    const val EVENING_THRESHOLD_MS = 12 * 60 * 60_000L

    /** Covered days each week needs before a week-over-week change is shown. */
    const val MIN_COMPARE_DAYS = 3

    /** Derived data may lag the period it reports on by this much before the rest counts as unobserved. */
    const val TAIL_TOLERANCE_MS = 30 * 60_000L

    /**
     * True when the derived data ([derivedThroughMs], `Long.MIN_VALUE` when nothing was derived) stops more than
     * [TAIL_TOLERANCE_MS] before [expectedEndMs] (now, for today so far; the day's end for a past day): what follows
     * was never seen, so the day's numbers are a lower bound and no positive budget verdict may be drawn.
     */
    fun unobservedTail(derivedThroughMs: Long, expectedEndMs: Long): Boolean =
        derivedThroughMs == Long.MIN_VALUE || derivedThroughMs < expectedEndMs - TAIL_TOLERANCE_MS

    /**
     * The logical day a daily digest fired at [nowMs] summarizes: today once it is [EVENING_THRESHOLD_MS] old (an
     * evening digest reports the day so far), otherwise the previous, complete day (a morning digest, or one that
     * fires between midnight and the rollover hour, which still belongs to yesterday's logical day).
     */
    fun dailyTarget(nowMs: Long, today: LocalDate, todayStartMs: Long): LocalDate =
        if (nowMs - todayStartMs >= EVENING_THRESHOLD_MS) today else today.minusDays(1)

    /** The dedupe key of a weekly digest run on logical day [today]: its ISO week, e.g. `2026-W48`. */
    fun isoWeekKey(today: LocalDate): String =
        String.format(Locale.US, "%d-W%02d", today.get(IsoFields.WEEK_BASED_YEAR), today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))

    /** The seven days a weekly digest run on [today] summarizes (ending yesterday) and the seven before them. */
    fun weeklyRanges(today: LocalDate): Pair<List<LocalDate>, List<LocalDate>> {
        val current = (7 downTo 1).map { today.minusDays(it.toLong()) }
        val previous = (14 downTo 8).map { today.minusDays(it.toLong()) }
        return current to previous
    }

    /** Null when there is nothing honest to say (no data for the day at all). */
    fun daily(input: DailyDigestInput): Digest? {
        val day = input.day ?: return null
        if (day.quality == DayQuality.NO_DATA) return null
        val inProgress = day.date == input.today
        val label = when {
            inProgress -> "Today so far"
            day.date == input.today.minusDays(1) -> "Yesterday"
            else -> dayLabel(day.date)
        }
        val budget = day.budgetMs ?: input.currentBudgetMs
        val partial = day.partial
        val distracting = if (partial) "at least ${duration(day.distractingMs)}" else duration(day.distractingMs)
        val budgetPart = when {
            budget <= 0 -> null
            day.distractingMs > budget -> "${duration(day.distractingMs - budget)} over your ${duration(budget)} budget"
            partial -> "budget ${duration(budget)}"
            else -> "within your ${duration(budget)} budget"
        }
        val title = "$label · $distracting distracting" + (budgetPart?.let { ", $it" } ?: "")

        val lines = ArrayList<String>()
        val weekdayKind = if (isWeekend(day.date)) "weekend" else "weekday"
        lines += buildString {
            append("Active phone time ")
            if (partial) append("at least ")
            append(duration(day.activeMs))
            if (inProgress) append(" so far")
            if (!partial && day.baselineMedianMs != null) {
                append(" · a full $weekdayKind usually lands around ${duration(day.baselineMedianMs)}")
            }
            append('.')
        }
        if (!inProgress && !partial) {
            when (day.dayStatus) {
                DayStatus.GOOD_DAY -> lines += "A good day: lighter than your usual $weekdayKind."
                DayStatus.SPIKE -> lines += "A heavier day than your usual $weekdayKind."
                else -> Unit
            }
        }
        input.biggestRabbitHole?.let { lines += "Biggest rabbit hole: ${rabbitHoleText(it, withDay = false)}." }
        // A stretch measured across a collection gap could include unseen pickups: only whole days report it.
        if (!partial && day.longestFocusGapMs > 0) lines += "Longest phone-free stretch while awake: ${duration(day.longestFocusGapMs)}."
        exitsLine(input.exits)?.let { lines += it }

        val note = when {
            input.recordedThrough != null -> {
                val t = input.recordedThrough
                "Nothing has been recorded since ${shortDay(t.toLocalDate())} ${clock(t.toLocalTime())}, so these numbers are lower bounds and aren't compared with your usual."
            }
            partial -> "Collection had a gap this day, so these numbers are lower bounds and aren't compared with your usual."
            else -> null
        }
        return Digest(
            kind = DigestKind.DAILY,
            key = day.date.toString(),
            title = title,
            summary = lines.first(),
            lines = lines,
            coverageNote = note,
        )
    }

    /** Null when the week has no fully covered day: nothing it says could be trusted. */
    fun weekly(input: WeeklyDigestInput): Digest? {
        val covered = input.current.filter { it.covered }
        if (covered.isEmpty()) return null
        val priorCovered = input.previous.filter { it.covered }
        val perDay = covered.sumOf { it.distractingMs } / covered.size
        val priorPerDay = priorCovered.takeIf { it.isNotEmpty() }?.let { days -> days.sumOf { it.distractingMs } / days.size }
        val comparable = covered.size >= MIN_COMPARE_DAYS && priorCovered.size >= MIN_COMPARE_DAYS && priorPerDay != null

        val title = buildString {
            append("Your week · ${duration(perDay)} a day distracting")
            if (comparable) {
                val delta = perDay - priorPerDay!!
                append(
                    when {
                        delta <= -60_000L -> ", ${duration(-delta)} less than the week before"
                        delta >= 60_000L -> ", ${duration(delta)} more than the week before"
                        else -> ", about the same as the week before"
                    },
                )
            }
        }

        val lines = ArrayList<String>()
        val days = input.current.size
        lines += "Based on ${covered.size} of $days fully covered days."
        val good = covered.count { it.dayStatus == DayStatus.GOOD_DAY }
        val spikes = covered.count { it.dayStatus == DayStatus.SPIKE }
        if (good > 0 || spikes > 0) lines += "${plural(good, "good day")} · ${plural(spikes, "spike")}."
        val budgeted = covered.filter { (it.budgetMs ?: 0) > 0 }
        if (budgeted.isNotEmpty()) {
            val within = budgeted.count { it.distractingMs <= it.budgetMs!! }
            lines += "Within budget on $within of ${budgeted.size} covered days."
        }
        if (!comparable && priorCovered.isNotEmpty()) {
            lines += "Not enough fully covered days in both weeks for a fair week-over-week comparison."
        }
        input.biggestRabbitHole?.let { lines += "Biggest rabbit hole: ${rabbitHoleText(it, withDay = true)}." }
        covered.filter { it.longestFocusGapMs > 0 }.maxByOrNull { it.longestFocusGapMs }?.let {
            lines += "Longest phone-free stretch: ${duration(it.longestFocusGapMs)} (${shortDay(it.date)})."
        }
        exitsLine(input.exits)?.let { lines += it }

        val gaps = input.current.count { it.partial }
        val note = if (gaps > 0) "${plural(gaps, "day")} had collection gaps and ${if (gaps == 1) "is" else "are"} left out of the averages." else null
        return Digest(
            kind = DigestKind.WEEKLY,
            key = isoWeekKey(input.today),
            title = title,
            summary = lines.first(),
            lines = lines,
            coverageNote = note,
        )
    }

    private fun exitsLine(exits: DigestExits): String? {
        if (exits.exits <= 0) return null
        return "Launch pauses: ${plural(exits.exits, "immediate exit")}, ${exits.sustained} stayed away 10m+."
    }

    private fun rabbitHoleText(r: DigestRabbitHole, withDay: Boolean): String = buildString {
        append(duration(r.distractingMs))
        append(if (withDay) " on ${shortDay(r.date)} at ${clock(r.startLocal)}" else " at ${clock(r.startLocal)}")
        val chain = r.apps.take(MAX_CHAIN_APPS)
        if (chain.isNotEmpty()) {
            append(" · ")
            append(chain.joinToString(" → "))
            if (r.apps.size > MAX_CHAIN_APPS) append(" …")
        }
    }

    private const val MAX_CHAIN_APPS = 4

    private fun isWeekend(date: LocalDate) = date.dayOfWeek.value >= 6

    private fun dayLabel(date: LocalDate): String =
        "${date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)} ${date.dayOfMonth} ${date.month.getDisplayName(TextStyle.SHORT, Locale.US)}"

    private fun shortDay(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US)

    private fun clock(t: LocalTime): String = String.format(Locale.US, "%02d:%02d", t.hour, t.minute)

    private fun plural(n: Int, noun: String) = if (n == 1) "1 $noun" else "$n ${noun}s"

    /** Rounded like the app's headlines: "1h 5m", "23m", "<1m"; a confirmed zero is "0m". */
    fun duration(ms: Long): String {
        val totalMin = (ms.coerceAtLeast(0) + 30_000) / 60_000
        val h = totalMin / 60
        val m = totalMin % 60
        return when {
            h > 0 && m > 0 -> "${h}h ${m}m"
            h > 0 -> "${h}h"
            m > 0 -> "${m}m"
            ms <= 0 -> "0m"
            else -> "<1m"
        }
    }
}
