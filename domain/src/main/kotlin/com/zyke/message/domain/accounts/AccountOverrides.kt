package com.zyke.message.domain.accounts

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the person says an account really is, over what the messages suggest. A blank [bank] and a null [kind] leave
 * the detected values alone, so the override can change the bank, the type, or both.
 */
data class AccountOverride(
    /** The bank as the person named it ("ICICI Bank", or free text); blank keeps the detected bank. */
    val bank: String = "",
    val kind: AccountKind? = null
) {
    val isEmpty: Boolean get() = bank.isBlank() && kind == null
}

/** The person's corrections, by account key. A new map is published on every change, so a state that holds it sees the change. */
interface AccountOverrideRepository {
    val overrides: StateFlow<Map<String, AccountOverride>>

    fun set(key: String, override: AccountOverride)

    /** Back to what the messages say. */
    fun clear(key: String)
}

object NoAccountOverrides : AccountOverrideRepository {
    override val overrides: StateFlow<Map<String, AccountOverride>> = MutableStateFlow(emptyMap())
    override fun set(key: String, override: AccountOverride) = Unit
    override fun clear(key: String) = Unit
}
