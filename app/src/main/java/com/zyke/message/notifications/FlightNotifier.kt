package com.zyke.message.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.FlightNotificationText
import com.zyke.message.domain.flight.TrackDecision
import com.zyke.message.presentation.MainActivity
import com.zyke.message.presentation.flights.FlightRequests

/**
 * Builds and posts the flight notifications. The words come from the tested [FlightNotificationText]; this only dresses them.
 *
 * - The ongoing one (departing soon, in the air): not dismissible, no sound or vibration and no heads-up (the "Flight tracking"
 *   channel is DEFAULT importance without sound, so it counts as an alerting notification on the lock screen yet never makes a
 *   noise; it is posted with only-alert-once), a determinate bar, a countdown to landing, one action "Stop tracking", a tap that
 *   opens the flight sheet. It is the service's foreground notification. On Android 16 it is also asked to be a Live Update
 *   (see [LiveUpdates]); before that it is simply an ongoing, public notification.
 * - The finished ones (landed, cancelled, diverted, tracking ended): dismissible, one per flight, in place of the ongoing one
 *   (same id), an ordinary notification (so a Live Update ends).
 * - Prompts (tap to start tracking, the reminder when live status is off, tracking paused): on the "Flight reminders" channel.
 *
 * On the lock screen the ongoing and finished notices show the route and times by default (flights are not amounts); with
 * "Show flight details on the lock screen" off they show only "Flight in progress" (or "Flight update") until unlock. Prompts
 * always keep the short version. Nothing here is logged.
 */
object FlightNotifier {
    /**
     * A new id: a channel's importance and lock-screen visibility cannot be changed by the app once created, and the first
     * version of the channel was LOW importance with private lock-screen content.
     */
    const val TRACKING_CHANNEL = "flight_live"
    private const val LEGACY_TRACKING_CHANNEL = "flight_tracking"
    const val PROMPT_CHANNEL = "flight_prompts"
    private const val BASE_ID = 4300
    private const val SPAN = 900

    /** A stable id for a flight, the same for its ongoing, finished and prompt notifications so each replaces the last. */
    fun idFor(key: String): Int = BASE_ID + (key.hashCode() and 0x7fffffff) % SPAN

