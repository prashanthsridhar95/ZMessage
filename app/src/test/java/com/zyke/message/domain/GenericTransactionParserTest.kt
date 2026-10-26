package com.zyke.message.domain

import com.zyke.message.domain.classification.GenericTransactionParser
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.BankTransactionType
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericTransactionParserTest {
    private val parser = GenericTransactionParser()

    @Test
    fun sbiDebit() {
        val tx = parser.parse("Dear Customer, A/C X1234 debited by Rs. 500.00 on 12Jan25 trf to RAJESH KUMAR Refno 123456789. If not you, call 1800111109 -SBI")!!
        assertEquals("500.00", tx.amount)
        assertEquals("1234", tx.accountNumber)
        assertEquals("RAJESH KUMAR", tx.payee)
        assertEquals(BankTransactionType.SENT, tx.type)
        assertEquals(BankName.SBI, tx.bankName)
    }

    @Test
    fun iciciDebitWithSemicolonPayee() {
        val tx = parser.parse("ICICI Bank Acct XX123 debited for Rs 1,200.00 on 12-Jan-25; SWIGGY credited. UPI:503412345678. Call 18002662 for dispute.")!!
        assertEquals("1200.00", tx.amount.replace(",", ""))
        assertEquals("123", tx.accountNumber)
        assertEquals("SWIGGY", tx.payee)
        assertEquals(BankName.ICICI, tx.bankName)
    }

    @Test
    fun kotakUpiSent() {
        val tx = parser.parse("Sent Rs.250.00 from Kotak Bank AC X1234 to merchant@okaxis on 12-01-25.UPI Ref 123456789012.")!!
        assertEquals("250.00", tx.amount)
        assertEquals("merchant@okaxis", tx.payee)
        assertEquals(BankName.KOTAK, tx.bankName)
    }

    @Test
    fun axisInfoField_usesLastSegment() {
        val tx = parser.parse("INR 1500.00 debited from A/c no. XX5678 on 12-01-25 12:30:45. Info- UPI/P2M/123456789/ZOMATO. Avl Bal- INR 5000.00 -Axis Bank")!!
        assertEquals("1500.00", tx.amount)
        assertEquals("5678", tx.accountNumber)
        assertEquals("ZOMATO", tx.payee)
    }

    @Test
    fun creditShowsSender() {
        val tx = parser.parse("Rs 15,000.00 credited to your A/c XX4321 on 01-02-25 by NEFT from ACME CORP. Avl Bal Rs 20,000.00")!!
        assertEquals(BankTransactionType.RECEIVED, tx.type)
        assertEquals("15000.00", tx.amount.replace(",", ""))
        assertEquals("ACME CORP", tx.payee)
    }

    @Test
    fun cardSpend_ignoresAvailableLimit() {
        val tx = parser.parse("Spent Rs. 899 on your ICICI Bank Card XX4321 on 12-Jan-25 at AMAZON PAY. Avl Limit Rs 50,000")!!
        assertEquals("899", tx.amount)
        assertEquals("4321", tx.accountNumber)
        assertEquals("AMAZON PAY", tx.payee)
    }

    @Test
    fun rupeeSymbol_andEndingDigits() {
        val tx = parser.parse("₹499.00 debited from your account ending 9876 towards Netflix on 12-Jan-25.")!!
        assertEquals("499.00", tx.amount)
        assertEquals("9876", tx.accountNumber)
        assertEquals("Netflix", tx.payee)
    }

    @Test
    fun balanceNextToKeyword_isNotTheAmount() {
        val tx = parser.parse("Your A/c XX1234 debited. Avl Bal Rs 5000. Amt Rs 300 spent at CAFE")
        // The only amount near "debited" is a balance, so we must not report 5000.
        if (tx != null) assertTrue(tx.amount != "5000")
    }

    @Test
    fun withdrawal() {
        val tx = parser.parse("Rs 2,000 withdrawn from A/c XX1234 at ATM on 12-01-25")!!
        assertEquals("2000", tx.amount.replace(",", ""))
        assertEquals("ATM", tx.payee)
    }

    @Test
    fun notMoneyMovements_areIgnored() {
        assertNull(parser.parse("Get flat Rs 200 cashback on your next order! T&C apply"))
        assertNull(parser.parse("Your A/c XX1234 balance is Rs 5,000.00"))
        assertNull(parser.parse("Dear customer, your payment of Rs 500 is due soon"))
    }

    @Test
    fun cardBillWithMonthNameDate() {
        val tx = parser.parse("Your ICICI Bank Credit Card XX4321 statement is generated. Total amount due Rs 12,345.00, Minimum amount due Rs 617.00, due by 25-Jan-25")!!
        assertEquals(BankTransactionType.CARD_BILL, tx.type)
        assertEquals("12,345.00", tx.amount)
        assertEquals("617.00", tx.minimumDue)
        assertEquals(LocalDate.of(2025, 1, 25), tx.dueDate)
        assertEquals("4321", tx.accountNumber)
    }

    @Test
    fun classifierTurnsUnknownBankFormatsIntoTransactions() {
        val classifier = MessageClassifier()
        val msg = classifier.classify(
            SmsMessage(1, "VM-SBIINB", "Dear Customer, A/C X1234 debited by Rs. 500.00 on 12Jan25 trf to RAJESH KUMAR Refno 123456789.", 0, true)
        )
        val content = msg.content as MessageContent.Transaction
        assertEquals("500", content.amount)
        assertEquals("1234", content.accountNumber)
        assertEquals("RAJESH KUMAR", content.payee)

        val rupee = classifier.classify(SmsMessage(2, "X", "₹75 paid to chai@ybl via UPI", 0, true))
        assertNotNull(rupee.content as? MessageContent.Transaction)
    }
}
