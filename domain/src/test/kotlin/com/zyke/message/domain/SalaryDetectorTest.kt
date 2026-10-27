package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.IncomeSource
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanInsightsBuilder
import com.zyke.message.domain.plan.SalaryDetector
import com.zyke.message.domain.plan.SalaryStatus
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class SalaryDetectorTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun at(date: LocalDate) = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    private fun credit(amount: String, on: LocalDate, payer: String = "acme@okhdfcbank") = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Credit Alert!\nRs.$amount credited to HDFC Bank A/c xx5765 on 01-10-26 from VPA $payer (UPI ${100000000000L + nextId})",
            at(on), true
        )
    )

    private fun salaries(vararg amounts: String) =
        amounts.mapIndexed { i, amount -> credit(amount, today.withDayOfMonth(1).minusMonths(amounts.size - i.toLong())) }

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun aSalaryThatDiffersABitEachMonth_isFoundWithAMiddleAmountAndARange() {
        // Three months, each a little different, then nothing yet this month.
        val pattern = SalaryDetector.detect(salaries("98500.00", "101200.00", "99800.00"), now, zone)!!
        assertEquals(0, bd("99800").compareTo(pattern.typical))
        assertEquals(0, bd("98500").compareTo(pattern.low))
        assertEquals(0, bd("101200").compareTo(pattern.high))
        assertEquals(1, pattern.typicalDay)
        assertNull(pattern.thisMonth)
    }

    @Test
    fun aBonusOrOneOffCredit_doesNotChangeWhatIsExpected() {
        val messages = salaries("100000.00", "100500.00", "99500.00") +
            credit("250000.00", today.minusMonths(1).withDayOfMonth(20), payer = "acme@okhdfcbank") + // bonus: far off the usual
            credit("45000.00", today.minusDays(3), payer = "friend@ybl") // a single credit from someone else
        val pattern = SalaryDetector.detect(messages, now, zone)!!
        assertEquals(0, bd("100000").compareTo(pattern.typical))
        assertEquals("acme@okhdfcbank", pattern.payer)
    }

    @Test
    fun oneCreditAlone_isNotASalary() {
        assertNull(SalaryDetector.detect(listOf(credit("95000.00", today.minusDays(5))), now, zone))
        assertNull(SalaryDetector.detect(emptyList(), now, zone))
    }

    @Test
    fun spottedSalaryLeadsThePlan_enteredOneIsTheFallback() {
        val enteredOnly = MoneyPlan(salary = bd("90000"), salaryDay = 1)

        // No pattern in the messages: the entered salary is used.
        val fallback = PlanInsightsBuilder.build(enteredOnly, emptyList(), now, zone)!!
        assertEquals(IncomeSource.ENTERED, fallback.incomeSource)
        assertEquals(0, bd("90000").compareTo(fallback.income))

        // A pattern, but this month's has not come: estimate from the recent months, not the entered amount.
        val estimate = PlanInsightsBuilder.build(enteredOnly, salaries("98500.00", "101200.00", "99800.00"), now, zone)!!
        assertEquals(IncomeSource.SPOTTED_ESTIMATE, estimate.incomeSource)
        assertEquals(0, bd("99800").compareTo(estimate.income))
        assertNotNull(estimate.salaryRange)
        assertEquals(SalaryStatus.LATE, estimate.salaryStatus) // day 1, now the 20th

        // This month's has arrived: the real amount is the income.
        val arrived = PlanInsightsBuilder.build(
            enteredOnly, salaries("98500.00", "101200.00", "99800.00") + credit("102300.00", today.withDayOfMonth(1)), now, zone
        )!!
        assertEquals(IncomeSource.SPOTTED_RECEIVED, arrived.incomeSource)
        assertEquals(0, bd("102300").compareTo(arrived.income))
        assertEquals(SalaryStatus.RECEIVED, arrived.salaryStatus)
    }

    @Test
    fun aSpottedSalaryAloneIsEnoughForAPlan_evenWithNothingEntered() {
        val insights = PlanInsightsBuilder.build(MoneyPlan(), salaries("98500.00", "101200.00", "99800.00"), now, zone)
        assertNotNull(insights)
        assertEquals(IncomeSource.SPOTTED_ESTIMATE, insights!!.incomeSource)
    }
}
