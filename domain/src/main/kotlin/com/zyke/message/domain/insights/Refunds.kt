package com.zyke.message.domain.insights

import com.zyke.message.domain.ledger.Counting
import com.zyke.message.domain.util.toBigDecimalOrNull
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.model.NoticeKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Where a refund is: said to be started, sent on by the merchant, or in the person's account. */
enum class RefundStage(val typeLabel: String) {
    INITIATED("Refund initiated"),
    PROCESSED("Refund processed"),
    ARRIVED("Refund received")
}

/**
 * One refund, however many messages told its story ("initiated", then "processed"). [message] is the newest stage's
 * text (what a tap opens); [messages] are all of them. [startedOn] is the earliest day any of them was sent.
 */
data class RefundItem(
    val message: ClassifiedMessage,
    val from: String,
    val amount: BigDecimal,
    val startedOn: LocalDate,
    val arrivedOn: LocalDate?,
    /** The day the merchant said it had processed the refund, if it did. */
    val processedOn: LocalDate? = null,
    /** The merchant's order the refund is for ("NUGTJCORO38008"), if the texts say. */
    val orderId: String? = null,
    /** The refund's own reference number ("Refund Ref. num. 667812523611"), if the texts say. */
    val refundRef: String? = null,
    val messages: List<ClassifiedMessage> = listOf(message),
    /** The bank credit that was matched as the arrival; null when none was found or the person said so by hand. */
    val credit: ClassifiedMessage? = null,
    /** The person said it arrived (see [RefundTracker.build]'s `received`). */
    val markedReceived: Boolean = false,
    /** Stable identity for "mark as received". */
    val key: String = ""
) {
    val isPending: Boolean get() = arrivedOn == null
    val stage: RefundStage get() = when {
        arrivedOn != null -> RefundStage.ARRIVED
        processedOn != null -> RefundStage.PROCESSED
        else -> RefundStage.INITIATED
    }

    /** "Initiated 8 Nov · expected in 5-7 days", "Processed 8 Nov · should reach you soon", "Arrived 12 Nov". */
    fun statusLine(day: (LocalDate) -> String): String = when (stage) {
        RefundStage.ARRIVED -> "Arrived " + day(arrivedOn ?: startedOn)
        RefundStage.PROCESSED -> "Processed " + day(processedOn ?: startedOn) + " \u00b7 should reach you soon"
        RefundStage.INITIATED -> "Initiated " + day(startedOn) + " \u00b7 expected in 5\u20137 days"
    }
}

/**
 * Keeps track of refunds. A refund goes INITIATED, then PROCESSED, then ARRIVED: the first two are messages from the
 * merchant (often both for one order, minutes apart: they are ONE refund), the last is a bank credit of the same amount
 * (or the person saying so). Until the credit shows up the refund is "on the way".
 *
 * These notices are not money movements: [Counting] never counts them. A matched bank credit is only linked to the
 * refund here (it is not taken off spending); it keeps counting as it did.
 */
object RefundTracker {
    private const val SHOW_PENDING_FOR_DAYS = 30L
    private const val SHOW_ARRIVED_FOR_DAYS = 7L

    /** How long after a refund started (or was processed) a credit may still be its arrival. */
    private const val ARRIVAL_WINDOW_DAYS = 10L

    /** Two texts for one refund with no order number or reference must be this close. */
    private const val SAME_REFUND_DAYS = 3L

    /** An unexplained credit is only ever taken for a refund below this; bigger ones are salary-like. */
    private val UNEXPLAINED_LIMIT = BigDecimal("20000")

