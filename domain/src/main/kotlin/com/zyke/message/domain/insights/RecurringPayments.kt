package com.zyke.message.domain.insights

import com.zyke.message.domain.repository.RecurringState
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/** A detected recurring payment together with what the person said about it ([state] null = not asked yet). */
data class RecurringItem(val subscription: Subscription, val state: RecurringState?) {
    val isConfirmed: Boolean get() = state == RecurringState.CONFIRMED

    /** "expected ~12 Nov" */
    val expectedLabel: String get() = RecurringPayments.expectedLabel(subscription.nextDue)
}

object RecurringPayments {
    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

    fun expectedLabel(date: LocalDate): String = "expected ~" + DAY_MONTH.format(date)

    /** Drops the ones the person said are not recurring, keeping the detected order (soonest due first). */
    fun arrange(subscriptions: List<Subscription>, states: Map<String, RecurringState>): List<RecurringItem> =
        subscriptions.map { RecurringItem(it, states[it.key]) }.filter { it.state != RecurringState.IGNORED }
}
