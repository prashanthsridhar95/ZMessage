package com.zyke.message.domain.repository

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the person said about a payment that looks recurring. */
enum class RecurringState { CONFIRMED, IGNORED }

/** The person's answers about recurring payments, by [com.zyke.message.domain.insights.Subscription.key]. */
interface RecurringConfirmationRepository {
    val states: StateFlow<Map<String, RecurringState>>

    fun set(key: String, state: RecurringState)

    /** Forgets the answer, so the payment asks again. */
    fun clear(key: String)
}

object NoRecurringConfirmations : RecurringConfirmationRepository {
    override val states: StateFlow<Map<String, RecurringState>> = MutableStateFlow(emptyMap())
    override fun set(key: String, state: RecurringState) = Unit
    override fun clear(key: String) = Unit
}
