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

    /**
     * Drops the ones the person said are not recurring, and the ones they marked as cancelled ([cancelled] holds their
     * keys), keeping the detected order (soonest due first). So a cancelled subscription is no longer an upcoming payment.
     */
    fun arrange(
        subscriptions: List<Subscription>,
        states: Map<String, RecurringState>,
        cancelled: Set<String> = emptySet()
    ): List<RecurringItem> =
        subscriptions.map { RecurringItem(it, states[it.key]) }
            .filter { it.state != RecurringState.IGNORED && it.subscription.key !in cancelled }
}
