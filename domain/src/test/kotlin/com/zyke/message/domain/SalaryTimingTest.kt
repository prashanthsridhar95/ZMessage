package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.IncomeSource
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanInsightsBuilder
import com.zyke.message.domain.plan.SalaryConfidence
import com.zyke.message.domain.plan.SalaryDetector
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SalaryTimingTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L
    private val thisMonth = YearMonth.from(today)

    private fun at(date: LocalDate) = date.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()

    /** A credit whose payee text is different every time, as when the narration carries a reference number. */
    private fun credit(amount: String, on: LocalDate, body: String? = null): ClassifiedMessage {
        val id = nextId++
        return classifier.classify(
            SmsMessage(
                id, "AD-HDFCBK",
                body ?: "Credit Alert!\nRs.$amount credited to HDFC Bank A/c xx5765 on 01-10-26 from VPA ${letters(id)}@okhdfcbank (UPI ${100000000000L + id})",
                at(on), true
            )
        )
    }

    /** Digits are dropped from a payer's name, so make the name itself differ. */
    private fun letters(n: Long) = "p" + n.toString().map { 'a' + (it - '0') }.joinToString("") + (n * 7).toString().map { 'k' + (it - '0') }.joinToString("")

    private fun inMonth(monthsAgo: Long, day: Int) = thisMonth.minusMonths(monthsAgo).let { it.atDay(minOf(day, it.lengthOfMonth())) }

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun payerChangesEveryMonth_butSizeAndDayRepeat_isDetected() {
        val msgs = listOf(5, 4, 3, 2, 1).map { credit("52000.00", inMonth(it.toLong(), 5)) }
        val p = SalaryDetector.detect(msgs, now, zone)!!
        assertEquals("timing", p.payer)
        assertEquals(5, p.typicalDay)
        assertEquals(5, p.monthsSeen)
        assertEquals(0, bd("52000").compareTo(p.typical))
        assertEquals(SalaryConfidence.MEDIUM, p.confidence)
        assertNull(p.thisMonth)
    }

    @Test
    fun amountDriftingTenPercent_isDetected_andThisMonthIsPicked() {
        val msgs = listOf("50000", "55000", "47500", "52000").mapIndexed { i, a -> credit(a, inMonth(4L - i, 3)) } +
            credit("51000", inMonth(0, 4))
        val p = SalaryDetector.detect(msgs, now, zone)!!
        assertEquals(0, bd("51000").compareTo(p.thisMonth!!))
        assertEquals(0, bd("47500").compareTo(p.low))
        assertEquals(0, bd("55000").compareTo(p.high))
    }

    @Test
    fun dayDriftingAcrossMonthEnd_isDetected() {
        val days = listOf(28, 31, 1, 30, 2)
        val msgs = days.mapIndexed { i, d -> credit("60000", inMonth(5L - i, d)) }
        val p = SalaryDetector.detect(msgs, now, zone)!!
        assertEquals("timing", p.payer)
        assertTrue(p.monthsSeen >= 4)
        assertTrue("day ${p.typicalDay}", p.typicalDay >= 28 || p.typicalDay <= 2)
    }

    @Test
    fun aLastWorkingDaySalary_isDetected() {
        val days = listOf(30, 29, 31, 30, 28)
        val msgs = days.mapIndexed { i, d -> credit("75000", inMonth(5L - i, d)) }
        assertNotNull(SalaryDetector.detect(msgs, now, zone))
    }

    @Test
    fun oneOffLargeCredit_isNotDetected() {
        assertNull(SalaryDetector.detect(listOf(credit("90000", inMonth(2, 10))), now, zone))
        // A bonus among small everyday credits.
        val msgs = listOf(credit("90000", inMonth(2, 10))) + (0L..5L).map { credit("800", inMonth(it, 12)) }
        assertNull(SalaryDetector.detect(msgs, now, zone))
    }

    @Test
    fun twoMonthsOnly_needsShortHistory() {
        val two = listOf(credit("40000", inMonth(2, 7)), credit("40000", inMonth(1, 7)))
        // Only two months of messages: two matching months are enough, as a low-confidence estimate.
        val short = SalaryDetector.detect(two, now, zone)
        assertEquals(SalaryConfidence.LOW, short!!.confidence)
        // A longer history with just two matching months is not enough.
        val longHistory = two + credit("300", inMonth(5, 9))
        assertNull(SalaryDetector.detect(longHistory, now, zone))
    }

    @Test
    fun cardRefundsInternalAndSetAsideCredits_areIgnored() {
        val refunds = (1L..5L).map {
            credit("0", inMonth(it, 8), "Refund of Rs.30000.00 credited to HDFC Bank A/c xx5765 on 01-10-26 from VPA ${letters(it)}@ybl (UPI ${it}12345678901)")
        }
        assertNull(SalaryDetector.detect(refunds, now, zone))

        val cardPayments = (1L..5L).map {
            credit("0", inMonth(it, 8), "Payment of Rs.30,000.00 received on your Credit Card XX1234, thank you")
        }
        assertNull(SalaryDetector.detect(cardPayments, now, zone))

        val setAside = (1L..5L).map { credit("30000", inMonth(it, 8)).copy(setAside = SetAside.EXCLUDED) }
        assertNull(SalaryDetector.detect(setAside, now, zone))
    }

    @Test
    fun manyUpiCreditsOfRandomSizes_isNotASalary() {
        val sizes = listOf(6200, 11800, 7400, 15300, 5600, 9100, 13700, 8200, 6900, 12100, 7800, 14500)
        val days = listOf(2, 11, 19, 25, 6, 14, 27, 9, 17, 22, 4, 13)
        val msgs = (0L..5L).flatMap { m ->
            listOf(0, 1).map { k -> credit("${sizes[(m * 2 + k).toInt()]}", inMonth(m, days[(m * 2 + k).toInt()])) }
        }
        assertNull(SalaryDetector.detect(msgs, now, zone))
    }

    @Test
    fun payerPatternKeepsPriority_andIsHighConfidence() {
        val payer = (1L..3L).map {
            credit("98000", inMonth(it, 1), "Credit Alert!\nRs.98000.00 credited to HDFC Bank A/c xx5765 on 01-10-26 from VPA acme@okhdfcbank (UPI 1000000000$it)")
        }
        val timingOnly = (1L..5L).map { credit("45000", inMonth(it, 15)) }
        val p = SalaryDetector.detect(payer + timingOnly, now, zone)!!
        assertEquals("acme@okhdfcbank", p.payer)
        assertEquals(SalaryConfidence.HIGH, p.confidence)
    }

    @Test
    fun lowConfidenceEstimate_neverOverridesAnEnteredSalary() {
        val two = listOf(credit("40000", inMonth(2, 7)), credit("40000", inMonth(1, 7)))
        val entered = PlanInsightsBuilder.build(MoneyPlan(salary = bd("90000"), salaryDay = 1), two, now, zone)!!
        assertEquals(IncomeSource.ENTERED, entered.incomeSource)
        val none = PlanInsightsBuilder.build(MoneyPlan(), two, now, zone)!!
        assertEquals(IncomeSource.SPOTTED_ESTIMATE, none.incomeSource)
        assertEquals(SalaryConfidence.LOW, none.salaryConfidence)
        assertFalse(none.salaryDay == null)
    }

    @Test
    fun mediumConfidenceEstimate_leadsLikeASpottedSalary() {
        val msgs = (1L..5L).map { credit("52000", inMonth(it, 5)) }
        val i = PlanInsightsBuilder.build(MoneyPlan(salary = bd("90000"), salaryDay = 1), msgs, now, zone)!!
        assertEquals(IncomeSource.SPOTTED_ESTIMATE, i.incomeSource)
        assertEquals(SalaryConfidence.MEDIUM, i.salaryConfidence)
    }
}
