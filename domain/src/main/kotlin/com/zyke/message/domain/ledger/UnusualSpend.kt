package com.zyke.message.domain.ledger

import java.math.BigDecimal

/** The one rule for "this debit is far above what the person usually pays", shared by the review queue and the heads-up alert. */
object UnusualSpend {
    /** A debit this many times the median is unusual. */
    const val FACTOR = 5L

    /** The median is taken over debits in this many days. */
    const val HISTORY_DAYS = 90L

    /** Fewer debits than this and there is no "usual" to compare with. */
    const val MIN_DEBITS = 10

    /** The amount from which a debit is unusual, given the amounts of the debits in the history window; null with too few. */
    fun threshold(amounts: List<BigDecimal>): BigDecimal? {
        if (amounts.size < MIN_DEBITS) return null
        val sorted = amounts.sorted()
        val mid = sorted.size / 2
        val median = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]).divide(BigDecimal(2))
        if (median.signum() <= 0) return null
        return median.multiply(BigDecimal(FACTOR))
    }

    fun isUnusual(amount: BigDecimal, history: List<BigDecimal>): Boolean = threshold(history)?.let { amount >= it } ?: false
}
