package com.zyke.message.domain.dashboard

import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.util.toBigDecimalOrNull
import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class LimitPeriod(val label: String, val noun: String) {
    DAILY("Per day", "today"),
    MONTHLY("Per month", "this month")
}

/** One limit and how much of it is used. */
data class LimitLine(
    val period: LimitPeriod,
    val limit: BigDecimal,
    val spent: BigDecimal
) {
    /** What is left; negative when the limit has been passed. */
    val remaining: BigDecimal get() = limit.subtract(spent)

    /** Passed the limit: the deficit is [deficit]. */
    val isOver: Boolean get() = remaining.signum() < 0

    val deficit: BigDecimal get() = remaining.negate().max(BigDecimal.ZERO)
    val available: BigDecimal get() = remaining.max(BigDecimal.ZERO)

    /** Share used, 0..1+ (more than 1 once over). */
    val used: Float get() = if (limit.signum() > 0) spent.toFloat() / limit.toFloat() else 0f
}

/** The limits a person has set for one category, with today's and this month's position against them. */
data class CategoryLimits(
    val category: MessageCategory,
    val daily: LimitLine?,
    val monthly: LimitLine?,
    /** What can still be spent each remaining day of the month and stay within the monthly limit; null if it is over or there is none. */
    val perDayLeft: BigDecimal?,
    val daysLeft: Int,
    /** Leftovers brought in from earlier cycles, already part of the monthly limit above. */
    val carriedIn: BigDecimal = BigDecimal.ZERO,
    /** Net money moved in (or out, if negative) this cycle, already part of the monthly limit above. */
    val moved: BigDecimal = BigDecimal.ZERO,
    val carry: CarryOver = CarryOver.RESET
) {
    val isOver: Boolean get() = (daily?.isOver ?: false) || (monthly?.isOver ?: false)
}

object SpendingLimits {

    /** Categories that have at least one limit, those that are over first, then by how much is used. */
    fun build(
        messages: List<ClassifiedMessage>,
        monthly: Map<MessageCategory, BigDecimal>,
        daily: Map<MessageCategory, BigDecimal>,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule = CycleRule.CALENDAR,
        adjustments: BudgetAdjustments = BudgetAdjustments()
    ): List<CategoryLimits> {
        val today = LocalDate.ofInstant(now, zone)
        val monthStart = cycle.containing(today).start
        val daysLeft = cycle.daysLeft(today)

        val spentToday = HashMap<MessageCategory, BigDecimal>()
        val spentMonth = HashMap<MessageCategory, BigDecimal>()
        messages.forEach { message ->
            val amount = Counting.signedSpend(message) ?: return@forEach
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isBefore(monthStart) || date.isAfter(today)) return@forEach
            spentMonth.merge(message.category, amount, BigDecimal::add)
            if (date == today) spentToday.merge(message.category, amount, BigDecimal::add)
        }

        return (monthly.keys + daily.keys).map { category ->
            // The monthly limit as it stands this cycle: the budget, plus what was carried in or moved here.
            val budget = Budgets.lineFor(monthly, adjustments, messages, cycle, today, zone, category)
            val monthlyLine = monthly[category]?.takeIf { it.signum() > 0 }
                ?.let { LimitLine(LimitPeriod.MONTHLY, budget?.available ?: it, spentMonth[category] ?: BigDecimal.ZERO) }
            val dailyLine = daily[category]?.takeIf { it.signum() > 0 }
                ?.let { LimitLine(LimitPeriod.DAILY, it, spentToday[category] ?: BigDecimal.ZERO) }
            val perDay = monthlyLine?.takeIf { !it.isOver }?.available?.divide(BigDecimal(daysLeft), 0, RoundingMode.FLOOR)
            CategoryLimits(
                category, dailyLine, monthlyLine, perDay, daysLeft,
                carriedIn = budget?.carriedIn ?: BigDecimal.ZERO, moved = budget?.moved ?: BigDecimal.ZERO, carry = budget?.carry ?: CarryOver.RESET
            )
        }.filter { it.daily != null || it.monthly != null }
            .sortedWith(
                compareByDescending<CategoryLimits> { it.isOver }
                    .thenByDescending { maxOf(it.daily?.used ?: 0f, it.monthly?.used ?: 0f) }
            )
    }
}
