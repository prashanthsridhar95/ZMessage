package com.zyke.message.domain.investments

import com.zyke.message.domain.reminders.Reminder
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A reminder the app derives from a holding; the person never edits it, changing the holding changes it. */
data class ManagedReminder(
    /** "inv:<holdingId>:<mat|rd>:<date it falls on>": the same holding and day always give the same id. */
    val id: String,
    val holdingId: String,
    val date: LocalDate,
    val time: LocalTime,
    val title: String
) {
    fun toReminder(): Reminder = Reminder(id = id, title = title, amount = null, date = date, repeatsMonthly = false, time = time)

    companion object {
        const val PREFIX = "inv:"
        fun isManaged(id: String): Boolean = id.startsWith(PREFIX)
        fun holdingIdOf(id: String): String? = if (isManaged(id)) id.removePrefix(PREFIX).substringBeforeLast(':').substringBeforeLast(':') else null
    }
}

object InvestmentReminders {
    val MORNING: LocalTime = LocalTime.of(9, 0)
    const val RD_LEAD_DAYS = 2L
    private const val RD_AHEAD = 3
    private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

    /** Every reminder from [today] on that active deposits call for: maturity lead times, and installments when switched on. */
    fun build(holdings: List<Holding>, today: LocalDate): List<ManagedReminder> {
        val out = ArrayList<ManagedReminder>()
        holdings.filter { it.status == HoldingStatus.ACTIVE && it.isDeposit }.forEach { h ->
            val maturity = InvestmentMath.maturityDate(h)
            if (maturity != null) {
                val value = InvestmentMath.maturityValue(h)
                val leads = h.terms.remindLeadDays.filter { it >= 0 }.toSortedSet()
                val where = if (h.institution.isBlank()) h.name else h.institution
                val amount = value?.let { " (${inr(it)})" }.orEmpty()
                val text = "Your ${h.kind.short} at $where matures on ${maturity.format(DATE)}$amount"
                leads.forEach { lead ->
                    val on = maturity.minusDays(lead.toLong())
                    if (on.isBefore(today)) return@forEach
                    out += ManagedReminder("${ManagedReminder.PREFIX}${h.id}:mat:$on", h.id, on, MORNING, text)
                }
            }
            if (h.kind == InvestmentKind.RECURRING_DEPOSIT && h.terms.installmentReminder) {
                val m = h.terms.monthlyInstallment
                val dates = InvestmentMath.installmentDates(h).dropWhile { it.minusDays(RD_LEAD_DAYS).isBefore(today) }.take(RD_AHEAD)
                dates.forEach { due ->
                    val on = due.minusDays(RD_LEAD_DAYS)
                    val amount = m?.let { " ${inr(it)}" }.orEmpty()
                    out += ManagedReminder(
                        "${ManagedReminder.PREFIX}${h.id}:rd:$on", h.id, on, MORNING,
                        "RD installment$amount due on the ${ordinal(due.dayOfMonth)}"
                    )
                }
            }
        }
        return out.sortedWith(compareBy({ it.date }, { it.id }))
    }

    fun ordinal(day: Int): String = day.toString() + when {
        day in 11..13 -> "th"
        day % 10 == 1 -> "st"
        day % 10 == 2 -> "nd"
        day % 10 == 3 -> "rd"
        else -> "th"
    }

    /** "₹1,07,186": rupees with Indian digit grouping. */
    fun inr(amount: BigDecimal): String {
        val whole = amount.setScale(0, java.math.RoundingMode.HALF_UP)
        val digits = whole.abs().toPlainString()
        val grouped = if (digits.length <= 3) digits else {
            val head = digits.dropLast(3).reversed().chunked(2).joinToString(",").reversed()
            "$head,${digits.takeLast(3)}"
        }
        return (if (whole.signum() < 0) "-" else "") + "₹" + grouped
    }
}
