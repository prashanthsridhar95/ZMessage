package com.zyke.message.domain.investments

import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.pow

/**
 * The sums behind the investment tracker. Pure; every figure is an estimate that the person can replace with the amount on
 * their receipt ([Terms.maturityAmountGiven]).
 *
 * Fixed deposit, compounded n times a year over t years (t = months / 12 when the tenure is in months, else days / 365):
 *     maturity = P * (1 + r/n) ^ (n * t)            simple interest: P * (1 + r * t)
 * Recurring deposit, installment M paid at the start of each month k = 1..N, quarterly compounded in the usual Indian way:
 *     maturity = sum over k of M * (1 + r/n) ^ (n * (N - k + 1) / 12)       (n = 4 by default)
 * Growth of a holding: gain = current - invested; CAGR = (current / invested) ^ (365.25 / days) - 1, only from 30 days on.
 * Amounts are rounded to the rupee.
 */
object InvestmentMath {
    const val STALE_DAYS = 45L
    const val MIN_CAGR_DAYS = 30L
    private const val DAYS_IN_YEAR = 365.0
    private const val DAYS_IN_MONTH = 365.25 / 12

    /** Start plus [months], the last day of the month when the day does not exist (31 Jan + 1 month = 28/29 Feb). */
    fun addMonths(start: LocalDate, months: Int): LocalDate = start.plusMonths(months.toLong())

    /** The tenure in months: entered, or counted from the start to the maturity date when that is a whole number of months. */
    fun tenureMonths(h: Holding): Int? {
        h.terms.tenureMonths?.let { return it }
        val start = h.startDate ?: return null
        val end = h.terms.maturityDate ?: return null
        if (!end.isAfter(start)) return null
        val months = ChronoUnit.MONTHS.between(start, end).toInt()
        return months.takeIf { it > 0 && addMonths(start, it) == end }
    }

    /** The maturity date: entered, or the start plus the tenure. */
    fun maturityDate(h: Holding): LocalDate? {
        h.terms.maturityDate?.let { return it }
        val start = h.startDate ?: return null
        val months = h.terms.tenureMonths ?: return null
        return addMonths(start, months)
    }

    fun daysToMaturity(h: Holding, today: LocalDate): Long? = maturityDate(h)?.let { ChronoUnit.DAYS.between(today, it) }

    /** Deposit whose date has passed while still marked active: the person should say what happened to it. */
    fun maturityOverdue(h: Holding, today: LocalDate): Boolean =
        h.status == HoldingStatus.ACTIVE && h.isDeposit && (maturityDate(h)?.isBefore(today) ?: false)

    fun suggestedStatus(h: Holding, today: LocalDate): HoldingStatus = if (maturityOverdue(h, today)) HoldingStatus.MATURED else h.status

    private fun years(h: Holding): Double? {
        tenureMonths(h)?.let { return it / 12.0 }
        val start = h.startDate ?: return null
        val end = h.terms.maturityDate ?: return null
        val days = ChronoUnit.DAYS.between(start, end)
        return if (days > 0) days / DAYS_IN_YEAR else null
    }

    private fun growth(c: Compounding, rate: Double, years: Double): Double = when {
        years <= 0.0 -> 1.0
        c.perYear > 0 -> (1 + rate / c.perYear).pow(c.perYear * years)
        else -> 1 + rate * years
    }

    private fun rate(h: Holding): Double = h.terms.ratePercent.toDouble() / 100.0

    private fun rupee(v: Double): BigDecimal = BigDecimal(v).setScale(0, RoundingMode.HALF_UP)

    /** The amount paid on the maturity date; null when the terms are not complete enough to say. */
    fun maturityValue(h: Holding): BigDecimal? {
        val t = h.terms
        return when (h.kind) {
            InvestmentKind.FIXED_DEPOSIT -> {
                if (h.invested.signum() <= 0) return null
                if (t.payout == Payout.CUMULATIVE) t.maturityAmountGiven?.let { return it }
                if (t.payout == Payout.PERIODIC) return h.invested
                val y = years(h) ?: return null
                if (t.compounding == Compounding.AT_MATURITY_AS_GIVEN) return null
                rupee(h.invested.toDouble() * growth(t.compounding, rate(h), y))
            }
            InvestmentKind.RECURRING_DEPOSIT -> {
                t.maturityAmountGiven?.let { return it }
                val m = t.monthlyInstallment ?: return null
                val n = tenureMonths(h) ?: return null
                if (m.signum() <= 0 || n <= 0 || t.compounding == Compounding.AT_MATURITY_AS_GIVEN) return null
                rupee((1..n).sumOf { k -> m.toDouble() * growth(t.compounding, rate(h), (n - k + 1) / 12.0) })
            }
            else -> null
        }
    }

