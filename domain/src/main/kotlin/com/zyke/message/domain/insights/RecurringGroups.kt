package com.zyke.message.domain.insights

import java.math.BigDecimal
import java.time.LocalDate

/**
 * A set of DIFFERENT repeating payments the person files together because they go to the same provider (four SIPs
 * to one fund house). Unlike a [RecurringMerge] (one payment under two names, counted once) every member still counts
 * on its own: a group is only a way to show the entries, never to add or drop any. [memberKeys] are
 * [Subscription.key]s, matched against an entry's own key or the keys merged into it.
 */
data class RecurringGroup(
    val id: String,
    val name: String,
    val memberKeys: List<String>,
    val createdAt: Long = 0L
)

/** One row of a grouped list: an entry on its own, or a group with its members and what they come to together. */
sealed interface RecurringListItem<out T> {
    /** A stable key for lazy lists; a group's can never equal an entry's key. */
    val listKey: String

    data class Single<T>(val item: T, val subscription: Subscription) : RecurringListItem<T> {
        override val listKey: String get() = subscription.key
    }

    data class Group<T>(
        val group: RecurringGroup,
        val members: List<T>,
        val subscriptions: List<Subscription>,
        /** What the members that count come to in a month and in a year; all of them when none counts. */
        val monthly: BigDecimal,
        val yearly: BigDecimal,
        val activeCount: Int,
        val pausedCount: Int,
        val cancelledCount: Int,
        /** The earliest next charge among the members that count (all of them when none counts). */
        val nextDue: LocalDate
    ) : RecurringListItem<T> {
        override val listKey: String get() = RecurringGroups.listKey(group.id)
        val count: Int get() = members.size
        val name: String get() = group.name

        /** True when no member is active, so the totals are shown muted. */
        val dormant: Boolean get() = activeCount == 0

        /** "2 active · 1 cancelled"; empty when everything is active. */
        val stateSummary: String
            get() = listOf(activeCount to "active", pausedCount to "paused", cancelledCount to "cancelled")
                .filter { it.first > 0 }.let { parts -> if (parts.size == 1 && pausedCount == 0 && cancelledCount == 0) "" else parts.joinToString(" · ") { "${it.first} ${it.second}" } }
    }
}

/** What a provider-word suggestion proposes: file [keys] under [name]. */
data class GroupSuggestion(val id: String, val name: String, val keys: List<String>, val members: List<Subscription>)

/** Groups of repeating payments: pure functions over the person's [RecurringGroup]s, display only. */
object RecurringGroups {
    private const val GROUP_PREFIX = "group:"
    private const val MAX_SUGGESTIONS = 3

    fun listKey(groupId: String): String = GROUP_PREFIX + groupId

    fun isGroupKey(key: String): Boolean = key.startsWith(GROUP_PREFIX)

    fun groupIdOf(listKey: String): String? = if (isGroupKey(listKey)) listKey.removePrefix(GROUP_PREFIX) else null

    /** All the keys an entry answers to: its own and the ones merged into it. */
    fun keysOf(s: Subscription): List<String> = listOf(s.key) + s.mergedKeys

    /** Groups [subscriptions] (already merged); see [arrange]. */
    fun apply(subscriptions: List<Subscription>, groups: List<RecurringGroup>): List<RecurringListItem<Subscription>> =
        arrange(subscriptions, groups, { it })

    /**
     * Turns a list into rows. A member is found by its entry's current key, or by a key merged into it; a group takes
     * the entry's place in the list at the position of its first member. Members that are gone drop out; a group with
     * none shows nothing and one with a single member shows that entry plainly (the group stays stored and comes back
     * when a second member appears). An entry in two groups belongs to the first group in [groups]. Nothing is added
     * or removed: every item of [items] is in exactly one row.
     */
    fun <T> arrange(
        items: List<T>,
        groups: List<RecurringGroup>,
        subscriptionOf: (T) -> Subscription,
        stateOf: (T) -> SubscriptionState = { SubscriptionState.ACTIVE }
    ): List<RecurringListItem<T>> {
        if (groups.isEmpty()) return items.map { RecurringListItem.Single(it, subscriptionOf(it)) }
        val ownerOf = arrayOfNulls<RecurringGroup>(items.size)
        val members = LinkedHashMap<RecurringGroup, List<T>>()
        val keys = items.map { keysOf(subscriptionOf(it)) }
        for (group in groups.distinctBy { it.id }) {
            val wanted = group.memberKeys.toHashSet()
            val found = items.indices.filter { ownerOf[it] == null && keys[it].any { k -> k in wanted } }
            // A group of one is not shown as a group; its entry stays free for a later group.
            if (found.size < 2) continue
            found.forEach { ownerOf[it] = group }
            members[group] = found.map { items[it] }
        }
        val out = ArrayList<RecurringListItem<T>>(items.size)
        val emitted = HashSet<RecurringGroup>()
        items.forEachIndexed { index, item ->
            val group = ownerOf[index]
            if (group == null) out += RecurringListItem.Single(item, subscriptionOf(item))
            else if (emitted.add(group)) out += build(group, members.getValue(group), subscriptionOf, stateOf)
        }
        return out
    }

