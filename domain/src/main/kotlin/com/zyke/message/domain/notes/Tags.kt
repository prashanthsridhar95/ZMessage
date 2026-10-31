package com.zyke.message.domain.notes

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal

/** One tag with how many payments carry it and what was spent on them. */
data class TagSummary(val tag: String, val count: Int, val spent: BigDecimal, val messageIds: List<Long>)

object Tags {
    /** Every tag in use, most used first. Spent counts debits not set aside, like the category totals. */
    fun summaries(messages: List<ClassifiedMessage>): List<TagSummary> {
        val byTag = LinkedHashMap<String, MutableList<ClassifiedMessage>>()
        messages.forEach { m -> m.tags.forEach { byTag.getOrPut(it) { ArrayList() } += m } }
        return byTag.map { (tag, list) ->
            val spent = list.fold(BigDecimal.ZERO) { sum, m -> sum + spentOn(m) }
            TagSummary(tag, list.size, spent, list.sortedByDescending { it.message.date }.map { it.id })
        }.sortedWith(compareByDescending<TagSummary> { it.count }.thenBy { it.tag })
    }

    private fun spentOn(message: ClassifiedMessage): BigDecimal {
        val tx = message.content as? MessageContent.Transaction ?: return BigDecimal.ZERO
        if (tx.isReceived || message.setAside != null || tx.isCardPayment) return BigDecimal.ZERO
        return tx.amount.replace(",", "").toBigDecimalOrNull() ?: BigDecimal.ZERO
    }

    /** Tags already in use ([allTags] holds one entry per use) that fit [typed], best first, then most used; without those [already] on the payment. */
    fun suggest(typed: String, allTags: List<String>, already: Collection<String>, limit: Int = 6): List<String> {
        val q = typed.trim().trimStart('#').lowercase()
        return allTags.groupingBy { it }.eachCount().entries
            .filter { it.key !in already && (q.isEmpty() || it.key.contains(q)) }
            .sortedWith(compareBy<Map.Entry<String, Int>> { if (it.key.startsWith(q)) 0 else 1 }.thenByDescending { it.value }.thenBy { it.key })
            .take(limit).map { it.key }
    }
}
