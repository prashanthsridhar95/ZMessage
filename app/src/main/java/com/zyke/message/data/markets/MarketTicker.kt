package com.zyke.message.data.markets

import com.zyke.message.domain.markets.MarketRow
import com.zyke.message.domain.markets.MarketSymbols
import com.zyke.message.domain.markets.Quote
import com.zyke.message.domain.markets.QuoteParser
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
                symbols.map { symbol -> async { symbol to fetcher.fetch(symbol)?.let { QuoteParser.parse(symbol, it) } } }.awaitAll()
            }
            fetched.forEach { (symbol, quote) -> if (quote != null) quotes[symbol] = quote }
            val reached = fetched.any { it.second != null }
            com.zyke.message.data.diag.DevLog.d("market") { "refresh: asked ${fetched.size}, got ${fetched.count { it.second != null }}" }
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
    suspend fun check(symbol: String): Quote? = withContext(Dispatchers.IO) { fetcher.fetch(symbol)?.let { QuoteParser.parse(symbol, it) } }

    /** Forget prices of tickers no longer wanted, and show the rows for what is. */
    fun reshow() {
        val wanted = watchlist.watch.value
        quotes.keys.retainAll(MarketSymbols.needed(wanted).toSet())
        _state.update { it.copy(rows = if (wanted.enabled) MarketSymbols.rows(wanted, quotes) else emptyList()) }
    }
}
