package com.zyke.message.domain.plan

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.forecast.UpcomingOutflow
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.goals.Goals
import com.zyke.message.domain.insights.BalanceReader
import com.zyke.message.domain.insights.PaceNudges
import com.zyke.message.domain.model.ClassifiedMessage
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class AffordLevel(val label: String) {
    COMFORTABLE("Comfortable"),
    TIGHT("Tight"),
    NOT_NOW("Not now"),
    CANT_TELL("Can't tell")
}

/** One line of the explanation; [weight] orders them, the heaviest concern first. */
data class AffordReason(val text: String, val weight: Int)

/** A no-cost EMI option. */
data class InstalmentOption(val months: Int, val perMonth: BigDecimal)

data class AffordVerdict(
    val level: AffordLevel,
    val price: BigDecimal,
    /** The top three reasons. */
    val reasons: List<AffordReason>,
    val spendableLeft: BigDecimal?,
    val dueBeforeSalary: BigDecimal?,
    val balance: BigDecimal?,
    val balanceAfter: BigDecimal?,
    val safetyFloor: BigDecimal?,
    /** How long the nearest goal slips, in weeks; null when it does not. */
    val goalName: String?,
    val goalDelayWeeks: Int?,
    val instalments: List<InstalmentOption>
) {
    companion object {
        const val DISCLAIMER = "Based on what your messages and plan show. Not financial advice."
    }
}

/**
 * A plain-language "can I afford this?" from what the app already knows: what is left of the plan this cycle, what is
 * due before the next salary, the latest balance a bank message stated and the nearest goal. It only reads; it never
 * advises, and says so when it has too little to go on.
 */
object Affordability {
    private val INSTALMENT_FROM = BigDecimal(5000)
    private val DEFAULT_FLOOR = BigDecimal(2000)
    private val FLOOR_SHARE = BigDecimal("0.10")
    private const val WEEKS_PER_MONTH = 4.345
    private const val MAX_REASONS = 3
    private val INSTALMENT_MONTHS = listOf(3, 6)

    fun money(amount: BigDecimal): String = PaceNudges.money(amount)

