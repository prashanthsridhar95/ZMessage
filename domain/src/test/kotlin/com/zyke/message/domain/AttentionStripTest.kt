package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.AttentionBuilder
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Why a card bill that is due soon might be missing from Home's "Needs your attention". */
class AttentionStripTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = Instant.parse("2026-10-31T06:00:00Z")
    private val day = 86_400_000L
    private var id = 1L

    private fun msg(body: String, daysAgo: Long, sender: String) =
        classifier.classify(SmsMessage(id++, sender, body, now.toEpochMilli() - daysAgo * day, true))

    private val hdfc = "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:03-11-2026 pay https://hdfcbk.io/x"
    private val hdfcOther = "Payment of Rs.12,345.00 received on your HDFC Bank Credit Card XX9999. Thank you"

    private fun bills(vararg ms: ClassifiedMessage, dismissed: Set<String> = emptySet(), at: Map<String, Long> = emptyMap()) =
        AttentionBuilder.build(ms.toList().sortedByDescending { it.message.date }, dismissed, now, zone, at)
            .filterIsInstance<AttentionItem.BillDue>()

    @Test
    fun billIsClassified() {
        assertTrue(msg(hdfc, 2, "AD-HDFCBK").content is MessageContent.CardBill)
    }

    @Test
    fun anotherCardOfTheSameBank_beingPaid_doesNotSettleThisBill() {
        val b = msg(hdfc, 2, "AD-HDFCBK")
        assertEquals(1, bills(b, msg(hdfcOther, 1, "AD-HDFCBK")).size)
    }

    @Test
    fun anAxisAccountPayingAnotherCard_doesNotSettleAnAxisCardBill() {
        val b = msg("Axis Bank Credit Card XX4321 Statement: Total due Rs.9,953.00 Min due Rs.500.00 Due by:03-11-2026", 2, "AX-AXISBK")
        val pay = msg("Rs 9,953.00 debited from Axis A/c XX5678 on 30-10-26. CreditCard Payment XX 9999", 1, "AX-AXISBK")
        assertEquals(1, bills(b, pay).size)
    }

    @Test
    fun aShortAccountNumber_isNeverSettled() {
        val b = msg("HDFC Bank Credit Card XX56 Statement: Total due amt: Rs.9,953.00 Min due amt: Rs.500.00 Due by:03-11-2026", 2, "AD-HDFCBK")
        if (b.content is MessageContent.CardBill) assertEquals(1, bills(b, msg("Rs 9,953.00 debited towards credit card payment", 1, "AX-BANK")).size)
    }

    @Test
    fun lastMonthsDismissedBill_doesNotHideThisMonths() {
        val old = msg(hdfc.replace("03-11-2026", "03-10-2026"), 32, "AD-HDFCBK")
        val new = msg(hdfc, 2, "AD-HDFCBK")
        val item = bills(old, new).single()
        val oldKey = "bill-HDFC-1234-2026-10-03"
        assertTrue(item.key != oldKey)
        assertEquals(1, bills(old, new, dismissed = setOf(oldKey)).size)
    }

    @Test
    fun aBillFromAMutedSender_stillShows_butMarketingDoesNot() {
        val b = msg(hdfc, 2, "AD-HDFCBK").copy(isPromotional = true, isMuted = true)
        val eligible = AttentionBuilder.eligible(listOf(b))
        assertEquals(1, AttentionBuilder.build(eligible, emptySet(), now, zone).size)
        assertTrue(AttentionBuilder.eligible(listOf(b.copy(content = MessageContent.Generic))).isEmpty())
    }

    @Test
    fun whenNothingIsLeft_theLogSaysWhichStepRemovedTheBill() {
        val lines = mutableListOf<String>()
        com.zyke.message.domain.diag.DiagLog.sink = { tag, m -> if (tag == "attention") lines += m }
        try {
            val b = msg(hdfc, 2, "AD-HDFCBK")
            val key = bills(b).single().key
            assertTrue(lines.isEmpty())
            AttentionBuilder.build(listOf(b), setOf(key), now, zone)
            assertTrue(lines.toString(), lines.single().contains("dismissed 1") && lines.single().contains("final 0"))
        } finally {
            com.zyke.message.domain.diag.DiagLog.sink = null
        }
    }
}
