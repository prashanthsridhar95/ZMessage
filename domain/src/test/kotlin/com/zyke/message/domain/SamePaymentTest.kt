package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.dashboard.DashboardBuilder
import com.zyke.message.domain.dashboard.DashboardQuery
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.insights.AccountBalance
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.ledger.CardBillDecisions
import com.zyke.message.domain.ledger.CardBillPayments
import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.CountedReason
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.MemorySamePayments
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.ledger.SamePaymentDecisions
import com.zyke.message.domain.ledger.SamePaymentKind
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.review.YearInReview
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** One payment, several texts: the bank's debit is the payment; a gateway's or merchant's text about it is not a second one. */
class SamePaymentTest {
    private val zone = ZoneId.of("UTC")
    private val classifier = MessageClassifier()
    private val now = Instant.parse("2026-10-28T20:00:00Z")
    private var nextId = 1L

    private fun at(day: Int, hour: Int, minute: Int, month: Int = 10) =
        LocalDate.of(2026, month, day).atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    private fun sms(sender: String, body: String, day: Int, hour: Int, minute: Int, month: Int = 10): ClassifiedMessage =
        classifier.classify(SmsMessage(nextId++, sender, body, at(day, hour, minute, month), true))

    private fun bank(amount: String, ref: String, hour: Int, minute: Int, day: Int = 28, month: Int = 10, payee: String = "netflix@icici") =
        sms("AD-HDFCBK", "Rs $amount debited from A/c XX5765 on $day-$month-26 to VPA $payee (UPI Ref $ref). Not you? Call 18002586161", day, hour, minute, month)

    private fun razorpay(amount: String, hour: Int, minute: Int, ref: String? = null, day: Int = 28, month: Int = 10) =
        sms("VM-RZRPAY", "Payment of Rs.$amount towards Netflix successfully made on $day October 2026." + (ref?.let { " Ref $it" } ?: "") + " - Razorpay", day, hour, minute, month)

    private fun merchant(amount: String, hour: Int, minute: Int, ref: String? = null, day: Int = 28, month: Int = 10) =
        sms("VM-NETFLX", "Dear customer, your payment of Rs.$amount to Netflix has been received." + (ref?.let { " Ref $it." } ?: "") + " Thank you", day, hour, minute, month)

    private fun phonepe(amount: String, hour: Int, minute: Int, day: Int = 28, month: Int = 10) =
        sms("VM-PHONPE", "Paid Rs. $amount to Netflix via PhonePe.", day, hour, minute, month)

    private fun link(list: List<ClassifiedMessage>, decisions: SamePaymentDecisions = SamePaymentDecisions()) =
        SamePayment.apply(list.sortedByDescending { it.message.date }, decisions, zone)

    private fun spent(list: List<ClassifiedMessage>): BigDecimal = list.fold(BigDecimal.ZERO) { a, m -> a + (Counting.spend(m) ?: BigDecimal.ZERO) }
    private fun income(list: List<ClassifiedMessage>): BigDecimal = list.fold(BigDecimal.ZERO) { a, m -> a + (Counting.income(m) ?: BigDecimal.ZERO) }
    private fun assertMoney(expected: Long, actual: BigDecimal, what: String = "") =
        assertEquals("$what expected $expected got $actual", 0, BigDecimal(expected).compareTo(actual))

