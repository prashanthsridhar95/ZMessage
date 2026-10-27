package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanInsightsBuilder
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanItemKind
import com.zyke.message.domain.plan.SalaryStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanInsightsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun at(date: LocalDate) = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sent(amount: String, to: String, on: LocalDate) = classifier.classify(
        SmsMessage(nextId++, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo $to\nOn 20/10/26\nRef $nextId", at(on), true)
    )

    private fun credit(amount: String, on: LocalDate) = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Credit Alert!\nRs.$amount credited to HDFC Bank A/c xx5765 on 01-10-26 from VPA acme@okhdfcbank (UPI 123456789012)",
            at(on), true
        )
    )

    private fun bd(value: String) = BigDecimal(value)

    private val plan = MoneyPlan(
        salary = bd("100000"), salaryDay = 1,
        items = listOf(
            PlanItem("p", PlanItemKind.OUTFLOW, "Parents", bd("15000"), 5),
            PlanItem("c", PlanItemKind.OUTFLOW, "Charity", bd("2000")),
            PlanItem("s", PlanItemKind.INVESTMENT, "Index fund SIP", bd("20000"), 7)
        ),
        savingsGoal = bd("25000")
    )

    @Test
    fun spendable_isWhatIsLeftAfterTheWholePlan() {
        val insights = PlanInsightsBuilder.build(plan, emptyList(), now, zone)!!
        assertEquals(0, bd("38000").compareTo(insights.spendable))
        assertEquals(0, bd("17000").compareTo(insights.outflows))
        assertEquals(0, bd("20000").compareTo(insights.investments))
        assertTrue(insights.planAddsUp)
    }

    @Test
    fun plannedPayments_areSpottedAndKeptOutOfEverydaySpending() {
        val messages = listOf(
            credit("100000.00", today.minusDays(19)),
            sent("15000.00", "Parents Savings", today.minusDays(15)),   // by name
            sent("20000.00", "SOME FUND HOUSE", today.minusDays(13)),   // by exact amount
            sent("3000.00", "SWIGGY", today.minusDays(2)),
            sent("1000.00", "AMAZON", today)
        )
        val insights = PlanInsightsBuilder.build(plan, messages, now, zone)!!

        val byName = insights.items.associateBy { it.item.id }
        assertTrue(byName.getValue("p").isPaid)
        assertTrue(byName.getValue("s").isPaid)
        assertFalse(byName.getValue("c").isPaid)

        assertEquals(0, bd("4000").compareTo(insights.spentOnTheRest))
        assertEquals(0, bd("34000").compareTo(insights.spendableLeft))
        // 12 days left including today (20th to 31st): 34000 / 12 = 2833 a day.
        assertEquals(12, insights.daysLeft)
        assertEquals(0, bd("2833").compareTo(insights.perDayLeft!!))
        assertEquals(SalaryStatus.RECEIVED, insights.salaryStatus)
    }

    @Test
    fun overspending_turnsTheLeftoverNegative_andSalaryCanBeLate() {
        val messages = listOf(sent("50000.00", "GADGET STORE", today.minusDays(1)))
        val insights = PlanInsightsBuilder.build(plan, messages, now, zone)!!
        assertTrue(insights.isOverPlan)
        assertNull(insights.perDayLeft)
        assertEquals(SalaryStatus.LATE, insights.salaryStatus)
    }

    @Test
    fun aPlanThatDoesNotAddUp_isSaid_andAnEmptyPlanGivesNothing() {
        val tooBig = plan.copy(savingsGoal = bd("90000"))
        assertFalse(PlanInsightsBuilder.build(tooBig, emptyList(), now, zone)!!.planAddsUp)
        assertNull(PlanInsightsBuilder.build(MoneyPlan(), emptyList(), now, zone))
        assertNotNull(PlanInsightsBuilder.build(MoneyPlan(savingsGoal = bd("1")), emptyList(), now, zone))
    }
}
