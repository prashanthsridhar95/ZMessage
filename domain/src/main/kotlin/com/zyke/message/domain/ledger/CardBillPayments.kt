package com.zyke.message.domain.ledger

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.home.BillPayments
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.util.BodyMemo
import com.zyke.message.domain.util.toBigDecimalOrNull
import java.math.BigDecimal
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the debits from a bank account that paid a credit card's bill. A card swipe is counted as spending on the day it
 * is made, so the payment of the bill afterwards must not be counted again (the bank balance still falls by it).
 *
 * Layers, strongest first (a debit is [CardBillKind.CERTAIN] when any of L1 to L3 holds):
 * - L1 wording: the payee or narration speaks of a credit card's bill ("credit card", "CC bill", "card payment", a card
 *   issuer's "... card" collection name, "ACH D- ... CREDIT CARD").
 * - L2 a card bill: the amount is the bill's total or minimum, or a part payment to a service/card payee, within the
 *   bill date and ten days after its due date; or BillPayments already takes it for that bill's payment.
 * - L3 pairing: a card shows "payment received" for the same amount within three days.
 * - L4 service: CRED, BillDesk, BBPS, PhonePe ... with no other evidence is only [CardBillKind.SUSPECTED]: it stays
 *   spending and is raised in Needs review. So is a payee filed under credit cards with no card words.
 * What the person answered always wins (see [CardBillDecisions]).
 */
object CardBillPayments {
    private const val DAY_MS = 24L * 60 * 60 * 1000
    private val ONE = BigDecimal.ONE
    private const val PAIR_WITHIN_MS = 3 * DAY_MS
    private const val AFTER_DUE_MS = 10 * DAY_MS
    private const val NO_DUE_MS = 40 * DAY_MS
    private const val MAX_BILLS = 36
    private const val MAX_CACHED = 20_000

    private val WORDING = Regex(
        "(?i)\\bcredit\\s*card\\b|\\bcreditcard\\b|\\bcc\\s*(?:bill|payment|pay|dues?)\\b|\\bccpay\\b|\\bsbi\\s*card\\b|" +
            "\\bamazon\\s*pay\\s*icici\\b|\\bone\\s*card\\b|\\bonecard\\b|(?<!debit )\\bcard\\s*(?:bill|payment|dues?|outstanding)\\b"
    )
    private val ISSUER_CARD = Regex(
        "(?i)\\b(?:hdfc|icici|sbi|axis|kotak|amex|american express|idfc|yes|rbl|indusind|au|hsbc|citi|standard chartered|federal|slice)\\b[^\\n]{0,24}\\bcard\\b"
    )
    private val DEBIT_CARD = Regex("(?i)\\bdebit\\s*card\\b")
    private val AGGREGATOR = Regex(
        "(?i)\\bcred(?:\\.?club)?\\b|\\bbbps\\b|\\bbill\\s*desk\\b|\\bbilldesk\\b|\\bbharat\\s*bill\\w*\\b|\\bpaytm\\b|\\bphone\\s*pe\\b|\\bphonepe\\b|" +
            "\\bamazon\\s*pay\\b|\\bgoogle\\s*pay\\b|\\bgpay\\b|\\brazorpay\\b|\\bpayu\\b|\\bcheq\\b"
    )
    private val NOT_A_CARD = Regex("(?i)\\b(?:loan|emi|insurance|mutual fund|sip)\\b")
    private val SWIPE = Regex("(?i)\\b(?:spent|purchase|swiped|pos|used (?:at|for|on))\\b")
    private val ACCOUNT = Regex("(?i)\\ba/?c\\b|\\bacct\\b|\\baccount\\b")
    private val PAYMENT = Regex("(?i)\\bpayment\\b|\\bthank you for\\b|\\btowards\\b|\\bpaid\\b")
    private val CARD_DIGITS = Regex("(?i)card\\s*(?:no\\.?\\s*|payment\\s*)?[x*•]*\\s*(\\d{4})\\b")
    private val SPACES = Regex("[\\s\\u00a0]+")

    private class Facts(raw: String) {
        val flat: String = SPACES.replace(BodyMemo.bounded(raw), " ")
        val wording = WORDING.containsMatchIn(flat) && (!DEBIT_CARD.containsMatchIn(flat) || flat.contains("credit", ignoreCase = true))
        val aggregator = AGGREGATOR.containsMatchIn(flat)
        val swipe = SWIPE.containsMatchIn(flat)
        val account = ACCOUNT.containsMatchIn(flat)
        val payment = PAYMENT.containsMatchIn(flat)
        val notACard = NOT_A_CARD.containsMatchIn(flat)
        val digits: String? = CARD_DIGITS.find(flat)?.groupValues?.get(1)
    }

    private class PayeeFacts(payee: String) {
        val wording = WORDING.containsMatchIn(payee) || (ISSUER_CARD.containsMatchIn(payee) && !DEBIT_CARD.containsMatchIn(payee))
        val aggregator = AGGREGATOR.containsMatchIn(payee)
        val notACard = NOT_A_CARD.containsMatchIn(payee)
    }

