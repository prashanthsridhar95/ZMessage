package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.insights.RefundStage
import com.zyke.message.domain.insights.RefundTracker
import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A refund goes initiated, processed, arrived; the texts for one refund are one row, however they arrive. */
class RefundLifecycleTest {
    private val zone = ZoneId.of("UTC")
    private val today = LocalDate.of(2026, 11, 14)
    private val now = today.atTime(12, 0).atZone(zone).toInstant()
    private val classifier = MessageClassifier()
    private var nextId = 1L

    private val d8 = LocalDate.of(2026, 11, 8)
    private val d13 = LocalDate.of(2026, 11, 13)

    private fun sms(body: String, on: LocalDate, hour: Int = 10, sender: String = "AD-ZEPTOO"): ClassifiedMessage =
        classifier.classify(SmsMessage(nextId++, sender, body, on.atTime(hour, 0).atZone(zone).toInstant().toEpochMilli(), true))

    private fun initiated(amount: String = "176.00", order: String = "NUGTJCORO38008", on: LocalDate = d8, hour: Int = 22) =
        sms("Dear user, refund of Rs $amount for your Zepto order $order has been initiated. It should reflect in your account within 5–7 business days!", on, hour)

    private fun processed(amount: String = "176.00", order: String = "NUGTJCORO38008", ref: String = "667812523611", on: LocalDate = d8, hour: Int = 22) =
        sms("Dear user, Refund of Rs $amount has been processed for your Zepto order $order. Refund Ref. num. $ref. You can check details on Refunds page in the app.", on, hour)

    private fun credit(amount: String, on: LocalDate, narration: String = "UPI/ZEPTO/998877", account: String = "XX5765") =
        sms("Rs.$amount credited to HDFC Bank A/c $account on ${"%02d".format(on.dayOfMonth)}-${"%02d".format(on.monthValue)}-26 by $narration", on, 9, "AD-HDFCBK")

    private fun build(vararg m: ClassifiedMessage, received: Map<String, LocalDate> = emptyMap()) =
        RefundTracker.build(m.toList().sortedByDescending { it.message.date }, now, zone, received)

    @Test
    fun sampleTexts_areNoticesNotMoney() {
        for (m in listOf(initiated(), processed())) {
            assertTrue(m.content.toString(), m.content is MessageContent.Notice)
            assertEquals(NoticeKind.REFUND_PENDING, (m.content as MessageContent.Notice).kind)
            assertTrue(Counting.of(m) is Counted.NotCounted)
        }
    }

    @Test
    fun threeSampleTexts_areTwoRefunds() {
        val a = initiated(hour = 22)
        val b = processed(hour = 22)
        val c = processed("46.00", "JVRKCORM37886", "668339461488", on = d13, hour = 19)
        val items = build(a, b, c)
        assertEquals(2, items.size)
        val cItem = items.first()
        assertEquals(d13, cItem.startedOn)
        assertEquals(RefundStage.PROCESSED, cItem.stage)
        assertEquals("JVRKCORM37886", cItem.orderId)
        assertEquals("668339461488", cItem.refundRef)
        val ab = items.last()
        assertEquals(d8, ab.startedOn)
        assertEquals(d8, ab.processedOn)
        assertEquals(RefundStage.PROCESSED, ab.stage)
        assertEquals("NUGTJCORO38008", ab.orderId)
        assertEquals("667812523611", ab.refundRef)
        assertEquals(0, BigDecimal("176").compareTo(ab.amount))
        assertEquals(2, ab.messages.size)
        assertEquals(b.id, ab.message.id)
    }

    @Test
    fun stageProgression_doesNotDependOnMessageOrder() {
        val a = initiated(on = d8, hour = 10)
        val b = processed(on = d8, hour = 12)
        // Processed first in the list, and even dated before the "initiated" one.
        val flipped = RefundTracker.build(listOf(b, a), now, zone)
        val normal = RefundTracker.build(listOf(a, b), now, zone)
        assertEquals(1, flipped.size)
        assertEquals(RefundStage.PROCESSED, flipped.single().stage)
        assertEquals(normal.single().startedOn, flipped.single().startedOn)
        val onlyInitiated = build(a).single()
        assertEquals(RefundStage.INITIATED, onlyInitiated.stage)
        assertNull(onlyInitiated.processedOn)
    }

