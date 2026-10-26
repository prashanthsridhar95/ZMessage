package com.zyke.message.domain.dashboard

import com.zyke.message.domain.model.Merchant
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.LocalDate

enum class DashboardPeriod(val label: String) {
    THIS_MONTH("This month"),
    LAST_MONTH("Last month"),
    LAST_3_MONTHS("3 months"),
    ALL_TIME("All time")
}

enum class FlowDirection(val label: String) {
    SPENT("Spent"),
    RECEIVED("Received")
}

data class DashboardQuery(
    val period: DashboardPeriod = DashboardPeriod.THIS_MONTH,
    val direction: FlowDirection = FlowDirection.SPENT,
    /** Narrows everything below the headline to one category. */
    val category: MessageCategory? = null
)

data class DateRange(val start: LocalDate, val endInclusive: LocalDate) {
    operator fun contains(date: LocalDate) = !date.isBefore(start) && !date.isAfter(endInclusive)
}

/** One money movement behind a vendor total. */
data class Payment(
    val messageId: Long,
    val epochMillis: Long,
    val amount: BigDecimal,
    /** Where the money came from or went to, e.g. "HDFC Card •• 1234". */
    val source: String
)

data class VendorSummary(
    val key: String,
    val merchant: Merchant,
    val total: BigDecimal,
    val count: Int,
    val lastEpochMillis: Long,
    /** Newest first, capped. */
    val recent: List<Payment>
)

data class CategoryTotal(val category: MessageCategory, val total: BigDecimal, val count: Int)

data class SourceTotal(val label: String, val total: BigDecimal, val count: Int)

enum class TrendUnit { DAY, MONTH }

data class TrendPoint(val start: LocalDate, val amount: BigDecimal)

data class Dashboard(
    val range: DateRange,
    val direction: FlowDirection,
    /** Total in [direction] for the current filters. */
    val total: BigDecimal,
    val count: Int,
    /** Same direction over the comparable earlier period; null when there is none. */
    val previousTotal: BigDecimal?,
    /** Totals for the whole period, ignoring direction and category filters. */
    val spentTotal: BigDecimal,
    val receivedTotal: BigDecimal,
    val categories: List<CategoryTotal>,
    val vendors: List<VendorSummary>,
    val sources: List<SourceTotal>,
    val trend: List<TrendPoint>,
    val trendUnit: TrendUnit
) {
    /** Percent change versus [previousTotal]; null if there is nothing to compare with. */
    val changePercent: Double?
        get() = previousTotal
            ?.takeIf { it.signum() > 0 }
            ?.let { (total - it).toDouble() / it.toDouble() * 100.0 }

    val isEmpty: Boolean get() = count == 0

    companion object {
        fun empty(range: DateRange, direction: FlowDirection) = Dashboard(
            range, direction, BigDecimal.ZERO, 0, null, BigDecimal.ZERO, BigDecimal.ZERO,
            emptyList(), emptyList(), emptyList(), emptyList(), TrendUnit.DAY
        )
    }
}
