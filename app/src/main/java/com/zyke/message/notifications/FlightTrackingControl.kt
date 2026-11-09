package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.data.diag.DevLog
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.flight.BookedFlight
import com.zyke.message.domain.flight.FlightNotificationText
import com.zyke.message.domain.flight.FlightTracking
import com.zyke.message.domain.flight.ManualFlights
import com.zyke.message.domain.flight.TrackDecision
import java.time.Instant
import java.time.ZoneId

/**
 * When the in-flight notification starts, and the one alarm that can start it with the app closed.
 *
 * HOW IT STARTS (the honest version):
 * 1. The app coming to the front ([onForeground], from Home): if live status and the tracking switch are on, notifications are
 *    allowed and a flight is in the air or leaves within [FlightTracking.START_LEAD_MINUTES], the service is started. An
 *    activity that is showing may start a foreground service on every Android version.
 * 2. One alarm for the next flight, [FlightTracking.ALARM_LEAD_MINUTES] before it leaves. The app asks for no exact-alarm
 *    permission (like the morning digest and the reminders, the alarm is inexact and Android may move it a few minutes, or more
 *    while the phone dozes). When it goes off, [onAlarm] tries to start the service. Android 12 and later refuse that from the
 *    background, so there the alarm posts a "Tap to start live tracking" notification and tapping it opens the app, which starts
 *    the service from the foreground. Before Android 12 the service starts by itself. With live status off the alarm posts an
 *    ordinary dismissible reminder instead and nothing ongoing.
 * 3. The alarm is set again after a restart ([com.zyke.message.notifications.BootReceiver]), whenever flights, switches or the
 *    reminder change, and after it fires. It is never set in Demo mode.
 *
 * It does not start the service from BOOT_COMPLETED (not allowed for a data-sync service on Android 15).
 */
object FlightTrackingControl {
    private const val REQUEST_CODE = 811

    private fun container(context: Context): AppContainer = (context.applicationContext as ZykeMessagesApplication).container

    /** The flights found in texts (as last written by Home) and those added by hand, as one list. */
    fun flights(container: AppContainer): List<BookedFlight> =
        ManualFlights.merge(container.flightPlan.fromTexts(), container.manualFlights.flights.value)

    fun decisions(container: AppContainer, now: Instant = Instant.now(), zone: ZoneId = ZoneId.systemDefault()): List<TrackDecision> =
        FlightTracking.decisions(flights(container), container.flights.live.value.statuses, now, zone, container.flightPlan.stopped())

    /** Notifications are allowed (permission, and not switched off for the app); never in Demo mode. */
    fun canNotify(context: Context): Boolean =
        DigestNotifier.canNotify(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Called when Home is showing (and when flights or switches change). Writes the plan, sets the alarm, starts the service if one is due. */
    fun onForeground(context: Context, fromTexts: List<BookedFlight>) {
        if (DemoMode.active) return
        val app = context.applicationContext
        val container = container(app)
        container.flightPlan.saveFromTexts(fromTexts)
        container.manualFlights.prune(Instant.now(), ZoneId.systemDefault())
        rearm(app)
        if (FlightTrackingService.running) return
        val ds = decisions(container)
        val live = container.flights.liveEnabled.value
        val tracking = container.flights.trackingEnabled.value
        if (FlightTracking.shouldRun(live, tracking, canNotify(app), ds)) {
            DevLog.d("flight") { "tracking: start from the app" }
            startService(app)
        }
    }

    /** Puts the one alarm where the next flight needs it, or clears it. Safe to call whenever flights or switches change. */
    fun rearm(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(app)
        alarms.cancel(pending)
        // Demo mode sets no alarm; the real app sets its own on its next start.
        if (DemoMode.active) return
        val container = container(app)
        if (!container.flights.trackingEnabled.value) return
        val now = Instant.now()
        val at = FlightTracking.nextAlarm(decisions(container, now), now) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
    }

    /** Clears the alarm and stops the service (Delete all, switching tracking off). */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(app))
        runCatching { app.stopService(Intent(app, FlightTrackingService::class.java)) }
    }

    /** Starts the service as a foreground service. False when the system refuses (Android 12 and later, from the background). */
    fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, FlightTrackingService::class.java))
        true
    } catch (e: Exception) {
        DevLog.d("flight") { "tracking: start refused (${e.javaClass.simpleName})" }
        false
    }

    /** The alarm went off: start the service, or leave a notification the person can tap, or (live status off) a plain reminder. */
    fun onAlarm(context: Context) {
        if (DemoMode.active) return
        val app = context.applicationContext
        val container = container(app)
        try {
            val tracking = container.flights.trackingEnabled.value
            val live = container.flights.liveEnabled.value
            if (!tracking || !canNotify(app)) return
            val now = Instant.now()
            val active = FlightTracking.active(decisions(container, now))
            DevLog.d("flight") { "tracking: alarm, ${active.size} to watch, live=$live" }
            if (active.isEmpty()) return
            FlightNotifier.ensureChannels(app)
            if (live) {
                if (!FlightTrackingService.running && !startService(app)) {
                    val first = active.first()
                    FlightNotifier.post(app, FlightNotifier.idFor(first.key), FlightNotifier.prompt(app, first, FlightNotificationText.tapToStart(first.snapshot, now)))
                }
            } else {
                for (d in active) {
                    if (container.flightPlan.markReminded(d.key)) {
                        FlightNotifier.post(app, FlightNotifier.idFor(d.key), FlightNotifier.prompt(app, d, FlightNotificationText.reminder(d.snapshot, now)))
                    }
                }
            }
        } finally {
            rearm(app)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, FlightAlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Hears the flight alarm. Not exported: only the system's alarm manager, on this app's behalf, reaches it. */
class FlightAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        FlightTrackingControl.onAlarm(context)
    }
}
