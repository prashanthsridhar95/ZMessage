package com.zyke.message.domain.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Subscriptions the person says they cancelled, by [com.zyke.message.domain.insights.Subscription.key], with the day they said so. */
interface CancelledSubscriptionRepository {
    val cancelled: StateFlow<Map<String, LocalDate>>

    fun markCancelled(key: String, on: LocalDate)

    /** Takes the mark back (undo, or the person still uses it). */
    fun clear(key: String)
}

object NoCancelledSubscriptions : CancelledSubscriptionRepository {
    override val cancelled: StateFlow<Map<String, LocalDate>> = MutableStateFlow(emptyMap())
    override fun markCancelled(key: String, on: LocalDate) = Unit
    override fun clear(key: String) = Unit
}
