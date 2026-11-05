package com.zyke.message.domain.ledger

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent

/**
 * Which payments in "Needs review" can be picked together, and the picking itself. Only a single transaction can be
 * picked: pairs (duplicates, transfers), bills, charges after cancelling and messages that were not understood each ask
 * their own question and cannot be named or filed in bulk.
 */
object ReviewSelection {
    /** The payments of [review] that can be picked, in the order the page lists them, each once. */
    fun selectable(review: ReviewQueue): List<ClassifiedMessage> =
        (review.unusual + review.uncategorised + review.cardBills + review.samePayments).filter { it.content is MessageContent.Transaction }.distinctBy { it.id }

    /** Adds [id] if it is not picked, takes it away if it is. */
    fun toggle(picked: Collection<Long>, id: Long): List<Long> = if (id in picked) picked.filter { it != id } else picked.filter { it != id } + id

    /** Everything in [section] picked, keeping what was picked elsewhere. */
    fun selectAll(picked: Collection<Long>, section: Collection<Long>): List<Long> = (picked + section).distinct()

    /** Only what can still be picked: payments that left the list (handled, merged) drop out of the choice. */
    fun prune(picked: Collection<Long>, available: Collection<Long>): List<Long> {
        val have = available.toHashSet()
        return picked.filter { it in have }.distinct()
    }

    /** What is said aloud when the mode starts or the count changes. */
    fun announcement(count: Int): String = "Selection mode, $count selected"

    /** Whether every payment of [section] is picked. */
    fun allPicked(picked: Collection<Long>, section: Collection<Long>): Boolean = section.isNotEmpty() && picked.toHashSet().containsAll(section)
}
