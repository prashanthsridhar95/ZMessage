package com.zyke.message.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.StopReason
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches a trip (a flight or a train journey) that is about to start or under way and keeps one ongoing notification for it up to date,
 * every 30 seconds. A foreground service of type dataSync (declared in the manifest for [FlightTrackingService] and
 * [TrainTrackingService], with FOREGROUND_SERVICE_DATA_SYNC), because it fetches. This is the shared loop; each service is a thin
 * subclass that names its [control] (which supplies the kind's questions and words) and its stop action.
 *
 * It asks only through the kind's own data service (FlightService, TrainService), which owns the one pinned fetcher, so the host,
 * timeouts and caps are the ones every other request has. It runs only while: live status and the tracking switch are on,
 * notifications are allowed, and a trip (at most two in all, flights counted first) is within half an hour of leaving or under way.
 * It ends by itself on arrival (leaving a dismissible "Landed" or "Arrived" notification), cancellation or diversion (the same), the
 * cut-off 30 minutes after the planned arrival, the person pressing "Stop tracking", a switch being turned off, notifications
 * being revoked, or the system's time limit (Android 15 gives a data-sync service about six hours; then a dismissible
 * "Tracking paused" notification is left). After more than 10 answers in a row that fail it says "Can't refresh · last update
 * 14:32" and asks only every five minutes until one works; the notification stays until the cut-off.
 *
 * It is not sticky: if the system ends the process the notification goes with it, and it starts again only when the app is
 * opened or the alarm for the next trip goes off. Nothing here writes a trip number, route or status to the log.
 */
abstract class TripTrackingService<D : Any> : Service() {
    /** The kind's questions and words. */
    protected abstract val control: TripTrackingControl<*, D>

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var startedAt = 0L
    /** The notification id the system has tied to this service, or null. */
    private var foregroundId: Int? = null
    private val shown = LinkedHashMap<String, Int>()
    private val failures = HashMap<String, Int>()
    private val seenAttempt = HashMap<String, Long>()

