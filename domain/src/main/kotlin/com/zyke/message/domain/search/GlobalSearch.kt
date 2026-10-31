package com.zyke.message.domain.search

import com.zyke.message.domain.accounts.AccountSummary
import com.zyke.message.domain.calendar.CalendarEvent
import com.zyke.message.domain.feed.FeedBuilder
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.ledger.ManualEntries
import com.zyke.message.domain.markets.StockDirectory
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.model.MessageContent
import com.zyke.message.domain.reminders.Reminder
import java.math.BigDecimal

/** What a search result is, in the order the groups are shown. */
enum class SearchKind(val label: String) {
    MESSAGE("Messages"),
    HAND_MADE("Hand-made entries"),
    ACCOUNT("Accounts and cards"),
    GOAL("Goals"),
    REMINDER("Reminders"),
    EVENT("Calendar"),
    STOCK("Stocks you watch")
}

/** One result. [messageId] is set for messages and hand-made entries, so the app can open them. */
data class SearchHit(
    val kind: SearchKind,
    val id: String,
    val title: String,
    val subtitle: String,
    val messageId: Long? = null,
    /** 0 is the best match. */
    val rank: Int = 0
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
    val watched: List<WatchSymbol> = emptyList()
)

/**
 * One search over messages, hand-made entries, goals, reminders, calendar events, accounts and watched stocks. A name
 * that is, or starts with, what was typed ranks before one that only contains it; an amount typed as "540" or "₹1,200"
 * finds payments of that amount.
 */
object GlobalSearch {
    const val DEFAULT_LIMIT = 5

    fun search(query: String, sources: SearchSources, limit: Int = DEFAULT_LIMIT): SearchResults {
        val needle = query.trim()
        if (needle.isEmpty()) return SearchResults.EMPTY
        val amount = amountQuery(needle)
        val groups = listOf(
            group(SearchKind.MESSAGE, messageHits(needle, amount, sources.messages, hand = false), limit),
            group(SearchKind.HAND_MADE, messageHits(needle, amount, sources.messages, hand = true), limit),
            group(SearchKind.ACCOUNT, accountHits(needle, amount, sources.accounts), limit),
            group(SearchKind.GOAL, goalHits(needle, amount, sources.goals), limit),
            group(SearchKind.REMINDER, reminderHits(needle, amount, sources.reminders), limit),
            group(SearchKind.EVENT, eventHits(needle, sources.events), limit),
            group(SearchKind.STOCK, stockHits(needle, sources.watched), limit)
        )
        return SearchResults(groups.filterNotNull())
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

    private fun messageHits(needle: String, amount: String?, messages: List<ClassifiedMessage>, hand: Boolean): List<SearchHit> =
        messages.mapNotNull { m ->
            if (ManualEntries.isManual(m) != hand) return@mapNotNull null
            val content = m.content
            val payee = (content as? MessageContent.Transaction)?.payee
            val shown = m.merchant.name
            val rank = listOfNotNull(
                best(needle, shown, payee),
                amountRank(amount, amountOf(content)),
                best(needle, m.category.displayString)?.plus(CATEGORY_PENALTY),
                m.tags.mapNotNull { rank(needle, it) }.minOrNull()?.plus(CATEGORY_PENALTY),
                m.note?.let { best(needle, it)?.plus(NOTE_PENALTY + 1) },
                if (FeedBuilder.matches(m, needle)) TEXT_RANK else null
            ).minOrNull() ?: return@mapNotNull null
            val money = amountOf(content)?.let { "₹$it" }
            val sub = listOfNotNull(m.category.displayString, money).joinToString(" · ")
            SearchHit(
                if (hand) SearchKind.HAND_MADE else SearchKind.MESSAGE, m.id.toString(), shown, sub,
                messageId = m.id, rank = rank
            ) to m.message.date
        }.sortedWith(compareBy({ it.first.rank }, { -it.second })).map { it.first }

    private fun accountHits(needle: String, amount: String?, accounts: List<AccountSummary>): List<SearchHit> =
        accounts.mapNotNull { a ->
            val rank = listOfNotNull(
                best(needle, a.title, a.bankName.displayString, a.last4, a.kind.label),
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
}
