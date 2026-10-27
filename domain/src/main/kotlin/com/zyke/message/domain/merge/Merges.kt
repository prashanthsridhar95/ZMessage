package com.zyke.message.domain.merge

import com.zyke.message.domain.model.ClassifiedMessage

/** The rules for merging messages that are one transaction. */
object Merges {
    /**
     * [existing] with [duplicateId] folded into [primaryId]. If the primary was itself merged into another message,
     * everything goes to that one; anything that had been merged into the duplicate follows it. Merging a message
     * with itself, or with what it already stands for, changes nothing.
     */
    fun add(existing: Map<Long, Long>, primaryId: Long, duplicateId: Long): Map<Long, Long> {
        val root = existing[primaryId] ?: primaryId
        val duplicateRoot = existing[duplicateId] ?: duplicateId
        if (root == duplicateRoot) return existing
        val moved = existing.filterValues { it == duplicateRoot }.keys + duplicateRoot
        return existing + moved.associateWith { root }
    }

    /** [existing] without [duplicateId]'s merge. */
    fun remove(existing: Map<Long, Long>, duplicateId: Long): Map<Long, Long> = existing - duplicateId

    /**
     * Takes the merged duplicates out of [messages] and puts them on the message that stands for them. A duplicate
     * whose primary is not in the list (deleted, or not read yet) stays as it was.
     */
    fun apply(messages: List<ClassifiedMessage>, merges: Map<Long, Long>): List<ClassifiedMessage> {
        if (merges.isEmpty()) return messages
        val byId = messages.associateBy { it.id }
        val duplicates = merges.entries.filter { (duplicate, primary) -> duplicate in byId && primary in byId && duplicate != primary }
        val hidden = duplicates.mapTo(HashSet()) { it.key }
        val byPrimary = duplicates.groupBy({ it.value }, { it.key })
        return messages.mapNotNull { message ->
            when {
                message.id in hidden -> null
                message.id in byPrimary ->
                    message.copy(mergedMessages = byPrimary.getValue(message.id).mapNotNull { byId[it]?.message }.sortedByDescending { it.date })
                else -> message
            }
        }
    }
}
