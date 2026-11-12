package com.zyke.message.domain.accounts

import com.zyke.message.domain.cards.CardNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the person says an account really is, over what the messages suggest. A blank [bank] and a null [kind] leave
 * the detected values alone, so the override can change the bank, the type, or both.
 */
data class AccountOverride(
    /** The bank as the person named it ("ICICI Bank", or free text); blank keeps the detected bank. */
    val bank: String = "",
    val kind: AccountKind? = null,
    /** What the person says the account holds, and when they said so; payments after that move it like a stated balance. */
    val manual: ManualBalance? = null,
    /** The card's payment network as the person chose it; [CardNetwork.OTHER] means "no mark", null leaves it to the messages. */
    val network: CardNetwork? = null
) {
    val isEmpty: Boolean get() = bank.isBlank() && kind == null && manual == null && network == null
}

/** A balance the person typed for an account that states none (or states it wrongly), as of [atMillis]. */
data class ManualBalance(val accountKey: String, val amount: java.math.BigDecimal, val atMillis: Long)

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
