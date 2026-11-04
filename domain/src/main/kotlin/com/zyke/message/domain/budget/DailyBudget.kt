package com.zyke.message.domain.budget

import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.util.toBigDecimalOrNull
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

/** Where a daily allowance comes from, strongest first: the person's own figure, the plan, or past spending. */
enum class DailyBasis { CUSTOM, PLAN, TREND }

/** A daily allowance with where it came from. */
data class DailyAllowance(val amount: BigDecimal, val basis: DailyBasis, val cyclesUsed: Int = 0)

/** What is left of today's budget, and how many days in a row have stayed within it. */
data class DailyBudgetStatus(
    val allowance: BigDecimal,
    val spentToday: BigDecimal,
    val currentStreak: Int,
    val bestStreak: Int,
    /** Where [allowance] comes from. */
    val basis: DailyBasis = DailyBasis.PLAN,
    /** For [DailyBasis.TREND]: the finished pay cycles it was worked out from (0 = this cycle so far). */
    val cyclesUsed: Int = 0
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

    /**
     * A daily allowance worked out from what was really spent in the last (up to) three finished pay cycles: the
     * typical (median) daily average of those cycles, so one odd month does not move it, rounded down to a clean
     * figure. A cycle with fewer than five spending days is too thin to count. With no finished cycle to go on, this
     * cycle's average so far is used once at least a week of it has passed; otherwise null.
     */
    fun fromTrend(
        messages: List<ClassifiedMessage>, today: LocalDate, zone: ZoneId, cycle: CycleRule = CycleRule.CALENDAR, planned: List<PlanItem> = emptyList()
    ): DailyAllowance? {
        val spend = spendByDay(messages, zone, planned)
        if (spend.isEmpty()) return null
        val perDay = (1..TREND_CYCLES).mapNotNull { back ->
            val range = cycle.shifted(today, -back)
            val length = java.time.temporal.ChronoUnit.DAYS.between(range.start, range.endInclusive).toInt() + 1
            var total = BigDecimal.ZERO
            var spendingDays = 0
            var day = range.start
            while (!day.isAfter(range.endInclusive)) {
                val spent = spend[day]
                if (spent != null && spent.signum() > 0) { total += spent; spendingDays++ }
                day = day.plusDays(1)
            }
            if (spendingDays < MIN_SPENDING_DAYS) null else total.divide(BigDecimal(length), 4, RoundingMode.HALF_UP)
        }
        if (perDay.isNotEmpty()) {
            val sorted = perDay.sorted()
            val mid = sorted.size / 2
            val typical = if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]).divide(BigDecimal(2), 4, RoundingMode.HALF_UP)
            return clean(typical)?.let { DailyAllowance(it, DailyBasis.TREND, perDay.size) }
        }
        // Nothing finished to go on: this cycle so far, once a week of it is behind us.
        val start = cycle.startOf(today)
        val elapsed = java.time.temporal.ChronoUnit.DAYS.between(start, today).toInt()
        if (elapsed < MIN_ELAPSED_DAYS) return null
        var total = BigDecimal.ZERO
        var day = start
        while (day.isBefore(today)) { total += spend[day] ?: BigDecimal.ZERO; day = day.plusDays(1) }
        return clean(total.divide(BigDecimal(elapsed), 4, RoundingMode.HALF_UP))?.let { DailyAllowance(it, DailyBasis.TREND, 0) }
    }

    /** Rounded down to a figure that reads cleanly: to the ten, or to the fifty from a thousand; null when it comes to nothing. */
    private fun clean(perDay: BigDecimal): BigDecimal? {
        val step = if (perDay >= BigDecimal(1000)) 50 else 10
        val floored = perDay.divide(BigDecimal(step), 0, RoundingMode.FLOOR).multiply(BigDecimal(step))
        return floored.takeIf { it.signum() > 0 }
    }

    /** The figure to use: the person's own, else the plan's, else the one the past spending suggests; null when none. */
    fun resolve(
        custom: BigDecimal?, insights: PlanInsights?, messages: List<ClassifiedMessage>,
        today: LocalDate, zone: ZoneId, cycle: CycleRule = CycleRule.CALENDAR, planned: List<PlanItem> = emptyList()
    ): DailyAllowance? {
        if (custom != null && custom.signum() > 0) return DailyAllowance(custom, DailyBasis.CUSTOM)
        allowance(insights, today, cycle)?.takeIf { it.signum() > 0 }?.let { return DailyAllowance(it, DailyBasis.PLAN) }
        return fromTrend(messages, today, zone, cycle, planned)
    }

    private const val TREND_CYCLES = 3
    private const val MIN_SPENDING_DAYS = 5
    private const val MIN_ELAPSED_DAYS = 7

    /** What was spent on each day: money sent, apart from what the plan has already set aside. */
    fun spendByDay(messages: List<ClassifiedMessage>, zone: ZoneId, planned: List<PlanItem> = emptyList()): Map<LocalDate, BigDecimal> {
        val byDay = HashMap<LocalDate, BigDecimal>()
        messages.forEach { message ->
            if (message.category in COMMITTED) return@forEach
            val amount = Counting.signedSpend(message) ?: return@forEach
            // A regular outgoing the plan already set aside (a subscription, a SIP) is not spending from the day's allowance.
            if (PlanMatching.isPlanned(message, planned)) return@forEach
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
