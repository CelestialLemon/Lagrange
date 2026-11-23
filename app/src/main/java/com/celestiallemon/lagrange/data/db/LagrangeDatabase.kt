package com.celestiallemon.lagrange.data.db

import android.content.Context
import androidx.room3.Database
import androidx.room3.Room
import androidx.room3.RoomDatabase
import androidx.room3.useReaderConnection
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL

/**
 * The single source of truth. Schema changes always ship with an explicit, tested migration: this database never
 * falls back to destructive migration, because raw history cannot be re-harvested once Android purges it.
 */
@Database(
    entities = [
        PackageEntity::class,
        ClassEntity::class,
        RawUsageEventEntity::class,
        RawNotificationEntity::class,
        TzHistoryEntity::class,
        CoverageIntervalEntity::class,
        IngestionStateEntity::class,
        SettingsHistoryEntity::class,
        DailyNoteEntity::class,
        FrictionScheduleEntity::class,
        FrictionEventEntity::class,
        SessionEntity::class,
        SessionSegmentEntity::class,
        DailyRollupEntity::class,
        DailyAppRollupEntity::class,
        HourlyAppUsageEntity::class,
    ],
    version = LagrangeDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class LagrangeDatabase : RoomDatabase() {
    abstract fun packageDao(): PackageDao
    abstract fun classDao(): ClassDao
    abstract fun rawUsageEventDao(): RawUsageEventDao
    abstract fun rawNotificationDao(): RawNotificationDao
    abstract fun tzHistoryDao(): TzHistoryDao
    abstract fun coverageDao(): CoverageDao
    abstract fun ingestionStateDao(): IngestionStateDao
    abstract fun settingsHistoryDao(): SettingsHistoryDao
    abstract fun derivedDao(): DerivedDao

    /** Forces the database open (running any migration) and reads Room's schema identity hash. */
    suspend fun identityHash(): String? = useReaderConnection { connection ->
        connection.usePrepared("SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1") { stmt ->
            if (stmt.step() && !stmt.isNull(0)) stmt.getText(0) else null
        }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val FILE_NAME = "lagrange.db"

        /** All migrations, in order. Empty at v1; every future version bump must add one here. */
        val MIGRATIONS = emptyArray<androidx.room3.migration.Migration>()

        fun create(context: Context): LagrangeDatabase =
            Room.databaseBuilder(context, LagrangeDatabase::class.java, FILE_NAME)
                .setDriver(AndroidSQLiteDriver())
                .addMigrations(*MIGRATIONS)
                .addCallback(SeedCallback)
                .build()

        fun inMemory(context: Context): LagrangeDatabase =
            Room.inMemoryDatabaseBuilder(context, LagrangeDatabase::class.java)
                .setDriver(AndroidSQLiteDriver())
                .addCallback(SeedCallback)
                .build()
    }

    /** Seeds the canonical empty-string class row so a null activity class always interns to the same id. */
    private object SeedCallback : Callback() {
        override suspend fun onCreate(connection: SQLiteConnection) {
            connection.execSQL("INSERT OR IGNORE INTO classes (class_name) VALUES ('')")
        }
    }
}
