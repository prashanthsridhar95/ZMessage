package com.zyke.message.domain.goals

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Something the person is putting money towards: a trip, a gadget, an emergency fund. */
data class Goal(
    val id: String,
    val name: String,
    val target: BigDecimal,
    /** What has been put towards it so far, as the person records it. */
    val saved: BigDecimal = BigDecimal.ZERO,
    val targetDate: LocalDate? = null
)

data class GoalProgress(
    val goal: Goal,
    /** 0 to 1. */
    val fraction: Float,
    val remaining: BigDecimal,
    val isReached: Boolean,
    /** How much to put aside each month to arrive by the target date; null with no date, once reached or when the date has passed. */
    val neededPerMonth: BigDecimal?,
    val isLate: Boolean
)

object Goals {
    fun progress(goal: Goal, today: LocalDate): GoalProgress {
        val remaining = (goal.target - goal.saved).max(BigDecimal.ZERO)
        val fraction = if (goal.target.signum() > 0) (goal.saved.toFloat() / goal.target.toFloat()).coerceIn(0f, 1f) else 0f
        val reached = remaining.signum() == 0
        val date = goal.targetDate
        val months = date?.let { ChronoUnit.MONTHS.between(today.withDayOfMonth(1), it.withDayOfMonth(1)).coerceAtLeast(1) }
        return GoalProgress(
            goal = goal,
            fraction = fraction,
            remaining = remaining,
            isReached = reached,
            neededPerMonth = if (!reached && date != null && !date.isBefore(today) && months != null) {
                remaining.divide(BigDecimal(months), 0, RoundingMode.CEILING)
            } else {
                null
            },
            isLate = !reached && date != null && date.isBefore(today)
        )
    }

    /** Goals still being worked on first, the nearest date first; reached ones last. */
    fun all(goals: List<Goal>, today: LocalDate): List<GoalProgress> =
        goals.map { progress(it, today) }.sortedWith(
            compareBy<GoalProgress> { it.isReached }.thenBy { it.goal.targetDate ?: LocalDate.MAX }
        )
}
