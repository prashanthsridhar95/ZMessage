package com.zyke.message.data.backup

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.backup.DriveRetention
import com.zyke.message.domain.backup.RemoteBackup
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [DriveSync] over [DriveAuth] and [DriveBackupClient]: gets a token, uploads, then keeps only the newest seven. */
class GoogleDriveSync(
    private val auth: DriveAuth,
    private val client: DriveBackupClient,
    private val consentedBefore: () -> Boolean,
    private val onConsented: () -> Unit
) : DriveSync {
    private suspend fun accessToken(given: String?): String {
        if (given != null) { onConsented(); return given }
        return when (val r = auth.authorize()) {
            is DriveAuthResult.Token -> { onConsented(); r.value }
            is DriveAuthResult.NeedsUser -> throw BackupFailureException(if (consentedBefore()) BackupFailure.AUTH_EXPIRED else BackupFailure.NOT_SIGNED_IN)
            is DriveAuthResult.Failed -> throw BackupFailureException(r.failure)
        }
    }

    override suspend fun upload(bytes: ByteArray, createdAt: Instant, token: String?): Long {
        val t = accessToken(token)
        return withContext(Dispatchers.IO) {
            val name = "zyke-backup-" + DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneOffset.UTC).format(createdAt) + ".zbk"
            val uploaded = client.upload(t, name, bytes)
            // Keep the newest seven; a failure to tidy up does not undo a good backup.
            runCatching {
                val toDelete = DriveRetention.toDelete(client.list(t)).filter { it.id != uploaded.id }
                for (old in toDelete) client.delete(t, old.id)
                DevLog.d("backup") { "drive kept=${DriveRetention.KEEP} removed=${toDelete.size}" }
            }
            uploaded.sizeBytes
        }
    }

    override suspend fun list(token: String?): List<RemoteBackup> {
        val t = accessToken(token)
        return withContext(Dispatchers.IO) { client.list(t) }
    }

    override suspend fun download(id: String, token: String?): String {
        val t = accessToken(token)
        return withContext(Dispatchers.IO) { client.download(t, id) }
    }
}
