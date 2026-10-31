package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.TokenRuleRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Word to category rules, kept in private preferences. */
class TokenRuleRepositoryImpl(context: Context) : TokenRuleRepository {

    private val prefs = context.applicationContext.getSharedPreferences("token_rules", Context.MODE_PRIVATE)

    private val _rules = MutableStateFlow(load())
    override val rules: StateFlow<Map<String, MessageCategory>> = _rules.asStateFlow()

    override fun set(token: String, category: MessageCategory?) {
        val editor = prefs.edit()
        if (category == null) editor.remove(token) else editor.putString(token, category.name)
        editor.apply()
        _rules.value = if (category == null) _rules.value - token else _rules.value + (token to category)
        com.zyke.message.data.diag.DevLog.d("category") { "word rule set=${category != null} count now ${_rules.value.size}" }
    }

    private fun load(): Map<String, MessageCategory> =
        prefs.all.mapNotNull { (token, value) ->
            MessageCategory.entries.firstOrNull { it.name == value }?.let { token to it }
        }.toMap()
}
