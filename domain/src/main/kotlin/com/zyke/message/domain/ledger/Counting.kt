package com.zyke.message.domain.ledger

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.util.toBigDecimalOrNull
import java.math.BigDecimal

/** What one message is worth to the person's income and spending. See [Counting.of]. */
sealed interface Counted {
    /** Money that left the person's pocket for something. */
    data class Spend(val amount: BigDecimal) : Counted

    /** Money that came into a bank account from outside (salary, a friend paying back). */
    data class Income(val amount: BigDecimal) : Counted

    /** Money given back on a card (a refund, cashback, a reversal): it lowers what was spent, it is never income. */
    data class Refund(val amount: BigDecimal, val category: MessageCategory) : Counted

    /** Kept out of both; [reason] is the plain-language answer to "why". */
    data class NotCounted(val reason: String) : Counted
}

/**
 * The ONE rule for whether a message is spending, income, a refund or neither. Every total (dashboard, month summary,
 * year in review, forecasts, budgets, limits, subscriptions ...) asks here instead of looking at
 * isCardPayment / setAside / isReceived itself, so they cannot disagree.
 *
 * Spending is counted when the money is spent (a card swipe on the day of the swipe). Paying a card's bill afterwards is
 * money moving between the person's own accounts: [Counted.NotCounted], though the bank balance still falls by it.
 * A credit on a card that is not a bill payment is a [Counted.Refund], never income.
 * One payment often makes several texts (the bank's debit, a gateway's confirmation, the merchant's receipt): the bank's
 * debit counts and the others are [Counted.NotCounted] once linked to it (see [SamePayment]).
 */
object Counting {
    fun of(message: ClassifiedMessage): Counted {
        // A gateway's, merchant's or app's text for a payment the bank's debit already shows: the debit is the payment.
        if (SamePayment.isRepeat(message)) return Counted.NotCounted(CountedReason.explain(message))
        val tx = message.content as? MessageContent.Transaction ?: return Counted.NotCounted(CountedReason.explain(message))
        if (message.setAside != null || tx.isCardPayment) return Counted.NotCounted(CountedReason.explain(message))
        val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return Counted.NotCounted("Not counted: no amount")
        return when {
            tx.isReceived && tx.isCard -> Counted.Refund(amount, message.category)
            tx.isReceived -> Counted.Income(amount)
            else -> Counted.Spend(amount)
        }
    }

    /** What was spent, or null when the message is not spending. */
    fun spend(message: ClassifiedMessage): BigDecimal? = (of(message) as? Counted.Spend)?.amount

    /** What came in as income, or null. */
    fun income(message: ClassifiedMessage): BigDecimal? = (of(message) as? Counted.Income)?.amount

    /** Spending as a positive amount, a card refund as a negative one, anything else null: what a spending total adds up. */
    fun signedSpend(message: ClassifiedMessage): BigDecimal? = when (val c = of(message)) {
        is Counted.Spend -> c.amount
        is Counted.Refund -> c.amount.negate()
        else -> null
    }

    /** What a card gave back, or null. */
    fun refund(message: ClassifiedMessage): BigDecimal? = (of(message) as? Counted.Refund)?.amount

    /** Spending minus card refunds over [messages]: the one figure "spent" means in a total. */
    fun netSpent(messages: Iterable<ClassifiedMessage>): BigDecimal {
        var sum = BigDecimal.ZERO
        for (m in messages) when (val c = of(m)) {
            is Counted.Spend -> sum += c.amount
            is Counted.Refund -> sum -= c.amount
            else -> Unit
        }
        return sum
    }
}