    @Test
    fun withoutIds_sameMerchantAmountAndDay_isOneRefund() {
        val a = sms("Refund of Rs 250.00 has been initiated for your Zepto order.", d8, 9)
        val b = sms("Refund of Rs 250.00 has been processed for your Zepto order.", d8, 9)
        assertEquals(1, build(a, b).size)
        val later = sms("Refund of Rs 250.00 has been processed for your Zepto order.", d8.plusDays(2), 9)
        assertEquals(1, build(a, later).size)
        val far = sms("Refund of Rs 250.00 has been processed for your Zepto order.", d8.plusDays(5), 9)
        assertEquals(2, build(a, far).size)
        val other = sms("Refund of Rs 251.00 has been processed for your Zepto order.", d8, 9)
        assertEquals(2, build(a, other).size)
    }

    @Test
    fun twoDifferentOrdersWithTheSameAmount_stayTwo() {
        val items = build(initiated(order = "AAAAAAAA11111"), initiated(order = "BBBBBBBB22222"), processed(order = "BBBBBBBB22222", ref = "123456789012"))
        assertEquals(2, items.size)
        assertEquals(setOf(RefundStage.INITIATED, RefundStage.PROCESSED), items.map { it.stage }.toSet())
    }

    @Test
    fun wordingVariants_areUnderstood() {
        for (text in listOf(
            "Your refund of Rs 100.00 for order ABCD1234567 is processed.",
            "Refund processed for order ABCD1234567 of Rs 100.00",
            "Refund of Rs 100.00 for order ABCD1234567 processed successfully",
            "Zepto has processed a refund of Rs 100.00 for order ABCD1234567"
        )) {
            val m = sms(text, d8)
            assertEquals(text, RefundStage.PROCESSED, RefundTracker.stageOf(text))
            assertEquals(text, "Refund processed", RefundTracker.typeLabel(m))
            assertEquals(text, 1, build(m).size)
        }
        for (text in listOf(
            "Your refund of Rs 100.00 for order ABCD1234567 is initiated.",
            "We have initiated a refund of Rs 100.00 for order ABCD1234567",
            "Refund of Rs 100.00 initiated for order ABCD1234567",
            "Refund of Rs 100.00 has been initiated and will be processed in 3 days"
        )) {
            val m = sms(text, d8)
            assertEquals(text, RefundStage.INITIATED, RefundTracker.stageOf(text))
            assertEquals(text, "Refund initiated", RefundTracker.typeLabel(m))
            assertEquals(text, 1, build(m).size)
        }
        assertNull(RefundTracker.typeLabel(credit("100.00", d8)))
    }

    @Test
    fun idParsing() {
        assertEquals("NUGTJCORO38008", RefundTracker.orderIdOf("for your Zepto order NUGTJCORO38008 has"))
        assertEquals("ABC123456789", RefundTracker.orderIdOf("order id: ABC123456789."))
        assertNull(RefundTracker.orderIdOf("your order has been delivered"))
        assertNull(RefundTracker.orderIdOf("order 4021"))
        assertEquals("667812523611", RefundTracker.refundRefOf("Refund Ref. num. 667812523611."))
        assertEquals("667812523611", RefundTracker.refundRefOf("Refund Ref 667812523611"))
        assertNull(RefundTracker.refundRefOf("Ref. num. 12345"))
    }

    @Test
    fun statusLines() {
        val fmt: (LocalDate) -> String = { "${it.dayOfMonth} Nov" }
        val i = build(initiated()).single()
        assertEquals("Initiated 8 Nov · expected in 5–7 days", i.statusLine(fmt))
        val p = build(initiated(), processed()).single()
        assertEquals("Processed 8 Nov · should reach you soon", p.statusLine(fmt))
        val arrived = build(initiated(), processed(), credit("176.00", d8.plusDays(4))).single()
        assertEquals("Arrived 12 Nov", arrived.statusLine(fmt))
    }

    @Test
    fun arrival_viaACreditNamingTheMerchant() {
        val credit = credit("176.00", d8.plusDays(4))
        assertTrue(credit.content.toString(), (credit.content as? MessageContent.Transaction)?.isReceived == true)
        val item = build(initiated(), credit).single()
        assertFalse(item.isPending)
        assertEquals(d8.plusDays(4), item.arrivedOn)
        assertEquals(RefundStage.ARRIVED, item.stage)
        assertEquals(credit.id, item.credit?.id)
    }

    @Test
    fun arrival_viaTheCompanyNameOrTheWordRefund_evenBeforeProcessed() {
        assertFalse(build(initiated(), credit("176.00", d8.plusDays(3), "IMPS/KIRANAKART TECHNOLOGIES/778899")).single().isPending)
        assertFalse(build(initiated(), credit("176.00", d8.plusDays(3), "NEFT-REFUND-ORDER")).single().isPending)
    }

