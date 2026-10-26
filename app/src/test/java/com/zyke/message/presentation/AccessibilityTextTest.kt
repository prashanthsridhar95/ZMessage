package com.zyke.message.presentation

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.presentation.format.spokenSummary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityTextTest {
    private val classifier = MessageClassifier()
    private fun summary(body: String, unread: Boolean = false, today: LocalDate = LocalDate.of(2026, 10, 26)) =
        classifier.classify(SmsMessage(1, "AD-HDFCBK", body, 0, true)).spokenSummary("2:30 PM", unread, today)

    @Test
    fun payment_isOneSentence() {
        val text = summary("Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1")
        assertEquals("Paid ₹540 to Burmy Foods, account ending 5765. 2:30 PM", text)
    }

    @Test
    fun unreadIsSaidFirst() {
        assertTrue(summary("Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1", unread = true).startsWith("Unread. Paid"))
    }

    @Test
    fun otpIsReadDigitByDigit() {
        assertTrue("4 8 2 9 1 3" in summary("482913 is your OTP for login"))
    }

    @Test
    fun scheduledPaymentSaysWhen() {
        val text = summary("Alert:\nINR.650.00 will be debited on 28/10/2026 from HDFC Bank Card 0421 for Google Play - Conten..")
        assertTrue("Upcoming auto-debit of ₹650 for Google Play" in text)
        assertTrue("due in 2 days" in text)
    }

    @Test
    fun genericMessageIsTrimmed() {
        val text = summary("hello   there\n" + "x".repeat(500))
        assertTrue(text.startsWith("Message from AD-HDFCBK: hello there"))
        assertFalse(text.length > 260)
    }
}
