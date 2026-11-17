package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.data.demo.DemoMode
import com.zyke.message.data.diag.DevLog
import com.zyke.message.di.AppContainer
import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.StopReason
import java.time.Instant
import java.time.ZoneId

/**
 * When the live trip notification starts, and the one alarm that can start it with the app closed. The shared half of
 * [FlightTrackingControl] and [TrainTrackingControl]: the start rules and the alarm are written once here, and each kind supplies
 * (below, under "What a kind supplies") only its own questions: which trips there are, what the domain's rules say about them,
 * and the words of its notices. [TripTrackingService] asks the same hooks for the loop. [B] is the booked trip, [D] the decision.
 *
 * HOW IT STARTS (the honest version):
 * 1. The app coming to the front ([onForeground], from Home): if live status and the tracking switch are on, notifications are
 *    allowed and a trip is under way or leaves within the start lead, the service is started. An
 *    activity that is showing may start a foreground service on every Android version.
 * 2. One alarm for the next trip, 10 minutes before it leaves. The app asks for no exact-alarm
 *    permission (like the morning digest and the reminders, the alarm is inexact and Android may move it a few minutes, or more
 *    while the phone dozes). When it goes off, [onAlarm] tries to start the service. Android 12 and later refuse that from the
 *    background, so there the alarm posts a "Tap to start live tracking" notification and tapping it opens the app, which starts
 *    the service from the foreground. Before Android 12 the service starts by itself. With live status off the alarm posts an
 *    ordinary dismissible reminder instead and nothing ongoing.
 * 3. The alarm is set again after a restart ([com.zyke.message.notifications.BootReceiver]), whenever trips, switches or the
 *    reminder change, and after it fires. It is never set in Demo mode.
 *
 * It does not start the service from BOOT_COMPLETED (not allowed for a data-sync service on Android 15).
 */
abstract class TripTrackingControl<B : Any, D : Any> {
    // ---- What a kind supplies (the same questions for both kinds; the answers come from the kind's own domain object).

    /** The tag of the log lines ("flight", "train"); the lines carry counts and switches only. */
    abstract val logTag: String
    protected abstract val requestCode: Int
    protected abstract val receiverClass: Class<out BroadcastReceiver>
    protected abstract val serviceClass: Class<out Service>
    abstract val notifier: TripNotifier

    /** The service of this kind is running in this process (so Home does not start it again). */
    abstract var running: Boolean

    abstract fun key(d: D): String
    abstract fun isActive(d: D): Boolean
    abstract fun liveEnabled(container: AppContainer): Boolean
    abstract fun trackingEnabled(container: AppContainer): Boolean
    abstract fun decisions(container: AppContainer, now: Instant, zone: ZoneId): List<D>
    /** The trips to watch now (a train takes only the places flights have left over). */
    abstract fun active(container: AppContainer, now: Instant): List<D>
    /** The same, from decisions already worked out. */
    abstract fun activeOf(container: AppContainer, ds: List<D>): List<D>
    abstract fun shouldRun(container: AppContainer, live: Boolean, tracking: Boolean, canNotify: Boolean, ds: List<D>): Boolean
    abstract fun stopReason(container: AppContainer, liveOn: Boolean, trackingOn: Boolean, canNotify: Boolean, ds: List<D>, runMs: Long): StopReason?
    abstract fun nextAlarm(ds: List<D>, now: Instant): Instant?
    abstract fun pollDelayMs(consecutiveFailures: Int): Long
    abstract fun refreshFailed(consecutiveFailures: Int): Boolean
    abstract fun savePlan(container: AppContainer, fromTexts: List<B>)
    abstract fun pruneManual(container: AppContainer)
    abstract fun markReminded(container: AppContainer, key: String): Boolean
    abstract fun stopTracking(container: AppContainer, key: String)
    abstract fun tapToStart(d: D, now: Instant): FlightNotice
    abstract fun reminder(d: D, now: Instant): FlightNotice

