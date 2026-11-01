package com.zyke.message.data.backup

import android.content.Context
import com.zyke.message.data.diag.DevLog
import com.zyke.message.data.images.ImageStore
import com.zyke.message.domain.backup.BackupContainer
import com.zyke.message.domain.backup.BackupContents
import com.zyke.message.domain.backup.BackupCrypto
import com.zyke.message.domain.backup.BackupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** A backup that has been opened with the right PIN and checked, ready to replace the setup. Holds no secret except the derived key. */
class PreparedRestore(
    internal val snapshot: PrefsBackup.Snapshot,
    internal val contents: BackupContents,
    internal val material: BackupCrypto.KeyMaterial,
    val createdAt: Long,
    val pictureCount: Int
)

/** Opening a backup file and putting it in place. */
class BackupRestorer(
    private val context: Context,
    private val images: ImageStore,
    private val keys: BackupKeyStore,
    private val prefs: BackupPrefs
) {
    /** When the file was made, read without any PIN; null when it is not a backup. */
    fun createdAt(file: String): Long? = try { BackupCrypto.peek(file).createdAt } catch (e: BackupException) { null }

    /**
     * Opens [file] with [secret]. A wrong secret waits a second before saying so, so guessing at the keyboard is slow.
     * Throws [BackupException] or [PrefsBackup.InvalidSnapshotException].
     */
    suspend fun prepare(file: String, secret: CharArray): PreparedRestore = withContext(Dispatchers.Default) {
        try {
            val opened = BackupCrypto.open(file, secret)
            val contents = BackupContainer.unpack(opened.plain)
            PreparedRestore(PrefsBackup.parse(contents.setup), contents, opened.material, opened.createdAt, contents.pictures.size)
        } catch (e: BackupException.WrongPassphrase) {
            DevLog.d("backup") { "restore result=failed class=wrong_secret" }
            delay(WRONG_SECRET_DELAY_MS)
            throw e
        }
    }

    /**
     * Replaces the setup (and the chosen pictures, when the backup has them) with the backup, keeps its key so
     * automatic backups carry on with the same PIN, and marks the welcome step as done. The caller must close the app
     * straight afterwards so nothing in memory writes the old values back.
     */
    fun apply(prepared: PreparedRestore) {
        PrefsBackup.apply(context, prepared.snapshot)
        if (prepared.contents.picturesIncluded) images.restoreChosenPictures(prepared.contents.pictures)
        keys.save(prepared.material)
        prefs.welcomeDone = true
        prefs.refresh()
        DevLog.d("backup") { "restore result=ok files=${prepared.snapshot.fileCount} pictures=${prepared.pictureCount}" }
    }

    private companion object {
        const val WRONG_SECRET_DELAY_MS = 1_000L
    }
}
