package com.zyke.message.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.FlightNotificationText
import com.zyke.message.domain.flight.FlightTracking
import com.zyke.message.domain.flight.StopReason
import com.zyke.message.domain.flight.TrackDecision
import com.zyke.message.domain.flight.TrackPhase
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Watches a flight that is about to leave or in the air and keeps one ongoing notification for it up to date, every 30 seconds.
 * A foreground service of type dataSync (declared in the manifest, with FOREGROUND_SERVICE_DATA_SYNC), because it fetches.
 *
 * It asks only through [com.zyke.message.data.flights.FlightService], which owns the one pinned fetcher, so the host,
 * timeouts and caps are the ones every other flight request has. It runs only while: live status and the tracking switch are on,
 * notifications are allowed, and a flight (at most [FlightTracking.MAX_TRACKED]) is within half an hour of leaving or in the air.
 * It ends by itself on landing (leaving a dismissible "Landed" notification), cancellation or diversion (the same), the
 * cut-off 30 minutes after the planned landing, the person pressing "Stop tracking", a switch being turned off, notifications
 * being revoked, or the system's time limit (Android 15 gives a data-sync service about six hours; then a dismissible
 * "Tracking paused" notification is left). After more than 10 answers in a row that fail it says "Can't refresh · last update
 * 14:32" and asks only every five minutes until one works; the notification stays until the cut-off.
 *
 * It is not sticky: if the system ends the process the notification goes with it, and it starts again only when the app is
 * opened or the alarm for the next flight goes off. Nothing here writes a flight number, route or status to the log.
 */
class FlightTrackingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var startedAt = 0L
    /** The notification id the system has tied to this service, or null. */
    private var foregroundId: Int? = null
    private val shown = LinkedHashMap<String, Int>()
    private val failures = HashMap<String, Int>()
    private val seenAttempt = HashMap<String, Long>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopFlight(intent.getStringExtra(EXTRA_KEY))
            return START_NOT_STICKY
        }
        FlightNotifier.ensureChannels(this)
        val container = (application as ZykeMessagesApplication).container
        // A foreground start must be answered with a notification at once, whatever happens next.
        val first = if (DemoMode.active) null else FlightTracking.active(FlightTrackingControl.decisions(container)).firstOrNull()
        val id = first?.let { FlightNotifier.idFor(it.key) } ?: FlightNotifier.PLACEHOLDER_ID
        val notification = first?.let { FlightNotifier.ongoing(this, it, id, noticeFor(it, container, Instant.now())) } ?: FlightNotifier.placeholder(this)
        // The system may refuse (Android 15 once the day's data-sync time is used up): then the service simply ends.
        if (!runCatching { startInForeground(id, notification) }.isSuccess) {
            running = false
            stopSelf()
            return START_NOT_STICKY
        }
        foregroundId = id
        running = true
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
        val flights = container.flights
        while (true) {
            val liveOn = flights.liveEnabled.value
            val trackingOn = flights.trackingEnabled.value
            val canNotify = FlightTrackingControl.canNotify(this)
            val before = FlightTrackingControl.decisions(container, Instant.now(), zone)
            val runMs = System.currentTimeMillis() - startedAt
            val early = FlightTracking.stopReason(liveOn, trackingOn, canNotify, before, runMs)
            if (early != null && early != StopReason.NOTHING_TO_WATCH) {
                end(early)
                return
            }
            // One question for each flight being watched (and one more for any that may just have landed).
            val toAsk = before.filter { it.phase.isActive || it.key in shown }.map { it.flight }
            if (toAsk.isNotEmpty()) flights.refresh(toAsk, zone)
            val now = Instant.now()
            val ds = FlightTrackingControl.decisions(container, now, zone)
            countFailures(ds)
            val active = FlightTracking.active(ds)
            update(ds, active, container, now)
            if (active.isEmpty()) {
                end(StopReason.NOTHING_TO_WATCH)
                return
            }
            val worst = active.maxOf { failures[it.key] ?: 0 }
            DevLog.d("flight") { "tracking: tick, ${active.size} watched, failures=$worst" }
            delay(FlightTracking.pollDelayMs(worst))
        }
    }

    /** A failure is counted once for each try, not once for each look at the result (the sheet may have asked a moment before). */
    private fun countFailures(ds: List<TrackDecision>) {
        val container = (application as ZykeMessagesApplication).container
        val live = container.flights.live.value
        for (d in ds.filter { it.phase.isActive }) {
            val attempt = container.flights.lastAttemptAt(d.key)
            if (seenAttempt[d.key] == attempt) continue
            seenAttempt[d.key] = attempt
            failures[d.key] = if (d.key in live.failed) (failures[d.key] ?: 0) + 1 else 0
        }
    }

    private fun noticeFor(d: TrackDecision, container: com.zyke.message.di.AppContainer, now: Instant): FlightNotice {
        val updated = container.flights.live.value.statuses[d.key]?.updatedAt?.takeIf { it > 0 }
        val failed = FlightTracking.refreshFailed(failures[d.key] ?: 0)
        val zone = ZoneId.systemDefault()
        return if (d.phase == TrackPhase.DEPARTING) FlightNotificationText.departing(d.snapshot, now, zone, updated, failed)
        else FlightNotificationText.inAir(d.snapshot, now, zone, updated, failed)
    }

    /** Posts the ongoing notices, then the final notice of every flight that has just finished. */
    private fun update(ds: List<TrackDecision>, active: List<TrackDecision>, container: com.zyke.message.di.AppContainer, now: Instant) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val taken = HashSet<Int>()
        val ids = LinkedHashMap<String, Int>()
        for (d in active) {
            val id = FlightNotifier.idFor(d.key, taken)
            taken += id
            ids[d.key] = id
        }
        // The one the system ties to the service stays the same while that flight is watched; otherwise the first.
        val anchorKey = active.firstOrNull { ids[it.key] == foregroundId }?.key ?: active.firstOrNull()?.key
        for (d in active) {
            val id = ids.getValue(d.key)
            val notification = FlightNotifier.ongoing(this, d, id, noticeFor(d, container, now))
            if (d.key == anchorKey && foregroundId != id) {
                startInForeground(id, notification)
                foregroundId = id
            } else {
                manager.notify(id, notification)
            }
            shown[d.key] = id
        }
        // Flights that were shown and are no longer watched: finished (a last notice) or no longer due (taken down).
        val gone = shown.keys.filter { it !in ids }
        if (active.isEmpty() && foregroundId != null) {
            stopForegroundKeepingNotification()
        }
        for (key in gone) {
            val id = shown.remove(key) ?: continue
            val d = ds.firstOrNull { it.key == key }
            val notice = d?.let { finalNotice(it) }
            if (d != null && notice != null) manager.notify(id, FlightNotifier.finished(this, d, id, notice)) else manager.cancel(id)
        }
    }

    private fun finalNotice(d: TrackDecision): FlightNotice? = when (d.phase) {
        TrackPhase.LANDED -> FlightNotificationText.landed(d.snapshot, d.landingEstimated)
        TrackPhase.CANCELLED -> FlightNotificationText.cancelled(d.snapshot)
        TrackPhase.DIVERTED -> FlightNotificationText.diverted(d.snapshot)
        TrackPhase.GAVE_UP -> FlightNotificationText.unconfirmed(d.snapshot)
        else -> null
    }

    /** The service stops being in the foreground but its notification stays (it is about to be replaced by a dismissible one). */
    private fun stopForegroundKeepingNotification() {
        stopForeground(STOP_FOREGROUND_DETACH)
        foregroundId = null
    }

    private fun end(reason: StopReason) {
        DevLog.d("flight") { "tracking: end ($reason)" }
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (reason == StopReason.NOTHING_TO_WATCH) {
            // update() has already replaced finished flights' notices; anything left over is taken down.
            shown.values.forEach { manager.cancel(it) }
            shown.clear()
            finishService(removeForeground = foregroundId != null)
            return
        }
        shown.values.forEach { manager.cancel(it) }
        shown.clear()
        if (reason == StopReason.TIME_LIMIT && FlightTrackingControl.canNotify(this)) manager.notify(FlightNotifier.PAUSED_ID, FlightNotifier.paused(this))
        finishService(removeForeground = true)
    }

    /** "Stop tracking": this flight is not tracked again (until the person adds it again); the others go on. */
    private fun stopFlight(key: String?) {
        val container = (application as ZykeMessagesApplication).container
        var stoppedId: Int? = null
        if (key != null) {
            container.flightPlan.stop(key)
            stoppedId = shown.remove(key) ?: FlightNotifier.idFor(key)
            FlightNotifier.cancel(this, stoppedId)
        }
        if (!running) {
            // Started only to carry out the stop (the notification outlived the service); nothing to run.
            stopSelf()
            return
        }
        // Look again at once: the loop moves the system's notification to another flight, or ends if none is left.
        loop?.cancel()
        val wasAnchor = stoppedId != null && foregroundId == stoppedId
        if (wasAnchor) foregroundId = null
        if (FlightTracking.active(FlightTrackingControl.decisions(container)).isNotEmpty()) {
            loop = scope.launch {
                if (wasAnchor) rehome(stoppedId)
                watch()
            }
        } else {
            finishService(removeForeground = true)
        }
    }

    /** The stopped flight's notification was the one the system had tied to the service: tie it to another flight, and take the old one down. */
    private fun rehome(stoppedId: Int?) {
        val container = (application as ZykeMessagesApplication).container
        val next = FlightTracking.active(FlightTrackingControl.decisions(container)).firstOrNull() ?: return
        val id = FlightNotifier.idFor(next.key)
        startInForeground(id, FlightNotifier.ongoing(this, next, id, noticeFor(next, container, Instant.now())))
        foregroundId = id
        shown[next.key] = id
        stoppedId?.let { FlightNotifier.cancel(this, it) }
    }

    private fun finishService(removeForeground: Boolean) {
        running = false
        loop?.cancel()
        if (removeForeground) stopForeground(STOP_FOREGROUND_REMOVE)
        FlightTrackingControl.rearm(this)
        stopSelf()
    }

    /** Android 15: a data-sync service may run about six hours in a day. The system calls this, and the service must stop at once. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        end(StopReason.TIME_LIMIT)
    }

    override fun onDestroy() {
        running = false
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

    companion object {
        const val ACTION_STOP = "com.zyke.message.action.STOP_FLIGHT_TRACKING"
        const val EXTRA_KEY = "com.zyke.message.extra.FLIGHT_KEY"

        /** The service is running in this process (so Home does not start it again). */
        @Volatile var running: Boolean = false
            internal set
    }
}
