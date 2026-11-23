package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import com.celestiallemon.lagrange.data.db.UsageEventTypes as T

const val SEC = 1_000L
const val MIN = 60 * SEC

/** Package ids used throughout the reconstruction tests. */
object P {
    const val LAUNCHER = 1L
    const val SYSTEMUI = 2L
    const val INSTAGRAM = 3L
    const val YOUTUBE = 4L
    const val MAPS = 5L
    const val WHATSAPP = 6L
    const val CHROME = 7L
    const val DIALER = 8L
    const val PERMISSIONS = 9L
    const val REDDIT = 10L
    const val TIKTOK = 11L
    const val SETTINGS = 12L

    /** Chrome's Custom Tab activity class. */
    const val CCT_CLASS = 700L

    val apps: Map<Long, AppInfo> = listOf(
        AppInfo(LAUNCHER, "com.google.android.apps.nexuslauncher", AppCategory.NEUTRAL, isSystemSurface = true),
        AppInfo(SYSTEMUI, "com.android.systemui", AppCategory.NEUTRAL, isSystemSurface = true),
        AppInfo(INSTAGRAM, "com.instagram.android", AppCategory.DISTRACTING, false),
        AppInfo(YOUTUBE, "com.google.android.youtube", AppCategory.DISTRACTING, false),
        AppInfo(MAPS, "com.google.android.apps.maps", AppCategory.UTILITY, false),
        AppInfo(WHATSAPP, "com.whatsapp", AppCategory.NEUTRAL, false),
        AppInfo(CHROME, "com.android.chrome", AppCategory.NEUTRAL, false),
        AppInfo(DIALER, "com.google.android.dialer", AppCategory.UTILITY, false),
        AppInfo(PERMISSIONS, "com.google.android.permissioncontroller", AppCategory.NEUTRAL, isSystemSurface = true),
        AppInfo(REDDIT, "com.reddit.frontpage", AppCategory.DISTRACTING, false),
        AppInfo(TIKTOK, "com.zhiliaoapp.musically", AppCategory.DISTRACTING, false),
        AppInfo(SETTINGS, "com.android.settings", AppCategory.UTILITY, false),
    ).associateBy { it.pkgId }
}

/** Builds a usage ledger the way Android logs it: screen and keyguard transitions plus activity resume/pause pairs. */
class Ledger(val origin: Long = 1_800_000_000_000L) {
    val events = ArrayList<UsageEvent>()
    val notifications = ArrayList<NotificationMeta>()
    val friction = ArrayList<FrictionMeta>()
    val coverage = ArrayList<CoverageSpan>()
    val customTabs = HashSet<Long>()
    private var order = 0L
    private var notifId = 0L

    fun at(offsetMs: Long) = origin + offsetMs

    fun ev(t: Long, type: Int, pkg: Long = 0, cls: Long = 0) = apply { events += UsageEvent(at(t), type, pkg, cls, ++order) }

    fun screenOn(t: Long) = ev(t, T.SCREEN_INTERACTIVE)
    fun screenOff(t: Long) = ev(t, T.SCREEN_NON_INTERACTIVE)
    fun unlock(t: Long) = ev(t, T.KEYGUARD_HIDDEN)
    fun lock(t: Long) = ev(t, T.KEYGUARD_SHOWN)
    fun resume(t: Long, pkg: Long, cls: Long = pkg * 10) = ev(t, T.ACTIVITY_RESUMED, pkg, cls)
    fun pause(t: Long, pkg: Long, cls: Long = pkg * 10) = ev(t, T.ACTIVITY_PAUSED, pkg, cls)

    /** One app in the foreground for `[from, to)`. */
    fun use(pkg: Long, from: Long, to: Long, cls: Long = pkg * 10) = resume(from, pkg, cls).pause(to, pkg, cls)

    /** A complete pickup: screen on, unlock, the given apps back to back, lock and screen off. */
    fun session(on: Long, vararg apps: Pair<Long, Long>, off: Long? = null): Ledger {
        screenOn(on)
        unlock(on + SEC)
        var t = on + 2 * SEC
        for ((pkg, dur) in apps) {
            use(pkg, t, t + dur)
            t += dur
        }
        val end = off ?: (t + SEC)
        lock(end)
        screenOff(end)
        return this
    }

    /** Screen on and off on the lockscreen only. */
    fun glance(on: Long, durMs: Long = 4 * SEC) = apply {
        lock(on)
        screenOn(on)
        screenOff(on + durMs)
    }

    fun notification(
        pkg: Long,
        postedAt: Long,
        removedAt: Long? = null,
        reason: RemovalReason = if (removedAt == null) RemovalReason.ACTIVE else RemovalReason.CANCEL,
        importance: Int = 4,
        ongoing: Boolean = false,
        dnd: Boolean = false,
    ): Long {
        val id = ++notifId
        notifications += NotificationMeta(
            id = id, pkgId = pkg, postedAtMs = at(postedAt), removedAtMs = removedAt?.let(::at), removalReason = reason,
            importance = importance, isOngoing = ongoing, isForegroundService = false, isGroupSummary = false,
            isAmbient = false, matchedInterruptionFilter = true, dndOrSilentAtPost = dnd,
        )
        return id
    }

    fun blocked(pkg: Long, t: Long, pauseSec: Int = 5, outcome: String = "CLOSED_GO_HOME", reactionMs: Long? = 800) = apply {
        friction += FrictionMeta(at(t), pkg, "LAUNCH_PAUSE", pauseSec, reactionMs, outcome)
    }

    fun listener(from: Long, to: Long) = apply { coverage += CoverageSpan(ServiceKind.NOTIFICATION_LISTENER, CoverageStatus.ACTIVE, at(from), at(to)) }
    fun usageCoverage(from: Long, to: Long, status: CoverageStatus = CoverageStatus.ACTIVE) =
        apply { coverage += CoverageSpan(ServiceKind.USAGE_HARVEST, status, at(from), at(to)) }

    fun input(dataEnd: Long? = null, apps: Map<Long, AppInfo> = P.apps) = ReconstructionInput(
        events = events.toList(),
        apps = apps,
        customTabClassIds = customTabs.toSet(),
        notifications = notifications.toList(),
        friction = friction.toList(),
        coverage = coverage.toList(),
        dataEndMs = dataEnd?.let(::at) ?: (events.maxOf { it.timestampMs } + MIN),
    )

    fun reconstruct(dataEnd: Long? = null, rules: ReconstructionRules = ReconstructionRules()) =
        SessionReconstructor.reconstruct(input(dataEnd), rules)
}

val Episode.unlocked get() = !isGlanceOnly

fun List<Episode>.unlocked() = filter { !it.isGlanceOnly }
