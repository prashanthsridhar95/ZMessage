package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.balance.BalanceRefresh
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Sets or cancels the alarm for the next morning digest. The alarm is inexact on purpose: Android may
 * move it a little to save battery, which is fine for "good morning", and no exact-alarm permission is needed. It does
 * wake the phone, or it would wait until the screen was next on.
 *
 * When "Refresh balances before my digest" is on, a second alarm of the same kind (inexact, allowed while idle, no new permission)
 * goes [BalanceRefresh.LEAD_MS] before it and starts [BalanceRefreshReceiver]. It is set and cancelled together with the digest's own.
 */
class DigestScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun apply(enabled: Boolean) {
        val pending = pendingIntent()
        // Demo mode never schedules anything (and clears an alarm left by the real app, which sets it again on its next start).
        if (!enabled || com.zyke.message.data.demo.DemoMode.active) {
            alarms.cancel(pending)
            alarms.cancel(refreshIntent(0L))
            return
        }
        val now = LocalDateTime.now()
        var first = now.toLocalDate().atTime(LocalTime.of(DIGEST_HOUR, 0))
        if (!first.isAfter(now)) first = first.plusDays(1)
        val at = first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // A one-shot that wakes the phone and may run while it dozes; the receiver sets tomorrow's when it fires.
        alarms.cancel(pending)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        applyRefresh(at, first.plusDays(1).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    }

    /** The refresh alarm: before today's digest while there is time, else before tomorrow's; cancelled when the switch is off. */
    private fun applyRefresh(digestAt: Long, digestTomorrowAt: Long) {
        val wanted = runCatching {
            (appContext as ZykeMessagesApplication).container.settings.settings.value.refreshBalancesBeforeDigest
        }.getOrDefault(false)
        val cancel = refreshIntent(0L)
        alarms.cancel(cancel)
        if (!wanted) return
        val now = System.currentTimeMillis()
        val at = BalanceRefresh.alarmAt(now, digestAt, digestTomorrowAt)
        val forDigest = if (at == digestAt - BalanceRefresh.LEAD_MS) digestAt else digestTomorrowAt
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, refreshIntent(forDigest))
    }

    private fun refreshIntent(digestAt: Long): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REFRESH_REQUEST_CODE,
        Intent(appContext, BalanceRefreshReceiver::class.java).putExtra(BalanceRefreshReceiver.EXTRA_DIGEST_AT, digestAt),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, DigestReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val DIGEST_HOUR = 8
        const val REQUEST_CODE = 801
        const val REFRESH_REQUEST_CODE = 805
    }
}
