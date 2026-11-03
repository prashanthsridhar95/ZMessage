package com.zyke.message.domain.insights

import com.zyke.message.domain.util.toBigDecimalOrNull
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.domain.model.MessageContent
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A payment that comes round every month. */
data class Subscription(
    val payee: String,
    val amount: BigDecimal,
    val lastPaid: LocalDate,
    val nextDue: LocalDate,
    val times: Int,
    /** The payment's own message, to open. */
    val messageId: Long,
    /** How often it comes round. Detection finds monthly ones; the overview can convert any cadence to a yearly cost. */
    val cadence: Cadence = Cadence.MONTHLY,
    /** The charge before the latest one, to spot a price rise; null when not known. */
    val previousAmount: BigDecimal? = null,
    /** The earliest charge of the run that was recognised. */
    val firstPaid: LocalDate? = null,
    /**
     * Set when the payee is charged several times each cycle (five SIP mandates of the same size): tells this entry
     * from its siblings. Part of [key], so each has its own cancel and confirm state. Empty for an ordinary one.
     */
    val variant: String = "",
    /** This entry's place among [ofCount] alike ones; 1 and 1 for an ordinary subscription. */
    val ordinal: Int = 1,
    val ofCount: Int = 1,
    /** The mandate or reference the charges quote ("UMRN …4009"); empty when they quote none or all quote the same. */
    val reference: String = "",
    /** The name the person chose for a merged entry; empty keeps [payee]. Never part of [key]. */
    val alias: String = "",
    /** Names of the other entries merged into this one ("Also seen as"); empty for an ordinary entry. */
    val alsoSeenAs: List<String> = emptyList(),
    /** Keys of the entries folded into this one by [RecurringMerges.apply]. */
    val mergedKeys: List<String> = emptyList()
) {
    fun daysUntilDue(today: LocalDate): Long = ChronoUnit.DAYS.between(today, nextDue)

    /** Stable identity for the person's confirm / ignore choice. */
    val key: String get() = Subscriptions.keyFor(payee) + (if (variant.isEmpty()) "" else Subscriptions.VARIANT_SEPARATOR + variant)

    /** "#2 of 5" for one of several alike entries; empty for an ordinary one (and for a renamed merged one). */
    val suffix: String get() = if (ofCount > 1 && alias.isEmpty()) "#$ordinal of $ofCount" else ""

    /** What to show as the name: the person's own for a merged entry, else the payee. */
    val title: String get() = alias.ifEmpty { payee }

    val isMerged: Boolean get() = mergedKeys.isNotEmpty()

    /** How many names this entry is known by ("Merged · 2 names"). */
    val nameCount: Int get() = alsoSeenAs.size + 1

    /** The payee key shared by every entry of the same payee. */
    val payeeKey: String get() = Subscriptions.keyFor(payee)
}

/**
 * Finds payments that repeat monthly: the same payee three or more times, about a month apart and about the same
 * size. It can only remind: the app cannot pay, so this is the "alerted payment" part of recurring payments.
 */
object Subscriptions {
    private const val MIN_TIMES = 3
    private const val MIN_GAP = 24L
    private const val MAX_GAP = 38L
    private val SIZE_TOLERANCE = BigDecimal("0.25")
    private const val LOOKBACK_MONTHS = 8L

    fun find(messages: List<ClassifiedMessage>, now: Instant, zone: ZoneId): List<Subscription> {
        val today = LocalDate.ofInstant(now, zone)
        val earliest = today.minusMonths(LOOKBACK_MONTHS)

        val payments = messages.mapNotNull { message ->
            val tx = message.content as? MessageContent.Transaction ?: return@mapNotNull null
            if (tx.isReceived || message.setAside != null || tx.payee.isBlank()) return@mapNotNull null
            val amount = tx.amount.toBigDecimalOrNull() ?: return@mapNotNull null
            val date = Instant.ofEpochMilli(message.message.date).atZone(zone).toLocalDate()
            if (date.isBefore(earliest) || date.isAfter(today)) return@mapNotNull null
            val ref = reference(message.message.body)
            Payment(
                key(tx.payee), message.merchant.name.ifEmpty { tx.payee }, amount, date, message.id, message.message.date,
                ref?.second, ref?.let { it.first + " …" + it.second.filter { c -> c.isLetterOrDigit() }.takeLast(4) },
                message.category == MessageCategory.INVESTMENTS || MANDATE_WORDS.containsMatchIn(message.message.body)
            )
        }

        return payments.groupBy { it.key }.flatMap { (_, group) ->
            val split = splitEntries(group, today)
            val rest = group.filter { p -> split.none { p in it.used } }
            split.map { it.subscription } + listOfNotNull(subscription(rest, today))
        }.sortedWith(compareBy<Subscription> { it.nextDue }.thenBy { it.payeeKey }.thenBy { it.ordinal })
    }

