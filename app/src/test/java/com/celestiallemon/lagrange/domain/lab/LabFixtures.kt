package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.RabbitHoleReason
import com.celestiallemon.lagrange.data.db.TriggerEvidence
import com.celestiallemon.lagrange.domain.AppMeta
import com.celestiallemon.lagrange.domain.MINUTE_MS
import java.time.LocalDate

const val MIN = MINUTE_MS

const val INSTA = 1L
const val TIKTOK = 2L
const val YOUTUBE = 3L
const val MAPS = 4L
const val KINDLE = 5L
const val LAUNCHER = 9L

val testApps = LabApps(
    mapOf(
        INSTA to AppMeta(INSTA, "com.instagram.android", "Instagram", AppCategory.DISTRACTING, null, false),
        TIKTOK to AppMeta(TIKTOK, "com.zhiliaoapp.musically", "TikTok", AppCategory.DISTRACTING, null, false),
        YOUTUBE to AppMeta(YOUTUBE, "com.google.android.youtube", "YouTube", AppCategory.DISTRACTING, null, false),
        MAPS to AppMeta(MAPS, "com.google.android.apps.maps", "Maps", AppCategory.UTILITY, null, false),
        KINDLE to AppMeta(KINDLE, "com.amazon.kindle", "Kindle", AppCategory.NEUTRAL, null, false),
        LAUNCHER to AppMeta(LAUNCHER, "com.android.launcher", "Launcher", AppCategory.NEUTRAL, null, true),
    ),
)

private var nextId = 1L

/** An episode made of back-to-back segments `(pkg, minutes)` starting at [startMs]. */
fun episode(
    startMs: Long,
    vararg segs: Pair<Long, Int>,
    date: LocalDate = LocalDate.of(2026, 11, 2),
    rabbit: Boolean = false,
    reasons: List<RabbitHoleReason> = emptyList(),
    trigger: TriggerEvidence = TriggerEvidence.NO_OBSERVED_TRIGGER,
    triggerNotificationId: Long? = null,
    rating: Int? = null,
    inProgress: Boolean = false,
    key: String? = null,
    blockedFirst: Long? = null,
): LabEpisode {
    val id = nextId++
    var t = startMs
    val out = ArrayList<LabSegment>()
    if (blockedFirst != null) out += LabSegment(blockedFirst, 0, t, t + 5_000, 0, isBlockedAttempt = true).also { t += 5_000 }
    segs.forEach { (pkg, min) ->
        val d = min * MIN
        out += LabSegment(pkg, out.size, t, t + d, d)
        t += d
    }
    val active = out.sumOf { it.durationMs }
    val distracting = out.filter { testApps.isDistracting(it.pkgId) }.sumOf { it.durationMs }
    return LabEpisode(
        id = id,
        episodeKey = key ?: "ep-$id",
        logicalDate = date,
        startMs = startMs,
        endMs = t,
        activeDurationMs = active,
        totalDistractingMs = distracting,
        triggerEvidence = trigger,
        triggerNotificationId = triggerNotificationId,
        entryPkgId = out.firstOrNull { !it.isBlockedAttempt }?.pkgId,
        isRabbitHole = rabbit,
        rabbitHoleReasons = reasons,
        worthItRating = rating,
        inProgress = inProgress,
        segments = out,
    )
}
