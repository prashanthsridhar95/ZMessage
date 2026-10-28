package com.zyke.message.domain.budget

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.plan.PlanInsights
import com.zyke.message.domain.plan.PlanItem
import com.zyke.message.domain.plan.PlanMatching
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class DayStatus {
    /** Spent no more than the daily budget (a day with nothing spent counts). */
    UNDER,
    OVER,
    /** Not here yet, or before the app has any messages to go on. */
    NONE
}

data class BudgetDay(val date: LocalDate, val spent: BigDecimal, val status: DayStatus)

/** What is left of today's budget, and how many days in a row have stayed within it. */
data class DailyBudgetStatus(
    val allowance: BigDecimal,
    val spentToday: BigDecimal,
    val currentStreak: Int,
    val bestStreak: Int
) {
    val leftToday: BigDecimal get() = allowance - spentToday
    val isOver: Boolean get() = leftToday.signum() < 0
}

/**
 * A daily budget in the way that is easiest to feel: what is yours to spend this month, shared across the days of
 * the month. Whatever is already committed (investments, savings, insurance, charity, card bills) is not counted
 * against it, because the plan has set that money aside.
 */
object DailyBudgets {
    private val COMMITTED = setOf(
        MessageCategory.INVESTMENTS, MessageCategory.SAVINGS, MessageCategory.INSURANCE,
        MessageCategory.CHARITY, MessageCategory.CREDIT_CARDS
    )

    /** The plan's spendable money over the days of the pay cycle; null when there is no usable plan. */
    fun allowance(insights: PlanInsights?, today: LocalDate, cycle: CycleRule = CycleRule.CALENDAR): BigDecimal? {
        val spendable = insights?.spendable?.takeIf { it.signum() > 0 } ?: return null
        return spendable.divide(BigDecimal(cycle.length(today)), 0, RoundingMode.FLOOR)
    }

    /** What was spent on each day: money sent, apart from what the plan has already set aside. */
    fun spendByDay(messages: List<ClassifiedMessage>, zone: ZoneId, planned: List<PlanItem> = emptyList()): Map<LocalDate, BigDecimal> {
        val byDay = HashMap<LocalDate, BigDecimal>()
        messages.forEach { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@forEach
            if (tx.isReceived || message.category in COMMITTED) return@forEach
            // A regular outgoing the plan already set aside (a subscription, a SIP) is not spending from the day's allowance.
            if (PlanMatching.isPlanned(message, planned)) return@forEach
            val amount = tx.amount.toBigDecimalOrNull() ?: return@forEach
            val day = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            byDay[day] = (byDay[day] ?: BigDecimal.ZERO) + amount
        }
        return byDay
    }

    fun status(
        messages: List<ClassifiedMessage>, allowance: BigDecimal, now: Instant, zone: ZoneId, planned: List<PlanItem> = emptyList()
    ): DailyBudgetStatus {
        val today = LocalDate.ofInstant(now, zone)
        val spend = spendByDay(messages, zone, planned)
        val first = spend.keys.minOrNull()
        val spentToday = spend[today] ?: BigDecimal.ZERO

        var current = 0
        var best = 0
        var run = 0
        if (first != null) {
            var day: LocalDate = first
            while (!day.isAfter(today)) {
                if ((spend[day] ?: BigDecimal.ZERO) <= allowance) run++ else run = 0
                if (run > best) best = run
                day = day.plusDays(1)
            }
            current = run
        }
        return DailyBudgetStatus(allowance, spentToday, current, best)
    }

    /** Every day of [range] (a pay cycle) with what was spent and whether it was within the budget. */
    fun days(
        messages: List<ClassifiedMessage>, allowance: BigDecimal, range: DateRange, now: Instant, zone: ZoneId, planned: List<PlanItem> = emptyList()
    ): List<BudgetDay> {
        val today = LocalDate.ofInstant(now, zone)
        val spend = spendByDay(messages, zone, planned)
        val first = spend.keys.minOrNull()
        val count = java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endInclusive).toInt() + 1
        return (0 until count).map { offset ->
            val date = range.start.plusDays(offset.toLong())
            val spent = spend[date] ?: BigDecimal.ZERO
            val status = when {
                date.isAfter(today) || first == null || date.isBefore(first) -> DayStatus.NONE
                spent <= allowance -> DayStatus.UNDER
                else -> DayStatus.OVER
            }
            BudgetDay(date, spent, status)
        }
    }
}
