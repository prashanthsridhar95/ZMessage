package com.zyke.message.domain.repository

import com.zyke.message.domain.insights.RecurringGroup
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The person's groups of repeating payments, their own names for entries, and the group suggestions they turned
 * down. Each list is written whole, so one change (or one undo) is one write.
 */
interface RecurringGroupRepository {
    val groups: StateFlow<List<RecurringGroup>>

    /** Names by [com.zyke.message.domain.insights.Subscription.key]; any entry can have one. */
    val names: StateFlow<Map<String, String>>

    /** Ids of group suggestions the person said no to ([com.zyke.message.domain.insights.GroupSuggestion.id]). */
    val dismissed: StateFlow<Set<String>>

    fun setGroups(groups: List<RecurringGroup>)

    fun setNames(names: Map<String, String>)

    fun dismiss(suggestionId: String)

    fun undismiss(suggestionId: String)
}

object NoRecurringGroups : RecurringGroupRepository {
    override val groups: StateFlow<List<RecurringGroup>> = MutableStateFlow(emptyList())
    override val names: StateFlow<Map<String, String>> = MutableStateFlow(emptyMap())
    override val dismissed: StateFlow<Set<String>> = MutableStateFlow(emptySet())
    override fun setGroups(groups: List<RecurringGroup>) = Unit
    override fun setNames(names: Map<String, String>) = Unit
    override fun dismiss(suggestionId: String) = Unit
    override fun undismiss(suggestionId: String) = Unit
}