    private class Split(val subscription: Subscription, val used: List<Payment>)

    /**
     * A payee charged several times in each cycle by separate standing orders (five SIP mandates of 2,000 on one day):
     * one entry per charge slot. A slot is a mandate id the messages quote, else the charge's place in the month. It
     * needs the same slot in two consecutive months; with a single month of history only identical amounts within one
     * hour that look like a mandate (keyword or investments) count. Ordinary payees never reach this: they need two or
     * more alike charges in a cycle.
     */
    private fun splitEntries(group: List<Payment>, today: LocalDate): List<Split> {
        if (group.size < 2) return emptyList()
        val onlyMonth = group.map { it.month }.distinct().size == 1
        val result = ArrayList<Split>()
        for (cluster in clusters(group)) {
            if (cluster.size < 2) continue
            result += splitCluster(cluster, onlyMonth, today)
        }
        return result
    }

    private fun clusters(group: List<Payment>): List<List<Payment>> {
        val sorted = group.sortedBy { it.amount }
        val out = ArrayList<MutableList<Payment>>()
        for (p in sorted) {
            val current = out.lastOrNull()
            if (current != null && p.amount <= current.first().amount.multiply(SAME_SIZE)) current += p else out += mutableListOf(p)
        }
        return out
    }

    private fun splitCluster(cluster: List<Payment>, onlyMonth: Boolean, today: LocalDate): List<Split> {
        val byMonth = cluster.groupBy { it.month }
        val idMode = cluster.all { it.ref != null } && byMonth.values.all { m -> m.map { it.ref }.distinct().size == m.size }
        val slotOf = HashMap<Payment, String>()
        for ((_, inMonth) in byMonth) {
            inMonth.sortedWith(compareBy<Payment> { it.time }.thenBy { it.messageId }).forEachIndexed { i, p ->
                slotOf[p] = if (idMode) p.ref!!.refKey() else (i + 1).toString()
            }
        }
        val slots = cluster.groupBy { slotOf.getValue(it) }
        val first = cluster.minWith(compareBy<Payment> { it.time }.thenBy { it.messageId })
        val keyAmount = first.amount.setScale(0, RoundingMode.HALF_UP).toPlainString()

        val runs = LinkedHashMap<String, List<Payment>>()
        if (onlyMonth) {
            val identical = cluster.all { it.amount.compareTo(cluster.first().amount) == 0 }
            val times = cluster.map { it.time }
            val sameHour = times.max() - times.min() <= HOUR_MS
            val looksLikeMandate = cluster.any { it.mandateLike }
            if (identical && sameHour && looksLikeMandate) slots.forEach { (slot, ps) -> if (ps.size == 1) runs[slot] = ps }
        } else {
            for ((slot, ps) in slots) {
                val run = latestRun(ps)
                if (run.size >= 2) runs[slot] = run
            }
        }
        if (runs.size < 2) return emptyList()

        val ordered = runs.entries.sortedWith(
            compareBy<Map.Entry<String, List<Payment>>> { e -> e.value.minOf { it.time } }
                .thenBy { e -> e.key.toIntOrNull() ?: Int.MAX_VALUE }.thenBy { it.key }
        )
        // An entry that has stopped is dropped before numbering, so "#n of k" stays true.
        val live = ordered.map { it.key to it.value.sortedBy { p -> p.time } }
            .filter { (_, run) -> !run.last().date.plusMonths(1).isBefore(today.minusDays(MAX_GAP)) }
        if (live.size < 2) return emptyList()
        return live.mapIndexed { i, (slot, run) ->
            val last = run.last()
            var due = last.date.plusMonths(1)
            while (due.isBefore(today)) due = due.plusMonths(1)
            Split(
                Subscription(
                    last.name, last.amount.setScale(0, RoundingMode.HALF_UP), last.date, due, run.size, last.messageId,
                    Cadence.MONTHLY, run.getOrNull(run.size - 2)?.amount?.setScale(0, RoundingMode.HALF_UP), run.first().date,
                    if (idMode) "r$slot" else "$keyAmount#${i + 1}", i + 1, live.size, if (idMode) last.refLabel.orEmpty() else ""
                ),
                run
            )
        }
    }

