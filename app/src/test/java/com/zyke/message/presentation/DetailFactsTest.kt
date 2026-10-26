package com.zyke.message.presentation

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.presentation.format.detailFacts
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailFactsTest {
    private val classifier = MessageClassifier()
    private fun facts(body: String) =
        classifier.classify(SmsMessage(1, "AD-HDFCBK", body, 0, true)).detailFacts(Locale.ENGLISH).associate { it.label to it.value }

    @Test
    fun payment() {
        val f = facts("Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1")
        assertEquals("₹540", f["Paid"])
        assertEquals("Burmy Foods", f["To"])
        assertEquals("•• 5765", f["Account"])
        assertEquals("HDFC", f["Bank"])
    }

    @Test
    fun cardBill() {
        val f = facts("HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:20-03-2025 pay https://hdfcbk.io/x")
        assertEquals("₹12345", f["Amount due"]?.replace(",", ""))
        assertEquals("₹617", f["Minimum due"])
        assertEquals("20 Mar 2025", f["Due date"])
    }

    @Test
    fun scheduledPayment() {
        val f = facts("Alert:\nINR.650.00 will be debited on 28/10/2026 from HDFC Bank Card 0421 for Google Play - Conten..")
        assertEquals("₹650", f["Will be debited"])
        assertEquals("Google Play", f["For"])
        assertEquals("28 Oct 2026", f["On"])
        assertEquals("•• 0421", f["Card"])
    }

    @Test
    fun otpAndGeneric() {
        assertEquals("482913", facts("482913 is your OTP for login")["Code"])
        assertTrue(facts("hello there").isEmpty())
    }
}
