package com.zyke.message.domain.plan

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.util.BodyMemo
import com.zyke.message.domain.util.toBigDecimalOrNull
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/**
 * Finds a salary by its rhythm when the payer's name does not repeat (a reference number in every narration, NEFT one
 * month and IMPS the next): a large credit of about the same size that lands on about the same few days each month.
 * Only used when the payer or the word "salary" found nothing, and always reported as an estimate.
 */
internal object SalaryTiming {
    private val MIN_AMOUNT = BigDecimal(5_000)
    private val SMALL_MULTIPLE = BigDecimal(2)
    private val OUTLIER_RATIO = BigDecimal("1.5")
    private val SIZE_TOLERANCE = BigDecimal("0.40")
    private const val MONTHS_LOOKED_AT = 6
    private const val MONTHS_AVERAGED = 3
    private const val DAY_WINDOW = 3
    private const val MONTH_END_FROM = 28
    private const val DAYS_IN_LONG_MONTH = 31
    private const val FULL_HISTORY_MONTHS = 4
    private const val MIN_MONTHS_FULL = 3
    private const val MIN_MONTHS_SHORT = 2
    const val KEY = "timing"

    private val NOT_INCOME = Regex("(?i)\\b(?:refund(?:ed)?|cash ?back|reversal|reversed|interest|dividend|reward|failed)\\b")
    private val notIncome = BodyMemo { body -> NOT_INCOME.containsMatchIn(BodyMemo.bounded(body)) }

    private class Credit(val month: YearMonth, val day: Int, val amount: BigDecimal) {
        /** Month-end days count as just before the 1st, so the 30th, 31st, 1st and 2nd sit close together. */
        val position: Int get() = if (day >= MONTH_END_FROM) day - DAYS_IN_LONG_MONTH else day
    }

    fun detect(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): SalaryPattern? {
        val today = LocalDate.ofInstant(now, zone)
        val thisMonth = YearMonth.from(today)
        val earliest = thisMonth.minusMonths(MONTHS_LOOKED_AT - 1L)

        var oldest = thisMonth
        val credits = ArrayList<Credit>()
        for (message in messages) {
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isAfter(today)) continue
            val month = YearMonth.from(date)
            if (month < oldest) oldest = month
            val tx = message.content as? MessageContent.Transaction ?: continue
            if (!tx.isReceived || tx.isCardPayment || message.setAside != null) continue
            if (month < earliest || notIncome.of(message.message)) continue
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it >= MIN_AMOUNT } ?: continue
            credits += Credit(month, date.dayOfMonth, amount)
        }
        if (credits.isEmpty()) return null

        // The usual small credit (everyday UPI): what is not the month's biggest. The salary has to stand well above it.
        val byMonth = credits.groupBy { it.month }
        val everyday = byMonth.values.flatMap { month -> month.sortedByDescending { it.amount }.drop(1).map { it.amount } }
        val threshold = if (everyday.isEmpty()) MIN_AMOUNT else MIN_AMOUNT.max(medianOf(everyday).multiply(SMALL_MULTIPLE))

        val spikes = credits.filter { credit ->
            if (credit.amount < threshold) return@filter false
            val smaller = byMonth.getValue(credit.month).filter { it.amount < threshold }.map { it.amount }
            smaller.isEmpty() || credit.amount >= medianOf(smaller).multiply(OUTLIER_RATIO)
        }
        if (spikes.size < MIN_MONTHS_SHORT) return null

        val historyMonths = java.time.temporal.ChronoUnit.MONTHS.between(oldest, thisMonth).toInt() + 1
        val needed = if (historyMonths < FULL_HISTORY_MONTHS) MIN_MONTHS_SHORT else MIN_MONTHS_FULL

        val cluster = bestCluster(spikes)
        if (cluster.size < needed) return null
        return pattern(cluster, thisMonth)
    }

    private fun medianOf(values: List<BigDecimal>) = SalaryDetector.median(values)

    /** The most months that agree on a day (within the window) and a size (within the tolerance), one credit per month. */
    private fun bestCluster(spikes: List<Credit>): List<Credit> {
        var best: List<Credit> = emptyList()
        for (anchor in spikes) {
            val near = spikes.filter { Math.abs(it.position - anchor.position) <= DAY_WINDOW && closeInSize(it.amount, anchor.amount) }
            if (near.isEmpty()) continue
            val size = medianOf(near.map { it.amount })
            val position = SalaryDetector.medianDay(near.map { it.position })
            val agreeing = near.filter { Math.abs(it.position - position) <= DAY_WINDOW && closeInSize(it.amount, size) }
                .groupBy { it.month }.map { (_, ofMonth) -> ofMonth.minBy { (it.amount - size).abs() } }
            if (agreeing.size > best.size || (agreeing.size == best.size && agreeing.size > 0 && newer(agreeing, best))) best = agreeing
        }
        return best
    }

    private fun closeInSize(a: BigDecimal, b: BigDecimal) = (a - b).abs() <= b.multiply(SIZE_TOLERANCE)

    private fun newer(a: List<Credit>, b: List<Credit>) = a.maxOf { it.month } > b.maxOf { it.month }

    private fun pattern(cluster: List<Credit>, thisMonth: YearMonth): SalaryPattern {
        val byMonth = cluster.associateBy { it.month }
        val earlier = byMonth.filterKeys { it != thisMonth }
        val reference = (if (earlier.isNotEmpty()) earlier else byMonth).entries
            .sortedByDescending { it.key }.take(MONTHS_AVERAGED).map { it.value.amount }
        val position = SalaryDetector.medianDay(cluster.map { it.position })
        return SalaryPattern(
            payer = KEY,
            typical = SalaryDetector.median(reference).setScale(0, RoundingMode.HALF_UP),
            low = reference.min(),
            high = reference.max(),
            typicalDay = if (position <= 0) position + DAYS_IN_LONG_MONTH else position,
            monthsSeen = byMonth.size,
            thisMonth = byMonth[thisMonth]?.amount,
            confidence = if (byMonth.size >= FULL_HISTORY_MONTHS) SalaryConfidence.MEDIUM else SalaryConfidence.LOW
        )
    }
}
