package com.zyke.message.domain.feed

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId

object SpendSummaryBuilder {

    /** Money sent this calendar month, or null if there is none. */
    fun build(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): SpendSummary? {
        val month = YearMonth.from(now.atZone(zone))

        val spends = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (tx.isReceived || message.setAside != null) return@mapNotNull null
            if (YearMonth.from(Instant.ofEpochMilli(message.message.date).atZone(zone)) != month) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            message.category to amount
        }
        if (spends.isEmpty()) return null

        val byCategory = spends
            .groupBy({ it.first }, { it.second })
            .map { (category, amounts) -> CategorySpend(category, amounts.fold(BigDecimal.ZERO, BigDecimal::add)) }
            .sortedByDescending { it.amount }

        return SpendSummary(month, byCategory.fold(BigDecimal.ZERO) { sum, c -> sum + c.amount }, byCategory)
    }
}