    /** A refund message: the word refund beside a stage word, in either order ("initiated a refund"). */
    val STARTED_PATTERN = Regex(
        "(?i)\\brefund\\b.*\\b(?:initiated|processed|approved|will be credited|being processed|in process|raised|issued)\\b" +
            "|\\b(?:initiated|processed|raised|issued)\\s+(?:a |your |the )?refund\\b"
    )
    private val STARTED = STARTED_PATTERN
    private val PROCESSED = Regex(
        "(?i)\\b(?:has been|have been|is|was|been|successfully|now)\\s+processed\\b|\\bprocessed\\s+successfully\\b" +
            "|\\brefund\\s+(?:is\\s+)?processed\\b|\\bprocessed\\s+(?:a |your |the )?refund\\b"
    )
    private val CREDITED_ALREADY = Regex("(?i)\\b(?:has been|have been|was|is)\\s+credited\\b|(?<!be )\\bcredited to\\b")
    private val AMOUNT = Regex("(?i)(?:INR\\.?|Rs\\.?|\u20b9)\\s*([\\d,]+(?:\\.\\d+)?)")
    private val ORDER_ID = Regex("(?i)\\border\\b(?:\\s*(?:id|no\\.?|number))?\\s*[:#-]?\\s*([A-Za-z0-9]{9,14})\\b")
    private val REFUND_REF = Regex(
        "(?i)\\bref(?:erence)?\\.?\\s*(?:num(?:ber)?\\.?|no\\.?|id)\\s*[:#-]?\\s*(\\d{9,16})\\b|\\brefund\\s+ref(?:erence)?\\.?\\s*[:#-]?\\s*(\\d{9,16})\\b"
    )
    private val REFUND_WORD = Regex("(?i)\\b(?:refund|refunded|reversal|reversed)\\b")
    private val BRAND = Regex("(?i)\\b([A-Za-z][A-Za-z0-9&]{3,})\\s+order\\b")
    private val NOT_BRANDS = setOf("your", "this", "that", "their", "with", "refund", "placed", "same", "the", "online", "cancelled", "canceled", "pending")
    private val SALARY_LIKE = Regex("(?i)\\b(?:salary|payroll|interest|dividend|bonus|reimbursement|emi)\\b")

    /** Other names a merchant's payments go by in a bank's narration. */
    private val ALIASES: Map<String, List<String>> = mapOf(
        "zepto" to listOf("kiranakart"),
        "swiggy" to listOf("bundl"),
        "instamart" to listOf("bundl", "swiggy"),
        "blinkit" to listOf("blinkcommerce", "grofers")
    )

    private val CONDITIONAL = Regex("(?i)\\b(?:we|they) (?:will|shall) refund\\b|\\bin case\\b.*\\brefund")

    /** "In case it is not approved, we will refund the amount": a promise that may never be kept, not money in or out. */
    fun isConditionalRefund(body: String): Boolean = CONDITIONAL.containsMatchIn(body)

    /** True for a message that announces a refund which has not arrived yet. */
    fun announcesPendingRefund(body: String): Boolean = STARTED.containsMatchIn(body) && !CREDITED_ALREADY.containsMatchIn(body)

    /** The stage a refund message states: PROCESSED when the merchant says it has processed it, else INITIATED. */
    fun stageOf(body: String): RefundStage = if (PROCESSED.containsMatchIn(body)) RefundStage.PROCESSED else RefundStage.INITIATED

    /** What a refund message's detail sheet calls its type ("Refund initiated" / "Refund processed"), or null for any other message. */
    fun typeLabel(message: ClassifiedMessage): String? {
        val content = message.content
        if (content !is MessageContent.Notice || content.kind != NoticeKind.REFUND_PENDING) return null
        return facts(message).stage.typeLabel
    }

    /** The order number a refund text names, or null. */
    fun orderIdOf(body: String): String? = ORDER_ID.findAll(body).map { it.groupValues[1] }.firstOrNull { id -> id.any { it.isDigit() } }

    /** The refund's own reference number a text names, or null. */
    fun refundRefOf(body: String): String? = REFUND_REF.find(body)?.let { m -> m.groupValues[1].ifEmpty { m.groupValues[2] } }?.takeIf { it.isNotEmpty() }

    private class Notice(
        val message: ClassifiedMessage,
        val day: LocalDate,
        val amount: BigDecimal,
        val stage: RefundStage,
        val orderId: String?,
        val ref: String?,
        val from: String,
        val merchantKey: String
    )

    private class Group(first: Notice) {
        val notices = arrayListOf(first)
        var orderId: String? = first.orderId
        var ref: String? = first.ref
        val merchantKey = first.merchantKey
        val amount = first.amount
        val startedOn: LocalDate get() = notices.minOf { it.day }
        val endedOn: LocalDate get() = notices.maxOf { it.day }
        val hasIds: Boolean get() = orderId != null || ref != null
        fun has(stage: RefundStage) = notices.any { it.stage == stage }
        val stage: RefundStage get() = if (has(RefundStage.PROCESSED)) RefundStage.PROCESSED else RefundStage.INITIATED
        val processedOn: LocalDate? get() = notices.filter { it.stage == RefundStage.PROCESSED }.minOfOrNull { it.day }
        fun add(n: Notice) {
            notices += n
            if (orderId == null) orderId = n.orderId
            if (ref == null) ref = n.ref
        }
    }

