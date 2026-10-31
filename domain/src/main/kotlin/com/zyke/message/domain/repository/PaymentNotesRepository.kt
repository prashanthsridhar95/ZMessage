package com.zyke.message.domain.repository

import com.zyke.message.domain.notes.PaymentNotes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Notes and tags the person put on single payments, by [com.zyke.message.domain.categories.CategoryKey.messageKey]. */
interface PaymentNotesRepository {
    val notes: StateFlow<Map<String, PaymentNotes>>

    /** An empty [value] (or null) removes the entry. */
    fun set(key: String, value: PaymentNotes?)
}

object NoPaymentNotes : PaymentNotesRepository {
    override val notes: StateFlow<Map<String, PaymentNotes>> = MutableStateFlow(emptyMap())
    override fun set(key: String, value: PaymentNotes?) = Unit
}
