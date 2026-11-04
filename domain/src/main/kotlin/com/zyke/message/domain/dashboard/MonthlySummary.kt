package com.zyke.message.domain.dashboard

import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.ledger.Counted
import com.zyke.message.domain.util.toBigDecimalOrNull
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
import java.time.temporal.ChronoUnit

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
    val changes: List<CategoryChange>,
    /** Running spend through day 1..n of this cycle (n = days elapsed, today included). */
    val cumulativeSpent: List<BigDecimal> = emptyList(),
    /** Running spend through each day of the whole of last cycle. */
    val previousCumulativeSpent: List<BigDecimal> = emptyList(),
    /** Days in this cycle, whole. */
    val cycleDays: Int = 0,
    /** Run-rate guess of the whole cycle's spend; null while there is too little to go on. */
    val projectedSpent: BigDecimal? = null
) {
    /** Spent so far minus spent at the same point last month; negative means below last month. */
    val spentDelta: BigDecimal get() = spent - previousSpent

    /** Whole percent against last month at this point; null when last month had nothing. */
    val spentDeltaPercent: Int?
        get() = previousSpent.takeIf { it.signum() > 0 }
            ?.let { spentDelta.multiply(HUNDRED).divide(it, 0, RoundingMode.HALF_UP).toInt() }

    /** Plain-language headline for the hero. */
    val heroLine: String
        get() {
            val amount = DigestFormat.rupees(spentDelta.abs())
            return when {
                previousSpent.signum() <= 0 && spent.signum() <= 0 -> "No spending yet this month or at this point last month"
                previousSpent.signum() <= 0 -> "Nothing was spent at this point last month; you have spent $amount"
                spentDelta.signum() < 0 -> "You are $amount below last month at this point"
                spentDelta.signum() > 0 -> "You are $amount above last month at this point"
                else -> "You are level with last month at this point"
            }
        }

    /** Projected month-end against last month's whole total; null when either is unknown. */
    val projectedVsLastMonth: BigDecimal?
        get() = projectedSpent?.let { p -> previousCumulativeSpent.lastOrNull()?.let { p - it } }

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
        private const val MIN_DAYS_FOR_PROJECTION = 3

        /** Straight run-rate: spent / elapsed * total. Null before [MIN_DAYS_FOR_PROJECTION] days or with nothing spent. */
        fun project(spent: BigDecimal, elapsedDays: Int, totalDays: Int): BigDecimal? {
            if (totalDays <= 0 || elapsedDays <= 0 || spent.signum() <= 0) return null
            if (elapsedDays >= totalDays) return spent
            if (elapsedDays < MIN_DAYS_FOR_PROJECTION) return null
            return spent.multiply(BigDecimal(totalDays)).divide(BigDecimal(elapsedDays), 0, RoundingMode.HALF_UP)
        }

        /** Running totals for each of [days] days starting at [start]; a day with no spend repeats the total. */
        fun cumulative(flows: List<Pair<LocalDate, BigDecimal>>, start: LocalDate, days: Int): List<BigDecimal> {
            val perDay = Array(days.coerceAtLeast(0)) { BigDecimal.ZERO }
            flows.forEach { (date, amount) ->
                val i = ChronoUnit.DAYS.between(start, date).toInt()
                if (i in perDay.indices) perDay[i] = perDay[i] + amount
            }
            var running = BigDecimal.ZERO
            return perDay.map { running += it; running }
        }

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

            val lastCycle = cycle.shifted(today, -1)
            val thisCycle = cycle.containing(today)
            val elapsed = (ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1).toInt()
            val cycleDays = (ChronoUnit.DAYS.between(thisCycle.start, thisCycle.endInclusive) + 1).toInt()
            val lastDays = (ChronoUnit.DAYS.between(lastCycle.start, lastCycle.endInclusive) + 1).toInt()
            val spentFlows = flows.filter { !it.received }
            val cumThis = cumulative(spentFlows.filter { it.date in range }.map { it.date to it.amount }, range.start, elapsed)
            val cumPrev = cumulative(spentFlows.filter { it.date in lastCycle }.map { it.date to it.amount }, lastCycle.start, lastDays)

            return MonthlySummary(
                range = range,
                previousRange = previousRange,
                income = current.filter { it.received }.sumOf { it.amount },
                spent = current.filter { !it.received }.sumOf { it.amount },
                previousIncome = previous.filter { it.received }.sumOf { it.amount },
                previousSpent = previous.filter { !it.received }.sumOf { it.amount },
                changes = changes,
                cumulativeSpent = cumThis,
                previousCumulativeSpent = cumPrev,
                cycleDays = cycleDays,
                projectedSpent = project(cumThis.lastOrNull() ?: BigDecimal.ZERO, elapsed, cycleDays)
            )
        }

        /** Card-bill payments and anything set aside (left out, or a transfer between own accounts) are neither income nor spending. */
        private fun flow(message: ClassifiedMessage, zone: ZoneId): Flow? {
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone)
            return when (val counted = Counting.of(message)) {
                is Counted.Spend -> Flow(message.category, counted.amount, false, date)
                is Counted.Income -> Flow(message.category, counted.amount, true, date)
                // A card refund lowers what was spent; it is never income.
                is Counted.Refund -> Flow(message.category, counted.amount.negate(), false, date)
                is Counted.NotCounted -> null
            }
        }
    }
}
