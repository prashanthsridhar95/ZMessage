package com.zyke.message.domain

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.forecast.ForecastStories
import com.zyke.message.domain.forecast.SpendForecast
import com.zyke.message.domain.forecast.Verdict
import com.zyke.message.domain.plan.PlanInsights
import com.zyke.message.domain.plan.SalaryRunways
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForecastStoryTest {
    private val today = LocalDate.of(2026, 10, 10)

    private fun insights(spendable: Int, spent: Int) = PlanInsights(
        income = BigDecimal(spendable), outflows = BigDecimal.ZERO, investments = BigDecimal.ZERO, savingsGoal = BigDecimal.ZERO,
        spendable = BigDecimal(spendable), spentOnTheRest = BigDecimal(spent), spendableLeft = BigDecimal(spendable - spent),
        daysLeft = 21, perDayLeft = null, salaryReceived = null, salaryStatus = null, items = emptyList(), savedSoFar = BigDecimal.ZERO,
        incomeSource = null, salaryDay = 1, salaryRange = null
    )

    private fun summary(spent: Int, projected: Int?) = MonthlySummary(
        range = DateRange(LocalDate.of(2026, 10, 1), today), previousRange = null,
        income = BigDecimal.ZERO, spent = BigDecimal(spent), previousIncome = BigDecimal.ZERO, previousSpent = BigDecimal.ZERO,
        changes = emptyList(), cumulativeSpent = listOf(BigDecimal(spent)), cycleDays = 31, projectedSpent = projected?.let { BigDecimal(it) }
    )

    private fun forecast(cardNeeded: Int? = null) = SpendForecast(
        cycleEnd = LocalDate.of(2026, 10, 31), daysLeft = 21, balanceNow = BigDecimal(1000), bankPerDay = BigDecimal.TEN,
        cardPerDay = BigDecimal.ZERO, projectedBankSpend = BigDecimal.ZERO, projectedCardSpend = BigDecimal.ZERO, billsDue = BigDecimal.ZERO,
        expectedIncome = BigDecimal.ZERO, endBalance = cardNeeded?.let { BigDecimal(-it) } ?: BigDecimal(500), runsOutOn = null,
        cardNeeded = cardNeeded?.let { BigDecimal(it) }, suggestions = emptyList()
    )

    @Test
    fun underPlanWhenTheMoneyLasts() {
        // 1000 spent in 10 days = 100 a day; 21 more days = 2100; 1000 + 2100 = 3100 of 30000.
        val ins = insights(30000, 1000)
        val runway = SalaryRunways.project(ins, today, CycleRule.CALENDAR)
        val story = ForecastStories.build(forecast(), runway, ins, summary(1000, 3100))
        assertEquals(Verdict.ON_TRACK, story.verdict)
        assertEquals(BigDecimal(26900), story.planGap)
        assertEquals(BigDecimal(29000), story.planLeft)
        assertEquals(BigDecimal(30000), story.planLimit)
        assertEquals(BigDecimal(3100), story.projectedSpend)
    }

    @Test
    fun runsOutWhenThePaceOutrunsThePlan() {
        val ins = insights(15000, 10000)
        val runway = SalaryRunways.project(ins, today, CycleRule.CALENDAR)
        val story = ForecastStories.build(forecast(), runway, ins, summary(10000, 31000))
        assertEquals(Verdict.RUNS_OUT, story.verdict)
        assertEquals(LocalDate.of(2026, 10, 15), story.runsOutOn)
        assertEquals(runway!!.daysBefore, story.daysBeforePayday)
        // 10000 + 1000 * 21 = 31000 against 15000.
        assertEquals(BigDecimal(-16000), story.planGap)
    }

    @Test
    fun usedUpWhenNothingIsLeft() {
        val ins = insights(10000, 12000)
        val story = ForecastStories.build(null, SalaryRunways.project(ins, today, CycleRule.CALENDAR), ins, summary(12000, null))
        assertEquals(Verdict.USED_UP, story.verdict)
        // The limit never sits below what is already spent.
        assertEquals(BigDecimal(12000), story.planLimit)
    }

    @Test
    fun shortBalanceBeatsAnOnTrackPlan() {
        val ins = insights(30000, 1000)
        val story = ForecastStories.build(forecast(cardNeeded = 800), SalaryRunways.project(ins, today, CycleRule.CALENDAR), ins, summary(1000, 3100))
        assertEquals(Verdict.SHORT, story.verdict)
    }

    @Test
    fun noPlanAndNoBalanceIsJustAPace() {
        val story = ForecastStories.build(forecast().copy(endBalance = null, balanceNow = null), null, null, null)
        assertEquals(Verdict.PACE, story.verdict)
        assertNull(story.planGap)
        assertNull(story.planLimit)
    }
}
