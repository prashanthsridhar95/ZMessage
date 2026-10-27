package com.zyke.message.presentation.format

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import java.time.format.DateTimeFormatter
import java.util.Locale

data class DetailFact(val label: String, val value: String)

/** The numbers and names we understood in a message, as label / value rows for its detail sheet. */
fun ClassifiedMessage.detailFacts(locale: Locale = Locale.getDefault()): List<DetailFact> {
    val dates = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    return when (val c = content) {
        is MessageContent.Transaction -> transactionFacts(c)
        is MessageContent.CardBill -> billFacts(c, dates)
        is MessageContent.ScheduledPayment -> scheduledFacts(c, dates)
        is MessageContent.Otp -> otpFacts(c)
        is MessageContent.FastTag -> fastTagFacts(c.event)
        is MessageContent.DataUsage -> listOf(DetailFact("Carrier", c.carrier.displayName), DetailFact("Used", "${c.percentUsed}%"))
        is MessageContent.Courier -> listOf(DetailFact("Status", courierStatus(c.status)))
        is MessageContent.Loyalty -> listOf(
            DetailFact("Programme", merchant.displayName),
            DetailFact(if (c.isLoaded) "Earned" else "Used", "${c.points} points"),
            DetailFact("Balance", c.balance)
        )
        is MessageContent.Notice -> listOfNotNull(
            DetailFact("Type", c.kind.label),
            c.amount?.let { DetailFact("Amount", DisplayFormatters.rupees(it)) }
        )
        MessageContent.Generic -> emptyList()
    }
}

private fun ClassifiedMessage.transactionFacts(c: MessageContent.Transaction) = listOfNotNull(
    DetailFact(if (c.isReceived) "Received" else "Paid", DisplayFormatters.rupees(c.amount)),
    c.payee.takeIf { it.isNotEmpty() }?.let { DetailFact(if (c.isReceived) "From" else "To", merchant.displayName) },
    c.accountNumber.takeIf { it.isNotEmpty() }?.let { DetailFact(if (c.isCard) "Card" else "Account", "•• $it") },
    c.bankName.displayString.takeIf { it.isNotEmpty() }?.let { DetailFact("Bank", it) }
)

private fun billFacts(c: MessageContent.CardBill, dates: DateTimeFormatter) = listOfNotNull(
    DetailFact("Amount due", DisplayFormatters.rupees(c.amount)),
    c.minimumDue?.let { DetailFact("Minimum due", DisplayFormatters.rupees(it)) },
    c.dueDate?.let { DetailFact("Due date", dates.format(it)) },
    c.accountNumber.takeIf { it.isNotEmpty() }?.let { DetailFact("Card", "${c.bankName.displayString} •• $it".trim()) }
)

private fun scheduledFacts(c: MessageContent.ScheduledPayment, dates: DateTimeFormatter) = listOfNotNull(
    DetailFact(
        when (c.kind) {
            ScheduledKind.UPCOMING_DEBIT -> "Will be debited"
            ScheduledKind.MANDATE_CANCELLED -> "Cancelled autopay of"
            ScheduledKind.MANDATE_REQUEST -> "Requested"
            ScheduledKind.MANDATE_SET -> "Autopay of"
        },
        DisplayFormatters.rupees(c.amount)
    ),
    c.payee.takeIf { it.isNotEmpty() }?.let { DetailFact("For", it) },
    c.date?.let { DetailFact("On", dates.format(it)) },
    c.accountNumber.takeIf { it.isNotEmpty() }?.let { DetailFact(if (c.isCard) "Card" else "Account", "•• $it") }
)

private fun otpFacts(c: MessageContent.Otp) = listOfNotNull(
    c.code.takeIf { it.isNotEmpty() }?.let { DetailFact("Code", it) },
    DetailFact("From", c.senderName),
    c.paymentAmount?.let { DetailFact("For payment of", DisplayFormatters.rupees(it)) },
    c.cardNumber?.let { DetailFact("Card", "•• $it") }
)

private fun fastTagFacts(event: FastTagEvent) = when (event) {
    is FastTagEvent.TollPaid -> listOfNotNull(
        event.tollName.takeIf { it.isNotEmpty() }?.let { DetailFact("Toll plaza", it) },
        DetailFact("Vehicle", event.vehicleNumber),
        DetailFact("Toll", DisplayFormatters.rupees(event.tollAmount)),
        DetailFact("Balance", DisplayFormatters.rupees(event.balance))
    )
    is FastTagEvent.ToppedUp -> listOf(
        DetailFact("Vehicle", event.vehicleNumber),
        DetailFact("Added", DisplayFormatters.rupees(event.amount)),
        DetailFact("Balance", DisplayFormatters.rupees(event.balance))
    )
    is FastTagEvent.RechargeNeeded -> listOf(DetailFact("Vehicle", event.vehicleNumber), DetailFact("Status", "Balance low"))
    is FastTagEvent.Activated -> listOf(DetailFact("Vehicle", event.vehicleNumber), DetailFact("Status", "Active"))
}

private fun courierStatus(status: CourierStatus) = when (status) {
    CourierStatus.ARRIVING_TODAY -> "Arriving today"
    CourierStatus.DELIVERED -> "Delivered"
    CourierStatus.UPDATE -> "Update"
}
