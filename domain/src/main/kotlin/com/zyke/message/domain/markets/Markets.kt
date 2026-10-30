package com.zyke.message.domain.markets

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** One thing the person watches: the ticker a price service knows it by, and the name to show. */
data class WatchSymbol(val symbol: String, val label: String)

/** What the person asked to see. Off until they switch it on, because prices need the internet. */
data class WatchSettings(
    val enabled: Boolean = false,
    val symbols: List<WatchSymbol> = emptyList(),
    val showDollar: Boolean = true,
    val showGold: Boolean = true
)

enum class MarketKind { INDEX, STOCK, CURRENCY, COMMODITY }

/** A price as the service reported it. */
data class Quote(
    val symbol: String,
    val price: BigDecimal,
    val previousClose: BigDecimal?,
    val currency: String,
    /** Seconds since 1970 of the last trade, when the service said. */
    val tradedAt: Long? = null
)

/** One line of the strip and the list: a quote with the name it is shown under and what it measures. */
data class MarketRow(
    val label: String,
    val detail: String,
    val kind: MarketKind,
    val price: BigDecimal,
    val change: BigDecimal?,
    val changePercent: BigDecimal?,
    /** "INR" for rupees, "USD" for dollars, blank for an index. */
    val currency: String
) {
    val isUp: Boolean get() = (change?.signum() ?: 0) > 0
    val isDown: Boolean get() = (change?.signum() ?: 0) < 0
}

object MarketSymbols {
    const val USD_INR = "USDINR=X"
    const val GOLD_USD_PER_OUNCE = "GC=F"
    const val NIFTY = "^NSEI"
    const val SENSEX = "^BSESN"

    private const val GRAMS_PER_TROY_OUNCE = "31.1035"

    val suggestions = listOf(
        WatchSymbol(NIFTY, "Nifty 50"),
        WatchSymbol(SENSEX, "Sensex")
    )

    /**
     * What the person typed, as the ticker to ask for. A bare name like "reliance" means the NSE listing; a ticker
     * that already says where it trades (RELIANCE.BO, ^NSEI, AAPL stays AAPL when typed with a dot or caret) is kept.
     */
    fun normalise(input: String): String {
        val text = input.trim().uppercase().replace(" ", "")
        if (text.isEmpty()) return ""
        return when {
            text.startsWith("^") || text.contains('.') || text.endsWith("=X") || text.endsWith("=F") -> text
            else -> "$text.NS"
        }
    }

    /** A short name for a ticker nobody named: "RELIANCE.NS" is "RELIANCE". */
    fun defaultLabel(symbol: String): String = symbol.removePrefix("^").substringBefore('.').substringBefore('=')

    /** Gold priced the way it is bought in India, rupees for ten grams, from dollars an ounce and the dollar's price. */
    fun goldPerTenGrams(dollarsPerOunce: BigDecimal, rupeesPerDollar: BigDecimal): BigDecimal =
        dollarsPerOunce.multiply(rupeesPerDollar)
            .multiply(BigDecimal(10))
            .divide(BigDecimal(GRAMS_PER_TROY_OUNCE), MathContext.DECIMAL64)
            .setScale(0, RoundingMode.HALF_UP)

    /** The tickers to ask for given what the person wants to see. */
    fun needed(settings: WatchSettings): List<String> = buildList {
        settings.symbols.forEach { add(it.symbol) }
        if (settings.showDollar || settings.showGold) add(USD_INR)
        if (settings.showGold) add(GOLD_USD_PER_OUNCE)
    }.distinct()

    /**
     * The rows to show, in the order the person set them: the dollar and gold first (they are what people check most),
     * then their own list. Anything not fetched yet is left out rather than shown as zero.
     */
    fun rows(settings: WatchSettings, quotes: Map<String, Quote>): List<MarketRow> = buildList {
        val dollar = quotes[USD_INR]
        if (settings.showDollar && dollar != null) {
            add(row("USD / INR", "US dollar in rupees", MarketKind.CURRENCY, dollar.price, dollar.previousClose, "INR"))
        }
        val gold = quotes[GOLD_USD_PER_OUNCE]
        if (settings.showGold && gold != null && dollar != null) {
            add(
                row(
                    "Gold", "Per 10 g, international price in rupees", MarketKind.COMMODITY,
                    goldPerTenGrams(gold.price, dollar.price),
                    gold.previousClose?.let { before -> dollar.previousClose?.let { goldPerTenGrams(before, it) } },
                    "INR"
                )
            )
        }
        settings.symbols.forEach { watched ->
            val quote = quotes[watched.symbol] ?: return@forEach
            val kind = if (watched.symbol.startsWith("^")) MarketKind.INDEX else MarketKind.STOCK
            add(row(watched.label, watched.symbol, kind, quote.price, quote.previousClose, if (kind == MarketKind.INDEX) "" else quote.currency))
        }
    }

    private fun row(label: String, detail: String, kind: MarketKind, price: BigDecimal, before: BigDecimal?, currency: String): MarketRow {
        val change = before?.let { price - it }
        val percent = if (before != null && before.signum() != 0) {
            change!!.multiply(BigDecimal(100)).divide(before, 2, RoundingMode.HALF_UP)
        } else {
            null
        }
        return MarketRow(label, detail, kind, price, change, percent, currency)
    }
}

/**
 * Reads the price service's chart reply for one ticker. Only the few fields wanted are picked out, so a change in
 * the rest of the reply does not matter, and a reply without a price (unknown ticker, error) gives null.
 */
object QuoteParser {
    private val price = Regex("\"regularMarketPrice\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)")
    private val before = Regex("\"(?:chartPreviousClose|previousClose)\"\\s*:\\s*(-?[0-9]+(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?)")
    private val currency = Regex("\"currency\"\\s*:\\s*\"([A-Za-z]{3})\"")
    private val time = Regex("\"regularMarketTime\"\\s*:\\s*([0-9]+)")

    fun parse(symbol: String, json: String): Quote? {
        val now = price.find(json)?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        return Quote(
            symbol = symbol,
            price = now,
            previousClose = before.find(json)?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
            currency = currency.find(json)?.groupValues?.get(1)?.uppercase().orEmpty(),
            tradedAt = time.find(json)?.groupValues?.get(1)?.toLongOrNull()
        )
    }
}

interface WatchlistRepository {
    val watch: kotlinx.coroutines.flow.StateFlow<WatchSettings>
    fun save(settings: WatchSettings)
}

object NoWatchlist : WatchlistRepository {
    override val watch = kotlinx.coroutines.flow.MutableStateFlow(WatchSettings())
    override fun save(settings: WatchSettings) = Unit
}