    /** What has been put in so far: the amount for most holdings; installments paid for a recurring deposit. */
    fun invested(h: Holding, today: LocalDate): BigDecimal =
        if (h.kind == InvestmentKind.RECURRING_DEPOSIT) (h.terms.monthlyInstallment ?: BigDecimal.ZERO) * BigDecimal(installmentsPaid(h, today))
        else h.invested

    /** What will be put in by the end: for a recurring deposit all installments. */
    fun totalDeposit(h: Holding): BigDecimal? =
        if (h.kind == InvestmentKind.RECURRING_DEPOSIT) tenureMonths(h)?.let { (h.terms.monthlyInstallment ?: BigDecimal.ZERO) * BigDecimal(it) }
        else h.invested

    /** Interest over the whole term (for a periodic-payout deposit, the total that will be paid out). */
    fun interestAtMaturity(h: Holding): BigDecimal? {
        if (h.kind == InvestmentKind.FIXED_DEPOSIT && h.terms.payout == Payout.PERIODIC) {
            val y = years(h) ?: return null
            return rupee(h.invested.toDouble() * rate(h) * y)
        }
        val maturity = maturityValue(h) ?: return null
        return maturity - (totalDeposit(h) ?: return null)
    }

    /** One payout of a periodic-payout deposit (monthly, or per compounding period). */
    fun periodicPayout(h: Holding): BigDecimal? {
        if (h.kind != InvestmentKind.FIXED_DEPOSIT || h.terms.payout != Payout.PERIODIC) return null
        val n = h.terms.compounding.perYear.takeIf { it > 0 } ?: 12
        return rupee(h.invested.toDouble() * rate(h) / n)
    }

    /** The estimated worth on [on]: compounded up to the day, never above the maturity value and never falling. Null when unknown. */
    fun accruedValue(h: Holding, on: LocalDate): BigDecimal? {
        val start = h.startDate ?: return null
        val maturity = maturityValue(h)
        val end = maturityDate(h)
        return when (h.kind) {
            InvestmentKind.FIXED_DEPOSIT -> {
                if (h.invested.signum() <= 0) return null
                if (h.terms.payout == Payout.PERIODIC) return h.invested
                if (end == null || maturity == null) return h.invested
                if (!on.isBefore(end)) return maturity
                if (!on.isAfter(start)) return h.invested
                val total = ChronoUnit.DAYS.between(start, end).toDouble()
                val f = ChronoUnit.DAYS.between(start, on) / total
                val p = h.invested.toDouble()
                val value = if (h.terms.maturityAmountGiven != null) {
                    // Interest spread geometrically between the amount put in and the amount stated.
                    p * (maturity.toDouble() / p).pow(f)
                } else {
                    p * growth(h.terms.compounding, rate(h), f * (years(h) ?: 0.0))
                }
                rupee(value).min(maturity).max(h.invested)
            }
            InvestmentKind.RECURRING_DEPOSIT -> {
                val m = h.terms.monthlyInstallment ?: return null
                val paid = installmentsPaid(h, on)
                if (paid == 0) return BigDecimal.ZERO
                if (end != null && maturity != null && !on.isBefore(end)) return maturity
                val dates = installmentDates(h).take(paid)
                val value = dates.sumOf { d ->
                    val months = ChronoUnit.DAYS.between(d, on).coerceAtLeast(0) / DAYS_IN_MONTH
                    m.toDouble() * growth(h.terms.compounding.takeIf { it != Compounding.AT_MATURITY_AS_GIVEN } ?: Compounding.QUARTERLY, rate(h), months / 12.0)
                }
                val out = rupee(value)
                (if (maturity != null) out.min(maturity) else out).max(m * BigDecimal(paid))
            }
            else -> null
        }
    }

    /** Interest earned so far, an estimate. */
    fun interestSoFar(h: Holding, today: LocalDate): BigDecimal? {
        if (h.kind == InvestmentKind.FIXED_DEPOSIT && h.terms.payout == Payout.PERIODIC) {
            val start = h.startDate ?: return null
            val end = maturityDate(h) ?: return null
            val total = interestAtMaturity(h) ?: return null
            val days = ChronoUnit.DAYS.between(start, end).toDouble().takeIf { it > 0 } ?: return null
            val f = (ChronoUnit.DAYS.between(start, today).toDouble() / days).coerceIn(0.0, 1.0)
            return rupee(total.toDouble() * f)
        }
        val accrued = accruedValue(h, today) ?: return null
        return (accrued - invested(h, today)).max(BigDecimal.ZERO)
    }

