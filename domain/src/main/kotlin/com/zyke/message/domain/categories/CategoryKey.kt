package com.zyke.message.domain.categories

import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent

/**
 * Who a message is "with", for filing it under a category: the receiver of a payment when there is one,
 * otherwise the sender. All messages sharing a key share the category the user picked.
 */
object CategoryKey {

    private fun payee(message: ClassifiedMessage): String = when (val content = message.content) {
        is MessageContent.Transaction -> content.payee
        is MessageContent.ScheduledPayment -> content.payee
        else -> ""
    }

    fun of(message: ClassifiedMessage): String? =
        normalise(payee(message).ifBlank { message.message.sender }).ifEmpty { null }

    /** The key for one message on its own, for a choice that should not spread to everything else from the same sender. */
    fun messageKey(id: Long): String = "msg:$id"

    /** What to call the key on screen. */
    fun label(message: ClassifiedMessage): String =
        payee(message).ifBlank { SenderKey.displayAddress(message.message.sender) }.trim()

    fun normalise(raw: String): String = raw.trim().lowercase().replace(Regex("\\s+"), " ")

    /** Applies the user's choices; messages without a key or a choice are returned untouched. */
    fun apply(message: ClassifiedMessage, overrides: Map<String, MessageCategory>): ClassifiedMessage {
        if (overrides.isEmpty()) return message
        // A choice made for this one message beats one made for everything from its sender or receiver.
        val category = overrides[messageKey(message.id)] ?: of(message)?.let(overrides::get) ?: return message
        return message.copy(
            category = category,
            merchant = message.merchant.copy(category = category),
            categoryOverridden = true,
            categoryOwn = overrides.containsKey(messageKey(message.id))
        )
    }
}
