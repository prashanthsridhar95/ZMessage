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

    fun of(message: ClassifiedMessage): String? {
        val payee = payee(message)
        // A payee is keyed by who it is ([PayeeKey]); a message with none by its sender.
        if (payee.isNotBlank()) PayeeKey.of(payee)?.let { return it }
        return normalise(payee.ifBlank { message.message.sender }).ifEmpty { null }
    }

    /**
     * The key this message's payee was filed under before [PayeeKey] folded punctuation and company endings ("mr. diy"),
     * when that differs from [of]; null otherwise. Choices saved under it still apply (see [find]).
     */
    fun legacyOf(message: ClassifiedMessage): String? {
        val payee = payee(message)
        if (payee.isBlank()) return null
        val old = normalise(payee).ifEmpty { return null }
        return old.takeIf { it != of(message) }
    }

    /** The value kept for this message's key, or else under its older spelling, or null. */
    fun <V> find(map: Map<String, V>, message: ClassifiedMessage): V? {
        if (map.isEmpty()) return null
        return of(message)?.let(map::get) ?: legacyOf(message)?.let(map::get)
    }

    /** The key for one message on its own, for a choice that should not spread to everything else from the same sender. */
    fun messageKey(id: Long): String = "msg:$id"

    /** What to call the key on screen. */
    fun label(message: ClassifiedMessage): String =
        payee(message).ifBlank { SenderKey.displayAddress(message.message.sender) }.trim()

    private val SPACES = Regex("\\s+")

    private val normalised = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** Trimmed, lower-case, single-spaced. Names repeat across messages, so each distinct text is worked out once. */
    fun normalise(raw: String): String {
        normalised[raw]?.let { return it }
        val result = raw.trim().lowercase().replace(SPACES, " ")
        if (raw.length <= MAX_CACHED_LENGTH) {
            if (normalised.size >= MAX_CACHED) normalised.clear()
            normalised[raw] = result
        }
        return result
    }

    private const val MAX_CACHED = 20_000
    private const val MAX_CACHED_LENGTH = 200

    /**
     * Applies the user's choices; messages without a key or a choice are returned untouched. Priority: a choice for this
     * one message, then one for its sender / receiver, then a word rule ([tokenRules], see [Tokens]).
     */
    fun apply(
        message: ClassifiedMessage,
        overrides: Map<String, MessageCategory>,
        tokenRules: Map<String, MessageCategory> = emptyMap()
    ): ClassifiedMessage {
        if (overrides.isEmpty() && tokenRules.isEmpty()) return message
        val key = of(message)
        val category = overrides[messageKey(message.id)]
            ?: find(overrides, message)
            ?: key?.let { Tokens.match(it, tokenRules) }
            ?: return message
        return message.copy(
            category = category,
            merchant = message.merchant.copy(category = category),
            categoryOverridden = true,
            categoryOwn = overrides.containsKey(messageKey(message.id))
        )
    }
}
