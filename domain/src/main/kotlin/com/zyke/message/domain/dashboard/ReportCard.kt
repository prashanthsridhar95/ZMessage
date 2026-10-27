package com.zyke.message.domain.dashboard

import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.YearMonth

enum class Grade(val label: String, val points: Int) {
    A("A", 4),
    B("B", 3),
    C("C", 2),
    D("D", 1),
    F("F", 0);

    companion object {
        /** The grade nearest to an average of [points], so a mix of A and B rounds to one of them. */
        fun fromAverage(average: Double): Grade = entries.minBy { kotlin.math.abs(it.points - average) }
    }
}

enum class Trend { UP, DOWN, SAME, NEW }

/** One category's marks for the month so far. */
data class Subject(
    val category: MessageCategory,
    val spent: BigDecimal,
    /** Spent in the same stretch of the previous month. */
    val previousSpent: BigDecimal,
    val budget: BigDecimal?,
    /** Null when there is nothing to grade against (no budget and no earlier month). */
    val grade: Grade?,
    val trend: Trend
)

/** The one sentence the card says about the month. */
sealed interface Remark {
    data class OverBudget(val category: MessageCategory, val over: BigDecimal) : Remark
    data class Rising(val category: MessageCategory, val percent: Int) : Remark
    data class Improved(val category: MessageCategory, val percent: Int) : Remark
    data class Saving(val percent: Int) : Remark
    data object OnTrack : Remark
}

/**
 * How the month is going, in the shape of a school report: marks per category, an overall grade and a remark.
 * It always covers the current month so far, whatever the dashboard is filtered to.
 */
data class ReportCard(
    val month: YearMonth,
    val subjects: List<Subject>,
    val overall: Grade?,
    val spent: BigDecimal,
    val received: BigDecimal,
    /** Share of income kept, in percent; null with no income to measure against. */
    val savingsPercent: Int?,
    val remark: Remark,
    /** Money messages the app could not read, which could make the marks too kind. */
    val unreadMessages: Int
) {
    val isEmpty: Boolean get() = subjects.isEmpty() && received.signum() == 0
}
