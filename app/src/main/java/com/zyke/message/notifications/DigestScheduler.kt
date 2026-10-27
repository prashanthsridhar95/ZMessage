package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * Sets or cancels the once-a-day alarm for the morning digest. The alarm is inexact on purpose: Android may
 * move it a little to save battery, which is fine for "good morning", and no exact-alarm permission is needed.
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
        alarms.setInexactRepeating(AlarmManager.RTC, at, AlarmManager.INTERVAL_DAY, pending)
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