    private class Credit(val message: ClassifiedMessage, val amount: BigDecimal, val day: LocalDate, val text: String, val flat: String)

    private fun merchantKey(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun sameRefund(g: Group, n: Notice): Boolean {
        if (g.merchantKey != n.merchantKey || g.amount.compareTo(n.amount) != 0) return false
        val order = g.orderId
        val ref = g.ref
        if (order != null && n.orderId != null && order == n.orderId) return true
        if (ref != null && n.ref != null && ref == n.ref) return true
        // A different order or reference is a different refund, even for the same amount.
        return false
    }

    /** Without a shared id: one stage after the other within a few days, or the very same stage on the very same day. */
    private fun looselySame(g: Group, n: Notice): Boolean {
        if (g.merchantKey != n.merchantKey || g.amount.compareTo(n.amount) != 0) return false
        val order = g.orderId
        val ref = g.ref
        if (order != null && n.orderId != null && order != n.orderId) return false
        if (ref != null && n.ref != null && ref != n.ref) return false
        if (!g.has(n.stage)) {
            val gap = if (n.day.isBefore(g.startedOn)) java.time.temporal.ChronoUnit.DAYS.between(n.day, g.startedOn)
            else java.time.temporal.ChronoUnit.DAYS.between(g.endedOn, n.day).coerceAtLeast(0)
            return gap <= SAME_REFUND_DAYS
        }
        return !g.hasIds && n.orderId == null && n.ref == null && g.notices.any { it.stage == n.stage && it.day == n.day }
    }

    /**
     * The refunds the messages announce, one per refund, newest start first.
     *
     * @param messages newest first
     * @param received refunds the person said arrived, by [RefundItem.key], with the day they said so
     */
    fun build(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId, received: Map<String, LocalDate> = emptyMap()): List<RefundItem> {
        val today = LocalDate.ofInstant(now, zone)

        val credits = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (!tx.isReceived || tx.isCardPayment || com.zyke.message.domain.ledger.SamePayment.isRepeat(message)) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            val text = tx.payee + " " + message.message.body + " " + message.message.sender
            Credit(message, amount, Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), text, merchantKey(text))
        }.sortedWith(compareBy<Credit>({ it.day }, { it.message.id }))

        val notices = messages.mapNotNull { message ->
            val amount = refundNotice(message) ?: return@mapNotNull null
            val f = facts(message)
            val from = message.merchant.name.ifEmpty { message.message.sender }
            Notice(
                message, Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate(), amount, f.stage,
                f.orderId, f.ref, from, merchantKey(from)
            )
        }.sortedWith(compareBy<Notice>({ it.day }, { it.stage.ordinal }, { it.message.id }))

        // One refund, one group: the same order or reference, else the same amount a stage apart within days.
        val groups = ArrayList<Group>()
        for (n in notices) {
            val exact = groups.firstOrNull { sameRefund(it, n) }
            val target = exact ?: groups.filter { looselySame(it, n) }.minByOrNull { it.startedOn }
            if (target != null) target.add(n) else groups += Group(n)
        }

        // Arrival: a credit of exactly the refund's amount, from the day it started to ten days after, taken once.
        val used = HashSet<Long>()
        val arrivals = HashMap<Group, Credit>()
        fun windowEnd(g: Group) = maxOf(g.startedOn, g.processedOn ?: g.startedOn).plusDays(ARRIVAL_WINDOW_DAYS)
        fun inWindow(g: Group, c: Credit) = c.amount.compareTo(g.amount) == 0 && !c.day.isBefore(g.startedOn) && !c.day.isAfter(windowEnd(g))
        val oldestFirst = groups.sortedWith(compareBy<Group>({ it.startedOn }, { it.notices.first().message.id }))

