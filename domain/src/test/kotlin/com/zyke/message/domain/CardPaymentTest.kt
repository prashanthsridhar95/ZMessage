package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardPaymentTest {
    private fun tx(body: String): MessageContent.Transaction? =
        MessageClassifier().classify(SmsMessage(1, "AD-HDFCBK", body, 0L, true)).content as? MessageContent.Transaction

    @Test
    fun paymentReceivedOnACard_isNotIncome() {
        val t = tx("Payment of Rs.5,000.00 has been received on your HDFC Bank Credit Card XX1234 on 12-10-26. Thank you for the payment.")
        assertTrue(t.toString(), t?.isCardPayment == true)
    }

    @Test
    fun ordinaryCredit_isStillIncome() {
        val t = tx("Rs.25000.00 credited to HDFC Bank A/c XX5765 on 12-10-26 by UPI Ref 998877")
        assertFalse(t.toString(), t?.isCardPayment == true)
    }
}
