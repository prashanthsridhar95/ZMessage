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
    }

    /** Which place a job id belongs to. */
    fun mechanismFor(jobId: Int): BackupMechanism? = BackupMechanism.values().firstOrNull { jobId in ids(it) }

    private fun enabled(m: BackupMechanism, view: BackupView) = when (m) {
        BackupMechanism.LOCAL -> view.localEnabled
        BackupMechanism.DRIVE -> view.driveEnabled
    }

    /** After the settings or schedule changed: queue everything afresh. */
    fun rescheduleAll(now: Instant = Instant.now()) {
        for (m in BackupMechanism.values()) schedule(m, fromJobId = null, now = now)
    }

    /** On app start and after a restart: queue only what is missing, so a running job is never disturbed. */
    fun ensureScheduled(now: Instant = Instant.now()) {
        val pending = jobs.allPendingJobs.map { it.id }.toSet()
        for (m in BackupMechanism.values()) {
            val on = enabled(m, prefs.view.value)
            if (on && ids(m).none { it in pending }) schedule(m, null, now)
            if (!on && ids(m).any { it in pending }) schedule(m, null, now)
        }
    }

    /** Called by the job that has just finished (or given up) to queue its successor. */
    fun rescheduleAfterRun(m: BackupMechanism, finishedJobId: Int, now: Instant = Instant.now()) = schedule(m, finishedJobId, now)

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
            m == BackupMechanism.LOCAL -> JobInfo.NETWORK_TYPE_NONE
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
        val result = jobs.schedule(job)
        if (fromJobId == null) pair.filter { it != id }.forEach { jobs.cancel(it) }
        DevLog.d("backup") { "queued place=$m in=${delay / 60_000}min accepted=${result == JobScheduler.RESULT_SUCCESS}" }
    }

    companion object {
        private const val LOCAL_A = 8101
        private const val LOCAL_B = 8102
        private const val DRIVE_A = 8103
        private const val DRIVE_B = 8104
        private const val BACKOFF_MS = 60_000L
    }
}
