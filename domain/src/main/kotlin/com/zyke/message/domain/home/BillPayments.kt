package com.zyke.message.domain.home

import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** That a card's bill was paid, and when. */
data class BillPayment(val date: LocalDate, val amount: BigDecimal, val messageId: Long)

object BillPayments {
    private val AMOUNT = Regex("(?i)(?:INR\\.?|Rs\\.?|₹)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val PAID_WORDS = Regex("(?i)\\b(?:payment|paid|debited|deducted|received|credited|settled|cleared|auto-?debit(?:ed)?)\\b")
    private val NOT_YET = Regex("(?i)\\b(?:will be|to be|scheduled|upcoming|reminder|due (?:on|by)|pay (?:before|by)|please pay|failed|declined|unsuccessful)\\b")
    private val SPEND = Regex("(?i)\\b(?:spent|purchase|txn|transaction of)\\b.*\\b(?:at|on|to)\\b")
    private val TOLERANCE = BigDecimal.ONE

    /**
     * The first message after the bill that says it was paid: it names the card's last digits, speaks of a payment that
     * happened (not one that will), and carries an amount from the minimum due up to the whole bill.
     */
    fun find(
        billMessage: ClassifiedMessage,
        bill: MessageContent.CardBill,
        messages: List<ClassifiedMessage>,
        zone: ZoneId
    ): BillPayment? {
        val total = bill.amount.toBigDecimalOrNull() ?: return null
        val minimum = bill.minimumDue?.toBigDecimalOrNull() ?: total.multiply(BigDecimal("0.05"))
        val last4 = bill.accountNumber.filter { it.isDigit() }.takeLast(4)
        if (last4.length < 4) return null
        val mentionsCard = Regex("(?i)(?:[x*•]+\\s*|\\bcard\\s*(?:no\\.?\\s*)?|\\bending\\s*(?:in|with)?\\s*)$last4\\b")

        return messages
            .filter { it.message.date > billMessage.message.date && it.id != billMessage.id && it.content !is MessageContent.CardBill }
            .sortedBy { it.message.date }
            .firstNotNullOfOrNull { message ->
                val body = message.message.body.replace(Regex("[\\s\\u00a0]+"), " ")
                val tx = message.content as? MessageContent.Transaction
                val onThisCard = mentionsCard.containsMatchIn(body) || (tx != null && tx.isCard && tx.accountNumber.endsWith(last4))
                if (!onThisCard || !PAID_WORDS.containsMatchIn(body) || NOT_YET.containsMatchIn(body)) return@firstNotNullOfOrNull null
                // Spending on the card is not paying it, however much it was.
                if (tx != null && tx.isCard && !tx.isReceived || SPEND.containsMatchIn(body) && tx?.isReceived != true) return@firstNotNullOfOrNull null
                val amount = AMOUNT.findAll(body).mapNotNull { it.groupValues[1].replace(",", "").trimEnd('.').toBigDecimalOrNull() }
                    .firstOrNull { it >= minimum - TOLERANCE && it <= total + TOLERANCE } ?: return@firstNotNullOfOrNull null
                BillPayment(Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), amount, message.id)
            }
    }
}
