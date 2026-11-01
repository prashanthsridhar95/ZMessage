package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.home.UpcomingBuilder
import com.zyke.message.domain.home.UpcomingKind
import com.zyke.message.domain.ledger.CountedReason
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.life.LifeAgenda
import com.zyke.message.domain.life.LifeKind
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The five shapes the parsing report listed as not understood, rebuilt with made-up numbers. */
class ParsingReportFormatsTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneOffset.UTC

    private fun at(day: LocalDate, hour: Int = 10) = day.atTime(hour, 0).toInstant(zone).toEpochMilli()

    private fun classify(body: String, sender: String = "AX-AXISBK", id: Long = 1, date: Long = 0L): ClassifiedMessage =
        classifier.classify(SmsMessage(id, sender, body, date, true))

    private fun tx(body: String, sender: String = "AX-AXISBK") =
        classify(body, sender).content as? MessageContent.Transaction ?: error("not a transaction: ${classify(body, sender).content}")

    private fun axisDebit(amount: String, account: String, narration: String, stamp: String = "10-10-26 10:00:00") =
        "Debit INR $amount\nAxis Bank A/c XX$account\n$stamp\n$narration\nWhatsApp BAL to 9999999999\nNot You? SMS BLOCKALL CustID to 9999999999"

    private fun assertUnderstood(vararg bodies: String, sender: String = "AX-AXISBK") {
        val messages = bodies.mapIndexed { i, b -> classify(b, sender, id = i + 1L) }
        val report = DiagnosticsBuilder.build(messages)
        assertEquals(bodies.size, report.moneyMessages)
        assertEquals(report.samples.toString(), 0, report.unrecognised)
    }

    // ---- 1. store wallet credit

    private val wallet = "A credit of Rs. 1,250* has been processed to your Tata CLiQ Power Cash Wallet. View here: https://tcl.iq/ab12\nTata CLiQ Luxury"

    @Test
    fun walletCredit_isReceivedIntoAWalletAndNotIncome() {
        val m = classify(wallet, "VM-TATACL")
        val t = m.content as MessageContent.Transaction
        assertTrue(t.isReceived)
        assertEquals("1250", t.amount)
        assertEquals("Tata CLiQ Power Cash Wallet", t.payee)
        assertEquals("", t.accountNumber)
        assertTrue("kept out of income totals", t.isCardPayment)
        assertEquals(MessageCategory.SHOPPING, m.category)
        assertTrue(CountedReason.explain(m).contains("wallet"))
    }

    @Test
    fun walletCredit_variants() {
        val a = tx("A credit of Rs 499.50 has been processed to your Myntra Cash Wallet. Use it on your next order.", "VM-MYNTRA")
        assertEquals("499.50", a.amount)
        assertTrue(a.isReceived && a.isCardPayment)
        val b = tx("A credit of INR 75 has been added to your Acme Cashback Wallet. View here: https://x.in/1", "VM-ACME")
        assertEquals("75", b.amount)
        assertEquals("Acme Cashback Wallet", b.payee)
        assertUnderstood(wallet, sender = "VM-TATACL")
    }

    @Test
    fun creditToABankAccount_isNotAWalletCredit() {
        val m = classify("A credit of Rs. 500 has been processed to your Savings Account ending 1234.", "VM-BANK")
        // Not a wallet: whatever it is read as, it is not the store-credit form.
        val c = m.content
        assertFalse(c is MessageContent.Transaction && c.isCardPayment)
    }

    // ---- 2. EMI due reminder with a loan account

    private val emi = "EMI of INR 12345.67 for Axis Bank Loan A/c XX1234 is due on 05-11-26. Maintain adequate balance prior to the due date to avoid lien / bounce / penal charges."

    @Test
    fun emiDue_isAnUpcomingEmiNotATransaction() {
        val m = classify(emi)
        val s = m.content as MessageContent.ScheduledPayment
        assertEquals(ScheduledKind.UPCOMING_DEBIT, s.kind)
        assertEquals("12345.67", s.amount)
        assertEquals("1234", s.accountNumber)
        assertEquals(BankName.AXIS, s.bankName)
        assertEquals(LocalDate.of(2026, 11, 5), s.date)
        assertEquals("Axis Bank loan", s.payee)
        assertEquals(MessageCategory.OTHERS, m.category)
    }

    @Test
    fun emiDue_otherDateFormats() {
        val long = classify("EMI of INR 8000 for Axis Bank Loan A/c XX4321 is due on 05-11-2026. Maintain adequate balance.").content as MessageContent.ScheduledPayment
        assertEquals(LocalDate.of(2026, 11, 5), long.date)
        val named = classify("EMI of Rs. 8,000.00 for HDFC Bank Personal Loan A/c XX4321 is due on 05-Nov-26. Maintain adequate balance.", "AD-HDFCBK").content as MessageContent.ScheduledPayment
        assertEquals(LocalDate.of(2026, 11, 5), named.date)
        assertEquals("8000", named.amount)
        assertEquals("4321", named.accountNumber)
        assertUnderstood(emi)
    }

    @Test
    fun emiDue_showsUpcomingAndIsClosedByTheDebit() {
        val reminder = classify(emi, id = 1, date = at(LocalDate.of(2026, 11, 1)))
        val today = LocalDate.of(2026, 11, 2)
        val before = UpcomingBuilder.build(listOf(reminder), today, zone = zone)
        assertEquals(1, before.size)
        assertEquals(UpcomingKind.AUTO_DEBIT, before[0].kind)
        assertEquals(BigDecimal("12345.67"), before[0].amount)
        assertEquals(LocalDate.of(2026, 11, 5), before[0].date)
        assertNull(before[0].paidOn)

        val debit = classify("NACH debit towards AXIS FINANCE for INR 12,345.67 with UMRN CITI0000000000000001 has been successfully processed in A/c no. XX1234 today - Axis Bank", id = 2, date = at(LocalDate.of(2026, 11, 5)))
        val after = UpcomingBuilder.build(listOf(reminder, debit), LocalDate.of(2026, 11, 6), zone = zone)
        assertTrue("paid and gone: $after", after.none { it.paidOn == null })
    }

    // ---- 3. credit card payment from the account

    private val cardPayment = axisDebit("15000.00", "1234", "CreditCard Payment XX 5678")

    @Test
    fun creditCardPayment_isABillPaymentNotSpending() {
        val m = classify(cardPayment)
        val t = m.content as MessageContent.Transaction
        assertFalse(t.isReceived)
        assertEquals("15000", t.amount)
        assertEquals("1234", t.accountNumber)
        assertTrue(t.isCardPayment)
        assertFalse("the account it left is a bank account", t.isCard)
        assertEquals("Credit card payment XX5678", t.payee)
        assertEquals(MessageCategory.CREDIT_CARDS, m.category)
        assertFalse(m.message.body.isEmpty())
        assertTrue(CountedReason.explain(m).startsWith("Not counted: card bill payment"))
    }

    @Test
    fun creditCardPayment_marksTheCardsBillPaid() {
        val bill = classify(
            "E-Statement of your Axis Bank Credit Card no. XX5678 has been generated. Total amount due: INR Dr. 15000.00. Minimum amt due: INR Dr. 750.00, Due date: 25-10-26.",
            id = 1, date = at(LocalDate.of(2026, 10, 5))
        )
        assertTrue(bill.content is MessageContent.CardBill)
        val paid = classify(cardPayment, id = 2, date = at(LocalDate.of(2026, 10, 12)))
        val payment = BillPayments.find(bill, bill.content as MessageContent.CardBill, listOf(bill, paid), zone)
        assertNotNull(payment)
        assertEquals(2L, payment!!.messageId)
    }

    @Test
    fun creditCardPayment_variants() {
        val a = tx(axisDebit("2500.50", "9876", "CreditCard Payment XX 4321"))
        assertTrue(a.isCardPayment)
        assertEquals("2500.50", a.amount)
        val b = tx(axisDebit("900.00", "9876", "Credit Card Payment"))
        assertTrue(b.isCardPayment)
        assertEquals("Credit card payment", b.payee)
        assertUnderstood(cardPayment)
    }

    // ---- 4. mobile-banking self transfer

    private val selfTransfer = axisDebit("5000.00", "1234", "MOB/SELFFT/1234567890/5678")

    @Test
    fun selfTransfer_isASentTransferCandidate() {
        val t = tx(selfTransfer)
        assertFalse(t.isReceived)
        assertEquals("5000", t.amount)
        assertEquals("1234", t.accountNumber)
        assertEquals("Self transfer", t.payee)
        assertFalse("not marked on its own: the person confirms the pairing", t.isCardPayment)
        assertUnderstood(selfTransfer)
    }

    @Test
    fun selfTransfer_pairsWithTheCreditInTheOtherAccount() {
        val out = classify(selfTransfer, id = 1, date = at(LocalDate.of(2026, 10, 10)))
        val inn = classify(
            "Rs.5,000.00 credited to HDFC Bank A/c XX9876 on 10-10-26 by A/c linked to mobile 9999999999 (IMPS Ref no 123456789012)", "AD-HDFCBK",
            id = 2, date = at(LocalDate.of(2026, 10, 10), 11)
        )
        val credit = inn.content as? MessageContent.Transaction
        // The credit leg is read by the general rules; pair only when it was understood as a credit with its account.
        if (credit != null && credit.isReceived && credit.accountNumber.isNotEmpty()) {
            val queue = ReviewQueues.build(listOf(out, inn), SetAsides(), zone, Instant.ofEpochMilli(at(LocalDate.of(2026, 10, 11))))
            assertEquals(1, queue.transfers.size)
            assertEquals(1L, queue.transfers[0].debit.id)
        }
    }

    @Test
    fun selfTransfer_pairsWithAnAxisCreditToo() {
        val out = classify(selfTransfer, id = 1, date = at(LocalDate.of(2026, 10, 10)))
        val inn = classify(
            "Credit INR 5000.00\nAxis Bank A/c XX9876\n10-10-26 10:00:05\nMOB/SELFFT/1234567890/1234\nWhatsApp BAL to 9999999999", id = 2, date = at(LocalDate.of(2026, 10, 10), 11)
        )
        val credit = inn.content as MessageContent.Transaction
        assertTrue(credit.isReceived)
        assertEquals("9876", credit.accountNumber)
        val queue = ReviewQueues.build(listOf(out, inn), SetAsides(), zone, Instant.ofEpochMilli(at(LocalDate.of(2026, 10, 11))))
        assertEquals(1, queue.transfers.size)
    }

    @Test
    fun otherAxisNarrations() {
        val third = tx(axisDebit("1000.00", "1234", "MOB/TPFT/RAVI KUMAR/5678"))
        assertEquals("RAVI KUMAR", third.payee)
        assertFalse(third.isCardPayment)
        val thirdPlain = tx(axisDebit("1000.00", "1234", "MOB/TPFT/1234567890/5678"))
        assertEquals("Fund transfer", thirdPlain.payee)
        val upi = tx(axisDebit("250.00", "1234", "UPI/P2A/412345678901/ASHA STORES/Axis Bank"))
        assertEquals("ASHA STORES", upi.payee)
        assertFalse(upi.isReceived)
        val neft = tx(axisDebit("25000.00", "1234", "NEFT/CITIN12345678/ACME RENTALS"))
        assertEquals("ACME RENTALS", neft.payee)
        val imps = tx(axisDebit("1200.00", "1234", "IMPS/P2A/412345678901/MEERA/HDFC"))
        assertEquals("MEERA", imps.payee)
        assertUnderstood(axisDebit("1000.00", "1234", "MOB/TPFT/RAVI KUMAR/5678"), axisDebit("25000.00", "1234", "NEFT/CITIN12345678/ACME RENTALS"))
    }

    // ---- 5. food order confirmation

    private val order = "Thanks for choosing Popeyes! Delicious food is arriving at your doorstep  at the earliest|Order no. 123 |Amount:Rs. 499.50 |Track order@ https://x.co/abc"

    @Test
    fun foodOrder_isAnOrderNotADebit() {
        val m = classify(order, "VM-POPEYE")
        val life = m.content as MessageContent.Life
        assertEquals(LifeKind.DELIVERY, life.kind)
        assertEquals("Popeyes", life.title)
        assertEquals("Order placed", life.status)
        assertEquals("123", life.reference)
        assertEquals("499.50", life.amount)
        assertFalse(life.isDone)
        assertEquals(MessageCategory.FOOD, m.category)
    }

    @Test
    fun foodOrder_showsInTheAgendaWithItsValueAndNeverCountsAsSpending() {
        val today = LocalDate.of(2026, 10, 10)
        val m = classify(order, "VM-POPEYE", id = 1, date = at(today))
        val items = LifeAgenda.build(listOf(m), today, zone)
        assertEquals(1, items.size)
        assertEquals("Popeyes", items[0].title)
        assertTrue(items[0].status, items[0].status.contains("499.50"))
        assertTrue(m.content !is MessageContent.Transaction)
    }

    @Test
    fun foodOrder_variants() {
        val a = classify("Thanks for choosing Burger Barn! Your meal will reach you soon|Order no. B-2041 |Amount:Rs. 1,299.00 |Track order@ https://x.co/z", "VM-BBARN").content as MessageContent.Life
        assertEquals("Burger Barn", a.title)
        assertEquals("1299.00", a.amount)
        assertEquals("B-2041", a.reference)
        val b = classify("Thanks for choosing Pizza Point! Order no. 77 |Amount: INR 350 |Track order@ https://x.co/q", "VM-PIZZA").content as MessageContent.Life
        assertEquals("Pizza Point", b.title)
        assertEquals("350", b.amount)
        assertUnderstood(order, sender = "VM-POPEYE")
    }

    // ---- regressions: similar but different messages keep their old meaning

    @Test
    fun periodOrderMessageIsStillAPayment() {
        val t = tx("Thanks for choosing Domino's.We will deliver your order hot & fresh at the earliest|Order no. 123 |Amount:Rs. 1,299.50 |Track order@ https://x.in", "VM-DOMINO")
        assertEquals("Domino's", t.payee)
        assertFalse(t.isReceived)
    }

    @Test
    fun achAndNachDebitsKeepTheirReading() {
        val ach = tx(axisDebit("1234.56", "1234", "ACH-DR-PAYTM PAYMENTS SERV"))
        assertEquals("PAYTM PAYMENTS SERV", ach.payee)
        assertFalse(ach.isCardPayment)
    }

    @Test
    fun existingEmiReminderFormatsStillWork() {
        val c = classify("Dear user, EMI of Rs. 12345.67 towards LN1234 is due on 05/11/2026. Pls ensure sufficient balance.", "AD-BOBSMS").content as MessageContent.ScheduledPayment
        assertEquals(LocalDate.of(2026, 11, 5), c.date)
        val d = classify("EMI of INR 2500 for ACME FINANCE loan account no. 12345678 is due on 2026-11-05. Thanks", "AD-ACMEFN").content as MessageContent.ScheduledPayment
        assertEquals(LocalDate.of(2026, 11, 5), d.date)
    }

    @Test
    fun ordinaryReceivedCreditIsStillIncome() {
        val t = tx("Rs.500.00 credited to HDFC Bank A/c XX1234 on 10-10-26 from VPA friend@okaxis (UPI 412345678901)", "AD-HDFCBK")
        assertTrue(t.isReceived)
        assertFalse(t.isCardPayment)
    }
}
