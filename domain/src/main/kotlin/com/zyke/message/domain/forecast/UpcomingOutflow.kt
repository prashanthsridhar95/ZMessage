package com.zyke.message.domain.forecast

import com.zyke.message.domain.home.UpcomingItem
import com.zyke.message.domain.insights.RecurringItem
import com.zyke.message.domain.loans.Loan
import com.zyke.message.domain.loans.Loans
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.Reminders
import java.math.BigDecimal
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

enum class OutflowSource { CARD_BILL, AUTO_DEBIT, EMI, REMINDER, PLAN, RECURRING }

/** One payment expected to leave on a day. */
data class OutflowItem(val title: String, val amount: BigDecimal, val date: LocalDate, val source: OutflowSource)

/** Everything expected to leave between [from] and [to] (both included). */
data class UpcomingOutflow(val from: LocalDate, val to: LocalDate, val items: List<OutflowItem>) {
    val total: BigDecimal get() = items.fold(BigDecimal.ZERO) { sum, item -> sum + item.amount }

    /** The [count] largest, biggest first. */
    fun biggest(count: Int = 3): List<OutflowItem> = items.sortedByDescending { it.amount }.take(count)

    val isEmpty: Boolean get() = items.isEmpty()
}

/**
 * Adds up what is about to leave from the app's different sources: card bills and announced auto-debits, EMIs,
 * reminders with an amount, the plan's regular outflows and SIPs that have a day, and recurring payments the person
 * confirmed. The same payment often shows up in two of them (an EMI that also has an auto-debit alert), so an item
 * with the same amount on the same or the next day as one already kept is dropped; the earlier source wins.
 */
object UpcomingOutflows {
    private const val DEFAULT_DAYS = 7L

    fun build(
        from: LocalDate,
        days: Long = DEFAULT_DAYS,
        upcoming: List<UpcomingItem> = emptyList(),
        loans: List<Loan> = emptyList(),
        reminders: List<Reminder> = emptyList(),
        plan: MoneyPlan = MoneyPlan(),
        recurring: List<RecurringItem> = emptyList()
    ): UpcomingOutflow {
        val to = from.plusDays(days - 1)
        fun inRange(date: LocalDate) = !date.isBefore(from) && !date.isAfter(to)
        val found = mutableListOf<OutflowItem>()

        upcoming.filter { it.paidOn == null && inRange(it.date) }.forEach {
            val source = if (it.kind == com.zyke.message.domain.home.UpcomingKind.CARD_BILL) OutflowSource.CARD_BILL else OutflowSource.AUTO_DEBIT
            found += OutflowItem(it.title, it.amount, it.date, source)
        }
        loans.forEach { loan ->
            Loans.schedule(loan).filter { inRange(it.dueDate) }.forEach { found += OutflowItem(loan.name, it.amount, it.dueDate, OutflowSource.EMI) }
        }
        Reminders.upcoming(reminders, from, withinDays = days - 1).forEach { due ->
            val amount = due.reminder.amount?.takeIf { it.signum() > 0 } ?: return@forEach
            found += OutflowItem(due.reminder.title, amount, due.on, OutflowSource.REMINDER)
        }
        (0 until days).map { from.plusDays(it) }.forEach { date ->
            plan.items.filter { it.dayOfMonth != null && it.amount.signum() > 0 }.forEach { item ->
                val day = minOf(item.dayOfMonth!!, YearMonth.from(date).lengthOfMonth())
                if (date.dayOfMonth == day) found += OutflowItem(item.name, item.amount, date, OutflowSource.PLAN)
            }
        }
        recurring.filter { it.isConfirmed && inRange(it.subscription.nextDue) }.forEach {
            found += OutflowItem(it.subscription.payee, it.subscription.amount, it.subscription.nextDue, OutflowSource.RECURRING)
        }

        val kept = mutableListOf<OutflowItem>()
        found.forEach { item ->
            val twin = kept.any { it.amount.compareTo(item.amount) == 0 && kotlin.math.abs(ChronoUnit.DAYS.between(it.date, item.date)) <= 1 }
            if (!twin) kept += item
        }
        return UpcomingOutflow(from, to, kept.sortedBy { it.date })
    }
}
