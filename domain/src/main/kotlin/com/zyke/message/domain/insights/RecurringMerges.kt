package com.zyke.message.domain.insights

import java.math.BigDecimal
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * One payment the person says is reported under several names (the bank names the clearing house, the fund house
 * sends its own confirmation): [memberKeys] are folded into [primaryKey]. Keys are [Subscription.key]s.
 */
data class RecurringMerge(
    val primaryKey: String,
    val memberKeys: List<String>,
    val displayName: String? = null,
    val createdAt: Long = 0L
) {
    /** The primary first, then the members in the order given, without repeats. */
    val allKeys: List<String> get() = (listOf(primaryKey) + memberKeys).distinct()
}

/** Merges of repeating payments, applied in one place so nothing downstream sees the duplicates. */
object RecurringMerges {
    /**
     * Flattens the merges into disjoint groups: merges that share a key (a group merged into another, or an entry in two
     * merges) become one. The newest merge decides the primary and the name; order of [merges] does not matter.
     */
    fun normalize(merges: List<RecurringMerge>): List<RecurringMerge> {
        val usable = merges.filter { it.allKeys.size >= 2 }
        if (usable.isEmpty()) return emptyList()
        val ordered = usable.sortedWith(compareBy<RecurringMerge> { it.createdAt }.thenBy { it.primaryKey }.thenBy { it.memberKeys.joinToString("\u0000") })
        val parent = HashMap<String, String>()
        fun find(k: String): String {
            var root = k
            while (true) { val p = parent[root] ?: break; if (p == root) break; root = p }
            return root
        }
        for (m in ordered) {
            val keys = m.allKeys
            keys.forEach { parent.putIfAbsent(it, it) }
            val head = find(keys.first())
            for (k in keys.drop(1)) { val r = find(k); if (r != head) parent[r] = head }
        }
        val byRoot = LinkedHashMap<String, MutableList<RecurringMerge>>()
        for (m in ordered) byRoot.getOrPut(find(m.primaryKey)) { ArrayList() } += m
        return byRoot.values.map { group ->
            val newest = group.last()
            val keys = LinkedHashSet<String>()
            keys += newest.allKeys
            group.asReversed().forEach { keys += it.allKeys }
            val name = group.asReversed().firstNotNullOfOrNull { it.displayName?.trim()?.takeIf { n -> n.isNotEmpty() } }
            RecurringMerge(newest.primaryKey, keys.filter { it != newest.primaryKey }, name, newest.createdAt)
        }.sortedBy { it.primaryKey }
    }

    /** Every key in the group [key] belongs to (the key itself when it is in none): what a cancel or confirm applies to. */
    fun groupOf(key: String, merges: List<RecurringMerge>): Set<String> =
        normalize(merges).firstOrNull { key in it.allKeys }?.allKeys?.toSet() ?: setOf(key)

    /** The key that stands for [key]: its group's primary, or itself. Planned items and charges match on this. */
    fun canonicalKey(key: String, merges: List<RecurringMerge>): String =
        normalize(merges).firstOrNull { key in it.allKeys }?.primaryKey ?: key

    /** Adds [added] to [existing] (flattening chains), as one new list. */
    fun merged(existing: List<RecurringMerge>, added: List<RecurringMerge>): List<RecurringMerge> = normalize(existing + added)

    /** Takes the group of [key] apart again. */
    fun unmerged(existing: List<RecurringMerge>, key: String): List<RecurringMerge> =
        normalize(existing).filter { key !in it.allKeys }

    /** Renames the group of [key]; a blank name goes back to the primary's own. */
    fun renamed(existing: List<RecurringMerge>, key: String, name: String, now: Long): List<RecurringMerge> {
        val groups = normalize(existing)
        return groups.map { if (key in it.allKeys) it.copy(displayName = name.trim().ifEmpty { null }, createdAt = maxOf(it.createdAt, now)) else it }
    }

    /**
     * Gives every key of a group the state of its primary (a state kept for the group's keys, such as cancelled): a
     * member's own leftover state is dropped, a primary's is copied to the members.
     */
    fun <V> harmonize(states: Map<String, V>, merges: List<RecurringMerge>): Map<String, V> {
        val groups = normalize(merges)
        if (groups.isEmpty() || states.isEmpty()) return states
        val out = LinkedHashMap(states)
        for (g in groups) {
            val value = states[g.primaryKey]
            g.allKeys.forEach { out.remove(it) }
            if (value != null) g.allKeys.forEach { out[it] = value }
        }
        return out
    }

