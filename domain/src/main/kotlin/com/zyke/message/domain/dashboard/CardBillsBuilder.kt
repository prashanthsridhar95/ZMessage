package com.zyke.message.domain.dashboard

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.time.LocalDate

/** Groups card statements by card. */
object CardBillsBuilder {
    private const val MAX_EARLIER = 6

    /**
     * Cards with a statement still to be paid come first (soonest due date), then the rest
     * by how recent their statement is.
     */
    fun build(messages: List<ClassifiedMessage>, today: LocalDate): List<CardBills> =
        messages.mapNotNull { message ->
            val bill = message.content as? MessageContent.CardBill ?: return@mapNotNull null
            val amount = bill.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return@mapNotNull null
            Triple(
                bill,
                "${bill.bankName.name}|${bill.accountNumber}",
                BillEntry(
                    messageId = message.id,
                    epochMillis = message.message.date,
                    amount = amount,
                    minimumDue = bill.minimumDue?.toBigDecimalOrNull(),
                    dueDate = bill.dueDate,
                    paymentLink = bill.paymentLink
                )
            )
        }
            .groupBy({ it.second }, { it })
            .map { (key, items) ->
                val entries = items.map { it.third }.sortedByDescending { it.epochMillis }
                val bill = items.first().first
                CardBills(
                    key = key,
                    bankName = bill.bankName,
                    accountNumber = bill.accountNumber,
                    label = label(bill),
                    latest = entries.first(),
                    earlier = entries.drop(1).take(MAX_EARLIER)
                )
            }
            .sortedWith(
                compareBy<CardBills> { cards -> cards.latest.dueDate?.takeIf { !it.isBefore(today) } ?: LocalDate.MAX }
                    .thenByDescending { it.latest.epochMillis }
            )

    private fun label(bill: MessageContent.CardBill): String {
        val bank = bill.bankName
        val base = "${bank.displayString}${if (bank.isCard) " Card" else ""}".trim()
        return if (bill.accountNumber.isNotEmpty()) "$base •• ${bill.accountNumber}" else base
    }
}
