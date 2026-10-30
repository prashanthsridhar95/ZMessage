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
    val repeatsMonthly: Boolean = false,
    /** When in the day to be told; null means only the morning summary mentions it. */
    val time: java.time.LocalTime? = null
)

/** A reminder and the day it next falls on. */
data class ReminderDue(val reminder: Reminder, val on: LocalDate) {
    fun daysFrom(today: LocalDate): Long = java.time.temporal.ChronoUnit.DAYS.between(today, on)
}

/** One notification to be posted: which reminder, and the moment. */
data class ReminderAlarm(val reminder: Reminder, val on: LocalDate, val at: java.time.LocalDateTime) {
    /** Remembered once posted, so the same reminder on the same day is not posted twice. */
    val key: String get() = "${reminder.id}@$on"
}

object Reminders {
    /**
     * The next reminder with a time that has yet to be posted: the earliest moment from a minute ago on, skipping those
     * already in [posted]. A moment only just passed still counts, so two reminders set for the same minute both fire.
     */
    fun nextAlarm(reminders: List<Reminder>, now: java.time.LocalDateTime, posted: Set<String>): ReminderAlarm? =
        reminders.filter { it.time != null }.mapNotNull { reminder ->
            var day = next(reminder, now.toLocalDate()) ?: return@mapNotNull null
            var alarm = ReminderAlarm(reminder, day, day.atTime(reminder.time))
            // Already posted, or passed for good: the next time it falls, if it repeats.
            var tries = 0
            while ((alarm.key in posted || alarm.at.isBefore(now.minusMinutes(1))) && tries < 3) {
                if (!reminder.repeatsMonthly) return@mapNotNull null
                day = next(reminder, day.plusDays(1)) ?: return@mapNotNull null
                alarm = ReminderAlarm(reminder, day, day.atTime(reminder.time))
                tries++
            }
            alarm.takeIf { it.key !in posted && !it.at.isBefore(now.minusMinutes(1)) }
        }.minByOrNull { it.at }

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
