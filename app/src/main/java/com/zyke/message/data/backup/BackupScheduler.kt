package com.zyke.message.data.backup

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import com.zyke.message.data.diag.DevLog
import java.time.Instant
import java.time.ZoneId

/**
 * Puts the next backup in the system's job queue (JobScheduler: no library, no extra permission; jobs survive a
 * restart). One job per place. Each is a one-time job with a delay up to the next due time; when it has run, the
 * next one is queued. Android runs it near that time, when the phone is idle and the chosen conditions hold, so it
 * can come a little late.
 *
 * Each place has two job ids that take turns, so the job that is running can queue its successor without replacing itself.
 */
class BackupScheduler(context: Context, private val prefs: BackupPrefs) {
    private val app = context.applicationContext
    private val jobs = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler

    private fun ids(m: BackupMechanism): IntArray = when (m) {
        BackupMechanism.LOCAL -> intArrayOf(LOCAL_A, LOCAL_B)
        BackupMechanism.DRIVE -> intArrayOf(DRIVE_A, DRIVE_B)
        BackupMechanism.FOLDER -> intArrayOf(FOLDER_A, FOLDER_B)
    }

    /** Which place a job id belongs to. */
    fun mechanismFor(jobId: Int): BackupMechanism? = BackupMechanism.values().firstOrNull { jobId in ids(it) }

    private fun enabled(m: BackupMechanism, view: BackupView) = view.enabled(m)

    /** After the settings or schedule changed: queue everything afresh. */
    fun rescheduleAll(now: Instant = Instant.now()) {
        for (m in BackupMechanism.values()) runCatching { schedule(m, fromJobId = null, now = now) }
    }

    /** On app start and after a restart: queue only what is missing, so a running job is never disturbed. */
    fun ensureScheduled(now: Instant = Instant.now()) {
        val pending = runCatching { jobs.allPendingJobs.map { it.id }.toSet() }.getOrDefault(emptySet())
        for (m in BackupMechanism.values()) runCatching {
            val on = enabled(m, prefs.view.value)
            if (on && ids(m).none { it in pending }) schedule(m, null, now)
            if (!on && ids(m).any { it in pending }) schedule(m, null, now)
        }
    }

    /** Called by the job that has just finished (or given up) to queue its successor. */
    fun rescheduleAfterRun(m: BackupMechanism, finishedJobId: Int, now: Instant = Instant.now()) {
        runCatching { schedule(m, finishedJobId, now) }
    }

    private fun schedule(m: BackupMechanism, fromJobId: Int?, now: Instant) {
        val view = prefs.view.value
        val pair = ids(m)
        if (!enabled(m, view)) {
            if (fromJobId == null) pair.forEach { jobs.cancel(it) }
            return
        }
        val next = view.schedule.nextAttempt(now, ZoneId.systemDefault(), view.lastSuccess(m), view.lastFailure(m))
        val delay = (next.toEpochMilli() - now.toEpochMilli()).coerceAtLeast(0L)
        val pending = jobs.allPendingJobs.map { it.id }.toSet()
        val id = when {
            fromJobId != null && fromJobId in pair -> pair.first { it != fromJobId }
            else -> pair.firstOrNull { it !in pending } ?: pair[0]
        }
        val network = when {
            // Writing to this phone or to a chosen folder needs no network, so those jobs never wait for one.
            m == BackupMechanism.LOCAL || m == BackupMechanism.FOLDER -> JobInfo.NETWORK_TYPE_NONE
            view.wifiOnly -> JobInfo.NETWORK_TYPE_UNMETERED
            else -> JobInfo.NETWORK_TYPE_ANY
        }
        val job = JobInfo.Builder(id, ComponentName(app, BackupJobService::class.java))
            .setMinimumLatency(delay)
            .setRequiredNetworkType(network)
            .setRequiresCharging(view.chargingOnly)
            .setPersisted(true)
            .setBackoffCriteria(BACKOFF_MS, JobInfo.BACKOFF_POLICY_EXPONENTIAL)
            .build()
        // A refusal by the system (for example a missing permission on some builds) must never crash the app or the job.
        val result = try { jobs.schedule(job) } catch (e: Exception) {
            DevLog.d("backup") { "queue refused place=$m: ${e.javaClass.simpleName}" }
            JobScheduler.RESULT_FAILURE
        }
        if (fromJobId == null) pair.filter { it != id }.forEach { jobs.cancel(it) }
        DevLog.d("backup") { "queued place=$m in=${delay / 60_000}min accepted=${result == JobScheduler.RESULT_SUCCESS}" }
    }

    companion object {
        private const val LOCAL_A = 8101
        private const val LOCAL_B = 8102
        private const val DRIVE_A = 8103
        private const val DRIVE_B = 8104
        private const val FOLDER_A = 8105
        private const val FOLDER_B = 8106
        private const val BACKOFF_MS = 60_000L
    }
}