    @Test
    fun bankDebitGatewayAndMerchantWithTheSameReference_areOnePayment() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val gateway = razorpay("987", 10, 1, ref = "6123456789")
        val receipt = merchant("987.00", 10, 2, ref = "6123456789")
        assertTrue("the merchant text reads as money in: ${receipt.content}", (receipt.content as MessageContent.Transaction).isReceived)
        val all = link(listOf(debit, gateway, receipt))
        assertEquals(3, all.size)
        assertMoney(987, spent(all), "spent")
        assertMoney(0, income(all), "the merchant's receipt is not income")
        val byId = all.associateBy { it.id }
        assertNull(byId.getValue(debit.id).samePayment)
        for (id in listOf(gateway.id, receipt.id)) {
            val l = byId.getValue(id).samePayment!!
            assertEquals(SamePaymentKind.CERTAIN, l.kind)
            assertEquals(debit.id, l.primaryId)
            assertEquals("ref", l.layer)
            val counted = Counting.of(byId.getValue(id))
            assertTrue(counted.toString(), counted is Counted.NotCounted)
            assertTrue(CountedReason.explain(byId.getValue(id)), CountedReason.explain(byId.getValue(id)).contains("same payment as the ICICI debit"))
        }
        // The totals read the one rule.
        assertMoney(987, DashboardBuilder.build(all, DashboardQuery(), now, zone).spentTotal, "dashboard")
        assertMoney(0, DashboardBuilder.build(all, DashboardQuery(), now, zone).receivedTotal, "dashboard income")
        assertMoney(987, MonthlySummary.build(all, now, zone).spent, "month")
        assertMoney(987, YearInReview.build(all, 2026, zone, LocalDate.of(2026, 10, 28)).spent, "year")
    }

    @Test
    fun noMessageIsDropped_andNothingElseChanges() {
        val a = bank("987.00", "6123456789", 10, 0)
        val b = razorpay("987", 10, 1, ref = "6123456789")
        val other = bank("120.00", "6000000001", 11, 0, payee = "swiggy@icici")
        val all = link(listOf(a, b, other))
        assertEquals(3, all.size)
        assertSame(other.id, all.single { it.id == other.id }.id)
        // Without anything to link, the very same list comes back.
        val plain = listOf(a, other).sortedByDescending { it.message.date }
        assertSame(plain, SamePayment.apply(plain, SamePaymentDecisions(), zone))
    }

    @Test
    fun noReference_butTheSameMerchantAndMinutes_isAQuestionNotALink() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val app = phonepe("987", 10, 2)
        val all = link(listOf(debit, app))
        val l = all.single { it.id == app.id }.samePayment!!
        assertEquals(SamePaymentKind.SUSPECTED, l.kind)
        assertEquals("name", l.layer)
        // Both still count until the person answers.
        assertMoney(1974, spent(all), "still counted")
        val queue = ReviewQueues.build(all, SetAsides(), zone, now)
        assertEquals(listOf(app.id), queue.samePayments.map { it.id })
        assertTrue(CountedReason.explain(all.single { it.id == app.id }).contains("may be the same payment"))
    }

    @Test
    fun onlyTheAmountAndMinutes_isAQuestion_andFarApartIsNothing() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val gateway = sms("VM-RZRPAY", "Payment of Rs.987 towards your order successfully made on 28 October 2026. - Razorpay", 28, 10, 1)
        val l = link(listOf(debit, gateway)).single { it.id == gateway.id }.samePayment!!
        assertEquals(SamePaymentKind.SUSPECTED, l.kind)
        assertEquals("time", l.layer)
        val late = sms("VM-RZRPAY", "Payment of Rs.987 towards your order successfully made on 28 October 2026. - Razorpay", 28, 10, 9)
        assertNull(link(listOf(debit, late)).single { it.id == late.id }.samePayment)
        val bigger = razorpay("988", 10, 1)
        assertNull(link(listOf(debit, bigger)).single { it.id == bigger.id }.samePayment)
    }

    @Test
    fun twoGenuineEqualPaymentsWithTwoBankDebits_stayTwo() {
        val d1 = bank("987.00", "6111111111", 10, 0)
        val d2 = bank("987.00", "6222222222", 10, 4)
        val g1 = razorpay("987", 10, 1, ref = "6111111111")
        val g2 = razorpay("987", 10, 5, ref = "6222222222")
        val all = link(listOf(d1, d2, g1, g2))
        assertMoney(1974, spent(all), "two payments")
        val byId = all.associateBy { it.id }
        assertEquals(d1.id, byId.getValue(g1.id).samePayment!!.primaryId)
        assertEquals(d2.id, byId.getValue(g2.id).samePayment!!.primaryId)
    }

    @Test
    fun aSharedReferenceBeatsNearnessInTime() {
        // The second gateway text is nearer the first debit, but its reference is the second debit's.
        val d1 = bank("987.00", "6111111111", 10, 0)
        val d2 = bank("987.00", "6222222222", 10, 6)
        val g = razorpay("987", 10, 1, ref = "6222222222")
        val all = link(listOf(d1, d2, g))
        assertEquals(d2.id, all.single { it.id == g.id }.samePayment!!.primaryId)
        assertMoney(1974, spent(all))
    }

    @Test
    fun oneDebitTakesOnlyOneTextFromASender() {
        val d = bank("987.00", "6111111111", 10, 0)
        val g1 = razorpay("987", 10, 1, ref = "6111111111")
        val g2 = razorpay("987", 10, 2, ref = "6111111111")
        val all = link(listOf(d, g1, g2))
        // Two Razorpay texts for one debit: only one is taken; the other may be a payment of its own.
        assertEquals(1, all.count { it.samePayment != null })
        assertMoney(1974, spent(all))
    }

    @Test
    fun twoEqualDebitsAndOneUnreferencedText_isOneQuestion() {
        val d1 = bank("987.00", "6111111111", 10, 0)
        val d2 = bank("987.00", "6222222222", 10, 2)
        val g = razorpay("987", 10, 1)
        val all = link(listOf(d1, d2, g))
        assertEquals(1, all.count { it.samePayment != null })
        assertEquals(SamePaymentKind.SUSPECTED, all.single { it.samePayment != null }.samePayment!!.kind)
        assertMoney(2961, spent(all), "nothing silently merged")
    }

    @Test
    fun fiveIdenticalBankAlerts_stayFive() {
        // Five payments of the same amount: five alerts, each with its own reference number (one reference told twice is one payment).
        val five = (0 until 5).map { bank("500.00", "600000001$it", 10, it) }
        val all = link(five)
        assertEquals(5, all.size)
        assertTrue(all.all { it.samePayment == null })
        assertMoney(2500, spent(all))
        // Five alike texts from one app, with no bank debit at all, are not paired with each other either.
        val apps = (0 until 5).map { phonepe("500", 11, it) }
        assertTrue(link(apps).all { it.samePayment == null })
    }

    @Test
    fun twoTextsAndNoBankDebit_areAQuestion_andTheDebitDirectionStaysCounted() {
        val g = razorpay("987", 10, 0, ref = "6123456789")
        val r = merchant("987.00", 10, 1, ref = "6123456789")
        val all = link(listOf(g, r))
        assertNull(all.single { it.id == g.id }.samePayment)
        val l = all.single { it.id == r.id }.samePayment!!
        assertEquals(SamePaymentKind.SUSPECTED, l.kind)
        assertEquals(g.id, l.primaryId)
        assertMoney(987, spent(all))
        assertMoney(987, income(all), "until answered")
        val yes = MemorySamePayments()
        yes.answer(r.id, g.id, null, same = true, always = false)
        val answered = link(listOf(g, r), yes.state.value)
        assertMoney(987, spent(answered))
        assertMoney(0, income(answered))
    }

    @Test
    fun yourNo_beatsDetection_everywhere() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val gateway = razorpay("987", 10, 1, ref = "6123456789")
        val repo = MemorySamePayments()
        repo.answer(gateway.id, null, null, same = false, always = false)
        val all = link(listOf(debit, gateway), repo.state.value)
        assertTrue(all.all { it.samePayment == null })
        assertMoney(1974, spent(all))
        // Taking the answer back brings the link back.
        repo.forget(gateway.id, null)
        assertEquals(SamePaymentKind.CERTAIN, link(listOf(debit, gateway), repo.state.value).single { it.id == gateway.id }.samePayment!!.kind)
        // A rule for the sender stops it for every text of that sender.
        val key = CategoryKey.of(gateway)!!
        val rule = MemorySamePayments()
        rule.answer(gateway.id, null, key, same = false, always = true)
        val other = razorpay("987", 12, 1, ref = "6123456789")
        val d2 = bank("987.00", "6123456789", 12, 0)
        assertTrue(link(listOf(d2, other), rule.state.value).all { it.samePayment == null })
    }

    @Test
    fun yourYes_linksWhatDetectionWasUnsureOf_andCanBeTakenBack() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val app = phonepe("987", 10, 2)
        val repo = MemorySamePayments()
        repo.answer(app.id, debit.id, null, same = true, always = false)
        val all = link(listOf(debit, app), repo.state.value)
        val l = all.single { it.id == app.id }.samePayment!!
        assertEquals(SamePaymentKind.CERTAIN, l.kind)
        assertEquals("you", l.layer)
        assertMoney(987, spent(all))
        repo.forget(app.id, null)
        assertMoney(1974, spent(link(listOf(debit, app), repo.state.value)))
    }

    @Test
    fun aLearnedRule_linksTheNextOneFromTheSameSender() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val app = phonepe("987", 10, 2)
        val repo = MemorySamePayments()
        repo.answer(app.id, debit.id, CategoryKey.of(app), same = true, always = true)
        // Next month: a new debit and a new app text, nothing shared but the sender and the minutes.
        val d2 = bank("499.00", "6999999999", 9, 0, day = 5, month = 11)
        val a2 = phonepe("499", 9, 3, day = 5, month = 11)
        val all = link(listOf(d2, a2), repo.state.value)
        val l = all.single { it.id == a2.id }.samePayment!!
        assertEquals(SamePaymentKind.CERTAIN, l.kind)
        assertEquals("rule", l.layer)
        assertMoney(499, spent(all))
        // The rule never links across a different amount.
        val a3 = phonepe("500", 9, 3, day = 5, month = 11)
        assertNull(link(listOf(d2, a3), repo.state.value).single { it.id == a3.id }.samePayment)
    }

    @Test
    fun aLearnedRule_doesNotPickBetweenTwoDebits() {
        val d1 = bank("987.00", "6111111111", 10, 0)
        val d2 = bank("987.00", "6222222222", 10, 4)
        val app = phonepe("987", 10, 2)
        val repo = MemorySamePayments()
        repo.answer(999L, d1.id, CategoryKey.of(app), same = true, always = true)
        val l = link(listOf(d1, d2, app), repo.state.value).single { it.id == app.id }.samePayment!!
        assertEquals(SamePaymentKind.SUSPECTED, l.kind)
    }

    @Test
    fun aGatewayNoticeAndAnUnreadGatewayText_withTheSameReference_areLinkedToo() {
        val debit = bank("1499.00", "12345678901", 10, 0)
        val payu = sms("VM-PAYU", "Transaction No. 12345678901 for Rs. 1499.00 done for Arun Kumar has succeeded Team PayU", 28, 10, 1)
        val billdesk = sms("VM-BILDSK", "Your payment of Rs.1,499.00 to Netflix via BillDesk is successful. Txn Ref 12345678901", 28, 10, 2)
        assertTrue(payu.content is MessageContent.Notice)
        assertTrue(billdesk.content is MessageContent.Generic)
        val all = link(listOf(debit, payu, billdesk))
        assertEquals(debit.id, all.single { it.id == payu.id }.samePayment!!.primaryId)
        assertEquals(debit.id, all.single { it.id == billdesk.id }.samePayment!!.primaryId)
        assertMoney(1499, spent(all))
        // The unread text is no longer asked about as "not understood".
        assertTrue(ReviewQueues.build(all, SetAsides(), zone, now).unclear.isEmpty())
        // Without a reference nothing is invented for them.
        val noRef = sms("VM-BILDSK", "Your payment of Rs.1,499.00 to Netflix via BillDesk is successful.", 28, 10, 2)
        assertNull(link(listOf(debit, noRef)).single { it.id == noRef.id }.samePayment)
    }

    @Test
    fun aMoneyReceivedText_isNeverTakenForAConfirmation() {
        val debit = bank("500.00", "6123456789", 10, 0)
        val friend = sms("VM-PHONPE", "Received Rs.500 from Rahul. UPI Ref 6123456789", 28, 10, 1)
        val all = link(listOf(debit, friend))
        assertTrue(all.all { it.samePayment == null })
    }

    @Test
    fun monthlySubscription_isNotSplitByItsConfirmations() {
        val months = listOf(7, 8, 9, 10)
        val withRef = months.flatMap { m ->
            val ref = "61000000$m"
            listOf(bank("649.00", ref, 10, 0, day = 5, month = m), razorpay("649", 10, 2, ref = ref, day = 5, month = m), merchant("649.00", 10, 3, ref = ref, day = 5, month = m))
        }
        val all = link(withRef)
        assertEquals(12, all.size)
        val subs = Subscriptions.find(all, now, zone)
        assertEquals(subs.toString(), 1, subs.size)
        assertMoney(649, subs.single().amount)
        assertMoney(649 * 4, spent(all))
    }

    @Test
    fun aBillPayment_isInTheBillsHistoryOnce() {
        val billText = "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.50,000.00 Min due amt: Rs.2,500.00 Due by:08-11-2026 pay https://hdfcbk.io/x"
        val bill = sms("AD-HDFCBK", billText, 20, 8, 0)
        val debit = sms("AD-HDFCBK", "Rs 50000.00 debited from A/c XX5765 on 28-10-26 CC bill HDFC Bank Credit Card XX1234 (UPI Ref 7712345678)", 28, 10, 0)
        val gateway = sms("VM-RZRPAY", "Payment of Rs.50000 towards HDFC Bank Credit Card XX1234 successfully made on 28 October 2026. Ref 7712345678 - Razorpay", 28, 10, 1)
        val raw = listOf(bill, debit, gateway).sortedByDescending { it.message.date }
        val withoutLink = CardBillPayments.apply(raw, CardBillDecisions(), zone)
        val billMessage = withoutLink.single { it.content is MessageContent.CardBill }
        val doubled = BillPayments.payments(billMessage, billMessage.content as MessageContent.CardBill, withoutLink, zone)
        val all = SamePayment.apply(withoutLink, SamePaymentDecisions(), zone)
        val once = BillPayments.payments(billMessage, billMessage.content as MessageContent.CardBill, all, zone)
        assertTrue("the gateway text is its own payment before it is linked: ${doubled.size}", doubled.size >= 2)
        assertEquals(1, once.size)
        assertEquals(debit.id, once.single().messageId)
    }

    @Test
    fun theBankBalance_isUntouched() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val gateway = razorpay("987", 10, 1, ref = "6123456789")
        val receipt = merchant("987.00", 10, 2, ref = "6123456789")
        val reading = AccountBalance("5765", "HDFC", BigDecimal(10_000), LocalDate.of(2026, 9, 30), 0L, at(30, 23, 0, 9))
        val before = BalanceReader.movement(listOf(debit, gateway, receipt).sortedByDescending { it.message.date }, reading)
        val after = BalanceReader.movement(link(listOf(debit, gateway, receipt)), reading)
        assertEquals(before.net, after.net)
        assertMoney(-987, after.net)
    }

    @Test
    fun aDebitYouLeftOutOfTheTotals_takesItsConfirmationsWithIt() {
        val debit = bank("987.00", "6123456789", 10, 0)
        val gateway = razorpay("987", 10, 1, ref = "6123456789")
        val excluded = debit.copy(setAside = com.zyke.message.domain.ledger.SetAside.EXCLUDED)
        val all = link(listOf(excluded, gateway))
        assertMoney(0, spent(all))
    }

    @Test
    fun theLivePipeline_linksFollowsYourAnswer_andKeepsEveryMessage() = runBlocking {
        val inbox = listOf(
            SmsMessage(1, "AD-HDFCBK", "Rs 987.00 debited from A/c XX5765 on 28-10-26 to VPA netflix@icici (UPI Ref 6123456789). Not you? Call 18002586161", at(28, 10, 0), true),
            SmsMessage(2, "VM-RZRPAY", "Payment of Rs.987 towards Netflix successfully made on 28 October 2026. Ref 6123456789 - Razorpay", at(28, 10, 1), true)
        )
        val repository = object : SmsRepository {
            override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(inbox)
        }
        val repo = MemorySamePayments()
        val useCase = ObserveMessagesUseCase(repository, classifier, Dispatchers.Unconfined, samePayments = repo)
        var latest: List<ClassifiedMessage> = emptyList()
        val job = launch(Dispatchers.Unconfined) { useCase().collect { latest = it } }
        try {
            assertEquals(2, latest.size)
            assertMoney(987, spent(latest))
            assertEquals(SamePaymentKind.CERTAIN, latest.single { it.id == 2L }.samePayment?.kind)
            repo.answer(2, null, null, same = false, always = false)
            assertEquals(2, latest.size)
            assertMoney(1974, spent(latest))
            repo.forget(2, null)
            assertMoney(987, spent(latest))
        } finally { job.cancel() }
    }
}
