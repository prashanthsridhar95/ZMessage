package com.zyke.message.domain.model

import com.zyke.message.domain.life.LifeKind
import java.time.LocalDate
import java.time.LocalTime

/** An [SmsMessage] together with everything the UI needs to render it. */
data class ClassifiedMessage(
    val message: SmsMessage,
    val category: MessageCategory,
    val merchant: Merchant,
    val content: MessageContent,
    /** Marketing noise; only ever set on [MessageContent.Generic] messages. */
    val isPromotional: Boolean = false,
    /** True when [category] was chosen by the user for this sender / receiver rather than guessed. */
    val categoryOverridden: Boolean = false,
    /** The person's card this message is about, if they have told us about it. */
    val cardId: String? = null,
    /** What the on-device model thinks an uncategorised sender / receiver belongs under; never applied on its own. */
    val suggestedCategory: MessageCategory? = null,
    /** The amount and payee were read by the on-device model, then checked against the text. */
    val isModelParsed: Boolean = false,
    /** The person muted this sender; it is treated like marketing, kept out of the main views and the unread badge. */
    val isMuted: Boolean = false
) : java.io.Serializable {
    val id: Long get() = message.id
}

/** Serializable so a classified message can be kept in the encrypted on-device cache. */
sealed interface MessageContent : java.io.Serializable {

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

    /** An informational message that mentions money without being a payment (failed autopay, IPO allotment, ...). */
    data class Notice(val kind: NoticeKind, val amount: String?) : MessageContent

    /** An order, trip, appointment or renewal read from a non-money message. */
    data class Life(
        val kind: LifeKind,
        val title: String,
        val status: String,
        val date: LocalDate?,
        val time: LocalTime?,
        val reference: String?,
        val isDone: Boolean
    ) : MessageContent

    data object Generic : MessageContent
}

enum class NoticeKind(val label: String) {
    AUTOPAY_FAILED("Autopay failed"),
    REWARD_REDEMPTION("Rewards to redeem"),
    IPO_ALLOTMENT("IPO allotment"),
    TAX_DEDUCTED("Tax deducted"),
    BROKER_BALANCE("Broker balance"),
    INSURANCE_CLAIM("Claim approved"),
    PAYMENT_REQUEST("Payment request"),
    FASTAG_PASS("FASTag annual pass"),
    MANDATE_MODIFIED("Autopay changed"),
    FUNDS_UNBLOCKED("Funds unblocked"),
    LIMIT_CHANGE("Limit changed"),
    DUES_REMINDER("Dues reminder"),
    ADVISORY("Advisory")
}

enum class ScheduledKind { UPCOMING_DEBIT, MANDATE_SET, MANDATE_CANCELLED, MANDATE_REQUEST }

enum class CourierStatus { ARRIVING_TODAY, DELIVERED, UPDATE }