    /** The newest stretch of charges that come in consecutive calendar months. */
    private fun latestRun(ps: List<Payment>): List<Payment> {
        val perMonth = ps.groupBy { it.month }.toSortedMap(compareByDescending { it })
        val run = ArrayList<Payment>()
        var expected: Int? = null
        for ((month, inMonth) in perMonth) {
            if (expected != null && month != expected) break
            run += inMonth.maxBy { it.time }
            expected = month - 1
        }
        return run
    }

    private fun subscription(group: List<Payment>, today: LocalDate): Subscription? {
        // One payment per month: the latest, so a double charge does not look like two months.
        val perMonth = group.groupBy { it.date.year * 12 + it.date.monthValue }.map { (_, p) -> p.maxBy { it.date } }.sortedBy { it.date }
        if (perMonth.size < MIN_TIMES) return null

        val gaps = perMonth.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date) }
        if (gaps.any { it !in MIN_GAP..MAX_GAP }) return null

        val amounts = perMonth.map { it.amount }.sorted()
        val middle = amounts[amounts.size / 2]
        if (middle.signum() <= 0 || amounts.any { (it - middle).abs() > middle.multiply(SIZE_TOLERANCE) }) return null

        val last = perMonth.last()
        val averageGap = gaps.average().toLong()
        var next = last.date.plusDays(averageGap)
        // A subscription that has stopped is not "due"; give it a month's grace and then drop it.
        if (next.isBefore(today.minusDays(MAX_GAP))) return null
        while (next.isBefore(today)) next = next.plusMonths(1)
        val previous = perMonth[perMonth.size - 2]
        return Subscription(
            last.name, last.amount.setScale(0, RoundingMode.HALF_UP), last.date, next, perMonth.size, last.messageId,
            Cadence.MONTHLY, previous.amount.setScale(0, RoundingMode.HALF_UP), perMonth.first().date
        )
    }

    fun keyFor(payee: String) = key(payee)

    /** Splits a payee key from the part that tells its sibling entries apart. */
    const val VARIANT_SEPARATOR = "|"

    /** The payee part of a subscription key, whether or not it carries a variant. */
    fun payeeOfKey(key: String) = key.substringBefore(VARIANT_SEPARATOR)

    private val SAME_SIZE = BigDecimal("1.05")
    private const val HOUR_MS = 3_600_000L
    private val MANDATE_WORDS = Regex("\\b(mandate|sip|ecs|nach|umrn|auto[- ]?debit|standing instruction)\\b", RegexOption.IGNORE_CASE)
    private val REFERENCES = listOf(
        "UMRN" to Regex("\\bUMRN\\b[\\s:.\\-]*([A-Z0-9]{8,})", RegexOption.IGNORE_CASE),
        "SIP" to Regex("\\bSIP\\s*(?:id|ref(?:erence)?|no\\.?|number)[\\s:.\\-]*([A-Z0-9]{4,})", RegexOption.IGNORE_CASE),
        "Folio" to Regex("\\bfolio\\s*(?:no\\.?|number)?[\\s:.\\-]*([A-Z0-9/]{4,})", RegexOption.IGNORE_CASE),
        "Ref" to Regex("\\bref(?:erence)?\\s*(?:no\\.?|number|id)?[\\s:.\\-]*([A-Z0-9]{6,})", RegexOption.IGNORE_CASE)
    )
    private val NOT_ALNUM = Regex("[^a-z0-9]")

    private fun String.refKey() = lowercase().replace(NOT_ALNUM, "").takeLast(10)

    /** "UMRN" to the id the message quotes, if any (it has to carry a digit, so a plain word is never taken). */
    private fun reference(body: String): Pair<String, String>? {
        for ((label, regex) in REFERENCES) {
            val id = regex.find(body)?.groupValues?.get(1) ?: continue
            if (id.any { it.isDigit() }) return label to id
        }
        return null
    }

    private val NOT_PAYER = Regex("[^a-z@. ]+")
    private val SPACES = Regex("\\s+")

    private fun key(payee: String) = payee.lowercase().replace(NOT_PAYER, " ").replace(SPACES, " ").trim()

    private class Payment(
        val key: String, val name: String, val amount: BigDecimal, val date: LocalDate, val messageId: Long, val time: Long,
        val ref: String? = null, val refLabel: String? = null, val mandateLike: Boolean = false
    ) {
        val month: Int get() = date.year * 12 + date.monthValue - 1
    }
}
