package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.investments.InvestmentReminders
import com.zyke.message.domain.investments.ManagedReminder
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.ReminderAlarm
import com.zyke.message.domain.reminders.Reminders
import com.zyke.message.presentation.MainActivity
import com.zyke.message.presentation.format.DisplayFormatters
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Tells the person about a reminder at the time they set. One alarm is kept, for the next reminder that has a time;
 * when it goes off that reminder is posted and the alarm moves on to the one after. Like the morning digest the alarm is
 * inexact, so no exact-alarm permission is needed and Android may move it by a few minutes.
 */
object ReminderAlarms {
    private const val CHANNEL = "reminders_timed"
    private const val REQUEST_CODE = 803
    private const val PREFS = "reminder_alarms"
    private const val POSTED = "posted"
    private const val KEEP_POSTED = 100

    /** Sets the alarm for the next reminder, or clears it if there is none. Safe to call whenever reminders change. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val alarms = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pending = pendingIntent(app)
        alarms.cancel(pending)
        // Demo mode sets no alarm; a real one is cleared above and set again by the real app on its next start.
        if (com.zyke.message.data.demo.DemoMode.active) return
        val container = (app as ZykeMessagesApplication).container
        val next = Reminders.nextAlarm(everything(container), LocalDateTime.now(), posted(app)) ?: return
        val at = next.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    /** Posts every reminder whose moment has come, then sets the alarm for the one after. */
    fun fireDue(context: Context) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        com.zyke.message.data.diag.DevLog.d("reminder") { "alarm fired" }
        val app = context.applicationContext
        val container = (app as ZykeMessagesApplication).container
        val done = posted(app).toMutableSet()
        val now = LocalDateTime.now()
        while (true) {
            val next = Reminders.nextAlarm(everything(container), now, done) ?: break
            if (next.at.isAfter(now.plusMinutes(1))) break
            post(app, next)
            done += next.key
        }
        remember(app, done)
        schedule(app)
    }

    /**
     * The person's own reminders, plus those the Investments tracker derives from active deposits (maturity and RD
     * installments). The second source is recomputed on every call from the saved holdings, so a changed, renewed, closed
     * or deleted deposit simply stops producing its reminders: there is nothing stale to cancel.
     */
    private fun everything(container: com.zyke.message.di.AppContainer): List<Reminder> {
        val own = container.reminders.reminders.value
        val managed = InvestmentReminders.build(container.investments.state.value.holdings, LocalDate.now()).map { it.toReminder() }
        return if (managed.isEmpty()) own else own + managed
    }

    private fun post(context: Context, alarm: ReminderAlarm) {
        if (!DigestNotifier.canNotify(context)) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "A reminder at the time you set for it"
            }
        )
        val reminder = alarm.reminder
        val managed = ManagedReminder.isManaged(reminder.id)
        val launch = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        // A deposit's reminder opens the Investments tab, on that deposit.
        if (managed) {
            launch.setAction(com.zyke.message.presentation.investments.InvestmentRequests.ACTION_OPEN)
                .putExtra(com.zyke.message.presentation.investments.InvestmentRequests.EXTRA_HOLDING, ManagedReminder.holdingIdOf(reminder.id))
        }
        val open = PendingIntent.getActivity(
            context, if (managed) alarm.key.hashCode() else 0, launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val text = if (managed) "Open Investments" else listOfNotNull(
            reminder.amount?.let { DisplayFormatters.rupees(it) },
            if (reminder.repeatsMonthly) "every month" else null
        ).joinToString(" · ").ifEmpty { "Due today" }
        manager.notify(
            alarm.key.hashCode(),
            NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.zyke_icon)
                .setContentTitle(NotificationPrivacy.text(context, reminder.title))
                .setContentText(NotificationPrivacy.text(context, text))
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setContentIntent(open)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
        )
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE, Intent(context, ReminderReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun posted(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(POSTED, "").orEmpty().split('\n').filter { it.isNotEmpty() }.toSet()

    private fun remember(context: Context, keys: Set<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(POSTED, keys.toList().takeLast(KEEP_POSTED).joinToString("\n")).apply()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ReminderAlarms.fireDue(context)
    }
}
