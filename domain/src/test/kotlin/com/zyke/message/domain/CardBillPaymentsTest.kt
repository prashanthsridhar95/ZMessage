package com.zyke.message.domain

import com.zyke.message.domain.budget.DailyBudgets
import com.zyke.message.domain.budget.DayBreakdowns
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.feed.SpendSummaryBuilder
import com.zyke.message.domain.forecast.SpendForecasts
import com.zyke.message.domain.insights.AccountBalance
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.insights.WeeklySummary
import com.zyke.message.domain.forecast.UpcomingOutflow
import com.zyke.message.domain.ledger.CardBillDecisions
import com.zyke.message.domain.ledger.CardBillKind
import com.zyke.message.domain.ledger.CardBillPayments
import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.MemoryCardBills
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.PlanInsightsBuilder
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.review.YearInReview
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The card swipe is the spending; paying the card's bill afterwards moves money between the person's own accounts. */
class CardBillPaymentsTest {
    private val zone = ZoneId.of("UTC")
    private val classifier = MessageClassifier()
    private val now = Instant.parse("2026-10-28T20:00:00Z")
    private var nextId = 1L

    private fun at(day: Int, hour: Int = 10, month: Int = 10) =
        LocalDate.of(2026, month, day).atTime(hour, 0).atZone(zone).toInstant().toEpochMilli()

    private fun sms(body: String, day: Int, sender: String = "AD-HDFCBK", hour: Int = 10, month: Int = 10): ClassifiedMessage =
        classifier.classify(SmsMessage(nextId++, sender, body, at(day, hour, month), true))

    private fun swipe(amount: String, merchant: String, day: Int, last4: String = "1234", month: Int = 10) =
        sms("Rs.$amount spent on HDFC Bank Credit Card XX$last4 at $merchant on $day-$month-26. Avl limit Rs 90,000", day, month = month)

    private fun salary(day: Int = 1) = sms("Rs.80000.00 credited to HDFC Bank A/c XX5765 on 01-10-26 by NEFT ACME CORP SALARY", day)

    private val bill = "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.50,000.00 Min due amt: Rs.2,500.00 Due by:08-11-2026 pay https://hdfcbk.io/x"
    private fun billMessage(day: Int = 28, hour: Int = 8) = sms(bill, day, hour = hour)

    private fun swipes(): List<ClassifiedMessage> = listOf(
        swipe("12000.00", "AMAZON", 24), swipe("8000.00", "SWIGGY", 25), swipe("15000.00", "CROMA", 26), swipe("15000.00", "MAKEMYTRIP", 27)
    )

    /** Every way the same ₹50,000 can leave the bank account to pay the card; the second element says whether a bill message is needed. */
    private val channels = listOf(
        "creditcard payment narration" to "Rs 50000.00 debited from Axis A/c XX5765 on 28-10-26. CreditCard Payment XX 1234",
        "upi to cred" to "Rs 50000.00 debited from A/c XX5765 on 28-10-26 and credited to cred.club@axisb (UPI Ref 6123456789).",
        "neft to card" to "Rs 50000.00 debited from A/c XX5765 on 28-10-26 by NEFT to HDFC CREDIT CARD (Ref 123456)",
        "ach debit" to "Rs 50000.00 debited from A/c XX5765 on 28-10-26 ACH D- ICICI BANK CREDIT CARD Ref 12345",
        "towards card" to "Rs 50000.00 debited from A/c XX5765 on 28-10-26 towards HDFC Bank Credit Card XX1234 payment",
        "cc bill only" to "Rs 50000.00 debited from A/c XX5765 on 28-10-26 CC bill (Ref 77)",
        "billdesk" to "Rs.50000.00 debited from A/c XX5765 on 28-10-26 to VPA billdesk@hdfcbank (UPI Ref 123)"
    )

    private fun month(payment: String, withBill: Boolean = true, extra: List<ClassifiedMessage> = emptyList(), decisions: CardBillDecisions = CardBillDecisions()): List<ClassifiedMessage> {
        val raw = (listOf(salary()) + swipes() + (if (withBill) listOf(billMessage()) else emptyList()) +
            sms(payment, 28, hour = 14) + extra).sortedByDescending { it.message.date }
        return CardBillPayments.apply(raw, decisions, zone)
    }

    private fun assertMoney(expected: Long, actual: BigDecimal, what: String = "") =
        assertEquals("$what expected $expected got $actual", 0, BigDecimal(expected).compareTo(actual))

