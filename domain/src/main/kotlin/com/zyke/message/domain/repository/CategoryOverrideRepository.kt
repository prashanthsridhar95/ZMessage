package com.zyke.message.domain.repository

import com.zyke.message.domain.model.MessageCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The category a person has chosen for a sender / receiver, by [com.zyke.message.domain.categories.CategoryKey]. */
interface CategoryOverrideRepository {
    val overrides: StateFlow<Map<String, MessageCategory>>

    /** Passing null goes back to the automatic category. */
    fun set(key: String, category: MessageCategory?)

    fun clearAll()
}

/** For callers that have no stored choices. */
object NoCategoryOverrides : CategoryOverrideRepository {
    override val overrides: StateFlow<Map<String, MessageCategory>> = MutableStateFlow(emptyMap())
    override fun set(key: String, category: MessageCategory?) = Unit
    override fun clearAll() = Unit
}