        // 1. A credit that names the merchant (or its company) or says refund / reversal.
        for (g in oldestFirst) {
            val names = merchantNames(g)
            val hit = credits.firstOrNull { c ->
                c.message.id !in used && inWindow(g, c) &&
                    (REFUND_WORD.containsMatchIn(c.text) || names.any { it in c.flat })
            }
            if (hit != null) { used += hit.message.id; arrivals[g] = hit }
        }
        // 2. After "processed" only: a credit of exactly that amount nothing else explains, when it is not ambiguous.
        for (g in oldestFirst) {
            if (g in arrivals || g.stage != RefundStage.PROCESSED) continue
            val candidates = credits.filter { c ->
                c.message.id !in used && inWindow(g, c) && c.amount < UNEXPLAINED_LIMIT && !SALARY_LIKE.containsMatchIn(c.text)
            }
            if (candidates.isEmpty()) continue
            val rivals = oldestFirst.count { o -> o !in arrivals && o.stage == RefundStage.PROCESSED && o.amount.compareTo(g.amount) == 0 }
            // Several credits for one refund is a guess; several refunds for as many credits pair up oldest first.
            if (candidates.size == 1 || rivals >= candidates.size) {
                val hit = candidates.first()
                used += hit.message.id
                arrivals[g] = hit
            }
        }

        return groups.map { g ->
            val latest = g.notices.maxWithOrNull(compareBy<Notice>({ it.stage.ordinal }, { it.day }, { it.message.id }))!!
            val first = g.notices.first()
            val key = g.merchantKey + "|" + g.amount.stripTrailingZeros().toPlainString() + "|" + g.startedOn
            val credit = arrivals[g]
            val byHand = if (credit == null) received[key] else null
            RefundItem(
                message = latest.message,
                from = first.from,
                amount = g.amount,
                startedOn = g.startedOn,
                arrivedOn = credit?.day ?: byHand,
                processedOn = g.processedOn,
                orderId = g.orderId,
                refundRef = g.ref,
                messages = g.notices.map { it.message },
                credit = credit?.message,
                markedReceived = byHand != null,
                key = key
            )
        }.filter { item ->
            if (item.isPending) !item.startedOn.isBefore(today.minusDays(SHOW_PENDING_FOR_DAYS))
            else !item.arrivedOn!!.isBefore(today.minusDays(SHOW_ARRIVED_FOR_DAYS))
        }.sortedWith(compareByDescending<RefundItem> { it.isPending }.thenByDescending { it.arrivedOn ?: it.startedOn }.thenByDescending { it.startedOn })
    }

    /** The words a bank's narration may call the merchant: its name as the sender shows it, as the text says it ("your Zepto order"), and its company. */
    private fun merchantNames(g: Group): List<String> {
        val names = LinkedHashSet<String>()
        names += g.merchantKey
        for (n in g.notices) BRAND.find(n.message.message.body)?.groupValues?.get(1)?.let { names += merchantKey(it) }
        names.removeAll { it.length < 4 || it in NOT_BRANDS }
        val out = ArrayList<String>(names)
        for ((name, others) in ALIASES) if (names.any { name in it }) out += others
        return out
    }

    /** What a body says about a refund, worked out once per body. */
    private class RefundFacts(raw: String) {
        val body = com.zyke.message.domain.util.BodyMemo.bounded(raw)
        val announces = announcesPendingRefund(body)
        val amount: BigDecimal? by lazy { AMOUNT.find(body)?.groupValues?.get(1)?.replace(",", "")?.toBigDecimalOrNull() }
        val stage: RefundStage by lazy { stageOf(body) }
        val orderId: String? by lazy { orderIdOf(body) }
        val ref: String? by lazy { refundRefOf(body) }
    }

    private val refundFacts = com.zyke.message.domain.util.BodyMemo { body -> RefundFacts(body) }

    private fun facts(message: ClassifiedMessage): RefundFacts = refundFacts.of(message.message)

    /** The amount a refund-started message names, or null for any other message. */
    private fun refundNotice(message: ClassifiedMessage): BigDecimal? {
        val content = message.content
        val isNotice = content is MessageContent.Notice && content.kind == NoticeKind.REFUND_PENDING
        val facts = refundFacts.of(message.message)
        if (!isNotice && !facts.announces) return null
        return facts.amount
    }
}
