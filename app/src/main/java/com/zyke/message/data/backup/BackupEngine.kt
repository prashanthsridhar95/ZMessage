package com.zyke.message.data.backup

import android.content.Context
import com.zyke.message.data.images.ImageStore
import com.zyke.message.domain.backup.BackupContainer
import com.zyke.message.domain.backup.BackupCrypto
import java.io.File

/** An encrypted backup file ready to be stored somewhere. */
class BuiltBackup(val bytes: ByteArray, val picturesSkipped: Boolean)

/**
 * Makes the encrypted snapshot: the setup (see [PrefsBackup]) plus the person's chosen sender pictures, in a zip,
 * sealed with the saved key. The same bytes go to the Auto Backup file and to Google Drive. Heavy: call off the main thread.
 */
class BackupEngine(context: Context, private val images: ImageStore, private val keys: BackupKeyStore) {
    private val app = context.applicationContext

    /** The one file Android's Auto Backup is allowed to take (see backup_rules.xml); refreshed by [writeLocal]. */
    val localFile: File get() = File(File(app.filesDir, DIR), NAME)

    @Throws(BackupFailureException::class)
    fun build(createdAt: Long = System.currentTimeMillis()): BuiltBackup {
        val material = keys.load() ?: throw BackupFailureException(BackupFailure.NO_KEY)
        val setup = PrefsBackup.capture(app)
        val pictures = images.chosenPicturesForBackup(BackupContainer.MAX_PICTURE_BYTES)
        val packed = BackupContainer.pack(setup, pictures.orEmpty(), leftOut = pictures == null)
        val file = BackupCrypto.encryptWith(packed.bytes, material, createdAt)
        return BuiltBackup(file.toByteArray(Charsets.UTF_8), packed.picturesSkipped)
    }

    @Throws(BackupFailureException::class)
    fun writeLocal(bytes: ByteArray) {
        try {
            val target = localFile
            target.parentFile?.mkdirs()
            val temp = File(target.parentFile, "$NAME.tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                target.delete()
                if (!temp.renameTo(target)) throw java.io.IOException("rename")
            }
        } catch (e: java.io.IOException) {
            throw BackupFailureException(BackupFailure.STORAGE)
        }
    }

    fun deleteLocal() {
        localFile.delete()
        File(localFile.parentFile, "$NAME.tmp").delete()
    }

    /** The text of the Auto Backup file, if there is one (for example one Android restored on a new phone). */
    fun readLocalOrNull(): String? = runCatching {
        localFile.takeIf { it.isFile && it.length() in 1..MAX_FILE_BYTES }?.readText(Charsets.UTF_8)
    }.getOrNull()

    companion object {
        const val DIR = "auto_backup"
        const val NAME = "zyke-backup.bin"
        const val MAX_FILE_BYTES = 48L * 1024 * 1024
    }
}