    private fun <T> build(group: RecurringGroup, members: List<T>, subscriptionOf: (T) -> Subscription, stateOf: (T) -> SubscriptionState): RecurringListItem.Group<T> {
        val subs = members.map(subscriptionOf)
        val states = members.map(stateOf)
        val counting = members.indices.filter { states[it] == SubscriptionState.ACTIVE }.ifEmpty { members.indices.toList() }
        return RecurringListItem.Group(
            group, members, subs,
            monthly = counting.fold(BigDecimal.ZERO) { sum, i -> sum + subs[i].cadence.monthly(subs[i].amount) },
            yearly = counting.fold(BigDecimal.ZERO) { sum, i -> sum + subs[i].cadence.yearly(subs[i].amount) },
            activeCount = states.count { it == SubscriptionState.ACTIVE },
            pausedCount = states.count { it == SubscriptionState.PAUSED },
            cancelledCount = states.count { it == SubscriptionState.CANCELLED },
            nextDue = counting.minOf { subs[it].nextDue }
        )
    }

    /**
     * The keys a "cancel all in this group" marks: every key of every member that is not cancelled yet, with the keys
     * merged into each (a merged entry stands for all its names). Undo clears exactly these, so a member the person
     * had already cancelled stays as it was.
     */
    fun <T> cancellableKeys(group: RecurringListItem.Group<T>, stateOf: (T) -> SubscriptionState): List<String> =
        group.members.indices.filter { stateOf(group.members[it]) != SubscriptionState.CANCELLED }
            .flatMap { keysOf(group.subscriptions[it]) }.distinct()

    /** Every key of every member, merged ones included. */
    fun allKeys(group: RecurringListItem.Group<*>): List<String> = group.subscriptions.flatMap { keysOf(it) }.distinct()

    // --- changing the stored groups; each returns the whole new list, so one change is one write and one Undo ---

    /** Takes [keys] out of every group (a group left with nothing is removed), so an entry is never in two. */
    private fun without(groups: List<RecurringGroup>, keys: Collection<String>): List<RecurringGroup> {
        if (keys.isEmpty()) return groups
        val gone = keys.toHashSet()
        return groups.mapNotNull { g ->
            val kept = g.memberKeys.filter { it !in gone }
            if (kept.size == g.memberKeys.size) g else if (kept.isEmpty()) null else g.copy(memberKeys = kept)
        }
    }

    /** A new group of [keys]; they leave any group they were in. */
    fun created(groups: List<RecurringGroup>, id: String, name: String, keys: List<String>, now: Long): List<RecurringGroup> {
        val distinct = keys.distinct()
        if (distinct.isEmpty()) return groups
        return without(groups, distinct) + RecurringGroup(id, name.trim(), distinct, now)
    }

    /** Adds [keys] to the group [id], taking them out of the group they were in. An unknown id changes nothing. */
    fun added(groups: List<RecurringGroup>, id: String, keys: List<String>): List<RecurringGroup> {
        if (groups.none { it.id == id }) return groups
        val distinct = keys.distinct()
        return without(groups, distinct.filter { k -> groups.firstOrNull { it.id == id }?.memberKeys?.contains(k) != true })
            .map { if (it.id == id) it.copy(memberKeys = (it.memberKeys + distinct).distinct()) else it }
    }

    /** Takes one entry (all its [keys]) out of its group. */
    fun removed(groups: List<RecurringGroup>, keys: Collection<String>): List<RecurringGroup> = without(groups, keys)

    fun ungrouped(groups: List<RecurringGroup>, id: String): List<RecurringGroup> = groups.filter { it.id != id }

    fun renamed(groups: List<RecurringGroup>, id: String, name: String): List<RecurringGroup> =
        name.trim().let { n -> if (n.isEmpty()) groups else groups.map { if (it.id == id) it.copy(name = n) else it } }

    /** Drops duplicates (same id), empty groups and repeated keys across groups (first wins): what is stored. */
    fun normalize(groups: List<RecurringGroup>): List<RecurringGroup> {
        val seen = HashSet<String>()
        return groups.distinctBy { it.id }.mapNotNull { g ->
            val keys = g.memberKeys.filter { seen.add(it) }
            if (keys.isEmpty() || g.name.isBlank()) null else g.copy(memberKeys = keys)
        }
    }

