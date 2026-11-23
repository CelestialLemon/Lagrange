package com.celestiallemon.lagrange.domain.widget

import com.celestiallemon.lagrange.data.db.DayCoverage
import com.celestiallemon.lagrange.data.db.DayStatus
import com.celestiallemon.lagrange.domain.digest.DigestComposer

/** What the home-screen widget needs to know, gathered by the app (all wall-clock times are epoch ms). */
data class WidgetInput(
    val nowMs: Long,
    val hasUsageAccess: Boolean,
    /** Today's rollup exists (the first harvest and rebuild have landed). */
    val hasRollup: Boolean,
    val distractingMs: Long = 0,
    val budgetMs: Long = 0,
    val dayCoverage: DayCoverage? = null,
    val dayStatus: DayStatus? = null,
    /** Start of the latest unlocked episode, if it was still in progress at the last rebuild. */
    val inProgressStartMs: Long? = null,
    /** End of the latest finished unlocked episode today. */
    val lastEndedMs: Long? = null,
    val longestFocusGapMs: Long = 0,
    val lastHarvestAtMs: Long? = null,
    val frictionEnabled: Boolean = false,
    val accessibilityConnected: Boolean = false,
    val focusBoostUntilMs: Long? = null,
    val snoozeUntilMs: Long? = null,
)

enum class WidgetStatus { ON_TRACK, NEAR_BUDGET, OVER_BUDGET, GRANT_ACCESS, COVERAGE_GAP, WAITING }

enum class WidgetBadgeKind { FOCUS_BOOST, SNOOZED, SET_UP_FRICTION }

data class WidgetBadge(val kind: WidgetBadgeKind, val text: String)

/** Everything the widget draws, as text and one progress fraction; no Android types, so it is unit-testable. */
data class WidgetState(
    val status: WidgetStatus,
    val statusLabel: String,
    /** Big number, e.g. "42m" or "≥ 42m" when collection had a gap. */
    val usedText: String,
    /** "of 1h budget" */
    val budgetText: String,
    /** 0..1, clamped; the bar is full once over budget. */
    val progress: Float,
    val streakLabel: String,
    val streakText: String,
    val longestText: String,
    val badge: WidgetBadge?,
    /** When the state goes stale on its own (a boost or snooze ending); the refresher re-renders then. */
    val nextChangeAtMs: Long? = null,
) {
    companion object {
        val LOADING = WidgetState(
            status = WidgetStatus.WAITING,
            statusLabel = "Starting up",
            usedText = "—",
            budgetText = "",
            progress = 0f,
            streakLabel = "Phone-free",
            streakText = "—",
            longestText = "—",
            badge = null,
        )
    }
}

/**
 * Maps today's numbers to the widget. The widget can't tick (it is redrawn on rollups and state changes, not every
 * minute), so time-relative facts are shown as clock times ("since 14:05", "until 15:42") that stay true between
 * redraws, and coverage problems are named instead of rendering a confident 0m.
 */
object WidgetStateCalculator {
    /** "Near budget" from this share of the budget onward. */
    const val NEAR_BUDGET_RATIO = 0.8
    private const val STALE_HARVEST_MS = DigestComposer.EVENING_THRESHOLD_MS * 4 // 48h, as the coverage alert

