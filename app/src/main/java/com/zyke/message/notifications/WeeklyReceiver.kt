package com.zyke.message.notifications

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.forecast.UpcomingOutflows
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.insights.RecurringPayments
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.insights.WeeklySummary
import com.zyke.message.presentation.MainActivity
import com.zyke.message.presentation.format.DisplayFormatters
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Posts the weekly summary on Sunday morning and arms next week's alarm. Not exported; nothing leaves the phone. */
class WeeklyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val container = (context.applicationContext as ZykeMessagesApplication).container
                if (!container.settings.settings.value.weeklySummary) return@launch
                // Next week's alarm first, so a failure below cannot end the summaries.
                WeeklyScheduler(context).apply(true)
                WeeklyNotifier.show(context)
            } finally {
                pending.finish()
            }
        }
    }
}

internal object WeeklyNotifier {
    private const val CHANNEL = "weekly_summary"
    private const val NOTIFICATION_ID = 804

    suspend fun show(context: Context) {
        if (!DigestNotifier.canNotify(context)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) return
        val container = (context.applicationContext as ZykeMessagesApplication).container
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = LocalDate.ofInstant(now, zone)
        val live = container.observeMessages().first().filter { !it.isPromotional && !it.isMuted && it.setAside == null }
        val subscriptions = RecurringPayments.arrange(Subscriptions.find(live, now, zone), container.recurringPayments.states.value)
        val next = UpcomingOutflows.build(
            from = today.plusDays(1),
            upcoming = UpcomingBuilder.build(live, today, zone = zone),
            loans = container.loans.loans.value,
            reminders = container.reminders.reminders.value,
            plan = container.moneyPlan.plan.value,
            recurring = subscriptions
        )
        val week = WeeklySummary.build(live, now, zone, next)
        if (week.isQuiet) return
        post(context, week)
    }

    private fun post(context: Context, week: WeeklySummary) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Weekly summary", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "One summary each Sunday of the week's spending and what is due next"
                lockscreenVisibility = Notification.VISIBILITY_PRIVATE
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val change = week.changePercent?.let { if (it >= 0) "$it% more than last week" else "${-it}% less than last week" }
        val headline = "Spent ${DisplayFormatters.rupees(week.spent)} this week"
        val lines = listOfNotNull(
            change?.let { "That is $it" } ?: "Last week: ${DisplayFormatters.rupees(week.spentLastWeek)}",
            week.topCategory?.let { "Most on ${it.displayString}: ${DisplayFormatters.rupees(week.topCategoryAmount)}" },
            week.biggest?.let { "Biggest: ${it.payee}, ${DisplayFormatters.rupees(it.amount)}" },
            if (week.dueNext.isEmpty) "Nothing due next week" else "${DisplayFormatters.rupees(week.dueNext.total)} leaves next week"
        )
        val accent = ContextCompat.getColor(context, R.color.zyke_accent)
        val style = NotificationCompat.InboxStyle().setSummaryText("Zyke Message")
        lines.forEach { style.addLine(it) }
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.baseline_message_24)
            .setColor(accent)
            .setContentTitle(headline)
            .setContentText(lines.first())
            .setStyle(style)
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setShowWhen(false)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(
                NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.baseline_message_24)
                    .setColor(accent)
                    .setContentTitle("Your week")
                    .setContentText("Unlock to read your weekly summary")
                    .build()
            )
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }
}
