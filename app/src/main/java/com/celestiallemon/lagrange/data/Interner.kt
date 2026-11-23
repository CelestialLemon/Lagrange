package com.celestiallemon.lagrange.data

import androidx.room3.withWriteTransaction
import com.celestiallemon.lagrange.data.db.ClassEntity
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
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
        val missing = ArrayList<String>()
        for (name in names.toSet()) {
            val cached = packageIds[name]
            if (cached != null) result[name] = cached else missing += name
        }
        if (missing.isEmpty()) return result
        val dao = db.packageDao()
        val unknown = missing.filter { name -> dao.idOf(name)?.also { result[name] = it; packageIds[name] = it } == null }
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
            for (name in unknown) {
                val id = checkNotNull(dao.idOf(name)) { "package row for $name was not created" }
                result[name] = id
                packageIds[name] = id
            }
        }
        return result
    }

    suspend fun classIds(names: Collection<String>): Map<String, Long> {
        val result = HashMap<String, Long>(names.size)
        val missing = ArrayList<String>()
        for (name in names.toSet()) {
            val cached = classIds[name]
            if (cached != null) result[name] = cached else missing += name
        }
        if (missing.isEmpty()) return result
        val dao = db.classDao()
        db.withWriteTransaction {
            for (name in missing) dao.insertIgnore(ClassEntity(className = name))
        }
        for (name in missing) {
            val id = checkNotNull(dao.idOf(name)) { "class row for $name was not created" }
            result[name] = id
            classIds[name] = id
        }
        return result
    }

    /** Must be called after the tables are wiped, otherwise cached ids would dangle. */
    fun clearCaches() {
        packageIds.clear()
        classIds.clear()
    }
}
