package com.zyke.message.domain.accounts

import com.zyke.message.domain.insights.AccountBalance
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Small, pure facts the Accounts tab draws from an account: trend, flow, utilisation and how fresh a balance is. */
object AccountInsights {
    /** A bank balance under this is worth a nudge. */
    val LOW_BALANCE: BigDecimal = BigDecimal(1000)

    /** A stated balance older than this many days is flagged as possibly out of date. */
    const val STALE_BALANCE_DAYS = 7L

    /** The last [maxPoints] balances an account stated (oldest first), one per day (the day's latest), for a sparkline. */
    fun balanceSeries(history: List<AccountBalance>, maxPoints: Int = 12): List<BigDecimal> =
        history.sortedWith(compareBy({ it.asOf }, { it.at }, { it.messageId }))
            .groupBy { it.asOf }
            .map { (_, day) -> day.last().amount }
            .takeLast(maxPoints)

    /** Share of this cycle's money movement that came in, 0..1; null when nothing moved. */
    fun inShare(spent: BigDecimal, received: BigDecimal): Float? {
        val total = spent.abs() + received.abs()
        if (total.signum() == 0) return null
        return received.abs().divide(total, 4, RoundingMode.HALF_UP).toFloat()
    }

    /** How much of a card's limit is in use, 0..1 (capped); null when the limit or the owed amount is unknown. */
    fun utilisation(owed: BigDecimal?, limit: Long?): Float? {
        if (owed == null || limit == null || limit <= 0L) return null
        val fraction = owed.max(BigDecimal.ZERO).divide(BigDecimal(limit), 4, RoundingMode.HALF_UP).toFloat()
        return fraction.coerceIn(0f, 1f)
    }

    fun isLowBalance(balance: BigDecimal?): Boolean = balance != null && balance < LOW_BALANCE

    fun isStale(asOf: LocalDate?, today: LocalDate): Boolean = asOf != null && ChronoUnit.DAYS.between(asOf, today) > STALE_BALANCE_DAYS

    /** "today", "yesterday", "5 days ago", "3 weeks ago", "2 months ago". */
    fun age(asOf: LocalDate, today: LocalDate): String {
        val days = ChronoUnit.DAYS.between(asOf, today)
        return when {
            days <= 0L -> "today"
            days == 1L -> "yesterday"
            days < 14L -> "$days days ago"
            days < 60L -> "${days / 7} weeks ago"
            else -> "${days / 30} months ago"
        }
    }
}
