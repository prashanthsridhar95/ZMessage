package com.zyke.message.domain.forecast

import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.plan.PlanInsights
import com.zyke.message.domain.plan.RunwayStatus
import com.zyke.message.domain.plan.SalaryRunway
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.temporal.ChronoUnit

/** The one-line answer to "where is this heading?". */
enum class Verdict {
    /** The plan's money used up already. */
    USED_UP,

    /** The plan's money runs out before the cycle ends. */
    RUNS_OUT,

    /** The bank balance would not cover the rest of the cycle. */
    SHORT,

    /** Within the plan (or, with no plan, simply on pace). */
    ON_TRACK,

    /** No plan and no balance to measure against: only the pace is known. */
    PACE
}

/**
 * The forecast read as a story: a [verdict], what is spent so far, where the month ends at this pace, how that sits
 * against the plan, and the cumulative level at which the plan's money would be used up (for a chart marker).
 */
data class ForecastStory(
    val verdict: Verdict,
    val spentSoFar: BigDecimal?,
    /** Whole-cycle spend at the current pace; null while there is too little to go on. */
    val projectedSpend: BigDecimal?,
    /** Plan's spendable money less its projected use: positive under plan, negative over. Null with no plan. */
    val planGap: BigDecimal?,
    /** What is left of the plan's spendable money now; null with no plan. */
    val planLeft: BigDecimal?,
    /** Running total of all spending at which the plan's money is used up; null with no plan or no spending. */
    val planLimit: BigDecimal?,
    val runsOutOn: java.time.LocalDate?,
    val daysBeforePayday: Int
)

object ForecastStories {
    fun build(forecast: SpendForecast?, runway: SalaryRunway?, insights: PlanInsights?, summary: MonthlySummary?): ForecastStory {
        val spent = summary?.cumulativeSpent?.lastOrNull()
        val projected = summary?.projectedSpent
        var gap: BigDecimal? = null
        var left: BigDecimal? = null
        var limit: BigDecimal? = null
        if (insights != null && insights.spendable.signum() > 0) {
            left = insights.spendableLeft
            if (spent != null) limit = spent + left.max(BigDecimal.ZERO)
            if (runway != null) {
                val remaining = ChronoUnit.DAYS.between(runway.today, runway.cycleEnd).coerceAtLeast(0)
                val projectedRest = insights.spentOnTheRest + runway.perDay.multiply(BigDecimal(remaining))
                gap = (insights.spendable - projectedRest).setScale(0, RoundingMode.HALF_UP)
            }
        }
        val verdict = when {
            runway?.status == RunwayStatus.USED_UP -> Verdict.USED_UP
            runway?.status == RunwayStatus.RUNS_OUT_EARLY -> Verdict.RUNS_OUT
            forecast?.cardNeeded != null -> Verdict.SHORT
            runway != null || (left != null && projected != null) || forecast?.endBalance != null -> Verdict.ON_TRACK
            else -> Verdict.PACE
        }
        return ForecastStory(
            verdict, spent, projected, gap, left, limit,
            runsOutOn = runway?.runsOutOn ?: forecast?.runsOutOn,
            daysBeforePayday = runway?.daysBefore ?: 0
        )
    }
}
