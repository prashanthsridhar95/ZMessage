package com.zyke.message.domain.budget

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** One payment or credit on a day. */
data class DayItem(val messageId: Long, val title: String, val amount: BigDecimal, val category: MessageCategory, val at: Long)

/** A category's share of a day. */
data class DayCategory(val category: MessageCategory, val total: BigDecimal, val count: Int)

/** Everything that moved on one day: what counted against the daily budget, what the plan had set aside, and what came in. */
data class DayBreakdown(val spent: List<DayItem>, val setAside: List<DayItem>, val received: List<DayItem>) {
    companion object {
        fun categories(items: List<DayItem>): List<DayCategory> =
            items.groupBy { it.category }.map { (category, list) -> DayCategory(category, list.sumOf { it.amount }, list.size) }
                .sortedByDescending { it.total }
    }
}

object DayBreakdowns {
    private val SET_ASIDE = setOf(
        MessageCategory.INVESTMENTS, MessageCategory.SAVINGS, MessageCategory.INSURANCE,
        MessageCategory.CHARITY, MessageCategory.CREDIT_CARDS
    )

    fun build(messages: List<ClassifiedMessage>, date: LocalDate, zone: ZoneId): DayBreakdown {
        val spent = ArrayList<DayItem>()
        val setAside = ArrayList<DayItem>()
        val received = ArrayList<DayItem>()
        messages.forEach { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@forEach
            if (Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate() != date) return@forEach
            val amount = tx.amount.toBigDecimalOrNull() ?: return@forEach
            // Paying a card's bill is neither income nor a new expense.
            if (tx.isCardPayment) return@forEach
            val item = DayItem(
                message.id, tx.payee.ifBlank { message.merchant.name }.ifBlank { message.message.sender }, amount, message.category, message.message.date
            )
            when {
                tx.isReceived -> received += item
                message.category in SET_ASIDE -> setAside += item
                else -> spent += item
            }
        }
        val newest = compareByDescending<DayItem> { it.at }
        return DayBreakdown(spent.sortedWith(newest), setAside.sortedWith(newest), received.sortedWith(newest))
    }
}
