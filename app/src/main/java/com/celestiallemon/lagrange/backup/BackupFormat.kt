package com.celestiallemon.lagrange.backup

import android.database.sqlite.SQLiteDatabase
import com.celestiallemon.lagrange.data.BackupFingerprint
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
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

    const val MIME_ZIP = "application/zip"

    /** What a document picker may label a ZIP as, depending on the provider. */
    val ZIP_MIME_TYPES = arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")

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

    /** Byte length and SHA-256 of everything [input] yields. */
    fun fingerprint(input: InputStream): BackupFingerprint {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var size = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            digest.update(buffer, 0, n)
            size += n
        }
        return BackupFingerprint(size, digest.digest().toHex())
    }

    /**
     * True when [input] reads as a whole Lagrange bundle (every entry, manifest included, readable to the end). A
     * write killed mid-stream never is, so a complete bundle found where a partial one was recorded is not ours.
     */
    fun isCompleteBundle(input: InputStream): Boolean = try {
        val seen = HashSet<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val buffer = ByteArray(64 * 1024)
                while (zip.read(buffer) >= 0) Unit
                seen += entry.name
            }
        }
        seen.containsAll(ENTRIES)
    } catch (_: Exception) {
        false
    }

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

/** One entry of `sqlite_master`: the schema objects a restored database must match exactly. */
data class SchemaObject(val type: String, val name: String, val tableName: String, val sql: String?)

/** Read-side checks run on a standalone SQLite file (a snapshot or a staged restore), never on the live database. */
object SqliteFileChecks {
    data class Report(
        val integrity: String,
        val userVersion: Int,
        val identityHash: String?,
        val foreignKeyViolations: Int = 0,
        val schema: List<SchemaObject> = emptyList(),
    )

    /** Schema objects SQLite or the Android framework add on their own; they never count as part of the app schema. */
    fun isInternal(name: String): Boolean = name.startsWith("sqlite_") || name == "android_metadata"

    const val SCHEMA_QUERY = "SELECT type, name, tbl_name, sql FROM sqlite_master ORDER BY type, name"

    /**
     * Opens [file] directly, optionally switches it to rollback-journal mode (so it is a single self-contained
     * file), and runs `PRAGMA integrity_check`. Used on snapshots the app itself just produced.
     */
    fun inspect(file: File, normalizeJournal: Boolean = false): Report {
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (e: Exception) {
            throw BackupException("The database file could not be opened.", e)
        }
        return db.use {
            if (normalizeJournal) it.rawQuery("PRAGMA journal_mode=DELETE", null).use { c -> c.moveToFirst() }
            report(it)
        }
    }

    /**
     * Checks a database that came from outside (a backup being restored) without changing a single byte of it:
     * the file must be a self-contained rollback-journal database (never WAL, which would need sidecars and a
     * write to open), and it is opened read-only for integrity, foreign-key and schema checks.
     */
    fun inspectReadOnly(file: File): Report {
        val header = ByteArray(100)
        val read = file.inputStream().use { input -> input.readNBytes(header, 0, header.size) }
        if (read < header.size || !header.copyOfRange(0, 16).contentEquals(SQLITE_MAGIC)) {
            throw BackupException("The backup database is not an SQLite file.")
        }
        // Bytes 18/19 are the file format write/read versions: 1 = rollback journal, 2 = WAL.
        if (header[18].toInt() != 1 || header[19].toInt() != 1) {
            throw BackupException("The backup database is not a self-contained snapshot.")
        }
        val db = try {
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS)
        } catch (e: Exception) {
            throw BackupException("The database file could not be opened.", e)
        }
        return db.use { report(it) }
    }

    private fun report(db: SQLiteDatabase): Report {
        val integrity = db.rawQuery("PRAGMA integrity_check", null).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }.joinToString("; ")
        }
        val fkViolations = db.rawQuery("PRAGMA foreign_key_check", null).use { c -> c.count }
        val identity = try {
            db.rawQuery("SELECT identity_hash FROM room_master_table WHERE id = 42 LIMIT 1", null).use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (_: Exception) {
            null
        }
        val schema = db.rawQuery(SCHEMA_QUERY, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val obj = SchemaObject(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getString(3))
                    if (!isInternal(obj.name)) add(obj)
                }
            }
        }
        return Report(integrity, db.version, identity, fkViolations, schema)
    }

    private val SQLITE_MAGIC = "SQLite format 3\u0000".encodeToByteArray()
}
