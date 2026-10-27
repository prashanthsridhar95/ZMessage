package com.zyke.message.presentation.format

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.CourierStatus
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.ScheduledKind
import java.time.LocalDate

/**
 * One sentence per message for screen readers, so TalkBack says "Paid ₹540 to Burmy Foods from
 * account ending 5765" instead of reading a logo, a name, a dot and an amount as separate items.
 */
fun ClassifiedMessage.spokenSummary(time: String, isUnread: Boolean, today: LocalDate = LocalDate.now()): String {
    val body = when (val c = content) {
        is MessageContent.Otp ->
            if (c.code.isEmpty()) "Message from ${c.senderName}"
            else "One-time password from ${c.senderName}: ${c.code.toList().joinToString(" ")}. Tap to copy"
        is MessageContent.Transaction -> {
            val who = merchant.displayName.ifEmpty { c.bankName.displayString }
            val amount = DisplayFormatters.rupees(c.amount)
            val verb = if (c.isReceived) "Received $amount from $who" else "Paid $amount to $who"
            verb + account(c.accountNumber, c.isCard)
        }
        is MessageContent.CardBill -> {
            val due = c.dueDate?.let { ", ${DisplayFormatters.dueLabel(it, today).replaceFirst("Due", "due")}" }.orEmpty()
            "${c.bankName.displayString} card bill ${DisplayFormatters.rupees(c.amount)}$due" + account(c.accountNumber, true)
        }
        is MessageContent.ScheduledPayment -> when (c.kind) {
            ScheduledKind.UPCOMING_DEBIT -> {
                val on = c.date?.let { ", ${DisplayFormatters.dueLabel(it, today).replaceFirst("Due", "due")}" }.orEmpty()
                "Upcoming auto-debit of ${DisplayFormatters.rupees(c.amount)} for ${c.payee.ifEmpty { "a subscription" }}$on"
            }
            ScheduledKind.MANDATE_CANCELLED -> "Autopay cancelled for ${c.payee.ifEmpty { "a subscription" }}, ${DisplayFormatters.rupees(c.amount)}"
            ScheduledKind.MANDATE_REQUEST -> "Autopay request of ${DisplayFormatters.rupees(c.amount)} from ${c.payee.ifEmpty { "a merchant" }}. Needs your approval"
            ScheduledKind.MANDATE_SET -> "Autopay set up for ${c.payee.ifEmpty { "a subscription" }}, ${DisplayFormatters.rupees(c.amount)}"
        }
        is MessageContent.FastTag -> when (val e = c.event) {
            is FastTagEvent.TollPaid -> "Toll paid ${DisplayFormatters.rupees(e.tollAmount)} for vehicle ${e.vehicleNumber}"
            is FastTagEvent.ToppedUp -> "FASTag topped up ${DisplayFormatters.rupees(e.amount)} for vehicle ${e.vehicleNumber}"
            is FastTagEvent.RechargeNeeded -> "FASTag balance low for vehicle ${e.vehicleNumber}. Recharge now"
            is FastTagEvent.Activated -> "FASTag active for vehicle ${e.vehicleNumber}"
        }
        is MessageContent.DataUsage -> "${c.carrier.displayName} data usage alert: ${c.percentUsed} percent used"
        is MessageContent.Courier -> "Courier from ${message.sender}: " + when (c.status) {
            CourierStatus.ARRIVING_TODAY -> "arriving today"
            CourierStatus.DELIVERED -> "delivered"
            CourierStatus.UPDATE -> "update"
        }
        is MessageContent.Loyalty ->
            "${merchant.displayName}: ${if (c.isLoaded) "earned" else "used"} ${c.points} points, balance ${c.balance}"
        MessageContent.Generic -> "Message from ${message.sender}: ${message.body.replace(Regex("\\s+"), " ").take(160)}"
    }
    return listOfNotNull(if (isUnread) "Unread" else null, body, time).joinToString(". ")
}

private fun account(number: String, isCard: Boolean): String =
    if (number.isEmpty()) "" else ", ${if (isCard) "card" else "account"} ending $number"
