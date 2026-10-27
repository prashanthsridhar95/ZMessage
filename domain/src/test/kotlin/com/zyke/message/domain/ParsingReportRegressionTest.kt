package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Message shapes that a user's parsing report showed as plain text; each is rebuilt here with made-up numbers. */
class ParsingReportRegressionTest {
    private val classifier = MessageClassifier()

    private fun classify(body: String, sender: String = "AD-HDFCBK") =
        classifier.classify(SmsMessage(1, sender, body, 0L, true)).content

    private fun scheduled(body: String): MessageContent.ScheduledPayment =
        classify(body) as? MessageContent.ScheduledPayment ?: error("not a scheduled payment")

    private fun transaction(body: String): MessageContent.Transaction =
        classify(body) as? MessageContent.Transaction ?: error("not a transaction")

    private fun bill(body: String): MessageContent.CardBill =
        classify(body) as? MessageContent.CardBill ?: error("not a card bill")

    @Test
    fun cancelledMandate_withAndWithoutServiceName() {
        val named = scheduled("Update:\nRs. 199.00 UPI mandate to Google Play has been cancelled from HDFC Bank A/c x1234.\nUMN: abc@okhdfcb")
        assertEquals(ScheduledKind.MANDATE_CANCELLED, named.kind)
        assertEquals("199", named.amount)
        assertEquals("Google Play", named.payee)
        assertEquals("1234", named.accountNumber)

        assertEquals(
            ScheduledKind.MANDATE_CANCELLED,
            scheduled("Update:\nRs. 199.00 UPI mandate to Netflix has been cancelled from HDFC Bank A/c x1234.").kind
        )
    }

    @Test
    fun upiMandateConfirmation_isAutopaySetUp_notSpend() {
        val c = scheduled("UPI-Mandate:\nFor Rs. 1500.00\nTo Netflix\nA/c x1234\nRef 123456789012\nNot you? Call 18002586161/ SMS BLOCK UPI to 7308080808\n-HDFC Bank")
        assertEquals(ScheduledKind.MANDATE_SET, c.kind)
        assertEquals("1500", c.amount)
        assertEquals("Netflix", c.payee)
    }

    @Test
    fun upiMandateRequest_needsApproval() {
        val c = scheduled("UPI-Mandate:\nYou have a UPI request of Rs.2500.00 from Netflix your UPI app to block\nApplication No JPLSDF123456789012\n-HDFC Bank")
        assertEquals(ScheduledKind.MANDATE_REQUEST, c.kind)
        assertEquals("2500", c.amount)
        assertEquals("Netflix", c.payee)
    }

    @Test
    fun atmWithdrawal_isMoneyOut() {
        val c = transaction("UPI ATM Withdrawal:Rs.500.0\nFrom:HDFC Bank A/c 1234\nAt:SBI ATM CASH ATM\nUPI RRN:123456789012 on 25/10/25\nNot you? Call 18002586161/SMS BLOCK UPI to 7308080808")
        assertFalse(c.isReceived)
        assertEquals("500", c.amount)
        assertEquals("1234", c.accountNumber)
        assertEquals("ATM cash", c.payee)
    }

    @Test
    fun axisStatement_withDrPrefixedAmounts() {
        val c = bill(
            "Your statement for Axis Bank Credit Card no. XX1234 is generated.\nDue on: 20-11-25\n" +
                "Total amt: INR  Dr. 12345.67\nMin amt due: INR  Dr. 617.28\nPay at axis.bank.in/ccpaynow\n" +
                "To view fees & charges: axis.bank.in/kfs\nTo view/download statement: https://axis.bank.in/s/abc"
        )
        assertEquals(BankName.AXIS, c.bankName)
        assertEquals("12345.67", c.amount)
        assertEquals("617.28", c.minimumDue)
        assertEquals(LocalDate.of(2025, 11, 20), c.dueDate)
        assertEquals("1234", c.accountNumber)
        assertEquals("https://axis.bank.in/ccpaynow", c.paymentLink)
    }

    @Test
    fun cardStatement_withTotalPaymentWording() {
        val c = bill("Dear Customer, your statement for Jane Card **********12345 has been generated. Total payment of Rs.8450.50 is due by 05/11/25. Please refer to your statement for min payment due.")
        assertEquals("8450.50", c.amount)
        assertEquals(LocalDate.of(2025, 11, 5), c.dueDate)
        assertEquals(null, c.minimumDue)
    }

    @Test
    fun rechargeProcessed_isMoneyOut() {
        val c = transaction("Hi, we have processed Rs. 299.0 for your Airtel Mobile 9876543210. The payment will be updated within 10 minutes. Please keep your order ID 1234567890123456789 for future reference. Track your payment status via https://x.in/abc")
        assertFalse(c.isReceived)
        assertEquals("299", c.amount)
        assertTrue(c.payee.startsWith("Airtel"))
    }

    @Test
    fun razorpayDonation_isMoneyOut() {
        val c = transaction("Payment of Rs.1000 towards subscription for Jane On Hunger Foundation successfully made on 26 October 2025 - Razorpay")
        assertFalse(c.isReceived)
        assertEquals("1000", c.amount)
        assertEquals("Hunger Foundation", c.payee)
    }

    @Test
    fun ordinaryBalanceAlert_isStillNotAMovement() {
        assertNotNull(classify("Your a/c balance is Rs. 5000.00 as of today"))
        assertFalse(classify("Your a/c balance is Rs. 5000.00 as of today") is MessageContent.Transaction)
    }
}
