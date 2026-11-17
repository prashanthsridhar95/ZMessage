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
import com.zyke.message.presentation.MainActivity

/**
 * Everything that differs between the flight and the train notifications: channel ids and names, the icon, the id range,
 * the words that are not part of the tested notification texts, the intents that open the sheet and stop the service.
 * [FlightNotifier] and [TrainNotifier] each hold one; nothing else differs between them.
 */
class TripLook(
    val trackingChannel: String,
    /** The first version of the channel, removed when found (a channel's importance cannot be changed afterwards); null when there never was one. */
    val legacyTrackingChannel: String?,
    val promptChannel: String,
    val trackingName: String,
    val trackingDescription: String,
    val promptName: String,
    val promptDescription: String,
    val icon: Int,
    val baseId: Int,
    val pausedId: Int,
    val placeholderId: Int,
    /** The redacted lock-screen words of the ongoing notice and the public text of every notice (the tested constants of the text object). */
    val publicTitle: String,
    val publicText: String,
    /** The redacted title of a finished notice, of a prompt and of the "tracking paused" notice. */
    val finishedPublicTitle: String,
    val promptPublicTitle: String,
    val pausedPublicTitle: String,
    val placeholderTitle: String,
    val placeholderText: String,
    /** The intent that opens the sheet of one trip, and its extra. */
    val openAction: String,
    val openExtra: String,
    /** The service that "Stop tracking" reaches, with its action and extra. */
    val serviceClass: Class<*>,
    val stopAction: String,
    val stopExtra: String,
    /** The "Tracking paused" words (shared by both kinds). */
    val paused: () -> FlightNotice
)

/**
 * Builds and posts the trip notifications of one kind. The words come from the tested notification texts; this only dresses them.
 *
 * - The ongoing one (departing soon, under way): not dismissible, no sound or vibration and no heads-up (the tracking channel
 *   is DEFAULT importance without sound, so it counts as an alerting notification on the lock screen yet never makes a
 *   noise; it is posted with only-alert-once), a determinate bar, a countdown to arrival, one action "Stop tracking", a tap that
 *   opens the trip sheet. It is the service's foreground notification. On Android 16 it is also asked to be a Live Update
 *   (see [LiveUpdates]); before that it is simply an ongoing, public notification.
 * - The finished ones (arrived, cancelled, diverted, tracking ended): dismissible, one per trip, in place of the ongoing one
 *   (same id), an ordinary notification (so a Live Update ends).
 * - Prompts (tap to start tracking, the reminder when live status is off, tracking paused): on the reminders channel.
 *
 * On the lock screen the ongoing and finished notices show the route and times by default (a trip is not an amount); with
 * "Show flight and train details on the lock screen" off they show only the short redacted words until unlock. Prompts
 * always keep the short version. Nothing here is logged.
 */
abstract class TripNotifier {
    /** What this kind looks like; a property, so that an object can build it from its own constants. */
    protected abstract val look: TripLook

    val pausedId: Int get() = look.pausedId
    val placeholderId: Int get() = look.placeholderId
    val stopAction: String get() = look.stopAction
    val stopExtra: String get() = look.stopExtra

    /** A stable id for a trip, the same for its ongoing, finished and prompt notifications so each replaces the last. */
    fun idFor(key: String): Int = look.baseId + (key.hashCode() and 0x7fffffff) % SPAN

