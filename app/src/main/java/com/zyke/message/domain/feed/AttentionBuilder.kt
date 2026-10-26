package com.zyke.message.domain.feed

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.FastTagEvent
import com.zyke.message.domain.model.MessageContent
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Picks the few messages that need action right now. */
object AttentionBuilder {
    private const val MAX_ITEMS = 3
    private val BILL_WITHOUT_DUE_DATE_WINDOW: Duration = Duration.ofDays(30)
    private val BILL_MAX_AGE: Duration = Duration.ofDays(60)

    /** @param messages newest first */
    fun build(
        messages: List<ClassifiedMessage>,
        dismissed: Set<String>,
        now: Instant,
        zone: ZoneId
    ): List<AttentionItem> {
        val today = LocalDate.ofInstant(now, zone)
        val items = listOfNotNull(freshOtp(messages, now)) + bills(messages, now, today) + lowFastTags(messages)
        return items.filter { it.key !in dismissed }.take(MAX_ITEMS)
    }

    private fun freshOtp(messages: List<ClassifiedMessage>, now: Instant): AttentionItem? {
        val newest = messages.firstOrNull { it.content is MessageContent.Otp } ?: return null
        val otp = newest.content as MessageContent.Otp
        if (otp.code.isEmpty() || OtpPolicy.isExpired(newest, now)) return null
        return AttentionItem.FreshOtp(newest, otp.code, otp.senderName)
    }

    private fun bills(messages: List<ClassifiedMessage>, now: Instant, today: LocalDate): List<AttentionItem> =
        messages
            .filter { it.content is MessageContent.CardBill }
            .distinctBy {
                val bill = it.content as MessageContent.CardBill
                bill.bankName to bill.accountNumber
            }
            .filter { isStillRelevant(it, it.content as MessageContent.CardBill, now, today) }
            .map {
                val bill = it.content as MessageContent.CardBill
                val bank = bill.bankName
                val label = "${bank.displayString}${if (bank.isCard) " Card" else ""}" +
                    if (bill.accountNumber.isNotEmpty()) " •• ${bill.accountNumber}" else ""
                AttentionItem.BillDue(it, label.trim(), bill.amount, bill.dueDate, bill.paymentLink)
            }
            .sortedBy { it.dueDate ?: LocalDate.MAX }

    private fun isStillRelevant(
        message: ClassifiedMessage,
        bill: MessageContent.CardBill,
        now: Instant,
        today: LocalDate
    ): Boolean {
        val age = Duration.between(Instant.ofEpochMilli(message.message.date), now)
        return when {
            age > BILL_MAX_AGE -> false
            bill.dueDate != null -> !bill.dueDate.isBefore(today)
            else -> age <= BILL_WITHOUT_DUE_DATE_WINDOW
        }
    }

    /** A vehicle needs a top-up if its newest FASTag message says so. */
    private fun lowFastTags(messages: List<ClassifiedMessage>): List<AttentionItem> =
        messages
            .filter { it.content is MessageContent.FastTag }
            .distinctBy { (it.content as MessageContent.FastTag).event.vehicleNumber }
            .mapNotNull {
                val event = (it.content as MessageContent.FastTag).event
                if (event is FastTagEvent.RechargeNeeded) AttentionItem.LowFastTag(it, event.vehicleNumber) else null
            }
}