    // For the service's loop.
    /** The last time the source was asked about [key], as a stamp, and the keys whose last answer failed. */
    abstract fun lastAttemptAt(container: AppContainer, key: String): Long
    abstract fun failedKeys(container: AppContainer): Set<String>
    /** When the source last answered about [key] (null when it never did). */
    abstract fun updatedAt(container: AppContainer, key: String): Long?
    abstract suspend fun refresh(container: AppContainer, toAsk: List<D>, zone: ZoneId)
    abstract fun ongoingNotice(d: D, now: Instant, zone: ZoneId, updatedAt: Long?, refreshFailed: Boolean): FlightNotice
    /** The one dismissible notice of a trip that has finished, or null when the phase has none. */
    abstract fun finalNotice(d: D): FlightNotice?

    // ---- The shared rules.

    private fun container(context: Context): AppContainer = (context.applicationContext as ZykeMessagesApplication).container

    fun decisions(container: AppContainer): List<D> = decisions(container, Instant.now(), ZoneId.systemDefault())

    /** Notifications are allowed (permission, and not switched off for the app); never in Demo mode. The same for flights and trains. */
    fun canNotify(context: Context): Boolean =
        DigestNotifier.canNotify(context) && NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Called when Home is showing (and when trips or switches change). Writes the plan, sets the alarm, starts the service if one is due. */
    fun onForeground(context: Context, fromTexts: List<B>) {
        if (DemoMode.active) return
        val app = context.applicationContext
        val container = container(app)
        savePlan(container, fromTexts)
        pruneManual(container)
        rearm(app)
        if (running) return
        val ds = decisions(container)
        val live = liveEnabled(container)
        val tracking = trackingEnabled(container)
        if (shouldRun(container, live, tracking, canNotify(app), ds)) {
            DevLog.d(logTag) { "tracking: start from the app" }
            startService(app)
        }
    }

    /** Puts the one alarm where the next trip needs it, or clears it. Safe to call whenever trips or switches change. */
    fun rearm(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(app)
        alarms.cancel(pending)
        // Demo mode sets no alarm; the real app sets its own on its next start.
        if (DemoMode.active) return
        val container = container(app)
        if (!trackingEnabled(container)) return
        val now = Instant.now()
        val at = nextAlarm(decisions(container, now, ZoneId.systemDefault()), now) ?: return
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), pending)
    }

    /** Clears the alarm and stops the service (Delete all, switching tracking off). */
    fun cancelAll(context: Context) {
        val app = context.applicationContext
        (app.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(app))
        runCatching { app.stopService(Intent(app, serviceClass)) }
    }

    /** Starts the service as a foreground service. False when the system refuses (Android 12 and later, from the background). */
    fun startService(context: Context): Boolean = try {
        ContextCompat.startForegroundService(context, Intent(context, serviceClass))
        true
    } catch (e: Exception) {
        DevLog.d(logTag) { "tracking: start refused (${e.javaClass.simpleName})" }
        false
    }

    /** The alarm went off: start the service, or leave a notification the person can tap, or (live status off) a plain reminder. */
    fun onAlarm(context: Context) {
        if (DemoMode.active) return
        val app = context.applicationContext
        val container = container(app)
        try {
            val tracking = trackingEnabled(container)
            val live = liveEnabled(container)
            if (!tracking || !canNotify(app)) return
            val now = Instant.now()
            val active = active(container, now)
            DevLog.d(logTag) { "tracking: alarm, ${active.size} to watch, live=$live" }
            if (active.isEmpty()) return
            notifier.ensureChannels(app)
            if (live) {
                if (!running && !startService(app)) {
                    val first = active.first()
                    notifier.post(app, notifier.idFor(key(first)), notifier.prompt(app, key(first), tapToStart(first, now)))
                }
            } else {
                for (d in active) {
                    if (markReminded(container, key(d))) {
                        notifier.post(app, notifier.idFor(key(d)), notifier.prompt(app, key(d), reminder(d, now)))
                    }
                }
            }
        } finally {
            rearm(app)
        }
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, requestCode, Intent(context, receiverClass),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
