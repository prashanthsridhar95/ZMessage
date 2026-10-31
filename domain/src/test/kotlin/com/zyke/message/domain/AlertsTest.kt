package com.zyke.message.domain

import com.zyke.message.domain.forecast.OutflowSource
import com.zyke.message.domain.forecast.UpcomingOutflows
import com.zyke.message.domain.insights.HeadsUpKind
import com.zyke.message.domain.insights.HeadsUps
import com.zyke.message.domain.insights.RecurringItem
import com.zyke.message.domain.insights.Subscription
import com.zyke.message.domain.insights.WeeklySummary
import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.loans.LoanKind
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanItemKind
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.repository.RecurringState
import com.zyke.message.domain.forecast.UpcomingOutflow
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertsTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 25)
    private val now = today.atTime(9, 0).atZone(zone).toInstant()
    private var nextId = 1L

    private fun tx(amount: String, on: LocalDate, body: String = "spent $amount", category: MessageCategory = MessageCategory.FOOD, payee: String = "Cafe") =
        ClassifiedMessage(
            message = SmsMessage(nextId++, "AD-HDFCBK", body, on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true),
            category = category,
            merchant = Merchant(payee, MerchantLogo.UPI, category),
            content = MessageContent.Transaction(BankName.HDFC, payee, amount, "1234", false, false)
        )

    @Test
    fun outflow_sumsSources_andDropsTheSamePaymentSeenTwice() {
        val loan = Loan("l", "Car loan", LoanKind.VEHICLE, BigDecimal("500000"), BigDecimal("9"), 60, today.minusMonths(3), BigDecimal("8000"), today.plusDays(2).dayOfMonth)
        val reminders = listOf(
            Reminder("r1", "Rent", BigDecimal("8000"), today.plusDays(3)), // same as the EMI, one day apart
            Reminder("r2", "Gift", BigDecimal("1500"), today.plusDays(1)),
            Reminder("r3", "Far", BigDecimal("999"), today.plusDays(20))
        )
        val plan = MoneyPlan(items = listOf(PlanItem("p", PlanItemKind.INVESTMENT, "SIP", BigDecimal("5000"), today.plusDays(4).dayOfMonth)))
        val sub = Subscription("Netflix", BigDecimal("649"), today.minusDays(28), today.plusDays(5), 4, 1L)
        val recurring = listOf(
            RecurringItem(sub, RecurringState.CONFIRMED),
            RecurringItem(sub.copy(payee = "Unconfirmed", amount = BigDecimal("77")), null)
        )
        val out = UpcomingOutflows.build(today, 7, loans = listOf(loan), reminders = reminders, plan = plan, recurring = recurring)
        assertEquals(BigDecimal("15149"), out.total)
        assertEquals(listOf(OutflowSource.EMI, OutflowSource.PLAN, OutflowSource.REMINDER, OutflowSource.RECURRING), out.biggest(4).map { it.source })
        assertEquals("Car loan", out.biggest(3).first().title)
    }

    @Test
    fun outflow_emptyWhenNothingIsDue() {
        assertTrue(UpcomingOutflows.build(today).isEmpty)
    }

    @Test
    fun weekly_comparesSameWeekdays_andPicksTopAndBiggest() {
        val msgs = listOf(
            tx("1000", today.minusDays(1), category = MessageCategory.FOOD),
            tx("3000", today.minusDays(2), category = MessageCategory.SHOPPING, payee = "Shop"),
            tx("2000", today.minusDays(8)),
            tx("500", today.minusDays(14)) // outside both weeks
        )
        val w = WeeklySummary.build(msgs, now, zone, UpcomingOutflow(today.plusDays(1), today.plusDays(7), emptyList()))
        assertEquals(BigDecimal("4000"), w.spent)
        assertEquals(BigDecimal("2000"), w.spentLastWeek)
        assertEquals(100, w.changePercent)
        assertEquals(MessageCategory.SHOPPING, w.topCategory)
        assertEquals("Shop", w.biggest?.payee)
    }

    @Test
    fun headsUp_flagsUnusualDebit_needsEnoughHistory() {
        val history = (1..10).map { tx("100", today.minusDays(it.toLong() + 1), body = "h$it") }
        val big = tx("600", today, body = "big")
        val nowMs = big.message.date
        assertEquals(listOf(HeadsUpKind.UNUSUAL_SPEND), HeadsUps.evaluate(big, history, null, zone, nowMs).map { it.kind })
        assertTrue(HeadsUps.evaluate(tx("499", today, body = "ok"), history, null, zone, nowMs).isEmpty())
        assertTrue(HeadsUps.evaluate(big, history.take(9), null, zone, nowMs).isEmpty())
    }

    @Test
    fun headsUp_lowBalanceBelowFloor() {
        val low = tx("200", today, body = "Rs.200 debited from A/c XX1234. Avl Bal Rs.1,500.00")
        assertEquals(listOf(HeadsUpKind.LOW_BALANCE), HeadsUps.evaluate(low, emptyList(), BigDecimal("2000"), zone).map { it.kind })
        assertTrue(HeadsUps.evaluate(low, emptyList(), BigDecimal("1000"), zone).isEmpty())
        assertTrue(HeadsUps.evaluate(low, emptyList(), null, zone).isEmpty())
        assertNull(HeadsUps.evaluate(low, emptyList(), null, zone).firstOrNull())
    }
}