    // ---- recurring deposits

    /** The day each installment falls on: the first on the start date, the rest on the installment day of each month. */
    fun installmentDates(h: Holding): Sequence<LocalDate> {
        val start = h.startDate ?: return emptySequence()
        val day = h.terms.installmentDay ?: start.dayOfMonth
        val limit = tenureMonths(h)
        return generateSequence(0) { it + 1 }.takeWhile { limit == null || it < limit }.map { i ->
            if (i == 0) start else {
                val ym = YearMonth.from(start).plusMonths(i.toLong())
                ym.atDay(minOf(day, ym.lengthOfMonth()))
            }
        }
    }

    /** Installments paid by [today]: the person's own count if given, else those whose day has come. */
    fun installmentsPaid(h: Holding, today: LocalDate): Int {
        if (h.kind != InvestmentKind.RECURRING_DEPOSIT) return 0
        val total = tenureMonths(h)
        h.terms.installmentsPaidOverride?.let { return if (total != null) it.coerceIn(0, total) else it.coerceAtLeast(0) }
        val counted = installmentDates(h).takeWhile { !it.isAfter(today) }.take(1200).count()
        return if (total != null) minOf(counted, total) else counted
    }

    fun installmentsRemaining(h: Holding, today: LocalDate): Int? = tenureMonths(h)?.let { (it - installmentsPaid(h, today)).coerceAtLeast(0) }

    /** The next installment day on or after [today], if any are left. */
    fun nextInstallment(h: Holding, today: LocalDate): LocalDate? {
        if (h.kind != InvestmentKind.RECURRING_DEPOSIT) return null
        val remaining = installmentsRemaining(h, today)
        if (remaining != null && remaining <= 0) return null
        return installmentDates(h).firstOrNull { !it.isBefore(today) }
    }

    /**
     * The successor of a matured deposit: starts on the old maturity date with the maturity amount as its principal (the amount
     * from the receipt when there is one), the same tenure and terms; the person edits whatever the bank changed.
     */
    fun renewalDraft(old: Holding, newId: String, today: LocalDate): Holding {
        val start = maturityDate(old) ?: today
        val principal = if (old.kind == InvestmentKind.RECURRING_DEPOSIT) BigDecimal.ZERO else (maturityValue(old) ?: old.invested)
        return old.copy(
            id = newId, invested = principal, currentValue = null, valueAsOf = null, startDate = start, status = HoldingStatus.ACTIVE,
            closedOn = null, closedAmount = null, history = emptyList(), renewedFrom = old.id, receiptNo = "",
            terms = old.terms.copy(
                tenureMonths = tenureMonths(old) ?: 12, maturityDate = null, maturityAmountGiven = null,
                installmentsPaidOverride = null, installmentDay = old.terms.installmentDay
            )
        )
    }

    // ---- funds, shares, gold

    fun gain(current: BigDecimal, invested: BigDecimal): BigDecimal = current - invested

    fun gainPercent(current: BigDecimal, invested: BigDecimal): Double? =
        if (invested.signum() <= 0) null else (current - invested).toDouble() / invested.toDouble() * 100.0

    /** Yearly growth, as a percentage, from the start date; null before 30 days, without a start, or when the value is nil. */
    fun cagr(current: BigDecimal, invested: BigDecimal, start: LocalDate?, on: LocalDate): Double? {
        start ?: return null
        val days = ChronoUnit.DAYS.between(start, on)
        if (days < MIN_CAGR_DAYS || current.signum() <= 0 || invested.signum() <= 0) return null
        return ((current.toDouble() / invested.toDouble()).pow(365.25 / days) - 1) * 100.0
    }

    /** The value is older than [STALE_DAYS] days (for a holding whose value the person keeps up to date). */
    fun isStale(h: Holding, today: LocalDate): Boolean {
        if (h.isDeposit && h.currentValue == null) return false
        val asOf = h.valueAsOf ?: return false
        return ChronoUnit.DAYS.between(asOf, today) > STALE_DAYS
    }

    /** A new value on [on]: sets it and appends to the history (capped, one point per day). */
    fun withValue(h: Holding, value: BigDecimal, on: LocalDate, invested: BigDecimal = h.invested): Holding {
        val point = ValuePoint(on, value, invested)
        val history = (h.history.filter { it.date != on } + point).sortedBy { it.date }.takeLast(Holding.MAX_HISTORY)
        return h.copy(currentValue = value, valueAsOf = on, invested = invested, history = history)
    }
}
