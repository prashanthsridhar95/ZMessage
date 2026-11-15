package com.zyke.message.domain.categories

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory

/**
 * Choices of category the person has just made that the inbox pipeline has not handed back yet. A screen applies them on top of
 * the last list it has, in the same moment as the tap, so the rows, the open sheet and the picker show the new category at once;
 * when a list comes back that already carries the choice it replaces the overlay (nothing changes on screen, so nothing flickers),
 * and a list that was already on its way from before the tap does not take the choice away.
 *
 * Same precedence as [CategoryKey.apply]: a choice for one payment beats one for its payee. Taking a choice away (null) is not
 * shown ahead of the pipeline, because the automatic category is only known to the pipeline.
 */
class PendingCategories(private val nanoTime: () -> Long = System::nanoTime, private val expiryNanos: Long = EXPIRY_NANOS) {
    private val entries = LinkedHashMap<String, Pair<MessageCategory, Long>>()

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    /** Notes a choice for [key] (a payee key or [CategoryKey.messageKey]); null forgets any earlier one for it. */
    @Synchronized
    fun add(key: String, category: MessageCategory?) {
        if (category == null) entries.remove(key) else entries[key] = category to nanoTime()
    }

    /** [messages] with every pending choice applied; the very same list when nothing changes, and unchanged messages stay the same instances. */
    @Synchronized
    fun overlay(messages: List<ClassifiedMessage>): List<ClassifiedMessage> {
        expire()
        if (entries.isEmpty()) return messages
        val choices = HashMap<String, MessageCategory>(entries.size * 2).also { map -> entries.forEach { (k, v) -> map[k] = v.first } }
        var out: ArrayList<ClassifiedMessage>? = null
        for (i in messages.indices) {
            val was = messages[i]
            val now = applyTo(was, choices)
            if (now !== was) {
                val list = out ?: ArrayList(messages).also { out = it }
                list[i] = now
            }
        }
        return out ?: messages
    }

    /**
     * What to show for a list that has just come from the pipeline: the list with the choices that it does not carry yet applied.
     * Once it carries them all they are forgotten.
     */
    @Synchronized
    fun settle(fromPipeline: List<ClassifiedMessage>): List<ClassifiedMessage> {
        val shown = overlay(fromPipeline)
        if (shown === fromPipeline) entries.clear()
        return shown
    }

    private fun expire() {
        if (entries.isEmpty()) return
        val now = nanoTime()
        entries.entries.removeAll { now - it.value.second > expiryNanos }
    }

    companion object {
        /** A choice the pipeline has not confirmed within this long is not shown ahead of it any more. */
        const val EXPIRY_NANOS = 5_000_000_000L

        /** [message] as it reads with [choices] applied, or the same instance when they change nothing for it. */
        fun applyTo(message: ClassifiedMessage, choices: Map<String, MessageCategory>): ClassifiedMessage {
            if (choices.isEmpty()) return message
            val own = choices[CategoryKey.messageKey(message.id)]
            val category = own ?: message.takeIf { !it.categoryOwn }?.let { CategoryKey.find(choices, it) } ?: return message
            val isOwn = own != null
            if (message.category == category && message.merchant.category == category && message.categoryOverridden &&
                message.categoryOwn == isOwn && message.customCategoryId == null
            ) {
                return message
            }
            // A built-in choice replaces any category of the person's own for the same thing, as the pipeline does.
            return message.copy(
                category = category,
                merchant = message.merchant.copy(category = category),
                categoryOverridden = true,
                categoryOwn = isOwn,
                customCategoryId = null
            )
        }
    }
}
