package com.celestiallemon.lagrange.data

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.AppCategory
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.PackageEntity
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
import com.celestiallemon.lagrange.domain.friction.AppFrictionOverride
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject

/** One app as shown in the categorization review and Friction tab. */
data class CatalogApp(
    val packageName: String,
    val displayName: String,
    val category: AppCategory,
    val confirmed: Boolean,
    val recentOpens: Long,
    val frictionOverride: AppFrictionOverride? = null,
) {
    /** Whether friction will fire on this app when master friction is enabled. */
    val isFrictionEligible: Boolean
        get() = when (category) {
            AppCategory.DISTRACTING -> frictionOverride?.enabled ?: confirmed
            AppCategory.NEUTRAL -> frictionOverride?.enabled ?: false
            AppCategory.UTILITY, AppCategory.EXCLUDED, AppCategory.SELF_APP -> false
        }
}

/** The app list used for categorization: every launchable app plus anything already seen in usage events. */
class AppCatalogRepository(
    private val db: LagrangeDatabase,
    private val interner: Interner,
    private val launchablePackages: () -> List<String>,
    private val resolver: PackageMetaResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Makes sure every launchable app has a row (with a suggested category) so it can be reviewed, and refreshes
     * the installed flag of apps seen before (uninstalled apps drop out of the review; reinstalled ones return).
     * Calls PackageManager: run it off the main thread.
     */
    suspend fun syncLaunchable() {
        val launchable = launchablePackages().toSet()
        interner.packageIds(launchable)
        val now = clock()
        val dao = db.packageDao()
        val changed = dao.all().mapNotNull { row ->
            if (row.packageName.isEmpty()) return@mapNotNull null
            val installed = row.packageName in launchable || resolver.isInstalled(row.packageName)
            when {
                installed == row.isInstalled -> null
                installed -> Triple(row.id, true, null as Long?)
                else -> Triple(row.id, false, now as Long?)
            }
        }
        if (changed.isNotEmpty()) {
            db.withWriteTransaction {
                changed.forEach { (id, installed, uninstalledAt) ->
                    dao.setInstallState(id, installed, uninstalledAt, now)
                }
            }
        }
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

    /**
     * Sets an app's category. A real change queues a full derived rebuild in the same transaction (categories apply
     * retroactively), so the request can't be lost to a crash or a racing settings check.
     */
    suspend fun setCategory(packageName: String, category: AppCategory) = db.withWriteTransaction {
        val before = db.packageDao().byName(packageName)?.category
        val changed = db.packageDao().setCategory(packageName, category.name, clock()) > 0 && before != category.name
        if (changed) DerivedDirty.mark(db.ingestionStateDao(), 0)
    }

    /** Updates per-app friction override JSON (`packages.friction_override_json`) and records history when changed. */
    suspend fun setFrictionOverride(packageName: String, override: AppFrictionOverride?) {
        db.withWriteTransaction<Unit> {
            val before = db.packageDao().byName(packageName) ?: return@withWriteTransaction
            val encoded = override?.toJsonOrNull()
            if (before.frictionOverrideJson == encoded) return@withWriteTransaction
            val now = clock()
            val updated = db.packageDao().setFrictionOverride(packageName, encoded, now) > 0
            if (updated) {
                val payload = buildJsonObject {
                    put("package", JsonPrimitive(packageName))
                    put("override", JsonPrimitive(encoded ?: "default"))
                }
                db.settingsHistoryDao().insert(
                    SettingsHistoryEntity(timestampUtcMs = now, settingKey = "app_friction_override", valueJson = payload.toString()),
                )
            }
        }
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
        frictionOverride = AppFrictionOverride.parse(frictionOverrideJson),
    )
}
