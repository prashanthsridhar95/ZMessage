package com.zyke.message.domain

import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.insights.RecurringPayments
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.RecurringState
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MonthlySummaryAndRecurringTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private var nextId = 1L

    private fun tx(
        amount: String,
        on: LocalDate,
        received: Boolean = false,
        category: MessageCategory = MessageCategory.FOOD,
        cardPayment: Boolean = false,
        setAside: SetAside? = null
    ) = ClassifiedMessage(
        message = SmsMessage(nextId++, "AD-HDFCBK", "x", on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true),
        category = category,
        merchant = Merchant("M", MerchantLogo.UPI, category),
        content = MessageContent.Transaction(BankName.HDFC, "M", amount, "1234", received, false, cardPayment),
        setAside = setAside
    )

    @Test
    fun savingsRate_andNet() {
        val s = MonthlySummary.build(listOf(tx("50000", today.minusDays(15), received = true), tx("20000", today.minusDays(3))), now, zone)
        assertEquals(BigDecimal("30000"), s.net)
        assertEquals(60, s.savingsRatePercent)
    }

    @Test
    fun noIncome_savingsRateNotAvailable() {
        val s = MonthlySummary.build(listOf(tx("100", today)), now, zone)
        assertNull(s.savingsRatePercent)
        assertTrue(s.lines.last().contains("not available"))
    }

    @Test
    fun setAsideAndCardPayments_areNotCounted() {
        val s = MonthlySummary.build(
            listOf(
                tx("500", today, setAside = SetAside.TRANSFER),
                tx("700", today, received = true, cardPayment = true),
                tx("200", today)
            ),
            now, zone
        )
        assertEquals(BigDecimal("200"), s.spent)
        assertEquals(BigDecimal.ZERO, s.income)
    }

    @Test
    fun categoryChange_isLikeForLike_andBiggestFirst() {
        // Today is the 20th: compare 1-20 Oct with 1-20 Sep. A late-September payment must not count.
        val messages = listOf(
            tx("14160", LocalDate.of(2026, 10, 5)),
            tx("12000", LocalDate.of(2026, 9, 5)),
            tx("99999", LocalDate.of(2026, 9, 28)),
            tx("500", LocalDate.of(2026, 10, 6), category = MessageCategory.FUEL),
            tx("1000", LocalDate.of(2026, 9, 6), category = MessageCategory.FUEL)
        )
        val s = MonthlySummary.build(messages, now, zone)
        assertEquals(MessageCategory.FOOD, s.changes.first().category)
        assertEquals(18, s.changes.first().percent)
        assertEquals("Food up 18% (₹2,160 more)", s.changes.first().text)
        assertEquals("Fuel Spends down 50% (₹500 less)", s.changes[1].text)
    }

    private fun sub(name: String) =
        Subscription(name, BigDecimal(199), today.minusDays(20), LocalDate.of(2026, 11, 12), 3, 1L)

    @Test
    fun recurring_ignoredHidden_confirmedKept_andLabel() {
        val a = sub("Netflix")
        val b = sub("Gym")
        val c = sub("Spotify")
        val items = RecurringPayments.arrange(listOf(a, b, c), mapOf(a.key to RecurringState.CONFIRMED, b.key to RecurringState.IGNORED))
        assertEquals(listOf("Netflix", "Spotify"), items.map { it.subscription.payee })
        assertTrue(items[0].isConfirmed)
        assertNull(items[1].state)
        assertEquals("expected ~12 Nov", items[0].expectedLabel)
    }

    @Test
    fun heroLine_belowAboveLevelAndNew() {
        // today is 20 Oct; the same point last month is 20 Sep.
        val below = MonthlySummary.build(listOf(tx("300", today.minusDays(2)), tx("1000", today.minusDays(31))), now, zone)
        assertEquals(BigDecimal("-700"), below.spentDelta)
        assertTrue(below.heroLine.contains("below last month"))
        assertEquals(-70, below.spentDeltaPercent)
        val above = MonthlySummary.build(listOf(tx("1500", today.minusDays(2)), tx("1000", today.minusDays(31))), now, zone)
        assertTrue(above.heroLine.contains("above last month"))
        assertEquals(50, above.spentDeltaPercent)
        val fresh = MonthlySummary.build(listOf(tx("200", today)), now, zone)
        assertNull(fresh.spentDeltaPercent)
        assertTrue(fresh.heroLine.contains("Nothing was spent"))
        assertTrue(MonthlySummary.build(emptyList(), now, zone).heroLine.startsWith("No spending"))
    }

    @Test
    fun cumulativeSeries_andProjection() {
        val s = MonthlySummary.build(listOf(tx("100", LocalDate.of(2026, 10, 1)), tx("200", LocalDate.of(2026, 10, 5)), tx("50", LocalDate.of(2026, 9, 30))), now, zone)
        assertEquals(20, s.cumulativeSpent.size)
        assertEquals(BigDecimal("100"), s.cumulativeSpent[0])
        assertEquals(BigDecimal("100"), s.cumulativeSpent[3])
        assertEquals(BigDecimal("300"), s.cumulativeSpent[4])
        assertEquals(BigDecimal("300"), s.cumulativeSpent.last())
        assertEquals(30, s.previousCumulativeSpent.size)
        assertEquals(BigDecimal("50"), s.previousCumulativeSpent.last())
        assertEquals(31, s.cycleDays)
        // 300 over 20 days of 31 = 465
        assertEquals(BigDecimal("465"), s.projectedSpent)
        assertEquals(BigDecimal("415"), s.projectedVsLastMonth)
    }

    @Test
    fun projection_edgeCases() {
        assertNull(MonthlySummary.project(BigDecimal("100"), 2, 30))
        assertNull(MonthlySummary.project(BigDecimal.ZERO, 10, 30))
        assertEquals(BigDecimal("100"), MonthlySummary.project(BigDecimal("100"), 30, 30))
        assertEquals(BigDecimal("300"), MonthlySummary.project(BigDecimal("100"), 10, 30))
    }

    @Test
    fun cumulative_ignoresOutOfRangeDays() {
        val start = LocalDate.of(2026, 10, 1)
        val c = MonthlySummary.cumulative(listOf(start.minusDays(1) to BigDecimal("9"), start.plusDays(1) to BigDecimal("5"), start.plusDays(40) to BigDecimal("7")), start, 3)
        assertEquals(listOf(BigDecimal.ZERO, BigDecimal("5"), BigDecimal("5")), c)
    }
}
