package com.zyke.message.domain

import com.zyke.message.domain.ledger.ReviewQueues
import com.zyke.message.domain.ledger.SetAsides
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReviewQueueExtrasTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val now: Instant = Instant.parse("2026-10-31T10:00:00Z")
    private val nowMs = now.toEpochMilli()
    private val day = 24L * 60 * 60 * 1000

    private fun tx(
        id: Long, amount: String, at: Long, account: String = "1234", payee: String = "SHOP", received: Boolean = false,
        category: MessageCategory = MessageCategory.OTHERS, overridden: Boolean = false
    ) = ClassifiedMessage(
        SmsMessage(id, "AD-BANK", "body $id", at, true), category,
        Merchant(payee, MerchantLogo.UPI, category),
        MessageContent.Transaction(BankName.HDFC, payee, amount, account, received, false),
        categoryOverridden = overridden
    )

    private fun bill(id: Long, due: LocalDate, at: Long = nowMs - 20 * day) = ClassifiedMessage(
        SmsMessage(id, "AD-HDFCBK", "Your HDFC card bill", at, true), MessageCategory.OTHERS,
        Merchant("HDFC", MerchantLogo.HDFC, MessageCategory.OTHERS),
        MessageContent.CardBill(BankName.HDFC, "5678", "12000", "600", due)
    )

    private fun build(messages: List<ClassifiedMessage>, asides: SetAsides = SetAsides(), dismissed: Set<String> = emptySet()) =
        ReviewQueues.build(messages, asides, zone, now, dismissed)

    @Test
    fun twoMessagesForTheSamePayment_areOfferedAsDuplicates_untilDismissed() {
        val a = tx(1, "450.00", nowMs - 3_600_000)
        val b = tx(2, "450.00", nowMs - 3_600_000 + 120_000, payee = "OTHER", account = "1234")
        val queue = build(listOf(b, a))
        assertEquals(1, queue.duplicates.size)
        assertEquals(1L, queue.duplicates.single().first.id)
        assertEquals("dup:1:2", queue.duplicates.single().key)
        assertEquals(1, queue.count)
        assertTrue(build(listOf(a, b), SetAsides(dismissed = setOf("dup:1:2"))).duplicates.isEmpty())
    }

    @Test
    fun differentAmountsDirectionsOrFarApartInTime_areNotDuplicates() {
        val a = tx(1, "450.00", nowMs - 3_600_000)
        assertTrue(build(listOf(a, tx(2, "451.00", nowMs - 3_600_000))).duplicates.isEmpty())
        assertTrue(build(listOf(a, tx(2, "450.00", nowMs - 3_600_000, received = true))).duplicates.isEmpty())
        assertTrue(build(listOf(a, tx(2, "450.00", nowMs - 3_600_000 + 2 * 3_600_000))).duplicates.isEmpty())
        assertTrue(build(listOf(a, tx(2, "450.00", nowMs - 3_600_000, account = "9999", payee = "ELSE"))).duplicates.isEmpty())
    }

    @Test
    fun anOverdueBillWithNoPayment_isAsked_andGoesWhenMarkedPaidOrPaid() {
        val b = bill(10, LocalDate.of(2026, 10, 25))
        val queue = build(listOf(b))
        val item = queue.bills.single()
        assertEquals(LocalDate.of(2026, 10, 25), item.dueDate)
        assertEquals("paid-bill-HDFC|5678-2026-10-25", item.markedPaidKey)
        assertEquals("bill-HDFC-5678-2026-10-25", item.attentionKey)
        assertTrue(build(listOf(b), dismissed = setOf(item.markedPaidKey)).bills.isEmpty())
        assertTrue(build(listOf(b), dismissed = setOf(item.attentionKey)).bills.isEmpty())
        // Not yet due, or long overdue: not asked.
        assertTrue(build(listOf(bill(10, LocalDate.of(2026, 11, 5)))).bills.isEmpty())
        assertTrue(build(listOf(bill(10, LocalDate.of(2026, 7, 1)))).bills.isEmpty())
    }

    @Test
    fun aLargeDebit_isAskedAboutOnlyWithEnoughHistory_andNotWhenCategorisedOrConfirmed() {
        val usual = (1..10L).map { tx(it, "200.00", nowMs - (it + 40) * day, account = "1", payee = "P$it") }
        val big = tx(50, "1500.00", nowMs - 2 * day, account = "2", payee = "BIGSHOP")
        assertEquals(listOf(50L), build(usual + big).unusual.map { it.id })
        assertTrue(build(usual.take(5) + big).unusual.isEmpty())
        assertTrue(build(usual + tx(50, "1500.00", nowMs - 2 * day, category = MessageCategory.SHOPPING, overridden = true)).unusual.isEmpty())
        assertTrue(build(usual + big, SetAsides(dismissed = setOf(ReviewQueues.unusualKey(big)))).unusual.isEmpty())
        // 4x the median is not unusual enough.
        assertTrue(build(usual + tx(50, "800.00", nowMs - 2 * day, account = "2", payee = "X")).unusual.isEmpty())
    }
}
