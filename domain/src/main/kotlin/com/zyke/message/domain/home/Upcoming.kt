package com.zyke.message.domain.home

import com.zyke.message.domain.dashboard.CardBillsBuilder
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import java.math.BigDecimal
import java.time.LocalDate

enum class UpcomingKind { CARD_BILL, AUTO_DEBIT }

/** Money that is about to leave: a card bill falling due or an announced auto-debit. */
data class UpcomingItem(
    val key: String,
    val kind: UpcomingKind,
    val title: String,
    val amount: BigDecimal,
    val date: LocalDate,
    val message: ClassifiedMessage,
    val paymentLink: String? = null
)

object UpcomingBuilder {
    private const val HORIZON_DAYS = 30L

    /** Soonest first, within the next 30 days, today included. */
    fun build(messages: List<ClassifiedMessage>, today: LocalDate): List<UpcomingItem> {
        val horizon = today.plusDays(HORIZON_DAYS)
        val byId = messages.associateBy { it.id }

        val bills = CardBillsBuilder.build(messages, today).mapNotNull { card ->
            val due = card.latest.dueDate ?: return@mapNotNull null
            val message = byId[card.latest.messageId] ?: return@mapNotNull null
            UpcomingItem(
                key = "bill-${card.key}",
                kind = UpcomingKind.CARD_BILL,
                title = card.label,
                amount = card.latest.amount,
                date = due,
                message = message,
                paymentLink = card.latest.paymentLink
            )
        }

        // The bank can send the same alert more than once; keep one per payee, date and amount.
        val autoDebits = messages.mapNotNull { message ->
            val scheduled = message.content as? MessageContent.ScheduledPayment ?: return@mapNotNull null
            if (scheduled.kind != ScheduledKind.UPCOMING_DEBIT) return@mapNotNull null
            val date = scheduled.date ?: return@mapNotNull null
            val amount = scheduled.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            UpcomingItem(
                key = "debit-${scheduled.payee.lowercase()}-$date-$amount",
                kind = UpcomingKind.AUTO_DEBIT,
                title = scheduled.payee.ifEmpty { "Auto-debit" },
                amount = amount,
                date = date,
                message = message
            )
        }.distinctBy { it.key }

        return (bills + autoDebits)
            .filter { !it.date.isBefore(today) && !it.date.isAfter(horizon) }
            .sortedBy { it.date }
    }
}
