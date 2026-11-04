package com.zyke.message.domain.ledger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How sure the app is that a bank debit paid a credit card's bill. */
enum class CardBillKind { CERTAIN, SUSPECTED }

/** That a bank-side debit looks like a card bill payment, and why; [layer] is which check found it (L1 wording ... L4 service). */
data class CardBillMatch(
    val kind: CardBillKind,
    val cardLast4: String?,
    val reason: String,
    val layer: String
) : java.io.Serializable

/**
 * What the person said about card bill payments: this payment is / is not one ([yes], [no], by message id), and every
 * payment to this payee is / is not one ([payeesYes], [payeesNo], by payee key). Their word beats what is detected.
 */
data class CardBillDecisions(
    val yes: Set<Long> = emptySet(),
    val no: Set<Long> = emptySet(),
    val payeesYes: Set<String> = emptySet(),
    val payeesNo: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = yes.isEmpty() && no.isEmpty() && payeesYes.isEmpty() && payeesNo.isEmpty()
}

interface CardBillRepository {
    val state: StateFlow<CardBillDecisions>

    /**
     * Records the answer to "is this a credit card bill payment?". With [always] (and a [payeeKey]) every payment to that
     * payee, now and later, is treated the same way.
     */
    fun answer(id: Long, payeeKey: String?, isBillPayment: Boolean, always: Boolean)

    /** Takes back an answer given for [id] (and for [payeeKey] when given). */
    fun forget(id: Long, payeeKey: String?)
}

object NoCardBills : CardBillRepository {
    override val state: StateFlow<CardBillDecisions> = MutableStateFlow(CardBillDecisions())
    override fun answer(id: Long, payeeKey: String?, isBillPayment: Boolean, always: Boolean) = Unit
    override fun forget(id: Long, payeeKey: String?) = Unit
}

/** In-memory implementation, for tests and as the base of persisted ones. */
open class MemoryCardBills(initial: CardBillDecisions = CardBillDecisions()) : CardBillRepository {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<CardBillDecisions> get() = flow

    override fun answer(id: Long, payeeKey: String?, isBillPayment: Boolean, always: Boolean) {
        flow.value = flow.value.let { d ->
            val withMsg = if (isBillPayment) d.copy(yes = d.yes + id, no = d.no - id) else d.copy(no = d.no + id, yes = d.yes - id)
            if (!always || payeeKey == null) withMsg
            else if (isBillPayment) withMsg.copy(payeesYes = withMsg.payeesYes + payeeKey, payeesNo = withMsg.payeesNo - payeeKey)
            else withMsg.copy(payeesNo = withMsg.payeesNo + payeeKey, payeesYes = withMsg.payeesYes - payeeKey)
        }
    }

    override fun forget(id: Long, payeeKey: String?) {
        flow.value = flow.value.let { d ->
            d.copy(
                yes = d.yes - id, no = d.no - id,
                payeesYes = if (payeeKey == null) d.payeesYes else d.payeesYes - payeeKey,
                payeesNo = if (payeeKey == null) d.payeesNo else d.payeesNo - payeeKey
            )
        }
    }
}
