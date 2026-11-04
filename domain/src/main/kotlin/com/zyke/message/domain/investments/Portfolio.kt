package com.zyke.message.domain.investments

import java.math.BigDecimal
import java.time.LocalDate

/** One holding worked out for a day: what went in, what it is worth, and how much it has grown. */
data class HoldingValue(
    val holding: Holding,
    val invested: BigDecimal,
    val current: BigDecimal,
    /** The value is an estimate from the deposit's terms, not a figure the person entered. */
    val estimated: Boolean,
    /** A fund, share or gold holding with no value entered yet: counted at what was invested. */
    val valueMissing: Boolean,
    val stale: Boolean,
    val gain: BigDecimal,
    val gainPercent: Double?,
    val cagr: Double?,
    val maturityOn: LocalDate?,
    val daysToMaturity: Long?
)

data class Slice(val key: String, val label: String, val kind: InvestmentKind?, val value: BigDecimal, val share: Double)

data class Maturity(val holding: Holding, val on: LocalDate, val days: Long, val value: BigDecimal?)

data class PortfolioSummary(
    /** Active and matured holdings, deposits first by maturity then the rest by value. */
    val rows: List<HoldingValue>,
    val closed: List<Holding>,
    val totalInvested: BigDecimal,
    /** The worth of the holdings, without the provident fund. */
    val totalCurrent: BigDecimal,
    /** Provident fund balance from messages, when there is one. */
    val providentFund: BigDecimal?,
    /** Holdings plus provident fund. */
    val totalValue: BigDecimal,
    val gain: BigDecimal,
    val gainPercent: Double?,
    val allocation: List<Slice>,
    val maturities: List<Maturity>,
    val staleCount: Int,
    val estimatedCount: Int,
    val missingCount: Int,
    val overdue: List<Holding>
) {
    val count: Int get() = rows.size
    val isEmpty: Boolean get() = rows.isEmpty() && closed.isEmpty()
}

object Portfolio {
    const val PF_KEY = "pf"

    fun value(h: Holding, today: LocalDate): HoldingValue {
        val invested = InvestmentMath.invested(h, today)
        val entered = h.currentValue
        val accrued = if (entered == null && h.isDeposit) InvestmentMath.accruedValue(h, today) else null
        val current = entered ?: accrued ?: invested
        val gain = current - invested
        val maturity = if (h.isDeposit) InvestmentMath.maturityDate(h) else null
        return HoldingValue(
            holding = h, invested = invested, current = current,
            estimated = accrued != null, valueMissing = !h.isDeposit && entered == null,
            stale = InvestmentMath.isStale(h, today), gain = gain,
            gainPercent = InvestmentMath.gainPercent(current, invested),
            cagr = if (h.isDeposit) null else InvestmentMath.cagr(current, invested, h.startDate, today),
            maturityOn = maturity, daysToMaturity = maturity?.let { InvestmentMath.daysToMaturity(h, today) }
        )
    }

