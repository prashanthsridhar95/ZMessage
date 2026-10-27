package com.zyke.message.domain.reminders

import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth

/** Something the person wants to be told about on a day: rent, a premium, a friend to pay. */
data class Reminder(
    val id: String,
    val title: String,
    val amount: BigDecimal? = null,
    /** The day, or the first day when it repeats. */
    val date: LocalDate,
    val repeatsMonthly: Boolean = false
)

/** A reminder and the day it next falls on. */
data class ReminderDue(val reminder: Reminder, val on: LocalDate) {
    fun daysFrom(today: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(today, on)
}

object Reminders {
    /** Reminders falling from [today] on, within [withinDays] days (all of them when null), soonest first. */
    fun upcoming(reminders: List<Reminder>, today: LocalDate, withinDays: Long? = null): List<ReminderDue> =
        reminders.mapNotNull { next(it, today)?.let { on -> ReminderDue(it, on) } }
            .filter { withinDays == null || it.daysFrom(today) <= withinDays }
            .sortedBy { it.on }

    /** The next day the reminder falls on, today included; null for a one-off that has passed. */
    fun next(reminder: Reminder, today: LocalDate): LocalDate? {
        if (!reminder.repeatsMonthly) return reminder.date.takeIf { !it.isBefore(today) }
        if (today.isBefore(reminder.date)) return reminder.date
        var month = YearMonth.from(today)
        repeat(2) {
            val day = month.atDay(minOf(reminder.date.dayOfMonth, month.lengthOfMonth()))
            if (!day.isBefore(today)) return day
            month = month.plusMonths(1)
        }
        return null
    }
}
