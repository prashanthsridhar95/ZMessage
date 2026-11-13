package com.zyke.message.domain.accounts

import com.zyke.message.domain.networth.NetWorth
import java.math.BigDecimal
import java.time.LocalDate

/** One credit card's dues: what is left of its latest statement ([billed]) and what was spent since ([unbilled]). */
data class CardDue(
    val key: String,
    val title: String,
    val last4: String,
    /** Still owed on the latest statement: nothing once it is paid, only the rest when part of it was. */
    val billed: BigDecimal,
    /** The latest statement's total as the bank stated it; null when no statement has been seen. */
    val statementTotal: BigDecimal?,
    /** Spent since the statement date. When the card's cycle is not known this is the spending of the current month. */
    val unbilled: BigDecimal,
    /** [unbilled] is the month's spending because the card's statement day is not known. */
    val unbilledApprox: Boolean,
    val dueDate: LocalDate?,
    val billPaid: Boolean
) {
    val total: BigDecimal get() = billed + unbilled

    /** Nothing is owed, billed or not. */
    val isClear: Boolean get() = billed.signum() <= 0 && unbilled.signum() <= 0
}

/** The bill that falls due first: [overdue] when its date has passed and it is still owed. */
data class NextDue(val key: String, val title: String, val date: LocalDate, val amount: BigDecimal, val overdue: Boolean)

/** What every credit card owes together: [billed] + [unbilled] = [total]; [approx] when any card's unbilled is the month's spending. */
data class CardDuesSummary(
    val cards: List<CardDue>,
    val billed: BigDecimal,
    val unbilled: BigDecimal,
    val approx: Boolean,
    val next: NextDue?
) {
    val total: BigDecimal get() = billed + unbilled
    val hasCards: Boolean get() = cards.isNotEmpty()
}

object CardDues {
    /** The credit cards among [accounts] (a debit card owes nothing), added up and ordered for the breakdown. */
    fun summarize(accounts: List<AccountSummary>, today: LocalDate): CardDuesSummary {
        val cards = accounts.filter { it.kind == AccountKind.CREDIT_CARD }.distinctBy { it.key }.map { due(it) }
        val ordered = order(cards)
        val billed = cards.fold(BigDecimal.ZERO) { sum, c -> sum + c.billed }
        val unbilled = cards.fold(BigDecimal.ZERO) { sum, c -> sum + c.unbilled }
        val soonest = cards.filter { it.billed.signum() > 0 && it.dueDate != null }
            .minWithOrNull(compareBy<CardDue> { it.dueDate?.toEpochDay() ?: Long.MAX_VALUE }.thenByDescending { it.billed }.thenBy { it.key })
        val soonestDate = soonest?.dueDate
        val next = if (soonest != null && soonestDate != null) NextDue(soonest.key, soonest.title, soonestDate, soonest.billed, overdue = soonestDate.isBefore(today)) else null
        return CardDuesSummary(ordered, billed, unbilled, approx = cards.any { it.unbilledApprox && it.unbilled.signum() > 0 }, next = next)
    }

    internal fun due(card: AccountSummary): CardDue {
        val known = card.unbilled
        // Without a statement day or a bill the cycle is unknown: the month's spending stands in for it, marked approximate.
        val approx = known == null && card.spent.signum() > 0
        val unbilled = (known ?: card.spent).max(BigDecimal.ZERO)
        return CardDue(
            key = card.key, title = card.title, last4 = card.last4,
            billed = NetWorth.billOwing(card), statementTotal = card.dueAmount,
            unbilled = unbilled, unbilledApprox = approx, dueDate = card.dueDate, billPaid = card.billPaid
        )
    }

    /** Cards with a bill to pay first, the soonest due first (no date last); then the rest by what they owe, then by name. */
    fun order(cards: List<CardDue>): List<CardDue> {
        val (owing, rest) = cards.partition { it.billed.signum() > 0 }
        return owing.sortedWith(
            compareBy<CardDue> { it.dueDate?.toEpochDay() ?: Long.MAX_VALUE }.thenByDescending { it.total }.thenBy { it.title.lowercase() }.thenBy { it.key }
        ) + rest.sortedWith(compareByDescending<CardDue> { it.total }.thenBy { it.title.lowercase() }.thenBy { it.key })
    }
}

/** What the Overview tab shows: the bank total, the card dues, and what is left once they are paid. */
data class AccountsOverviewModel(
    val totalBalance: BigDecimal?,
    val countedBalances: Int,
    val dues: CardDuesSummary
) {
    /** Bank balance minus everything the cards will ask for (billed and unbilled); null with no balance or no card. May be negative. */
    val leftAfterDues: BigDecimal? get() = if (totalBalance == null || !dues.hasCards) null else totalBalance - dues.total

    companion object {
        fun build(overview: AccountsOverview, today: LocalDate): AccountsOverviewModel =
            AccountsOverviewModel(overview.totalBalance, overview.countedBalances, CardDues.summarize(overview.accounts, today))
    }
}
