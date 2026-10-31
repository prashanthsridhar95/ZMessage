package com.zyke.message.domain.review

import com.zyke.message.domain.insights.Subscriptions
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.Month
import java.time.ZoneId

data class YearCategory(val category: MessageCategory, val amount: BigDecimal)

data class YearPayee(val name: String, val amount: BigDecimal, val count: Int)

data class YearPayment(val payee: String, val amount: BigDecimal, val date: LocalDate)

data class YearMonth(val month: Month, val spent: BigDecimal, val income: BigDecimal)

/** A calendar year of income and spending, for the "Your year" page and its shareable card. */
data class YearInReview(
    val year: Int,
    val income: BigDecimal,
    val spent: BigDecimal,
    /** Always 12 entries, January first; months without messages are zero. */
    val months: List<YearMonth>,
    val topCategories: List<YearCategory>,
    val topPayees: List<YearPayee>,
    val biggestPayment: YearPayment?,
    /** Month with the most / least spending among months that have data; null when fewer than two such months. */
    val busiestMonth: Month?,
    val quietestMonth: Month?,
    val transactionCount: Int,
    /** Days from the first message of the year to the last (or today, for the current year) that spending covers. */
    val daysCovered: Int,
    val averageDailySpend: BigDecimal,
    /** Longest run of days with no spending, within the days covered. */
    val longestNoSpendStreak: Int,
    val subscriptionsTotal: BigDecimal,
    /** Distinct months of the year that have at least one counted transaction. */
    val monthsOfData: Int
) {
    val net: BigDecimal get() = income - spent

    /** (income - spent) / income as a whole percent; null when nothing came in. */
    val savingsRatePercent: Int?
        get() = income.takeIf { it.signum() > 0 }
            ?.let { (income - spent).multiply(HUNDRED).divide(it, 0, RoundingMode.HALF_UP).toInt() }

    val isEmpty: Boolean get() = transactionCount == 0

    companion object {
        private val HUNDRED = BigDecimal(100)
        private const val TOP = 5
        const val MIN_MONTHS_FOR_PAGE = 2

        /** How many distinct months in [year] have counted transactions; cheap check for showing an entry point. */
        fun monthsWithData(messages: List<ClassifiedMessage>, year: Int, zone: ZoneId): Int =
            messages.mapNotNull { flow(it, zone) }.filter { it.date.year == year }.map { it.date.month }.distinct().size

        private class Flow(
            val payee: String,
            val category: MessageCategory,
            val amount: BigDecimal,
            val received: Boolean,
            val date: LocalDate
        )

        /** Card-bill payments, set-aside messages, promotions and muted senders are neither income nor spending. */
        private fun flow(message: ClassifiedMessage, zone: ZoneId): Flow? {
            if (message.setAside != null || message.isPromotional || message.isMuted) return null
            val tx = message.content as? MessageContent.Transaction ?: return null
            if (tx.isCardPayment) return null
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
            val date = LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone)
            val name = message.merchant.name.ifBlank { tx.payee }.ifBlank { "Unknown" }
            return Flow(name, message.category, amount, tx.isReceived, date)
        }

        fun build(messages: List<ClassifiedMessage>, year: Int, zone: ZoneId, today: LocalDate? = null): YearInReview {
            val flows = messages.mapNotNull { flow(it, zone) }.filter { it.date.year == year }
            val spends = flows.filter { !it.received }
            val credits = flows.filter { it.received }

            val spent = spends.sumOf { it.amount }
            val income = credits.sumOf { it.amount }

            val months = Month.entries.map { m ->
                YearMonth(
                    m,
                    spends.filter { it.date.month == m }.sumOf { it.amount },
                    credits.filter { it.date.month == m }.sumOf { it.amount }
                )
            }
            val monthsWithData = flows.map { it.date.month }.distinct()
            val withSpend = months.filter { it.spent.signum() > 0 }
            val busiest = withSpend.takeIf { it.size >= 2 }?.maxByOrNull { it.spent }?.month
            val quietest = withSpend.takeIf { it.size >= 2 }?.minByOrNull { it.spent }?.month

            val topCategories = spends.groupBy { it.category }
                .map { (c, f) -> YearCategory(c, f.sumOf { it.amount }) }
                .sortedByDescending { it.amount }.take(TOP)
            val topPayees = spends.groupBy { it.payee }
                .map { (p, f) -> YearPayee(p, f.sumOf { it.amount }, f.size) }
                .sortedByDescending { it.amount }.take(TOP)
            val biggest = spends.maxByOrNull { it.amount }?.let { YearPayment(it.payee, it.amount, it.date) }

            val first = flows.minOfOrNull { it.date }
            val yearEnd = LocalDate.of(year, 12, 31)
            val last = flows.maxOfOrNull { it.date }
            val coverEnd = when {
                last == null -> null
                today != null && today.year == year -> maxOf(today, last)
                else -> last
            }
            val daysCovered = if (first == null || coverEnd == null) 0 else (coverEnd.toEpochDay() - first.toEpochDay() + 1).toInt()
            val average = if (daysCovered > 0) spent.divide(BigDecimal(daysCovered), 2, RoundingMode.HALF_UP) else BigDecimal.ZERO

            val spendDays = spends.map { it.date }.toSet()
            var best = 0
            var run = 0
            if (first != null && coverEnd != null) {
                var d: LocalDate = first
                while (!d.isAfter(coverEnd)) {
                    if (d in spendDays) run = 0 else { run++; if (run > best) best = run }
                    d = d.plusDays(1)
                }
            }

            val subKeys = Subscriptions.find(messages, (today ?: yearEnd).atTime(12, 0).atZone(zone).toInstant(), zone)
                .map { it.key }.toSet()
            val subs = spends.filter { Subscriptions.keyFor(it.payee) in subKeys }.sumOf { it.amount }

            return YearInReview(
                year, income, spent, months, topCategories, topPayees, biggest, busiest, quietest,
                flows.size, daysCovered, average, best, subs, monthsWithData.size
            )
        }
    }
}
