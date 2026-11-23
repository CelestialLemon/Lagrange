package com.celestiallemon.lagrange.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.celestiallemon.lagrange.data.db.LagrangeDatabase
import com.celestiallemon.lagrange.data.db.SettingsHistoryEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/** A plain snapshot of the user's preferences. */
data class UserPrefs(
    val rolloverHour: Int = DEFAULT_ROLLOVER_HOUR,
    val onboardingComplete: Boolean = false,
    val useDynamicColor: Boolean = false,
    val backupTreeUri: String? = null,
    val lastBackupAtMs: Long? = null,
    val lastBackupStatus: String? = null,
) {
    companion object {
        const val DEFAULT_ROLLOVER_HOUR = 4
    }
}

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

    /** The portable subset that goes into a backup. Device-specific state (folder grants, backup status) is left out. */
    suspend fun exportJson(): String {
        val p = current()
        val obj = buildJsonObject {
            put("format_version", JsonPrimitive(1))
            put(ROLLOVER_HOUR.name, JsonPrimitive(p.rolloverHour))
            put(ONBOARDING_COMPLETE.name, JsonPrimitive(p.onboardingComplete))
            put(USE_DYNAMIC_COLOR.name, JsonPrimitive(p.useDynamicColor))
        }
        return obj.toString()
    }

    suspend fun importJson(text: String) {
        val obj = parsePortable(text)
        store.edit { prefs ->
            (obj[ROLLOVER_HOUR.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in 0..23 }?.let { prefs[ROLLOVER_HOUR] = it }
            (obj[ONBOARDING_COMPLETE.name] as? JsonPrimitive)?.booleanOrNull?.let { prefs[ONBOARDING_COMPLETE] = it }
            (obj[USE_DYNAMIC_COLOR.name] as? JsonPrimitive)?.booleanOrNull?.let { prefs[USE_DYNAMIC_COLOR] = it }
        }
    }

    companion object {
        const val STATUS_OK = "OK"

        private val ROLLOVER_HOUR = intPreferencesKey("rollover_hour")
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        private val BACKUP_TREE_URI = stringPreferencesKey("backup_tree_uri")
        private val LAST_BACKUP_AT_MS = longPreferencesKey("last_backup_at_ms")
        private val LAST_BACKUP_STATUS = stringPreferencesKey("last_backup_status")

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
        )
    }
}
