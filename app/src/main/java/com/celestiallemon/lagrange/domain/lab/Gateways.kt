package com.celestiallemon.lagrange.domain.lab

import com.celestiallemon.lagrange.domain.MINUTE_MS

// Gateway Apps (spec §4.2 C): which app, once opened, most often leads somewhere else distracting. Small samples are
// pulled toward the overall rate (empirical-Bayes shrinkage) so one unlucky afternoon can't crown a gateway.

data class GatewayRules(
    /** Apps opened fewer times than this are listed apart, never ranked. */
    val minEntries: Int = 5,
    /** Prior weight m in (k + m·p0) / (n + m). */
    val priorWeight: Double = 5.0,
    /** An episode spirals when it is a rabbit hole or reaches this much time in *other* distracting apps. */
    val spiralDownstreamMs: Long = 15 * MINUTE_MS,
)

data class GatewayRow(
    val pkgId: Long,
    /** Episodes that started in this app (n). */
    val entries: Int,
    /** Of those, episodes that spiraled (k). */
    val spirals: Int,
    /** Raw k / n. */
    val rawRate: Double,
    /** (k + m·p0) / (n + m). */
    val shrunkRate: Double,
    /** Average distracting time in other apps after entering here, per entry. */
    val avgDownstreamMs: Long,
)

data class GatewayReport(
    /** Apps with n ≥ minEntries, highest shrunk rate first. */
    val ranked: List<GatewayRow>,
    /** Apps with too few entries to rank, by entries. */
    val lowSample: List<GatewayRow>,
    /** Global base rate p0 = K / N over every counted episode. */
    val baseRate: Double,
    val totalEntries: Int,
    val totalSpirals: Int,
    val rules: GatewayRules,
)

object GatewayAnalyzer {
    /** Distracting time in apps other than the entry app (real use only). */
    fun downstreamMs(e: LabEpisode, entry: Long, apps: LabApps): Long =
        apps.realSegments(e).filter { it.pkgId != entry && apps.isDistracting(it.pkgId) }.sumOf { it.durationMs }

    fun spiraled(e: LabEpisode, entry: Long, apps: LabApps, rules: GatewayRules = GatewayRules()): Boolean =
        e.isRabbitHole || downstreamMs(e, entry, apps) >= rules.spiralDownstreamMs

    /** (k + m·p0) / (n + m); with no data at all it is just the prior. */
    fun shrink(k: Int, n: Int, p0: Double, m: Double): Double = if (n + m <= 0.0) p0 else (k + m * p0) / (n + m)

    /**
     * [episodes] should be settled, unlocked episodes from covered days only (the repository filters); episodes still
     * in progress are skipped here too, since they can still spiral.
     */
    fun analyze(episodes: List<LabEpisode>, apps: LabApps, rules: GatewayRules = GatewayRules()): GatewayReport {
        data class Acc(var n: Int = 0, var k: Int = 0, var downstream: Long = 0)
        val acc = HashMap<Long, Acc>()
        for (e in episodes) {
            if (e.inProgress) continue
            val entry = apps.entryOf(e) ?: continue
            val a = acc.getOrPut(entry) { Acc() }
            a.n++
            if (spiraled(e, entry, apps, rules)) a.k++
            a.downstream += downstreamMs(e, entry, apps)
        }
        val n = acc.values.sumOf { it.n }
        val k = acc.values.sumOf { it.k }
        val p0 = if (n == 0) 0.0 else k.toDouble() / n
        val rows = acc.map { (pkg, a) ->
            GatewayRow(pkg, a.n, a.k, a.k.toDouble() / a.n, shrink(a.k, a.n, p0, rules.priorWeight), a.downstream / a.n)
        }
        val (big, small) = rows.partition { it.entries >= rules.minEntries }
        return GatewayReport(
            ranked = big.sortedWith(compareByDescending<GatewayRow> { it.shrunkRate }.thenByDescending { it.entries }.thenBy { it.pkgId }),
            lowSample = small.sortedWith(compareByDescending<GatewayRow> { it.entries }.thenByDescending { it.spirals }.thenBy { it.pkgId }),
            baseRate = p0,
            totalEntries = n,
            totalSpirals = k,
            rules = rules,
        )
    }
}
