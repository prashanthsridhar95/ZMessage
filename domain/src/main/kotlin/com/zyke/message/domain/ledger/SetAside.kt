package com.zyke.message.domain.ledger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Why a payment is kept out of the income and spending totals while staying in the list. */
enum class SetAside(val label: String) {
    /** The person left it out. */
    EXCLUDED("Left out of totals"),

    /** Money moved between the person's own accounts: not income, not spending. */
    TRANSFER("Transfer between own accounts")
}

/**
 * What the person has set aside: payments left out of the totals, and transfers, each a pair of legs (the debit and the
 * credit) or one leg seen alone. [dismissed] are suggested transfers the person said are not transfers.
 */
data class SetAsides(
    val excluded: Set<Long> = emptySet(),
    val transfers: List<TransferLink> = emptyList(),
    val dismissed: Set<String> = emptySet()
) {
    val transferIds: Set<Long> get() = transfers.flatMapTo(HashSet()) { listOfNotNull(it.outId, it.inId) }
    fun kindOf(id: Long): SetAside? = when {
        id in transferIds -> SetAside.TRANSFER
        id in excluded -> SetAside.EXCLUDED
        else -> null
    }
}

/** A transfer: the debit leg and, when it has been seen, the credit leg. */
data class TransferLink(val outId: Long?, val inId: Long?) {
    init { require(outId != null || inId != null) }
}

interface SetAsideRepository {
    val state: StateFlow<SetAsides>
    fun exclude(id: Long, excluded: Boolean)
    /** Links the two legs as one transfer; either may be null for a leg not seen. */
    fun link(outId: Long?, inId: Long?)
    /** Takes a message out of any transfer it is in. */
    fun unlink(id: Long)
    /** Remembers that this suggestion is not a transfer. */
    fun dismissSuggestion(key: String)
    /** Takes back [dismissSuggestion]. */
    fun restoreSuggestion(key: String) = Unit
}

object NoSetAsides : SetAsideRepository {
    override val state: StateFlow<SetAsides> = MutableStateFlow(SetAsides())
    override fun exclude(id: Long, excluded: Boolean) = Unit
    override fun link(outId: Long?, inId: Long?) = Unit
    override fun unlink(id: Long) = Unit
    override fun dismissSuggestion(key: String) = Unit
}
