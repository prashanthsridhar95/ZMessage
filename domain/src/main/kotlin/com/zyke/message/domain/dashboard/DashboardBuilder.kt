package com.zyke.message.domain.dashboard

import com.zyke.message.domain.cycle.CycleRule
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Aggregates the classified inbox into spending / income insights. */
object DashboardBuilder {
    private const val RECENT_PER_VENDOR = 5
    private const val MAX_MONTH_BARS = 12
    private const val MAX_DAY_BARS = 62

    private class Movement(
        val message: ClassifiedMessage,
        val tx: MessageContent.Transaction,
        val amount: BigDecimal,
        val date: LocalDate
    ) {
        val direction get() = if (tx.isReceived) FlowDirection.RECEIVED else FlowDirection.SPENT
    }

    fun build(
        messages: List<ClassifiedMessage>,
        query: DashboardQuery,
        now: Instant,
        zone: ZoneId,
        budgets: Map<MessageCategory, BigDecimal> = emptyMap(),
        cycle: CycleRule = CycleRule.CALENDAR
    ): Dashboard {
        val today = LocalDate.ofInstant(now, zone)
        val movements = messages.mapNotNull { toMovement(it, zone) }

        val range = rangeFor(query.period, today, movements.minOfOrNull { it.date }, cycle, query.customRange)
        val inRange = movements.filter { it.date in range }

        val spentTotal = inRange.filter { it.direction == FlowDirection.SPENT }.sumOf { it.amount }
        val receivedTotal = inRange.filter { it.direction == FlowDirection.RECEIVED }.sumOf { it.amount }

        val selected = inRange.filter {
            it.direction == query.direction && (query.category == null || it.message.category == query.category)
        }
        val previous = previousRange(query.period, range, today, cycle)?.let { prev ->
            movements.filter {
                it.date in prev && it.direction == query.direction &&
                    (query.category == null || it.message.category == query.category)
            }.sumOf { it.amount }
        }

        val (trend, unit) = trend(selected, query.period, range, today, cycle)

        return Dashboard(
            range = range,
            direction = query.direction,
            total = selected.sumOf { it.amount },
            count = selected.size,
            previousTotal = previous,
            spentTotal = spentTotal,
            receivedTotal = receivedTotal,
            // Categories ignore the category filter so the list can still be used to switch.
            categories = categories(
                inRange.filter { it.direction == query.direction },
                budgets.takeIf { query.period == DashboardPeriod.THIS_MONTH && query.direction == FlowDirection.SPENT }.orEmpty()
            ),
            vendors = vendors(selected),
            sources = sources(selected),
            trend = trend,
            trendUnit = unit
        )
    }

    private fun toMovement(message: ClassifiedMessage, zone: ZoneId): Movement? {
        val tx = message.content as? MessageContent.Transaction ?: return null
        val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        val date = LocalDate.ofInstant(Instant.ofEpochMilli(message.message.date), zone)
        return Movement(message, tx, amount, date)
    }

    private fun categories(movements: List<Movement>, budgets: Map<MessageCategory, BigDecimal>): List<CategoryTotal> {
        val totals = movements.groupBy { it.message.category }
            .map { (category, items) -> CategoryTotal(category, items.sumOf { it.amount }, items.size, budgets[category]) }
        // A category with a budget stays listed even before the first payment of the month.
        val untouched = budgets.keys.filter { key -> totals.none { it.category == key } }
            .map { CategoryTotal(it, BigDecimal.ZERO, 0, budgets[it]) }
        return (totals + untouched).sortedByDescending { it.total }
    }

    private fun vendors(movements: List<Movement>): List<VendorSummary> =
        movements.sortedByDescending { it.message.message.date }
            .groupBy { vendorKey(it) }
            .map { (key, items) ->
                VendorSummary(
                    key = key,
                    merchant = items.first().message.merchant,
                    total = items.sumOf { it.amount },
                    count = items.size,
                    lastEpochMillis = items.first().message.message.date,
                    recent = items.take(RECENT_PER_VENDOR).map {
                        Payment(it.message.id, it.message.message.date, it.amount, sourceLabel(it.tx))
                    }
                )
            }
            .sortedByDescending { it.total }

    private fun vendorKey(movement: Movement): String =
        movement.message.merchant.name.trim().lowercase().ifEmpty { "unknown" }

