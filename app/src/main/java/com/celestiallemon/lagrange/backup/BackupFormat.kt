package com.celestiallemon.lagrange.backup

import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

class BackupException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
data class BackupFileEntry(val name: String, val sha256: String, val size: Long)

@Serializable
data class BackupManifest(
    val format: String = BackupFormat.FORMAT,
    @SerialName("format_version") val formatVersion: Int = BackupFormat.FORMAT_VERSION,
    @SerialName("schema_version") val schemaVersion: Int,
    @SerialName("room_identity_hash") val roomIdentityHash: String? = null,
    @SerialName("app_version_name") val appVersionName: String,
    @SerialName("app_version_code") val appVersionCode: Long,
    @SerialName("created_at_utc_ms") val createdAtUtcMs: Long,
    val files: List<BackupFileEntry>,
)

/** The `.zip` bundle layout shared by backup and restore. */
object BackupFormat {
    const val FORMAT = "lagrange-backup"
    const val FORMAT_VERSION = 1
    const val DB_ENTRY = "lagrange.sqlite"
    const val PREFS_ENTRY = "preferences.json"
    const val MANIFEST_ENTRY = "manifest.json"
    val ENTRIES = setOf(DB_ENTRY, PREFS_ENTRY, MANIFEST_ENTRY)

    const val MAX_DB_BYTES = 4L * 1024 * 1024 * 1024
    const val MAX_JSON_BYTES = 1L * 1024 * 1024

    const val FILE_PREFIX = "lagrange-backup-"
    const val FILE_SUFFIX = ".zip"
    private val FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC)

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    /** `lagrange-backup-20261123-140501.zip`: UTC stamps sort lexically in time order. */
    fun fileName(atMs: Long): String = FILE_PREFIX + FILE_STAMP.format(Instant.ofEpochMilli(atMs)) + FILE_SUFFIX

    fun isBackupFileName(name: String?): Boolean =
        name != null && name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    /** Of [names], the backups beyond the newest [keep] that should be deleted. */
    fun toPrune(names: List<String>, keep: Int): List<String> =
        names.filter(::isBackupFileName).sortedDescending().drop(keep)

    fun sha256(file: File): String = file.inputStream().use { sha256(it) }

    fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
        }
        return digest.digest().toHex()
    }

    fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    /** Copies at most [limit] bytes, failing instead of filling the disk with an oversized or hostile entry. */
    fun copyLimited(input: InputStream, output: OutputStream, limit: Long): Long {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > limit) throw BackupException("Backup entry is larger than allowed.")
            output.write(buffer, 0, n)
        }
        return total
    }
}

/** Read-side checks run on a standalone SQLite file (a snapshot or a staged restore), never on the live database. */
object SqliteFileChecks {
    data class Report(val integrity: String, val userVersion: Int, val identityHash: String?)

    /**
     * Opens [file] directly, optionally switches it to rollback-journal mode (so it is a single self-contained
     * file), and runs `PRAGMA integrity_check`.
     */
    fun inspect(file: File, normalizeJournal: Boolean = false): Report {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE)
        } catch (e: Exception) {
            throw BackupException("The database file could not be opened.", e)
        }
        return db.use {
            if (normalizeJournal) it.rawQuery("PRAGMA journal_mode=DELETE", null).use { c -> c.moveToFirst() }
            val integrity = it.rawQuery("PRAGMA integrity_check", null).use { c ->
                buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString("; ")
            }
            val identity = try {
                it.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1", null).use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                }
            } catch (_: Exception) {
                null
            }
            Report(integrity, it.version, identity)
        }
    }
}
