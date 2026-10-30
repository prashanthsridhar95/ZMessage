package com.zyke.message.domain.plan

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

object SalaryDays {
    /** The last Monday-to-Friday of [month]; bank holidays are not known, so a holiday on that day is not allowed for. */
    fun lastWorkingDay(month: YearMonth): LocalDate {
        var day = month.atEndOfMonth()
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        return day
    }
}
