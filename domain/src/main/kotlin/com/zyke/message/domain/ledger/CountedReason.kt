package com.zyke.message.domain.ledger

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind

/** A plain-language answer to "why is this (not) in my income and spending?". */
object CountedReason {
    private val WALLET = Regex("(?i)\\bwallet\\b")

    /**
     * @param isRefundCredit the credit is a refund of an earlier payment (see RefundTracker), which is shown on its own
     * rather than as income
     */
    fun explain(message: ClassifiedMessage, isRefundCredit: Boolean = false): String {
        val same = message.samePayment
        if (same != null && same.kind == SamePaymentKind.CERTAIN) return "Not counted: same payment as the ${same.primaryLabel}, which is counted"
        val maybe = if (same != null) ", though it may be the same payment as the ${same.primaryLabel}" else ""
        val tx = message.content as? MessageContent.Transaction
            ?: return when (val content = message.content) {
                is MessageContent.Notice -> when (content.kind) {
                    NoticeKind.AUTOPAY_FAILED -> "Not counted: the payment failed, so no money moved"
                    NoticeKind.REFUND_PENDING -> "Not counted: a refund on the way is tracked separately until the money arrives"
                    else -> "Not counted: a notice, not a payment"
                }
                is MessageContent.CardBill -> "Not counted: a card bill is a reminder; the payment itself counts when it is made"
                is MessageContent.ScheduledPayment -> "Not counted: an upcoming or changed auto-debit is not money spent yet"
                else -> "Not counted: this is not a payment"
            }
        return when {
            message.setAside == SetAside.EXCLUDED -> "Not counted: you left it out of your totals"
            message.setAside == SetAside.TRANSFER -> "Not counted: a transfer between your own accounts"
            tx.isCardPayment && tx.isReceived && WALLET.containsMatchIn(tx.payee) -> "Not counted as income: store credit added to a wallet is not money in your bank"
            tx.isCardPayment && InternalMoves.reason(tx) != null -> InternalMoves.reason(tx)!!
            tx.isCardPayment && !tx.isReceived && (message.cardBill?.kind == CardBillKind.CERTAIN || CardBillPayments.isCountedAsBillPayment(message)) ->
                "Not counted: credit card bill payment (the swipes were already counted)"
            tx.isCardPayment -> "Not counted: card bill payment, which is money moving between your own accounts"
            tx.isReceived && isRefundCredit -> "Not counted as income: a refund is tracked separately"
            tx.isReceived -> "Counted as income: money received" + payerSuffix(tx) + maybe
            message.cardBill?.kind == CardBillKind.SUSPECTED -> "Counted as spending: a debit" + payeeSuffix(tx) + ", though it may be a credit card bill payment"
            else -> "Counted as spending: a debit" + payeeSuffix(tx) + maybe
        }
    }

    private fun payeeSuffix(tx: MessageContent.Transaction) =
        if (tx.payee.isBlank()) "" else " to ${tx.payee}"

    private fun payerSuffix(tx: MessageContent.Transaction) =
        if (tx.payee.isBlank()) "" else " from ${tx.payee}"
}
