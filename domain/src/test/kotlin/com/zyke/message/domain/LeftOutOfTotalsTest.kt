package com.zyke.message.domain

import com.zyke.message.domain.classification.MessageClassifier
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.SpendingLimits
import com.zyke.message.domain.ledger.SetAside
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.SmsMessage
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test

class LeftOutOfTotalsTest {
    private val zone = ZoneId.systemDefault()
    private val now = Instant.parse("2026-10-20T06:00:00Z")
    private val classifier = MessageClassifier()

    private fun spend(id: Long, amount: String) = classifier.classify(
        SmsMessage(id, "AD-HDFCBK", "Sent Rs.$amount\nFrom HDFC Bank A/C *5765\nTo SHOP $id\nOn 20/10/26\nRef $id", now.toEpochMilli() - 3_600_000, true)
    ).copy(category = MessageCategory.FOOD)

    private fun leftOut(message: com.zyke.message.domain.model.ClassifiedMessage) =
        message.copy(content = (message.content as MessageContent.Transaction).copy(isCardPayment = true), setAside = SetAside.EXCLUDED)

    @Test
    fun aPaymentLeftOutIsNotCountedAgainstALimit() {
        val messages = listOf(spend(1, "500.00"), spend(2, "300.00"))
        val limits = mapOf(MessageCategory.FOOD to BigDecimal(2000))

        val counted = SpendingLimits.build(messages, limits, emptyMap(), now, zone, CycleRule.CALENDAR)
        assertEquals(BigDecimal(800), counted.single().monthly!!.spent.setScale(0))

        val withOneLeftOut = SpendingLimits.build(listOf(spend(1, "500.00"), leftOut(spend(2, "300.00"))), limits, emptyMap(), now, zone, CycleRule.CALENDAR)
        assertEquals(BigDecimal(500), withOneLeftOut.single().monthly!!.spent.setScale(0))
    }
}
