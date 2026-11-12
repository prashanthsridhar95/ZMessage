package com.zyke.message.domain.search

import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.calendar.CalendarEvent
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.ledger.PaymentGroups
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.markets.StockDirectory
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MerchantLogo
import com.zyke.message.domain.model.MessageContent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.settings.SettingsIndex
import java.math.BigDecimal

/** Which chip along the top narrows to a kind of result. */
enum class SearchScope(val label: String) {
    ALL("All"), PAYMENTS("Payments"), ACCOUNTS("Accounts"), BILLS("Bills"), TAGS("Tags")
}

/** What a search result is, in the order the groups are shown. A kind with no [scope] only shows under All. */
enum class SearchKind(val label: String, val scope: SearchScope?) {
    MESSAGE("Payments & messages", SearchScope.PAYMENTS),
    HAND_MADE("Hand-made entries", SearchScope.PAYMENTS),
    ACCOUNT("Accounts & cards", SearchScope.ACCOUNTS),
    REMINDER("Bills & reminders", SearchScope.BILLS),
    GOAL("Goals & plans", SearchScope.BILLS),
    EVENT("Calendar", SearchScope.BILLS),
    TAG("Tags & notes", SearchScope.TAGS),
    STOCK("Stocks you watch", null),
    SETTING("Settings", null),
    PAGE("Jump to", null)
}

/** One result. [messageId] is set for messages and hand-made entries, so the app can open them. */
data class SearchHit(
    val kind: SearchKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val messageId: Long? = null,
    /** 0 is the best match. */
    val rank: Int = 0,
    /** For payments: when it happened, the amount as read, and whether money came in. Null for other kinds. */
    val date: Long? = null,
    val amount: String? = null,
    val credit: Boolean = false,
    val category: String? = null,
    val logo: MerchantLogo? = null,
    val imageKey: String? = null
)

/** The hits of one kind, at most the limit; [total] counts all that matched. */
data class SearchGroup(val kind: SearchKind, val hits: List<SearchHit>, val total: Int)

data class SearchResults(val groups: List<SearchGroup>) {
    val isEmpty: Boolean get() = groups.isEmpty()

    companion object {
        val EMPTY = SearchResults(emptyList())
    }
}

/** Everything that can be searched; the lists are the ones the app already holds. */
data class SearchSources(
    val messages: List<ClassifiedMessage> = emptyList(),
    val goals: List<Goal> = emptyList(),
    val reminders: List<Reminder> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val accounts: List<AccountSummary> = emptyList(),
    val watched: List<WatchSymbol> = emptyList(),
    /** Developer options are on: their settings can be found too. */
    val developerMode: Boolean = false,
    /** Demo mode is on: settings that are hidden there cannot be found either. */
    val demoMode: Boolean = false
)

/**
 * One search over messages, hand-made entries, goals, reminders, calendar events, accounts and watched stocks. A name
 * that is, or starts with, what was typed ranks before one that only contains it; an amount typed as "540" or "₹1,200"
 * finds payments of that amount.
 */
object GlobalSearch {
    const val DEFAULT_LIMIT = 5

    /**
     * [scope] narrows to one kind of result. Phrases such as "food this month" or ">5000" become filters on the payments;
     * when they match nothing, the whole query is searched as plain words instead.
     */
    fun search(
        query: String,
        sources: SearchSources,
        limit: Int = DEFAULT_LIMIT,
        scope: SearchScope = SearchScope.ALL,
        today: LocalDate = LocalDate.now(),
        zone: ZoneId = ZoneId.systemDefault()
    ): SearchResults {
        val parsed = SearchQueryParser.parse(query, today)
        if (parsed.isBlank) return SearchResults.EMPTY
        if (parsed.hasFilters) {
            val filtered = run(parsed, parsed.text, sources, limit, scope, zone)
            if (!filtered.isEmpty) return filtered
        }
        return run(null, query.trim(), sources, limit, scope, zone)
    }

