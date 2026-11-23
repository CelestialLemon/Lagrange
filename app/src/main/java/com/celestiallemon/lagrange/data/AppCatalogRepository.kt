package com.celestiallemon.lagrange.data

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.PackageEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/** One app as shown in the categorization review. */
data class CatalogApp(
    val packageName: String,
    val displayName: String,
    val category: AppCategory,
    val confirmed: Boolean,
    val recentOpens: Long,
)

/** The app list used for categorization: every launchable app plus anything already seen in usage events. */
class AppCatalogRepository(
    private val db: LagrangeDatabase,
    private val interner: Interner,
    private val launchablePackages: () -> List<String>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** Makes sure every launchable app has a row (with a suggested category) so it can be reviewed. */
    suspend fun syncLaunchable() {
        interner.packageIds(launchablePackages())
    }

    fun observeReviewList(recentOpens: Flow<Map<Long, Long>> = flowOf(emptyMap())): Flow<List<CatalogApp>> =
        combine(db.packageDao().observeAll(), recentOpens) { rows, opens ->
            rows.asSequence()
                .filterNot { it.isLauncherOrSystemUi || it.packageName.isEmpty() }
                .filter { it.isInstalled }
                .map { it.toCatalogApp(opens[it.id] ?: 0) }
                .sortedWith(compareByDescending<CatalogApp> { it.recentOpens }.thenBy { it.displayName.lowercase() })
                .toList()
        }

    suspend fun recentOpens(sinceMs: Long): Map<Long, Long> =
        db.rawUsageEventDao().resumeCountsSince(sinceMs).associate { it.pkgId to it.resumeCount }

    suspend fun setCategory(packageName: String, category: AppCategory) {
        db.packageDao().setCategory(packageName, category.name, clock())
    }

    /** Accepts the current (suggested or edited) category of every listed app as the user's choice. */
    suspend fun confirmAll(packageNames: Collection<String>) = db.withWriteTransaction {
        val ids = packageNames.mapNotNull { db.packageDao().idOf(it) }
        ids.chunked(500).forEach { db.packageDao().confirm(it, clock()) }
    }

    private fun PackageEntity.toCatalogApp(opens: Long) = CatalogApp(
        packageName = packageName,
        displayName = displayName,
        category = runCatching { AppCategory.valueOf(category) }.getOrDefault(AppCategory.NEUTRAL),
        confirmed = categoryConfirmed,
        recentOpens = opens,
    )
}
