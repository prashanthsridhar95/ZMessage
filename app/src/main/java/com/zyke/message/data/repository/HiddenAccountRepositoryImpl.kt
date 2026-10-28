package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.HiddenAccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which accounts the person removed as not theirs, kept in private preferences. */
class HiddenAccountRepositoryImpl(context: Context) : HiddenAccountRepository {

    private val prefs = context.applicationContext.getSharedPreferences("hidden_accounts", Context.MODE_PRIVATE)

    private val _hidden = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    override val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    @Synchronized
    override fun hide(key: String) = write(_hidden.value + key)

    @Synchronized
    override fun show(key: String) = write(_hidden.value - key)

    private fun write(keys: Set<String>) {
        _hidden.value = keys
        prefs.edit().putStringSet(KEY, keys).apply()
    }

    private companion object {
        const val KEY = "keys"
    }
}
