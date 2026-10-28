package com.zyke.message.domain.forecast

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** A monthly limit worth setting for a category, from what has been spent in it before. */
data class LimitSuggestion(
    val category: MessageCategory,
    /** What the category averaged over the recent cycles. */
    val usual: BigDecimal,
    val suggested: BigDecimal,
    /** The limit already set for it, if any. */
    val current: BigDecimal?
)

object LimitSuggestions {
    private const val HISTORY_CYCLES = 3
    private val MIN_USUAL = BigDecimal(500)
    private val SHAVE = BigDecimal("0.90")
    private val NEAR = BigDecimal("0.10")

    /**
     * For each flexible category with a spending history, a limit a little under its usual (about a tenth less), so
     * a limit asks for a small improvement and not a sacrifice. When the plan says how much is spendable and the
     * limits together exceed it, they are scaled down to fit.
     */
    fun build(
        messages: List<ClassifiedMessage>,
        budgets: Map<MessageCategory, BigDecimal>,
        spendable: BigDecimal?,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule
    ): List<LimitSuggestion> {
        val today = LocalDate.ofInstant(now, zone)
        val ranges = (1..HISTORY_CYCLES).map { cycle.shifted(today, -it) }

        val spends = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (tx.isReceived || tx.isCardPayment || message.category !in SpendForecasts.FLEXIBLE) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            Triple(message.category, LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone), amount)
        }
        // Only cycles that have any spending count, so a phone that is new to the app is not read as thrift.
        val active = ranges.filter { range -> spends.any { it.second in range.start..range.endInclusive } }
        if (active.isEmpty()) return emptyList()

        val raw = SpendForecasts.FLEXIBLE.mapNotNull { category ->
            val total = spends.filter { it.first == category && active.any { r -> it.second in r.start..r.endInclusive } }
                .fold(BigDecimal.ZERO) { sum, s -> sum + s.third }
            val usual = total.divide(BigDecimal(active.size), 0, RoundingMode.HALF_UP)
            if (usual < MIN_USUAL) null else category to usual
        }
        if (raw.isEmpty()) return emptyList()

        var limits = raw.associate { (category, usual) -> category to round(usual.multiply(SHAVE)) }
        val sum = limits.values.fold(BigDecimal.ZERO, BigDecimal::add)
        if (spendable != null && spendable.signum() > 0 && sum > spendable) {
            val factor = spendable.divide(sum, 4, RoundingMode.DOWN)
            limits = limits.mapValues { round(it.value.multiply(factor)) }
        }

        return raw.map { (category, usual) ->
            LimitSuggestion(category, usual, limits.getValue(category), budgets[category])
        }
            // A limit that is already about right needs no suggestion.
            .filter { s -> s.current == null || (s.current - s.suggested).abs() > s.suggested.multiply(NEAR) }
            .sortedByDescending { it.usual }
    }

    /** To a tidy number: the nearest 50 under 2,000, else the nearest 100. */
    private fun round(amount: BigDecimal): BigDecimal {
        val step = if (amount < BigDecimal(2000)) BigDecimal(50) else BigDecimal(100)
        return amount.divide(step, 0, RoundingMode.HALF_UP).multiply(step).max(step)
    }
}
