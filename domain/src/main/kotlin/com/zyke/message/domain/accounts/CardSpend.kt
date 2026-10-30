package com.zyke.message.domain.accounts

import java.math.BigDecimal

/** What each credit card has been used for in the current month, against one limit for all of them together. */
data class CardSpendStatus(
    val limit: BigDecimal,
    val used: BigDecimal,
    val perCard: List<Pair<String, BigDecimal>>
) {
    val remaining: BigDecimal get() = (limit - used).max(BigDecimal.ZERO)
    val over: BigDecimal get() = (used - limit).max(BigDecimal.ZERO)
    val isOver: Boolean get() = used > limit
    /** 0 to 1 and beyond is held at 1. */
    val fraction: Float get() = if (limit.signum() <= 0) 0f else (used.toDouble() / limit.toDouble()).toFloat().coerceIn(0f, 1f)
    val isNear: Boolean get() = !isOver && fraction >= NEAR_AT

    private companion object {
        const val NEAR_AT = 0.8f
    }
}

object CardSpend {
    /** Spending on every credit card in the pay cycle, or null when no limit is set. The limit is shared: use it on any card. */
    fun status(accounts: List<AccountSummary>, limit: BigDecimal?): CardSpendStatus? {
        if (limit == null || limit.signum() <= 0) return null
        val cards = accounts.filter { it.kind == AccountKind.CREDIT_CARD }
        return CardSpendStatus(
            limit = limit,
            used = cards.fold(BigDecimal.ZERO) { sum, card -> sum + card.spent },
            perCard = cards.filter { it.spent.signum() > 0 }.sortedByDescending { it.spent }.map { it.title to it.spent }
        )
    }
}
