package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.diagnostics.MessageRedactor
import com.zyke.message.domain.model.SmsMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun redactor_hidesNumbersNamesHandlesAndLinks() {
        val redacted = MessageRedactor.redact(
            "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 629918461792 pay at https://1.hdfc.bank.in/HDFCBK/a/wLHlKg or friend.name@okaxis"
        )

        assertFalse(redacted.any { it in "012345678" })
        assertTrue("Rs.999.99" in redacted)
        assertFalse("BURMY" in redacted)
        assertTrue("[name]" in redacted)
        assertTrue("<link>" in redacted)
        assertTrue("xxxx@okaxis" in redacted)
        assertFalse("friend.name" in redacted)
        // The structure a parser author needs is still there.
        assertTrue("From HDFC Bank A/C *9999" in redacted)
    }

    @Test
    fun redactor_leavesLowerCaseWordsAlone() {
        assertEquals("paid to the shop", MessageRedactor.redact("paid to the shop"))
    }

    @Test
    fun diagnostics_countsRecognisedAndUnrecognisedMoneyMessages() {
        val classifier = MessageClassifier()
        fun msg(id: Long, body: String, sender: String = "AD-BANK") = classifier.classify(SmsMessage(id, sender, body, 0, true))

        val diagnostics = DiagnosticsBuilder.build(
            listOf(
                msg(1, "Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1"),
                msg(2, "Your order total is Rs 200"),
                msg(3, "Your order total is Rs 200"),   // duplicate shape
                msg(4, "Fee of Rs 15 applies to your account"),
                msg(5, "Hello there"),                    // not money
                msg(6, "Flat Rs 200 off T&C apply", sender = "AD-SHOP-P") // promo: ignored
            )
        )

        assertEquals(4, diagnostics.moneyMessages)
        assertEquals(3, diagnostics.unrecognised)
        assertEquals(1, diagnostics.recognised)
        assertEquals(2, diagnostics.samples.size) // the duplicate collapses
        assertTrue(diagnostics.samples.all { s -> s.none { it in "012345678" } })
    }

    @Test
    fun samplesAreOnePerKind_mostCommonFirst() {
        val classifier = MessageClassifier()
        fun msg(id: Long, body: String) = classifier.classify(SmsMessage(id, "XY-ZORK", body, 1_793_000_000_000L, true))
        val rare = "Zorkplex quarterly flibber of Rs 10 noted for you"
        val common = "Wibblewobble gadget moment of Rs %d is ticking along"
        val messages = listOf(msg(1, rare)) + (2L..5L).map { msg(it, common.format(100 * it)) }

        val diagnostics = DiagnosticsBuilder.build(messages)
        // Four of one kind and one of another make two samples, the common kind first.
        assertEquals(5, diagnostics.unrecognised)
        assertEquals(2, diagnostics.samples.size)
        assertTrue(diagnostics.samples.first().startsWith("Wibblewobble"))
    }
}
