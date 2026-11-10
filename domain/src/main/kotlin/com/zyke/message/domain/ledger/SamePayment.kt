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
 * Two more kinds of evidence (a linked message is shown inside the payment's one row, see [PaymentGroups]; nothing is deleted):
 * - A merchant's booking or order confirmation (IRCTC, an airline, a bus, a hotel, a shop: "Booking confirmed", "Order placed")
 *   follows a bank debit of the same amount within 15 minutes when it also names the same merchant or shares a reference. A
 *   confirmation with no amount needs a shared reference, or the debit's payee naming the same merchant within 10 minutes.
 *   Anything less is not linked at all (a booking is not a text the person can be asked about).
 * - Two bank messages about one transaction (an account alert and a UPI or card alert): they must carry the same reference
 *   number (or, with no reference on one of them, the same account and amount within two minutes) AND be worded in clearly
 *   different templates. Identical or near-identical bodies are never linked: five identical alerts are five payments.
 *   A shared reference links by itself; without one it is a "Same payment?" question. The one that states the account balance
 *   (else the earlier one) is the payment; the other stops counting, so a total and a balance both see one debit.
 *   Messages of opposite directions (a debit and the credit at the receiver) are never one payment.
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

    private const val BOOKING_WITHIN_MS = 15 * MINUTE_MS
    private const val BOOKING_NO_AMOUNT_WITHIN_MS = 10 * MINUTE_MS
    private const val BANK_PAIR_WITHIN_MS = 2 * MINUTE_MS
    private const val BOOKING_AMBIGUOUS_MS = 3 * MINUTE_MS
    private const val SIMILAR_TEMPLATE = 0.6
    private const val BOOKING_CONFIRMED = "Booking confirmed"
    private const val ORDER_PLACED = "Order placed"

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
    private val WORD = Regex("[a-z]{2,}")
    private val BALANCE = Regex("(?i)\\b(?:avl|available)\\.?\\s*bal(?:ance)?\\b|\\bbalance\\s*(?:is|:|-)?\\s*(?:inr|rs\\.?|₹)")
    private val AMOUNTS = Regex("(?i)(?:rs\\.?|inr|\u20b9)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)")
    /** Words that say what a business does, not which one it is: never enough to call two names the same merchant. */
    private val COMMON_WORDS = setOf(
        "ticketing", "tickets", "ticket", "booking", "bookings", "online", "travel", "travels", "services", "service", "limited", "private",
        "india", "payments", "payment", "store", "stores", "shop", "mart", "bank", "pvtltd", "retail", "digital", "technologies", "solutions"
    )
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
        /** Every distinct amount the text states (a booking can state a fare and a fee), at most four. */
        val amounts: List<BigDecimal> by lazy {
            val found = ArrayList<BigDecimal>(4)
            for (m in AMOUNTS.findAll(flat)) {
                val v = m.groupValues[1].replace(",", "").toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: continue
                if (found.none { it.compareTo(v) == 0 }) found += v
                if (found.size >= 4) break
            }
            found
        }
        /** The wording as a set of lower-case words (numbers and one-letter words left out): what two alerts of the same bank template share. */
        val template: Set<String> by lazy { WORD.findAll(flat.lowercase()).mapTo(HashSet()) { it.value } }
        /** The text states the account's balance: it is a reading of the account, so it is the one a pair keeps. */
        val statesBalance: Boolean by lazy { BALANCE.containsMatchIn(flat) }
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
        /** Bank credits, only ever paired with each other (two alerts of one money in), never made followers of a debit. */
        val credits = ArrayList<Node>()
        val bookings = ArrayList<Booking>()
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
                        else if (!c.isCardPayment && m.setAside == null) credits += Node(m, amount, body)
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
                is MessageContent.Life -> if (isBooking(c)) bookings += Booking(m, c, body)
                else -> Unit
            }
        }
        if (followers.isEmpty() && bookings.isEmpty() && anchors.size < 2 && credits.size < 2) return emptyMap()

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

        // Two bank messages about one transaction: the one that states the balance (else the earlier) is the payment.
        val bankFollowers = HashSet<Long>()
        pairBanks(anchors, decisions, byId, primaries, found, bankFollowers)
        pairBanks(credits, decisions, byId, primaries, found, bankFollowers)
        // A debit that is a certain repeat of another bank message is no longer the payment a gateway text confirms.
        val payments = anchors.filter { it.id !in bankFollowers }

        val live = followers.filter { it.id !in settled && it.id !in primaries }
        val bookable = bookings.filter { it.message.id !in settled && it.message.id !in primaries && it.message.id !in found }
        for (b in bookable.filter { b -> decisions.yes[b.message.id]?.let { it in byId } == true }) {
            val primary = byId[decisions.yes[b.message.id]!!]!!
            found[b.message.id] = SamePaymentLink(SamePaymentKind.CERTAIN, primary.id, labelOf(primary), "You said this is the same payment", "you")
        }
        if (live.isNotEmpty()) matchTexts(payments, live, decisions, found)
        matchBookings(payments, bookable.filter { it.message.id !in found && it.message.id !in decisions.no && CategoryKey.of(it.message).let { k -> k == null || k !in decisions.sendersNo } }, found)
        return found
    }

    private fun matchTexts(anchors: List<Node>, live: List<Node>, decisions: SamePaymentDecisions, found: MutableMap<Long, SamePaymentLink>) {
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
    }


    // ---- two bank messages about one transaction

    private fun last4(tx: MessageContent.Transaction) = tx.accountNumber.filter { it.isDigit() }.takeLast(4)

    /** True when the two texts are worded so differently that they cannot be one bank's template filled in twice. */
    private fun differentTemplates(x: Facts, y: Facts): Boolean {
        if (x.flat == y.flat) return false
        val a = x.template
        val b = y.template
        if (a.isEmpty() || b.isEmpty()) return false
        val shared = a.count { it in b }
        val total = a.size + b.size - shared
        return shared.toDouble() / total < SIMILAR_TEMPLATE
    }

    private class BankPair(val first: Node, val second: Node, val rank: Int, val gap: Long)

    /**
     * Pairs bank messages of one direction that are one transaction told twice. [found] gets the later role (the follower);
     * [certain] gets the ids of followers that are certain, so a gateway text is matched to the payment that stays.
     */
    private fun pairBanks(
        pool: List<Node>, decisions: SamePaymentDecisions, byId: Map<Long, ClassifiedMessage>, primaries: Set<Long>,
        found: MutableMap<Long, SamePaymentLink>, certain: MutableSet<Long>
    ) {
        if (pool.size < 2) return
        // What the person said about a bank message: "same as that one" settles it, "not the same" keeps it apart.
        val free = ArrayList<Node>(pool.size)
        for (n in pool) {
            val key = CategoryKey.of(n.message)
            val primary = decisions.yes[n.id]?.let { byId[it] }
            when {
                n.id in decisions.no || (key != null && key in decisions.sendersNo) -> Unit
                primary != null && primary.id != n.id && n.id !in primaries -> {
                    found[n.id] = SamePaymentLink(SamePaymentKind.CERTAIN, primary.id, labelOf(primary), "You said this is the same payment", "you")
                    certain += n.id
                }
                else -> free += n
            }
        }
        if (free.size < 2) return
        val byAmount = HashMap<String, MutableList<Node>>()
        for (n in free) byAmount.getOrPut(amountKey(n.amount)) { ArrayList() } += n
        val pairs = ArrayList<BankPair>()
        for (group in byAmount.values) {
            if (group.size < 2) continue
            for (i in group.indices) for (j in i + 1 until group.size) {
                val x = group[i]
                val y = group[j]
                val tx = x.tx ?: continue
                val ty = y.tx ?: continue
                if (!differentTemplates(x.body, y.body)) continue
                val gap = kotlin.math.abs(x.date - y.date)
                val shared = x.body.refs.any { it in y.body.refs }
                // Two different reference numbers are two transactions.
                if (!shared && x.body.refs.isNotEmpty() && y.body.refs.isNotEmpty()) continue
                val ax = last4(tx)
                val ay = last4(ty)
                if (ax.isNotEmpty() && ay.isNotEmpty() && ax != ay) continue
                val rank = when {
                    shared && gap <= REF_WITHIN_MS -> RANK_REF
                    ax.isNotEmpty() && ax == ay && tx.isCard == ty.isCard && gap <= BANK_PAIR_WITHIN_MS -> RANK_TIME
                    else -> continue
                }
                pairs += BankPair(x, y, rank, gap)
            }
        }
        if (pairs.isEmpty()) return
        pairs.sortWith(compareByDescending<BankPair> { it.rank }.thenBy { it.gap }.thenBy { it.first.id }.thenBy { it.second.id })
        val anchorsUsed = HashSet<Long>()
        val taken = HashSet<Long>()
        for (p in pairs) {
            val (anchor, follower) = when {
                p.first.body.statesBalance != p.second.body.statesBalance -> if (p.first.body.statesBalance) p.first to p.second else p.second to p.first
                p.first.date != p.second.date -> if (p.first.date < p.second.date) p.first to p.second else p.second to p.first
                else -> if (p.first.id < p.second.id) p.first to p.second else p.second to p.first
            }
            // A message is a follower once, and one that already is a follower never also stands for a payment.
            if (follower.id in taken || anchor.id in taken || follower.id in anchorsUsed) continue
            anchorsUsed += anchor.id
            taken += follower.id
            val label = labelOf(anchor.message)
            if (p.rank == RANK_REF) {
                found[follower.id] = SamePaymentLink(SamePaymentKind.CERTAIN, anchor.id, label, "Same amount and reference number as the $label", "ref")
                certain += follower.id
            } else {
                found[follower.id] = SamePaymentLink(SamePaymentKind.SUSPECTED, anchor.id, label, "Same amount and account as the $label, ${apart(anchor.date, follower.date)}, worded differently", "time")
            }
        }
    }

    // ---- merchant booking and order confirmations

    private class Booking(val message: ClassifiedMessage, val life: MessageContent.Life, val body: Facts) {
        val date: Long get() = message.message.date
        val source: String = SenderKey.of(message.message.sender)
        /** Every amount the confirmation states: the order value some state, the fare and fees a booking may state. */
        val amounts: List<BigDecimal> = life.amount?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }?.let { listOf(it) } ?: body.amounts
        val refs: Set<String> = body.refs + listOfNotNull(life.reference?.takeIf { r -> r.count { it.isDigit() } >= 5 }?.uppercase())
        /** What the merchant is called in this message: the title read from it and the sender's own name. */
        val names: List<String> = listOf(life.title, SenderKey.displayAddress(message.message.sender)).map { NON_ALNUM.replace(it.lowercase(), "") }.filter { it.length >= MIN_NAME }.distinct()
    }

    private fun isBooking(c: MessageContent.Life): Boolean =
        (c.kind == com.zyke.message.domain.life.LifeKind.TRAVEL && c.status == BOOKING_CONFIRMED) ||
            (c.kind == com.zyke.message.domain.life.LifeKind.DELIVERY && c.status == ORDER_PLACED)

    private fun related(x: String, y: String): Boolean {
        if (x.length < MIN_NAME || y.length < MIN_NAME) return false
        if (x.contains(y) || y.contains(x)) return true
        var n = 0
        while (n < x.length && n < y.length && x[n] == y[n]) n++
        return n >= MIN_NAME
    }

    /** The words of a payee that name it (not what it does): "Irctc Ticketing" is named by "irctc". */
    private fun payeeWords(payee: String): List<String> =
        payee.substringBefore('@').lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= MIN_NAME && it !in COMMON_WORDS && it !in GENERIC_NAMES }

    private fun payeeNamesMerchant(a: Node, b: Booking): Boolean {
        val payee = a.tx?.payee ?: return false
        return payeeWords(payee).any { w -> b.names.any { n -> related(w, n) } }
    }

    private fun textNamesMerchant(a: Node, b: Booking): Boolean {
        val key = a.name
        if (key != null && b.body.squashed.contains(key)) return true
        return b.names.any { n -> n !in COMMON_WORDS && a.body.squashed.contains(n) }
    }

    private class BookEdge(val anchor: Node, val booking: Booking, val rank: Int, val gap: Long)

    private fun matchBookings(anchors: List<Node>, bookings: List<Booking>, found: MutableMap<Long, SamePaymentLink>) {
        if (bookings.isEmpty() || anchors.isEmpty()) return
        val edges = ArrayList<BookEdge>()
        for (b in bookings) {
            val known = b.amounts.isNotEmpty()
            for (a in anchors) {
                val gap = kotlin.math.abs(a.date - b.date)
                if (gap > REF_WITHIN_MS) continue
                val equal = known && b.amounts.any { it.compareTo(a.amount) == 0 }
                // A confirmation that states an amount must state this one.
                if (known && !equal) continue
                val rank = when {
                    a.body.refs.any { it in b.refs } -> RANK_REF
                    equal && gap <= BOOKING_WITHIN_MS && (payeeNamesMerchant(a, b) || textNamesMerchant(a, b)) -> RANK_NAME
                    !known && gap <= BOOKING_NO_AMOUNT_WITHIN_MS && payeeNamesMerchant(a, b) -> RANK_NAME
                    else -> continue
                }
                edges += BookEdge(a, b, rank, gap)
            }
        }
        if (edges.isEmpty()) return
        edges.sortWith(compareByDescending<BookEdge> { it.rank }.thenBy { it.gap }.thenBy { it.booking.message.id }.thenBy { it.anchor.id })
        // Two debits that both fit one confirmation about equally well: no link, rather than a guess.
        val ambiguous = HashSet<Long>()
        for ((id, list) in edges.groupBy { it.booking.message.id }) {
            val top = list.first()
            if (top.rank == RANK_NAME && list.any { it !== top && it.rank == RANK_NAME && it.anchor.id != top.anchor.id && it.gap - top.gap < BOOKING_AMBIGUOUS_MS }) ambiguous += id
        }
        val taken = HashSet<String>()
        val done = HashSet<Long>()
        for (e in edges) {
            val id = e.booking.message.id
            if (id in done || id in ambiguous) continue
            // One debit takes one confirmation from each sender, so two equal bookings keep their own.
            if (!taken.add("${e.anchor.id}|${e.booking.source}")) continue
            done += id
            val label = labelOf(e.anchor.message)
            found[id] = if (e.rank == RANK_REF) {
                SamePaymentLink(SamePaymentKind.CERTAIN, e.anchor.id, label, "Same reference number as the $label", "ref")
            } else {
                SamePaymentLink(SamePaymentKind.CERTAIN, e.anchor.id, label, "Same amount and merchant as the $label, ${apart(e.anchor.date, e.booking.date)}", "booking")
            }
        }
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
