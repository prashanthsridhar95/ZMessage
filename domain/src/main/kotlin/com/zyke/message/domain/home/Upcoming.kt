package com.zyke.message.domain.home

import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

enum class UpcomingKind { CARD_BILL, AUTO_DEBIT }

/** Money that is about to leave: a card bill falling due or an announced auto-debit. */
data class UpcomingItem(
    val key: String,
    val kind: UpcomingKind,
    val title: String,
    val amount: BigDecimal,
    val date: LocalDate,
    val message: ClassifiedMessage,
    val paymentLink: String? = null,
    /** The day the bill was paid, for a bill that has been; such a bill is kept for that day only. */
    val paidOn: LocalDate? = null,
    val paidAmount: BigDecimal? = null
) {
    /** What "Mark as paid" stores, so a bill the person says they paid (outside the messages) stays gone. */
    val markedPaidKey: String get() = "paid-$key-$date"

    /** The key the same bill has in the attention strip, so marking it paid clears both. */
    val attentionKey: String?
        get() = (message.content as? MessageContent.CardBill)?.let { "bill-${it.bankName.name}-${it.accountNumber}-$date" }
}

object UpcomingBuilder {
    private const val HORIZON_DAYS = 30L

    /** Soonest first, within the next 30 days, today included. */
    fun build(
        messages: List<ClassifiedMessage>,
        today: LocalDate,
        /** Keep a bill that was paid today, marked as paid, so the day shows it done; it goes tomorrow. */
        includePaidToday: Boolean = false,
        zone: ZoneId = ZoneId.systemDefault(),
        /** Keys of bills the person marked as paid themselves; see [UpcomingItem.markedPaidKey]. */
        markedPaid: Set<String> = emptySet()
    ): List<UpcomingItem> {
        val horizon = today.plusDays(HORIZON_DAYS)
        val byId = messages.associateBy { it.id }

        val bills = CardBillsBuilder.build(messages, today).mapNotNull { card ->
            val due = card.latest.dueDate ?: return@mapNotNull null
            val message = byId[card.latest.messageId] ?: return@mapNotNull null
            val paid = (message.content as? MessageContent.CardBill)?.let { BillPayments.find(message, it, messages, zone) }
            // Paid before today: done and gone. Paid today: shown as paid, for today only.
            if (paid != null && !(includePaidToday && paid.date == today)) return@mapNotNull null
            UpcomingItem(
                key = "bill-${card.key}",
                kind = UpcomingKind.CARD_BILL,
                title = card.label,
                amount = card.latest.amount,
                date = due,
                message = message,
                paymentLink = card.latest.paymentLink.takeIf { paid == null },
                paidOn = paid?.date,
                paidAmount = paid?.amount
            )
        }

        // The bank can send the same alert more than once; keep one per payee, date and amount.
        val autoDebits = messages.mapNotNull { message ->
            val scheduled = message.content as? MessageContent.ScheduledPayment ?: return@mapNotNull null
            if (scheduled.kind != ScheduledKind.UPCOMING_DEBIT) return@mapNotNull null
            val date = scheduled.date ?: return@mapNotNull null
            val amount = scheduled.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            val paid = DebitPayments.find(message, scheduled.payee, amount, date, messages, zone)
            if (paid != null && !(includePaidToday && paid.date == today)) return@mapNotNull null
            UpcomingItem(
                key = "debit-${scheduled.payee.lowercase()}-$date-$amount",
                kind = UpcomingKind.AUTO_DEBIT,
                title = scheduled.payee.ifEmpty { "Auto-debit" },
                amount = amount,
                date = date,
                message = message,
                paidOn = paid?.date,
                paidAmount = paid?.amount
            )
        }.distinctBy { it.key }

        return (bills + autoDebits)
            .filter { it.markedPaidKey !in markedPaid }
            .filter { it.paidOn != null || (!it.date.isBefore(today) && !it.date.isAfter(horizon)) }
            .sortedBy { it.date }
    }
}
