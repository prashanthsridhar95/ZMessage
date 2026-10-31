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
    /** Services people pay card bills through; a payment to one of these, for the whole bill, is that bill being paid. */
    private val AGGREGATOR = Regex("(?i)\\bcred(?:\\.?club)?\\b|\\bbbps\\b|\\bbill\\s*desk\\b|\\bbharat\\s*bill\\b|\\bcc\\s*bill\\b")
    private val CARD_BILL_WORDS = Regex("(?i)\\b(?:credit\\s*card|card)\\b.*\\b(?:bill|dues?|outstanding|payment)\\b|\\b(?:bill|dues?|outstanding|payment)\\b.*\\b(?:credit\\s*card|card)\\b")

    private fun bankNamed(bill: MessageContent.CardBill, body: String): Boolean {
        val name = bill.bankName.displayString.takeIf { it.isNotBlank() }?.substringBefore(' ') ?: return false
        return body.contains(name, ignoreCase = true)
    }

    /**
     * The first message after the bill that says it was paid: it names the card's last digits, speaks of a payment that
     * happened (not one that will), and carries an amount from the minimum due up to the whole bill.
     *
     * A bank account's note that money went "towards credit card payment", or to CRED or a bill-payment service, often
     * names no card at all. When it carries the whole bill's amount, to the rupee, that is taken as the bill being paid
     * (a part payment still has to name the card or its bank).
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
                val onThisCard = mentionsCard.containsMatchIn(body) || (tx != null && tx.isCard && tx.accountNumber.endsWith(last4)) ||
                    // A bank's note that a card bill was debited often leaves the card's digits out: the card's bank and the
                    // words "card" and "bill" or "payment" then have to carry it.
                    (CARD_BILL_WORDS.containsMatchIn(body) && bankNamed(bill, body))
                val billWords = CARD_BILL_WORDS.containsMatchIn(body)
                val viaService = billWords || AGGREGATOR.containsMatchIn(body) || AGGREGATOR.containsMatchIn(tx?.payee.orEmpty())
                // Naming no card, a debit for a card bill can still be this bill when it is for the whole of it.
                val wholeBillOnly = !onThisCard && viaService && tx?.isReceived != true
                if ((!onThisCard && !wholeBillOnly) || !PAID_WORDS.containsMatchIn(body) || NOT_YET.containsMatchIn(body)) return@firstNotNullOfOrNull null
                // Spending on the card is not paying it, however much it was.
                if (tx != null && tx.isCard && !tx.isReceived && tx.payee.isNotBlank() && !billWords || SPEND.containsMatchIn(body) && tx?.isReceived != true) return@firstNotNullOfOrNull null
                val amounts = AMOUNT.findAll(body).mapNotNull { it.groupValues[1].replace(",", "").trimEnd('.').toBigDecimalOrNull() }
                val amount = (if (wholeBillOnly) amounts.firstOrNull { (it - total).abs() <= TOLERANCE }
                else amounts.firstOrNull { it >= minimum - TOLERANCE && it <= total + TOLERANCE }) ?: return@firstNotNullOfOrNull null
                BillPayment(Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), amount, message.id)
            }
    }
}
