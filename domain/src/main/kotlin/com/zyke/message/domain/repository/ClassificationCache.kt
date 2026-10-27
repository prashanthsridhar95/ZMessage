package com.zyke.message.domain.repository

import com.zyke.message.domain.model.ClassifiedMessage

/**
 * Remembers how each message was read by the rules, so the inbox does not have to be parsed again from scratch
 * every time. Only the rules' own result is kept: the person's choices (categories, cards, muted senders,
 * pictures) are applied afterwards and are never part of it.
 */
interface ClassificationCache {
    /** Everything remembered, by message id. May block; call it off the main thread. */
    fun load(): Map<Long, ClassifiedMessage>

    /** Adds or replaces entries. */
    fun save(messages: List<ClassifiedMessage>)

    /** Forgets every entry whose id is not in [ids], so deleted messages do not linger. */
    fun retainOnly(ids: Set<Long>)

    /** Forgets everything. */
    fun clear()
}

/** No memory: every message is read by the rules each time. */
object NoClassificationCache : ClassificationCache {
    override fun load(): Map<Long, ClassifiedMessage> = emptyMap()
    override fun save(messages: List<ClassifiedMessage>) = Unit
    override fun retainOnly(ids: Set<Long>) = Unit
    override fun clear() = Unit
}
