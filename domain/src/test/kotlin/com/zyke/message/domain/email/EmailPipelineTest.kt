package com.zyke.message.domain.email

import com.zyke.message.domain.classification.HdfcSmsNotice
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SamePayment
import com.zyke.message.domain.ledger.SamePaymentDecisions
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.math.BigDecimal
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An email alert is a message like any other: counted, categorised, linked to the SMS of the same payment, and marked as an email. */
class EmailPipelineTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val classifier = MessageClassifier()
    private val at = 1_795_000_000_000L

    private fun alert(
        key: String = "k1", kind: EmailAlertKind = EmailAlertKind.DEBIT, amount: String = "250.00", payee: String = "Chai Point",
        vpa: String = "chai@okhdfcbank", account: String = "1234", ref: String = "612345678901", whenAt: Long = at
    ) = EmailAlert(key, "hdfc", com.zyke.message.domain.model.BankName.HDFC, "HDFC Bank", whenAt, kind, amount, payee, vpa, account, false, ref, "")

    private fun sms(id: Long, body: String, whenAt: Long = at) = SmsMessage(id, "AD-HDFCBK", body, whenAt, true)

    private fun spent(list: List<ClassifiedMessage>) = list.fold(BigDecimal.ZERO) { a, m -> a + (Counting.spend(m) ?: BigDecimal.ZERO) }
    private fun income(list: List<ClassifiedMessage>) = list.fold(BigDecimal.ZERO) { a, m -> a + (Counting.income(m) ?: BigDecimal.ZERO) }

    @Test
    fun anEmailDebitIsAPaymentFromTheEmailSender() {
        val m = EmailAlerts.toMessage(alert(), classifier, zone)
        assertTrue(m.message.isEmail)
        assertTrue(EmailAlerts.isEmailId(m.id))
        assertEquals("HDFC Bank", m.message.sender)
        assertTrue(m.message.isRead)
        val tx = m.content as MessageContent.Transaction
        assertEquals("250", tx.amount)
        assertEquals("1234", tx.accountNumber)
        assertFalse(tx.isReceived)
        assertEquals("Chai Point", tx.payee)
        assertEquals(BigDecimal("250"), spent(listOf(m)))
        // The words are the bank's shape: amount, account, UPI address, date and the reference.
        assertTrue(m.message.body, "Rs.250.00 debited from HDFC Bank A/c XX1234 to VPA chai@okhdfcbank (Chai Point) on" in m.message.body)
        assertTrue("UPI Ref No. 612345678901" in m.message.body)
    }

    @Test
    fun anEmailCreditIsIncome() {
        val m = EmailAlerts.toMessage(alert(kind = EmailAlertKind.CREDIT, payee = "Ravi Kumar", vpa = "ravi@okaxis", amount = "500.00"), classifier, zone)
        assertTrue((m.content as MessageContent.Transaction).isReceived)
        assertEquals(BigDecimal("500"), income(listOf(m)))
    }

    @Test
    fun theSameUpiPaymentBySmsAndEmail_isCountedOnce_andTheSmsStays() {
        val smsBody = "Sent Rs.250.00\nFrom HDFC Bank A/C *1234\nTo Chai Point\nOn 28/11/26\nRef 612345678901\nNot You? Call 18002586161"
        val fromSms = classifier.classify(sms(7, smsBody))
        val fromEmail = EmailAlerts.toMessage(alert(whenAt = at + 40_000), classifier, zone)
        assertTrue(fromSms.content is MessageContent.Transaction)
        val linked = SamePayment.apply(listOf(fromEmail, fromSms).sortedByDescending { it.message.date }, SamePaymentDecisions(), zone)
        assertEquals(BigDecimal("250"), spent(linked))
        val email = linked.single { it.message.isEmail }
        assertNotNull(email.samePayment)
        assertNull(linked.single { !it.message.isEmail }.samePayment)
    }

    @Test
    fun twoDifferentPaymentsOfTheSameAmount_areBothCounted() {
        val a = EmailAlerts.toMessage(alert("a", ref = "612345678901"), classifier, zone)
        val b = EmailAlerts.toMessage(alert("b", ref = "612345678999", whenAt = at + 3600_000), classifier, zone)
        val linked = SamePayment.apply(listOf(b, a), SamePaymentDecisions(), zone)
        assertEquals(BigDecimal("500"), spent(linked))
    }

    @Test
    fun anAlertNotUnderstood_isAPlainMessage_neverAPayment_andShowsInTheNotUnderstoodList() {
        val unclear = alert(kind = EmailAlertKind.UNCLEAR, amount = "", payee = "", vpa = "", account = "", ref = "").copy(snippet = "Your UPI transaction of Rs 999.99 to [name] was done")
        val m = EmailAlerts.toMessage(unclear, classifier, zone)
        assertEquals(MessageContent.Generic, m.content)
        assertEquals(BigDecimal.ZERO, spent(listOf(m)))
        val review = ReviewQueues.build(listOf(m), com.zyke.message.domain.ledger.SetAsides(), zone, java.time.Instant.ofEpochMilli(at + 1000))
        assertEquals(listOf(m.id), review.unclear.map { it.id })
    }

    @Test
    fun theInboxStreamHoldsEmailAlertsBesideSms_andFollowsTheStore() = runBlocking {
        val inbox = listOf(sms(1, "Rs 40 debited from A/c XX1234 on 28-11-26 to VPA shop@icici (UPI Ref 100000000001). Not you? Call 18002586161", at - 3600_000))
        val repository = object : SmsRepository { override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(inbox) }
        val store = InMemoryEmailAlerts()
        val useCase = ObserveMessagesUseCase(repository, classifier, Dispatchers.Unconfined, emailAlerts = store)
        var latest: List<ClassifiedMessage> = emptyList()
        val job = launch(Dispatchers.Unconfined) { useCase().collect { latest = it } }
        try {
            assertEquals(1, latest.size)
            store.update { it.copy(keepMonths = 0) }
            store.add(listOf(alert()), at)
            assertEquals(2, latest.size)
            assertEquals(listOf(true, false), latest.map { it.message.isEmail })
            assertEquals(BigDecimal("290"), spent(latest))
            store.clearAlerts()
            assertEquals(1, latest.size)
        } finally { job.cancel() }
    }

    @Test
    fun theMarksOnAnEmailAlertSurviveTheInboxCheck() {
        val kept = ArrayList<Set<Long>>()
        val inner = object : com.zyke.message.domain.repository.UserMessageStateRepository {
            override val state = kotlinx.coroutines.flow.MutableStateFlow(com.zyke.message.domain.model.UserMessageState())
            override fun markRead(messageId: Long) = Unit
            override fun setArchived(messageId: Long, archived: Boolean) = Unit
            override fun setPinned(messageId: Long, pinned: Boolean) = Unit
            override fun dismissAttention(key: String, forever: Boolean) = Unit
            override fun clearAll() = Unit
            override fun retainOnly(existingIds: Set<Long>) { kept += existingIds }
        }
        val a = alert()
        KeepEmailMarks(inner) { listOf(a) }.retainOnly(setOf(1L, 2L))
        assertEquals(setOf(1L, 2L, EmailAlerts.idFor(a.key)), kept.single())
    }

    @Test
    fun idsAreStable_andNeverCollideWithSmsOrTheNegativeIds() {
        assertEquals(EmailAlerts.idFor("k"), EmailAlerts.idFor("k"))
        assertTrue(EmailAlerts.idFor("k") != EmailAlerts.idFor("k2"))
        for (k in listOf("a", "b", "c", "m:ffff")) assertTrue(EmailAlerts.isEmailId(EmailAlerts.idFor(k)))
        assertFalse(EmailAlerts.isEmailId(12345L))
        assertFalse(EmailAlerts.isEmailId(-1L))
        assertFalse(EmailAlerts.isEmailId(-(1L shl 40) - 5))
        assertEquals(EmailAlerts.keyOf("gmail", 1, 5, "<A@x>"), EmailAlerts.keyOf("gmail", 2, 9, "a@x".let { "<$it>" }.replace("a@x", "A@x")))
        assertTrue(EmailAlerts.keyOf("gmail", 1, 5, "").startsWith("u:"))
    }

    @Test
    fun theHdfcNoticeIsSpotted_butOtherTextsAreNot() {
        assertTrue(HdfcSmsNotice.matches("From 1 Jan 2027, HDFC Bank will stop UPI SMS alerts up to Rs.500. All UPI email alerts continue."))
        assertTrue(HdfcSmsNotice.matches("Dear Customer, HDFC Bank will stop UPI SMS alerts for\ntransactions up to Rs 500 from 01-Jan-27."))
        assertTrue(HdfcSmsNotice.matches("HDFC Bank: UPI SMS alerts up to Rs.500 end 1 Jan 2027. Email alerts continue."))
        assertFalse(HdfcSmsNotice.matches("From 1 Jan 2027, Some Bank will stop UPI SMS alerts up to Rs.500."))
        assertFalse(HdfcSmsNotice.matches("Rs 250 debited from HDFC Bank A/c XX1234 to VPA chai@okhdfcbank. UPI Ref 612345678901."))
        assertFalse(HdfcSmsNotice.matches("HDFC Bank: your SMS alerts are on."))
    }
}
