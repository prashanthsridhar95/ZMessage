package com.zyke.message.domain

import com.zyke.message.domain.accounts.Accounts
import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.export.Exports
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.ledger.ManualEntry
import com.zyke.message.domain.ledger.ManualEntryRepository
import com.zyke.message.domain.ledger.ManualKind
import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.ledger.SetAsideRepository
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.ledger.TransferLink
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.repository.SmsRepository
import com.zyke.message.domain.usecase.ObserveMessagesUseCase
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LedgerTest {
    private val zone = ZoneId.systemDefault()
    private val classifier = MessageClassifier()

    private class Sms(private val messages: List<SmsMessage>) : SmsRepository {
        override fun observeInbox(): Flow<List<SmsMessage>> = flowOf(messages)
    }

    private class Entries(initial: List<ManualEntry>) : ManualEntryRepository {
        override val entries: StateFlow<List<ManualEntry>> = MutableStateFlow(initial)
        override fun save(entry: ManualEntry) = Unit
        override fun remove(id: Long) = Unit
        override fun nextId() = 100L
    }

    private class Asides(initial: SetAsides) : SetAsideRepository {
        override val state: StateFlow<SetAsides> = MutableStateFlow(initial)
        override fun exclude(id: Long, excluded: Boolean) = Unit
        override fun link(outId: Long?, inId: Long?) = Unit
        override fun unlink(id: Long) = Unit
        override fun dismissSuggestion(key: String) = Unit
    }

    private fun debit(id: Long, amount: String, last4: String, at: Long = 1_793_000_000_000L) =
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank A/C *$last4\nTo SOMEONE ELSE\nOn 30/10/26\nRef $id", at, true)

    private fun credit(id: Long, amount: String, last4: String, at: Long = 1_793_000_000_000L) =
        SmsMessage(id, "AD-ICICIB", "Rs $amount credited to your ICICI Bank A/c XX$last4 on 30-Oct-26 by UPI-Ref $id. Avl Bal Rs 50,000.00", at, true)

    private fun read(inbox: List<SmsMessage>, entries: List<ManualEntry> = emptyList(), asides: SetAsides = SetAsides()) = runBlocking {
        ObserveMessagesUseCase(
            Sms(inbox), classifier, Dispatchers.Unconfined, manualEntries = Entries(entries), setAsides = Asides(asides)
        )().first()
    }

    @Test
    fun aHandMadeEntryIsReadLikeAPayment_andMakesNoAccount() {
        val entry = ManualEntry(1, ManualKind.EXPENSE, BigDecimal("250"), "Auto ride", MessageCategory.TRAVEL, LocalDate.of(2026, 10, 30))
        val messages = read(emptyList(), listOf(entry))
        val message = messages.single()
        val tx = message.content as MessageContent.Transaction

        assertTrue(ManualEntries.isManual(message))
        assertFalse(tx.isReceived)
        assertEquals("250", tx.amount)
        assertEquals(MessageCategory.TRAVEL, message.category)
        assertEquals(0, Accounts.build(messages, emptyList(), Instant.now(), zone).accounts.size)
    }

    @Test
    fun anIncomeEntryIsAReceivedCredit_andATransferIsSetAside() {
        val income = ManualEntry(1, ManualKind.INCOME, BigDecimal("5000"), "Freelance", MessageCategory.OTHERS, LocalDate.of(2026, 10, 30))
        val transfer = ManualEntry(2, ManualKind.TRANSFER, BigDecimal("2000"), "To savings", MessageCategory.OTHERS, LocalDate.of(2026, 10, 30))
        val messages = read(emptyList(), listOf(income, transfer))
        val byTitle = messages.associateBy { it.merchant.name }

        assertTrue((byTitle.getValue("Freelance").content as MessageContent.Transaction).isReceived)
        assertEquals(SetAside.TRANSFER, byTitle.getValue("To savings").setAside)
        assertTrue((byTitle.getValue("To savings").content as MessageContent.Transaction).isCardPayment)
    }

    @Test
    fun aPaymentLeftOutOfTheTotals_isFlagged_andCountsAsInternal() {
        val messages = read(listOf(debit(1, "540.00", "5765")), asides = SetAsides(excluded = setOf(1L)))
        val message = messages.single()
        assertEquals(SetAside.EXCLUDED, message.setAside)
        assertTrue((message.content as MessageContent.Transaction).isCardPayment)
    }

    @Test
    fun aLinkedTransfer_setsBothLegsAside() {
        val messages = read(
            listOf(debit(1, "5000.00", "5765"), credit(2, "5,000.00", "4321")),
            asides = SetAsides(transfers = listOf(TransferLink(1, 2)))
        )
        assertEquals(setOf(SetAside.TRANSFER), messages.map { it.setAside }.toSet())
    }

    @Test
    fun aDebitAndAMatchingCreditToAnotherAccount_areOfferedAsATransfer_butNotWhenDismissed() {
        val messages = read(listOf(debit(1, "5000.00", "5765"), credit(2, "5,000.00", "4321", at = 1_793_000_000_000L + 60_000)))
        val queue = ReviewQueues.build(messages, SetAsides(), zone)
        assertEquals(1, queue.transfers.size)
        assertEquals(1L, queue.transfers.single().debit.id)

        assertEquals(0, ReviewQueues.build(messages, SetAsides(dismissed = setOf("1:2")), zone).transfers.size)
        // The same account on both sides, or a different amount, is no transfer.
        val sameAccount = read(listOf(debit(1, "5000.00", "5765"), credit(2, "5,000.00", "5765")))
        assertEquals(0, ReviewQueues.build(sameAccount, SetAsides(), zone).transfers.size)
        val other = read(listOf(debit(1, "5000.00", "5765"), credit(2, "4,000.00", "4321")))
        assertEquals(0, ReviewQueues.build(other, SetAsides(), zone).transfers.size)
    }

    @Test
    fun theCsvHasOneRowPerTransaction_notesTheTotalsTreatment_andIsSafeInASpreadsheet() {
        val entry = ManualEntry(1, ManualKind.EXPENSE, BigDecimal("250"), "=SUM(A1)", MessageCategory.FOOD, LocalDate.of(2026, 10, 30), note = "lunch, with Ravi")
        val messages = read(listOf(debit(1, "540.00", "5765")), listOf(entry), SetAsides(excluded = setOf(1L)))
        val lines = Exports.transactionsCsv(messages, zone).trim().lines()

        assertEquals(3, lines.size)
        assertTrue(lines[0].startsWith("date,time,direction,amount"))
        assertTrue(lines.any { it.contains("no: left out of totals") })
        assertTrue(lines.any { it.contains("'=SUM(A1)") && it.contains("\"lunch, with Ravi\"") && it.contains("entered by hand") })
        assertFalse(lines.any { it.contains("Ref 1") })
        assertNull(null)
    }
}
