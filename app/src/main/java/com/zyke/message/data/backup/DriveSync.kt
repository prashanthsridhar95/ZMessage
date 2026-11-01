package com.zyke.message.data.backup

import com.zyke.message.domain.backup.RemoteBackup
import java.time.Instant

/** Everything the backup code needs from Google Drive, so the rest of it does not know about Google. */
interface DriveSync {
    /** Uploads [bytes] as the newest backup and deletes backups beyond the newest seven. Returns the uploaded size. */
    @Throws(BackupFailureException::class)
    suspend fun upload(bytes: ByteArray, createdAt: Instant, token: String?): Long

    /** Backups in the person's private Drive app folder, newest first. */
    @Throws(BackupFailureException::class)
    suspend fun list(token: String?): List<RemoteBackup>

    @Throws(BackupFailureException::class)
    suspend fun download(id: String, token: String?): String
}
