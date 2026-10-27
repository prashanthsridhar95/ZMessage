package com.zyke.message.presentation.format

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Human friendly date strings for the message list. */
object DateFormatters {

    /** "Today, 05 Mar", "Yesterday, 04 Mar", "Sunday, 02 Mar", or "20 Feb". */
    fun dayHeader(
        date: LocalDate,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault()
    ): String {
        val day = DateTimeFormatter.ofPattern("dd MMM", locale).format(date)
        return when (relativeDay(date, today)) {
            Relative.TODAY -> "Today, $day"
            Relative.YESTERDAY -> "Yesterday, $day"
            Relative.THIS_WEEK -> "${DateTimeFormatter.ofPattern("EEEE", locale).format(date)}, $day"
            Relative.OLDER -> day
        }
    }

    /** Same as [dayHeader] with the time of day appended. */
    fun detailTimestamp(
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = Locale.getDefault()
    ): String {
        val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
        val dayAndTime = DateTimeFormatter.ofPattern("dd MMM, hh:mm a", locale).format(dateTime)
        return when (relativeDay(dateTime.toLocalDate(), today)) {
            Relative.TODAY -> "Today, $dayAndTime"
            Relative.YESTERDAY -> "Yesterday, $dayAndTime"
            Relative.THIS_WEEK -> "${DateTimeFormatter.ofPattern("EEEE", locale).format(dateTime)}, $dayAndTime"
            Relative.OLDER -> dayAndTime
        }
    }

    private enum class Relative { TODAY, YESTERDAY, THIS_WEEK, OLDER }

    private fun relativeDay(date: LocalDate, today: LocalDate): Relative =
        when (ChronoUnit.DAYS.between(date, today)) {
            0L -> Relative.TODAY
            1L -> Relative.YESTERDAY
            in 2L..6L -> Relative.THIS_WEEK
            else -> Relative.OLDER
        }
}