    private val facts = BodyMemo { body -> Facts(body) }
    private val payeeFacts = ConcurrentHashMap<String, PayeeFacts>()

    private fun payeeFacts(payee: String): PayeeFacts {
        payeeFacts[payee]?.let { return it }
        val made = PayeeFacts(payee)
        if (payeeFacts.size >= MAX_CACHED) payeeFacts.clear()
        payeeFacts[payee] = made
        return made
    }

    /** Payees of these kinds are people and shops-with-no-category: a payment to one that equals a card bill is the bill. */
    private val NEUTRAL = setOf(MessageCategory.OTHERS, MessageCategory.UPI_PAYMENT, MessageCategory.CREDIT_CARDS, MessageCategory.PAY_LATER)

    /** True when [message] is a bank-side payment of a card bill that is kept out of the totals (and not a wallet top-up). */
    fun isCountedAsBillPayment(message: ClassifiedMessage): Boolean {
        val tx = message.content as? MessageContent.Transaction ?: return false
        if (tx.isReceived || !tx.isCardPayment || message.setAside != null) return false
        return InternalMoves.reason(tx) == null && InternalMoves.label(tx) == null
    }

    private class Candidate(val message: ClassifiedMessage, val tx: MessageContent.Transaction, val amount: BigDecimal) {
        val body: Facts = facts.of(message.message)
        val payee: PayeeFacts = payeeFacts(tx.payee)
        val wording get() = body.wording || payee.wording
        val aggregator get() = body.aggregator || payee.aggregator
        val neutral get() = message.category in NEUTRAL
        val notACard get() = body.notACard || payee.notACard
        val weak get() = message.category == MessageCategory.CREDIT_CARDS && !notACard
    }

    /** The debits (by message id) that paid, or may have paid, a card's bill. Debits the person answered are settled by their word. */
    fun detect(messages: List<ClassifiedMessage>, decisions: CardBillDecisions, zone: ZoneId): Map<Long, CardBillMatch> {
        val candidates = ArrayList<Candidate>()
        val credits = ArrayList<Candidate>()
        val bills = ArrayList<ClassifiedMessage>()
        for (m in messages) {
            val content = m.content
            if (content is MessageContent.CardBill) { bills += m; continue }
            val tx = content as? MessageContent.Transaction ?: continue
            if (m.setAside != null || ManualEntries.isManual(m)) continue
            val amount = tx.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
            if (tx.isReceived) {
                if (tx.isCard && tx.isCardPayment) credits += Candidate(m, tx, amount)
                continue
            }
            if (tx.isCardPayment) continue
            candidates += Candidate(m, tx, amount)
        }
        val unsettled = candidates.filter { c ->
            val key = CategoryKey.of(c.message)
            c.message.id !in decisions.yes && c.message.id !in decisions.no &&
                (key == null || (key !in decisions.payeesYes && key !in decisions.payeesNo))
        }.filter { c -> !c.tx.isCard || (c.body.account && !c.body.swipe) }

        val found = HashMap<Long, CardBillMatch>()
        for (c in candidates) {
            val key = CategoryKey.of(c.message)
            val id = c.message.id
            when {
                id in decisions.no -> Unit
                id in decisions.yes -> found[id] = match(c, "You said this paid your credit card bill", "you")
                key != null && key in decisions.payeesNo -> Unit
                key != null && key in decisions.payeesYes -> found[id] = match(c, "You said payments to ${c.tx.payee} pay your credit card bill", "you")
            }
        }
        if (unsettled.isEmpty()) return found

        // L1: the words of a card bill.
        for (c in unsettled) if (c.wording) found[c.message.id] = match(c, "The payment is for a credit card bill", "L1")

        // L2: a bill with this amount, due about now; or a payment BillPayments already reads as that bill's.
        val live = unsettled.filter { it.message.id !in found }
        if (live.isNotEmpty() && bills.isNotEmpty()) {
            val latest = bills.sortedByDescending { it.message.date }.take(MAX_BILLS)
            val byDate = live.sortedBy { it.message.message.date }
            val dates = LongArray(byDate.size) { byDate[it].message.message.date }
            for (billMessage in latest) {
                val bill = billMessage.content as MessageContent.CardBill
                val total = bill.amount.toBigDecimalOrNull() ?: continue
                val minimum = bill.minimumDue?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
                val last4 = bill.accountNumber.filter { it.isDigit() }.takeLast(4)
                val from = billMessage.message.date
                val to = (bill.dueDate?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.plus(AFTER_DUE_MS + DAY_MS)) ?: (from + NO_DUE_MS)
                var i = firstAfter(dates, from - 1)
                while (i < byDate.size && dates[i] <= to) {
                    val c = byDate[i++]
                    val id = c.message.id
                    if (found[id]?.kind == CardBillKind.CERTAIN) continue
                    val named = c.body.digits
                    if (named != null && last4.length == 4 && named != last4) continue
                    val cardHint = c.aggregator || c.weak
                    val matchesTotal = (c.amount - total).abs() <= ONE
                    val matchesMinimum = minimum != null && (c.amount - minimum).abs() <= ONE
                    val partial = minimum != null && c.amount > minimum && c.amount < total - ONE
                    when {
                        matchesTotal && (cardHint || c.neutral) -> found[id] = match(c, "The amount is your ${label(bill)} card bill", "L2", last4)
                        matchesMinimum && cardHint -> found[id] = match(c, "The amount is the minimum due on your ${label(bill)} card bill", "L2", last4)
                        matchesMinimum && c.neutral -> found.putIfAbsent(id, suspected(c, "The amount is the minimum due on your ${label(bill)} card bill", "L2", last4))
                        partial && cardHint && named == last4 -> found[id] = match(c, "A part payment of your ${label(bill)} card bill", "L2", last4)
                    }
                }
            }
            val billed = BillPayments.paymentMessageIds(messages, zone)
            for (c in live) if (c.message.id in billed && found[c.message.id]?.kind != CardBillKind.CERTAIN) {
                found[c.message.id] = match(c, "BillPayments reads it as a card bill payment", "L2", c.body.digits)
            }
        }

        // L3: the card says a payment arrived for the same amount, within three days of this debit.
        if (credits.isNotEmpty()) {
            val byAmount = HashMap<BigDecimal, MutableList<Candidate>>()
            for (cr in credits) byAmount.getOrPut(cr.amount.stripTrailingZeros()) { ArrayList() } += cr
            val used = HashSet<Long>()
            for (c in unsettled.sortedBy { it.message.message.date }) {
                if (found[c.message.id]?.kind == CardBillKind.CERTAIN || !(c.neutral || c.wording || c.aggregator)) continue
                val options = byAmount[c.amount.stripTrailingZeros()] ?: continue
                val near = options.filter {
                    it.message.id !in used && kotlin.math.abs(it.message.message.date - c.message.message.date) <= PAIR_WITHIN_MS &&
                        (c.body.digits == null || it.tx.accountNumber.takeLast(4).let { l4 -> l4.isEmpty() || l4 == c.body.digits })
                }
                val best = near.minByOrNull {
                    val sameCard = if (c.body.digits != null && it.tx.accountNumber.endsWith(c.body.digits)) 0 else 1
                    sameCard * PAIR_WITHIN_MS * 2 + kotlin.math.abs(it.message.message.date - c.message.message.date)
                } ?: continue
                used += best.message.id
                val last4 = best.tx.accountNumber.filter { it.isDigit() }.takeLast(4).ifEmpty { null }
                found[c.message.id] = match(c, "Your card shows a payment of the same amount", "L3", last4)
            }
        }

        // L4: a bill-payment service or a credit-card payee with no card words: only a question.
        for (c in unsettled) {
            if (found.containsKey(c.message.id)) continue
            if (c.aggregator && !c.notACard) found[c.message.id] = suspected(c, "Paid through a bill-payment service", "L4")
            else if (c.weak) found[c.message.id] = suspected(c, "Paid to a credit card issuer", "L4")
        }
        return found
    }

