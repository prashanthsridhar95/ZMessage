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

    /** Only the entries for [ids], so a month of a huge inbox can be read without loading all of it. */
    fun load(ids: Collection<Long>): Map<Long, ClassifiedMessage> = load().filterKeys { it in ids }

    /** Adds or replaces entries. */
    fun save(messages: List<ClassifiedMessage>)

    /** Forgets every entry whose id is not in [ids], so deleted messages do not linger. */
    fun retainOnly(ids: Set<Long>)

    /** Forgets everything. */
    fun clear()

    /** A small note kept with the cache and forgotten with it: how far the inbox has been read, and when it was last checked in full. */
    fun meta(key: String): String? = null

    fun putMeta(key: String, value: String) = Unit

    /** The remembered messages dated from [fromMillis] up to [untilMillis], newest first. Empty when the cache cannot say. */
    fun loadWindow(fromMillis: Long, untilMillis: Long): List<ClassifiedMessage> = emptyList()

    /** The dates of the oldest and the newest remembered message; null when it cannot say or holds none. */
    fun dateRange(): Pair<Long, Long>? = null
}

/** No memory: every message is read by the rules each time. */
object NoClassificationCache : ClassificationCache {
    override fun load(): Map<Long, ClassifiedMessage> = emptyMap()
    override fun save(messages: List<ClassifiedMessage>) = Unit
    override fun retainOnly(ids: Set<Long>) = Unit
    override fun clear() = Unit
}
