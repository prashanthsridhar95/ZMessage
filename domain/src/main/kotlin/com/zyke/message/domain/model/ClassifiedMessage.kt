package com.zyke.message.domain.model

import java.time.LocalDate

/** An [SmsMessage] together with everything the UI needs to render it. */
data class ClassifiedMessage(
    val message: SmsMessage,
    val category: MessageCategory,
    val merchant: Merchant,
    val content: MessageContent,
    /** Marketing noise; only ever set on [MessageContent.Generic] messages. */
    val isPromotional: Boolean = false
) {
    val id: Long get() = message.id
}

sealed interface MessageContent {

    data class Otp(
        val code: String,
        val senderName: String,
        val paymentAmount: String?,
        val cardNumber: String?
    ) : MessageContent

    data class FastTag(val event: FastTagEvent) : MessageContent

    data class Transaction(
        val bankName: BankName,
        val payee: String,
        val amount: String,
        val accountNumber: String,
        val isReceived: Boolean,
        val isCard: Boolean
    ) : MessageContent

    data class CardBill(
        val bankName: BankName,
        val accountNumber: String,
        val amount: String,
        val minimumDue: String? = null,
        val dueDate: LocalDate? = null,
        val paymentLink: String? = null
    ) : MessageContent

    /** An auto-debit that is about to happen, or a mandate that was set up, cancelled or asked for. Not yet money spent. */
    data class ScheduledPayment(
        val kind: ScheduledKind,
        val bankName: BankName,
        val accountNumber: String,
        val payee: String,
        val amount: String,
        val date: LocalDate?,
        val isCard: Boolean
    ) : MessageContent

    data class DataUsage(val carrier: NetworkCarrier, val percentUsed: Int) : MessageContent

    data class Courier(val status: CourierStatus) : MessageContent

    data class Loyalty(
        val programme: String,
        val points: String,
        val balance: String,
        val isLoaded: Boolean
    ) : MessageContent

    data object Generic : MessageContent
}

enum class ScheduledKind { UPCOMING_DEBIT, MANDATE_SET, MANDATE_CANCELLED, MANDATE_REQUEST }

enum class CourierStatus { ARRIVING_TODAY, DELIVERED, UPDATE }
