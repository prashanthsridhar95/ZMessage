package com.zyke.message.domain.ledger

import com.zyke.message.domain.model.ClassifiedMessage

/**
 * One real payment, shown once. The messages [SamePayment] linked as certain repeats of a bank debit (a gateway's or merchant's
 * confirmation, an app's "paid", a booking confirmation, the same transaction told by a second bank alert) are the debit's
 * [followers]; a list shows the debit as one row ("+2 related") and its detail sheet lists them. This is only how lists are
 * drawn: no message is deleted, merged or hidden from its own detail, search still finds a follower by its text (and opens
 * the group), and the totals already count the debit once because a follower is [Counted.NotCounted].
 *
 * Only certain links group: a "Same payment?" question stays two rows until it is answered. The person's answers are read
 * through the links themselves, so "not the same payment" ungroups at once. A group holds at most [MAX_FOLLOWERS] followers
 * (the nearest in time); any further one stays a row of its own.
 */
class PaymentGroups private constructor(
    private val followersByAnchor: Map<Long, List<ClassifiedMessage>>,
    private val anchorByFollower: Map<Long, Long>
) {
    val isEmpty: Boolean get() = followersByAnchor.isEmpty()

    /** The followers of the payment [anchorId], oldest first; empty when it has none. */
    fun followersOf(anchorId: Long): List<ClassifiedMessage> = followersByAnchor[anchorId] ?: emptyList()

    /** The id of the payment [followerId] is shown inside, or null when it is a row of its own. */
    fun anchorOf(followerId: Long): Long? = anchorByFollower[followerId]

    fun isFollower(messageId: Long): Boolean = messageId in anchorByFollower

    /** Every member of the group [anchor] leads, the payment first. */
    fun membersOf(anchor: ClassifiedMessage): List<ClassifiedMessage> = listOf(anchor) + followersOf(anchor.id)

    companion object {
        /** The most followers one payment row holds. */
        const val MAX_FOLLOWERS = 6

        val NONE = PaymentGroups(emptyMap(), emptyMap())

        /**
         * What a payment's row says about the messages inside it: "+1 copy" / "+2 copies" when every one is a word-for-word copy of the
         * payment (same account, amount and reference number: [SamePayment.LAYER_COPY]), else "+N related"; null when it has none.
         */
        fun relatedCaption(related: List<ClassifiedMessage>): String? {
            if (related.isEmpty()) return null
            val copies = related.count { it.samePayment?.layer == SamePayment.LAYER_COPY }
            return if (copies == related.size) "+$copies ${if (copies == 1) "copy" else "copies"}" else "+${related.size} related"
        }

        /**
         * @param skipFollower messages that must stay rows of their own (archived or pinned ones the list is showing by themselves)
         */
        fun of(messages: List<ClassifiedMessage>, skipFollower: (ClassifiedMessage) -> Boolean = { false }): PaymentGroups {
            val linked = ArrayList<ClassifiedMessage>()
            for (m in messages) {
                val link = m.samePayment ?: continue
                if (link.kind != SamePaymentKind.CERTAIN || link.primaryId == m.id) continue
                if (skipFollower(m)) continue
                linked += m
            }
            if (linked.isEmpty()) return NONE
            val byId = HashMap<Long, ClassifiedMessage>(messages.size * 2)
            for (m in messages) byId[m.id] = m
            val followerIds = linked.mapTo(HashSet()) { it.id }
            val perAnchor = HashMap<Long, ArrayList<ClassifiedMessage>>()
            for (m in linked) {
                val anchorId = m.samePayment?.primaryId ?: continue
                // A payment that is itself a repeat of another message never leads a group (no chains).
                if (anchorId !in byId || anchorId in followerIds) continue
                perAnchor.getOrPut(anchorId) { ArrayList() } += m
            }
            if (perAnchor.isEmpty()) return NONE
            val followers = HashMap<Long, List<ClassifiedMessage>>(perAnchor.size * 2)
            val reverse = HashMap<Long, Long>()
            for ((anchorId, list) in perAnchor) {
                val at = byId.getValue(anchorId).message.date
                val kept = if (list.size <= MAX_FOLLOWERS) list
                else list.sortedWith(compareBy<ClassifiedMessage> { kotlin.math.abs(it.message.date - at) }.thenBy { it.id }).take(MAX_FOLLOWERS)
                followers[anchorId] = kept.sortedWith(compareBy<ClassifiedMessage> { it.message.date }.thenBy { it.id })
                for (f in kept) reverse[f.id] = anchorId
            }
            return PaymentGroups(followers, reverse)
        }
    }
}
