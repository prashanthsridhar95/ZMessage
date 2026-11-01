package com.zyke.message.data.backup

import android.app.job.JobParameters
import android.app.job.JobService
import com.zyke.message.ZykeMessagesApplication
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** The system starts this when a scheduled backup is due and its conditions (Wi-Fi, charging) hold. Not exported. */
class BackupJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<Int, Job>()

    override fun onStartJob(params: JobParameters): Boolean {
        val hub = runCatching { (application as ZykeMessagesApplication).container.backup }.getOrNull() ?: return false
        val place = hub.scheduler.mechanismFor(params.jobId) ?: return false
        running[params.jobId] = scope.launch {
            val ok = try {
                hub.runner.run(BackupTrigger.SCHEDULE, setOf(place))[place] == true
            } catch (e: Exception) {
                false
            }
            val failure = hub.prefs.view.value.failure[place]?.failure
            // A passing problem (no network, Drive busy) is retried by the system a few times with growing waits;
            // anything else waits for the next due time.
            val retry = !ok && failure?.retrySoon == true && hub.prefs.attempts < MAX_QUICK_RETRIES
            hub.prefs.attempts = if (retry) hub.prefs.attempts + 1 else 0
            if (!retry) hub.scheduler.rescheduleAfterRun(place, params.jobId)
            running.remove(params.jobId)
            jobFinished(params, retry)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        running.remove(params.jobId)?.cancel()
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[Job]?.cancel()
    }

    private companion object {
        const val MAX_QUICK_RETRIES = 3
    }
}