    /** What every total reads for the fixture month: they must all agree, since they ask the same rule. */
    private fun assertConsumersAgree(messages: List<ClassifiedMessage>, spent: Long, income: Long = 80_000, refunded: Long = 0, bankSpent: Boolean = false) {
        val today = LocalDate.of(2026, 10, 28)
        assertMoney(spent, DashboardBuilder.build(messages, DashboardQuery(), now, zone).spentTotal, "dashboard")
        assertMoney(income, DashboardBuilder.build(messages, DashboardQuery(), now, zone).receivedTotal, "dashboard income")
        val summary = MonthlySummary.build(messages, now, zone)
        assertMoney(spent, summary.spent, "month summary")
        assertMoney(income, summary.income, "month income")
        val year = YearInReview.build(messages, 2026, zone, today)
        assertMoney(spent, year.spent, "year in review")
        assertMoney(income, year.income, "year income")
        val plan = PlanInsightsBuilder.build(MoneyPlan(salary = BigDecimal(80_000)), messages, now, zone)!!
        assertMoney(spent, plan.spentOnTheRest, "plan spent")
        assertMoney(income, plan.savedSoFar + plan.spentOnTheRest, "plan income")
        assertMoney(spent, WeeklySummary.build(messages, now, zone, UpcomingOutflow(LocalDate.of(2026, 10, 29), LocalDate.of(2026, 11, 4), emptyList())).spent, "weekly")
        assertMoney(spent, SpendSummaryBuilder.build(messages, now, zone)!!.total, "spend summary")
        assertMoney(spent, DailyBudgets.spendByDay(messages, zone).values.fold(BigDecimal.ZERO) { a, b -> a + b }, "daily budget")
        val categories = messages.filter { Counting.spend(it) != null }.map { it.category }.toSet()
        val limits = SpendingLimits.build(messages, categories.associateWith { BigDecimal(1_000_000) }, emptyMap(), now, zone)
        assertMoney(spent, limits.fold(BigDecimal.ZERO) { sum, l -> sum + (l.monthly?.spent ?: BigDecimal.ZERO) }, "limits")
        val days = (1..31).sumOf { d -> DayBreakdowns.build(messages, LocalDate.of(2026, 10, d), zone).spent.sumOf { it.amount } }
        // A card refund is listed among the day's credits, not subtracted from the day's spending list.
        assertMoney(spent + refunded, days, "day breakdown")
        val forecast = SpendForecasts.build(messages, null, now, zone, CycleRule.CALENDAR)
        if (spent == 0L || bankSpent) return
        assertNotNull(forecast)
        val bankPerDay = forecast!!.bankPerDay
        assertTrue("the bank payment is not everyday spending: $bankPerDay", bankPerDay.signum() == 0)
        assertTrue(forecast.cardPerDay.signum() > 0)
    }

    @Test
    fun withoutAnyPayment_theSwipesAreTheSpending() {
        val messages = listOf(salary()) + swipes()
        assertConsumersAgree(messages, spent = 50_000)
    }

    @Test
    fun eachWayOfPayingTheBill_isCountedOnce() {
        channels.forEach { (name, body) ->
            val messages = month(body)
            val paid = messages.single { it.message.date == at(28, 14) }
            assertEquals("$name: ${paid.content}", true, (paid.content as MessageContent.Transaction).isCardPayment)
            assertConsumersAgree(messages, spent = 50_000)
        }
    }

    @Test
    fun wordsAloneSettleIt_withoutAnyBillMessage() {
        channels.filter { it.first in setOf("creditcard payment narration", "neft to card", "ach debit", "towards card", "cc bill only") }.forEach { (name, body) ->
            val messages = month(body, withBill = false)
            val paid = messages.single { it.message.date == at(28, 14) }
            assertEquals(name, CardBillKind.CERTAIN, paid.cardBill?.kind)
            assertConsumersAgree(messages, spent = 50_000)
        }
    }

    @Test
    fun theBankBalanceStillFallsByThePayment() {
        channels.forEach { (name, body) ->
            val messages = month(body)
            val reading = AccountBalance("5765", "HDFC", BigDecimal(100_000), LocalDate.of(2026, 9, 30), 0L, at(30, 23, month = 9))
            // The salary arrives, the card is paid: 100000 + 80000 - 50000.
            val movement = BalanceReader.movement(messages, reading)
            assertMoney(30_000, movement.net, name)
        }
    }

    @Test
    fun theCardsPaymentReceivedCredit_isNotIncome() {
        val received = sms("Payment of Rs.50,000.00 has been received on your HDFC Bank Credit Card XX1234 on 28-10-26. Thank you for the payment.", 28, hour = 15)
        val messages = month("Rs 50000.00 debited from A/c XX5765 on 28-10-26 CC bill (Ref 77)", extra = listOf(received))
        assertConsumersAgree(messages, spent = 50_000)
        assertTrue(Counting.of(received) is Counted.NotCounted)
    }