    // --- naming ---

    private class Provider(val display: String, val phrases: List<String>, val strong: Boolean)

    // Fund houses with their own names are "strong"; names that also belong to banks, insurers and utilities need a fund word beside them.
    private val PROVIDERS = listOf(
        Provider("Mahindra Manulife", listOf("mahindra manulife"), true),
        Provider("Parag Parikh", listOf("parag parikh", "ppfas"), true),
        Provider("Nippon India", listOf("nippon"), true),
        Provider("Motilal Oswal", listOf("motilal oswal"), true),
        Provider("Mirae Asset", listOf("mirae"), true),
        Provider("Edelweiss", listOf("edelweiss"), true),
        Provider("ICICI Prudential", listOf("icici prudential", "icici pru"), true),
        Provider("Aditya Birla Sun Life", listOf("aditya birla", "absl"), true),
        Provider("HDFC", listOf("hdfc"), false),
        Provider("SBI", listOf("sbi"), false),
        Provider("Axis", listOf("axis"), false),
        Provider("Kotak", listOf("kotak"), false),
        Provider("UTI", listOf("uti"), false),
        Provider("DSP", listOf("dsp"), false),
        Provider("Tata", listOf("tata"), false),
        Provider("Bajaj", listOf("bajaj"), false),
        Provider("Quant", listOf("quant"), false)
    )
    private val PROVIDER_PATTERNS = PROVIDERS.map { p -> p to p.phrases.map { Regex("(^| )" + Regex.escape(it) + "( |$)") } }
    private val FUND_WORDS = setOf("fund", "mutual", "sip", "amc", "growth", "reg", "regular", "direct", "scheme", "elss", "nifty", "sensex", "index", "cap", "equity", "idcw", "mf")
    private val STOP_WORDS = setOf("fund", "mutual", "sip", "reg", "regular", "direct", "growth", "plan", "idcw", "scheme", "the", "ltd", "limited", "mf", "of", "and", "amc")
    private val NON_WORD = Regex("[^a-z0-9]+")
    private val NUMBERING = Regex("#\\s*\\d+(\\s+of\\s+\\d+)?")

    private fun words(name: String): List<String> =
        NON_WORD.replace(NUMBERING.replace(name.lowercase(), " "), " ").trim().split(' ').filter { it.isNotEmpty() }

    private fun namesOf(s: Subscription): List<String> = listOf(s.payee) + s.alsoSeenAs

    private fun providerOf(s: Subscription): Provider? {
        for (name in namesOf(s)) {
            val w = words(name)
            val joined = w.joinToString(" ")
            for ((p, patterns) in PROVIDER_PATTERNS) {
                if (patterns.none { it.containsMatchIn(joined) }) continue
                if (p.strong || w.any { it in FUND_WORDS }) return p
            }
        }
        return null
    }

    private fun title(words: List<String>): String = words.joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

    /** The first two meaningful words of an entry's payee, when it is not a clearing house; the "provider phrase". */
    private fun leadingPhrase(s: Subscription): String? {
        if (MergeAliases.looksLikeAggregator(s.payee)) return null
        val w = words(s.payee).filter { it !in STOP_WORDS }
        if (w.size < 2 || w[0].length < 3 || w[1].length < 3) return null
        return w.take(2).joinToString(" ")
    }

    /** "SIP" when the members read like fund instalments, else "payment": what to call a group's members. */
    fun unitName(entries: List<Subscription>): String {
        val fundLike = entries.count { e ->
            providerOf(e) != null || namesOf(e).any { n -> words(n).any { it == "fund" || it == "sip" || it == "mutual" } || MergeAliases.looksLikeAggregator(n) }
        }
        return if (entries.isNotEmpty() && fundLike * 2 >= entries.size) "SIP" else "payment"
    }

    /**
     * A name to prefill for a group of [entries]: the fund house most of them belong to ("Mahindra Manulife Fund"),
     * else the leading words they all share, else empty.
     */
    fun commonName(entries: List<Subscription>): String {
        if (entries.isEmpty()) return ""
        val providers = entries.mapNotNull { providerOf(it) }
        val best = providers.groupBy { it.display }.maxByOrNull { it.value.size }
        if (best != null && (best.value.size >= 2 || entries.size == 1)) return best.key + " Fund"
        val lists = entries.filter { !MergeAliases.looksLikeAggregator(it.payee) }.map { words(it.payee) }
        if (lists.size < 2) return ""
        var shared = lists.first()
        for (l in lists.drop(1)) shared = shared.zip(l).takeWhile { it.first == it.second }.map { it.first }
        val trimmed = shared.filter { it !in STOP_WORDS }.take(3)
        return if (trimmed.isEmpty() || trimmed.joinToString("").length < 3) "" else title(trimmed)
    }

