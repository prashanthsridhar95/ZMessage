package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** HDFC's reply to "Balance" as a rich (RCS) message: a banner, this text and two buttons. The text is as the person reported it. */
class RichReplyTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneId.of("UTC")
    private fun classify(body: String, sender: String = "HDFC Bank", date: Long = 1_700_000_000_000L): ClassifiedMessage =
        classifier.classify(SmsMessage(1, sender, body, date, false, isRcs = true))

    private val sample = "Account Balance\nHi! Thanks for reaching out to HDFC Bank!\n\nYour account balance for account ending with 5765 is ₹8,509.06\n\n" +
        "Explore our WhatsApp Banking for 220+ transactions all at your fingertips!"

    private fun expect(body: String, account: String = "5765", amount: String = "8509.06") {
        val message = classify(body)
        assertEquals("not a balance notice: $body", NoticeKind.BALANCE_UPDATE, (message.content as? MessageContent.Notice)?.kind)
        assertFalse("must not be a promotion: $body", message.isPromotional)
        val found = BalanceReader.latest(listOf(message), zone)
        assertEquals("not read: $body", 1, found.size)
        assertEquals(account, found.single().account)
        assertEquals(0, found.single().amount.compareTo(BigDecimal(amount)))
        assertEquals("HDFC", found.single().bank)
        assertEquals(message.message.date, found.single().at)
    }

    @Test fun theReportedReply() = expect(sample)

    @Test fun withoutTheHeaderOrTheMarketing() = expect("Your account balance for account ending with 5765 is ₹8,509.06")

    @Test fun accountEndingWithoutWith() = expect("Your account balance for account ending 5765 is ₹8,509.06\n\nExplore our WhatsApp Banking for 220+ transactions!")

    @Test fun maskedAccount() = expect("Hi! Thanks for reaching out to HDFC Bank!\nYour account balance for A/c ending with XX5765 is Rs. 8,509.06")

    @Test fun inrAndRsSpellings() {
        expect("Your account balance for account ending with 5765 is INR 8,509.06\nExplore our WhatsApp Banking for 220+ transactions")
        expect("Account Balance\nYour account balance for account ending with 5765 is Rs.8,509.06\nSend Book FD to WhatsApp 7070022222")
    }

    @Test fun marketingWordsAfterTheBalanceDoNotUndoIt() =
        expect("Your account balance for account ending with 5765 is \u20b98,509.06\n\nExplore our WhatsApp Banking for UPI payments, bill pay and card offers, 220+ transactions")

    @Test fun theMarketingTailIsNotAPromotionAndNotAnAmount() {
        val message = classify(sample)
        assertFalse(message.isPromotional)
        assertEquals("8509.06", (message.content as MessageContent.Notice).amount)
    }

    @Test fun marketingNumbersAreNeverTheBalance() {
        val found = BalanceReader.latest(listOf(classify(sample)), zone).single()
        assertEquals(0, found.amount.compareTo(BigDecimal("8509.06")))
    }

    @Test fun theSmartEmiTextIsStillNotABalance() {
        val message = classify(
            "Bill of Rs. 12108 as on 06-Oct-26 is eligible for conversion to EMI of Rs. 1099 p.m. View SmartEMI details: https://1.hdfc.bank.in/abc Ignore if paid. *T&C apply.",
            "VM-HDFCBK"
        )
        assertTrue((message.content as? MessageContent.Notice)?.kind != NoticeKind.BALANCE_UPDATE)
        assertTrue(BalanceReader.latest(listOf(message), zone).isEmpty())
    }
}
