package com.celestiallemon.lagrange.data

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.ClassEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.NameId
import com.celestiallemon.lagrange.data.db.PackageEntity
import java.util.concurrent.ConcurrentHashMap

/**
 * Maps package and activity-class names to compact integer ids. Ids are committed in their own transaction before
 * any raw row references them, so a cached id always points at a committed row.
 */
class Interner(
    private val db: LagrangeDatabase,
    private val resolver: PackageMetaResolver,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val packageIds = ConcurrentHashMap<String, Long>()
    private val classIds = ConcurrentHashMap<String, Long>()

    suspend fun packageId(name: String): Long = packageIds(listOf(name)).getValue(name)

    suspend fun classId(name: String?): Long = classIds(listOf(name.orEmpty())).getValue(name.orEmpty())

    suspend fun packageIds(names: Collection<String>): Map<String, Long> {
        val result = HashMap<String, Long>(names.size)
        val missing = names.toSet().filter { name -> packageIds[name]?.also { result[name] = it } == null }
        if (missing.isEmpty()) return result
        val dao = db.packageDao()
        lookup(missing, dao::idsOf, result, packageIds)
        val unknown = missing.filter { it !in result }
        if (unknown.isNotEmpty()) {
            // Resolve labels outside the transaction: PackageManager calls are slow and must not hold the writer.
            val metas = unknown.associateWith { resolver.resolve(it) }
            val now = clock()
            db.withWriteTransaction {
                for ((name, meta) in metas) {
                    dao.insertIgnore(
                        PackageEntity(
                            packageName = name,
                            displayName = meta.displayName,
                            category = meta.suggestedCategory.name,
                            isLauncherOrSystemUi = meta.isLauncherOrSystemUi,
                            isInstalled = meta.isInstalled,
                            installedAtMs = meta.installedAtMs,
                            updatedAtMs = now,
                        ),
                    )
                }
            }
            lookup(unknown, dao::idsOf, result, packageIds)
            for (name in unknown) checkNotNull(result[name]) { "package row for $name was not created" }
        }
        return result
    }

    suspend fun classIds(names: Collection<String>): Map<String, Long> {
        val result = HashMap<String, Long>(names.size)
        val missing = names.toSet().filter { name -> classIds[name]?.also { result[name] = it } == null }
        if (missing.isEmpty()) return result
        val dao = db.classDao()
        // Cold cache: one batched lookup first, so known classes never cost an insert attempt each.
        lookup(missing, dao::idsOf, result, classIds)
        val unknown = missing.filter { it !in result }
        if (unknown.isNotEmpty()) {
            db.withWriteTransaction {
                for (name in unknown) dao.insertIgnore(ClassEntity(className = name))
            }
            lookup(unknown, dao::idsOf, result, classIds)
            for (name in unknown) checkNotNull(result[name]) { "class row for $name was not created" }
        }
        return result
    }

    private suspend fun lookup(
        names: List<String>,
        query: suspend (List<String>) -> List<NameId>,
        result: MutableMap<String, Long>,
        cache: MutableMap<String, Long>,
    ) {
        for (chunk in names.chunked(LOOKUP_CHUNK)) {
            for (row in query(chunk)) {
                result[row.name] = row.id
                cache[row.name] = row.id
            }
        }
    }

    /** Must be called after the tables are wiped, otherwise cached ids would dangle. */
    fun clearCaches() {
        packageIds.clear()
        classIds.clear()
    }

    private companion object {
        /** Stays well under SQLite's bound-parameter limit. */
        const val LOOKUP_CHUNK = 500
    }
}
