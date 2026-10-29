package com.zyke.message.domain.calendar

import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.home.DebitPayments
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.plan.SalaryDetector
import com.zyke.message.domain.reminders.Reminder
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/** Something the person put on their own calendar. */
data class CalendarEvent(
    val id: String,
    val title: String,
    val date: LocalDate,
    val time: LocalTime? = null,
    val note: String = ""
)

enum class CalendarKind(val label: String) {
    EVENT("Event"),
    REMINDER("Reminder"),
    BILL("Card bill"),
    DEBIT("Auto-debit"),
    REGULAR("Regular payment"),
    SALARY("Salary"),
    LIFE("Order, trip or appointment"),
    GOAL("Goal")
}

/** One line on one day of the calendar. */
data class CalendarEntry(
    val kind: CalendarKind,
    val title: String,
    val detail: String,
    val date: LocalDate,
    val amount: BigDecimal? = null,
    val time: LocalTime? = null,
    /** Settled or finished: a bill that was paid, an order that arrived. */
    val done: Boolean = false,
    /** The message it came from, to open it. */
    val messageId: Long? = null,
    /** The event it is, to edit or remove it. */
    val eventId: String? = null
)

/** Everything with a date, laid out by day: the person's own events, and what the messages and the plan say is coming. */
object CalendarBuilder {
    fun month(
        month: YearMonth,
        messages: List<ClassifiedMessage>,
        reminders: List<Reminder>,
        events: List<CalendarEvent>,
        goals: List<Goal>,
        plan: MoneyPlan,
        now: Instant,
        zone: ZoneId
    ): Map<LocalDate, List<CalendarEntry>> {
        val today = LocalDate.ofInstant(now, zone)
        val inMonth = { date: LocalDate -> YearMonth.from(date) == month }
        val all = ArrayList<CalendarEntry>()

        events.filter { inMonth(it.date) }.forEach {
            all += CalendarEntry(CalendarKind.EVENT, it.title, it.note, it.date, time = it.time, eventId = it.id)
        }

        reminders.forEach { reminder ->
            val on = occurrence(reminder, month) ?: return@forEach
            all += CalendarEntry(CalendarKind.REMINDER, reminder.title, if (reminder.repeatsMonthly) "Every month" else "", on, reminder.amount)
        }

        val byId = messages.associateBy { it.id }
        CardBillsBuilder.build(messages, today).forEach { card ->
            val entries = listOf(card.latest) + card.earlier
            entries.forEachIndexed { index, entry ->
                val due = entry.dueDate?.takeIf(inMonth) ?: return@forEachIndexed
                val message = byId[entry.messageId]
                val bill = message?.content as? MessageContent.CardBill
                // The latest bill is paid when a payment message says so; an earlier one was settled by the next.
                val paid = if (index > 0) true else bill != null && BillPayments.find(message, bill, messages, zone) != null
                all += CalendarEntry(CalendarKind.BILL, card.label, if (paid) "Paid" else "Bill due", due, entry.amount, done = paid, messageId = entry.messageId)
            }
        }

        messages.asSequence().mapNotNull { message ->
            val scheduled = message.content as? MessageContent.ScheduledPayment ?: return@mapNotNull null
            if (scheduled.kind != ScheduledKind.UPCOMING_DEBIT) return@mapNotNull null
            val date = scheduled.date?.takeIf(inMonth) ?: return@mapNotNull null
            val amount = scheduled.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            val paid = DebitPayments.find(message, scheduled.payee, amount, date, messages, zone) != null
            CalendarEntry(CalendarKind.DEBIT, scheduled.payee.ifEmpty { "Auto-debit" }, if (paid) "Paid" else "Auto-debit", date, amount, done = paid, messageId = message.id)
        }.distinctBy { Triple(it.title.lowercase(), it.date, it.amount) }.forEach { all += it }

        messages.forEach { message ->
            val life = message.content as? MessageContent.Life ?: return@forEach
            val date = life.date?.takeIf(inMonth) ?: return@forEach
            all += CalendarEntry(CalendarKind.LIFE, life.title, life.status, date, time = life.time, done = life.isDone, messageId = message.id)
        }

        // What is regularly paid or earned, on its usual day, from this month on.
        val thisMonth = YearMonth.from(today)
        if (month >= thisMonth) {
            plan.items.forEach { item ->
                val day = item.dayOfMonth ?: return@forEach
                val on = month.atDay(minOf(day, month.lengthOfMonth()))
                all += CalendarEntry(CalendarKind.REGULAR, item.name, item.kind.label, on, item.amount, done = on.isBefore(today))
            }
            val salary = SalaryDetector.detect(messages, now, zone)
            val salaryDay = salary?.typicalDay ?: plan.salaryDay
            val salaryAmount = salary?.typical ?: plan.salary
            if (salaryDay != null) {
                val on = month.atDay(minOf(salaryDay, month.lengthOfMonth()))
                all += CalendarEntry(CalendarKind.SALARY, "Salary", if (on.isBefore(today)) "Usually arrives" else "Expected", on, salaryAmount, done = on.isBefore(today))
            }
        }

        goals.forEach { goal ->
            val date = goal.targetDate?.takeIf(inMonth) ?: return@forEach
            all += CalendarEntry(CalendarKind.GOAL, goal.name, "Goal date", date, goal.target)
        }

        return all.groupBy { it.date }.mapValues { (_, list) ->
            list.sortedWith(compareBy<CalendarEntry>({ it.time == null }, { it.time }, { it.kind.ordinal }, { it.title }))
        }
    }

    /** The day a reminder falls on in [month], or null when it does not fall in it. */
    private fun occurrence(reminder: Reminder, month: YearMonth): LocalDate? {
        if (!reminder.repeatsMonthly) return reminder.date.takeIf { YearMonth.from(it) == month }
        if (month < YearMonth.from(reminder.date)) return null
        return month.atDay(minOf(reminder.date.dayOfMonth, month.lengthOfMonth()))
    }
}
