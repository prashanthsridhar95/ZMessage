package com.zyke.message.domain

import com.zyke.message.domain.calendar.CalendarBuilder
import com.zyke.message.domain.calendar.CalendarKind
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.SalaryDays
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class SalaryDaysTest {
    @Test
    fun theLastWorkingDayStepsBackFromTheWeekend() {
        // 31 Oct 2026 is a Saturday, 30 Nov 2026 a Monday, 31 Jan 2027 a Sunday, 31 Dec 2026 a Thursday.
        assertEquals(LocalDate.of(2026, 10, 30), SalaryDays.lastWorkingDay(YearMonth.of(2026, 10)))
        assertEquals(LocalDate.of(2026, 11, 30), SalaryDays.lastWorkingDay(YearMonth.of(2026, 11)))
        assertEquals(LocalDate.of(2027, 1, 29), SalaryDays.lastWorkingDay(YearMonth.of(2027, 1)))
        assertEquals(LocalDate.of(2026, 12, 31), SalaryDays.lastWorkingDay(YearMonth.of(2026, 12)))
    }

    @Test
    fun thePlanGivesTheSalaryDayOfEachMonth() {
        val plan = MoneyPlan(salary = java.math.BigDecimal(90_000), salaryOnLastWorkingDay = true)
        assertEquals(30, plan.salaryDayIn(YearMonth.of(2026, 10)))
        assertEquals(29, plan.salaryDayIn(YearMonth.of(2027, 1)))
        assertEquals(5, plan.copy(salaryOnLastWorkingDay = false, salaryDay = 5).salaryDayIn(YearMonth.of(2026, 10)))
    }

    @Test
    fun theCalendarPutsSalaryOnThatDay() {
        val plan = MoneyPlan(salary = java.math.BigDecimal(90_000), salaryOnLastWorkingDay = true)
        val zone = ZoneId.systemDefault()
        val entries = CalendarBuilder.month(
            YearMonth.of(2026, 10), emptyList(), emptyList(), emptyList(), emptyList(), plan,
            Instant.parse("2026-10-10T06:00:00Z"), zone
        )
        val salaryDays = entries.filterValues { list -> list.any { it.kind == CalendarKind.SALARY } }.keys
        assertEquals(setOf(LocalDate.of(2026, 10, 30)), salaryDays)
    }
}
