package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.classification.UpiLiteNotification
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class UpiLiteNotificationTest {
    @Test
    fun aTopUpNotification_givesItsAmount() {
        assertEquals("500", UpiLiteNotification.amount("UPI Lite refilled", "₹500 added to your UPI Lite balance"))
        assertEquals("1500.50", UpiLiteNotification.amount("Google Pay", "Your UPI Lite has been topped up with Rs. 1,500.50"))
    }

    @Test
    fun aPaymentOrAFailure_isNotATopUp() {
        assertNull(UpiLiteNotification.amount("Paid ₹120", "Paid to Chai Point using UPI Lite"))
        assertNull(UpiLiteNotification.amount("UPI Lite top-up failed", "₹500 could not be added to UPI Lite"))
        assertNull(UpiLiteNotification.amount("Payment received", "₹500 added to your account"))
    }

    @Test
    fun theKeptMessage_isReadAsMoneySpent() {
        val message = SmsMessage(1, UpiLiteNotification.SENDER, UpiLiteNotification.body("500"), 0L, false, isRcs = true)
        val content = MessageClassifier().classify(message).content as MessageContent.Transaction
        assertEquals("UPI Lite", content.payee)
        assertEquals("500", content.amount)
        assertFalse(content.isReceived)
    }
}