    /**
     * Folds merged entries into one each. The merged entry is the primary's own payment seen twice, so it keeps the
     * primary's amount (never a sum); it is due at the earliest of the members' dates, has the longest run and the
     * latest charge. Members that are gone are dropped; a vanished primary is replaced by the first member left. The
     * result does not depend on the order of the input and applying it again changes nothing.
     */
    fun apply(subscriptions: List<Subscription>, merges: List<RecurringMerge>): List<Subscription> {
        val groups = normalize(merges)
        if (groups.isEmpty() || subscriptions.isEmpty()) return subscriptions
        val owner = HashMap<String, RecurringMerge>()
        groups.forEach { g -> g.allKeys.forEach { owner[it] = g } }
        val folded = HashMap<RecurringMerge, MutableList<Subscription>>()
        val plain = ArrayList<Subscription>()
        for (s in subscriptions.distinctBy { it.key }) {
            val g = owner[s.key]
            if (g == null) plain += s else folded.getOrPut(g) { ArrayList() } += s
        }
        val result = ArrayList<Subscription>(plain)
        for ((g, present) in folded) {
            val order = g.allKeys
            val sorted = present.sortedBy { order.indexOf(it.key) }
            result += fold(g, sorted)
        }
        return result.sortedWith(compareBy<Subscription> { it.nextDue }.thenBy { it.payeeKey }.thenBy { it.ordinal }.thenBy { it.key })
    }

    private fun fold(group: RecurringMerge, present: List<Subscription>): Subscription {
        val name = group.displayName.orEmpty()
        val primary = present.first()
        if (present.size == 1) {
            // Already folded (applied twice), or the others are gone: nothing to count differently.
            return if (primary.isMerged) primary.copy(alias = name.ifEmpty { primary.alias }) else primary.copy(alias = name)
        }
        val earliest = present.minWith(compareBy<Subscription> { it.nextDue }.thenBy { present.indexOf(it) })
        val latest = present.maxWith(compareBy<Subscription> { it.lastPaid }.thenByDescending { present.indexOf(it) })
        val others = present.drop(1)
        val also = (primary.alsoSeenAs + others.flatMap { listOf(display(it)) + it.alsoSeenAs }).distinct().filter { it != display(primary) }
        val references = present.map { it.reference }.filter { it.isNotEmpty() }.distinct()
        return primary.copy(
            lastPaid = latest.lastPaid,
            messageId = latest.messageId,
            nextDue = earliest.nextDue,
            cadence = earliest.cadence,
            times = present.maxOf { it.times },
            firstPaid = present.mapNotNull { it.firstPaid }.minOrNull(),
            reference = references.joinToString(" · "),
            alias = name,
            alsoSeenAs = also,
            mergedKeys = (primary.mergedKeys + others.flatMap { listOf(it.key) + it.mergedKeys }).distinct()
        )
    }

    private fun display(s: Subscription): String = (s.title + (if (s.ofCount > 1 && s.alias.isEmpty()) " " + s.suffix else "")).trim()

    /** What a merge of [selected] would count as: the primary's amount once, and what it comes to in a year. */
    data class Preview(val amount: BigDecimal, val yearly: BigDecimal, val wouldBeYearly: BigDecimal, val amountsDiffer: Boolean)

    fun preview(primary: Subscription, others: List<Subscription>): Preview {
        val all = listOf(primary) + others
        val differ = all.any { (it.amount - primary.amount).abs() > BigDecimal.ONE }
        return Preview(primary.amount, primary.cadence.yearly(primary.amount), all.fold(BigDecimal.ZERO) { sum, s -> sum + s.cadence.yearly(s.amount) }, differ)
    }

    /** The entry [chosenName] lives under: keeps the typed name, else the picked entry's title. */
    fun build(primary: Subscription, others: List<Subscription>, displayName: String?, now: Long): RecurringMerge =
        RecurringMerge(primary.key, others.map { it.key }.filter { it != primary.key }.distinct(), displayName?.trim()?.takeIf { it.isNotEmpty() }, now)

    /** Entries that look like the same payment as [target]: same amount (within 1) and next date within 2 days. */
    fun likelySame(target: Subscription, candidates: List<Subscription>): List<Subscription> =
        candidates.filter { it.key != target.key && similar(target, it) }

    fun similar(a: Subscription, b: Subscription): Boolean =
        (a.amount - b.amount).abs() <= BigDecimal.ONE && kotlin.math.abs(ChronoUnit.DAYS.between(a.nextDue, b.nextDue)) <= DAY_WINDOW

    private const val DAY_WINDOW = 2L

    /** A proposed pairing of one entry from each side. */
    data class Pairing(val left: Subscription, val right: Subscription)

    /** The two sides of [selected]: aggregator-named entries against the rest, else the biggest payee against the rest. */
    fun sides(selected: List<Subscription>): kotlin.Pair<List<Subscription>, List<Subscription>> {
        val alias = selected.filter { MergeAliases.looksLikeAggregator(it.payee) }
        if (alias.isNotEmpty() && alias.size < selected.size) return alias to selected - alias.toSet()
        val biggest = selected.groupBy { it.payeeKey }.maxByOrNull { it.value.size }?.key ?: return selected to emptyList()
        val left = selected.filter { it.payeeKey == biggest }
        return left to selected - left.toSet()
    }

