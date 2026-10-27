package com.zyke.message.presentation.common

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import com.zyke.message.domain.model.MessageContent
import java.time.LocalTime
import java.time.ZoneId

/**
 * Opens the calendar app with this event filled in, for the person to save. It uses the calendar's own
 * "new event" screen, so the app needs no calendar permission and never reads their calendar.
 */
fun addToCalendar(context: Context, life: MessageContent.Life): Boolean {
    val date = life.date ?: return false
    val zone = ZoneId.systemDefault()
    val allDay = life.time == null
    val start = date.atTime(life.time ?: LocalTime.MIDNIGHT).atZone(zone).toInstant().toEpochMilli()
    val end = if (allDay) start + DAY_MS else start + HOUR_MS

    val intent = Intent(Intent.ACTION_INSERT)
        .setData(CalendarContract.Events.CONTENT_URI)
        .putExtra(CalendarContract.Events.TITLE, "${life.title}: ${life.status}")
        .putExtra(CalendarContract.Events.DESCRIPTION, life.reference?.let { "Reference $it" }.orEmpty())
        .putExtra(CalendarContract.EXTRA_EVENT_ALL_DAY, allDay)
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
        .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    return runCatching { context.startActivity(intent) }.isSuccess
}

private const val HOUR_MS = 60L * 60 * 1000
private const val DAY_MS = 24 * HOUR_MS
