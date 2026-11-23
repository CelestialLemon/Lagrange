package com.celestiallemon.lagrange.domain

import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.CoverageStatus
import com.celestiallemon.lagrange.data.db.RemovalReason
import com.celestiallemon.lagrange.data.db.ServiceKind
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Bumped whenever reconstruction or rollup rules change, so every derived row is rebuilt once on upgrade. */
object DerivedAlgorithm {
    const val VERSION = 1
}

/**
 * One logical day of real ledger data plus what Lagrange reconstructed from it, exported from the Debug Timeline
 * Inspector. Fixtures replay on the JVM (`FixtureReplayTest`), so a real Pixel 7a timeline becomes a regression test.
 * Metadata only: package names and timings, never notification content.
 */
@Serializable
data class DayFixture(
    val formatVersion: Int = FORMAT_VERSION,
    val algorithmVersion: Int,
    val logicalDate: String,
    val zoneId: String,
    val rolloverHour: Int,
    val windowStartMs: Long,
    val windowEndMs: Long,
    val dataEndMs: Long,
    val apps: List<FixtureApp>,
    val classes: List<FixtureClass>,
    val customTabClassIds: List<Long>,
    val events: List<FixtureEvent>,
    val notifications: List<FixtureNotification>,
    val friction: List<FixtureFriction>,
    val coverage: List<FixtureCoverage>,
    /** Episodes starting inside the window, as reconstructed when exported. */
    val expected: List<FixtureEpisode>,
) {
    fun toInput(): ReconstructionInput = ReconstructionInput(
        events = events.map { UsageEvent(it.t, it.type, it.pkg, it.cls, it.order) },
        apps = apps.associate { it.id to AppInfo(it.id, it.packageName, AppCategory.valueOf(it.category), it.systemSurface) },
        customTabClassIds = customTabClassIds.toSet(),
        notifications = notifications.map {
            NotificationMeta(
                id = it.id, pkgId = it.pkg, postedAtMs = it.postedAtMs, removedAtMs = it.removedAtMs,
                removalReason = RemovalReason.valueOf(it.removalReason), importance = it.importance, isOngoing = it.ongoing,
                isForegroundService = it.foregroundService, isGroupSummary = it.groupSummary, isAmbient = it.ambient,
                matchedInterruptionFilter = it.matchedInterruptionFilter, dndOrSilentAtPost = it.dndOrSilent,
            )
        },
        friction = friction.map { FrictionMeta(it.t, it.pkg, it.kind, it.pauseSec, it.reactionMs, it.outcome) },
        coverage = coverage.map { CoverageSpan(ServiceKind.valueOf(it.kind), CoverageStatus.valueOf(it.status), it.startMs, it.endMs) },
        dataEndMs = dataEndMs,
    )

    /** Reconstructs the fixture and returns the episodes starting inside its window, in the fixture's shape. */
    fun replay(rules: ReconstructionRules = ReconstructionRules()): List<FixtureEpisode> =
        SessionReconstructor.reconstruct(toInput(), rules)
            .filter { it.startMs >= windowStartMs && it.startMs < windowEndMs }
            .map(FixtureEpisode::of)

    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        const val FORMAT_VERSION = 1
        val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun decode(text: String): DayFixture = json.decodeFromString(serializer(), text)
    }
}

@Serializable
data class FixtureApp(val id: Long, val packageName: String, val category: String, val systemSurface: Boolean)

@Serializable
data class FixtureClass(val id: Long, val name: String)

@Serializable
data class FixtureEvent(val t: Long, val type: Int, val pkg: Long, val cls: Long, val order: Long)

@Serializable
data class FixtureNotification(
    val id: Long,
    val pkg: Long,
    val postedAtMs: Long,
    val removedAtMs: Long?,
    val removalReason: String,
    val importance: Int,
    val ongoing: Boolean,
    val foregroundService: Boolean,
    val groupSummary: Boolean,
    val ambient: Boolean,
    val matchedInterruptionFilter: Boolean,
    val dndOrSilent: Boolean,
)

@Serializable
data class FixtureFriction(val t: Long, val pkg: Long, val kind: String, val pauseSec: Int, val reactionMs: Long?, val outcome: String)

@Serializable
data class FixtureCoverage(val kind: String, val status: String, val startMs: Long, val endMs: Long)

@Serializable
data class FixtureSegment(
    val pkg: Long,
    val startMs: Long,
    val endMs: Long,
    val durationMs: Long,
    val blockedMs: Long,
    val blocked: Boolean,
    val trigger: String?,
)

@Serializable
data class FixtureEpisode(
    val key: String,
    val startMs: Long,
    val endMs: Long,
    val activeDurationMs: Long,
    val pickups: Int,
    val glance: Boolean,
    val trigger: String,
    val entryPkg: Long?,
    val archetype: String,
    val rabbitHoleReasons: List<String>,
    val segments: List<FixtureSegment>,
) {
    companion object {
        fun of(e: Episode) = FixtureEpisode(
            key = e.episodeKey,
            startMs = e.startMs,
            endMs = e.endMs,
            activeDurationMs = e.activeDurationMs,
            pickups = e.physicalPickupCount,
            glance = e.isGlanceOnly,
            trigger = e.triggerEvidence.name,
            entryPkg = e.entryPkgId,
            archetype = e.archetype.name,
            rabbitHoleReasons = e.rabbitHoleReasons.map { it.name },
            segments = e.segments.map {
                FixtureSegment(it.pkgId, it.startMs, it.endMs, it.durationMs, it.blockedMs, it.isBlockedAttempt, it.triggerEvidence?.name)
            },
        )
    }
}
