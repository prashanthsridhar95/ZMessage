package com.zyke.message.domain.ledger

import com.zyke.message.domain.categories.CategoryKey
import com.zyke.message.domain.conversation.SenderKey
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import com.zyke.message.domain.util.BodyMemo
import com.zyke.message.domain.util.toBigDecimalOrNull
import java.math.BigDecimal
import java.time.ZoneId

/**
 * One real payment often makes several texts: the bank's debit, a payment gateway's confirmation (Razorpay, PayU,
 * BillDesk, Cashfree), the merchant's "payment received", an app's "paid". Only the bank's debit (a message with an
 * account number) is money leaving the account, so it is the one that counts; the others, once linked to it, are
 * [Counted.NotCounted] ("Same payment as the HDFC debit"). Nothing is deleted or hidden: every text stays its own message.
 *
 * The link is made with the strongest evidence, never silently on weak evidence:
 * - "ref": the same amount and a shared reference number (UPI ref, txn id, RRN ...) within half an hour. This links on its own.
 * - "rule": the person said messages from this sender repeat a bank debit; same amount within ten minutes, and only one debit fits.
 * - "name": the same amount and the same merchant within ten minutes, but no shared reference: a question in Needs review.
 * - "time": only the same amount within three minutes: a question in Needs review.
 * Two texts with no bank debit (a gateway and a merchant) are only ever a question.
 *
 * Matching is one to one: one debit takes at most one text from each sender (a bank debit can be confirmed by Razorpay and
 * by the merchant, but two equal debits each take their own Razorpay text), and a shared reference beats nearness in time.
 * What the person answered always wins (see [SamePaymentDecisions]).
 */
object SamePayment {
    private const val MINUTE_MS = 60_000L
    private const val REF_WITHIN_MS = 30 * MINUTE_MS
    private const val NAME_WITHIN_MS = 10 * MINUTE_MS
    private const val TIME_WITHIN_MS = 3 * MINUTE_MS

    private const val RANK_TIME = 1
    private const val RANK_NAME = 2
    private const val RANK_RULE = 3
    private const val RANK_REF = 4

