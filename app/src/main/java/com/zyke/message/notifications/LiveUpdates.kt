package com.zyke.message.notifications

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.provider.Settings
import com.zyke.message.R
import com.zyke.message.domain.flight.FlightNotice
import com.zyke.message.domain.flight.FlightNotificationText

/**
 * Android 16 (API 36) "Live Updates": an ongoing notification that asks to be promoted (a progress bar with a plane, a chip in
 * the status bar with the time left, shown on the lock screen and the always-on display). The app is compiled against API 35
 * (the repo's Android Gradle Plugin 8.8 and the CI image's SDK platform stop there), and androidx.core 1.15 has no wrapper for it,
 * so everything new in 36 is reached by reflection here and nowhere else:
 *
 * - [promote] rebuilds an already-built notification with `Notification.ProgressStyle`, `setShortCriticalText` and
 *   `setRequestPromotedOngoing(true)`. On any failure at all (older Android, a missing class, a changed signature, a
 *   build error) it returns the notification it was given, so the person still gets the ordinary ongoing notification. It
 *   never throws.
 * - [canPost] asks `NotificationManager.canPostPromotedNotifications()` (the person can switch Live Updates off per app).
 * - [openSettings] opens the system page for it.
 *
 * Not verified on a device: written from the API 36 documentation. Moving to compileSdk 36 (AGP 8.9.1 or later, and
 * `platforms;android-36` on CI) would let this be plain code; until then this file is the only place that knows.
 */
object LiveUpdates {
    private const val API_LIVE_UPDATES = 36
    private const val STYLE = "android.app.Notification\$ProgressStyle"
    private const val SEGMENT = "android.app.Notification\$ProgressStyle\$Segment"
    private const val POINT = "android.app.Notification\$ProgressStyle\$Point"

    /** The extras the platform reads, as a last resort when a method by name is missing (the same keys the setters write). */
    private const val EXTRA_REQUEST_PROMOTED = "android.requestPromotedOngoing"
    private const val EXTRA_SHORT_CRITICAL = "android.shortCriticalText"

    /** Settings pages for Live Updates, tried in order (the action name was not final in every preview). */
    private val SETTINGS_ACTIONS = listOf(
        "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS",
        "android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS"
    )

    /** This Android has Live Updates at all. */
    fun available(): Boolean = Build.VERSION.SDK_INT >= API_LIVE_UPDATES

    /** True/false on Android 16 or later; null when there is nothing to say (older Android, or the system would not answer). */
    fun canPost(context: Context): Boolean? {
        if (!available()) return null
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.javaClass.getMethod("canPostPromotedNotifications").invoke(manager) as? Boolean
        } catch (t: Throwable) {
            null
        }
    }

    /** Opens the system page where Live Updates are switched on for this app. Returns false if no page could be opened. */
    fun openSettings(context: Context): Boolean {
        val extras = { i: Intent -> i.putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        for (action in SETTINGS_ACTIONS) {
            if (tryStart(context, extras(Intent(action)))) return true
        }
        return tryStart(context, extras(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)))
    }

    /**
     * Returns [built] as a promoted ongoing notification when this Android can, with the progress drawn as a bar with a plane
     * from take-off to landing and the time left as the chip; otherwise [built] unchanged.
     */
    fun promote(context: Context, built: Notification, notice: FlightNotice, iconRes: Int = R.drawable.baseline_flight_24): Notification {
        if (!available()) return built
        return try {
            val styleClass = Class.forName(STYLE)
            val segmentClass = Class.forName(SEGMENT)
            val pointClass = Class.forName(POINT)
            val progress = notice.progress ?: 0
            val style = styleClass.getConstructor().newInstance()
            val segment = segmentClass.getConstructor(Int::class.javaPrimitiveType).newInstance(100)
            styleClass.getMethod("setProgressSegments", List::class.java).invoke(style, listOf(segment))
            val points = notice.points.map { pointClass.getConstructor(Int::class.javaPrimitiveType).newInstance(it) }
            if (points.isNotEmpty()) styleClass.getMethod("setProgressPoints", List::class.java).invoke(style, points)
            styleClass.getMethod("setProgress", Int::class.javaPrimitiveType).invoke(style, progress)
            styleClass.getMethod("setProgressTrackerIcon", Icon::class.java)
                .invoke(style, Icon.createWithResource(context, iconRes))

            val builder = Notification.Builder.recoverBuilder(context, built)
            builder.setStyle(style as Notification.Style)
            builder.extras.putBoolean(EXTRA_REQUEST_PROMOTED, true)
            val chip = notice.chip?.take(FlightNotificationText.CHIP_MAX)
            if (chip != null) {
                try {
                    builder.javaClass.getMethod("setShortCriticalText", CharSequence::class.java).invoke(builder, chip)
                } catch (t: Throwable) {
                    builder.extras.putCharSequence(EXTRA_SHORT_CRITICAL, chip)
                }
            }
            try {
                builder.javaClass.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType).invoke(builder, true)
            } catch (t: Throwable) {
                // The extra above says the same thing.
            }
            builder.build()
        } catch (t: Throwable) {
            built
        }
    }

    private fun tryStart(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent)
        true
    } catch (e: Exception) {
        false
    }

    // ---- The lock screen's own switches (read only; the app needs no permission for this) ----

    /**
     * True when the phone's own setting hides notification content on the lock screen ("Sensitive notifications" /
     * "Show sensitive content only when unlocked"), or hides notifications there altogether. Then the flight shows as a bare
     * "Sensitive content hidden" or not at all, whatever this app asks for. False when it is fine or the phone will not say.
     */
    fun lockScreenHidesContent(context: Context): Boolean = try {
        val resolver = context.contentResolver
        Settings.Secure.getInt(resolver, "lock_screen_allow_private_notifications", 1) == 0 ||
            Settings.Secure.getInt(resolver, "lock_screen_show_notifications", 1) == 0
    } catch (t: Throwable) {
        false
    }

    /** Opens the phone's security / lock-screen settings (where notification content on the lock screen is chosen). */
    fun openLockScreenSettings(context: Context): Boolean {
        val flags = Intent.FLAG_ACTIVITY_NEW_TASK
        return tryStart(context, Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(flags)) ||
            tryStart(context, Intent(Settings.ACTION_SETTINGS).addFlags(flags))
    }
}
