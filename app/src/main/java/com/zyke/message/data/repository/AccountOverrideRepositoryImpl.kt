package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountOverride
import com.zyke.message.domain.accounts.AccountOverrideRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the person says each account's bank and type are, by account key, in private preferences (so a backup carries it).
 * Each value is "kind|bank"; kind may be empty. A new map is published on every change.
 */
class AccountOverrideRepositoryImpl(context: Context) : AccountOverrideRepository {

    private val prefs = context.applicationContext.getSharedPreferences("account_overrides", Context.MODE_PRIVATE)

    private val _overrides = MutableStateFlow(read())
    override val overrides: StateFlow<Map<String, AccountOverride>> = _overrides.asStateFlow()

    @Synchronized
    override fun set(key: String, override: AccountOverride) {
        if (override.isEmpty) return clear(key)
        _overrides.value = _overrides.value + (key to override)
        prefs.edit().putString(key, encode(override)).apply()
    }

    @Synchronized
    override fun clear(key: String) {
        if (key !in _overrides.value) return
        _overrides.value = _overrides.value - key
        prefs.edit().remove(key).apply()
    }

    private fun read(): Map<String, AccountOverride> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { decode(it) }?.takeUnless { it.isEmpty }?.let { key to it } }.toMap()

    private fun encode(o: AccountOverride) = "${o.kind?.name.orEmpty()}|${o.bank}"

    private fun decode(text: String): AccountOverride {
        val kind = text.substringBefore('|').let { name -> AccountKind.entries.firstOrNull { it.name == name } }
        return AccountOverride(bank = text.substringAfter('|', ""), kind = kind)
    }
}
