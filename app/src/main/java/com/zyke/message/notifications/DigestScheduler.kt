package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Sets or cancels the alarm for the next morning digest. The alarm is inexact on purpose: Android may
 * move it a little to save battery, which is fine for "good morning", and no exact-alarm permission is needed. It does
 * wake the phone, or it would wait until the screen was next on.
 */
class DigestScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun apply(enabled: Boolean) {
        val pending = pendingIntent()
        if (!enabled) {
            alarms.cancel(pending)
            return
        }
        val now = LocalDateTime.now()
        var first = now.toLocalDate().atTime(LocalTime.of(DIGEST_HOUR, 0))
        if (!first.isAfter(now)) first = first.plusDays(1)
        val at = first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        // A one-shot that wakes the phone and may run while it dozes; the receiver sets tomorrow's when it fires.
        alarms.cancel(pending)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext,
        REQUEST_CODE,
        Intent(appContext, DigestReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val DIGEST_HOUR = 8
        const val REQUEST_CODE = 801
    }
}
