package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.cycle.CycleMode
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.MoneyPlan
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Test

class PayCycleTest {
    private val zone = ZoneId.of("UTC")
    private val classifier = MessageClassifier()
    private var nextId = 1L

    @Test
    fun cycleStartingOnThe28th_runsFromOneSalaryDayToTheDayBeforeTheNext() {
        val rule = CycleRule(28)
        assertEquals(DateRange(LocalDate.of(2026, 9, 28), LocalDate.of(2026, 10, 27)), rule.containing(LocalDate.of(2026, 10, 5)))
        assertEquals(DateRange(LocalDate.of(2026, 10, 28), LocalDate.of(2026, 11, 27)), rule.containing(LocalDate.of(2026, 10, 28)))
        assertEquals(DateRange(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 9, 27)), rule.shifted(LocalDate.of(2026, 10, 5), -1))
        assertEquals(30, rule.length(LocalDate.of(2026, 9, 28)).let { it })
        assertEquals(1, rule.dayNumber(LocalDate.of(2026, 10, 28)))
    }

    @Test
    fun startDayOne_isTheCalendarMonth_andTheLastDayCoversShortMonths() {
        assertEquals(DateRange(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 2, 28)), CycleRule(1).containing(LocalDate.of(2026, 2, 10)))
        // The 31st falls on the last day of a shorter month.
        assertEquals(DateRange(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 30)), CycleRule(31).containing(LocalDate.of(2026, 3, 5)))
    }

    @Test
    fun theSettingChoosesTheRule() {
        val now = LocalDate.of(2026, 10, 20).atTime(12, 0).atZone(zone).toInstant()
        assertEquals(CycleRule(1), CycleRule.resolve(MoneyPlan(cycleMode = CycleMode.CALENDAR, salaryDay = 5), emptyList(), now, zone))
        assertEquals(CycleRule(12), CycleRule.resolve(MoneyPlan(cycleMode = CycleMode.DAY, cycleDay = 12), emptyList(), now, zone))
        // With no salary seen in the messages, the day entered with the salary is used.
        assertEquals(CycleRule(5), CycleRule.resolve(MoneyPlan(cycleMode = CycleMode.SALARY, salaryDay = 5), emptyList(), now, zone))
    }

    private fun spend(amount: String, on: LocalDate) = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo CAFE ONE\nOn ${on.format(DateTimeFormatter.ofPattern("dd/MM/yy"))}\nRef ${nextId * 13}",
            on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true
        )
    )

    @Test
    fun dashboardFollowsTheCycle_andACustomRange() {
        val today = LocalDate.of(2026, 10, 5)
        val now = today.atTime(12, 0).atZone(zone).toInstant()
        val messages = listOf(
            spend("100.00", LocalDate.of(2026, 9, 27)), // last cycle
            spend("200.00", LocalDate.of(2026, 9, 28)), // this cycle
            spend("300.00", LocalDate.of(2026, 10, 4))  // this cycle
        )
        val rule = CycleRule(28)

        val cycle = DashboardBuilder.build(messages, DashboardQuery(DashboardPeriod.THIS_MONTH, FlowDirection.SPENT), now, zone, cycle = rule)
        assertEquals(LocalDate.of(2026, 9, 28), cycle.range.start)
        assertEquals(0, BigDecimal("500").compareTo(cycle.total))
        // The same number of days into the previous cycle, which had no spending yet.
        assertEquals(0, BigDecimal.ZERO.compareTo(cycle.previousTotal!!))

        val calendar = DashboardBuilder.build(messages, DashboardQuery(DashboardPeriod.THIS_MONTH, FlowDirection.SPENT), now, zone)
        assertEquals(0, BigDecimal("300").compareTo(calendar.total))

        val custom = DashboardBuilder.build(
            messages,
            DashboardQuery(DashboardPeriod.CUSTOM, FlowDirection.SPENT, customRange = DateRange(LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))),
            now, zone
        )
        assertEquals(0, BigDecimal("300").compareTo(custom.total))
    }
}
