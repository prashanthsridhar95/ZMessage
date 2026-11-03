package com.zyke.message.data.repository

import android.content.Context
import com.zyke.message.domain.accounts.AccountKind
import com.zyke.message.domain.accounts.AccountOverride
import com.zyke.message.domain.accounts.AccountOverrideRepository
import com.zyke.message.domain.accounts.ManualBalance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the person says each account's bank and type are, by account key, in private preferences (so a backup carries it).
 * Each value is "v2|kind|amount|atMillis|bank" (kind and the typed balance may be empty). A new map is published on every change.
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
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { decode(key, it) }?.takeUnless { it.isEmpty }?.let { key to it } }.toMap()

    /** "v2|kind|amount|atMillis|bank" (amount and time empty without a typed balance); the older "kind|bank" still reads. */
    private fun encode(o: AccountOverride) =
        "v2|${o.kind?.name.orEmpty()}|${o.manual?.amount?.toPlainString().orEmpty()}|${o.manual?.atMillis?.toString().orEmpty()}|${o.bank}"

    private fun decode(key: String, text: String): AccountOverride {
        if (text.startsWith("v2|")) {
            val parts = text.split('|', limit = 5)
            if (parts.size < 5) return AccountOverride()
            val kind = AccountKind.entries.firstOrNull { it.name == parts[1] }
            val amount = parts[2].toBigDecimalOrNull()
            val at = parts[3].toLongOrNull()
            val manual = if (amount != null && at != null) ManualBalance(key, amount, at) else null
            return AccountOverride(bank = parts[4], kind = kind, manual = manual)
        }
        val kind = text.substringBefore('|').let { name -> AccountKind.entries.firstOrNull { it.name == name } }
        return AccountOverride(bank = text.substringAfter('|', ""), kind = kind)
    }
}
