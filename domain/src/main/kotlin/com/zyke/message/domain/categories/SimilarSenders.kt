package com.zyke.message.domain.categories

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory

/** A sender / receiver alike one the person just filed, still unfiled. */
data class SimilarSender(val key: String, val label: String, val messageCount: Int)

object SimilarSenders {
    const val MAX = 5

    /**
     * Other senders / receivers that share a distinctive word with [chosen]'s key (so "Swiggy Genie" is alike
     * "Swiggy Instamart") and have no choice of their own yet. Messages from the same bank header alone do not count:
     * every payee in a bank's messages shares it. Most messages first.
     */
    fun find(chosen: ClassifiedMessage, messages: List<ClassifiedMessage>, overrides: Map<String, MessageCategory>): List<SimilarSender> {
        val chosenKey = CategoryKey.of(chosen) ?: return emptyList()
        val words = Tokens.of(chosenKey).toSet()
        if (words.isEmpty()) return emptyList()
        return messages.asSequence()
            .filter { !it.categoryOverridden && !overrides.containsKey(CategoryKey.messageKey(it.id)) }
            .mapNotNull { m -> CategoryKey.of(m)?.let { it to m } }
            .filter { (key, _) -> key != chosenKey && !overrides.containsKey(key) && Tokens.of(key).any { it in words } }
            .groupBy({ it.first }, { it.second })
            .map { (key, group) -> SimilarSender(key, CategoryKey.label(group.first()), group.size) }
            .sortedWith(compareByDescending<SimilarSender> { it.messageCount }.thenBy { it.key })
            .take(MAX)
    }
}
