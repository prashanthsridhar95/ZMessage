package com.zyke.message.domain.home

import com.zyke.message.domain.util.toBigDecimalOrNull
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import com.zyke.message.domain.util.BodyMemo

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

    /** "Credit Card XX9999", "CreditCard Payment XX 9999": a card the message names by its digits. */
    private val CARD_DIGITS = Regex("(?i)card\\s*(?:no\\.?\\s*|payment\\s*)?[x*•]*\\s*(\\d{4})\\b")

    private val WHITESPACE = Regex("[\\s\\u00a0]+")

    /** What one message body says about a bill payment; worked out once per body, not once per bill that is checked against it. */
    class Facts(raw: String) {
        val flat: String = WHITESPACE.replace(BodyMemo.bounded(raw), " ")
        val billWords = CARD_BILL_WORDS.containsMatchIn(flat)
        val aggregator = AGGREGATOR.containsMatchIn(flat)
        val paidWords = PAID_WORDS.containsMatchIn(flat)
        val notYet = NOT_YET.containsMatchIn(flat)
        val spend = SPEND.containsMatchIn(flat)
        val amounts: List<BigDecimal> by lazy {
            AMOUNT.findAll(flat).mapNotNull { it.groupValues[1].replace(",", "").trimEnd('.').toBigDecimalOrNull() }.toList()
        }
    }

    private val facts = BodyMemo { body -> Facts(body) }
    private val cardMentions = ConcurrentHashMap<String, Regex>()

    internal val factComputations: Long get() = facts.computations

    /** For tests: how many bodies have been read so far. */
    fun factComputationsForTest(): Long = facts.computations

    /** [messages] without card bills, oldest first; kept for the list last asked about, since one rebuild asks many times. */
    private class Ordered(source: List<ClassifiedMessage>) {
        val ref = java.lang.ref.WeakReference(source)
        val size = source.size
        val sorted: List<ClassifiedMessage> = source.filter { it.content !is MessageContent.CardBill }.sortedBy { it.message.date }
        val dates = LongArray(sorted.size) { sorted[it].message.date }
    }

    /** The few lists asked about most recently (a rebuild asks about two or three at once, from several threads). */
    private val recent = java.util.ArrayDeque<Ordered>()

    private fun orderedFor(messages: List<ClassifiedMessage>): Ordered {
        synchronized(recent) {
            recent.removeAll { it.ref.get() == null }
            recent.firstOrNull { it.ref.get() === messages && it.size == messages.size }?.let { return it }
        }
        val made = Ordered(messages)
        synchronized(recent) {
            recent.addFirst(made)
            while (recent.size > RECENT_LISTS) recent.removeLast()
        }
        return made
    }

    private const val RECENT_LISTS = 4

    /** The messages in [messages] dated after [after] that are not card bills, oldest first (ties in their given order). */
    private fun laterThan(messages: List<ClassifiedMessage>, after: Long): List<ClassifiedMessage> {
        val o = orderedFor(messages)
        // First index whose date is greater than [after].
        var lo = 0
        var hi = o.dates.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (o.dates[mid] > after) hi = mid else lo = mid + 1
        }
        return o.sorted.subList(lo, o.sorted.size)
    }

    private fun mentionsCard(flat: String, last4: String): Boolean {
        // Plain text search first: a message that does not hold the four digits cannot name the card.
        if (!flat.contains(last4)) return false
        return cardMentions.getOrPut(last4) {
            Regex("(?i)(?:[x*•]+\\s*|\\bcard\\s*(?:no\\.?\\s*)?|\\bending\\s*(?:in|with)?\\s*)$last4\\b")
        }.containsMatchIn(flat)
    }

    private fun bankNamed(bill: MessageContent.CardBill, body: String): Boolean {
        val name = bill.bankName.displayString.takeIf { it.isNotBlank() }?.substringBefore(' ') ?: return false
        return body.contains(name, ignoreCase = true)
    }

    /**
     * Every message after the bill that says money went towards it: it names the card's last digits, speaks of a payment
     * that happened (not one that will), and carries an amount from the minimum due up to the whole bill, oldest first.
     *
     * A bank account's note that money went "towards credit card payment", or to CRED or a bill-payment service, often
     * names no card at all. When it carries the whole bill's amount, to the rupee, that is taken as a payment of the bill
     * (a part payment still has to name the card or its bank).
     */
    fun payments(
        billMessage: ClassifiedMessage,
        bill: MessageContent.CardBill,
        messages: List<ClassifiedMessage>,
        zone: ZoneId
    ): List<BillPayment> {
        val total = bill.amount.toBigDecimalOrNull() ?: return emptyList()
        val minimum = bill.minimumDue?.toBigDecimalOrNull() ?: total.multiply(BigDecimal("0.05"))
        val last4 = bill.accountNumber.filter { it.isDigit() }.takeLast(4)
        if (last4.length < 4) return emptyList()
        val later = laterThan(messages, billMessage.message.date)
        return later.asSequence()
            .filter { it.id != billMessage.id }
            .mapNotNull { message ->
                val fact = facts.of(message.message)
                val body = fact.flat
                val tx = message.content as? MessageContent.Transaction
                val named = mentionsCard(body, last4) || (tx != null && tx.isCard && tx.accountNumber.endsWith(last4))
                // A note that names a different card by its digits (another card of the bank, or a debit paying another
                // bank's card) is not this bill.
                val otherCard = !named && CARD_DIGITS.findAll(body).any { it.groupValues[1] != last4 }
                val billWords = fact.billWords
                // A bank's note that a card bill was debited often leaves the card's digits out: the card's bank and the
                // words "card" and "bill" or "payment" then have to carry it.
                val onThisCard = named || (!otherCard && billWords && bankNamed(bill, body))
                val viaService = billWords || fact.aggregator || AGGREGATOR.containsMatchIn(tx?.payee.orEmpty())
                // Naming no card, a debit for a card bill can still be this bill when it is for the whole of it.
                val wholeBillOnly = !onThisCard && !otherCard && viaService && tx?.isReceived != true
                if ((!onThisCard && !wholeBillOnly) || !fact.paidWords || fact.notYet) return@mapNotNull null
                // Spending on the card is not paying it, however much it was.
                if (tx != null && tx.isCard && !tx.isReceived && tx.payee.isNotBlank() && !billWords || fact.spend && tx?.isReceived != true) return@mapNotNull null
                val amounts = fact.amounts
                val amount = (if (wholeBillOnly) amounts.firstOrNull { (it - total).abs() <= TOLERANCE }
                else amounts.firstOrNull { it >= minimum - TOLERANCE && it <= total + TOLERANCE }) ?: return@mapNotNull null
                BillPayment(Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), amount, message.id)
            }
            .toList()
    }

    private fun total(bill: MessageContent.CardBill): BigDecimal? = bill.amount.toBigDecimalOrNull()

    /** What has been paid towards the bill, counting one payment once however many messages told of it (same day, same amount). */
    private fun paidSum(payments: List<BillPayment>): BigDecimal =
        payments.distinctBy { it.date to it.amount }.fold(BigDecimal.ZERO) { sum, payment -> sum + payment.amount }

    /**
     * That the whole bill was paid, and when: one payment of all of it, or part payments that add up to it (to the rupee).
     * A part payment alone is not this; see [partial]. The amount is what was paid in all.
     */
    fun find(
        billMessage: ClassifiedMessage,
        bill: MessageContent.CardBill,
        messages: List<ClassifiedMessage>,
        zone: ZoneId
    ): BillPayment? {
        val total = total(bill) ?: return null
        val all = payments(billMessage, bill, messages, zone)
        if (all.isEmpty()) return null
        val paid = paidSum(all)
        if (paid < total - TOLERANCE) return null
        // The payment that made it whole is the last one; the day it was paid is that day.
        val last = all.maxByOrNull { it.date } ?: all.last()
        return BillPayment(last.date, paid, last.messageId)
    }

    /** Part of the bill was paid, not all of it: how much so far (and when the latest part went), else null. */
    fun partial(
        billMessage: ClassifiedMessage,
        bill: MessageContent.CardBill,
        messages: List<ClassifiedMessage>,
        zone: ZoneId
    ): BillPayment? {
        val total = total(bill) ?: return null
        val all = payments(billMessage, bill, messages, zone)
        if (all.isEmpty()) return null
        val paid = paidSum(all)
        if (paid >= total - TOLERANCE) return null
        val last = all.maxByOrNull { it.date } ?: all.last()
        return BillPayment(last.date, paid, last.messageId)
    }

    /** What has been paid towards the bill in all, whole or part; null when nothing has. */
    fun paidSoFar(
        billMessage: ClassifiedMessage,
        bill: MessageContent.CardBill,
        messages: List<ClassifiedMessage>,
        zone: ZoneId
    ): BigDecimal? = payments(billMessage, bill, messages, zone).takeIf { it.isNotEmpty() }?.let { paidSum(it) }
}
