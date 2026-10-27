package com.zyke.message.domain.dashboard

import com.zyke.message.domain.diagnostics.DiagnosticsBuilder
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** The grading bands below are the product's policy, so they are written out as plain numbers next to their grade. */
@Suppress("MagicNumber")
object ReportCardBuilder {
    private const val MEANINGFUL_CHANGE = 25 // percent
    private val MINIMUM_FOR_REMARK = BigDecimal(500)

    fun build(
        messages: List<ClassifiedMessage>,
        budgets: Map<MessageCategory, BigDecimal>,
        now: Instant,
        zone: ZoneId
    ): ReportCard {
        val query = DashboardQuery(DashboardPeriod.THIS_MONTH, FlowDirection.SPENT)
        val spentView = DashboardBuilder.build(messages, query, now, zone, budgets)
        val receivedView = DashboardBuilder.build(messages, query.copy(direction = FlowDirection.RECEIVED), now, zone)

        val previousByCategory = previousSpent(messages, now, zone)
        val subjects = spentView.categories
            .map { subject(it, previousByCategory[it.category] ?: BigDecimal.ZERO) }
            .filter { it.spent.signum() > 0 || it.budget != null }

        val spent = spentView.total
        val received = receivedView.total
        val savings = savingsPercent(spent, received)
        val overall = savings?.let(::savingsGrade)
            ?: subjects.mapNotNull { it.grade }.takeIf { it.isNotEmpty() }?.let { Grade.fromAverage(it.map { g -> g.points }.average()) }

        return ReportCard(
            month = YearMonth.from(spentView.range.endInclusive),
            subjects = subjects,
            overall = overall,
            spent = spent,
            received = received,
            savingsPercent = savings,
            remark = remark(subjects, savings),
            unreadMessages = DiagnosticsBuilder.build(messages, maxSamples = 0).unrecognised
        )
    }

    /** What each category cost over the same days of last month, so a half-finished month is judged fairly. */
    private fun previousSpent(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): Map<MessageCategory, BigDecimal> {
        val query = DashboardQuery(DashboardPeriod.THIS_MONTH, FlowDirection.SPENT)
        val today = LocalDate.ofInstant(now, zone)
        val range = DashboardBuilder.rangeFor(DashboardPeriod.THIS_MONTH, today, null)
        val previous = DashboardBuilder.previousRange(DashboardPeriod.THIS_MONTH, range, today) ?: return emptyMap()
        // The previous window is an ordinary month window, so reuse the builder by shifting "now" into it.
        val shifted = previous.endInclusive.atTime(12, 0).atZone(zone).toInstant()
        return DashboardBuilder.build(messages, query, shifted, zone).categories.associate { it.category to it.total }
    }

    private fun subject(total: CategoryTotal, previous: BigDecimal): Subject {
        val trend = when {
            previous.signum() == 0 -> if (total.total.signum() > 0) Trend.NEW else Trend.SAME
            else -> when (total.total.compareTo(previous)) {
                1 -> Trend.UP
                -1 -> Trend.DOWN
                else -> Trend.SAME
            }
        }
        val grade = total.usedFraction?.let(::budgetGrade)
            ?: previous.takeIf { it.signum() > 0 }?.let { changeGrade((total.total - it).toDouble() / it.toDouble() * 100.0) }
        return Subject(total.category, total.total, previous, total.budget, grade, trend)
    }

    private fun budgetGrade(used: Double): Grade = when {
        used <= 0.7 -> Grade.A
        used <= 0.9 -> Grade.B
        used <= 1.0 -> Grade.C
        used <= 1.15 -> Grade.D
        else -> Grade.F
    }

    private fun changeGrade(percent: Double): Grade = when {
        percent <= -10 -> Grade.A
        percent <= 10 -> Grade.B
        percent <= 30 -> Grade.C
        percent <= 60 -> Grade.D
        else -> Grade.F
    }

    private fun savingsPercent(spent: BigDecimal, received: BigDecimal): Int? =
        received.takeIf { it.signum() > 0 }
            ?.let { ((received - spent).toDouble() / received.toDouble() * 100.0).toInt() }

    private fun savingsGrade(percent: Int): Grade = when {
        percent >= 30 -> Grade.A
        percent >= 20 -> Grade.B
        percent >= 10 -> Grade.C
        percent >= 0 -> Grade.D
        else -> Grade.F
    }

    private fun remark(subjects: List<Subject>, savings: Int?): Remark {
        subjects.filter { it.budget != null && it.spent > it.budget }
            .maxByOrNull { it.spent - it.budget!! }
            ?.let { return Remark.OverBudget(it.category, it.spent - it.budget!!) }

        val moved = subjects
            .filter { it.previousSpent.signum() > 0 && it.spent >= MINIMUM_FOR_REMARK }
            .map { it to ((it.spent - it.previousSpent).toDouble() / it.previousSpent.toDouble() * 100.0).toInt() }
        moved.filter { it.second >= MEANINGFUL_CHANGE }.maxByOrNull { it.second }
            ?.let { (subject, percent) -> return Remark.Rising(subject.category, percent) }
        moved.filter { it.second <= -MEANINGFUL_CHANGE }.minByOrNull { it.second }
            ?.let { (subject, percent) -> return Remark.Improved(subject.category, -percent) }

        return if (savings != null && savings > 0) Remark.Saving(savings) else Remark.OnTrack
    }
}
