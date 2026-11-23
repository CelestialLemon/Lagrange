package com.celestiallemon.lagrange.backup

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/** One file in a backup folder. [id] is stable for the file's lifetime (a document URI for SAF). */
data class BackupDocument(val id: String, val name: String?)

/**
 * The folder weekly backups go to. All calls do blocking I/O (SAF provider round-trips): call them on
 * `Dispatchers.IO` only.
 */
interface BackupFolder {
    fun canWrite(): Boolean
    fun create(mimeType: String, name: String): BackupDocument?
    fun openOutput(doc: BackupDocument): OutputStream?
    fun openInput(id: String): InputStream?
    fun list(): List<BackupDocument>
    fun delete(id: String): Boolean

    /** True when [id] names a document inside this folder (recorded ids from other folders are left alone). */
    fun owns(id: String): Boolean
}

/** A folder picked with `ACTION_OPEN_DOCUMENT_TREE`. */
class SafBackupFolder(private val context: Context, private val treeUri: Uri) : BackupFolder {
    private val tree: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw BackupException("The backup folder is no longer available.")

    override fun canWrite(): Boolean = tree.canWrite()

    override fun create(mimeType: String, name: String): BackupDocument? =
        tree.createFile(mimeType, name)?.let { BackupDocument(it.uri.toString(), it.name ?: name) }

    override fun openOutput(doc: BackupDocument): OutputStream? = context.contentResolver.openOutputStream(Uri.parse(doc.id), "wt")

    override fun openInput(id: String): InputStream? = context.contentResolver.openInputStream(Uri.parse(id))

    override fun list(): List<BackupDocument> =
        tree.listFiles().filter { it.isFile }.map { BackupDocument(it.uri.toString(), it.name) }

    override fun delete(id: String): Boolean =
        DocumentFile.fromSingleUri(context, Uri.parse(id))?.delete() == true

    override fun owns(id: String): Boolean = id.startsWith(tree.uri.toString().substringBefore("/document/") + "/document/")
}

/** A plain directory; used by tests and usable for app-private targets. */
class FileBackupFolder(private val dir: File) : BackupFolder {
    override fun canWrite(): Boolean = dir.isDirectory && dir.canWrite()

    override fun create(mimeType: String, name: String): BackupDocument? {
        var file = File(dir, name)
        var n = 1
        while (file.exists()) file = File(dir, "${name.substringBeforeLast('.')} ($n).${name.substringAfterLast('.')}").also { n++ }
        return if (file.createNewFile()) BackupDocument(file.path, file.name) else null
    }

    override fun openOutput(doc: BackupDocument): OutputStream = FileOutputStream(doc.id)

    override fun openInput(id: String): InputStream? = File(id).takeIf { it.isFile }?.let(::FileInputStream)

    override fun list(): List<BackupDocument> = dir.listFiles()?.filter { it.isFile }?.map { BackupDocument(it.path, it.name) }.orEmpty()

    override fun delete(id: String): Boolean = File(id).delete()

    override fun owns(id: String): Boolean = File(id).parentFile?.absolutePath == dir.absolutePath
}
