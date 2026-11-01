package com.zyke.message.data.backup

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.backup.BackupFileNames
import com.zyke.message.domain.backup.FolderFile
import com.zyke.message.domain.backup.FolderLabel
import com.zyke.message.domain.backup.FolderRetention
import java.io.FileNotFoundException
import java.io.IOException
import java.time.Instant
import java.time.ZoneId

/**
 * The third place a backup can go: a folder the person chose once with Android's own folder picker (the Storage
 * Access Framework). No permission is needed: the system keeps the grant for that one folder (taken here with
 * `takePersistableUriPermission`), and it is shown in the phone's settings. The grant does not survive a reinstall, so
 * the setting is not part of any backup (see `backup_folder` in [PrefsBackup]).
 *
 * Written with [DocumentsContract] and the content resolver directly (no extra library). Every call here does file
 * work: call it off the main thread. A dated file `zyke-backup-yyyyMMdd-HHmmss.zbk` is added at each run and only
 * files with exactly that kind of name beyond the newest [FolderRetention.KEEP] are removed; nothing else in the
 * folder is ever deleted. No network is involved.
 */
class FolderBackupStore(context: Context, private val prefs: BackupPrefs) {
    private val resolver: ContentResolver = context.applicationContext.contentResolver

    /** Keeps access to [tree] across restarts. Returns false when the system refused. */
    fun takeAccess(tree: Uri): Boolean = try {
        resolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        true
    } catch (e: Exception) {
        DevLog.d("backup") { "folder access could not be kept: ${e.javaClass.simpleName}" }
        false
    }

    /** Gives back the access to [tree] (when the folder is changed or the setting turned off). */
    fun releaseAccess(tree: String?) {
        if (tree.isNullOrEmpty()) return
        try {
            resolver.releasePersistableUriPermission(Uri.parse(tree), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: Exception) {
            // Already gone: nothing to give back.
        }
    }

    private fun hasWriteGrant(tree: Uri): Boolean =
        resolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }

    /** The readable name for the folder, for example "Download/Zyke". Falls back to a plain phrase. */
    fun labelFor(tree: Uri): String {
        val docId = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: Exception) { null }
        val name = if (docId == null) null else try {
            resolver.query(
                DocumentsContract.buildDocumentUriUsingTree(tree, docId),
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) { null }
        return FolderLabel.of(docId, name)
    }

    /** True when the chosen folder can still be written to (the grant is there and the provider answers). */
    fun isAccessible(): Boolean = try {
        val tree = prefs.folderTree?.let(Uri::parse)
        tree != null && hasWriteGrant(tree) && listOurs(tree, DocumentsContract.getTreeDocumentId(tree)) != null
    } catch (e: Exception) { false }

    /**
     * Adds a dated file with [bytes] and trims the old ones. Returns the number of bytes written.
     * Throws [BackupFailureException] with FOLDER_LOST (grant taken back, folder deleted or its app removed),
     * FOLDER_FULL (no space) or STORAGE (anything else that went wrong while writing).
     */
    @Throws(BackupFailureException::class)
    fun save(bytes: ByteArray, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): Long {
        val tree = prefs.folderTree?.let { Uri.parse(it) } ?: throw BackupFailureException(BackupFailure.FOLDER_LOST)
        try {
            if (!hasWriteGrant(tree)) throw BackupFailureException(BackupFailure.FOLDER_LOST)
            val rootId = DocumentsContract.getTreeDocumentId(tree)
            if (listOurs(tree, rootId) == null) throw BackupFailureException(BackupFailure.FOLDER_LOST)
            val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
            val name = BackupFileNames.forTime(now, zone)
            val created = DocumentsContract.createDocument(resolver, parent, MIME, name) ?: throw BackupFailureException(BackupFailure.STORAGE)
            try {
                resolver.openOutputStream(created, "wt")?.use { it.write(bytes); it.flush() } ?: throw BackupFailureException(BackupFailure.STORAGE)
            } catch (e: Exception) {
                // Never leave half a file that looks like a backup.
                runCatching { DocumentsContract.deleteDocument(resolver, created) }
                throw e
            }
            trim(tree, rootId)
            return bytes.size.toLong()
        } catch (e: BackupFailureException) {
            throw e
        } catch (e: SecurityException) {
            throw BackupFailureException(BackupFailure.FOLDER_LOST)
        } catch (e: FileNotFoundException) {
            throw BackupFailureException(BackupFailure.FOLDER_LOST)
        } catch (e: IllegalArgumentException) {
            throw BackupFailureException(BackupFailure.FOLDER_LOST)
        } catch (e: UnsupportedOperationException) {
            throw BackupFailureException(BackupFailure.FOLDER_LOST)
        } catch (e: IOException) {
            val text = (e.message ?: "") + (e.cause?.message ?: "")
            throw BackupFailureException(if ("ENOSPC" in text || "No space left" in text) BackupFailure.FOLDER_FULL else BackupFailure.STORAGE)
        } catch (e: Exception) {
            throw BackupFailureException(BackupFailure.STORAGE)
        }
    }

    private fun trim(tree: Uri, rootId: String) {
        val files = listOurs(tree, rootId) ?: return
        for (old in FolderRetention.toDelete(files)) {
            runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, old.ref)) }
        }
    }

    /** The files in the folder whose names are exactly ours, or null when the folder cannot be listed. */
    private fun listOurs(tree: Uri, rootId: String): List<FolderFile>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, rootId)
        val out = ArrayList<FolderFile>()
        val cursor = try {
            resolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null
            )
        } catch (e: Exception) {
            null
        } ?: return null
        cursor.use { c ->
            while (c.moveToNext()) {
                val id = c.getString(0) ?: continue
                val name = c.getString(1) ?: continue
                if (BackupFileNames.matches(name)) out.add(FolderFile(id, name))
            }
        }
        return out
    }

    private companion object {
        const val MIME = "application/octet-stream"
    }
}