    /**
     * Pairs entries of [left] with entries of [right] one to one: closest next date and amount first, then by order.
     * Entries without a partner are not in the result.
     */
    fun autoPair(left: List<Subscription>, right: List<Subscription>): List<Pairing> {
        val candidates = ArrayList<Triple<Long, BigDecimal, kotlin.Pair<Int, Int>>>()
        left.forEachIndexed { i, a ->
            right.forEachIndexed { j, b ->
                candidates += Triple(kotlin.math.abs(ChronoUnit.DAYS.between(a.nextDue, b.nextDue)), (a.amount - b.amount).abs(), i to j)
            }
        }
        val sorted = candidates.sortedWith(compareBy<Triple<Long, BigDecimal, kotlin.Pair<Int, Int>>> { it.second }.thenBy { it.first }.thenBy { it.third.first }.thenBy { it.third.second })
        val usedL = HashSet<Int>()
        val usedR = HashSet<Int>()
        val pairs = ArrayList<Pairing>()
        for ((_, _, ij) in sorted) {
            if (ij.first in usedL || ij.second in usedR) continue
            usedL += ij.first; usedR += ij.second
            pairs += Pairing(left[ij.first], right[ij.second])
        }
        return pairs.sortedBy { left.indexOf(it.left) }
    }
}

/** Names that stand for a clearing house, a payment aggregator or a bank debit rather than the brand paid. */
object MergeAliases {
    private val WORDS = listOf(
        "indian clearing corp", "indian clearing", "icc", "nach", "npci", "billdesk", "razorpay", "payu", "cashfree", "ccavenue",
        "amc", "mutual fund", "clearing", "ecs", "autopay", "upi mandate", "mandate", "bse star", "nse clearing"
    )
    private val PATTERNS = WORDS.map { Regex("\\b" + Regex.escape(it) + "\\b") }

    fun looksLikeAggregator(name: String): Boolean {
        val lower = name.lowercase()
        return PATTERNS.any { it.containsMatchIn(lower) }
    }
}

/** A conservative "is this the same payment?" question for two entries. */
data class MergeSuggestion(val first: Subscription, val second: Subscription) {
    /** Stable id for "No, keep separate". */
    val id: String get() = listOf(first.key, second.key).sorted().joinToString("&")
    val keys: List<String> get() = listOf(first.key, second.key)
}

object MergeSuggestions {
    private const val MAX_HINTS = 3
    private const val DAY_WINDOW = 2
    private const val EXACT_MONTHS = 3

    /**
     * Pairs of entries under different payees that charge the same amount (within ₹1) on about the same day of the
     * month, at least two months in a row, when one name looks like an aggregator or a clearing house, or when amount
     * and day match exactly for three months or more. Each entry is in at most one suggestion; at most three.
     */
    fun find(subscriptions: List<Subscription>, dismissed: Set<String>): List<MergeSuggestion> {
        val list = subscriptions.distinctBy { it.key }
        val found = ArrayList<Pair<Int, MergeSuggestion>>()
        for (i in list.indices) for (j in i + 1 until list.size) {
            val a = list[i]
            val b = list[j]
            if (a.payeeKey == b.payeeKey || a.cadence != Cadence.MONTHLY || b.cadence != Cadence.MONTHLY) continue
            if (a.times < 2 || b.times < 2) continue
            val amountGap = (a.amount - b.amount).abs()
            if (amountGap > BigDecimal.ONE) continue
            val dayGap = dayGap(a.lastPaid, b.lastPaid)
            val dueGap = kotlin.math.abs(ChronoUnit.DAYS.between(a.nextDue, b.nextDue))
            if (dayGap > DAY_WINDOW || dueGap > DAY_WINDOW) continue
            val aliasLike = MergeAliases.looksLikeAggregator(a.payee) || MergeAliases.looksLikeAggregator(b.payee)
            val exact = amountGap.signum() == 0 && dayGap == 0 && a.times >= EXACT_MONTHS && b.times >= EXACT_MONTHS
            if (!aliasLike && !exact) continue
            val s = MergeSuggestion(a, b)
            if (s.id in dismissed) continue
            found += (amountGap.multiply(BigDecimal(100)).toInt() + dayGap * 10 + dueGap.toInt()) to s
        }
        val used = HashSet<String>()
        val out = ArrayList<MergeSuggestion>()
        for ((_, s) in found.sortedWith(compareBy<Pair<Int, MergeSuggestion>> { it.first }.thenBy { it.second.id })) {
            if (s.first.key in used || s.second.key in used) continue
            used += s.first.key; used += s.second.key
            out += s
            if (out.size == MAX_HINTS) break
        }
        return out
    }

    private fun dayGap(a: LocalDate, b: LocalDate): Int = kotlin.math.abs(a.dayOfMonth - b.dayOfMonth)
}
