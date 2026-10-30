package com.zyke.message.domain.cycle

import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.SalaryDetector
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Where a person's "month" begins. */
enum class CycleMode(val label: String) {
    /** From the day the salary arrives: spotted in the messages, else the day entered with the salary. */
    SALARY("From my salary day"),
    CALENDAR("Calendar month"),
    /** From a day the person picks. */
    DAY("From a day I choose")
}

/**
 * A "month" that starts on [startDay] instead of the 1st, so graphs, limits and the report card follow the pay
 * cycle: from one salary credit to the day before the next. A start day later than a month has (the 31st in
 * April) falls on the month's last day. With a start day of 1 it is the calendar month.
 */
data class CycleRule(val startDay: Int = 1) {
    private val day: Int = startDay.coerceIn(1, MAX_DAY)

    private fun startIn(month: YearMonth): LocalDate = month.atDay(minOf(day, month.lengthOfMonth()))

    /** The first day of the cycle that [date] falls in. */
    fun startOf(date: LocalDate): LocalDate {
        val thisMonth = startIn(YearMonth.from(date))
        return if (!date.isBefore(thisMonth)) thisMonth else startIn(YearMonth.from(date).minusMonths(1))
    }

    /** The whole cycle that [date] falls in, from its first day to its last. */
    fun containing(date: LocalDate): DateRange {
        val start = startOf(date)
        // The next cycle starts on the start day of the following month.
        return DateRange(start, startIn(YearMonth.from(start).plusMonths(1)).minusDays(1))
    }

    /** The cycle [offset] cycles from the one containing [date] (negative for earlier ones). */
    fun shifted(date: LocalDate, offset: Int): DateRange {
        val current = containing(date)
        val month = YearMonth.from(current.start).plusMonths(offset.toLong())
        val start = startIn(month)
        return DateRange(start, startIn(month.plusMonths(1)).minusDays(1))
    }

    fun length(date: LocalDate): Int = ChronoUnit.DAYS.between(containing(date).start, containing(date).endInclusive).toInt() + 1

    /** Days from [date] to the end of its cycle, counting [date]. */
    fun daysLeft(date: LocalDate): Int = ChronoUnit.DAYS.between(date, containing(date).endInclusive).toInt() + 1

    /** 1 on the first day of the cycle. */
    fun dayNumber(date: LocalDate): Int = ChronoUnit.DAYS.between(containing(date).start, date).toInt() + 1

    companion object {
        private const val MAX_DAY = 31
        val CALENDAR = CycleRule(1)

        /** The rule the person's settings call for; [now] and [messages] are used to find the salary day. */
        fun resolve(plan: MoneyPlan, messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): CycleRule = when (plan.cycleMode) {
            CycleMode.CALENDAR -> CALENDAR
            CycleMode.DAY -> CycleRule(plan.cycleDay ?: 1)
            CycleMode.SALARY -> CycleRule(SalaryDetector.detect(messages, now, zone)?.typicalDay ?: plan.salaryDayIn(java.time.YearMonth.from(now.atZone(zone))) ?: 1)
        }
    }
}
