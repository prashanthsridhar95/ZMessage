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
}
