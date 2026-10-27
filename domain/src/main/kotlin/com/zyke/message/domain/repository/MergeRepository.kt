package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Messages the person says are one transaction, as duplicate id to the id of the message that stands for it. A bank
 * message, a card message and a UPI app's message about the same payment are three messages and one spend.
 */
interface MergeRepository {
    val merges: StateFlow<Map<Long, Long>>

    /** Counts [duplicateId] as part of [primaryId]. Whatever was merged into either one comes along. */
    fun merge(primaryId: Long, duplicateId: Long)

    /** Makes [duplicateId] a message of its own again. */
    fun unmerge(duplicateId: Long)
}

object NoMerges : MergeRepository {
    override val merges: StateFlow<Map<Long, Long>> = MutableStateFlow(emptyMap())
    override fun merge(primaryId: Long, duplicateId: Long) = Unit
    override fun unmerge(duplicateId: Long) = Unit
}
