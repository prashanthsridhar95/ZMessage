package com.zyke.message.domain

import com.zyke.message.domain.accounts.PaymentInstrument
import com.zyke.message.domain.accounts.PaymentInstruments
import com.zyke.message.domain.cards.CardNetwork
import com.zyke.message.domain.cards.CardNetworks
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.CountedReason
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Two shapes the parsing report listed as not understood: "A transaction of INR X was made on your Card ending N at M on D"
 * (an Amex card purchase) and "₹X/- has been credited to your <shop> account" (a shop's wallet). Made-up numbers.
 */
class CardMadeAndMerchantWalletTest {
    private val classifier = MessageClassifier()

    private fun classify(body: String, sender: String = "AMEXIN", id: Long = 1): ClassifiedMessage =
        classifier.classify(SmsMessage(id, sender, body, 1_790_000_000_000L, true))

    private fun tx(body: String, sender: String = "AMEXIN") =
        classify(body, sender).content as? MessageContent.Transaction ?: error("not a transaction: ${classify(body, sender).content}")

    private val amex = "AMEX: A transaction of INR 2,499.99 was made on your Card ending 1007 at SWIGGY PG on 12-11-2026. Call 18002662 if this was not made by you."
    private val bigbasket = "Alert: bigbasket account balance update.\nHi,\n₹999/- has been credited to your bigbasket account."

    // ---- card purchase

    @Test
    fun cardTransactionMade_isASpendOnTheCard() {
        val m = classify(amex)
        val t = m.content as MessageContent.Transaction
        assertFalse(t.isReceived)
        assertTrue(t.isCard)
        assertFalse(t.isCardPayment)
        assertEquals("2499.99", t.amount)
        assertEquals("1007", t.accountNumber)
        assertEquals(BankName.AMERICAN_EXPRESS, t.bankName)
        assertEquals("SWIGGY", t.payee)
        assertEquals(BigDecimal("2499.99"), Counting.spend(m))
        assertEquals(PaymentInstrument.CREDIT_CARD, PaymentInstruments.of(m))
        assertEquals(CardNetwork.AMEX, CardNetworks.fromText(m.message.body))
        assertUnderstood(amex)
    }

    @Test
    fun cardTransactionMade_stripsGatewayNoiseAndReadsOtherIssuers() {
        assertEquals("Acme Store", tx("A transaction of INR 1,499.00 was made on your Card ending 1007 at Acme Store Payment Gateway on 12-11-2026.").payee)
        assertEquals("Acme Store", tx("A transaction of Rs. 150 was made on your Credit Card ending 4321 at Acme Store PG on 12-11-26. Not you? Call 1800.", "VK-BANKXX").payee)
        // A shop that is only called PG keeps its name.
        assertEquals("PG", tx("A transaction of INR 10.00 was made on your Card ending 1007 at PG on 12-11-2026.").payee)
    }

    @Test
    fun cardTransactionMade_toAMerchantThatTheResolverKnowsGetsItsCategory() {
        assertEquals(MessageCategory.FOOD, classify(amex).category)
    }

    // ---- merchant wallet credit

    @Test
    fun merchantAccountCredit_isAWalletCreditNotIncome() {
        val m = classify(bigbasket, "VM-BBSTAR")
        val t = m.content as MessageContent.Transaction
        assertTrue(t.isReceived)
        assertEquals("999", t.amount)
        assertEquals("bigbasket wallet", t.payee)
        assertEquals("", t.accountNumber)
        assertTrue("kept out of income totals", t.isCardPayment)
        assertFalse(t.isCard)
        assertEquals(MessageCategory.SHOPPING, m.category)
        assertTrue(Counting.of(m) is Counted.NotCounted)
        assertEquals(null, Counting.income(m))
        assertEquals(null, Counting.spend(m))
        assertTrue(CountedReason.explain(m).contains("wallet"))
        assertEquals(PaymentInstrument.WALLET, PaymentInstruments.of(m))
        assertUnderstood(bigbasket, sender = "VM-BBSTAR")
    }

    @Test
    fun merchantAccountCredit_variants() {
        val a = tx("Rs 250.50 has been credited to your Acme wallet. Use it on your next order.", "VM-ACME")
        assertEquals("250.50", a.amount)
        assertEquals("Acme wallet", a.payee)
        assertTrue(a.isReceived && a.isCardPayment)
        val b = tx("₹1,000/- has been added to your Acme Store account.", "VM-ACME")
        assertEquals("1000", b.amount)
        assertEquals("Acme Store wallet", b.payee)
    }

    // ---- unchanged

    @Test
    fun bankAccountCredits_stayIncome() {
        listOf(
            "Rs 5,000.00 has been credited to your A/c XX1234 on 12-11-26. Avl Bal Rs 20,000.00",
            "INR 12,000.00 has been credited to your account ending 1234 on 12-11-26.",
            "Rs 500 has been credited to your HDFC Bank account on 12-11-26.",
            "Rs 500 has been credited to your savings account. Ref 123456789012.",
            "Rs 500 has been credited to your account XX1234 by UPI."
        ).forEachIndexed { i, body ->
            val m = classify(body, "AX-AXISBK", id = i + 10L)
            val t = m.content as? MessageContent.Transaction
            if (t != null) {
                assertTrue(body, t.isReceived)
                assertFalse(body, t.isCardPayment)
                assertFalse(body, t.payee.contains("wallet", ignoreCase = true))
            }
        }
        val m = classify("Rs 5,000.00 has been credited to your A/c XX1234 on 12-11-26. Avl Bal Rs 20,000.00", "AX-AXISBK")
        assertEquals(BigDecimal("5000"), Counting.income(m))
    }

    @Test
    fun otpAndPromoTexts_areUnaffected() {
        val otp = classify("123456 is your OTP for a transaction of INR 2,499.99 on your Card ending 1007 at SWIGGY. Do not share.", "AMEXIN")
        assertTrue(otp.content is MessageContent.Otp)
        val promo = classify("Get Rs 500 cashback credited to your account! Offer valid till 30-11-2026. Use code SAVE500 at bigbasket.", "VM-BBSTAR")
        assertFalse(promo.content is MessageContent.Transaction)
    }

    private fun assertUnderstood(vararg bodies: String, sender: String = "AMEXIN") {
        val messages = bodies.mapIndexed { i, b -> classify(b, sender, id = i + 1L) }
        val report = DiagnosticsBuilder.build(messages)
        assertEquals(bodies.size, report.moneyMessages)
        assertEquals(report.samples.toString(), 0, report.unrecognised)
    }
}