    private fun run(
        filters: ParsedSearch?,
        needle: String,
        sources: SearchSources,
        limit: Int,
        scope: SearchScope,
        zone: ZoneId
    ): SearchResults {
        val amount = amountQuery(needle)
        fun wanted(kind: SearchKind) = scope == SearchScope.ALL || kind.scope == scope
        val groups = mutableListOf<SearchGroup?>()
        if (wanted(SearchKind.MESSAGE)) groups += group(SearchKind.MESSAGE, messageHits(needle, amount, sources.messages, false, filters, zone), limit)
        if (wanted(SearchKind.HAND_MADE)) groups += group(SearchKind.HAND_MADE, messageHits(needle, amount, sources.messages, true, filters, zone), limit)
        // Filters such as a month or a category only mean something for payments.
        if (filters == null) {
            if (wanted(SearchKind.ACCOUNT)) groups += group(SearchKind.ACCOUNT, accountHits(needle, amount, sources.accounts), limit)
            if (wanted(SearchKind.REMINDER)) groups += group(SearchKind.REMINDER, reminderHits(needle, amount, sources.reminders), limit)
            if (wanted(SearchKind.GOAL)) groups += group(SearchKind.GOAL, goalHits(needle, amount, sources.goals), limit)
            if (wanted(SearchKind.EVENT)) groups += group(SearchKind.EVENT, eventHits(needle, sources.events), limit)
            if (wanted(SearchKind.TAG)) groups += group(SearchKind.TAG, tagHits(needle, sources.messages), limit)
            if (wanted(SearchKind.STOCK)) groups += group(SearchKind.STOCK, stockHits(needle, sources.watched), limit)
            if (wanted(SearchKind.SETTING)) groups += group(SearchKind.SETTING, settingHits(needle, sources), limit)
            if (wanted(SearchKind.PAGE)) groups += group(SearchKind.PAGE, SearchPages.hits(needle), limit)
        }
        // A page the person named ("settings") comes first; any other page match is a footnote.
        return SearchResults(groups.filterNotNull().sortedBy { if (it.kind == SearchKind.PAGE && it.hits.first().rank <= 1) 0 else 1 })
    }

    private fun group(kind: SearchKind, hits: List<SearchHit>, limit: Int): SearchGroup? =
        if (hits.isEmpty()) null else SearchGroup(kind, hits.sortedBy { it.rank }.take(limit.coerceAtLeast(1)), hits.size)

    /** 0 when [text] is [needle], 1 when it or one of its words starts with it, 2 when it contains it; null otherwise. */
    internal fun rank(needle: String, text: String): Int? {
        if (text.isEmpty()) return null
        if (text.equals(needle, ignoreCase = true)) return 0
        if (text.startsWith(needle, ignoreCase = true)) return 1
        val at = text.indexOf(needle, ignoreCase = true)
        if (at < 0) return null
        return if (!text[at - 1].isLetterOrDigit()) 1 else 2
    }

    private fun best(needle: String, vararg texts: String?): Int? =
        texts.mapNotNull { it?.let { text -> rank(needle, text) } }.minOrNull()

    /** The typed digits of an amount ("540", "₹1,200", "Rs. 12.50"), or null when the query is not one. */
    internal fun amountQuery(query: String): String? {
        val cleaned = query.replace("₹", "").replace(Regex("(?i)\\brs\\.?"), "").replace(",", "").replace(" ", "")
        return cleaned.takeIf { it.isNotEmpty() && it.all { c -> c.isDigit() || c == '.' } && it.any { c -> c.isDigit() } }
    }

    /** 0 when [amount] equals what was typed, 1 when it begins with it, 2 when it contains it; null otherwise. */
    private fun amountRank(typed: String?, amount: String?): Int? {
        if (typed == null || amount == null) return null
        val plain = amount.replace(",", "").trim()
        val equal = runCatching { BigDecimal(plain).compareTo(BigDecimal(typed)) == 0 }.getOrDefault(false)
        return when {
            equal -> 0
            plain.startsWith(typed) -> 1
            typed.length >= MIN_AMOUNT_CONTAINS && plain.contains(typed) -> 2
            else -> null
        }
    }

