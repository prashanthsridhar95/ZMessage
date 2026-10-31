package com.zyke.message.domain.repository

import com.zyke.message.domain.model.MessageCategory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** "Anything with this word files under this category" rules, by lowercase word. Weaker than a choice for one sender or message. */
interface TokenRuleRepository {
    val rules: StateFlow<Map<String, MessageCategory>>

    /** Passing null removes the rule. */
    fun set(token: String, category: MessageCategory?)
}

object NoTokenRules : TokenRuleRepository {
    override val rules: StateFlow<Map<String, MessageCategory>> = MutableStateFlow(emptyMap())
    override fun set(token: String, category: MessageCategory?) = Unit
}
