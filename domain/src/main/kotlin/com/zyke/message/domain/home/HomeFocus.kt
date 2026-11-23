package com.zyke.message.domain.home

import java.math.BigDecimal
import java.time.LocalDate

/** A trip happening today, as far as the choice needs it. [departsAtMillis] orders trips; the card itself is drawn elsewhere. */
data class FocusTrip(val key: String, val isTrain: Boolean, val departsAtMillis: Long)

/** A one-time code and when it arrived. */
data class FocusCode(val messageId: Long, val arrivedAtMillis: Long)

/** A bill that is not paid yet. [dueDate] before today means overdue. */
data class FocusBill(val key: String, val dueDate: LocalDate, val amount: BigDecimal)

/** What Home's TODAY card shows. Exactly one is chosen. */
sealed interface Focus {
    /** The trip to show first (the earliest departure). */
    data class Trip(val trip: FocusTrip) : Focus
    /** The newest code that has not expired; every fresh code is on the card. */
    data class Code(val newest: FocusCode, val count: Int) : Focus
    /** A bill due today or overdue. */
    data class BillDue(val bill: FocusBill, val daysLate: Int) : Focus
    /** A bill due within [HomeFocus.SOON_DAYS]. */
    data class BillSoon(val bill: FocusBill, val inDays: Int) : Focus
    data object Clear : Focus
}

/** The choice and how many other things were left out ("and 2 more"). */
data class FocusPick(val focus: Focus, val more: Int) {
    val hasMore: Boolean get() = more > 0
}

/**
 * Home shows one thing at a time. Priority: a trip today, then a fresh code, then a bill due today or overdue, then the next bill due
 * within [SOON_DAYS], then calm. Pure and clock-free: the caller passes `nowMillis` and `today`.
 *
 * "More" counts what the shown card does not already hold: with a trip on top, trips of the other kind, a fresh code (as one) and
 * every bill due soon; with a code, every bill due soon (the card holds all fresh codes); with a bill, the other bills due soon.
 */
object HomeFocus {
    const val SOON_DAYS = 3
    const val CODE_LIFE_MS = 10 * 60 * 1000L

    fun pick(
        nowMillis: Long,
        today: LocalDate,
        trips: List<FocusTrip> = emptyList(),
        codes: List<FocusCode> = emptyList(),
        bills: List<FocusBill> = emptyList()
    ): FocusPick {
        val fresh = codes.filter { nowMillis - it.arrivedAtMillis in 0 until CODE_LIFE_MS }
        val dueBills = bills.filter { !it.dueDate.isAfter(today.plusDays(SOON_DAYS.toLong())) }
            .sortedWith(compareBy<FocusBill> { it.dueDate }.thenByDescending { it.amount }.thenBy { it.key })
        val tripFirst = trips.sortedWith(compareBy<FocusTrip> { it.departsAtMillis }.thenBy { it.key }).firstOrNull()
        val newestCode = fresh.sortedWith(compareByDescending<FocusCode> { it.arrivedAtMillis }.thenByDescending { it.messageId }).firstOrNull()

        return when {
            tripFirst != null -> {
                val otherKind = trips.count { it.isTrain != tripFirst.isTrain }
                FocusPick(Focus.Trip(tripFirst), otherKind + (if (newestCode != null) 1 else 0) + dueBills.size)
            }
            newestCode != null -> FocusPick(Focus.Code(newestCode, fresh.size), dueBills.size)
            dueBills.isNotEmpty() -> {
                val top = dueBills.first()
                val days = java.time.temporal.ChronoUnit.DAYS.between(today, top.dueDate).toInt()
                val focus = if (days <= 0) Focus.BillDue(top, -days) else Focus.BillSoon(top, days)
                FocusPick(focus, dueBills.size - 1)
            }
            else -> FocusPick(Focus.Clear, 0)
        }
    }
}
