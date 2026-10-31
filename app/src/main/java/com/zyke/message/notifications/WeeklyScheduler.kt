package com.zyke.message.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Sets or cancels the alarm for the next Sunday-morning summary. Inexact on purpose, like [DigestScheduler]; no permission needed. */
class WeeklyScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarms = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun apply(enabled: Boolean) {
        val pending = pendingIntent()
        alarms.cancel(pending)
        if (!enabled) return
        val now = LocalDateTime.now()
        var first = now.toLocalDate().atTime(LocalTime.of(SUMMARY_HOUR, 0)).with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY))
        if (!first.isAfter(now)) first = first.plusWeeks(1)
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, first.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), pending)
    }

    private fun pendingIntent(): PendingIntent = PendingIntent.getBroadcast(
        appContext, REQUEST_CODE, Intent(appContext, WeeklyReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private companion object {
        const val SUMMARY_HOUR = 9
        const val REQUEST_CODE = 803
    }
}
