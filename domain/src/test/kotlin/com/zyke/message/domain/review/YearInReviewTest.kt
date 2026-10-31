package com.zyke.message.domain.review

import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YearInReviewTest {
    private val zone = ZoneId.of("UTC")
    private var nextId = 1L

    private fun tx(
        amount: String,
        on: LocalDate,
        received: Boolean = false,
        payee: String = "Shop",
        category: MessageCategory = MessageCategory.FOOD,
        cardPayment: Boolean = false,
        setAside: SetAside? = null,
        promo: Boolean = false
    ) = ClassifiedMessage(
        message = SmsMessage(nextId++, "AD-HDFCBK", "x", on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true),
        category = category,
        merchant = Merchant(payee, MerchantLogo.UPI, category),
        content = MessageContent.Transaction(BankName.HDFC, payee, amount, "1234", received, false, cardPayment),
        isPromotional = promo,
        setAside = setAside
    )

    private fun d(m: Int, day: Int) = LocalDate.of(2025, m, day)

    @Test
    fun totalsRatesAndExclusions() {
        val msgs = listOf(
            tx("1000", d(1, 5), received = true, category = MessageCategory.SALARY),
            tx("200", d(1, 6), payee = "A"),
            tx("300", d(2, 6), payee = "A"),
            tx("900", d(2, 7), payee = "B", category = MessageCategory.SHOPPING),
            tx("5000", d(2, 8), cardPayment = true),
            tx("5000", d(2, 9), setAside = SetAside.EXCLUDED),
            tx("5000", d(2, 9), promo = true),
            tx("5000", LocalDate.of(2024, 2, 9))
        )
        val r = YearInReview.build(msgs, 2025, zone)
        assertEquals(0, BigDecimal(1000).compareTo(r.income))
        assertEquals(0, BigDecimal(1400).compareTo(r.spent))
        assertEquals(0, BigDecimal(-400).compareTo(r.net))
        assertEquals(-40, r.savingsRatePercent)
        assertEquals(4, r.transactionCount)
        assertEquals(2, r.monthsOfData)
        assertEquals("B", r.topPayees.first().name)
        assertEquals(MessageCategory.SHOPPING, r.topCategories.first().category)
        assertEquals(BigDecimal(900).setScale(0), r.biggestPayment!!.amount.setScale(0))
        assertEquals(Month.FEBRUARY, r.busiestMonth)
        assertEquals(Month.JANUARY, r.quietestMonth)
        assertEquals(12, r.months.size)
    }

    @Test
    fun noIncomeAndEmptyNeverDivideByZero() {
        val r = YearInReview.build(listOf(tx("10", d(3, 1))), 2025, zone)
        assertNull(r.savingsRatePercent)
        assertNull(r.busiestMonth)
        val e = YearInReview.build(emptyList(), 2025, zone)
        assertTrue(e.isEmpty)
        assertEquals(0, e.daysCovered)
        assertEquals(0, e.longestNoSpendStreak)
        assertEquals(0, BigDecimal.ZERO.compareTo(e.averageDailySpend))
    }

    @Test
    fun streakAndAverage() {
        val msgs = listOf(tx("100", d(1, 1)), tx("100", d(1, 2)), tx("100", d(1, 10)))
        val r = YearInReview.build(msgs, 2025, zone)
        assertEquals(10, r.daysCovered)
        assertEquals(7, r.longestNoSpendStreak)
        assertEquals(0, BigDecimal("30.00").compareTo(r.averageDailySpend))
    }

    @Test
    fun monthsWithDataCountsDistinctMonths() {
        val msgs = listOf(tx("1", d(1, 1)), tx("1", d(1, 2)), tx("1", d(4, 2)))
        assertEquals(2, YearInReview.monthsWithData(msgs, 2025, zone))
        assertEquals(0, YearInReview.monthsWithData(msgs, 2024, zone))
    }
}
