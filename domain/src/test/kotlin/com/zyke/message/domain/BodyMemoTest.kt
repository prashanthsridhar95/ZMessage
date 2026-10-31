package com.zyke.message.domain

import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.model.BankName
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import com.zyke.message.domain.util.BodyMemo
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyMemoTest {
    private val zone: ZoneId = ZoneId.of("UTC")
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun sameBodyIsWorkedOutOnce_changedBodyOrReusedIdIsWorkedOutAgain() {
        val memo = BodyMemo { body -> body.length }
        assertEquals(5, memo.of(1, "hello"))
        assertEquals(5, memo.of(1, String("hello".toCharArray())))
        assertEquals(1L, memo.computations)
        assertEquals(3, memo.of(1, "abc"))
        assertEquals(2L, memo.computations)
        assertEquals(3, memo.of(2, "abc"))
        assertEquals(3L, memo.computations)
    }

    @Test
    fun memoStaysWithinItsLimit() {
        val memo = BodyMemo(limit = 10) { it }
        for (i in 1L..100L) memo.of(i, "b$i")
        assertEquals("b100", memo.of(100, "b100"))
        assertEquals(100L, memo.computations)
    }

    @Test
    fun overlongBodiesAreCutBeforeScanning() {
        val long = "x".repeat(BodyMemo.MAX_SCANNED_CHARS * 3)
        assertEquals(BodyMemo.MAX_SCANNED_CHARS, BodyMemo.bounded(long).length)
        assertEquals("short", BodyMemo.bounded("short"))
    }

    private fun bill() = ClassifiedMessage(
        SmsMessage(1, "AD-HDFCBK", "Your HDFC card bill", 0L, true), MessageCategory.OTHERS,
        Merchant("HDFC", MerchantLogo.HDFC, MessageCategory.OTHERS),
        MessageContent.CardBill(BankName.HDFC, "5678", "12000", "600", LocalDate.of(2026, 11, 5))
    )

    private fun other(id: Long, body: String, at: Long) = ClassifiedMessage(
        SmsMessage(id, "AD-BANK", body, at, true), MessageCategory.OTHERS,
        Merchant("X", MerchantLogo.UPI, MessageCategory.OTHERS),
        MessageContent.Generic
    )

    @Test
    fun billPaymentIsFoundAsBefore_andBodiesAreReadOnceAcrossRepeatedChecks() {
        val b = bill()
        val content = b.content as MessageContent.CardBill
        val noise = (2L..200L).map { other(it, "Rs 10 spent at shop $it on card 9999", it * day / 100) }
        val paid = other(500, "Payment of Rs 12,000.00 received towards your credit card ending 5678. Thank you", 10 * day)
        val messages = listOf(paid) + noise.reversed() + b
        val before = BillPayments.factComputationsForTest()
        val first = BillPayments.find(b, content, messages, zone)
        assertNotNull(first)
        assertEquals(BigDecimal("12000.00"), first!!.amount)
        assertEquals(500L, first.messageId)
        val afterFirst = BillPayments.factComputationsForTest()
        repeat(5) { assertEquals(first, BillPayments.find(b, content, messages, zone)) }
        assertEquals(afterFirst, BillPayments.factComputationsForTest())
        assertTrue(afterFirst - before <= messages.size)
    }

    @Test
    fun aPaymentThatWillHappenIsNotAPayment() {
        val b = bill()
        val content = b.content as MessageContent.CardBill
        val later = other(2, "Payment of Rs 12,000.00 will be debited from your credit card ending 5678 on due date", day)
        assertNull(BillPayments.find(b, content, listOf(later, b), zone))
    }
}
