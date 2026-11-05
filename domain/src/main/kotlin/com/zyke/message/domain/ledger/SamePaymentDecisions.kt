package com.zyke.message.domain.ledger

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** How sure the app is that a message is the same payment as another one. */
enum class SamePaymentKind { CERTAIN, SUSPECTED }

/**
 * That this message (a payment gateway's, a merchant's or an app's text) is the same payment as [primaryId], the message
 * that is counted instead, and why. [layer] is which check found it: "ref" (a shared reference number), "rule" (the person
 * taught it for this sender), "you", "name" (same merchant) or "time" (same amount, minutes apart).
 */
data class SamePaymentLink(
    val kind: SamePaymentKind,
    val primaryId: Long,
    /** What the counted message is called on screen: "HDFC debit". */
    val primaryLabel: String,
    val reason: String,
    val layer: String
) : java.io.Serializable

/**
 * What the person said about which messages are one payment: this message is the same payment as that one ([yes], by
 * message id to the id of the counted message), this one is a separate payment ([no]), and every message from this
 * sender or payee is / is not a repeat of a bank debit ([sendersYes], [sendersNo], by payee key). Their word beats what is detected.
 */
data class SamePaymentDecisions(
    val yes: Map<Long, Long> = emptyMap(),
    val no: Set<Long> = emptySet(),
    val sendersYes: Set<String> = emptySet(),
    val sendersNo: Set<String> = emptySet()
) {
    val isEmpty: Boolean get() = yes.isEmpty() && no.isEmpty() && sendersYes.isEmpty() && sendersNo.isEmpty()
}

interface SamePaymentRepository {
    val state: StateFlow<SamePaymentDecisions>

    /**
     * Records the answer to "is [id] the same payment as [primaryId]?". With [always] (and a [senderKey]) every message from
     * that sender or payee that matches a bank debit in amount and time is treated the same way, now and later.
     */
    fun answer(id: Long, primaryId: Long?, senderKey: String?, same: Boolean, always: Boolean)

    /** Takes back an answer given for [id] (and for [senderKey] when given). */
    fun forget(id: Long, senderKey: String?)
}

object NoSamePayments : SamePaymentRepository {
    override val state: StateFlow<SamePaymentDecisions> = MutableStateFlow(SamePaymentDecisions())
    override fun answer(id: Long, primaryId: Long?, senderKey: String?, same: Boolean, always: Boolean) = Unit
    override fun forget(id: Long, senderKey: String?) = Unit
}

/** In-memory implementation, for tests and as the base of persisted ones. */
open class MemorySamePayments(initial: SamePaymentDecisions = SamePaymentDecisions()) : SamePaymentRepository {
    private val flow = MutableStateFlow(initial)
    override val state: StateFlow<SamePaymentDecisions> get() = flow

    override fun answer(id: Long, primaryId: Long?, senderKey: String?, same: Boolean, always: Boolean) {
        flow.value = flow.value.let { d ->
            // "Same" needs the message it is the same as; without one there is nothing to record.
            val withMsg = if (same) {
                if (primaryId == null) d else d.copy(yes = d.yes + (id to primaryId), no = d.no - id)
            } else d.copy(no = d.no + id, yes = d.yes - id)
            if (!always || senderKey == null) withMsg
            else if (same) withMsg.copy(sendersYes = withMsg.sendersYes + senderKey, sendersNo = withMsg.sendersNo - senderKey)
            else withMsg.copy(sendersNo = withMsg.sendersNo + senderKey, sendersYes = withMsg.sendersYes - senderKey)
        }
    }

    override fun forget(id: Long, senderKey: String?) {
        flow.value = flow.value.let { d ->
            d.copy(
                yes = d.yes - id, no = d.no - id,
                sendersYes = if (senderKey == null) d.sendersYes else d.sendersYes - senderKey,
                sendersNo = if (senderKey == null) d.sendersNo else d.sendersNo - senderKey
            )
        }
    }
}