    @Test
    fun aCardCredit_thatIsNotABillPayment_isARefund_notIncome() {
        val refund = sms("Rs.500.00 credited to HDFC Bank Credit Card XX1234 on 28-10-26 for refund from AMAZON", 28, hour = 12)
        assertTrue(Counting.of(refund).toString(), Counting.of(refund) is Counted.Refund)
        val messages = month("Rs 50000.00 debited from A/c XX5765 on 28-10-26 CC bill (Ref 77)", extra = listOf(refund))
        assertConsumersAgree(messages, spent = 49_500, refunded = 500)
    }

    @Test
    fun aPartPayment_isFoundAndCountedOnce() {
        // 20,000 of the 50,000 bill, through CRED: more than the minimum due, and the card's pairing credit confirms it.
        val credit = sms("Payment of Rs.20,000.00 has been received on your HDFC Bank Credit Card XX1234 on 28-10-26. Thank you for the payment.", 28, hour = 15)
        val messages = month("Rs 20000.00 debited from A/c XX5765 on 28-10-26 and credited to cred.club@axisb (UPI Ref 6123456789).", extra = listOf(credit))
        val paid = messages.single { it.message.date == at(28, 14) }
        assertEquals(CardBillKind.CERTAIN, paid.cardBill?.kind)
        assertConsumersAgree(messages, spent = 50_000)
        // A part payment by service with the amount between the minimum and the total, naming the card, needs no credit.
        val partOnly = month("Rs 20000.00 debited from A/c XX5765 on 28-10-26 to cred.club@axisb towards HDFC Bank Credit Card XX1234", withBill = true)
        assertEquals(CardBillKind.CERTAIN, partOnly.single { it.message.date == at(28, 14) }.cardBill?.kind)
        assertConsumersAgree(partOnly, spent = 50_000)
    }

    @Test
    fun anOverpayment_isStillOnePayment() {
        val messages = month("Rs 60000.00 debited from A/c XX5765 on 28-10-26 CC bill (Ref 77)")
        assertConsumersAgree(messages, spent = 50_000)
    }

    @Test
    fun twoCardsSameAmount_pairingPicksTheRightCardByItsDigits() {
        val other = swipe("50000.00", "FLIGHTS", 26, last4 = "9999")
        val creditA = sms("Payment of Rs.50,000.00 has been received on your HDFC Bank Credit Card XX1234 on 28-10-26. Thank you for the payment.", 28, hour = 15)
        val creditB = sms("Payment of Rs.50,000.00 has been received on your HDFC Bank Credit Card XX9999 on 28-10-26. Thank you for the payment.", 28, hour = 15)
        val debit = sms("Rs 50000.00 debited from A/c XX5765 on 28-10-26 and credited to cred.club@axisb (UPI Ref 6123456789). Card XX9999", 28, hour = 14)
        val found = CardBillPayments.detect(listOf(salary(), other, creditA, creditB, debit), CardBillDecisions(), zone)
        assertEquals("9999", found[debit.id]?.cardLast4)
        assertEquals(CardBillKind.CERTAIN, found[debit.id]?.kind)
    }

    @Test
    fun aPaymentToCredForSomethingElse_staysSpending_butIsAsked() {
        val rent = sms("Rs 30000.00 debited from A/c XX5765 on 28-10-26 to RENT via CRED RentPay (UPI Ref 6123456789)", 28, hour = 14)
        val messages = CardBillPayments.apply(listOf(salary(), billMessage(), rent) + swipes(), CardBillDecisions(), zone)
        val asked = messages.single { it.id == rent.id }
        assertEquals(CardBillKind.SUSPECTED, asked.cardBill?.kind)
        assertEquals(BigDecimal(30_000), Counting.spend(asked))
        assertConsumersAgree(messages, spent = 80_000, income = 80_000, bankSpent = true)
        val queue = ReviewQueues.build(messages, SetAsides(), zone, now)
        assertEquals(listOf(rent.id), queue.cardBills.map { it.id })
        assertTrue(queue.count >= 1)
    }

    @Test
    fun aBillDeskPayment_withNoBill_isOnlyAQuestion() {
        val messages = month(channels.single { it.first == "billdesk" }.second, withBill = false)
        val paid = messages.single { it.message.date == at(28, 14) }
        assertEquals(CardBillKind.SUSPECTED, paid.cardBill?.kind)
        assertConsumersAgree(messages, spent = 100_000, bankSpent = true)
    }

