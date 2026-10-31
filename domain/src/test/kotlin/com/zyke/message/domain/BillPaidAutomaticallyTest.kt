package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.feed.AttentionBuilder
import com.zyke.message.domain.feed.AttentionItem
import com.zyke.message.domain.model.SmsMessage
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillPaidAutomaticallyTest {
    private val classifier = MessageClassifier()
    private val zone = ZoneId.systemDefault()
    private val now = Instant.parse("2026-10-31T06:00:00Z")
    private val day = 86_400_000L
    private var id = 1L

    private val bill = "HDFC Bank Credit Card XX1234 Statement: Total due amt: Rs.12,345.00 Min due amt: Rs.617.00 Due by:10-11-2026 pay https://hdfcbk.io/x"

    private fun msg(body: String, daysAfterBill: Long, sender: String = "AD-HDFCBK") =
        classifier.classify(SmsMessage(id++, sender, body, now.toEpochMilli() - 8 * day + daysAfterBill * day, true))

    private fun needingAttention(vararg later: Pair<String, Long>): List<AttentionItem.BillDue> {
        val messages = listOf(msg(bill, 0)) + later.map { (body, days) -> msg(body, days, "AX-BANK") }
        return AttentionBuilder.build(messages.sortedByDescending { it.message.date }, emptySet(), now, zone, emptyMap()).filterIsInstance<AttentionItem.BillDue>()
    }

    @Test
    fun theBillStillNeedsAttentionWhenNothingPaidIt() {
        assertEquals(1, needingAttention().size)
    }

    @Test
    fun aDebitTowardsCreditCardPayment_forTheWholeBill_namingNoCard_settlesIt() {
        val settled = needingAttention("Rs 12,345.00 debited from A/c XX5678 on 30-10-26 towards credit card payment. Avl Bal Rs 80,000.00" to 5)
        assertTrue(settled.toString(), settled.isEmpty())
    }

    @Test
    fun aPaymentThroughCred_forTheWholeBill_settlesIt() {
        val settled = needingAttention("Rs 12345.00 debited from A/c XX5678 on 30-10-26 and credited to cred.club@axisb (UPI Ref 6123456789). Avl Bal Rs 80,000.00" to 5)
        assertTrue(settled.toString(), settled.isEmpty())
    }

    @Test
    fun aDifferentAmountNamingNoCard_doesNotSettleIt() {
        assertEquals(1, needingAttention("Rs 500.00 debited from A/c XX5678 on 30-10-26 towards credit card payment" to 5).size)
        assertEquals(1, needingAttention("Rs 12,345.00 debited from A/c XX5678 on 30-10-26 to SOME SHOP (UPI Ref 6123456789)" to 5).size)
    }

    @Test
    fun aPartPaymentStillHasToNameTheCard() {
        assertEquals(1, needingAttention("Rs 5,000.00 debited from A/c XX5678 on 30-10-26 towards credit card payment" to 5).size)
        assertTrue(needingAttention("Rs 5,000.00 debited from A/c XX5678 towards HDFC Bank Credit Card XX1234 payment" to 5).isEmpty())
    }

    @Test
    fun aPaymentBeforeTheBillWasMade_doesNotSettleIt() {
        assertEquals(1, needingAttention("Rs 12,345.00 debited from A/c XX5678 towards credit card payment" to -3).size)
    }
}