    private val notifier: TripNotifier get() = control.notifier

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == notifier.stopAction) {
            stopTrip(intent.getStringExtra(notifier.stopExtra))
            return START_NOT_STICKY
        }
        notifier.ensureChannels(this)
        val container = (application as ZykeMessagesApplication).container
        // A foreground start must be answered with a notification at once, whatever happens next.
        val first = if (DemoMode.active) null else control.active(container, Instant.now()).firstOrNull()
        val id = first?.let { notifier.idFor(control.key(it)) } ?: notifier.placeholderId
        val notification = first?.let { notifier.ongoing(this, control.key(it), id, noticeFor(it, container, Instant.now())) } ?: notifier.placeholder(this)
        // The system may refuse (Android 15 once the day's data-sync time is used up): then the service simply ends.
        if (!runCatching { startInForeground(id, notification) }.isSuccess) {
            control.running = false
            stopSelf()
            return START_NOT_STICKY
        }
        foregroundId = id
        control.running = true
        if (first == null || DemoMode.active) {
            finishService(removeForeground = true)
            return START_NOT_STICKY
        }
        if (loop?.isActive != true) {
            startedAt = System.currentTimeMillis()
            loop = scope.launch { watch() }
        }
        return START_NOT_STICKY
    }

    private suspend fun watch() {
        val container = (application as ZykeMessagesApplication).container
        val zone = ZoneId.systemDefault()
        while (true) {
            val liveOn = control.liveEnabled(container)
            val trackingOn = control.trackingEnabled(container)
            val canNotify = control.canNotify(this)
            val before = control.decisions(container, Instant.now(), zone)
            val runMs = System.currentTimeMillis() - startedAt
            val early = control.stopReason(container, liveOn, trackingOn, canNotify, before, runMs)
            if (early != null && early != StopReason.NOTHING_TO_WATCH) {
                end(early)
                return
            }
            // One question for each trip being watched (and one more for any that may just have finished).
            val toAsk = before.filter { control.isActive(it) || control.key(it) in shown }
            if (toAsk.isNotEmpty()) control.refresh(container, toAsk, zone)
            val now = Instant.now()
            val ds = control.decisions(container, now, zone)
            countFailures(ds)
            val active = control.activeOf(container, ds)
            update(ds, active, container, now)
            if (active.isEmpty()) {
                end(StopReason.NOTHING_TO_WATCH)
                return
            }
            val worst = active.maxOf { failures[control.key(it)] ?: 0 }
            DevLog.d(control.logTag) { "tracking: tick, ${active.size} watched, failures=$worst" }
            delay(control.pollDelayMs(worst))
        }
    }

    /** A failure is counted once for each try, not once for each look at the result (the sheet may have asked a moment before). */
    private fun countFailures(ds: List<D>) {
        val container = (application as ZykeMessagesApplication).container
        val failed = control.failedKeys(container)
        for (d in ds.filter { control.isActive(it) }) {
            val key = control.key(d)
            val attempt = control.lastAttemptAt(container, key)
            if (seenAttempt[key] == attempt) continue
            seenAttempt[key] = attempt
            failures[key] = if (key in failed) (failures[key] ?: 0) + 1 else 0
        }
    }

    private fun noticeFor(d: D, container: com.zyke.message.di.AppContainer, now: Instant): FlightNotice {
        val key = control.key(d)
        val updated = control.updatedAt(container, key)
        val failed = control.refreshFailed(failures[key] ?: 0)
        val zone = ZoneId.systemDefault()
        return control.ongoingNotice(d, now, zone, updated, failed)
    }

    /** Posts the ongoing notices, then the final notice of every trip that has just finished. */
    private fun update(ds: List<D>, active: List<D>, container: com.zyke.message.di.AppContainer, now: Instant) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val taken = HashSet<Int>()
        val ids = LinkedHashMap<String, Int>()
        for (d in active) {
            val key = control.key(d)
            val id = notifier.idFor(key, taken)
            taken += id
            ids[key] = id
        }
        // The one the system ties to the service stays the same while that trip is watched; otherwise the first.
        val anchorKey = active.firstOrNull { ids[control.key(it)] == foregroundId }?.let { control.key(it) } ?: active.firstOrNull()?.let { control.key(it) }
        for (d in active) {
            val key = control.key(d)
            val id = ids.getValue(key)
            val notification = notifier.ongoing(this, key, id, noticeFor(d, container, now))
            if (key == anchorKey && foregroundId != id) {
                startInForeground(id, notification)
                foregroundId = id
            } else {
                manager.notify(id, notification)
            }
            shown[key] = id
        }
        // Trips that were shown and are no longer watched: finished (a last notice) or no longer due (taken down).
        val gone = shown.keys.filter { it !in ids }
        if (active.isEmpty() && foregroundId != null) {
            stopForegroundKeepingNotification()
        }
        for (key in gone) {
            val id = shown.remove(key) ?: continue
            val d = ds.firstOrNull { control.key(it) == key }
            val notice = d?.let { control.finalNotice(it) }
            if (d != null && notice != null) manager.notify(id, notifier.finished(this, key, id, notice)) else manager.cancel(id)
        }
    }

    /** The service stops being in the foreground but its notification stays (it is about to be replaced by a dismissible one). */
    private fun stopForegroundKeepingNotification() {
        stopForeground(STOP_FOREGROUND_DETACH)
        foregroundId = null
    }

    private fun end(reason: StopReason) {
        DevLog.d(control.logTag) { "tracking: end ($reason)" }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (reason == StopReason.NOTHING_TO_WATCH) {
            // update() has already replaced finished trips' notices; anything left over is taken down.
            shown.values.forEach { manager.cancel(it) }
            shown.clear()
            finishService(removeForeground = foregroundId != null)
            return
        }
        shown.values.forEach { manager.cancel(it) }
        shown.clear()
        if (reason == StopReason.TIME_LIMIT && control.canNotify(this)) manager.notify(notifier.pausedId, notifier.paused(this))
        finishService(removeForeground = true)
    }

    /** "Stop tracking": this trip is not tracked again (until the person adds it again); the others go on. */
    private fun stopTrip(key: String?) {
        val container = (application as ZykeMessagesApplication).container
        var stoppedId: Int? = null
        if (key != null) {
            control.stopTracking(container, key)
            stoppedId = shown.remove(key) ?: notifier.idFor(key)
            notifier.cancel(this, stoppedId)
        }
        if (!control.running) {
            // Started only to carry out the stop (the notification outlived the service); nothing to run.
            stopSelf()
            return
        }
        // Look again at once: the loop moves the system's notification to another trip, or ends if none is left.
        loop?.cancel()
        val wasAnchor = stoppedId != null && foregroundId == stoppedId
        if (wasAnchor) foregroundId = null
        if (control.active(container, Instant.now()).isNotEmpty()) {
            loop = scope.launch {
                if (wasAnchor) rehome(stoppedId)
                watch()
            }
        } else {
            finishService(removeForeground = true)
        }
    }

    /** The stopped trip's notification was the one the system had tied to the service: tie it to another trip, and take the old one down. */
    private fun rehome(stoppedId: Int?) {
        val container = (application as ZykeMessagesApplication).container
        val next = control.active(container, Instant.now()).firstOrNull() ?: return
        val key = control.key(next)
        val id = notifier.idFor(key)
        startInForeground(id, notifier.ongoing(this, key, id, noticeFor(next, container, Instant.now())))
        foregroundId = id
        shown[key] = id
        stoppedId?.let { notifier.cancel(this, it) }
    }

    private fun finishService(removeForeground: Boolean) {
        control.running = false
        loop?.cancel()
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        control.rearm(this)
        stopSelf()
    }

    /** Android 15: a data-sync service may run about six hours in a day. The system calls this, and the service must stop at once. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        end(StopReason.TIME_LIMIT)
    }

    override fun onDestroy() {
        control.running = false
        scope.cancel()
        // Whatever ongoing notices are left come down with the service; finished notices were posted as dismissible ones and stay.
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        shown.values.forEach { manager.cancel(it) }
        super.onDestroy()
    }

    private fun startInForeground(id: Int, notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(id, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(id, notification)
        }
    }
}
