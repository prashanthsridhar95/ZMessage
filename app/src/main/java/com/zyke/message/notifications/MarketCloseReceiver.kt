package com.zyke.message.notifications

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.markets.CloseDecision
import com.zyke.message.domain.markets.CloseSummaryText
import com.zyke.message.domain.markets.MarketCloseSummary
import com.zyke.message.presentation.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Posts the market-close notification once the Indian market has shut. Only while the market watch and its "Market close
 * summary" switch are both on, never in Demo mode. It asks Yahoo Finance for the two index prices (see MarketTicker), sends
 * nothing if they are not today's close, and retries once about 20 minutes later when they are not there yet.
 */
class MarketCloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val retried = intent.getBooleanExtra(EXTRA_RETRY, false)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val container = (context.applicationContext as ZykeMessagesApplication).container
                val enabled = container.watchlist.watch.value.closeSummaryActive
                val scheduler = MarketCloseScheduler(context)
                // Tomorrow's alarm first, so a failure below cannot end the summaries. The retry is a one-off and leaves it be.
                if (!retried) scheduler.apply(enabled)
                if (!enabled) return@launch
                val now = Instant.now()
                if (MarketCloseNotifier.alreadySentToday(context, now)) return@launch
                val quotes = container.markets.closingQuotes().orEmpty()
                when (val decision = MarketCloseSummary.decide(now, quotes, retried)) {
                    is CloseDecision.Send -> MarketCloseNotifier.show(context, decision.summary, now)
                    CloseDecision.RetryLater -> scheduler.scheduleRetry()
                    CloseDecision.Skip -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_RETRY = "retry"
    }
}

/** One notification a day under a fixed id, so a second firing replaces it instead of adding another. */
object MarketCloseNotifier {
    private const val CHANNEL = "market_summary"
    private const val NOTIFICATION_ID = 813
    private const val PREFS = "market_close"
    private const val LAST_SENT = "last_sent_day"

    /** Whether today's (Indian date) summary has been posted already, so an alarm that fires twice posts once. */
    fun alreadySentToday(context: Context, now: Instant): Boolean =
        prefs(context).getString(LAST_SENT, null) == day(now)

    fun show(context: Context, summary: CloseSummaryText, now: Instant): Boolean {
        // canNotify is false in Demo mode and without the notification permission.
        if (!DigestNotifier.canNotify(context)) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Low importance: it appears in the shade without sound, vibration or a banner.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Market summary", NotificationManager.IMPORTANCE_LOW).apply {
                description = "The Nifty 50 and Sensex closing levels after the market shuts, on weekdays"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Index levels are public figures, not the person's money, so Hide amounts does not apply.
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.zyke_icon)
            .setColor(ContextCompat.getColor(context, R.color.zyke_accent))
            .setContentTitle(summary.title)
            .setContentText(summary.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summary.body))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setSilent(true)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
        prefs(context).edit().putString(LAST_SENT, day(now)).apply()
        return true
    }

    private fun day(now: Instant): String = now.atZone(MarketCloseSummary.ZONE).toLocalDate().toString()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
