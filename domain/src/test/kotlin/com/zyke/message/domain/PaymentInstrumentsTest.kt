package com.zyke.message.domain

import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.accounts.PaymentInstrument
import com.zyke.message.domain.accounts.PaymentInstruments
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.model.UserCard
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PaymentInstrumentsTest {
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private fun classify(body: String, sender: String = "AD-HDFCBK") =
        classifier.classify(SmsMessage(nextId++, sender, body, 1_790_000_000_000L, true))

    private fun instrument(body: String, sender: String = "AD-HDFCBK"): PaymentInstrument {
        val m = classify(body, sender)
        assertTrue("not a transaction: $body", m.content is MessageContent.Transaction)
        return PaymentInstruments.of(m)
    }

    private fun tx(body: String, payee: String, last4: String, isCard: Boolean, bank: BankName = BankName.HDFC, received: Boolean = false): ClassifiedMessage {
        val merchant = classifier.classify(SmsMessage(0, "AD-HDFCBK", "Sent Rs.1.00\nFrom HDFC Bank A/C *1\nTo X\nOn 26/10/26\nRef 1", 0, true)).merchant
        return ClassifiedMessage(
            SmsMessage(nextId++, "AD-HDFCBK", body, 1_790_000_000_000L, true), MessageCategory.OTHERS, merchant,
            MessageContent.Transaction(bank, payee, "100", last4, received, isCard)
        )
    }

    @Test
    fun hdfcUpiAccountIsABankAccount() =
        assertEquals(PaymentInstrument.BANK_ACCOUNT, instrument("Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1"))

    @Test
    fun hdfcCardSpendIsACreditCard() =
        assertEquals(PaymentInstrument.CREDIT_CARD, instrument("Spent Rs.804 On HDFC Bank Card 5765 At AMAZON On 2026-10-01:10:20:11 Not You? To Block+Reissue call 18002586161"))

    @Test
    fun axisCardSpendIsACreditCard() =
        assertEquals(PaymentInstrument.CREDIT_CARD, instrument("Spent INR 1,200.00 Axis Bank Card no. XX8065 12-10-26 10:20:30 IST AMAZON Avl Limit: INR 50,000.00", "AX-AXISBK"))

    @Test
    fun debitCardWordingIsADebitCard() {
        val m = tx("Rs 500.00 spent on your SBI Debit Card ending 1234 at SWIGGY on 12Oct26", "SWIGGY", "1234", isCard = true, bank = BankName.SBI)
        assertEquals(PaymentInstrument.DEBIT_CARD, PaymentInstruments.of(m))
    }

    @Test
    fun creditWordingBeatsTheWordDebitedInTheSameText() {
        val m = tx("Rs 500.00 debited from your ICICI Credit Card XX1234 at SWIGGY", "SWIGGY", "1234", isCard = true, bank = BankName.ICICI)
        assertEquals(PaymentInstrument.CREDIT_CARD, PaymentInstruments.of(m))
    }

    @Test
    fun imps_neft_upi_areBankAccounts() {
        assertEquals(PaymentInstrument.BANK_ACCOUNT, PaymentInstruments.of(tx("Rs.5000 credited to A/c XX1805 via NEFT from ACME", "ACME", "1805", false, received = true)))
        assertEquals(PaymentInstrument.BANK_ACCOUNT, PaymentInstruments.of(tx("Rs.5000 sent via IMPS to RAVI", "RAVI", "", false)))
        assertEquals(PaymentInstrument.BANK_ACCOUNT, instrument("Credit Alert! Rs.1000.00 credited to HDFC Bank A/c XX5765 on 19-10-26 from VPA ravi@okhdfc (UPI 5566)"))
    }

    @Test
    fun aWalletPaymentIsAWallet() {
        assertEquals(PaymentInstrument.WALLET, PaymentInstruments.of(tx("Paid Rs.50 from Paytm Wallet to CAFE ONE", "CAFE ONE", "", false, BankName.PAYTM)))
        assertEquals(PaymentInstrument.WALLET, PaymentInstruments.of(tx("Rs.50 paid to CAFE ONE using UPI Lite", "CAFE ONE", "", false)))
        assertEquals(PaymentInstrument.WALLET, PaymentInstruments.of(tx("Rs.50 paid to CAFE ONE from Amazon Pay balance", "CAFE ONE", "", false)))
    }

    @Test
    fun aTopUpOfUpiLiteLeavesTheBankAccount() {
        val m = classify("UPI LITE Top-up amounting to Rs.200 has been successful. Via Google Pay.", "Google Pay")
        val c = m.content
        if (c is MessageContent.Transaction) {
            assertEquals("UPI Lite", c.payee)
            assertEquals(PaymentInstrument.BANK_ACCOUNT, PaymentInstruments.of(m))
        }
    }

    @Test
    fun aFastTagMessageIsFastTag() {
        val m = tx("x", "x", "", false).copy(content = MessageContent.FastTag(FastTagEvent.Activated("TN38DC3528")))
        assertEquals(PaymentInstrument.FASTAG, PaymentInstruments.of(m))
    }

    @Test
    fun noWordsNoNumberNoIcon() {
        assertEquals(PaymentInstrument.UNKNOWN, PaymentInstruments.of(tx("Rs.100 paid to SHOP", "SHOP", "", false)))
        assertEquals(PaymentInstrument.UNKNOWN, PaymentInstruments.of(classify("hello there")))
    }

    @Test
    fun theMatchedAccountBeatsTheWording() {
        // Ambiguous wording ("Card" only) reads as a credit card; the person's debit card of the same digits wins.
        val a = classify("Spent Rs.804 On HDFC Bank Card 8065 At AMAZON On 2026-10-01:10:20:11")
        val b = classify("Spent Rs.200 On HDFC Bank Card 8065 At SWIGGY On 2026-10-02:10:20:11")
        val cards = listOf(UserCard("1", CardType.DEBIT, "8065", "My debit", bankName = "HDFC"))
        val overview = Accounts.build(listOf(a, b), cards, Instant.ofEpochMilli(1_790_000_000_000L), ZoneId.of("UTC"))
        val lookup = PaymentInstruments.Lookup.of(overview.accounts)
        assertEquals(PaymentInstrument.CREDIT_CARD, PaymentInstruments.of(a))
        assertEquals(PaymentInstrument.DEBIT_CARD, PaymentInstruments.of(a, lookup))
    }

    @Test
    fun theMatchedBankAccountIsABankAccount() {
        val a = classify("Sent Rs.540.00\nFrom HDFC Bank A/C *5765\nTo BURMY FOODS\nOn 26/10/26\nRef 1")
        val overview = Accounts.build(listOf(a), emptyList(), Instant.ofEpochMilli(1_790_000_000_000L), ZoneId.of("UTC"))
        assertEquals(PaymentInstrument.BANK_ACCOUNT, PaymentInstruments.of(a, PaymentInstruments.Lookup.of(overview.accounts)))
    }

    @Test
    fun otherContentHasNoInstrument() {
        assertEquals(PaymentInstrument.UNKNOWN, PaymentInstruments.of(classify("482913 is your OTP for login")))
    }
}
