package com.zyke.message.domain.ledger

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind

/** A plain-language answer to "why is this (not) in my income and spending?". */
object CountedReason {
    /**
     * @param isRefundCredit the credit is a refund of an earlier payment (see RefundTracker), which is shown on its own
     * rather than as income
     */
    fun explain(message: ClassifiedMessage, isRefundCredit: Boolean = false): String {
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
            tx.isCardPayment -> "Not counted: card bill payment, which is money moving between your own accounts"
            tx.isReceived && isRefundCredit -> "Not counted as income: a refund is tracked separately"
            tx.isReceived -> "Counted as income: money received" + payerSuffix(tx)
            else -> "Counted as spending: a debit" + payeeSuffix(tx)
        }
    }

    private fun payeeSuffix(tx: MessageContent.Transaction) =
        if (tx.payee.isBlank()) "" else " to ${tx.payee}"

    private fun payerSuffix(tx: MessageContent.Transaction) =
        if (tx.payee.isBlank()) "" else " from ${tx.payee}"
}
