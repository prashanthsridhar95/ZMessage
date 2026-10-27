package com.zyke.message.domain.plan

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where one planned item stands this month. */
data class PlanItemStatus(
    val item: PlanItem,
    /** The payment that matched it this month, if one was found. */
    val paidAmount: BigDecimal?,
    val paidOn: LocalDate?
) {
    val isPaid: Boolean get() = paidAmount != null
}

/** The plan laid against the month so far. */
data class PlanInsights(
    val income: BigDecimal,
    val outflows: BigDecimal,
    val investments: BigDecimal,
    val savingsGoal: BigDecimal,
    /** Income left after outflows, investments and the savings goal; negative when the plan does not add up. */
    val spendable: BigDecimal,
    /** Everyone's spending so far this month, apart from the planned items that were paid. */
    val spentOnTheRest: BigDecimal,
    /** What is left of [spendable] this month; negative once it is used up. */
    val spendableLeft: BigDecimal,
    val daysLeft: Int,
    /** What can be spent each remaining day and stay in plan; null when nothing is left. */
    val perDayLeft: BigDecimal?,
    val salaryReceived: BigDecimal?,
    /** Arrived but expected; null when no salary was planned. */
    val salaryStatus: SalaryStatus?,
    val items: List<PlanItemStatus>,
    /** Money in less money out so far this month, against [savingsGoal]. */
    val savedSoFar: BigDecimal,
    /** Where [income] comes from; null when there is neither a spotted salary nor an entered one. */
    val incomeSource: IncomeSource?,
    val salaryDay: Int?,
    /** The lowest and highest salary of the recent months, when they differed. */
    val salaryRange: Pair<BigDecimal, BigDecimal>?
) {
    val isOverPlan: Boolean get() = spendableLeft.signum() < 0
    val planAddsUp: Boolean get() = spendable.signum() >= 0
}

enum class SalaryStatus { RECEIVED, EXPECTED, LATE }

/** How the month's income was worked out. */
enum class IncomeSource {
    /** This month's salary has arrived and was found in the messages. */
    SPOTTED_RECEIVED,

    /** Not here yet; estimated from the recent months' salaries found in the messages. */
    SPOTTED_ESTIMATE,

    /** No salary pattern in the messages, so the amount the person entered. */
    ENTERED
}

object PlanInsightsBuilder {
    /** A payment to the item's name counts as it when within this share of the planned amount... */
    private val AMOUNT_TOLERANCE = BigDecimal("0.15")

    /** ...and a payment to anyone counts when it is within this much of it. */
    private val STRICT_TOLERANCE = BigDecimal("0.02")

    /** A credit this large is taken to be the salary. */
    private val SALARY_SHARE = BigDecimal("0.7")

    fun build(
        plan: MoneyPlan,
        messages: List<ClassifiedMessage>,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule = CycleRule.CALENDAR
    ): PlanInsights? {
        // The salary the messages show wins; what the person entered is the fallback when there is no such pattern.
        val spotted = SalaryDetector.detect(messages, now, zone)
        if (plan.isEmpty && spotted == null) return null
        val today = LocalDate.ofInstant(now, zone)
        val monthStart = cycle.containing(today).start
        val daysLeft = cycle.daysLeft(today)

        val moves = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isBefore(monthStart) || date.isAfter(today)) return@mapNotNull null
            if (tx.isCardPayment) return@mapNotNull null
            Move(date, amount, tx.isReceived, "${tx.payee} ${message.merchant.name}".lowercase())
        }
        val sent = moves.filter { !it.received }
        val received = moves.filter { it.received }

        val statuses = plan.items.map { item -> status(item, sent) }
        val paidTotal = statuses.mapNotNull { it.paidAmount }.fold(BigDecimal.ZERO, BigDecimal::add)
        val totalSent = sent.fold(BigDecimal.ZERO) { sum, m -> sum + m.amount }
        val totalReceived = received.fold(BigDecimal.ZERO) { sum, m -> sum + m.amount }

        val enteredReceived = plan.salary?.let { salary ->
            received.filter { it.amount >= salary.multiply(SALARY_SHARE) }.maxByOrNull { it.amount }?.amount
        }
        val incomeSource: IncomeSource?
        val income: BigDecimal
        val salaryReceived: BigDecimal?
        val salaryDay: Int?
        when {
            spotted != null -> {
                salaryReceived = spotted.thisMonth
                income = spotted.thisMonth ?: spotted.typical
                incomeSource = if (spotted.thisMonth != null) IncomeSource.SPOTTED_RECEIVED else IncomeSource.SPOTTED_ESTIMATE
                salaryDay = spotted.typicalDay
            }
            plan.salary != null -> {
                salaryReceived = enteredReceived
                income = plan.salary
                incomeSource = IncomeSource.ENTERED
                salaryDay = plan.salaryDay
            }
            else -> {
                salaryReceived = null
                income = BigDecimal.ZERO
                incomeSource = null
                salaryDay = null
            }
        }
        val outflows = plan.outflows.sumOf { it.amount }
        val investments = plan.investments.sumOf { it.amount }
        val goal = plan.savingsGoal ?: BigDecimal.ZERO
        val spendable = income - outflows - investments - goal

        val spentOnRest = (totalSent - paidTotal).max(BigDecimal.ZERO)
        val left = spendable - spentOnRest
        val perDay = left.takeIf { it.signum() > 0 }?.divide(BigDecimal(daysLeft), 0, RoundingMode.FLOOR)

        val salaryStatus = incomeSource?.let {
            when {
                salaryReceived != null -> SalaryStatus.RECEIVED
                salaryDay != null && today.dayOfMonth > salaryDay + LATE_AFTER_DAYS -> SalaryStatus.LATE
                else -> SalaryStatus.EXPECTED
            }
        }

        return PlanInsights(
            income = income,
            outflows = outflows,
            investments = investments,
            savingsGoal = goal,
            spendable = spendable,
            spentOnTheRest = spentOnRest,
            spendableLeft = left,
            daysLeft = daysLeft,
            perDayLeft = perDay,
            salaryReceived = salaryReceived,
            salaryStatus = salaryStatus,
            items = statuses,
            savedSoFar = totalReceived - totalSent,
            incomeSource = incomeSource,
            salaryDay = salaryDay,
            salaryRange = spotted?.takeIf { it.low.compareTo(it.high) != 0 }?.let { it.low to it.high }
        )
    }

    private fun status(item: PlanItem, sent: List<Move>): PlanItemStatus {
        val words = item.name.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= MIN_WORD }
        val match = sent.filter { move ->
            val named = words.any { it in move.who }
            val gap = (move.amount - item.amount).abs()
            // To the right name, an amount near the plan is enough; otherwise it has to be almost exactly the plan.
            (named && gap <= item.amount.multiply(AMOUNT_TOLERANCE)) || gap <= item.amount.multiply(STRICT_TOLERANCE)
        }.maxByOrNull { it.date }
        return PlanItemStatus(item, match?.amount, match?.date)
    }

    private class Move(val date: LocalDate, val amount: BigDecimal, val received: Boolean, val who: String)

    private const val MIN_WORD = 3
    private const val LATE_AFTER_DAYS = 3
}
