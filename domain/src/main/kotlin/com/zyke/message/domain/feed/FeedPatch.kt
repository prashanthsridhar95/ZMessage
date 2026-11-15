package com.zyke.message.domain.feed

import com.zyke.message.domain.model.ClassifiedMessage

/**
 * Puts newer versions of messages into a feed that was built from older ones, without building it again: a row whose message has a
 * newer version (by id) shows that one. Rows without a newer version stay the very same instances, so nothing else is redrawn.
 * Which rows are in the feed (filters, search, groups) is not touched; the next full build settles that.
 */
object FeedPatch {
    fun sync(feed: Feed, current: Map<Long, ClassifiedMessage>): Feed {
        if (current.isEmpty()) return feed
        var changed = false
        val sections = feed.sections.map { section ->
            var sectionChanged = false
            val items = section.items.map { item ->
                val patched = patch(item, current)
                if (patched !== item) sectionChanged = true
                patched
            }
            if (sectionChanged) { changed = true; section.copy(items = items) } else section
        }
        return if (changed) feed.copy(sections = sections) else feed
    }

    private fun patch(item: FeedItem, current: Map<Long, ClassifiedMessage>): FeedItem = when (item) {
        is FeedItem.Single -> {
            val message = swap(item.message, current)
            val related = swapAll(item.related, current)
            if (message === item.message && related === item.related) item else item.copy(message = message, related = related)
        }
        is FeedItem.Group -> {
            val messages = swapAll(item.messages, current)
            if (messages === item.messages) item else item.copy(messages = messages)
        }
    }

    private fun swap(message: ClassifiedMessage, current: Map<Long, ClassifiedMessage>): ClassifiedMessage = current[message.id] ?: message

    private fun swapAll(messages: List<ClassifiedMessage>, current: Map<Long, ClassifiedMessage>): List<ClassifiedMessage> {
        if (messages.isEmpty()) return messages
        var out: ArrayList<ClassifiedMessage>? = null
        for (i in messages.indices) {
            val was = messages[i]
            val now = swap(was, current)
            if (now !== was) (out ?: ArrayList(messages).also { out = it })[i] = now
        }
        return out ?: messages
    }

    /** The messages of [now] that are not the same instance as the one in [before] at the same place, by id; both newest-first lists of one inbox. */
    fun changed(before: List<ClassifiedMessage>, now: List<ClassifiedMessage>): Map<Long, ClassifiedMessage> {
        if (before === now || before.size != now.size) return emptyMap()
        var out: HashMap<Long, ClassifiedMessage>? = null
        for (i in now.indices) if (before[i] !== now[i]) (out ?: HashMap<Long, ClassifiedMessage>().also { out = it })[now[i].id] = now[i]
        return out ?: emptyMap()
    }
}