    /**
     * Names that make [entries] read as parts of the group [groupName] ("Mahindra Manulife Fund · Manufacturing"): the
     * words of each entry's title that the group name does not already say. An entry with nothing left, or already
     * starting with the group name, keeps its title. By entry key.
     */
    fun matchingNames(groupName: String, entries: List<Subscription>): Map<String, String> {
        val said = words(groupName).toHashSet()
        val out = LinkedHashMap<String, String>()
        for (e in entries) {
            val title = e.title.trim()
            if (title.lowercase().startsWith(groupName.trim().lowercase())) continue
            val rest = title.split(' ').filter { w -> w.isNotBlank() && words(w).let { it.isNotEmpty() && it.none { x -> x in said || x in STOP_WORDS } } }
            val tail = rest.joinToString(" ")
            out[e.key] = if (tail.isEmpty()) title else groupName.trim() + " · " + tail
        }
        return out
    }

    /**
     * Groups worth proposing: entries (two or more) that belong to one fund house (a known name, with a fund word
     * where the name is also a bank's or insurer's), or that share the first two words of their payee. At most three,
     * each entry in at most one, none that is already in a group the list shows, none the person said no to
     * ([dismissed] holds [GroupSuggestion.id]s).
     */
    fun suggest(subscriptions: List<Subscription>, groups: List<RecurringGroup> = emptyList(), dismissed: Set<String> = emptySet()): List<GroupSuggestion> {
        val entries = subscriptions.distinctBy { it.key }
        val grouped = arrange(entries, groups, { it }).flatMap { (it as? RecurringListItem.Group)?.subscriptions.orEmpty() }.map { it.key }.toHashSet()
        val free = entries.filter { it.key !in grouped }
        val byProvider = LinkedHashMap<String, MutableList<Subscription>>()
        val unknown = ArrayList<Subscription>()
        for (s in free) {
            val p = providerOf(s)
            if (p != null) byProvider.getOrPut("provider-" + p.display.lowercase().replace(' ', '-')) { ArrayList() } += s else unknown += s
        }
        val clusters = ArrayList<Triple<String, String, List<Subscription>>>()
        for ((id, list) in byProvider) {
            if (list.size < 2) continue
            val name = PROVIDERS.first { "provider-" + it.display.lowercase().replace(' ', '-') == id }.display + " Fund"
            clusters += Triple("group-suggest:$id", name, list)
        }
        val byPhrase = LinkedHashMap<String, MutableList<Subscription>>()
        for (s in unknown) leadingPhrase(s)?.let { byPhrase.getOrPut(it) { ArrayList() } += s }
        for ((phrase, list) in byPhrase) {
            if (list.size < 2) continue
            clusters += Triple("group-suggest:phrase-" + phrase.replace(' ', '-'), title(phrase.split(' ')), list)
        }
        return clusters.filter { it.first !in dismissed }
            .sortedWith(compareByDescending<Triple<String, String, List<Subscription>>> { it.third.size }.thenBy { it.second })
            .take(MAX_SUGGESTIONS)
            .map { (id, name, list) -> GroupSuggestion(id, name, list.map { it.key }, list) }
    }
}

/** The person's own names for entries, by [Subscription.key]; any entry can have one, merged or not. */
object RecurringNames {
    /**
     * Gives each entry whose key (or a key merged into it) has a name that name, in place of the payee and of the
     * "#2 of 4" suffix (the entry keeps its ordinal, so its key does not change). Names of vanished entries are
     * ignored; blank names are no names.
     */
    fun apply(subscriptions: List<Subscription>, names: Map<String, String>): List<Subscription> {
        if (names.isEmpty() || subscriptions.isEmpty()) return subscriptions
        return subscriptions.map { s ->
            val own = names[s.key]?.trim()?.takeIf { it.isNotEmpty() }
                ?: s.mergedKeys.firstNotNullOfOrNull { k -> names[k]?.trim()?.takeIf { it.isNotEmpty() } }
            if (own == null) s else s.copy(alias = own)
        }
    }

    /** [names] with [key] named [name]; a blank name removes it. */
    fun set(names: Map<String, String>, key: String, name: String): Map<String, String> {
        val trimmed = name.trim()
        return if (trimmed.isEmpty()) names - key else names + (key to trimmed)
    }

    /** [names] without any of [keys] (an entry and the keys merged into it). */
    fun reset(names: Map<String, String>, keys: Collection<String>): Map<String, String> = names - keys.toSet()
}
