package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.data.diag.DevLog
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.ManualTrains
import com.zyke.message.domain.train.TrainDecision
import com.zyke.message.domain.train.TrainNotificationText
import com.zyke.message.domain.train.TrainTracking
import java.time.Instant

/**
 * When the train notification starts, and the one alarm that can start it with the app closed. The same shape as
 * [FlightTrackingControl], whose honest description applies (an activity in front may start the service; the inexact alarm,
 * 10 minutes before the train leaves the person's station, can only start it before Android 12 and otherwise leaves a "Tap to start live
 * tracking" notification; nothing starts it from the boot broadcast), with one difference: at most two trips are tracked in all, so a
 * train gets only the places flights have left over ([slots]).
 */
object TrainTrackingControl {
    private const val REQUEST_CODE = 812

    private fun container(context: Context): AppContainer = (context.applicationContext as ZykeMessagesApplication).container

    /** The journeys found in texts (as last written by Home) and those added by hand, as one list. */
    fun trains(container: AppContainer): List<BookedTrain> = ManualTrains.merge(container.trainPlan.fromTexts(), container.manualTrains.trains.value)

    fun decisions(container: AppContainer, now: Instant = Instant.now()): List<TrainDecision> =
        TrainTracking.decisions(trains(container), container.trains.live.value.statuses, now, container.trainPlan.stopped())

    /** Notifications are allowed (permission, and not switched off for the app); never in Demo mode. Same as for flights. */
    fun canNotify(context: Context): Boolean = FlightTrackingControl.canNotify(context)

    /** How many more trips may be tracked: two in all, flights counted first. */
    fun slots(container: AppContainer): Int =
        TrainTracking.slotsLeft(com.zyke.message.domain.flight.FlightTracking.active(FlightTrackingControl.decisions(container)).size)

    fun active(container: AppContainer, now: Instant = Instant.now()): List<TrainDecision> =
        TrainTracking.active(decisions(container, now), slots(container))

    /** Called when Home is showing (and when journeys or switches change). Writes the plan, sets the alarm, starts the service if one is due. */
    fun onForeground(context: Context, fromTexts: List<BookedTrain>) {
        if (DemoMode.active) return
        val app = context.applicationContext
        val container = container(app)
        container.trainPlan.saveFromTexts(fromTexts)
        container.manualTrains.prune(Instant.now())
        rearm(app)
        if (TrainTrackingService.running) return
        val ds = decisions(container)
        val live = container.trains.liveEnabled.value
        val tracking = container.trains.trackingEnabled.value
        if (TrainTracking.shouldRun(live, tracking, FlightTrackingControl.canNotify(app), ds, slots(container))) {
            DevLog.d("train") { "tracking: start from the app" }
            startService(app)
        }
    }

    /** Puts the one alarm where the next journey needs it, or clears it. Safe to call whenever journeys or switches change. */
    fun rearm(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(app)
        alarms.cancel(pending)
        // Demo mode sets no alarm; the real app sets its own on its next start.
        if (DemoMode.active) return
        val container = container(app)
        if (!container.trains.trackingEnabled.value) return
        val now = Instant.now()
        val at = TrainTracking.nextAlarm(decisions(container, now), now) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
    }

    /** Clears the alarm and stops the service (Delete all, switching tracking off). */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(app))
        runCatching { app.stopService(Intent(app, TrainTrackingService::class.java)) }
    }

    /** Starts the service as a foreground service. False when the system refuses (Android 12 and later, from the background). */
    fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, TrainTrackingService::class.java))
        true
    } catch (e: Exception) {
        DevLog.d("train") { "tracking: start refused (${e.javaClass.simpleName})" }
        false
    }

    /** The alarm went off: start the service, or leave a notification the person can tap, or (live status off) a plain reminder. */
    fun onAlarm(context: Context) {
        if (DemoMode.active) return
        val app = context.applicationContext
        val container = container(app)
        try {
            val tracking = container.trains.trackingEnabled.value
            val live = container.trains.liveEnabled.value
            if (!tracking || !FlightTrackingControl.canNotify(app)) return
            val now = Instant.now()
            val active = active(container, now)
            DevLog.d("train") { "tracking: alarm, ${active.size} to watch, live=$live" }
            if (active.isEmpty()) return
            TrainNotifier.ensureChannels(app)
            if (live) {
                if (!TrainTrackingService.running && !startService(app)) {
                    val first = active.first()
                    TrainNotifier.post(app, TrainNotifier.idFor(first.key), TrainNotifier.prompt(app, first, TrainNotificationText.tapToStart(first.snapshot, now)))
                }
            } else {
                for (d in active) {
                    if (container.trainPlan.markReminded(d.key)) {
                        TrainNotifier.post(app, TrainNotifier.idFor(d.key), TrainNotifier.prompt(app, d, TrainNotificationText.reminder(d.snapshot, now)))
                    }
                }
            }
        } finally {
            rearm(app)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, TrainAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Hears the train alarm. Not exported: only the system's alarm manager, on this app's behalf, reaches it. */
class TrainAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        TrainTrackingControl.onAlarm(context)
    }
}
