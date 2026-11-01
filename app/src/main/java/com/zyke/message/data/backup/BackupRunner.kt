package com.zyke.message.data.backup

import android.content.Context
import android.os.SystemClock
import com.zyke.message.data.diag.DevLog
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Runs one backup: builds the encrypted file once and stores it in each chosen place. Every run is recorded in the
 * developer log under "backup" (trigger, place, result, size, time taken, failure class); the PIN, the key, tokens,
 * account names, file contents, amounts and names are never written.
 */
class BackupRunner(
    context: Context,
    val prefs: BackupPrefs,
    private val engine: BackupEngine,
    private val drive: DriveSync?,
    private val folder: FolderBackupStore
) {
    private val lock = Mutex()
    private companion object {
        val OFFLINE_PLACES = listOf(BackupMechanism.LOCAL, BackupMechanism.FOLDER)
    }

    /** Which places succeeded. [driveToken] is for a run the person started after signing in on screen. */
    suspend fun run(trigger: BackupTrigger, places: Set<BackupMechanism>, driveToken: String? = null): Map<BackupMechanism, Boolean> {
        if (places.isEmpty()) return emptyMap()
        return lock.withLock {
            prefs.setRunning(true)
            try {
                withContext(Dispatchers.IO) { runLocked(trigger, places, driveToken) }
            } finally {
                prefs.setRunning(false)
            }
        }
    }

    /**
     * The places that need no network (this phone, the chosen folder) whose scheduled time has passed without a good
     * backup since: what to catch up on while the app is open.
     */
    suspend fun runOfflineIfDue() {
        val view = prefs.view.value
        val now = Instant.now()
        val due = OFFLINE_PLACES.filter { m ->
            view.enabled(m) &&
                !view.schedule.nextAttempt(now, ZoneId.systemDefault(), view.lastSuccess(m), view.lastFailure(m)).isAfter(now)
        }.toSet()
        if (due.isNotEmpty()) run(BackupTrigger.FOREGROUND, due)
    }

    private suspend fun runLocked(trigger: BackupTrigger, places: Set<BackupMechanism>, driveToken: String?): Map<BackupMechanism, Boolean> {
        val started = SystemClock.elapsedRealtime()
        val results = LinkedHashMap<BackupMechanism, Boolean>()
        val built = try {
            engine.build()
        } catch (e: BackupFailureException) {
            for (m in places) fail(trigger, m, e.failure, started).also { results[m] = false }
            return results
        } catch (e: Exception) {
            for (m in places) fail(trigger, m, BackupFailure.UNKNOWN, started).also { results[m] = false }
            return results
        }
        prefs.picturesSkipped = built.picturesSkipped
        for (m in places) {
            val outcome = try {
                when (m) {
                    BackupMechanism.LOCAL -> { engine.writeLocal(built.bytes); built.bytes.size.toLong() }
                    BackupMechanism.FOLDER -> folder.save(built.bytes, Instant.now())
                    BackupMechanism.DRIVE -> (drive ?: throw BackupFailureException(BackupFailure.NOT_SIGNED_IN))
                        .upload(built.bytes, Instant.now(), driveToken)
                }
            } catch (e: BackupFailureException) {
                fail(trigger, m, e.failure, started)
                results[m] = false
                continue
            } catch (e: Exception) {
                fail(trigger, m, BackupFailure.UNKNOWN, started)
                results[m] = false
                continue
            }
            prefs.recordSuccess(m, Instant.now(), outcome)
            DevLog.d("backup") { "run trigger=$trigger place=$m result=ok size=${outcome}B pictures=${if (built.picturesSkipped) "skipped" else "kept"} took=${SystemClock.elapsedRealtime() - started}ms" }
            results[m] = true
        }
        return results
    }

    private fun fail(trigger: BackupTrigger, m: BackupMechanism, failure: BackupFailure, started: Long) {
        prefs.recordFailure(m, Instant.now(), failure)
        DevLog.d("backup") { "run trigger=$trigger place=$m result=failed class=$failure took=${SystemClock.elapsedRealtime() - started}ms" }
    }

    /** Where the Auto Backup file lives, for the restore step. */
    fun hasLocalFile(): Boolean = engine.readLocalOrNull() != null
}