    @Test
    fun theUsersNo_beatsDetection_evenForWhatTheClassifierFlagged() {
        val body = "Rs 50000.00 debited from A/c XX5765 on 28-10-26 CC bill (Ref 77)"
        val raw = (listOf(salary()) + swipes() + sms(body, 28, hour = 14)).sortedByDescending { it.message.date }
        val id = raw.first { it.message.date == at(28, 14) }.id
        val no = CardBillDecisions(no = setOf(id))
        val messages = CardBillPayments.apply(raw, no, zone)
        assertEquals(100_000L, Counting.netSpent(messages).toLong())
        assertNull(messages.first { it.id == id }.cardBill)
        // The classifier's own reading of a payment is undone too.
        val internal = classifier.classify(SmsMessage(900, "AD-HDFCBK", "Rs.9000.00 debited from A/c XX5765 to Credit card payment XX 1234", at(28), true))
        val kept = CardBillPayments.apply(listOf(internal), CardBillDecisions(no = setOf(900)), zone).single()
        assertEquals(false, (kept.content as MessageContent.Transaction).isCardPayment)
    }

    @Test
    fun theUsersYes_countsOnce_andLearnsThePayeeForNextMonth() {
        val first = sms("Rs 50000.00 debited from A/c XX5765 on 28-10-26 to VPA billdesk@hdfcbank (UPI Ref 123)", 28, hour = 14)
        val repo = MemoryCardBills()
        val key = com.zyke.message.domain.categories.CategoryKey.of(first)
        repo.answer(first.id, key, isBillPayment = true, always = true)
        val applied = CardBillPayments.apply(listOf(first), repo.state.value, zone).single()
        assertEquals(CardBillKind.CERTAIN, applied.cardBill?.kind)
        assertTrue(Counting.of(applied) is Counted.NotCounted)
        // Next month, a payment to the same payee is certain without being asked.
        val next = sms("Rs 31000.00 debited from A/c XX5765 on 28-11-26 to VPA billdesk@hdfcbank (UPI Ref 456)", 28, month = 11)
        assertEquals(CardBillKind.CERTAIN, CardBillPayments.apply(listOf(next), repo.state.value, zone).single().cardBill?.kind)
        // Answering "no" for the payee is remembered too.
        repo.answer(first.id, key, isBillPayment = false, always = true)
        assertNull(CardBillPayments.apply(listOf(next), repo.state.value, zone).single().cardBill)
        repo.forget(first.id, key)
        assertEquals(CardBillKind.SUSPECTED, CardBillPayments.apply(listOf(next), repo.state.value, zone).single().cardBill?.kind)
    }

    @Test
    fun theSwipeIsCountedOnTheSwipeDate_notThePaymentDate() {
        // Swiped in October, paid in November: October carries the spending, November does not repeat it.
        val octSwipe = swipe("50000.00", "FLIGHTS", 20)
        val bill = sms("HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.50,000.00 Min due amt: Rs.2,500.00 Due by:08-11-2026 pay https://hdfcbk.io/x", 28)
        val pay = sms("Rs 50000.00 debited from A/c XX5765 on 05-11-26 CC bill (Ref 77)", 5, month = 11)
        val messages = CardBillPayments.apply(listOf(pay, bill, octSwipe), CardBillDecisions(), zone)
        val november = Instant.parse("2026-11-10T10:00:00Z")
        assertMoney(0, MonthlySummary.build(messages, november, zone).spent)
        assertMoney(50_000, MonthlySummary.build(messages, now, zone).spent)
        assertMoney(50_000, YearInReview.build(messages, 2026, zone).spent)
    }

    @Test
    fun nothingToApply_keepsEveryInstance() {
        val messages = listOf(salary()) + swipes()
        assertSame(messages, CardBillPayments.apply(messages, CardBillDecisions(), zone))
    }

    @Test
    fun theLivePipeline_appliesItAndFollowsTheAnswer() = runBlocking {
        val pay = SmsMessage(50, "AD-HDFCBK", "Rs 50000.00 debited from A/c XX5765 on 28-10-26 to VPA billdesk@hdfcbank (UPI Ref 123)", at(28, 14), true)
        val repository = object : SmsRepository {
            override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(listOf(pay))
        }
        val repo = MemoryCardBills()
        val useCase = ObserveMessagesUseCase(repository, classifier, Dispatchers.Unconfined, cardBills = repo)
        var latest: List<ClassifiedMessage> = emptyList()
        val job = launch(Dispatchers.Unconfined) { useCase().collect { latest = it } }
        try {
            assertEquals(CardBillKind.SUSPECTED, latest.single().cardBill?.kind)
            repo.answer(50, null, isBillPayment = true, always = false)
            assertEquals(CardBillKind.CERTAIN, latest.single().cardBill?.kind)
            assertTrue(Counting.of(latest.single()) is Counted.NotCounted)
            repo.answer(50, null, isBillPayment = false, always = false)
            assertNull(latest.single().cardBill)
            assertEquals(50_000L, Counting.spend(latest.single())?.toLong())
        } finally { job.cancel() }
    }
}
