package com.zyke.message.domain.markets

import com.zyke.message.domain.util.toBigDecimalOrNull
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** One thing the person watches: the ticker a price service knows it by, and the name to show. */
data class WatchSymbol(val symbol: String, val label: String, val exchange: String = "")

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
    val tradedAt: Long? = null,
    /** The exchange the price is from, as the service named it, or blank. */
    val exchange: String = ""
)

/** One line of the strip and the list: a quote with the name it is shown under and what it measures. */
data class MarketRow(
    val label: String,
    val detail: String,
    val kind: MarketKind,
    val price: BigDecimal,
    val change: BigDecimal?,
    val changePercent: BigDecimal?,
    /** The currency code the quote is in ("INR", "USD", "GBp"...), blank for an index or an exchange rate. */
    val currency: String,
    /** Short exchange name (NSE, NASDAQ...) when known. */
    val exchange: String = ""
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

    /** The most names one watch list holds: enough for anyone, and every one is a request on each refresh. */
    const val MAX_WATCH = 30

    private val validSymbol = Regex("^[A-Z0-9^][A-Z0-9.\\-=^&_]{0,29}$")

    /** Whether text looks like a ticker the price service could know: letters, digits and . - = ^ & _ only, up to 30 long. */
    fun isValidSymbol(symbol: String): Boolean = validSymbol.matches(symbol)

    /**
     * The tickers to try for what was typed, in order. A ticker that says where it trades (RELIANCE.NS, ^GSPC, BTC-USD,
     * EURUSD=X, GC=F) is tried as it is. A bare one (AAPL, RELIANCE) is tried as it is, then as an NSE name, so old habits work.
     */
    fun candidates(input: String): List<String> {
        val text = input.trim().uppercase().replace(" ", "")
        if (text.isEmpty() || !isValidSymbol(text)) return emptyList()
        val says = text.startsWith("^") || text.contains('.') || text.contains('-') || text.endsWith("=X") || text.endsWith("=F")
        return if (says) listOf(text) else listOf(text, "$text.NS")
    }

    fun kindOf(symbol: String): MarketKind = when {
        symbol.startsWith("^") -> MarketKind.INDEX
        symbol.endsWith("=X") -> MarketKind.CURRENCY
        symbol.endsWith("=F") -> MarketKind.COMMODITY
        else -> MarketKind.STOCK
    }

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
            val kind = kindOf(watched.symbol)
            val exchange = watched.exchange.ifBlank { MarketNames.exchange(quote.exchange, null) }
            val detail = listOf(watched.symbol, exchange).filter { it.isNotBlank() }.joinToString(" \u00B7 ")
            val currency = if (kind == MarketKind.INDEX || (kind == MarketKind.CURRENCY && watched.symbol != USD_INR)) "" else quote.currency
            add(row(watched.label, detail, kind, quote.price, quote.previousClose, currency, exchange))
        }
    }

    private fun row(label: String, detail: String, kind: MarketKind, price: BigDecimal, before: BigDecimal?, currency: String, exchange: String = ""): MarketRow {
        val change = before?.let { price - it }
        val percent = if (before != null && before.signum() != 0) {
            change!!.multiply(BigDecimal(100)).divide(before, 2, RoundingMode.HALF_UP)
        } else {
            null
        }
        return MarketRow(label, detail, kind, price, change, percent, currency, exchange)
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
    private val exchange = Regex("\"exchangeName\"\\s*:\\s*\"([A-Za-z0-9 ._-]{1,20})\"")

    /** Yahoo quotes London in pence ("GBp") and Johannesburg in cents ("ZAc"); the lower-case letter matters. */
    private val SUB_UNITS = setOf("GBp", "ZAc")

    fun parse(symbol: String, json: String): Quote? {
        val now = price.find(json)?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 } ?: return null
        return Quote(
            symbol = symbol,
            price = now,
            previousClose = before.find(json)?.groupValues?.get(1)?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 },
            currency = currency.find(json)?.groupValues?.get(1)?.let { if (it in SUB_UNITS) it else it.uppercase() }.orEmpty(),
            tradedAt = time.find(json)?.groupValues?.get(1)?.toLongOrNull(),
            exchange = exchange.find(json)?.groupValues?.get(1).orEmpty()
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

/** The sign to show before a price in a currency, from the code the quote came with. */
object CurrencySymbols {
    private val known = mapOf(
        "INR" to "\u20B9", "USD" to "$", "GBP" to "\u00A3", "EUR" to "\u20AC", "JPY" to "\u00A5", "CNY" to "CN\u00A5", "HKD" to "HK$",
        "AUD" to "A$", "CAD" to "C$", "NZD" to "NZ$", "SGD" to "S$", "KRW" to "\u20A9", "TWD" to "NT$", "BRL" to "R$", "MXN" to "MX$",
        "CHF" to "CHF ", "SEK" to "SEK ", "NOK" to "NOK ", "DKK" to "DKK ", "ZAR" to "R", "RUB" to "\u20BD", "TRY" to "\u20BA",
        "ILS" to "\u20AA", "THB" to "\u0E3F", "IDR" to "Rp", "MYR" to "RM", "PHP" to "\u20B1", "VND" to "\u20AB", "AED" to "AED ", "SAR" to "SAR "
    )

    /** The text before the number: "\u20B9", "$", "HK$"; the code and a space when there is no common sign; blank for none. Pence and cents come after the number, see [suffix]. */
    fun prefix(code: String): String = when {
        code.isBlank() || code == "GBp" || code == "ZAc" -> ""
        else -> known[code.uppercase()] ?: (code.uppercase() + " ")
    }

    /** "p" for pence and "c" for cents, which Yahoo uses for London and Johannesburg. */
    fun suffix(code: String): String = when (code) {
        "GBp" -> "p"
        "ZAc" -> "c"
        else -> ""
    }

    /** Digits after the point: the currency's own, at least two for anything under 1,000 that is not a whole-number currency. */
    fun digits(code: String, price: BigDecimal): Int {
        if (price.abs() < BigDecimal.ONE) return if (price.signum() == 0) 2 else 4
        val base = runCatching { java.util.Currency.getInstance(code.uppercase()).defaultFractionDigits }.getOrDefault(2)
        return base.coerceIn(0, 4).let { if (code == "GBp" || code == "ZAc") 2 else it }
    }
}
