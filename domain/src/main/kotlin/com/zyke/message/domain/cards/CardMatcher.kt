package com.zyke.message.domain.cards

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserCard

/** Works out which of the person's cards a message is about, from the last four digits. */
object CardMatcher {

    fun match(message: ClassifiedMessage, cards: List<UserCard>): UserCard? {
        if (cards.isEmpty()) return null
        val account = accountNumber(message.content)
        if (account.isNotBlank()) {
            val digits = account.filter { it.isDigit() }
            cards.firstOrNull { digits.endsWith(it.last4) }?.let { return it }
        }
        // Not every parser keeps the number; look for it in the text, next to a mask or the word "card".
        val body = message.message.body
        return cards.firstOrNull { card -> mentions(body, card.last4) }
    }

    fun apply(message: ClassifiedMessage, cards: List<UserCard>): ClassifiedMessage {
        val card = match(message, cards) ?: return message
        return message.copy(cardId = card.id)
    }

    private fun accountNumber(content: MessageContent): String = when (content) {
        is MessageContent.Transaction -> content.accountNumber
        is MessageContent.CardBill -> content.accountNumber
        is MessageContent.ScheduledPayment -> content.accountNumber
        else -> ""
    }

    private fun mentions(body: String, last4: String): Boolean =
        Regex("(?i)(?:[x*•]+\\s*|\\bcard(?:\\s*(?:no\\.?|number|ending))?\\s*:?\\s*|\\bending\\s*(?:with|in)?\\s*)$last4\\b")
            .containsMatchIn(body)
}