    private fun amountOf(content: MessageContent): String? = when (content) {
        is MessageContent.Transaction -> content.amount
        is MessageContent.CardBill -> content.amount
        is MessageContent.ScheduledPayment -> content.amount
        else -> null
    }

    private fun messageHits(
        needle: String, amount: String?, messages: List<ClassifiedMessage>, hand: Boolean, filters: ParsedSearch?, zone: ZoneId
    ): List<SearchHit> {
        val groups = if (hand) PaymentGroups.NONE else PaymentGroups.of(messages)
        val byId = if (groups.isEmpty) null else messages.associateBy { it.id }
        val hits = messages.mapNotNull { found ->
            if (ManualEntries.isManual(found) != hand) return@mapNotNull null
            if (filters != null && !passes(found, filters, zone)) return@mapNotNull null
            val content = found.content
            val payee = (content as? MessageContent.Transaction)?.payee
            val rank = if (needle.isEmpty()) 0 else listOfNotNull(
                best(needle, found.merchant.name, payee),
                amountRank(amount, amountOf(content)),
                best(needle, found.category.displayString)?.plus(CATEGORY_PENALTY),
                found.tags.mapNotNull { rank(needle, it) }.minOrNull()?.plus(CATEGORY_PENALTY),
                found.note?.let { best(needle, it)?.plus(NOTE_PENALTY + 1) },
                if (FeedBuilder.matches(found, needle)) TEXT_RANK else null
            ).minOrNull() ?: return@mapNotNull null
            // A message that repeats a payment is found by its own text but shows, and opens, the payment whose row holds it.
            val m = groups.anchorOf(found.id)?.let { byId?.get(it) } ?: found
            val shown = m.merchant.name
            val mc = m.content
            val money = amountOf(mc)?.let { "₹$it" }
            val sub = listOfNotNull(m.category.displayString, money).joinToString(" · ")
            SearchHit(
                if (hand) SearchKind.HAND_MADE else SearchKind.MESSAGE, m.id.toString(), shown, sub,
                messageId = m.id, rank = rank, date = m.message.date, amount = amountOf(mc),
                credit = (mc as? MessageContent.Transaction)?.isReceived == true,
                category = m.category.displayString, logo = m.merchant.logo, imageKey = m.merchant.imageKey
            ) to m.message.date
        }.sortedWith(compareBy({ it.first.rank }, { -it.second })).map { it.first }
        // The payment and a repeat of it both matched: the better match stands for the one row.
        return if (groups.isEmpty) hits else hits.distinctBy { it.messageId }
    }

    /** Whether a message is inside the time span, category, type and amount bounds the query asked for. */
    private fun passes(m: ClassifiedMessage, f: ParsedSearch, zone: ZoneId): Boolean {
        if (f.category != null && m.category != f.category) return false
        if (f.smart != SmartFilter.ALL && !FeedBuilder.bucket(m, f.smart)) return false
        val from = f.from
        val to = f.to
        if (from != null && to != null) {
            val day = LocalDate.ofInstant(Instant.ofEpochMilli(m.message.date), zone)
            if (day.isBefore(from) || day.isAfter(to)) return false
        }
        if (f.minAmount != null || f.maxAmount != null) {
            val value = amountOf(m.content)?.replace(",", "")?.trim()?.toBigDecimalOrNull() ?: return false
            if (f.minAmount != null && value < f.minAmount) return false
            if (f.maxAmount != null && value > f.maxAmount) return false
        }
        return true
    }