    private fun sources(movements: List<Movement>): List<SourceTotal> =
        movements.groupBy { sourceLabel(it.tx) }
            .map { (label, items) -> SourceTotal(label, items.sumOf { it.amount }, items.size) }
            .sortedByDescending { it.total }

    internal fun sourceLabel(tx: MessageContent.Transaction): String {
        val bank = tx.bankName.displayString.ifEmpty { "Account" }
        val kind = if (tx.isCard) "Card" else "A/C"
        return if (tx.accountNumber.isNotEmpty()) "$bank $kind •• ${tx.accountNumber}" else bank
    }

    internal fun rangeFor(
        period: DashboardPeriod,
        today: LocalDate,
        earliest: LocalDate?,
        cycle: CycleRule = CycleRule.CALENDAR,
        custom: DateRange? = null
    ): DateRange = when (period) {
        DashboardPeriod.THIS_MONTH -> DateRange(cycle.containing(today).start, today)
        DashboardPeriod.LAST_MONTH -> cycle.shifted(today, -1)
        DashboardPeriod.LAST_3_MONTHS -> DateRange(cycle.shifted(today, -2).start, today)
        DashboardPeriod.CUSTOM -> custom?.let { DateRange(it.start, minOf(it.endInclusive, today).let { end -> if (end.isBefore(it.start)) it.start else end }) }
            ?: DateRange(cycle.containing(today).start, today)
        DashboardPeriod.ALL_TIME -> DateRange(earliest ?: today, today)
    }

    /** The comparable earlier window; a partly finished cycle is compared with the same number of days. */
    internal fun previousRange(period: DashboardPeriod, range: DateRange, today: LocalDate, cycle: CycleRule = CycleRule.CALENDAR): DateRange? =
        when (period) {
            DashboardPeriod.THIS_MONTH -> {
                val last = cycle.shifted(today, -1)
                val days = ChronoUnit.DAYS.between(range.start, range.endInclusive)
                DateRange(last.start, minOf(last.start.plusDays(days), last.endInclusive))
            }
            DashboardPeriod.LAST_MONTH -> cycle.shifted(range.start, -1)
            DashboardPeriod.LAST_3_MONTHS -> DateRange(cycle.shifted(range.start, -3).start, cycle.shifted(range.start, -1).endInclusive)
            DashboardPeriod.CUSTOM -> {
                // The same number of days straight before it.
                val days = ChronoUnit.DAYS.between(range.start, range.endInclusive) + 1
                DateRange(range.start.minusDays(days), range.start.minusDays(1))
            }
            DashboardPeriod.ALL_TIME -> null
        }

    private fun point(start: LocalDate, movements: List<Movement>): TrendPoint {
        val byCategory = movements.groupBy { it.message.category }
            .map { (category, items) -> category to items.sumOf { it.amount } }
            .sortedByDescending { it.second }
        return TrendPoint(start, movements.sumOf { it.amount }, byCategory)
    }

    private fun trend(
        movements: List<Movement>,
        period: DashboardPeriod,
        range: DateRange,
        today: LocalDate,
        cycle: CycleRule
    ): Pair<List<TrendPoint>, TrendUnit> {
        val byDay = period == DashboardPeriod.THIS_MONTH || period == DashboardPeriod.LAST_MONTH ||
            (period == DashboardPeriod.CUSTOM && ChronoUnit.DAYS.between(range.start, range.endInclusive) < MAX_DAY_BARS)
        return if (byDay) {
            val perDay = movements.groupBy { it.date }
            val days = ChronoUnit.DAYS.between(range.start, range.endInclusive).toInt() + 1
            List(days) { i ->
                val day = range.start.plusDays(i.toLong())
                point(day, perDay[day].orEmpty())
            } to TrendUnit.DAY
        } else {
            // One bar per cycle (a calendar month when the cycle starts on the 1st).
            val perCycle = movements.groupBy { cycle.startOf(it.date) }
            val last = cycle.startOf(minOf(range.endInclusive, today))
            val earliestBar = cycle.shifted(last, -(MAX_MONTH_BARS - 1)).start
            val first = maxOf(cycle.startOf(range.start), earliestBar)
            val starts = generateSequence(first) { cycle.shifted(it, 1).start }.takeWhile { !it.isAfter(last) }.toList()
            starts.map { start -> point(start, perCycle[start].orEmpty()) } to TrendUnit.MONTH
        }
    }
}
