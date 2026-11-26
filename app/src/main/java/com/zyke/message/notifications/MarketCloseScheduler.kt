package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zyke.message.domain.markets.Market
import com.zyke.message.domain.markets.MarketCloseSummary
import com.zyke.message.domain.markets.WatchSettings
import java.time.Instant

/**
 * Sets or cancels the alarm for each market's next close notification (India 15:30 IST and the United States 16:00 ET plus the
 * person's offset, on weekdays). Inexact on purpose, like [DigestScheduler]: no exact-alarm permission, and Android may move it
 * a little. The receiver sets the next one when it fires. One alarm per market, told apart by request code and market name.
 */
class MarketCloseScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Puts every market's alarm in step with [settings]: on for the markets switched on, cancelled for the rest. */
    fun apply(settings: WatchSettings) {
        // Demo mode never schedules anything (and clears an alarm left by the real app, which sets it again on its next start).
        val demo = com.zyke.message.data.demo.DemoMode.active
        MarketCloseSummary.MARKETS.forEach { market ->
            val daily = pendingIntent(market, retry = false)
            alarms.cancel(daily)
            if (demo || !settings.closeActive(market)) {
                alarms.cancel(pendingIntent(market, retry = true))
            } else {
                val at = MarketCloseSummary.nextFire(Instant.now(), market, settings.closeOffsetMinutes.coerceIn(0, 120))
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at.toEpochMilli(), daily)
            }
        }
    }

    /** The one second attempt, a little later the same day, when the first found no close for today or could not reach the service. */
    fun scheduleRetry(market: Market) {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val at = System.currentTimeMillis() + MarketCloseSummary.RETRY_MINUTES * 60_000L
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(market, retry = true))
    }

    private fun pendingIntent(market: Market, retry: Boolean): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        code(market, retry),
        Intent(appContext, MarketCloseReceiver::class.java)
            .putExtra(MarketCloseReceiver.EXTRA_RETRY, retry)
            .putExtra(MarketCloseReceiver.EXTRA_MARKET, market.name),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        // India keeps the codes the single summary always used (811 daily, 812 retry), so an alarm set before an update is cancelled.
        fun code(market: Market, retry: Boolean): Int = when (market) {
            Market.UNITED_STATES -> if (retry) 815 else 814
            else -> if (retry) 812 else 811
        }
    }
}
