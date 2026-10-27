package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The shapes from the 27 Oct parsing report, rebuilt with made-up numbers. */
class ParsingReportRound4Test {
    private val classifier = MessageClassifier()

    private fun classify(body: String, sender: String = "AD-AXISBK") = classifier.classify(SmsMessage(1, sender, body, 0L, true))

    private fun transaction(body: String) = classify(body).content as? MessageContent.Transaction ?: error("not a transaction: ${classify(body).content}")

    @Test
    fun txnLimitWelcome_isALimitNotice() {
        val c = classify("Welcome to the Axis Mobile App! Current Txn. Limit: INR 25,000. Will be upgraded to INR 5 lakhs after 24 hrs. Limit can be enhanced up to INR 50 lakhs.").content
        assertEquals(NoticeKind.LIMIT_CHANGE, (c as MessageContent.Notice).kind)
    }

    @Test
    fun emiDue_isAnUpcomingDebit() {
        val c = classify("Dear user, EMI of Rs. 12345.67 towards LN1234 is due on 05/11/2026. Pls ensure sufficient balance in your bank account to avoid bounce and penal charges. Please ignore if sufficient balance is available in the account. BANK OF BARODA", "AD-BOBSMS").content as MessageContent.ScheduledPayment
        assertEquals(ScheduledKind.UPCOMING_DEBIT, c.kind)
        assertEquals("12345.67", c.amount)
        assertEquals(LocalDate.of(2026, 11, 5), c.date)
    }

    @Test
    fun nachDebit_isMoneySent() {
        val t = transaction("NACH debit towards ACME FINANCE for INR 1,234.56 with UMRN CITI0000000000000001 has been successfully processed in A/c no. XX1234 today - Axis Bank")
        assertFalse(t.isReceived)
        assertEquals("1234.56", t.amount)
        assertEquals("ACME FINANCE", t.payee)
        assertEquals("1234", t.accountNumber)
    }

    @Test
    fun achDebitStatement_isMoneySent() {
        val t = transaction("Debit INR 1234.56\nAxis Bank A/c XX1234\n10-10-26 10:00:00\nACH-DR-PAYTM PAYMENTS SERV\nWhatsApp BAL to 9999999999\nNot You? SMS BLOCKALL CustID to 9999999999")
        assertFalse(t.isReceived)
        assertEquals("1234.56", t.amount)
        assertEquals("PAYTM PAYMENTS SERV", t.payee)
        assertEquals("1234", t.accountNumber)
    }

    @Test
    fun parkingCharge_isAFastagDebit() {
        val event = (classify("Madam/Dear Sir, SBI FASTag Acc ending with BCC12 VRN: TN01DT1234 debited Rs.100 Parking Charges at ACME MALL on 10-Oct-2026 10:00:00 AM Bal Rs.1234", "AD-SBIFTG").content as MessageContent.FastTag).event as FastTagEvent.TollPaid
        assertEquals("TN01DT1234", event.vehicleNumber)
        assertEquals("100", event.tollAmount)
        assertEquals("1234", event.balance)
        assertEquals("Parking at ACME MALL", event.tollName)
    }

    @Test
    fun accountDebitedTowards_isMoneySent() {
        val t = transaction("Your A/c has been debited towards ACME for INR 123.45 on 10-10-26. abc@axl - Axis Bank")
        assertFalse(t.isReceived)
        assertEquals("123.45", t.amount)
        assertEquals("ACME", t.payee)
    }

    @Test
    fun asPresentedMandate_isAutopaySetUp() {
        val c = classify("Your UPI ASPRESENTED mandate has been successfully created towards ACME from 10-10-26 to 10-10-27 for INR 1234.56 - Axis Bank").content as MessageContent.ScheduledPayment
        assertEquals(ScheduledKind.MANDATE_SET, c.kind)
        assertEquals("1234.56", c.amount)
        assertEquals("ACME", c.payee)
    }

    @Test
    fun cardEmiConversion_isANoticeWithTheAmount() {
        val c = classify("Dear Customer, your transaction of Rs 12,345.67 using ICICI Bank Credit Card XX1234 has been converted into EMI on 10-10-26.", "AD-ICICIB").content as MessageContent.Notice
        assertEquals(NoticeKind.EMI_CONVERTED, c.kind)
        assertEquals("12345.67", c.amount)
    }
}
