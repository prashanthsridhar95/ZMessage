package com.zyke.message.domain.search

/** The last searches, newest first. A search runs on the phone and is kept only there, so this store is private to it. */
interface RecentSearchStore {
    fun load(): List<String>
    fun save(searches: List<String>)
}

object NoRecentSearchStore : RecentSearchStore {
    override fun load(): List<String> = emptyList()
    override fun save(searches: List<String>) = Unit
}

/** Keeps the list tidy: newest first, no repeats, at most [MAX]. */
object RecentSearches {
    const val MAX = 8
    private const val MIN_LENGTH = 2

    /** [query] goes first; an older copy (ignoring case and spacing) is replaced. Too-short queries are not kept. */
    fun add(current: List<String>, query: String): List<String> {
        val clean = query.trim().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        if (clean.length < MIN_LENGTH) return current
        return (listOf(clean) + current.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX)
    }

    fun remove(current: List<String>, query: String): List<String> = current.filterNot { it.equals(query, ignoreCase = true) }
}

/** A ready-made search shown when nothing is typed. */
data class SearchSuggestion(val label: String, val query: String)

object SearchSuggestions {
    val all = listOf(
        SearchSuggestion("This month", "this month"),
        SearchSuggestion("Last month", "last month"),
        SearchSuggestion("Food", "food"),
        SearchSuggestion("Bills due", "bills"),
        SearchSuggestion("Credit card", "credit card"),
        SearchSuggestion("Salary", "salary"),
        SearchSuggestion("Refunds", "refund"),
        SearchSuggestion("Transfers", "transfer"),
        SearchSuggestion("Large payments >₹5,000", ">5000")
    )
}
