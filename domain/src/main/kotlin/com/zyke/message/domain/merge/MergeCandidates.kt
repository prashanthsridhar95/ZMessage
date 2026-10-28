package com.zyke.message.domain.merge

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import kotlin.math.abs

/** Finds the messages that look like another message about the same payment: the same amount and direction, close in time. */
object MergeCandidates {
    private const val WITHIN_MS = 2L * 24 * 60 * 60 * 1000
    private const val MAX = 5

    fun find(item: ClassifiedMessage, messages: List<ClassifiedMessage>): List<ClassifiedMessage> {
        val tx = item.content as? MessageContent.Transaction ?: return emptyList()
        val amount = tx.amount.toBigDecimalOrNull() ?: return emptyList()
        return messages.filter { other ->
            val o = other.content as? MessageContent.Transaction ?: return@filter false
            other.id != item.id &&
                o.isReceived == tx.isReceived &&
                o.amount.toBigDecimalOrNull()?.compareTo(amount) == 0 &&
                abs(other.message.date - item.message.date) <= WITHIN_MS
        }.sortedBy { abs(it.message.date - item.message.date) }.take(MAX)
    }
}
