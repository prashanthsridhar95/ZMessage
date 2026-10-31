package com.zyke.message.domain.plan

import com.zyke.message.domain.cycle.CycleRule
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** Whether the plan's spendable money reaches the next payday at the pace it is being spent. */
enum class RunwayStatus {
    /** It lasts to the end of the cycle. */
    ON_TRACK,
    /** It runs out [SalaryRunway.daysBefore] days before the next payday. */
    RUNS_OUT_EARLY,
    /** It is already used up. */
    USED_UP
}

/**
 * The pay cycle laid out as days, with the day the spendable money is projected to run out at the pace so far.
 * [runsOutOn] is null when the money lasts.
 */
data class SalaryRunway(
    val status: RunwayStatus,
    val cycleStart: LocalDate,
    val cycleEnd: LocalDate,
    val today: LocalDate,
    /** The day the next cycle (and so the next salary) starts. */
    val payday: LocalDate,
    val runsOutOn: LocalDate?,
    val daysBefore: Int,
    /** What has been spent each day on average this cycle. */
    val perDay: BigDecimal,
    /** True when the cycle starts on the day the salary usually arrives, so the next payday can be called salary day. */
    val paydayIsSalaryDay: Boolean
) {
    /** The days of the cycle, with the payday after them. */
    val days: List<LocalDate> get() = generateSequence(cycleStart) { it.plusDays(1) }.takeWhile { !it.isAfter(payday) }.toList()
}

object SalaryRunways {
    /** A pace worked out from fewer days than this is not worth reading. */
    const val MIN_DAYS = 3

    /**
     * @param insights the plan laid against the cycle so far (spendable, what is left, and the spending so far)
     * @return null when there is not enough to go on: no usable plan, or the cycle only just began
     */
    fun project(insights: PlanInsights?, today: LocalDate, cycle: CycleRule): SalaryRunway? {
        insights ?: return null
        if (insights.spendable.signum() <= 0) return null
        val range = cycle.containing(today)
        val elapsed = cycle.dayNumber(today)
        if (elapsed < MIN_DAYS) return null
        val payday = range.endInclusive.plusDays(1)
        val perDay = insights.spentOnTheRest.divide(BigDecimal(elapsed), 2, RoundingMode.HALF_UP)
        val atSalaryDay = insights.salaryDay?.let { it == payday.dayOfMonth } == true

        fun result(status: RunwayStatus, runsOut: LocalDate?) = SalaryRunway(
            status, range.start, range.endInclusive, today, payday, runsOut,
            runsOut?.let { ChronoUnit.DAYS.between(it, payday).toInt() } ?: 0, perDay, atSalaryDay
        )

        val left = insights.spendableLeft
        if (left.signum() <= 0) return result(RunwayStatus.USED_UP, today)
        if (perDay.signum() <= 0) return result(RunwayStatus.ON_TRACK, null)
        // The money covers this many more days after today.
        val covers = left.divide(perDay, 0, RoundingMode.FLOOR).toLong()
        val lastDay = today.plusDays(covers)
        return if (!lastDay.isBefore(range.endInclusive)) result(RunwayStatus.ON_TRACK, null) else result(RunwayStatus.RUNS_OUT_EARLY, lastDay)
    }

    /** The sentence under the strip. [dayLabel] turns a date into words such as "the 18th". */
    fun caption(runway: SalaryRunway, dayLabel: (LocalDate) -> String): String {
        val target = if (runway.paydayIsSalaryDay) "salary" else "the end of the cycle"
        return when (runway.status) {
            RunwayStatus.ON_TRACK -> "At this pace your plan's money lasts until $target"
            RunwayStatus.USED_UP -> "Your plan's money for this cycle is already used up, ${runway.daysBefore} days before $target"
            RunwayStatus.RUNS_OUT_EARLY ->
                "At this pace your plan's money lasts until ${dayLabel(runway.runsOutOn!!)} — " +
                    "${runway.daysBefore} ${if (runway.daysBefore == 1) "day" else "days"} before $target"
        }
    }
}
