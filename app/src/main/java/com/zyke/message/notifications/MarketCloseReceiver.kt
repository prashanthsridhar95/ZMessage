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
import com.zyke.message.domain.markets.Market
import com.zyke.message.domain.markets.MarketCloseSummary
import com.zyke.message.presentation.MainActivity
import com.zyke.message.presentation.markets.MarketRequests
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Posts a market's close notification once that market has shut (India or the United States; the alarm says which, and an
 * alarm from before the update, which says nothing, is India's). Only while the market watch and that market's "Market close
 * summary" switch are both on, never in Demo mode. It asks Yahoo Finance for the market's indices and the watched stocks listed
 * there (see MarketTicker), sends nothing if they are not today's close, and retries once about 20 minutes later when they are
 * not there yet.
 */
class MarketCloseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val retried = intent.getBooleanExtra(EXTRA_RETRY, false)
        val market = runCatching { Market.valueOf(intent.getStringExtra(EXTRA_MARKET) ?: "") }.getOrDefault(Market.INDIA)
            .takeIf { it in MarketCloseSummary.MARKETS } ?: Market.INDIA
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val container = (context.applicationContext as ZykeMessagesApplication).container
                val settings = container.watchlist.watch.value
                val scheduler = MarketCloseScheduler(context)
                // Tomorrow's alarms first, so a failure below cannot end the summaries. The retry is a one-off and leaves them be.
                if (!retried) scheduler.apply(settings)
                if (!settings.closeActive(market)) return@launch
                val now = Instant.now()
                if (MarketCloseNotifier.alreadySentToday(context, market, now)) return@launch
                val quotes = container.markets.closingQuotes(market).orEmpty()
                when (val decision = MarketCloseSummary.decide(now, quotes, retried, market, settings.symbols)) {
                    is CloseDecision.Send -> MarketCloseNotifier.show(context, market, decision.summary, now)
                    CloseDecision.RetryLater -> scheduler.scheduleRetry(market)
                    CloseDecision.Skip -> Unit
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val EXTRA_RETRY = "retry"
        const val EXTRA_MARKET = "market"
    }
}

/** One notification a day per market under a fixed id, so a second firing replaces it instead of adding another. */
object MarketCloseNotifier {
    private const val CHANNEL = "market_summary"
    private const val NOTIFICATION_ID_INDIA = 813
    private const val NOTIFICATION_ID_US = 816
    private const val PREFS = "market_close"
    private const val LAST_SENT = "last_sent_day"

    /** Whether today's (that market's date) summary has been posted already, so an alarm that fires twice posts once. */
    fun alreadySentToday(context: Context, market: Market, now: Instant): Boolean =
        prefs(context).getString(key(market), null) == day(market, now)

    fun show(context: Context, market: Market, summary: CloseSummaryText, now: Instant): Boolean {
        // canNotify is false in Demo mode and without the notification permission.
        if (!DigestNotifier.canNotify(context)) return false
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // Low importance: it appears in the shade without sound, vibration or a banner.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "Market summary", NotificationManager.IMPORTANCE_LOW).apply {
                description = "How each market you chose closed (its indices and the stocks you watch there), on weekdays"
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }
        )
        val open = PendingIntent.getActivity(
            context, notificationId(market),
            Intent(context, MainActivity::class.java).setAction(MarketRequests.ACTION_OPEN)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Index levels and share prices are public figures, not the person's money, so Hide amounts does not apply.
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
        manager.notify(notificationId(market), notification)
        prefs(context).edit().putString(key(market), day(market, now)).apply()
        return true
    }

    private fun notificationId(market: Market): Int = if (market == Market.UNITED_STATES) NOTIFICATION_ID_US else NOTIFICATION_ID_INDIA

    /** India keeps the key the single summary used. */
    private fun key(market: Market): String = if (market == Market.UNITED_STATES) LAST_SENT + "_us" else LAST_SENT

    private fun day(market: Market, now: Instant): String = now.atZone(market.zone ?: MarketCloseSummary.ZONE).toLocalDate().toString()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