    fun summarize(holdings: List<Holding>, providentFund: BigDecimal?, today: LocalDate): PortfolioSummary {
        val live = holdings.filter { it.isActiveish }
        val rows = live.map { value(it, today) }.sortedWith(
            compareBy<HoldingValue>({ !it.holding.isDeposit }, { it.maturityOn ?: LocalDate.MAX }).thenByDescending { it.current }.thenBy { it.holding.name.lowercase() }
        )
        val invested = rows.fold(BigDecimal.ZERO) { s, r -> s + r.invested }
        val current = rows.fold(BigDecimal.ZERO) { s, r -> s + r.current }
        val pf = providentFund?.takeIf { it.signum() > 0 }
        val total = current + (pf ?: BigDecimal.ZERO)
        val slices = ArrayList<Slice>()
        rows.groupBy { it.holding.kind }.forEach { (kind, list) ->
            val v = list.fold(BigDecimal.ZERO) { s, r -> s + r.current }
            if (v.signum() > 0) slices += Slice(kind.name, kind.label, kind, v, 0.0)
        }
        if (pf != null) slices += Slice(PF_KEY, "Provident fund (from messages)", null, pf, 0.0)
        val allocation = slices.sortedByDescending { it.value }.map {
            it.copy(share = if (total.signum() > 0) it.value.toDouble() / total.toDouble() else 0.0)
        }
        val maturities = rows.filter { it.holding.status == HoldingStatus.ACTIVE }.mapNotNull { r ->
            val on = r.maturityOn ?: return@mapNotNull null
            Maturity(r.holding, on, r.daysToMaturity ?: 0, InvestmentMath.maturityValue(r.holding))
        }.sortedBy { it.on }
        return PortfolioSummary(
            rows = rows,
            closed = holdings.filter { !it.isActiveish }.sortedByDescending { it.closedOn ?: LocalDate.MIN },
            totalInvested = invested, totalCurrent = current, providentFund = pf, totalValue = total,
            gain = current - invested, gainPercent = InvestmentMath.gainPercent(current, invested),
            allocation = allocation, maturities = maturities,
            staleCount = rows.count { it.stale }, estimatedCount = rows.count { it.estimated }, missingCount = rows.count { it.valueMissing },
            overdue = live.filter { InvestmentMath.maturityOverdue(it, today) }
        )
    }

    /** Active deposits maturing from [today] on, within [withinDays] days (the Home "Plans" horizon), soonest first. */
    fun maturingSoon(holdings: List<Holding>, today: LocalDate, withinDays: Long = 14): List<Maturity> =
        holdings.filter { it.status == HoldingStatus.ACTIVE && it.isDeposit }.mapNotNull { h ->
            val on = InvestmentMath.maturityDate(h) ?: return@mapNotNull null
            val days = java.time.temporal.ChronoUnit.DAYS.between(today, on)
            if (days < 0 || days > withinDays) null else Maturity(h, on, days, InvestmentMath.maturityValue(h))
        }.sortedBy { it.on }

    /** Institutions typed before, most used first, for suggestions. */
    fun institutions(holdings: List<Holding>): List<String> =
        holdings.map { it.institution.trim() }.filter { it.isNotEmpty() }.groupingBy { it }.eachCount().entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key.lowercase() }).map { it.key }
}

/** What is wrong with a holding's fields, by field name; empty when it can be saved. */
object HoldingCheck {
    const val MAX_RATE = 25

    fun validate(h: Holding, today: LocalDate): Map<String, String> {
        val e = LinkedHashMap<String, String>()
        if (h.name.isBlank()) e["name"] = "Give it a name"
        val t = h.terms
        if (h.kind != InvestmentKind.RECURRING_DEPOSIT && h.invested.signum() <= 0) e["invested"] = "Enter an amount above zero"
        h.currentValue?.let { if (it.signum() < 0) e["currentValue"] = "The value cannot be negative" }
        if (h.isDeposit) {
            if (t.ratePercent.signum() < 0 || t.ratePercent > BigDecimal(MAX_RATE)) e["rate"] = "Rate must be between 0 and $MAX_RATE%"
            if (h.startDate == null) e["startDate"] = "Choose the date it started"
            val maturity = InvestmentMath.maturityDate(h)
            if (t.tenureMonths == null && t.maturityDate == null) e["tenure"] = "Enter the tenure or the maturity date"
            if (t.tenureMonths != null && t.tenureMonths <= 0) e["tenure"] = "Tenure must be at least 1 month"
            if (maturity != null && h.startDate != null && !maturity.isAfter(h.startDate)) e["maturity"] = "Maturity must be after the start date"
            t.maturityAmountGiven?.let { if (it.signum() <= 0) e["maturityGiven"] = "Enter the amount from the receipt" }
            if (h.kind == InvestmentKind.RECURRING_DEPOSIT) {
                val m = t.monthlyInstallment
                if (m == null || m.signum() <= 0) e["installment"] = "Enter the monthly installment"
                t.installmentDay?.let { if (it !in 1..31) e["installmentDay"] = "Day must be 1 to 31" }
            }
        }
        return e
    }
}
