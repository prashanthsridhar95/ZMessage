package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zyke.message.domain.markets.MarketCloseSummary
import java.time.Instant

/**
 * Sets or cancels the alarm for the next market-close notification (15:45 Indian time on weekdays). Inexact on purpose,
 * like [DigestScheduler]: no exact-alarm permission, and Android may move it a little. The receiver sets the next one when it fires.
 */
class MarketCloseScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun apply(enabled: Boolean) {
        val daily = pendingIntent(REQUEST_DAILY, retry = false)
        alarms.cancel(daily)
        // Demo mode never schedules anything (and clears an alarm left by the real app, which sets it again on its next start).
        if (!enabled || com.zyke.message.data.demo.DemoMode.active) {
            alarms.cancel(pendingIntent(REQUEST_RETRY, retry = true))
            return
        }
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, MarketCloseSummary.nextFire(Instant.now()).toEpochMilli(), daily)
    }

    /** The one second attempt, a little later the same day, when the first found no close for today or could not reach the service. */
    fun scheduleRetry() {
        if (com.zyke.message.data.demo.DemoMode.active) return
        val at = System.currentTimeMillis() + MarketCloseSummary.RETRY_MINUTES * 60_000L
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pendingIntent(REQUEST_RETRY, retry = true))
    }

    private fun pendingIntent(code: Int, retry: Boolean): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        code,
        Intent(appContext, MarketCloseReceiver::class.java).putExtra(MarketCloseReceiver.EXTRA_RETRY, retry),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val REQUEST_DAILY = 811
        const val REQUEST_RETRY = 812
    }
}
