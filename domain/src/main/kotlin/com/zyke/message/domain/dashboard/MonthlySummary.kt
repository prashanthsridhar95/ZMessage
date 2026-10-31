package com.zyke.message.domain.dashboard

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.insights.DigestFormat
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** How one category's spending moved between two like-for-like windows. */
data class CategoryChange(
    val category: MessageCategory,
    val current: BigDecimal,
    val previous: BigDecimal
) {
    val delta: BigDecimal get() = current - previous

    /** Whole percent against last month; null when there was nothing to compare with. */
    val percent: Int?
        get() = previous.takeIf { it.signum() > 0 }
            ?.let { delta.multiply(HUNDRED).divide(it, 0, RoundingMode.HALF_UP).toInt() }

    /** Plain-language line, e.g. "Food up 18% (₹2,400 more)". */
    val text: String
        get() {
            val name = category.displayString
            val amount = DigestFormat.rupees(delta.abs())
            return when {
                previous.signum() <= 0 -> "$name is new this month ($amount)"
                delta.signum() > 0 -> "$name up ${percent}% ($amount more)"
                else -> "$name down ${-(percent ?: 0)}% ($amount less)"
            }
        }

    private companion object {
        val HUNDRED = BigDecimal(100)
    }
}

/** This month so far against the same days of last month. */
data class MonthlySummary(
    val range: DateRange,
    val previousRange: DateRange?,
    val income: BigDecimal,
    val spent: BigDecimal,
    val previousIncome: BigDecimal,
    val previousSpent: BigDecimal,
    /** Biggest movers first; categories that did not change are left out. */
    val changes: List<CategoryChange>
) {
    val net: BigDecimal get() = income - spent

    /** (income - spent) / income as a whole percent; null ("Not available") when nothing came in. */
    val savingsRatePercent: Int? get() = ratePercent(income, spent)

    val previousSavingsRatePercent: Int? get() = ratePercent(previousIncome, previousSpent)

    /** Plain-language sentences for income, spending, net and savings rate. */
    val lines: List<String>
        get() {
            val rate = savingsRatePercent?.let { "Savings rate $it%" } ?: "Savings rate: not available"
            val netText = if (net.signum() >= 0) "Net ${DigestFormat.rupees(net)} saved" else "Net ${DigestFormat.rupees(net.abs())} over"
            return listOf("Income ${DigestFormat.rupees(income)}", "Spent ${DigestFormat.rupees(spent)}", netText, rate)
        }

    companion object {
        private val HUNDRED = BigDecimal(100)
        private const val MAX_CHANGES = 8

        private fun ratePercent(income: BigDecimal, spent: BigDecimal): Int? =
            income.takeIf { it.signum() > 0 }
                ?.let { (income - spent).multiply(HUNDRED).divide(it, 0, RoundingMode.HALF_UP).toInt() }

        private class Flow(val category: MessageCategory, val amount: BigDecimal, val received: Boolean, val date: LocalDate)

        fun build(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId, cycle: CycleRule = CycleRule.CALENDAR): MonthlySummary {
            val today = LocalDate.ofInstant(now, zone)
            val range = DashboardBuilder.rangeFor(DashboardPeriod.THIS_MONTH, today, null, cycle)
            val previousRange = DashboardBuilder.previousRange(DashboardPeriod.THIS_MONTH, range, today, cycle)

            val flows = messages.mapNotNull { flow(it, zone) }
            val current = flows.filter { it.date in range }
            val previous = previousRange?.let { prev -> flows.filter { it.date in prev } }.orEmpty()

            val spentNow = current.filter { !it.received }.groupBy { it.category }.mapValues { (_, f) -> f.sumOf { it.amount } }
            val spentBefore = previous.filter { !it.received }.groupBy { it.category }.mapValues { (_, f) -> f.sumOf { it.amount } }
            val changes = (spentNow.keys + spentBefore.keys)
                .map { CategoryChange(it, spentNow[it] ?: BigDecimal.ZERO, spentBefore[it] ?: BigDecimal.ZERO) }
                .filter { it.delta.signum() != 0 }
                .sortedByDescending { it.delta.abs() }
                .take(MAX_CHANGES)

            return MonthlySummary(
                range = range,
                previousRange = previousRange,
                income = current.filter { it.received }.sumOf { it.amount },
                spent = current.filter { !it.received }.sumOf { it.amount },
                previousIncome = previous.filter { it.received }.sumOf { it.amount },
                previousSpent = previous.filter { !it.received }.sumOf { it.amount },
                changes = changes
            )
        }

        /** Card-bill payments and anything set aside (left out, or a transfer between own accounts) are neither income nor spending. */
        private fun flow(message: ClassifiedMessage, zone: ZoneId): Flow? {
            if (message.setAside != null) return null
            val tx = message.content as? MessageContent.Transaction ?: return null
            if (tx.isCardPayment) return null
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone)
            return Flow(message.category, amount, tx.isReceived, date)
        }
    }
}
