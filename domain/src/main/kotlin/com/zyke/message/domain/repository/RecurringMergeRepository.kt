package com.zyke.message.domain.repository

import com.zyke.message.domain.insights.RecurringMerge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's merges of repeating payments that are one payment reported under several names, plus the suggestions
 * they turned down. Written whole, so one merge (or one undo) is one write.
 */
interface RecurringMergeRepository {
    val merges: StateFlow<List<RecurringMerge>>

    /** Ids of suggestions the person said no to ([com.zyke.message.domain.insights.MergeSuggestion.id]). */
    val dismissed: StateFlow<Set<String>>

    /** Replaces every merge at once; undo puts the earlier list back. */
    fun setAll(merges: List<RecurringMerge>)

    fun dismiss(suggestionId: String)

    fun undismiss(suggestionId: String)
}

object NoRecurringMerges : RecurringMergeRepository {
    override val merges: StateFlow<List<RecurringMerge>> = MutableStateFlow(emptyList())
    override val dismissed: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun setAll(merges: List<RecurringMerge>) = Unit
    override fun dismiss(suggestionId: String) = Unit
    override fun undismiss(suggestionId: String) = Unit
}