    /** The id when two flights tracked together would share one. */
    fun idFor(key: String, taken: Set<Int>): Int {
        var id = idFor(key)
        while (id in taken) id = BASE_ID + (id - BASE_ID + 1) % SPAN
        return id
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(LEGACY_TRACKING_CHANNEL) != null) manager.deleteNotificationChannel(LEGACY_TRACKING_CHANNEL)
        manager.createNotificationChannel(
            NotificationChannel(TRACKING_CHANNEL, "Flight tracking", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Your flight while it is in the air, and when it lands. Shown on the lock screen; no sound, vibration or pop-up."
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                // lockscreenVisibility is left unset (VISIBILITY_NO_OVERRIDE): each notification decides, by the Settings switch.
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(PROMPT_CHANNEL, "Flight reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A reminder shortly before a flight, and a button to start live tracking when the phone does not let the app start it by itself"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun open(context: Context, key: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (key != null) intent.setAction(FlightRequests.ACTION_OPEN).putExtra(FlightRequests.EXTRA_KEY, key)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stop(context: Context, key: String, id: Int): PendingIntent = PendingIntent.getService(
        context, id,
        Intent(context, FlightTrackingService::class.java).setAction(FlightTrackingService.ACTION_STOP).putExtra(FlightTrackingService.EXTRA_KEY, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun publicVersion(context: Context, channel: String, title: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.baseline_flight_24)
            .setContentTitle(title)
            .setContentText(text)
            .build()

    /** Whether the route and times may show on the lock screen (Settings > Live flight status; on unless turned off). */
    fun detailsOnLock(context: Context): Boolean =
        (context.applicationContext as ZykeMessagesApplication).container.flights.lockScreenDetails.value

    /** Public with the real text, or private with the redacted one-liner ("Flight in progress") for the lock screen. */
    private fun NotificationCompat.Builder.lockScreen(context: Context, details: Boolean, title: String, text: String): NotificationCompat.Builder =
        if (details) {
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            setPublicVersion(publicVersion(context, TRACKING_CHANNEL, title, text))
        }

    /** The ongoing notification for a flight being watched (id from [idFor]). */
    fun ongoing(context: Context, d: TrackDecision, id: Int, notice: FlightNotice): Notification {
        val details = detailsOnLock(context)
        val builder = NotificationCompat.Builder(context, TRACKING_CHANNEL)
            .setSmallIcon(R.drawable.baseline_flight_24)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(NotificationPrivacy.text(context, notice.title))
            .setContentText(NotificationPrivacy.text(context, notice.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .lockScreen(context, details, FlightNotificationText.PUBLIC_TITLE, FlightNotificationText.PUBLIC_TEXT)
            .setContentIntent(open(context, d.key, id))
            // The one way to take it down before the flight ends.
            .addAction(0, "Stop tracking", stop(context, d.key, id))
        // The system counts down to landing (or departure) by itself between our 30-second updates.
        val countdown = notice.countdownTo
        if (countdown != null) {
            builder.setWhen(countdown.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        notice.subText?.let { builder.setSubText(NotificationPrivacy.text(context, it)) }
        notice.progress?.let { builder.setProgress(100, it, false) }
        val built = builder.build()
        // Android 16 Live Update (progress with a plane, a chip with the time left); anything else, or any failure, keeps the notification above.
        return if (details) LiveUpdates.promote(context, built, notice) else built
    }

    /** The notification that takes the place of the ongoing one when the flight is over: swipe it away, or tap to open the flight. */
    fun finished(context: Context, d: TrackDecision, id: Int, notice: FlightNotice): Notification =
        NotificationCompat.Builder(context, TRACKING_CHANNEL)
            .setSmallIcon(R.drawable.baseline_flight_24)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(NotificationPrivacy.text(context, notice.title))
            .setContentText(NotificationPrivacy.text(context, notice.text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationPrivacy.text(context, notice.text)))
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .lockScreen(context, detailsOnLock(context), "Flight update", FlightNotificationText.PUBLIC_TEXT)
            .setContentIntent(open(context, d.key, id))
            .build()

    /** A prompt: "tap to start live tracking" and the reminder when live status is off. Dismissible; a tap opens the flight. */
    fun prompt(context: Context, d: TrackDecision, notice: FlightNotice): Notification {
        val id = idFor(d.key)
        return NotificationCompat.Builder(context, PROMPT_CHANNEL)
            .setSmallIcon(R.drawable.baseline_flight_24)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(NotificationPrivacy.text(context, notice.title))
            .setContentText(NotificationPrivacy.text(context, notice.text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationPrivacy.text(context, notice.text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, PROMPT_CHANNEL, "Your flight", FlightNotificationText.PUBLIC_TEXT))
            .setContentIntent(open(context, d.key, id))
            .build()
    }

    /** "Tracking paused · Open Zyke to resume": the system's time limit ended the service. Not tied to one flight. */
    fun paused(context: Context): Notification {
        val notice = FlightNotificationText.paused()
        return NotificationCompat.Builder(context, PROMPT_CHANNEL)
            .setSmallIcon(R.drawable.baseline_flight_24)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, PROMPT_CHANNEL, "Flight update", FlightNotificationText.PUBLIC_TEXT))
            .setContentIntent(open(context, null, PAUSED_ID))
            .build()
    }

    /** What the service shows for the moment it has nothing to show but has to be in the foreground (it stops right after). */
    fun placeholder(context: Context): Notification =
        NotificationCompat.Builder(context, TRACKING_CHANNEL)
            .setSmallIcon(R.drawable.baseline_flight_24)
            .setContentTitle("Flight tracking")
            .setContentText("Checking your flight")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, TRACKING_CHANNEL, FlightNotificationText.PUBLIC_TITLE, FlightNotificationText.PUBLIC_TEXT))
            .build()

    fun post(context: Context, id: Int, notification: Notification) {
        // Nothing is posted while demonstrating, so no made-up flight reaches the shade.
        if (com.zyke.message.data.demo.DemoMode.active) return
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }

    const val PAUSED_ID = 4299
    const val PLACEHOLDER_ID = 4298
}
