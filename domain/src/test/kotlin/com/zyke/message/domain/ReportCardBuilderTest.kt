package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.dashboard.Grade
import com.zyke.message.domain.dashboard.Remark
import com.zyke.message.domain.dashboard.ReportCardBuilder
import com.zyke.message.domain.dashboard.Trend
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReportCardBuilderTest {
    private val zone = ZoneId.of("UTC")
    private val now = ZonedDateTime.of(2025, 3, 15, 12, 0, 0, 0, zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun sent(amount: String, to: String, daysAgo: Long): ClassifiedMessage = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Sent Rs.$amount\nFrom HDFC Bank A/C x1234\nTo $to\nOn 12/01/25\nRef 123456",
            now.minusSeconds(daysAgo * 86_400).toEpochMilli(), true
        )
    )

    private fun received(amount: String, daysAgo: Long): ClassifiedMessage = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Credit Alert!\nRs.$amount credited to HDFC Bank A/c xx9876 on 01-02-25 from VPA salary@okaxis (UPI 123456789)",
            now.minusSeconds(daysAgo * 86_400).toEpochMilli(), true
        )
    )

    private fun build(messages: List<ClassifiedMessage>, budgets: Map<MessageCategory, BigDecimal> = emptyMap()) =
        ReportCardBuilder.build(messages, budgets, now, zone)

    @Test
    fun noMessages_givesEmptyCard() {
        val card = build(emptyList())
        assertTrue(card.isEmpty)
        assertNull(card.overall)
    }

    @Test
    fun overall_followsHowMuchIncomeIsKept() {
        val keeps = build(listOf(received("10000.00", 2), sent("2000.00", "a@ybl", 3)))
        assertEquals(80, keeps.savingsPercent)
        assertEquals(Grade.A, keeps.overall)

        val overspends = build(listOf(received("1000.00", 2), sent("3000.00", "a@ybl", 3)))
        assertEquals(Grade.F, overspends.overall)
    }

    @Test
    fun withoutIncome_overallFallsBackToTheSubjectGrades() {
        // 15 Mar: this month 1000 so far; last month by the 15th: 2000 -> -50%, an A.
        val card = build(listOf(sent("1000.00", "a@ybl", 1), sent("2000.00", "a@ybl", 40)))
        assertNull(card.savingsPercent)
        assertEquals(Grade.A, card.overall)
        assertEquals(Trend.DOWN, card.subjects.single().trend)
    }

    @Test
    fun budget_decidesTheGradeAndTheRemark() {
        val first = build(listOf(sent("1500.00", "a@ybl", 1)))
        val category = first.subjects.single().category

        val over = build(listOf(sent("1500.00", "a@ybl", 1)), mapOf(category to BigDecimal(1000)))
        assertEquals(Grade.F, over.subjects.single().grade)
        val remark = over.remark as Remark.OverBudget
        assertEquals(0, BigDecimal(500).compareTo(remark.over))

        val within = build(listOf(sent("500.00", "a@ybl", 1)), mapOf(category to BigDecimal(1000)))
        assertEquals(Grade.A, within.subjects.single().grade)
    }

    @Test
    fun budgetedCategory_staysOnTheCardBeforeAnySpend() {
        val card = build(emptyList(), mapOf(MessageCategory.FOOD to BigDecimal(5000)))
        assertEquals(MessageCategory.FOOD, card.subjects.single().category)
        assertEquals(Grade.A, card.subjects.single().grade)
    }

    @Test
    fun bigRise_isCalledOut_andNewSpendHasNoGrade() {
        val rise = build(listOf(sent("1500.00", "a@ybl", 1), sent("1000.00", "a@ybl", 40)))
        assertEquals(Grade.D, rise.subjects.single().grade)
        assertEquals(50, (rise.remark as Remark.Rising).percent)

        val fresh = build(listOf(sent("1500.00", "a@ybl", 1)))
        assertNull(fresh.subjects.single().grade)
        assertEquals(Trend.NEW, fresh.subjects.single().trend)
    }

    @Test
    fun gradeFromAverage_roundsToNearest() {
        assertEquals(Grade.A, Grade.fromAverage(3.9))
        assertEquals(Grade.C, Grade.fromAverage(2.2))
    }
}
