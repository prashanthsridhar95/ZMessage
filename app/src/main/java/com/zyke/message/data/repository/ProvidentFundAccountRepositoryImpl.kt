package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.repository.ProvidentFundAccountRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which accounts the person marked as a provident fund, kept in private preferences. */
class ProvidentFundAccountRepositoryImpl(context: Context) : ProvidentFundAccountRepository {

    private val prefs = context.applicationContext.getSharedPreferences("pf_accounts", Context.MODE_PRIVATE)

    private val _keys = MutableStateFlow(prefs.getStringSet(KEY, emptySet()).orEmpty().toSet())
    override val keys: StateFlow<Set<String>> = _keys.asStateFlow()

    @Synchronized
    override fun set(key: String, isProvidentFund: Boolean) {
        val next = if (isProvidentFund) _keys.value + key else _keys.value - key
        _keys.value = next
        prefs.edit().putStringSet(KEY, next).apply()
    }

    private companion object {
        const val KEY = "keys"
    }
}
