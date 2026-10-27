package com.zyke.message.domain.cards

import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.UserCard

/** Works out which of the person's cards or accounts a message is about, from the last four digits. */
object CardMatcher {

    fun match(message: ClassifiedMessage, cards: List<UserCard>): UserCard? {
        if (cards.isEmpty()) return null
        val candidates = cards.filter { it.type.isCard == isCardMessage(message) }
        val account = accountNumber(message.content)
        if (account.isNotBlank()) {
            val digits = account.filter { it.isDigit() }
            candidates.firstOrNull { digits.endsWith(it.last4) }?.let { return it }
        }
        // Not every parser keeps the number; look for it in the text, next to a mask or a label.
        val body = message.message.body
        return candidates.firstOrNull { card -> mentions(body, card.last4) }
    }

    fun apply(message: ClassifiedMessage, cards: List<UserCard>): ClassifiedMessage {
        val card = match(message, cards) ?: return message
        return message.copy(cardId = card.id)
    }

    /** Card spends, bills and card autopays belong to cards; everything else with a number is an account. */
    private fun isCardMessage(message: ClassifiedMessage): Boolean = when (val content = message.content) {
        is MessageContent.Transaction -> content.isCard
        is MessageContent.ScheduledPayment -> content.isCard
        is MessageContent.CardBill -> true
        else -> message.message.body.contains("card", ignoreCase = true)
    }

    private fun accountNumber(content: MessageContent): String = when (content) {
        is MessageContent.Transaction -> content.accountNumber
        is MessageContent.CardBill -> content.accountNumber
        is MessageContent.ScheduledPayment -> content.accountNumber
        else -> ""
    }

    private fun mentions(body: String, last4: String): Boolean =
        Regex(
            "(?i)(?:[x*•]+\\s*|\\b(?:card|a/c|acct?|account)(?:\\s*(?:no\\.?|number|ending))?\\s*:?\\s*|\\bending\\s*(?:with|in)?\\s*)$last4\\b"
        ).containsMatchIn(body)
}
