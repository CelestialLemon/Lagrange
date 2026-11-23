package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.AppMeta
import com.celestiallemon.lagrange.domain.DateRange
import com.celestiallemon.lagrange.domain.TrendWindow
import com.celestiallemon.lagrange.domain.WindowKind
import java.time.LocalDate

// Behavior Lab (spec §4.2 C, §5.4, §6). Plain Kotlin over rows the repository reads from the derived and raw tables,
// so every ranking and every "is there enough data to say this?" rule runs (and is tested) on the JVM.
//
// The rule shared with Trends: a statistic is only computed over what was actually observed. Days with a harvest gap,
// days still in progress and periods the notification listener wasn't recording never count as zero; they are left out
// and the sample that remains is shown next to the number.

/** The windows the Lab offers (a subset of Trends' horizons). */
object LabWindows {
    val D7 = TrendWindow(WindowKind.D7)
    val D30 = TrendWindow(WindowKind.D30)
    val D90 = TrendWindow(WindowKind.D90)
    val ALL = TrendWindow(WindowKind.ALL)
    val entries = listOf(D7, D30, D90, ALL)
    val DEFAULT = D30
}

enum class LabSection(val label: String) {
    SPIKES("Spikes"),
    RABBIT_HOLES("Rabbit holes"),
    GATEWAYS("Gateways"),
    ALERTS("Alerts"),
    RHYTHM("Rhythm"),
    FRICTION("Friction"),
}

/** One `session_segments` row, as far as the Lab cares. [durationMs] is real use (launch-pause time scrubbed). */
data class LabSegment(
    val pkgId: Long,
    val seqIndex: Int,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val isBlockedAttempt: Boolean = false,
    val triggerEvidence: TriggerEvidence? = null,
    val triggerPkgId: Long? = null,
)

/** An unlocked episode (glances never reach the Lab) with its segments in order. */
data class LabEpisode(
    val id: Long,
    val episodeKey: String,
    val logicalDate: LocalDate,
    val startMs: Long,
    val endMs: Long,
    val activeDurationMs: Long,
    val totalDistractingMs: Long,
    val triggerEvidence: TriggerEvidence,
    val triggerPkgId: Long? = null,
    val triggerNotificationId: Long? = null,
    val entryPkgId: Long? = null,
    val isRabbitHole: Boolean = false,
    val rabbitHoleReasons: List<RabbitHoleReason> = emptyList(),
    val worthItRating: Int? = null,
    val inProgress: Boolean = false,
    val segments: List<LabSegment> = emptyList(),
)

/**
 * Current app metadata. Categories apply retroactively (spec §4.1.5): a recategorized app changes every Lab figure
 * at once. Unknown packages read as NEUTRAL, never as distracting.
 */
class LabApps(private val meta: Map<Long, AppMeta>) {
    fun category(pkgId: Long): AppCategory = meta[pkgId]?.category ?: AppCategory.NEUTRAL
    fun isTransient(pkgId: Long): Boolean = meta[pkgId]?.isSystemSurface == true
    fun isDistracting(pkgId: Long): Boolean = category(pkgId) == AppCategory.DISTRACTING
    fun meta(pkgId: Long): AppMeta? = meta[pkgId]
    fun name(pkgId: Long): String = meta[pkgId]?.displayName ?: "App #$pkgId"
    fun packageName(pkgId: Long): String? = meta[pkgId]?.packageName

    /** Real use of a real app: not a blocked launch attempt, not a system surface, not empty. */
    fun isReal(s: LabSegment): Boolean = !s.isBlockedAttempt && !isTransient(s.pkgId) && s.durationMs > 0

    fun realSegments(e: LabEpisode): List<LabSegment> = e.segments.sortedBy { it.seqIndex }.filter(::isReal)

    /** The app the episode really started in (first real segment), which may differ from a blocked first attempt. */
    fun entryOf(e: LabEpisode): Long? = realSegments(e).firstOrNull()?.pkgId

    /** The visible hop chain: real apps in order with consecutive repeats collapsed. */
    fun hopChain(e: LabEpisode): List<Long> {
        val out = ArrayList<Long>()
        for (s in realSegments(e)) if (out.lastOrNull() != s.pkgId) out += s.pkgId
        return out
    }

    companion object {
        val EMPTY = LabApps(emptyMap())
    }
}

/** How much a Lab section can say: the window, how many of its days were covered, and the instants it spans. */
data class LabCoverage(
    val range: DateRange,
    val coveredDays: Int,
    val totalDays: Int,
    /** Hours of notification-listener coverage inside the window (null for sections that don't use it). */
    val notificationCoveredMs: Long? = null,
)

internal fun medianLong(values: List<Long>): Long? {
    if (values.isEmpty()) return null
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
}