    /** The id when two trips tracked together would share one. */
    fun idFor(key: String, taken: Set<Int>): Int {
        var id = idFor(key)
        while (id in taken) id = look.baseId + (id - look.baseId + 1) % SPAN
        return id
    }

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val legacy = look.legacyTrackingChannel
        if (legacy != null && manager.getNotificationChannel(legacy) != null) manager.deleteNotificationChannel(legacy)
        manager.createNotificationChannel(
            NotificationChannel(look.trackingChannel, look.trackingName, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = look.trackingDescription
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
                setShowBadge(false)
                // lockscreenVisibility is left unset (VISIBILITY_NO_OVERRIDE): each notification decides, by the Settings switch.
            }
        )
        manager.createNotificationChannel(
            NotificationChannel(look.promptChannel, look.promptName, NotificationManager.IMPORTANCE_HIGH).apply {
                description = look.promptDescription
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
    }

    private fun open(context: Context, key: String?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (key != null) intent.setAction(look.openAction).putExtra(look.openExtra, key)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun stop(context: Context, key: String, id: Int): PendingIntent = PendingIntent.getService(
        context, id,
        Intent(context, look.serviceClass).setAction(look.stopAction).putExtra(look.stopExtra, key),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun publicVersion(context: Context, channel: String, title: String, text: String): Notification =
        NotificationCompat.Builder(context, channel)
            .setSmallIcon(look.icon)
            .setContentTitle(title)
            .setContentText(text)
            .build()

    /** Whether the route and times may show on the lock screen (one switch for flights and trains, kept by the flight service; on unless turned off). */
    fun detailsOnLock(context: Context): Boolean =
        (context.applicationContext as ZykeMessagesApplication).container.flights.lockScreenDetails.value

    /** Public with the real text, or private with the redacted one-liner for the lock screen. */
    private fun NotificationCompat.Builder.lockScreen(context: Context, details: Boolean, title: String, text: String): NotificationCompat.Builder =
        if (details) {
            setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        } else {
            setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            setPublicVersion(publicVersion(context, look.trackingChannel, title, text))
        }

    /** The ongoing notification for a trip being watched ([key], id from [idFor]). */
    fun ongoing(context: Context, key: String, id: Int, notice: FlightNotice): Notification {
        val details = detailsOnLock(context)
        val builder = NotificationCompat.Builder(context, look.trackingChannel)
            .setSmallIcon(look.icon)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(NotificationPrivacy.text(context, notice.title))
            .setContentText(NotificationPrivacy.text(context, notice.text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .lockScreen(context, details, look.publicTitle, look.publicText)
            .setContentIntent(open(context, key, id))
            // The one way to take it down before the trip ends.
            .addAction(0, "Stop tracking", stop(context, key, id))
        // The system counts down to arrival (or departure) by itself between our 30-second updates.
        val countdown = notice.countdownTo
        if (countdown != null) {
            builder.setWhen(countdown.toEpochMilli()).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true)
        } else {
            builder.setShowWhen(false)
        }
        notice.subText?.let { builder.setSubText(NotificationPrivacy.text(context, it)) }
        notice.progress?.let { builder.setProgress(100, it, false) }
        val built = builder.build()
        // Android 16 Live Update (progress with the trip's icon, a chip with the time left); anything else, or any failure, keeps the notification above.
        return if (details) LiveUpdates.promote(context, built, notice, look.icon) else built
    }

    /** The notification that takes the place of the ongoing one when the trip is over: swipe it away, or tap to open the trip. */
    fun finished(context: Context, key: String, id: Int, notice: FlightNotice): Notification =
        NotificationCompat.Builder(context, look.trackingChannel)
            .setSmallIcon(look.icon)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(NotificationPrivacy.text(context, notice.title))
            .setContentText(NotificationPrivacy.text(context, notice.text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationPrivacy.text(context, notice.text)))
            .setOngoing(false)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .lockScreen(context, detailsOnLock(context), look.finishedPublicTitle, look.publicText)
            .setContentIntent(open(context, key, id))
            .build()

    /** A prompt: "tap to start live tracking" and the reminder when live status is off. Dismissible; a tap opens the trip. */
    fun prompt(context: Context, key: String, notice: FlightNotice): Notification {
        val id = idFor(key)
        return NotificationCompat.Builder(context, look.promptChannel)
            .setSmallIcon(look.icon)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(NotificationPrivacy.text(context, notice.title))
            .setContentText(NotificationPrivacy.text(context, notice.text))
            .setStyle(NotificationCompat.BigTextStyle().bigText(NotificationPrivacy.text(context, notice.text)))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, look.promptChannel, look.promptPublicTitle, look.publicText))
            .setContentIntent(open(context, key, id))
            .build()
    }

    /** "Tracking paused · Open Zyke to resume": the system's time limit ended the service. Not tied to one trip. */
    fun paused(context: Context): Notification {
        val notice = look.paused()
        return NotificationCompat.Builder(context, look.promptChannel)
            .setSmallIcon(look.icon)
            .setColor(androidx.core.content.ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(notice.title)
            .setContentText(notice.text)
            .setAutoCancel(true)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, look.promptChannel, look.pausedPublicTitle, look.publicText))
            .setContentIntent(open(context, null, look.pausedId))
            .build()
    }

    /** What the service shows for the moment it has nothing to show but has to be in the foreground (it stops right after). */
    fun placeholder(context: Context): Notification =
        NotificationCompat.Builder(context, look.trackingChannel)
            .setSmallIcon(look.icon)
            .setContentTitle(look.placeholderTitle)
            .setContentText(look.placeholderText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion(context, look.trackingChannel, look.publicTitle, look.publicText))
            .build()

    fun post(context: Context, id: Int, notification: Notification) {
        // Nothing is posted while demonstrating, so no made-up trip reaches the shade.
        if (com.zyke.message.data.demo.DemoMode.active) return
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, notification)
    }

    fun cancel(context: Context, id: Int) {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(id)
    }

    private companion object {
        const val SPAN = 900
    }
}
