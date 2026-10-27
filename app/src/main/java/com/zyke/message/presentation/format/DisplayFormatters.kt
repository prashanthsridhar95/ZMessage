package com.zyke.message.presentation.format

import java.math.BigDecimal
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Small string helpers shared by the message cards. */
object DisplayFormatters {

    /** "2:30 PM" */
    fun time(epochMillis: Long, zone: ZoneId = ZoneId.systemDefault(), locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("h:mm a", locale).format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    /** Compact stamp for conversation lists: time today, "Yesterday", weekday this week, else "05 Mar". */
    fun listStamp(
        epochMillis: Long,
        zone: ZoneId = ZoneId.systemDefault(),
        today: LocalDate = LocalDate.now(zone),
        locale: Locale = Locale.getDefault()
    ): String {
        val dateTime = Instant.ofEpochMilli(epochMillis).atZone(zone)
        return when (ChronoUnit.DAYS.between(dateTime.toLocalDate(), today)) {
            0L -> time(epochMillis, zone, locale)
            1L -> "Yesterday"
            in 2L..6L -> DateTimeFormatter.ofPattern("EEE", locale).format(dateTime)
            else -> DateTimeFormatter.ofPattern("dd MMM", locale).format(dateTime)
        }
    }

    /** "1 message", "3 messages". */
    fun count(count: Int, one: String, many: String): String = if (count == 1) "$count $one" else "$count $many"

    /** "₹12,345" (Indian digit grouping where the platform supports it), fractions only when non-zero. */
    fun rupees(amount: BigDecimal, locale: Locale = Locale.forLanguageTag("en-IN")): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = 2
            minimumFractionDigits = 0
        }
        return "₹" + format.format(amount)
    }

    /** Like [rupees] but always with exactly [fractionDigits] decimals, so a counting number keeps one width and precision. */
    fun rupeesFixed(amount: BigDecimal, fractionDigits: Int, locale: Locale = Locale.forLanguageTag("en-IN")): String {
        val format = NumberFormat.getNumberInstance(locale).apply {
            maximumFractionDigits = fractionDigits
            minimumFractionDigits = fractionDigits
        }
        return "₹" + format.format(amount)
    }

    fun rupees(raw: String): String = raw.toBigDecimalOrNull()?.let { rupees(it) } ?: "₹$raw"

    /** "Due today", "Due tomorrow", "Due in 5 days", "Due 20 Mar". */
    fun dueLabel(dueDate: LocalDate, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String =
        when (val days = ChronoUnit.DAYS.between(today, dueDate)) {
            0L -> "Due today"
            1L -> "Due tomorrow"
            in 2L..7L -> "Due in $days days"
            else -> "Due ${DateTimeFormatter.ofPattern("dd MMM", locale).format(dueDate)}"
        }

    /** Due dates within three days are worth drawing the eye to. */
    fun isUrgent(dueDate: LocalDate, today: LocalDate = LocalDate.now()): Boolean =
        ChronoUnit.DAYS.between(today, dueDate) in 0L..3L

    fun monthName(month: java.time.YearMonth, locale: Locale = Locale.getDefault()): String =
        DateTimeFormatter.ofPattern("MMMM", locale).format(month)
}
