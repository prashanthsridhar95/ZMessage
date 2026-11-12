package com.zyke.message.domain.bills

import com.zyke.message.domain.motion.MotionMath
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** How close a due date is. The screen says it in words as well as colour: Overdue is the danger tone, Today and Soon the warning tone. */
enum class DueTone { OVERDUE, TODAY, SOON, LATER }

/** What the Bills hero says: how much is still to pay this month, how much is paid, and how far along that is. */
data class BillsSummary(
    val dueTotal: BigDecimal,
    val paidTotal: BigDecimal,
    val dueCount: Int,
    val paidCount: Int
) {
    val count: Int get() = dueCount + paidCount
    val total: BigDecimal get() = dueTotal.add(paidTotal)

    /** Paid as a share of everything for the month (0 to 1). Nothing at all is 0; everything paid is 1. */
    val fraction: Float
        get() = when {
            count == 0 -> 0f
            total.signum() <= 0 -> paidCount.toFloat() / count
            else -> MotionMath.share(paidTotal, total)
        }

    val allPaid: Boolean get() = count > 0 && dueCount == 0
}

/**
 * The arithmetic of the Bills tab, kept pure so it is tested without a screen: how a due date is said and toned, how bills
 * are ordered, what counts for this month, and how far a month's bills (or one part-paid bill) have come.
 */
object BillsMath {
    /** A bill due within this many days (today included) is "soon". */
    const val SOON_DAYS = 3L

    /** Up to this many days ahead a due date is said as "in N days"; further out it is a date. */
    const val RELATIVE_DAYS = 7L

    fun daysUntil(due: LocalDate, today: LocalDate): Long = ChronoUnit.DAYS.between(today, due)

    fun tone(due: LocalDate, today: LocalDate): DueTone {
        val days = daysUntil(due, today)
        return when {
            days < 0 -> DueTone.OVERDUE
            days == 0L -> DueTone.TODAY
            days <= SOON_DAYS -> DueTone.SOON
            else -> DueTone.LATER
        }
    }

    /** "Overdue by 2 days", "Due today", "Due tomorrow", "Due in 3 days", or "Due 5 Dec" a week or more out. */
    fun dueCaption(due: LocalDate, today: LocalDate, locale: Locale = Locale.getDefault()): String {
        val days = daysUntil(due, today)
        return when {
            days < 0 -> "Overdue by " + plural(-days, "day", "days")
            days == 0L -> "Due today"
            days == 1L -> "Due tomorrow"
            days <= RELATIVE_DAYS -> "Due in $days days"
            else -> "Due " + DateTimeFormatter.ofPattern("d MMM", locale).format(due)
        }
    }

    /** Part of a bill that is paid, as a share of the whole (0 to 1). No part, or no bill, is 0; paid in full or more is 1. */
    fun partFraction(amount: BigDecimal, partPaid: BigDecimal?): Float {
        if (partPaid == null || partPaid.signum() <= 0 || amount.signum() <= 0) return 0f
        return MotionMath.share(partPaid, amount)
    }

    /** What is left of a bill once [partPaid] is taken off; never below zero. */
    fun left(amount: BigDecimal, partPaid: BigDecimal?): BigDecimal =
        if (partPaid == null) amount else amount.subtract(partPaid).max(BigDecimal.ZERO)

    /** A bill belongs to this month's hero when it is due by the end of today's month (overdue ones included). */
    fun countsForThisMonth(due: LocalDate, today: LocalDate): Boolean = !YearMonth.from(due).isAfter(YearMonth.from(today))

    /** A bill marked as paid belongs to this month's progress when it was marked this month. */
    fun paidThisMonth(markedOn: LocalDate, today: LocalDate): Boolean = YearMonth.from(markedOn) == YearMonth.from(today)

    /**
     * [due] is what is still to pay on each unpaid bill (its amount less any part already paid, see [left]); [paid] what each
     * paid bill came to; [partPaid] the money already put towards bills that are not yet paid in full, which counts as paid
     * money (it moves the bar) without making those bills "paid" (the counts stay).
     */
    fun summary(due: List<BigDecimal>, paid: List<BigDecimal>, partPaid: BigDecimal = BigDecimal.ZERO): BillsSummary =
        BillsSummary(
            due.fold(BigDecimal.ZERO, BigDecimal::add),
            paid.fold(partPaid, BigDecimal::add),
            due.size, paid.size
        )

    /** "2 of 5 paid", "All 5 paid" or "Nothing due this month". */
    fun progressCaption(summary: BillsSummary): String = when {
        summary.count == 0 -> "Nothing due this month"
        summary.allPaid -> if (summary.count == 1) "Paid" else "All ${summary.count} paid"
        else -> "${summary.paidCount} of ${summary.count} paid"
    }

    /** Overdue first (the oldest first), then by date, the bigger amount before the smaller on a day, then by name. */
    fun <T> sortDue(items: List<T>, date: (T) -> LocalDate, amount: (T) -> BigDecimal, title: (T) -> String): List<T> =
        items.sortedWith(
            compareBy<T> { date(it) }.thenByDescending { amount(it) }.thenBy { title(it).lowercase() }
        )

    /** Paid bills, the most recently marked first, then by name. */
    fun <T> sortPaid(items: List<T>, markedOn: (T) -> LocalDate, title: (T) -> String): List<T> =
        items.sortedWith(compareByDescending<T> { markedOn(it) }.thenBy { title(it).lowercase() })

    private fun plural(n: Long, one: String, many: String) = if (n == 1L) "$n $one" else "$n $many"
}
