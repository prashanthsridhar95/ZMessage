package com.zyke.message.domain.repository

import java.time.LocalDate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Refunds the person says arrived, by [com.zyke.message.domain.insights.RefundItem.key], with the day they said so.
 * For the refunds no bank credit could be matched to.
 */
interface ReceivedRefundRepository {
    val received: StateFlow<Map<String, LocalDate>>

    fun markReceived(key: String, on: LocalDate)

    /** Takes the mark back (undo, or it had not arrived after all). */
    fun clear(key: String)
}

object NoReceivedRefunds : ReceivedRefundRepository {
    override val received: StateFlow<Map<String, LocalDate>> = MutableStateFlow(emptyMap())
    override fun markReceived(key: String, on: LocalDate) = Unit
    override fun clear(key: String) = Unit
}