    /** Each tag that matches, with how many payments carry it; and payments whose own note matches. */
    private fun tagHits(needle: String, messages: List<ClassifiedMessage>): List<SearchHit> {
        val tags = LinkedHashMap<String, Int>()
        val notes = mutableListOf<SearchHit>()
        for (m in messages) {
            m.tags.forEach { tags[it] = (tags[it] ?: 0) + 1 }
            val note = m.note ?: continue
            val rank = rank(needle, note) ?: continue
            notes += SearchHit(SearchKind.TAG, "note-${m.id}", note, m.merchant.name, messageId = m.id, rank = rank + NOTE_PENALTY, date = m.message.date)
        }
        val tagHits = tags.mapNotNull { (tag, count) ->
            val rank = rank(needle, tag) ?: return@mapNotNull null
            SearchHit(SearchKind.TAG, "tag-$tag", tag, if (count == 1) "1 payment" else "$count payments", rank = rank)
        }
        return tagHits + notes
    }

    private fun accountHits(needle: String, amount: String?, accounts: List<AccountSummary>): List<SearchHit> =
        accounts.mapNotNull { a ->
            val rank = listOfNotNull(
                best(needle, a.title, a.bankLabel, a.last4, a.kind.label),
                amountRank(amount, a.balance?.toPlainString())
            ).minOrNull() ?: return@mapNotNull null
            SearchHit(SearchKind.ACCOUNT, a.key, a.title, "${a.kind.label} ····${a.last4}", rank = rank)
        }

    private fun goalHits(needle: String, amount: String?, goals: List<Goal>): List<SearchHit> =
        goals.mapNotNull { g ->
            val rank = listOfNotNull(best(needle, g.name), amountRank(amount, g.target.toPlainString())).minOrNull()
                ?: return@mapNotNull null
            SearchHit(SearchKind.GOAL, g.id, g.name, "₹${g.saved.toPlainString()} of ₹${g.target.toPlainString()}", rank = rank)
        }

    private fun reminderHits(needle: String, amount: String?, reminders: List<Reminder>): List<SearchHit> =
        reminders.mapNotNull { r ->
            val rank = listOfNotNull(best(needle, r.title), amountRank(amount, r.amount?.toPlainString())).minOrNull()
                ?: return@mapNotNull null
            val sub = listOfNotNull(r.date.toString(), r.amount?.let { "₹${it.toPlainString()}" }).joinToString(" · ")
            SearchHit(SearchKind.REMINDER, r.id, r.title, sub, rank = rank)
        }

    private fun eventHits(needle: String, events: List<CalendarEvent>): List<SearchHit> =
        events.mapNotNull { e ->
            val rank = best(needle, e.title) ?: best(needle, e.note)?.plus(NOTE_PENALTY) ?: return@mapNotNull null
            SearchHit(SearchKind.EVENT, e.id, e.title, e.date.toString(), rank = rank)
        }

    /** A setting is found by its title, summary or a keyword; the hit's id is the setting's id, which names its page. */
    private fun settingHits(needle: String, sources: SearchSources): List<SearchHit> =
        if (needle.trim().length < MIN_SETTING_QUERY) emptyList() else SettingsIndex.search(needle, sources.developerMode, sources.demoMode).map { m ->
            SearchHit(SearchKind.SETTING, m.entry.id, m.entry.title, m.entry.group.title + " · " + m.entry.summary, rank = m.rank)
        }

    private fun stockHits(needle: String, watched: List<WatchSymbol>): List<SearchHit> =
        watched.mapNotNull { w ->
            val name = StockDirectory.search(w.symbol.substringBefore('.'), 1).firstOrNull { it.symbol == w.symbol }?.name
            val rank = best(needle, w.label, w.symbol, w.symbol.substringBefore('.'), name) ?: return@mapNotNull null
            SearchHit(SearchKind.STOCK, w.symbol, w.label, name ?: w.symbol, rank = rank)
        }

    private const val TEXT_RANK = 4
    private const val CATEGORY_PENALTY = 1
    private const val NOTE_PENALTY = 1
    private const val MIN_AMOUNT_CONTAINS = 3
    private const val MIN_SETTING_QUERY = 2
}