    fun compute(input: WidgetInput, clock: (Long) -> String): WidgetState {
        val badge = badge(input, clock)
        val nextChange = listOfNotNull(
            input.focusBoostUntilMs?.takeIf { it > input.nowMs },
            input.snoozeUntilMs?.takeIf { it > input.nowMs },
        ).minOrNull()
        val (streakLabel, streakText) = streak(input, clock)
        val longest = if (input.hasRollup && input.longestFocusGapMs > 0) DigestComposer.duration(input.longestFocusGapMs) else "—"
        val budget = input.budgetMs.coerceAtLeast(0)
        val budgetText = if (budget > 0) "of ${DigestComposer.duration(budget)} budget" else "no budget set"

        if (!input.hasUsageAccess) {
            return WidgetState(WidgetStatus.GRANT_ACCESS, "Grant access", "—", "Usage Access is off", 0f, streakLabel, "—", "—", badge, nextChange)
        }
        val harvestStale = input.lastHarvestAtMs != null && input.nowMs - input.lastHarvestAtMs > STALE_HARVEST_MS
        if (!input.hasRollup) {
            val status = if (harvestStale) WidgetStatus.COVERAGE_GAP else WidgetStatus.WAITING
            val label = if (harvestStale) "Coverage gap" else "Waiting for data"
            return WidgetState(status, label, "—", budgetText, 0f, streakLabel, "—", "—", badge, nextChange)
        }
        val gap = harvestStale ||
            input.dayCoverage == DayCoverage.PARTIAL_HARVEST ||
            input.dayStatus == DayStatus.UNCERTAIN_COVERAGE
        val used = input.distractingMs.coerceAtLeast(0)
        val ratio = if (budget > 0) used.toDouble() / budget else 0.0
        val status = when {
            // Over is over even with a gap: the recorded part alone already passed the budget.
            budget > 0 && used > budget -> WidgetStatus.OVER_BUDGET
            gap -> WidgetStatus.COVERAGE_GAP
            budget > 0 && ratio >= NEAR_BUDGET_RATIO -> WidgetStatus.NEAR_BUDGET
            else -> WidgetStatus.ON_TRACK
        }
        val label = when (status) {
            WidgetStatus.OVER_BUDGET -> "Over by ${DigestComposer.duration(used - budget)}"
            WidgetStatus.COVERAGE_GAP -> "Coverage gap"
            WidgetStatus.NEAR_BUDGET -> "Near budget · ${DigestComposer.duration(budget - used)} left"
            else -> if (budget > 0) "On track · ${DigestComposer.duration(budget - used)} left" else "On track"
        }
        val usedText = (if (gap) "≥ " else "") + DigestComposer.duration(used)
        return WidgetState(
            status = status,
            statusLabel = label,
            usedText = usedText,
            budgetText = budgetText,
            progress = ratio.coerceIn(0.0, 1.0).toFloat(),
            streakLabel = streakLabel,
            streakText = streakText,
            longestText = longest,
            badge = badge,
            nextChangeAtMs = nextChange,
        )
    }

    /**
     * The honest "current" stretch: while an episode is in progress it is the phone-free gap that ended with this
     * pickup; otherwise the phone has been down since the last episode ended (shown as a clock time).
     */
    private fun streak(input: WidgetInput, clock: (Long) -> String): Pair<String, String> {
        val inProgress = input.inProgressStartMs
        val ended = input.lastEndedMs
        return when {
            !input.hasRollup -> "Phone-free" to "—"
            inProgress != null && ended != null && inProgress > ended -> "Before this pickup" to DigestComposer.duration(inProgress - ended)
            inProgress == null && ended != null -> "Phone-free since" to clock(ended)
            else -> "Phone-free" to "—"
        }
    }

    private fun badge(input: WidgetInput, clock: (Long) -> String): WidgetBadge? {
        val boost = input.focusBoostUntilMs?.takeIf { it > input.nowMs }
        val snooze = input.snoozeUntilMs?.takeIf { it > input.nowMs }
        return when {
            !input.frictionEnabled -> null
            !input.accessibilityConnected -> WidgetBadge(WidgetBadgeKind.SET_UP_FRICTION, "Friction paused · set up")
            boost != null -> WidgetBadge(WidgetBadgeKind.FOCUS_BOOST, "Focus Boost until ${clock(boost)}")
            snooze != null -> WidgetBadge(WidgetBadgeKind.SNOOZED, "Snoozed until ${clock(snooze)}")
            else -> null
        }
    }
}

/** The Quick Settings tile's look for one moment. */
data class TileModel(val active: Boolean, val unavailable: Boolean, val subtitle: String)

enum class TileAction { ACTIVATE_BOOST, CANCEL_BOOST, OPEN_SETUP }

/**
 * "Focus Boost" tile: one tap starts the 1-hour boost (cancelling any snooze), a tap while it runs ends it, and
 * without a connected accessibility service the tile opens friction setup instead of pretending to work.
 */
object FocusBoostTileModel {
    fun model(frictionReady: Boolean, boostRemainingMs: Long, snoozeRemainingMs: Long): TileModel = when {
        !frictionReady -> TileModel(active = false, unavailable = false, subtitle = "Set up friction")
        boostRemainingMs > 0 -> TileModel(active = true, unavailable = false, subtitle = "1h · ${remaining(boostRemainingMs)} left")
        snoozeRemainingMs > 0 -> TileModel(active = false, unavailable = false, subtitle = "Snoozed")
        else -> TileModel(active = false, unavailable = false, subtitle = "Off")
    }

    fun onClick(frictionReady: Boolean, boostRemainingMs: Long): TileAction = when {
        !frictionReady -> TileAction.OPEN_SETUP
        boostRemainingMs > 0 -> TileAction.CANCEL_BOOST
        else -> TileAction.ACTIVATE_BOOST
    }

    /** Whole minutes, rounded up, so a fresh boost reads "60m" and the last seconds read "1m", never "0m". */
    private fun remaining(ms: Long): String {
        val minutes = (ms + 59_999) / 60_000
        return if (minutes > 60) "${minutes / 60}h${if (minutes % 60 > 0) " ${minutes % 60}m" else ""}" else "${minutes}m"
    }
}
