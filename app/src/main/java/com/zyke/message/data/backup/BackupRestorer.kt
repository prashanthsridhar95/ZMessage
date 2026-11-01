package com.zyke.message.data.backup

import android.content.Context
import com.zyke.message.data.diag.DevLog
import com.zyke.message.data.images.ImageStore
import com.zyke.message.domain.backup.BackupContainer
import com.zyke.message.domain.backup.BackupContents
import com.zyke.message.domain.backup.BackupCrypto
import com.zyke.message.domain.backup.BackupException
import com.zyke.message.domain.backup.BackupFileCheck
import java.io.ByteArrayOutputStream
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

/** What reading a file the person chose gave: its text, or the sentence to tell them why not. */
sealed interface ChosenFile {
    class Text(val text: String) : ChosenFile
    class Rejected(val message: String) : ChosenFile
}

/** Opening a backup file and putting it in place. */
class BackupRestorer(
    private val context: Context,
    private val images: ImageStore,
    private val keys: BackupKeyStore,
    private val prefs: BackupPrefs
) {
    /**
     * Reads a file the person picked (any name: a `.zbk` from a folder, a manual export, the `.bin`). The first bytes
     * are checked for the Zyke backup header before the rest is read, and nothing over [BackupFileCheck.MAX_BYTES] is
     * read at all. Blocking: call off the main thread. Never throws.
     */
    fun readChosenFile(uri: android.net.Uri): ChosenFile = try {
        val resolver = context.contentResolver
        val size = runCatching {
            resolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
            }
        }.getOrNull() ?: -1L
        val input = resolver.openInputStream(uri)
        if (input == null) {
            ChosenFile.Rejected("That file could not be read.")
        } else input.use { stream ->
            val head = ByteArray(BackupFileCheck.HEAD_BYTES)
            var filled = 0
            while (filled < head.size) {
                val n = stream.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
            val verdict = BackupFileCheck.look(size, head.copyOf(filled))
            val refusal = BackupFileCheck.message(verdict)
            if (refusal != null) {
                ChosenFile.Rejected(refusal)
            } else {
                val out = ByteArrayOutputStream()
                out.write(head, 0, filled)
                val buf = ByteArray(16 * 1024)
                var total = filled.toLong()
                var tooBig = false
                while (true) {
                    val n = stream.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > BackupFileCheck.MAX_BYTES) { tooBig = true; break }
                    out.write(buf, 0, n)
                }
                if (tooBig) ChosenFile.Rejected(BackupFileCheck.message(BackupFileCheck.Verdict.TOO_BIG).orEmpty())
                else ChosenFile.Text(out.toString("UTF-8"))
            }
        }
    } catch (e: Exception) {
        DevLog.d("backup") { "choose file result=failed class=${e.javaClass.simpleName}" }
        ChosenFile.Rejected("That file could not be read.")
    } catch (e: OutOfMemoryError) {
        ChosenFile.Rejected("That file is too big to be a Zyke backup.")
    }

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
        // A phone whose key store refuses must not lose the restore: the setup is already back, and automatic backups
        // simply ask for the PIN again.
        runCatching { keys.save(prepared.material) }.onFailure { DevLog.d("backup") { "restore: key not kept: ${it.javaClass.simpleName}" } }
        FirstRun.markDone(context, "restored")
        prefs.refresh()
        DevLog.d("backup") { "restore result=ok files=${prepared.snapshot.fileCount} pictures=${prepared.pictureCount}" }
    }

    private companion object {
        const val WRONG_SECRET_DELAY_MS = 1_000L
    }
}