    @Test
    fun arrival_viaAnUnexplainedSameAmountCredit_onlyAfterProcessed() {
        val plain = credit("176.00", d8.plusDays(3), "UPI/412345678901/rahul@okbank")
        assertTrue(build(initiated(), plain).single().isPending)
        val item = build(initiated(), processed(), plain).single()
        assertFalse(item.isPending)
        assertEquals(d8.plusDays(3), item.arrivedOn)
    }

    @Test
    fun noArrival_forADifferentAmountOutsideTheWindowOrBeforeTheRefund() {
        val a = initiated(); val b = processed()
        assertTrue(build(a, b, credit("176.50", d8.plusDays(2))).single().isPending)
        assertTrue(build(a, b, credit("175.99", d8.plusDays(2), "UPI/ZEPTO")).single().isPending)
        assertTrue(build(a, b, credit("176.00", d8.plusDays(12))).single().isPending)
        assertTrue(build(a, b, credit("176.00", d8.minusDays(1))).single().isPending)
        // Salary-like and large: never.
        val big = initiated("50000.00", "BIGORDER12345"); val bigDone = processed("50000.00", "BIGORDER12345")
        assertTrue(build(big, bigDone, credit("50000.00", d8.plusDays(2), "NEFT-ACME SALARY")).single().isPending)
    }

    @Test
    fun ambiguity_twoCreditsForOneRefund_isSkipped_butTwoForTwoPairOldestFirst() {
        val a = initiated(); val b = processed()
        val c1 = credit("176.00", d8.plusDays(2), "UPI/1/x@y"); val c2 = credit("176.00", d8.plusDays(3), "UPI/2/z@y")
        assertTrue(build(a, b, c1, c2).single().isPending)

        val a2 = initiated(order = "SECONDORD9999", on = d8.plusDays(1)); val b2 = processed(order = "SECONDORD9999", ref = "999888777666", on = d8.plusDays(1))
        val items = build(a, b, a2, b2, c1, c2)
        assertEquals(2, items.size)
        assertTrue(items.none { it.isPending })
        val older = items.first { it.startedOn == d8 }
        assertEquals(c1.id, older.credit?.id)
        assertEquals(c2.id, items.first { it.startedOn == d8.plusDays(1) }.credit?.id)
    }

    @Test
    fun aCreditIsUsedByOneRefundOnly() {
        val a = initiated(); val b = processed()
        val a2 = initiated(order = "SECONDORD9999", on = d8.plusDays(1))
        val items = build(a, b, a2, credit("176.00", d8.plusDays(4)))
        assertEquals(1, items.count { !it.isPending })
        assertEquals(d8, items.first { !it.isPending }.startedOn)
    }

    @Test
    fun manualReceived_marksAndUndoesByKey() {
        val a = initiated(); val b = processed()
        val key = build(a, b).single().key
        val marked = build(a, b, received = mapOf(key to today.minusDays(1))).single()
        assertFalse(marked.isPending)
        assertTrue(marked.markedReceived)
        assertEquals(today.minusDays(1), marked.arrivedOn)
        assertTrue(build(a, b, received = emptyMap()).single().isPending)
        // A found credit wins over the hand mark.
        val both = build(a, b, credit("176.00", d8.plusDays(2)), received = mapOf(key to today)).single()
        assertFalse(both.markedReceived)
        assertEquals(d8.plusDays(2), both.arrivedOn)
    }

    @Test
    fun noticesNeverCount_andMatchedCreditsKeepCountingAsBefore() {
        val a = initiated(); val b = processed(); val credit = credit("176.00", d8.plusDays(2))
        assertTrue(Counting.of(a) is Counted.NotCounted)
        assertTrue(Counting.of(b) is Counted.NotCounted)
        // Linked visually only: the bank credit is still income as Counting says (documented in RefundTracker).
        assertTrue(Counting.of(credit) is Counted.Income)
        assertEquals(1, build(a, b, credit).size)
    }

    @Test
    fun oldPendingAndOldArrivedRefunds_leaveTheList() {
        assertTrue(build(initiated(on = today.minusDays(40))).isEmpty())
        val a = initiated(on = today.minusDays(20)); val c = credit("176.00", today.minusDays(12), "UPI/ZEPTO")
        assertTrue(build(a, c).isEmpty())
    }
}
