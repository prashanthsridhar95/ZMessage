package com.zyke.message.data.markets

import com.zyke.message.data.diag.DevLog
import com.zyke.message.domain.markets.MarketRow
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.QuoteParser
import com.zyke.message.domain.markets.StockSuggestion
import com.zyke.message.domain.markets.YahooSearchParser
import com.zyke.message.domain.markets.WatchSymbol
import com.zyke.message.domain.markets.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** What the strip and the list draw. */
data class MarketState(
    val rows: List<MarketRow> = emptyList(),
    /** Epoch milliseconds of the last successful refresh. */
    val updatedAt: Long? = null,
    val loading: Boolean = false,
    /** The last refresh could not reach the service. */
    val offline: Boolean = false
)

/** What a name search found: names (possibly none), or that the service could not be reached. */
sealed interface SearchOutcome {
    data class Found(val suggestions: List<StockSuggestion>) : SearchOutcome
    data object Unreachable : SearchOutcome
}

/**
 * Keeps the latest prices for the person's watch list. It asks only while a screen that shows them is open (see
 * the polling effect in the UI) and only when the person has switched the strip on.
 */
class MarketTicker(private val watchlist: WatchlistRepository, private val scope: CoroutineScope) {
    private val fetcher = QuoteFetcher()
    private val quotes = HashMap<String, Quote>()
    private val lock = Mutex()
    private val _state = MutableStateFlow(MarketState())
    val state: StateFlow<MarketState> = _state

    val settings get() = watchlist.watch

    /** Asks for every ticker in the watch list at once and updates what is shown. Safe to call often. */
    suspend fun refresh() {
        val wanted = watchlist.watch.value
        if (!wanted.enabled) return
        lock.withLock {
            _state.update { it.copy(loading = true) }
            val symbols = MarketSymbols.needed(wanted)
            val fetched = withContext(Dispatchers.IO) {
                // A few at a time, so a long list does not open dozens of connections at once; one failing never stops the others.
                val gate = Semaphore(MAX_AT_ONCE)
                symbols.map { symbol -> async { gate.withPermit { symbol to fetch(symbol) } } }.awaitAll()
            }
            fetched.forEach { (symbol, quote) -> if (quote != null) quotes[symbol] = quote } // a failed one keeps its last good price
            val reached = fetched.any { it.second != null }
            DevLog.d("market") { "refresh: asked ${fetched.size}, got ${fetched.count { it.second != null }}" }
            val now = System.currentTimeMillis()
            _state.update {
                MarketState(
                    rows = MarketSymbols.rows(wanted, quotes),
                    updatedAt = if (reached) now else it.updatedAt,
                    loading = false,
                    offline = !reached
                )
            }
        }
    }

    /**
     * Whether the service knows this ticker, so a typo is caught when it is added instead of leaving a line that never fills.
     * Returns the quote, or null.
     */
    suspend fun check(symbol: String): Quote? = withContext(Dispatchers.IO) { fetch(symbol) }

    /** The first of the typed text's candidate tickers (see [MarketSymbols.candidates]) that has a price, or null. */
    suspend fun checkTyped(input: String): Quote? = withContext(Dispatchers.IO) {
        MarketSymbols.candidates(input).firstNotNullOfOrNull { fetch(it) }
    }

    /**
     * Asks Yahoo Finance for names matching [query]. Runs off the main thread. Only when the strip is switched on; the
     * caller shows the local list otherwise. Cancelling the caller drops the answer.
     */
    suspend fun search(query: String): SearchOutcome {
        if (!watchlist.watch.value.enabled) return SearchOutcome.Unreachable
        return withContext(Dispatchers.IO) {
            val reply = fetcher.search(query)
            if (reply == null) {
                SearchOutcome.Unreachable
            } else {
                val found = YahooSearchParser.parse(reply)
                DevLog.d("market") { "search: ${found.size} names" }
                SearchOutcome.Found(found)
            }
        }
    }

    private fun fetch(symbol: String): Quote? = runCatching { fetcher.fetch(symbol)?.let { QuoteParser.parse(symbol, it) } }.getOrNull()

    /** Forget prices of tickers no longer wanted, and show the rows for what is. */
    fun reshow() {
        val wanted = watchlist.watch.value
        quotes.keys.retainAll(MarketSymbols.needed(wanted).toSet())
        _state.update { it.copy(rows = if (wanted.enabled) MarketSymbols.rows(wanted, quotes) else emptyList()) }
    }

    private companion object {
        const val MAX_AT_ONCE = 4
    }
}