    @Suppress("LongParameterList", "LongMethod", "CyclomaticComplexMethod")
    fun check(
        price: BigDecimal,
        messages: List<ClassifiedMessage>,
        plan: MoneyPlan,
        goals: List<Goal>,
        upcomingOutflows: UpcomingOutflow?,
        now: Instant,
        zone: ZoneId,
        cycle: CycleRule = CycleRule.CALENDAR,
        lowBalanceFloor: BigDecimal? = null
    ): AffordVerdict {
        val today = LocalDate.ofInstant(now, zone)
        val insights = PlanInsightsBuilder.build(plan, messages, now, zone, cycle)
        val balance = BalanceReader.latest(messages, zone).takeIf { it.isNotEmpty() }
            ?.fold(BigDecimal.ZERO) { sum, b -> sum + b.amount }
        val reasons = mutableListOf<AffordReason>()
        var worst = 0 // 0 fine, 1 tight, 2 not now

        fun flag(level: Int, weight: Int, text: String) {
            worst = maxOf(worst, level)
            reasons += AffordReason(text, weight)
        }

        val instalments = if (price >= INSTALMENT_FROM) {
            INSTALMENT_MONTHS.map { InstalmentOption(it, price.divide(BigDecimal(it), 0, RoundingMode.CEILING)) }
        } else {
            emptyList()
        }

        // (a) what is left of the plan
        val left = insights?.spendableLeft
        if (insights != null && left != null) {
            val income = insights.income
            when {
                left.signum() <= 0 ->
                    flag(1, 60, "You are already ${money(left.abs())} past what your plan leaves to spend this cycle.")
                price > left -> {
                    val over = price - left
                    val level = if (income.signum() > 0 && over > income.divide(BigDecimal(2))) 2 else 1
                    flag(level, 70, "It is ${money(over)} more than the ${money(left)} your plan has left to spend this cycle.")
                }
                price.multiply(BigDecimal(5)) > left.multiply(BigDecimal(4)) ->
                    flag(1, 50, "It would use most of the ${money(left)} your plan has left this cycle.")
                else -> reasons += AffordReason("It fits in the ${money(left)} your plan has left to spend this cycle.", 10)
            }
        }

        // (b) due before the next salary
        val due = upcomingOutflows?.total
        if (upcomingOutflows != null && due != null && !upcomingOutflows.isEmpty) {
            val big = upcomingOutflows.biggest(1).first()
            reasons += AffordReason(
                "${money(due)} is still due before your next salary, such as ${big.title} (${money(big.amount)}).",
                if (due.signum() > 0) 30 else 0
            )
        }

        // (c) the balance and the safety floor
        val floor = lowBalanceFloor?.takeIf { it.signum() > 0 }
            ?: insights?.income?.takeIf { it.signum() > 0 }?.multiply(FLOOR_SHARE)?.setScale(0, RoundingMode.CEILING)
            ?: DEFAULT_FLOOR
        var after: BigDecimal? = null
        if (balance != null) {
            val dueAmount = due ?: BigDecimal.ZERO
            val afterPrice = balance - price
            after = afterPrice - dueAmount
            when {
                afterPrice < floor ->
                    flag(2, 100, "Your last stated balance of ${money(balance)} would drop to ${money(afterPrice)}, below your ${money(floor)} safety floor.")
                after < BigDecimal.ZERO ->
                    flag(2, 90, "After this and the bills due before salary, your balance would run short by ${money(after.abs())}.")
                after < floor ->
                    flag(1, 80, "After this and the bills due before salary, about ${money(after)} would be left, under your ${money(floor)} safety floor.")
                else -> reasons += AffordReason("About ${money(after)} would remain after this and the bills due before salary.", 10)
            }
        }

        // (d) the nearest goal
        var goalName: String? = null
        var delayWeeks: Int? = null
        val goal = Goals.all(goals, today).firstOrNull { !it.isReached }
        if (goal != null) {
            val contribution = (goal.neededPerMonth ?: plan.savingsGoal)?.takeIf { it.signum() > 0 }
            val shortfall = if (left == null) price else (price - left).max(BigDecimal.ZERO)
            if (contribution != null && shortfall.signum() > 0) {
                val weeks = Math.round(shortfall.toDouble() / contribution.toDouble() * WEEKS_PER_MONTH).toInt()
                if (weeks >= 1) {
                    goalName = goal.goal.name
                    delayWeeks = weeks
                    val span = if (weeks >= 8) "~${Math.round(weeks / WEEKS_PER_MONTH)} months" else if (weeks == 1) "~1 week" else "~$weeks weeks"
                    flag(if (weeks >= 4) 1 else 0, 40, "It could push back '${goal.goal.name}' by $span.")
                }
            }
        }

        // (e) instalments
        if (instalments.isNotEmpty() && worst > 0) {
            reasons += AffordReason(
                "As a no-cost EMI that is " + instalments.joinToString(" or ") { "${money(it.perMonth)} a month for ${it.months} months" } +
                    ", if the seller offers it.",
                5
            )
        }

        val noData = insights == null && balance == null
        val level = when {
            noData -> AffordLevel.CANT_TELL
            worst >= 2 -> AffordLevel.NOT_NOW
            worst == 1 -> AffordLevel.TIGHT
            else -> AffordLevel.COMFORTABLE
        }
        val shown = if (noData) {
            listOf(
                AffordReason(
                    "There is no plan, salary or bank balance in your messages yet, so there is nothing to compare this with. " +
                        "Add your salary in the plan, or wait for a bank message that states a balance.",
                    1
                )
            )
        } else {
            reasons.sortedByDescending { it.weight }.take(MAX_REASONS)
        }
        return AffordVerdict(
            level = level,
            price = price,
            reasons = shown,
            spendableLeft = left,
            dueBeforeSalary = due,
            balance = balance,
            balanceAfter = after,
            safetyFloor = if (balance != null) floor else null,
            goalName = goalName,
            goalDelayWeeks = delayWeeks,
            instalments = instalments
        )
    }
}
