package com.zyke.message.domain.search

import com.zyke.message.domain.feed.QueryInterpreter
import com.zyke.message.domain.feed.SmartFilter
import com.zyke.message.domain.model.MessageCategory
import java.math.BigDecimal
import java.time.LocalDate

/** The kinds of filter a typed search can imply; each one is shown as a removable chip. */
enum class SearchChipKind { TIME, CATEGORY, TYPE, AMOUNT }

/** One filter found in the query. [tokens] are the positions (in [ParsedSearch.tokens]) of the words it came from. */
data class SearchChip(val kind: SearchChipKind, val label: String, val tokens: IntRange)

/**
 * What a typed search means: the filters it implies (time span, category, type, amount bounds) and the words left to look
 * for. [from] and [to] are both inclusive.
 */
data class ParsedSearch(
    val tokens: List<String>,
    val text: String,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val category: MessageCategory? = null,
    val smart: SmartFilter = SmartFilter.ALL,
    val minAmount: BigDecimal? = null,
    val maxAmount: BigDecimal? = null,
    val chips: List<SearchChip> = emptyList()
) {
    val hasFilters: Boolean get() = chips.isNotEmpty()
    val isBlank: Boolean get() = tokens.isEmpty()

    /** The query typed again without the words [chip] came from. */
    fun without(chip: SearchChip): String =
        tokens.filterIndexed { index, _ -> index !in chip.tokens }.joinToString(" ")
}

/**
 * Reads everyday search phrases such as "swiggy last month", "food this month", ">5000" or "salary under 2k". Category and
 * type words come from [QueryInterpreter], so this and the Messages tab understand the same words. Rules, not a model.
 */
object SearchQueryParser {
    private val FILLER = setOf("show", "me", "my", "all", "the", "from", "in", "on", "of", "for", "a", "an", "paid", "spent")
    private val ABOVE = setOf("above", "over")
    private val BELOW = setOf("under", "below")

    fun parse(query: String, today: LocalDate): ParsedSearch {
        val tokens = query.trim().split(' ').filter { it.isNotEmpty() }
        val chips = mutableListOf<SearchChip>()
        val used = mutableSetOf<Int>()
        var from: LocalDate? = null
        var to: LocalDate? = null
        var category: MessageCategory? = null
        var smart = SmartFilter.ALL
        var min: BigDecimal? = null
        var max: BigDecimal? = null

        fun take(kind: SearchChipKind, label: String, start: Int, end: Int) {
            chips += SearchChip(kind, label, start..end)
            used += start..end
        }

        var i = 0
        while (i < tokens.size) {
            val w = tokens[i].lowercase()
            val n = tokens.getOrNull(i + 1)?.lowercase()
            val n2 = tokens.getOrNull(i + 2)?.lowercase()
            var step = 1
            when {
                from == null && w == "today" -> { from = today; to = today; take(SearchChipKind.TIME, "Today", i, i) }
                from == null && w == "yesterday" -> {
                    from = today.minusDays(1); to = from; take(SearchChipKind.TIME, "Yesterday", i, i)
                }
                from == null && w == "this" && n == "month" -> {
                    from = today.withDayOfMonth(1); to = today; take(SearchChipKind.TIME, "This month", i, i + 1); step = 2
                }
                from == null && w == "last" && n == "month" -> {
                    val start = today.withDayOfMonth(1).minusMonths(1)
                    from = start; to = start.withDayOfMonth(start.lengthOfMonth()); take(SearchChipKind.TIME, "Last month", i, i + 1); step = 2
                }
                from == null && (w == "this" || w == "last") && n == "week" -> {
                    from = today.minusDays(6); to = today; take(SearchChipKind.TIME, "Last 7 days", i, i + 1); step = 2
                }
                from == null && w == "last" && n == "7" && n2 == "days" -> {
                    from = today.minusDays(6); to = today; take(SearchChipKind.TIME, "Last 7 days", i, i + 2); step = 3
                }
                from == null && w == "last" && n == "30" && n2 == "days" -> {
                    from = today.minusDays(29); to = today; take(SearchChipKind.TIME, "Last 30 days", i, i + 2); step = 3
                }
                w.startsWith(">") || w.startsWith("<") -> {
                    val orEqual = w.getOrNull(1) == '='
                    val value = amount(w.substring(if (orEqual) 2 else 1))
                    val above = w.startsWith(">")
                    if (value != null && (if (above) min == null else max == null)) {
                        if (above) min = value else max = value
                        take(SearchChipKind.AMOUNT, (if (above) "Over ₹" else "Under ₹") + plain(value), i, i)
                    }
                }
                (w in ABOVE || w in BELOW) && n != null && amount(n) != null && (if (w in ABOVE) min == null else max == null) -> {
                    val value = amount(n)!!
                    if (w in ABOVE) min = value else max = value
                    take(SearchChipKind.AMOUNT, (if (w in ABOVE) "Over ₹" else "Under ₹") + plain(value), i, i + 1)
                    step = 2
                }
                else -> {
                    val found = QueryInterpreter.interpret(w).filter
                    if (found.category != null && category == null) {
                        category = found.category; take(SearchChipKind.CATEGORY, found.category.displayString, i, i)
                    } else if (found.smart != SmartFilter.ALL && smart == SmartFilter.ALL) {
                        smart = found.smart; take(SearchChipKind.TYPE, found.smart.label, i, i)
                    }
                }
            }
            i += step
        }

        val hasFilters = chips.isNotEmpty()
        val rest = tokens.filterIndexed { index, word -> index !in used && !(hasFilters && word.lowercase() in FILLER) }
        return ParsedSearch(tokens, rest.joinToString(" "), from, to, category, smart, min, max, chips)
    }

    /** "5000", "₹5,000", "5k", "1.5l" (lakh); null when it is not a whole-token amount. */
    internal fun amount(token: String): BigDecimal? {
        var t = token.lowercase().replace("₹", "").replace(",", "")
        if (t.startsWith("rs.")) t = t.removePrefix("rs.") else if (t.startsWith("rs")) t = t.removePrefix("rs")
        val scale = when {
            t.endsWith("k") -> THOUSAND
            t.endsWith("l") -> LAKH
            else -> BigDecimal.ONE
        }
        if (scale != BigDecimal.ONE) t = t.dropLast(1)
        if (t.isEmpty() || !t.all { it.isDigit() || it == '.' } || t.count { it == '.' } > 1 || t.none { it.isDigit() }) return null
        return runCatching { BigDecimal(t).multiply(scale) }.getOrNull()
    }

    private fun plain(value: BigDecimal): String =
        value.stripTrailingZeros().toPlainString().let { s -> s.reversed().chunked(3).joinToString(",").reversed().let { grouped ->
            // Group only the whole part; a fractional amount is shown as typed.
            if ('.' in s) s else grouped
        } }

    private val THOUSAND = BigDecimal(1000)
    private val LAKH = BigDecimal(100000)
}
