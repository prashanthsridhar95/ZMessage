package com.zyke.message.domain.dashboard

import com.zyke.message.domain.model.ClassifiedMessage
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
        zone: ZoneId
    ): Dashboard {
        val today = LocalDate.ofInstant(now, zone)
        val movements = messages.mapNotNull { toMovement(it, zone) }

        val range = rangeFor(query.period, today, movements.minOfOrNull { it.date })
        val inRange = movements.filter { it.date in range }

        val spentTotal = inRange.filter { it.direction == FlowDirection.SPENT }.sumOf { it.amount }
        val receivedTotal = inRange.filter { it.direction == FlowDirection.RECEIVED }.sumOf { it.amount }

        val selected = inRange.filter {
            it.direction == query.direction && (query.category == null || it.message.category == query.category)
        }
        val previous = previousRange(query.period, range, today)?.let { prev ->
            movements.filter {
                it.date in prev && it.direction == query.direction &&
                    (query.category == null || it.message.category == query.category)
            }.sumOf { it.amount }
        }

        val (trend, unit) = trend(selected, query.period, range, today)

        return Dashboard(
            range = range,
            direction = query.direction,
            total = selected.sumOf { it.amount },
            count = selected.size,
            previousTotal = previous,
            spentTotal = spentTotal,
            receivedTotal = receivedTotal,
            // Categories ignore the category filter so the list can still be used to switch.
            categories = categories(inRange.filter { it.direction == query.direction }),
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

    private fun categories(movements: List<Movement>): List<CategoryTotal> =
        movements.groupBy { it.message.category }
            .map { (category, items) -> CategoryTotal(category, items.sumOf { it.amount }, items.size) }
            .sortedByDescending { it.total }

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

    internal fun rangeFor(period: DashboardPeriod, today: LocalDate, earliest: LocalDate?): DateRange {
        val thisMonth = YearMonth.from(today)
        return when (period) {
            DashboardPeriod.THIS_MONTH -> DateRange(thisMonth.atDay(1), today)
            DashboardPeriod.LAST_MONTH -> {
                val last = thisMonth.minusMonths(1)
                DateRange(last.atDay(1), last.atEndOfMonth())
            }
            DashboardPeriod.LAST_3_MONTHS -> DateRange(thisMonth.minusMonths(2).atDay(1), today)
            DashboardPeriod.ALL_TIME -> DateRange(earliest ?: today, today)
        }
    }

    /** The comparable earlier window; partial months are compared with the same number of days. */
    internal fun previousRange(period: DashboardPeriod, range: DateRange, today: LocalDate): DateRange? =
        when (period) {
            DashboardPeriod.THIS_MONTH -> {
                val last = YearMonth.from(today).minusMonths(1)
                val days = ChronoUnit.DAYS.between(range.start, range.endInclusive)
                DateRange(last.atDay(1), minOf(last.atDay(1).plusDays(days), last.atEndOfMonth()))
            }
            DashboardPeriod.LAST_MONTH -> {
                val before = YearMonth.from(range.start).minusMonths(1)
                DateRange(before.atDay(1), before.atEndOfMonth())
            }
            DashboardPeriod.LAST_3_MONTHS -> {
                val start = YearMonth.from(range.start)
                DateRange(start.minusMonths(3).atDay(1), start.minusMonths(1).atEndOfMonth())
            }
            DashboardPeriod.ALL_TIME -> null
        }

    private fun trend(
        movements: List<Movement>,
        period: DashboardPeriod,
        range: DateRange,
        today: LocalDate
    ): Pair<List<TrendPoint>, TrendUnit> {
        val byDay = period == DashboardPeriod.THIS_MONTH || period == DashboardPeriod.LAST_MONTH
        return if (byDay) {
            val perDay = movements.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.amount } }
            val days = ChronoUnit.DAYS.between(range.start, range.endInclusive).toInt() + 1
            List(days) { i ->
                val day = range.start.plusDays(i.toLong())
                TrendPoint(day, perDay[day] ?: BigDecimal.ZERO)
            } to TrendUnit.DAY
        } else {
            val perMonth = movements.groupBy { YearMonth.from(it.date) }.mapValues { (_, v) -> v.sumOf { it.amount } }
            val last = YearMonth.from(minOf(range.endInclusive, today))
            val first = maxOf(YearMonth.from(range.start), last.minusMonths(MAX_MONTH_BARS - 1L))
            val months = ChronoUnit.MONTHS.between(first, last).toInt() + 1
            List(months) { i ->
                val month = first.plusMonths(i.toLong())
                TrendPoint(month.atDay(1), perMonth[month] ?: BigDecimal.ZERO)
            } to TrendUnit.MONTH
        }
    }
}
