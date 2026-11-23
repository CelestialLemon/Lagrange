package com.celestiallemon.lagrange.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject

/** A plain snapshot of the user's preferences. */
data class UserPrefs(
    val rolloverHour: Int = DEFAULT_ROLLOVER_HOUR,
    val onboardingComplete: Boolean = false,
    val useDynamicColor: Boolean = false,
    val backupTreeUri: String? = null,
    val lastBackupAtMs: Long? = null,
    val lastBackupStatus: String? = null,
    /** Daily distracting-time budget. */
    val dailyBudgetMs: Long = DEFAULT_DAILY_BUDGET_MS,
) {
    companion object {
        const val DEFAULT_ROLLOVER_HOUR = 4
        const val DEFAULT_DAILY_BUDGET_MS = 60L * 60 * 1000
        /** Budgets are whole minutes from 5 minutes to 12 hours. */
        val BUDGET_RANGE_MS = 5L * 60 * 1000..12L * 60 * 60 * 1000
    }
}

/** Byte length and SHA-256 of a backup file exactly as Lagrange wrote it. */
@Serializable
data class BackupFingerprint(val size: Long, val sha256: String)

@Serializable
internal data class CompletedBackup(val id: String, val fingerprint: BackupFingerprint? = null)

/**
 * A folder backup being written: its document id, the name it was created with, and the random per-write token
 * embedded at the start of its bytes (null for a record from a build that wrote none; such a file is never deleted).
 */
@Serializable
data class InProgressBackup(val id: String, val name: String? = null, val token: String? = null)

/**
 * DataStore-backed preferences. Changes that affect how history is interpreted (the logical-day rollover) are also
 * appended to `settings_history` so later recomputes know which value applied when.
 */
