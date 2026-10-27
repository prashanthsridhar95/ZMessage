package com.zyke.message.domain

import com.zyke.message.domain.budget.DailyBudgets
import com.zyke.message.domain.budget.DayStatus
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanItemKind
import com.zyke.message.domain.plan.QuickAdd
import com.zyke.message.domain.plan.QuickAdds
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyBudgetAndQuickAddTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 10, 20)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun spend(amount: String, to: String, on: LocalDate, account: String = "5765"): ClassifiedMessage = classifier.classify(
        SmsMessage(
            nextId++, "AD-HDFCBK",
            "Sent Rs.$amount\nFrom HDFC Bank A/C *$account\nTo $to\nOn ${on.format(DateTimeFormatter.ofPattern("dd/MM/yy"))}\nRef ${nextId * 11}",
            on.atTime(10, 0).atZone(zone).toInstant().toEpochMilli(), true
        )
    )

    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun streak_countsDaysWithinTheBudget_andBreaksOnAnOverDay() {
        val messages = listOf(
            spend("100.00", "CAFE ONE", today.minusDays(4)),
            spend("900.00", "CAFE ONE", today.minusDays(3)), // over a 500 budget
            spend("100.00", "CAFE ONE", today.minusDays(1)),
            spend("200.00", "CAFE ONE", today)
        )
        val status = DailyBudgets.status(messages, bd("500"), now, zone)
        // Days since the over day: the 2 days before today (one with nothing spent) and today.
        assertEquals(3, status.currentStreak)
        assertEquals(3, status.bestStreak)
        assertEquals(0, bd("300").compareTo(status.leftToday))
    }

    @Test
    fun calendar_marksUnderOverAndFutureDays() {
        val messages = listOf(spend("100.00", "CAFE ONE", today.minusDays(2)), spend("900.00", "CAFE ONE", today.minusDays(1)))
        val days = DailyBudgets.days(messages, bd("500"), com.zyke.message.domain.dashboard.DateRange(YearMonth.from(today).atDay(1), YearMonth.from(today).atEndOfMonth()), now, zone)
        assertEquals(DayStatus.UNDER, days.first { it.date == today.minusDays(2) }.status)
        assertEquals(DayStatus.OVER, days.first { it.date == today.minusDays(1) }.status)
        assertEquals(DayStatus.NONE, days.first { it.date == today.plusDays(1) }.status)
        assertEquals(DayStatus.NONE, days.first { it.date == today.minusDays(10) }.status)
    }

    @Test
    fun investmentsAndCharity_doNotCountAgainstTheDay() {
        val messages = listOf(spend("5000.00", "ZERODHA BROKING", today), spend("300.00", "CAFE ONE", today))
        val status = DailyBudgets.status(messages, bd("500"), now, zone)
        assertEquals(0, bd("300").compareTo(status.spentToday))
    }

    @Test
    fun quickAdds_findARegularOutflowAndAnAccountNotYetEntered() {
        val messages = listOf(
            spend("5000.00", "AKSHAYA PATRA FOUNDATION", today.minusDays(2)),
            spend("5000.00", "AKSHAYA PATRA FOUNDATION", today.minusDays(2).minusMonths(1)),
            spend("5000.00", "AKSHAYA PATRA FOUNDATION", today.minusDays(2).minusMonths(2))
        )
        val found = QuickAdds.find(messages, MoneyPlan(), emptyList(), emptySet(), now, zone)

        val item = found.filterIsInstance<QuickAdd.Item>().single().item
        assertEquals(PlanItemKind.OUTFLOW, item.kind)
        assertEquals(0, bd("5000").compareTo(item.amount))
        val account = found.filterIsInstance<QuickAdd.Account>().single().card
        assertEquals("5765", account.last4)
        assertEquals(CardType.ACCOUNT, account.type)
    }

    @Test
    fun quickAdds_skipWhatIsAlreadyEnteredOrDismissed() {
        val messages = listOf(
            spend("5000.00", "AKSHAYA PATRA FOUNDATION", today.minusDays(2)),
            spend("5000.00", "AKSHAYA PATRA FOUNDATION", today.minusDays(2).minusMonths(1)),
            spend("5000.00", "AKSHAYA PATRA FOUNDATION", today.minusDays(2).minusMonths(2))
        )
        val found = QuickAdds.find(messages, MoneyPlan(), emptyList(), emptySet(), now, zone)
        val keys = found.map { it.key }.toSet()
        val none = QuickAdds.find(messages, MoneyPlan(), emptyList(), keys, now, zone)
        assertTrue(none.toString(), none.isEmpty())
    }

    @Test
    fun quickAdds_suggestALimitFromSpendingAndASavingsGoalFromWhatWasKept() {
        val messages = (1..3).flatMap { back ->
            val day = today.minusMonths(back.toLong()).withDayOfMonth(10)
            listOf(spend("2000.00", "SWIGGY", day))
        } + (1..3).map { back ->
            classifier.classify(
                SmsMessage(
                    nextId++, "AD-HDFCBK",
                    "Credit Alert! Rs.20000.00 credited to HDFC Bank A/c XX5765 on 05-0$back-26 from VPA boss@okhdfc (UPI 1234$back)",
                    today.minusMonths(back.toLong()).withDayOfMonth(5).atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), true
                )
            )
        }
        val found = QuickAdds.find(messages, MoneyPlan(), emptyList(), emptySet(), now, zone)

        val limit = found.filterIsInstance<QuickAdd.Limit>().single()
        assertEquals(0, bd("2000").compareTo(limit.monthly))
        val goal = found.filterIsInstance<QuickAdd.SavingsGoal>().single()
        assertTrue(goal.toString(), goal.amount.signum() > 0)

        val withLimit = QuickAdds.find(messages, MoneyPlan(), emptyList(), emptySet(), now, zone, mapOf(limit.category to bd("3000")))
        assertTrue(withLimit.none { it is QuickAdd.Limit })
    }
}
