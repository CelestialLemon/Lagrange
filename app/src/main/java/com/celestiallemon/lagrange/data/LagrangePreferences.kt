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
import com.celestiallemon.lagrange.domain.friction.FrictionRuntimeSnapshot
import com.celestiallemon.lagrange.domain.friction.FrictionSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

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
    /** Master switch for real-time gentle friction overlays (off during initial baseline). */
    val frictionEnabled: Boolean = false,
    val escalationEnabled: Boolean = true,
    val tier0PauseSec: Int = 3,
    val tier1PauseSec: Int = 5,
    val tier2PauseSec: Int = 8,
    val tier3PauseSec: Int = 12,
    /** Monotonic switch-back grace window in seconds (default 2m; 0 disables). */
    val graceWindowSec: Int = 120,
    /** In-session time-blindness nudge interval in minutes (default 15m; 0 disables). */
    val nudgeIntervalMin: Int = 15,
    /** Configurable soft session timer presets in minutes. */
    val timerPresetsMin: List<Int> = FrictionSettings.DEFAULT_TIMER_PRESETS_MIN,
    /** Optional cap on launch-pause prompts shown per logical day; null = unlimited. */
    val dailyPromptCap: Int? = null,
    /** Optional healthy redirect app package name (e.g., Kindle, Notes). */
    val redirectPackageName: String? = null,
) {
    fun toFrictionSettings(): FrictionSettings = FrictionSettings(
        frictionEnabled = frictionEnabled,
        escalationEnabled = escalationEnabled,
        tier0PauseSec = tier0PauseSec,
        tier1PauseSec = tier1PauseSec,
        tier2PauseSec = tier2PauseSec,
        tier3PauseSec = tier3PauseSec,
        graceWindowSec = graceWindowSec,
        nudgeIntervalMin = nudgeIntervalMin,
        timerPresetsMin = timerPresetsMin,
        dailyPromptCap = dailyPromptCap,
        redirectPackageName = redirectPackageName,
        rolloverHour = rolloverHour,
        dailyBudgetMs = dailyBudgetMs,
    )

    companion object {
        const val DEFAULT_ROLLOVER_HOUR = 4
        const val DEFAULT_DAILY_BUDGET_MS = 60L * 60 * 1000
        /** Budgets are whole minutes from 5 minutes to 12 hours. */
        val BUDGET_RANGE_MS = 5L * 60 * 1000..12L * 60 * 60 * 1000
        val GRACE_WINDOW_RANGE_SEC = 0..600
        val NUDGE_INTERVAL_RANGE_MIN = 0..120
        val DAILY_CAP_RANGE = 1..200
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

    // History rows are written before the preference: the history is what past days are rebuilt from, and a crash
    // between the two writes is repaired at startup ([repairFromHistory]) instead of leaving them disagreeing.

    suspend fun setRolloverHour(hour: Int) {
        require(hour in 0..23) { "rollover hour must be 0..23" }
        if (current().rolloverHour != hour) {
            db.settingsHistoryDao().insert(
                SettingsHistoryEntity(timestampUtcMs = clock(), settingKey = ROLLOVER_HOUR.name, valueJson = hour.toString()),
            )
        }
        store.edit { it[ROLLOVER_HOUR] = hour }
    }

    /** Changes the daily distracting budget; every change is kept in `settings_history` so past days keep their budget. */
    suspend fun setDailyBudgetMs(budgetMs: Long) {
        require(budgetMs in UserPrefs.BUDGET_RANGE_MS) { "budget must be within ${UserPrefs.BUDGET_RANGE_MS}" }
        if (current().dailyBudgetMs != budgetMs) {
            db.settingsHistoryDao().insert(
                SettingsHistoryEntity(timestampUtcMs = clock(), settingKey = DAILY_BUDGET_MS.name, valueJson = budgetMs.toString()),
            )
        }
        store.edit { it[DAILY_BUDGET_MS] = budgetMs }
    }

    /**
     * Brings the rollover hour and budget preferences in line with the latest `settings_history` row, finishing a
     * change whose preference write was lost to a crash. Returns true if anything changed.
     */
    suspend fun repairFromHistory(): Boolean {
        val history = db.settingsHistoryDao()
        val rollover = history.lastWritten(HISTORY_ROLLOVER_HOUR)?.valueJson?.trim()?.toIntOrNull()?.takeIf { it in 0..23 }
        val budget = history.lastWritten(HISTORY_DAILY_BUDGET_MS)?.valueJson?.trim()?.toLongOrNull()
            ?.takeIf { it in UserPrefs.BUDGET_RANGE_MS }
        val now = current()
        if ((rollover == null || rollover == now.rolloverHour) && (budget == null || budget == now.dailyBudgetMs)) return false
        store.edit {
            if (rollover != null) it[ROLLOVER_HOUR] = rollover
            if (budget != null) it[DAILY_BUDGET_MS] = budget
        }
        return true
    }

    suspend fun setOnboardingComplete(done: Boolean) = store.edit { it[ONBOARDING_COMPLETE] = done }

    suspend fun setUseDynamicColor(enabled: Boolean) = store.edit { it[USE_DYNAMIC_COLOR] = enabled }

    // -- Gentle friction settings (logged to settings_history for honest before/after analytics) --------------------

    suspend fun setFrictionEnabled(enabled: Boolean) {
        if (current().frictionEnabled != enabled) {
            recordSettingHistory(FRICTION_ENABLED.name, enabled.toString())
        }
        store.edit { it[FRICTION_ENABLED] = enabled }
    }

    suspend fun setEscalationEnabled(enabled: Boolean) {
        if (current().escalationEnabled != enabled) {
            recordSettingHistory(ESCALATION_ENABLED.name, enabled.toString())
        }
        store.edit { it[ESCALATION_ENABLED] = enabled }
    }

    suspend fun setTierPauseSeconds(tier0: Int, tier1: Int, tier2: Int, tier3: Int) {
        val t0 = tier0.coerceIn(FrictionSettings.MIN_PAUSE_SEC, FrictionSettings.MAX_PAUSE_SEC)
        val t1 = tier1.coerceIn(FrictionSettings.MIN_PAUSE_SEC, FrictionSettings.MAX_PAUSE_SEC)
        val t2 = tier2.coerceIn(FrictionSettings.MIN_PAUSE_SEC, FrictionSettings.MAX_PAUSE_SEC)
        val t3 = tier3.coerceIn(FrictionSettings.MIN_PAUSE_SEC, FrictionSettings.MAX_PAUSE_SEC)
        val cur = current()
        if (cur.tier0PauseSec != t0 || cur.tier1PauseSec != t1 || cur.tier2PauseSec != t2 || cur.tier3PauseSec != t3) {
            recordSettingHistory("friction_tier_pauses_sec", "[$t0,$t1,$t2,$t3]")
        }
        store.edit {
            it[TIER0_PAUSE_SEC] = t0
            it[TIER1_PAUSE_SEC] = t1
            it[TIER2_PAUSE_SEC] = t2
            it[TIER3_PAUSE_SEC] = t3
        }
    }

    suspend fun setGraceWindowSec(seconds: Int) {
        require(seconds in UserPrefs.GRACE_WINDOW_RANGE_SEC) { "grace window must be within ${UserPrefs.GRACE_WINDOW_RANGE_SEC}" }
        if (current().graceWindowSec != seconds) {
            recordSettingHistory(GRACE_WINDOW_SEC.name, seconds.toString())
        }
        store.edit { it[GRACE_WINDOW_SEC] = seconds }
    }

    suspend fun setNudgeIntervalMin(minutes: Int) {
        require(minutes in UserPrefs.NUDGE_INTERVAL_RANGE_MIN) { "nudge interval must be within ${UserPrefs.NUDGE_INTERVAL_RANGE_MIN}" }
        if (current().nudgeIntervalMin != minutes) {
            recordSettingHistory(NUDGE_INTERVAL_MIN.name, minutes.toString())
        }
        store.edit { it[NUDGE_INTERVAL_MIN] = minutes }
    }

    suspend fun setTimerPresetsMin(presets: List<Int>) {
        val clean = presets.filter { it in 1..120 }.distinct().sorted()
        val encoded = clean.joinToString(",")
        if (current().timerPresetsMin != clean) {
            recordSettingHistory(TIMER_PRESETS_MIN.name, "[${clean.joinToString(",")}]")
        }
        store.edit { it[TIMER_PRESETS_MIN] = encoded }
    }

    suspend fun setDailyPromptCap(cap: Int?) {
        if (cap != null) require(cap in UserPrefs.DAILY_CAP_RANGE) { "daily cap must be within ${UserPrefs.DAILY_CAP_RANGE}" }
        if (current().dailyPromptCap != cap) {
            recordSettingHistory(DAILY_PROMPT_CAP.name, cap?.toString() ?: "null")
        }
        store.edit {
            if (cap == null) it.remove(DAILY_PROMPT_CAP) else it[DAILY_PROMPT_CAP] = cap
        }
    }

    suspend fun setRedirectPackageName(packageName: String?) {
        val clean = packageName?.trim()?.takeIf { it.isNotEmpty() }
        if (current().redirectPackageName != clean) {
            recordSettingHistory(REDIRECT_PACKAGE_NAME.name, clean?.let { JsonPrimitive(it).toString() } ?: "null")
        }
        store.edit {
            if (clean == null) it.remove(REDIRECT_PACKAGE_NAME) else it[REDIRECT_PACKAGE_NAME] = clean
        }
    }

    /** Device-local monotonic friction runtime state (never exported in backups). */
    suspend fun loadFrictionRuntime(): FrictionRuntimeSnapshot? =
        FrictionRuntimeSnapshot.decode(store.data.first()[FRICTION_RUNTIME_STATE])

    suspend fun saveFrictionRuntime(snapshot: FrictionRuntimeSnapshot?) {
        store.edit {
            if (snapshot == null) it.remove(FRICTION_RUNTIME_STATE) else it[FRICTION_RUNTIME_STATE] = snapshot.encode()
        }
    }

    private suspend fun recordSettingHistory(key: String, valueJson: String) {
        db.settingsHistoryDao().insert(
            SettingsHistoryEntity(timestampUtcMs = clock(), settingKey = key, valueJson = valueJson),
        )
    }

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

    /** The portable subset that goes into a backup. Device-specific state (folder grants, backup status, monotonic timers) is left out. */
    suspend fun exportJson(): String {
        val p = current()
        val obj = buildJsonObject {
            put("format_version", JsonPrimitive(1))
            put(ROLLOVER_HOUR.name, JsonPrimitive(p.rolloverHour))
            put(ONBOARDING_COMPLETE.name, JsonPrimitive(p.onboardingComplete))
            put(USE_DYNAMIC_COLOR.name, JsonPrimitive(p.useDynamicColor))
            put(DAILY_BUDGET_MS.name, JsonPrimitive(p.dailyBudgetMs))
            put(FRICTION_ENABLED.name, JsonPrimitive(p.frictionEnabled))
            put(ESCALATION_ENABLED.name, JsonPrimitive(p.escalationEnabled))
            put(TIER0_PAUSE_SEC.name, JsonPrimitive(p.tier0PauseSec))
            put(TIER1_PAUSE_SEC.name, JsonPrimitive(p.tier1PauseSec))
            put(TIER2_PAUSE_SEC.name, JsonPrimitive(p.tier2PauseSec))
            put(TIER3_PAUSE_SEC.name, JsonPrimitive(p.tier3PauseSec))
            put(GRACE_WINDOW_SEC.name, JsonPrimitive(p.graceWindowSec))
            put(NUDGE_INTERVAL_MIN.name, JsonPrimitive(p.nudgeIntervalMin))
            put(TIMER_PRESETS_MIN.name, JsonArray(p.timerPresetsMin.map { JsonPrimitive(it) }))
            p.dailyPromptCap?.let { put(DAILY_PROMPT_CAP.name, JsonPrimitive(it)) }
            p.redirectPackageName?.let { put(REDIRECT_PACKAGE_NAME.name, JsonPrimitive(it)) }
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
            (obj[FRICTION_ENABLED.name] as? JsonPrimitive)?.booleanOrNull?.let { prefs[FRICTION_ENABLED] = it }
            (obj[ESCALATION_ENABLED.name] as? JsonPrimitive)?.booleanOrNull?.let { prefs[ESCALATION_ENABLED] = it }
            (obj[TIER0_PAUSE_SEC.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in FrictionSettings.MIN_PAUSE_SEC..FrictionSettings.MAX_PAUSE_SEC }?.let { prefs[TIER0_PAUSE_SEC] = it }
            (obj[TIER1_PAUSE_SEC.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in FrictionSettings.MIN_PAUSE_SEC..FrictionSettings.MAX_PAUSE_SEC }?.let { prefs[TIER1_PAUSE_SEC] = it }
            (obj[TIER2_PAUSE_SEC.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in FrictionSettings.MIN_PAUSE_SEC..FrictionSettings.MAX_PAUSE_SEC }?.let { prefs[TIER2_PAUSE_SEC] = it }
            (obj[TIER3_PAUSE_SEC.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in FrictionSettings.MIN_PAUSE_SEC..FrictionSettings.MAX_PAUSE_SEC }?.let { prefs[TIER3_PAUSE_SEC] = it }
            (obj[GRACE_WINDOW_SEC.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in UserPrefs.GRACE_WINDOW_RANGE_SEC }?.let { prefs[GRACE_WINDOW_SEC] = it }
            (obj[NUDGE_INTERVAL_MIN.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in UserPrefs.NUDGE_INTERVAL_RANGE_MIN }?.let { prefs[NUDGE_INTERVAL_MIN] = it }
            (obj[TIMER_PRESETS_MIN.name] as? JsonArray)?.let { arr ->
                val list = arr.mapNotNull { (it as? JsonPrimitive)?.intOrNull }.filter { it in 1..120 }.distinct().sorted()
                prefs[TIMER_PRESETS_MIN] = list.joinToString(",")
            }
            if (DAILY_PROMPT_CAP.name in obj) {
                val cap = (obj[DAILY_PROMPT_CAP.name] as? JsonPrimitive)?.intOrNull?.takeIf { it in UserPrefs.DAILY_CAP_RANGE }
                if (cap == null) prefs.remove(DAILY_PROMPT_CAP) else prefs[DAILY_PROMPT_CAP] = cap
            }
            if (REDIRECT_PACKAGE_NAME.name in obj) {
                val pkg = (obj[REDIRECT_PACKAGE_NAME.name] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }
                if (pkg == null) prefs.remove(REDIRECT_PACKAGE_NAME) else prefs[REDIRECT_PACKAGE_NAME] = pkg
            }
        }
    }

    companion object {
        const val STATUS_OK = "OK"

        private val ROLLOVER_HOUR = intPreferencesKey("rollover_hour")
        private val ONBOARDING_COMPLETE = booleanPreferencesKey("onboarding_complete")
        private val USE_DYNAMIC_COLOR = booleanPreferencesKey("use_dynamic_color")
        private val DAILY_BUDGET_MS = longPreferencesKey("daily_budget_ms")
        private val FRICTION_ENABLED = booleanPreferencesKey("friction_enabled")
        private val ESCALATION_ENABLED = booleanPreferencesKey("escalation_enabled")
        private val TIER0_PAUSE_SEC = intPreferencesKey("tier0_pause_sec")
        private val TIER1_PAUSE_SEC = intPreferencesKey("tier1_pause_sec")
        private val TIER2_PAUSE_SEC = intPreferencesKey("tier2_pause_sec")
        private val TIER3_PAUSE_SEC = intPreferencesKey("tier3_pause_sec")
        private val GRACE_WINDOW_SEC = intPreferencesKey("grace_window_sec")
        private val NUDGE_INTERVAL_MIN = intPreferencesKey("nudge_interval_min")
        private val TIMER_PRESETS_MIN = stringPreferencesKey("timer_presets_min")
        private val DAILY_PROMPT_CAP = intPreferencesKey("daily_prompt_cap")
        private val REDIRECT_PACKAGE_NAME = stringPreferencesKey("redirect_package_name")

        /** `settings_history.setting_key` values. */
        const val HISTORY_ROLLOVER_HOUR = "rollover_hour"
        const val HISTORY_DAILY_BUDGET_MS = "daily_budget_ms"
        private val BACKUP_TREE_URI = stringPreferencesKey("backup_tree_uri")
        private val LAST_BACKUP_AT_MS = longPreferencesKey("last_backup_at_ms")
        private val LAST_BACKUP_STATUS = stringPreferencesKey("last_backup_status")
        private val BACKUP_IN_PROGRESS = stringPreferencesKey("backup_in_progress")
        private val COMPLETED_BACKUPS = stringSetPreferencesKey("completed_backups")
        private val FRICTION_RUNTIME_STATE = stringPreferencesKey("friction_runtime_state")

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

        private fun decodePresets(raw: String?): List<Int> {
            if (raw == null) return FrictionSettings.DEFAULT_TIMER_PRESETS_MIN
            if (raw.isBlank()) return emptyList()
            return raw.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 1..120 }.distinct().sorted()
        }

        private fun Preferences.toUserPrefs() = UserPrefs(
            rolloverHour = this[ROLLOVER_HOUR] ?: UserPrefs.DEFAULT_ROLLOVER_HOUR,
            onboardingComplete = this[ONBOARDING_COMPLETE] ?: false,
            useDynamicColor = this[USE_DYNAMIC_COLOR] ?: false,
            backupTreeUri = this[BACKUP_TREE_URI],
            lastBackupAtMs = this[LAST_BACKUP_AT_MS],
            lastBackupStatus = this[LAST_BACKUP_STATUS],
            dailyBudgetMs = this[DAILY_BUDGET_MS] ?: UserPrefs.DEFAULT_DAILY_BUDGET_MS,
            frictionEnabled = this[FRICTION_ENABLED] ?: false,
            escalationEnabled = this[ESCALATION_ENABLED] ?: true,
            tier0PauseSec = this[TIER0_PAUSE_SEC] ?: 3,
            tier1PauseSec = this[TIER1_PAUSE_SEC] ?: 5,
            tier2PauseSec = this[TIER2_PAUSE_SEC] ?: 8,
            tier3PauseSec = this[TIER3_PAUSE_SEC] ?: 12,
            graceWindowSec = this[GRACE_WINDOW_SEC] ?: 120,
            nudgeIntervalMin = this[NUDGE_INTERVAL_MIN] ?: 15,
            timerPresetsMin = decodePresets(this[TIMER_PRESETS_MIN]),
            dailyPromptCap = this[DAILY_PROMPT_CAP],
            redirectPackageName = this[REDIRECT_PACKAGE_NAME],
        )
    }
}