    private fun label(bill: MessageContent.CardBill): String = bill.bankName.displayString.ifBlank { "credit" }

    private fun match(c: Candidate, reason: String, layer: String, last4: String? = c.body.digits) =
        CardBillMatch(CardBillKind.CERTAIN, last4, reason, layer)

    private fun suspected(c: Candidate, reason: String, layer: String, last4: String? = c.body.digits) =
        CardBillMatch(CardBillKind.SUSPECTED, last4, reason, layer)

    /** First index whose date is greater than [value]. */
    private fun firstAfter(dates: LongArray, value: Long): Int {
        var lo = 0
        var hi = dates.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dates[mid] > value) hi = mid else lo = mid + 1
        }
        return lo
    }

    /**
     * Marks the debits found as card bill payments: certain ones leave the totals (the card swipes were counted already),
     * suspected ones carry the question. A debit the person said is no card bill payment is counted as spending again, even
     * when the rules had read it as one. Every message nothing applies to stays the same instance.
     */
    fun apply(messages: List<ClassifiedMessage>, decisions: CardBillDecisions, zone: ZoneId): List<ClassifiedMessage> {
        val found = detect(messages, decisions, zone)
        val unflag = decisions.no
        if (found.isEmpty() && unflag.isEmpty() && decisions.payeesNo.isEmpty()) return messages
        var changed = false
        val result = messages.map { m ->
            val tx = m.content as? MessageContent.Transaction ?: return@map m
            val match = found[m.id]
            val denied = !tx.isReceived && tx.isCardPayment && m.setAside == null && InternalMoves.reason(tx) == null && InternalMoves.label(tx) == null &&
                (m.id in unflag || CategoryKey.of(m)?.let { it in decisions.payeesNo } == true)
            when {
                match != null && match.kind == CardBillKind.CERTAIN -> {
                    changed = true
                    // A debit from a bank account is not a swipe, whatever word "card" in its text suggested: it moves the account's balance.
                    m.copy(content = tx.copy(isCardPayment = true, isCard = false), cardBill = match)
                }
                match != null -> { changed = true; m.copy(cardBill = match) }
                denied -> { changed = true; m.copy(content = tx.copy(isCardPayment = false), cardBill = null) }
                else -> m
            }
        }
        return if (changed) result else messages
    }
}
