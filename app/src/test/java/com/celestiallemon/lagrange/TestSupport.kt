package com.celestiallemon.lagrange

import com.celestiallemon.lagrange.data.CategorySuggester
import com.celestiallemon.lagrange.data.PackageMeta
import com.celestiallemon.lagrange.data.PackageMetaResolver
import com.celestiallemon.lagrange.harvest.RawUsageEvent
import com.celestiallemon.lagrange.harvest.UsageEventSource

/** Resolves every package to a predictable label and records how often it was asked. */
class FakeResolver : PackageMetaResolver {
    val calls = mutableListOf<String>()

    override fun resolve(packageName: String): PackageMeta {
        calls += packageName
        val systemUi = packageName == "com.android.systemui" || packageName.isEmpty()
        return PackageMeta(
            displayName = "Label($packageName)",
            suggestedCategory = CategorySuggester.suggest(packageName, "com.celestiallemon.lagrange", systemUi, null),
            isLauncherOrSystemUi = systemUi,
            isInstalled = true,
            installedAtMs = null,
        )
    }
}

/** An in-memory stand-in for `UsageStatsManager`. */
class FakeUsageEventSource(events: List<RawUsageEvent> = emptyList()) : UsageEventSource {
    val events = events.toMutableList()
    var access = true
    var unlocked = true
    var returnNull = false
    /** When set, queries whose begin is at or after this instant return null (simulates a mid-run failure). */
    var failFromMs: Long? = null
    val queries = mutableListOf<Pair<Long, Long>>()

    override fun hasUsageAccess() = access
    override fun isUserUnlocked() = unlocked

    override fun query(beginMs: Long, endMs: Long): Sequence<RawUsageEvent>? {
        queries += beginMs to endMs
        if (returnNull) return null
        failFromMs?.let { if (beginMs >= it) return null }
        return events.filter { it.timestampMs in beginMs until endMs }.sortedBy { it.timestampMs }.asSequence()
    }
}

class MutableClock(var now: Long) {
    val fn: () -> Long = { now }
}

const val HOUR = 3_600_000L
const val DAY = 24 * HOUR