    private val SPACES = Regex("[\\s\\u00a0]+")
    private val NON_ALNUM = Regex("[^a-z0-9]")
    private val REF_KEY = Regex("(?i)\\b(?:upi|ref(?:erence)?|txn|trans(?:action)?|rrn|utr|order|payment|receipt|auth|id)\\b")
    private val REF_VALUE = Regex("(?i)^[\\s.:#\\-]*(?:(?:no|id|number|num|ref|is|upi)\\b[\\s.:#\\-]*)*([A-Za-z0-9]{6,30})\\b")
    private val PAY_ID = Regex("(?i)\\bpay_[A-Za-z0-9]{8,30}\\b")
    private val GATEWAY = Regex(
        "(?i)\\b(?:razorpay|payu|billdesk|bill desk|cashfree|ccavenue|instamojo|juspay|paytm|phonepe|gpay|google pay|amazon pay|cred)\\b"
    )
    private val CONFIRMS = Regex(
        "(?i)\\b(?:received|got|recd)\\s+(?:your\\s+)?payment\\b|\\byour\\s+payment\\b.{0,80}?\\b(?:received|successful|successfully|confirmed|processed|done)\\b|" +
            "\\bpayment\\s+(?:of\\s+.{0,24}?)?(?:received|successful|confirmed)\\b|\\bthank you for (?:your |the )?payment\\b"
    )
    /** "received Rs 500 from Rahul": money coming to the person, never a confirmation of their own payment. */
    private val INCOMING = Regex("(?i)\\b(?:received|credited|got)\\b.{0,40}?\\bfrom\\b")
    private val AMOUNT = Regex("(?i)(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    /** The gateways whose own texts sometimes name a card or an account; their wording is a confirmation, not a bank's debit alert. */
    private val STRICT_GATEWAY = Regex("(?i)\\b(?:razorpay|payu|billdesk|bill desk|cashfree|ccavenue|instamojo|juspay)\\b")
    private val BANK_ALERT = Regex("(?i)\\b(?:debited|spent|withdrawn|purchase|a/c|acct|avl (?:bal|limit))\\b")
    private val GENERIC_NAMES = setOf("payment", "moneyreceived", "upi", "bank", "merchant", "paid", "transfer", "customer", "online", "pos")
    private const val MIN_NAME = 5

    private class Facts(raw: String) {
        val flat: String = SPACES.replace(BodyMemo.bounded(raw), " ")
        val refs: Set<String> = refsOf(flat)
        val confirms: Boolean = CONFIRMS.containsMatchIn(flat) && !INCOMING.containsMatchIn(flat)
        val gateway: Boolean = GATEWAY.containsMatchIn(flat)
        /** A gateway's confirmation that happens to name a card: it is not a bank's debit. */
        val gatewayText: Boolean = STRICT_GATEWAY.containsMatchIn(flat) && !BANK_ALERT.containsMatchIn(flat)
        val amount: BigDecimal? = AMOUNT.find(flat)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull()
        /** Letters and digits only, lower case: where a merchant's name is looked for. */
        val squashed: String = NON_ALNUM.replace(flat.lowercase(), "")
    }

    private fun refsOf(flat: String): Set<String> {
        val found = LinkedHashSet<String>()
        for (id in PAY_ID.findAll(flat)) found += id.value.uppercase()
        for (key in REF_KEY.findAll(flat)) {
            val from = key.range.last + 1
            val tail = flat.substring(from, minOf(flat.length, from + 48))
            val value = REF_VALUE.find(tail)?.groupValues?.get(1) ?: continue
            // A reference carries digits; "successful" and "received" do not.
            if (value.count { it.isDigit() } >= 5) found += value.uppercase()
            if (found.size >= 6) break
        }
        return found
    }

    private val facts = BodyMemo { body -> Facts(body) }

    private class Node(val message: ClassifiedMessage, val amount: BigDecimal, val body: Facts) {
        val date: Long get() = message.message.date
        val id: Long get() = message.id
        val tx: MessageContent.Transaction? get() = message.content as? MessageContent.Transaction
        val source: String = SenderKey.of(message.message.sender)
        /** What a merchant is called in this message, for finding it in another: no domain of a UPI address, no short or generic names. */
        val name: String? = tx?.let { nameKey(it.payee) }
    }

    private fun nameKey(payee: String): String? {
        val key = NON_ALNUM.replace(payee.substringBefore('@').lowercase(), "")
        return key.takeIf { it.length >= MIN_NAME && it !in GENERIC_NAMES }
    }

    private fun hasAccount(tx: MessageContent.Transaction) = tx.accountNumber.any { it.isDigit() }

    private fun amountKey(a: BigDecimal) = a.stripTrailingZeros().toPlainString()

    /** True when [message] is a text the app has linked to another message as the same payment and so does not count. */
    fun isRepeat(message: ClassifiedMessage): Boolean = message.samePayment?.kind == SamePaymentKind.CERTAIN

    /** Plain wording of the message that is counted instead, for a row or a sentence. */
    private fun labelOf(m: ClassifiedMessage): String {
        val tx = m.content as? MessageContent.Transaction
        if (tx != null && hasAccount(tx)) {
            val bank = tx.bankName.displayString.ifBlank { "bank" }
            return if (tx.isCard) "$bank card payment" else "$bank debit"
        }
        return SenderKey.displayAddress(m.message.sender) + " message"
    }

    private fun apart(a: Long, b: Long): String {
        val minutes = kotlin.math.abs(a - b) / MINUTE_MS
        return if (minutes < 1) "under a minute apart" else if (minutes == 1L) "1 minute apart" else "$minutes minutes apart"
    }

    private class Edge(val anchor: Node, val follower: Node, val rank: Int, val gap: Long)

    /**
     * The messages (by id) that repeat another message's payment, each with the message that is counted instead. Messages the
     * person answered are settled by their word.
     */
    fun detect(messages: List<ClassifiedMessage>, decisions: SamePaymentDecisions, @Suppress("UNUSED_PARAMETER") zone: ZoneId): Map<Long, SamePaymentLink> {
        val anchors = ArrayList<Node>()
        val followers = ArrayList<Node>()
        val byId = HashMap<Long, ClassifiedMessage>()
        for (m in messages) {
            if (m.isPromotional || ManualEntries.isManual(m)) continue
            val body = facts.of(m.message)
            when (val c = m.content) {
                is MessageContent.Transaction -> {
                    val amount = c.amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
                    byId[m.id] = m
                    if (hasAccount(c) && !(body.gatewayText && !c.isReceived)) {
                        // A real debit from an account or a card: the source of truth.
                        if (!c.isReceived) anchors += Node(m, amount, body)
                    } else if (m.setAside == null && !(c.isCardPayment && c.isReceived) && (!c.isReceived || body.confirms)) {
                        followers += Node(m, amount, body)
                    }
                }
                is MessageContent.Notice -> if (c.kind == NoticeKind.PAYMENT_CONFIRMATION && body.refs.isNotEmpty()) {
                    val amount = c.amount?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
                    followers += Node(m, amount, body)
                }
                MessageContent.Generic -> if (body.gateway && body.refs.isNotEmpty()) {
                    val amount = body.amount?.takeIf { it.signum() > 0 } ?: continue
                    followers += Node(m, amount, body)
                }
                else -> Unit
            }
        }
        if (followers.isEmpty()) return emptyMap()

        val found = HashMap<Long, SamePaymentLink>()
        val settled = HashSet<Long>()
        val primaries = decisions.yes.values.toHashSet()

        // What the person said first.
        for (f in followers) {
            val key = CategoryKey.of(f.message)
            val id = f.id
            if (id in decisions.no || (key != null && key in decisions.sendersNo)) { settled += id; continue }
            val primary = decisions.yes[id]?.let { byId[it] }
            if (primary != null && primary.id != id && id !in primaries) {
                settled += id
                found[id] = SamePaymentLink(SamePaymentKind.CERTAIN, primary.id, labelOf(primary), "You said this is the same payment", "you")
            }
        }

        val live = followers.filter { it.id !in settled && it.id !in primaries }
        if (live.isEmpty()) return found

        // Bank debits by amount, oldest first.
        val byAmount = HashMap<String, MutableList<Node>>()
        for (a in anchors) byAmount.getOrPut(amountKey(a.amount)) { ArrayList() } += a
        val edges = ArrayList<Edge>()
        val counts = HashMap<Long, Int>()
        for (f in live) {
            val bucket = byAmount[amountKey(f.amount)] ?: continue
            val key = CategoryKey.of(f.message)
            val ruled = key != null && key in decisions.sendersYes
            val refOnly = f.message.content !is MessageContent.Transaction
            for (a in bucket) {
                val gap = kotlin.math.abs(a.date - f.date)
                val rank = when {
                    gap <= REF_WITHIN_MS && a.body.refs.any { it in f.body.refs } -> RANK_REF
                    refOnly -> continue
                    ruled && gap <= NAME_WITHIN_MS -> RANK_RULE
                    gap <= NAME_WITHIN_MS && sharesName(a, f) -> RANK_NAME
                    gap <= TIME_WITHIN_MS -> RANK_TIME
                    else -> continue
                }
                edges += Edge(a, f, rank, gap)
                if (rank != RANK_REF) counts.merge(f.id, 1, Int::plus)
            }
        }
        edges.sortWith(compareByDescending<Edge> { it.rank }.thenBy { it.gap }.thenBy { it.follower.id }.thenBy { it.anchor.id })
        val taken = HashSet<String>()
        for (e in edges) {
            if (e.follower.id in found) continue
            // One debit takes one text from each sender.
            if (!taken.add("${e.anchor.id}|${e.follower.source}")) continue
            val label = labelOf(e.anchor.message)
            found[e.follower.id] = when (e.rank) {
                RANK_REF -> SamePaymentLink(SamePaymentKind.CERTAIN, e.anchor.id, label, "Same amount and reference number as the $label", "ref")
                RANK_RULE ->
                    if ((counts[e.follower.id] ?: 0) == 1) SamePaymentLink(SamePaymentKind.CERTAIN, e.anchor.id, label, "Messages from this sender repeat your $label, as you said", "rule")
                    else SamePaymentLink(SamePaymentKind.SUSPECTED, e.anchor.id, label, "Same amount as the $label, ${apart(e.anchor.date, e.follower.date)}", "time")
                RANK_NAME -> SamePaymentLink(SamePaymentKind.SUSPECTED, e.anchor.id, label, "Same amount and merchant as the $label, ${apart(e.anchor.date, e.follower.date)}", "name")
                else -> SamePaymentLink(SamePaymentKind.SUSPECTED, e.anchor.id, label, "Same amount as the $label, ${apart(e.anchor.date, e.follower.date)}", "time")
            }
        }

        // Two texts and no bank debit between them: only ever a question.
        val rest = live.filter { it.id !in found && it.message.content is MessageContent.Transaction }
        if (rest.size >= 2) pairTexts(rest, found)
        return found
    }

    private fun sharesName(a: Node, f: Node): Boolean {
        val an = a.name
        val fn = f.name
        return (an != null && (an == fn || f.body.squashed.contains(an))) || (fn != null && a.body.squashed.contains(fn))
    }

    private fun pairTexts(rest: List<Node>, found: MutableMap<Long, SamePaymentLink>) {
        val byAmount = HashMap<String, MutableList<Node>>()
        for (n in rest) byAmount.getOrPut(amountKey(n.amount)) { ArrayList() } += n
        class TextPair(val primary: Node, val secondary: Node, val rank: Int, val gap: Long)
        val pairs = ArrayList<TextPair>()
        for (group in byAmount.values) {
            if (group.size < 2) continue
            for (i in group.indices) for (j in i + 1 until group.size) {
                val x = group[i]
                val y = group[j]
                if (x.source == y.source) continue
                val gap = kotlin.math.abs(x.date - y.date)
                val rank = when {
                    gap <= REF_WITHIN_MS && x.body.refs.any { it in y.body.refs } -> RANK_REF
                    gap <= NAME_WITHIN_MS && sharesName(x, y) -> RANK_NAME
                    gap <= TIME_WITHIN_MS -> RANK_TIME
                    else -> continue
                }
                // The one that stays counted: money out before money in, then the earlier text.
                val xOut = (x.tx?.isReceived == false)
                val yOut = (y.tx?.isReceived == false)
                val (primary, secondary) = when {
                    xOut != yOut -> if (xOut) x to y else y to x
                    x.date <= y.date -> x to y
                    else -> y to x
                }
                pairs += TextPair(primary, secondary, rank, gap)
            }
        }
        pairs.sortWith(compareByDescending<TextPair> { it.rank }.thenBy { it.gap }.thenBy { it.secondary.id })
        val used = HashSet<Long>()
        for (p in pairs) {
            if (p.primary.id in used || p.secondary.id in used) continue
            used += p.primary.id
            used += p.secondary.id
            val label = labelOf(p.primary.message)
            val why = when (p.rank) {
                RANK_REF -> "Same amount and reference number as the $label"
                RANK_NAME -> "Same amount and merchant as the $label, ${apart(p.primary.date, p.secondary.date)}"
                else -> "Same amount as the $label, ${apart(p.primary.date, p.secondary.date)}"
            }
            found[p.secondary.id] = SamePaymentLink(SamePaymentKind.SUSPECTED, p.primary.id, label, why, if (p.rank == RANK_REF) "ref" else if (p.rank == RANK_NAME) "name" else "time")
        }
    }

    /**
     * Marks the messages found: a certain repeat leaves the totals (the bank debit is the payment), a suspected one only
     * carries the question. Every message nothing applies to stays the same instance.
     */
    fun apply(messages: List<ClassifiedMessage>, decisions: SamePaymentDecisions, zone: ZoneId): List<ClassifiedMessage> {
        val found = detect(messages, decisions, zone)
        if (found.isEmpty()) return messages
        return messages.map { m ->
            val link = found[m.id] ?: return@map m
            m.copy(samePayment = link)
        }
    }
}
