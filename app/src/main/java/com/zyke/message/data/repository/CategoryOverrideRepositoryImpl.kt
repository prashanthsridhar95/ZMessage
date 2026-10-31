package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.repository.CategoryOverrideRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Sender / receiver to category choices, kept in private preferences. */
class CategoryOverrideRepositoryImpl(context: Context) : CategoryOverrideRepository {

    private val prefs = context.applicationContext.getSharedPreferences("category_overrides", Context.MODE_PRIVATE)

    private val _overrides = MutableStateFlow(load())
    override val overrides: StateFlow<Map<String, MessageCategory>> = _overrides.asStateFlow()

    override fun set(key: String, category: MessageCategory?) {
        val editor = prefs.edit()
        if (category == null) editor.remove(key) else editor.putString(key, category.name)
        editor.apply()
        val before = _overrides.value.size
        _overrides.value = if (category == null) _overrides.value - key else _overrides.value + (key to category)
        com.zyke.message.data.diag.DevLog.d("category") {
            "set key=${com.zyke.message.domain.diag.DiagLog.key(key)} category=$category count $before -> ${_overrides.value.size} storedNow=${prefs.contains(key)}"
        }
    }

    override fun clearAll() {
        prefs.edit().clear().apply()
        _overrides.value = emptyMap()
    }

    private fun load(): Map<String, MessageCategory> =
        prefs.all.mapNotNull { (key, value) ->
            MessageCategory.entries.firstOrNull { it.name == value }?.let { key to it }
        }.toMap()
}
