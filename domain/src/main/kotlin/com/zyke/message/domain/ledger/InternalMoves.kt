package com.zyke.message.domain.ledger

import com.zyke.message.domain.model.MessageContent

/** What kind of money-moving-between-own-pockets a transaction flagged as such is: a card bill, a wallet top-up, a reversal. */
object InternalMoves {
    private val REVERSAL = Regex("(?i)\\b(?:reversal|reversed)\\b")
    private val WALLET = Regex("(?i)\\b(?:wallet|upi lite)\\b")

    /** A short word for what happened, for the "Paid"/"Received" line of the detail; null for an ordinary card bill payment. */
    fun label(tx: MessageContent.Transaction): String? = when {
        !tx.isCardPayment -> null
        tx.isReceived && REVERSAL.containsMatchIn(tx.payee) -> "Reversed"
        tx.isReceived && WALLET.containsMatchIn(tx.payee) -> "Added to wallet"
        !tx.isReceived && WALLET.containsMatchIn(tx.payee) -> "Topped up"
        else -> null
    }

    /** The explanation for the totals, or null when the card-bill wording fits. */
    fun reason(tx: MessageContent.Transaction): String? = when {
        !tx.isCardPayment -> null
        tx.isReceived && REVERSAL.containsMatchIn(tx.payee) -> "Not counted as income: a reversal returns money from a payment that did not go through"
        !tx.isReceived && WALLET.containsMatchIn(tx.payee) -> "Not counted as spending: topping up your UPI Lite wallet moves your own money"
        else -> null
    }
}