class LagrangePreferences(
    private val store: DataStore<Preferences>,
    private val db: LagrangeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val prefs: Flow<UserPrefs> = store.data.map { it.toUserPrefs() }

    suspend fun current(): UserPrefs = prefs.first()

    suspend fun setRolloverHour(hour: Int) {
        require(hour in 0..23) { "rollover hour must be 0..23" }
        val before = current().rolloverHour
        store.edit { it[ROLLOVER_HOUR] = hour }
        if (before != hour) {
            db.settingsHistoryDao().insert(
                SettingsHistoryEntity(timestampUtcMs = clock(), settingKey = ROLLOVER_HOUR.name, valueJson = hour.toString()),
            )
        }
    }

    /** Changes the daily distracting budget; every change is kept in `settings_history` so past days keep their budget. */
    suspend fun setDailyBudgetMs(budgetMs: Long) {
        require(budgetMs in UserPrefs.BUDGET_RANGE_MS) { "budget must be within ${UserPrefs.BUDGET_RANGE_MS}" }
        val before = current().dailyBudgetMs
        store.edit { it[DAILY_BUDGET_MS] = budgetMs }
        if (before != budgetMs) {
            db.settingsHistoryDao().insert(
                SettingsHistoryEntity(timestampUtcMs = clock(), settingKey = DAILY_BUDGET_MS.name, valueJson = budgetMs.toString()),
            )
        }
    }

    suspend fun setOnboardingComplete(done: Boolean) = store.edit { it[ONBOARDING_COMPLETE] = done }

    suspend fun setUseDynamicColor(enabled: Boolean) = store.edit { it[USE_DYNAMIC_COLOR] = enabled }

    suspend fun setBackupTreeUri(uri: String?) = store.edit {
        if (uri == null) it.remove(BACKUP_TREE_URI) else it[BACKUP_TREE_URI] = uri
    }

    suspend fun recordBackup(atMs: Long, status: String) = store.edit {
        if (status == STATUS_OK) it[LAST_BACKUP_AT_MS] = atMs
        it[LAST_BACKUP_STATUS] = status
    }

    suspend fun clearAll() = store.edit { it.clear() }

    // -- Backup-folder bookkeeping (device-specific, never exported) ------------------------------------------------

    /** The folder backup that was being written; set while writing, so a killed run can be cleaned up. */
    suspend fun backupInProgress(): InProgressBackup? = store.data.first()[BACKUP_IN_PROGRESS]?.let(::decodeInProgress)

    suspend fun setBackupInProgress(documentId: String?, name: String? = null, token: String? = null) = store.edit {
        if (documentId == null) {
            it.remove(BACKUP_IN_PROGRESS)
        } else {
            it[BACKUP_IN_PROGRESS] = recordJson.encodeToString(InProgressBackup.serializer(), InProgressBackup(documentId, name, token))
        }
    }

    /**
     * Folder backups Lagrange wrote and saw complete, with the fingerprint of the bytes it wrote (null for a record
     * kept from a build that stored none). Only these are ever counted for retention or pruned, and only after the
     * file at that id still matches its fingerprint.
     */
    suspend fun completedBackups(): Map<String, BackupFingerprint?> =
        store.data.first()[COMPLETED_BACKUPS].orEmpty().map(::decodeCompleted).associate { it.id to it.fingerprint }

    /** Records a finished folder backup and clears the in-progress marker in one step. */
    suspend fun recordCompletedBackup(documentId: String, fingerprint: BackupFingerprint) = store.edit {
        val kept = it[COMPLETED_BACKUPS].orEmpty().filterNot { raw -> decodeCompleted(raw).id == documentId }
        it[COMPLETED_BACKUPS] = kept.toSet() + recordJson.encodeToString(CompletedBackup.serializer(), CompletedBackup(documentId, fingerprint))
        it.remove(BACKUP_IN_PROGRESS)
    }

    suspend fun forgetBackups(documentIds: Collection<String>) = store.edit {
        val ids = documentIds.toSet()
        it[COMPLETED_BACKUPS] = it[COMPLETED_BACKUPS].orEmpty().filterNot { raw -> decodeCompleted(raw).id in ids }.toSet()
    }

    /** The portable subset that goes into a backup. Device-specific state (folder grants, backup status) is left out. */
    suspend fun exportJson(): String {
        val p = current()
        val obj = buildJsonObject {
            put("format_version", JsonPrimitive(1))
            put(ROLLOVER_HOUR.name, JsonPrimitive(p.rolloverHour))
            put(ONBOARDING_COMPLETE.name, JsonPrimitive(p.onboardingComplete))
            put(USE_DYNAMIC_COLOR.name, JsonPrimitive(p.useDynamicColor))
            put(DAILY_BUDGET_MS.name, JsonPrimitive(p.dailyBudgetMs))
        }
        return obj.toString()
    }

    suspend fun importJson(text: String) {
        val obj = parsePortable(text)
        store.edit { prefs ->
            (obj[ROLLOVER_HOUR.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..23 }?.let { prefs[ROLLOVER_HOUR] = it }
            (obj[ONBOARDING_COMPLETE.name] as? JsonPrimitive)?.booleanOrNull?.let { prefs[ONBOARDING_COMPLETE] = it }
            (obj[USE_DYNAMIC_COLOR.name] as? JsonPrimitive)?.booleanOrNull?.let { prefs[USE_DYNAMIC_COLOR] = it }
            (obj[DAILY_BUDGET_MS.name] as? JsonPrimitive)?.longOrNull?.takeIf { it in UserPrefs.BUDGET_RANGE_MS }?.let { prefs[DAILY_BUDGET_MS] = it }
        }
    }

    companion object {
        const val STATUS_OK = "OK"

        private val ROLLOVER_HOUR = intPreferencesKey("rollover_hour")
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        private val DAILY_BUDGET_MS = longPreferencesKey("daily_budget_ms")

        /** `settings_history.setting_key` values. */
        const val HISTORY_ROLLOVER_HOUR = "rollover_hour"
        const val HISTORY_DAILY_BUDGET_MS = "daily_budget_ms"
        private val BACKUP_TREE_URI = stringPreferencesKey("backup_tree_uri")
        private val LAST_BACKUP_AT_MS = longPreferencesKey("last_backup_at_ms")
        private val LAST_BACKUP_STATUS = stringPreferencesKey("last_backup_status")
        private val BACKUP_IN_PROGRESS = stringPreferencesKey("backup_in_progress")
        private val COMPLETED_BACKUPS = stringSetPreferencesKey("completed_backups")

        private val recordJson = Json { ignoreUnknownKeys = true }

        /** A plain string is a record from the build that stored ids only. */
        private fun decodeCompleted(raw: String): CompletedBackup =
            if (raw.startsWith("{")) runCatching { recordJson.decodeFromString(CompletedBackup.serializer(), raw) }.getOrElse { CompletedBackup(raw, null) }
            else CompletedBackup(raw, null)

        private fun decodeInProgress(raw: String): InProgressBackup =
            if (raw.startsWith("{")) runCatching { recordJson.decodeFromString(InProgressBackup.serializer(), raw) }.getOrElse { InProgressBackup(raw, null) }
            else InProgressBackup(raw, null)

        /** Throws [IllegalArgumentException] when [text] isn't a preferences object this version understands. */
        fun parsePortable(text: String): JsonObject {
            val obj = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw IllegalArgumentException("preferences.json is not a JSON object", e)
            }
            val version = (obj["format_version"] as? JsonPrimitive)?.intOrNull
            require(version == 1) { "Unsupported preferences format $version" }
            return obj
        }

        private fun Preferences.toUserPrefs() = UserPrefs(
            rolloverHour = this[ROLLOVER_HOUR] ?: UserPrefs.DEFAULT_ROLLOVER_HOUR,
            onboardingComplete = this[ONBOARDING_COMPLETE] ?: false,
            useDynamicColor = this[USE_DYNAMIC_COLOR] ?: false,
            backupTreeUri = this[BACKUP_TREE_URI],
            lastBackupAtMs = this[LAST_BACKUP_AT_MS],
            lastBackupStatus = this[LAST_BACKUP_STATUS],
            dailyBudgetMs = this[DAILY_BUDGET_MS] ?: UserPrefs.DEFAULT_DAILY_BUDGET_MS,
        )
    }
}
