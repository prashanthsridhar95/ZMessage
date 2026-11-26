package com.zyke.message.domain.markets

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * A stock market the app knows by name: where it is, when its regular session runs, and which indices stand for it.
 * [OTHER] holds what belongs to no country (the dollar's rate, gold, crypto) and has no session.
 */
enum class Market(
    val title: String,
    /** ISO country code; the flag is its two regional-indicator letters. Blank for [OTHER]. */
    val country: String,
    zoneId: String?,
    val opens: LocalTime?,
    val closes: LocalTime?,
    /** Indices shown in the close summary, in order: ticker to short name. */
    val indices: List<Pair<String, String>> = emptyList(),
    /** Shown after the title in the close notification ("India market closed"). */
    val exchanges: String = ""
) {
    INDIA("India", "IN", "Asia/Kolkata", LocalTime.of(9, 15), LocalTime.of(15, 30), listOf(MarketSymbols.NIFTY to "Nifty 50", MarketSymbols.SENSEX to "Sensex"), "NSE and BSE"),
    UNITED_STATES("United States", "US", "America/New_York", LocalTime.of(9, 30), LocalTime.of(16, 0), listOf("^GSPC" to "S&P 500", "^IXIC" to "NASDAQ", "^DJI" to "Dow"), "NYSE and NASDAQ"),
    UNITED_KINGDOM("United Kingdom", "GB", "Europe/London", LocalTime.of(8, 0), LocalTime.of(16, 30), listOf("^FTSE" to "FTSE 100"), "London"),
    GERMANY("Germany", "DE", "Europe/Berlin", LocalTime.of(9, 0), LocalTime.of(17, 30), listOf("^GDAXI" to "DAX"), "Xetra"),
    JAPAN("Japan", "JP", "Asia/Tokyo", LocalTime.of(9, 0), LocalTime.of(15, 30), listOf("^N225" to "Nikkei 225"), "Tokyo"),
    HONG_KONG("Hong Kong", "HK", "Asia/Hong_Kong", LocalTime.of(9, 30), LocalTime.of(16, 0), listOf("^HSI" to "Hang Seng"), "HKEX"),
    CANADA("Canada", "CA", "America/Toronto", LocalTime.of(9, 30), LocalTime.of(16, 0), listOf("^GSPTSE" to "S&P/TSX"), "TSX"),
    AUSTRALIA("Australia", "AU", "Australia/Sydney", LocalTime.of(10, 0), LocalTime.of(16, 0), listOf("^AXJO" to "ASX 200"), "ASX"),
    SINGAPORE("Singapore", "SG", "Asia/Singapore", LocalTime.of(9, 0), LocalTime.of(17, 0), listOf("^STI" to "STI"), "SGX"),
    OTHER("Currencies and commodities", "", null, null, null);

    val zone: ZoneId? = zoneId?.let { ZoneId.of(it) }

    /** The country's flag as two regional-indicator letters, or blank for [OTHER]. Plain text: any emoji font draws it. */
    val flag: String
        get() = country.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")

    val hasSession: Boolean get() = zone != null && opens != null && closes != null
}

/** Whether a market's regular session is running, and when it next changes. */
sealed interface SessionStatus {
    /** Regular session running; it ends at [closesAt]. */
    data class Open(val closesAt: ZonedDateTime) : SessionStatus
    /** Closed; the regular session next begins at [opensAt]. */
    data class Closed(val opensAt: ZonedDateTime) : SessionStatus
}

/**
 * Regular trading hours per market, Monday to Friday in the market's own time zone (so summer time is followed). Holidays,
 * half days, lunch breaks and pre- or after-hours trading are NOT modelled: on an exchange holiday this says "open" while
 * nothing trades. Treat it as a hint, never as a fact; the close summary therefore also checks the quotes' own time.
 */
object MarketCalendar {
    fun isWeekday(date: LocalDate): Boolean = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

    fun status(market: Market, now: Instant): SessionStatus? {
        val zone = market.zone ?: return null
        val opens = market.opens ?: return null
        val closes = market.closes ?: return null
        val local = now.atZone(zone)
        val date = local.toLocalDate()
        if (isWeekday(date)) {
            val open = ZonedDateTime.of(date, opens, zone)
            val close = ZonedDateTime.of(date, closes, zone)
            if (!local.isBefore(open) && local.isBefore(close)) return SessionStatus.Open(close)
            if (local.isBefore(open)) return SessionStatus.Closed(open)
        }
        var next = date.plusDays(1)
        while (!isWeekday(next)) next = next.plusDays(1)
        return SessionStatus.Closed(ZonedDateTime.of(next, opens, zone))
    }

    /** The next weekday close of [market] plus [offsetMinutes], strictly after [now]; null for a market with no session. */
    fun nextCloseSummary(market: Market, offsetMinutes: Int, now: Instant): Instant? {
        val zone = market.zone ?: return null
        val closes = market.closes ?: return null
        var day = now.atZone(zone).toLocalDate()
        while (true) {
            val at = ZonedDateTime.of(day, closes, zone).plusMinutes(offsetMinutes.toLong())
            // The offset may carry past midnight; the weekday rule is about the session's day.
            if (isWeekday(day) && at.toInstant().isAfter(now)) return at.toInstant()
            day = day.plusDays(1)
        }
    }

    /** A short hint for a group header: "Open · closes 4:00 PM ET" or "Closed · opens Mon 9:15 AM IST"; null with no session. */
    fun hint(market: Market, now: Instant): String? {
        val zone = market.zone ?: return null
        val label = zoneLabel(market)
        return when (val s = status(market, now) ?: return null) {
            is SessionStatus.Open -> "Open · closes ${clock(s.closesAt)} $label"
            is SessionStatus.Closed -> {
                val sameDay = s.opensAt.toLocalDate() == now.atZone(zone).toLocalDate()
                val day = if (sameDay) "" else s.opensAt.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.US) + " "
                "Closed · opens $day${clock(s.opensAt)} $label"
            }
        }
    }

    fun zoneLabel(market: Market): String = when (market) {
        Market.INDIA -> "IST"
        Market.UNITED_STATES -> "ET"
        Market.UNITED_KINGDOM -> "UK"
        Market.JAPAN -> "JST"
        Market.HONG_KONG -> "HKT"
        else -> market.zone?.id?.substringAfter('/')?.replace('_', ' ').orEmpty()
    }

    private fun clock(time: ZonedDateTime): String {
        val h = time.hour % 12
        val hour = if (h == 0) 12 else h
        return "$hour:${time.minute.toString().padStart(2, '0')} ${if (time.hour < 12) "AM" else "PM"}"
    }
}

/** Which market a ticker belongs to, from the ticker's own suffix and, for a bare one, the exchange name. */
object MarketOf {
    private val suffixes = mapOf(
        ".NS" to Market.INDIA, ".BO" to Market.INDIA, ".L" to Market.UNITED_KINGDOM, ".DE" to Market.GERMANY, ".F" to Market.GERMANY,
        ".T" to Market.JAPAN, ".HK" to Market.HONG_KONG, ".TO" to Market.CANADA, ".V" to Market.CANADA, ".AX" to Market.AUSTRALIA, ".SI" to Market.SINGAPORE
    )
    private val indices = mapOf(
        "^NSEI" to Market.INDIA, "^BSESN" to Market.INDIA, "^NSEBANK" to Market.INDIA, "^GSPC" to Market.UNITED_STATES, "^IXIC" to Market.UNITED_STATES,
        "^DJI" to Market.UNITED_STATES, "^RUT" to Market.UNITED_STATES, "^VIX" to Market.UNITED_STATES, "^NDX" to Market.UNITED_STATES,
        "^FTSE" to Market.UNITED_KINGDOM, "^GDAXI" to Market.GERMANY, "^N225" to Market.JAPAN, "^HSI" to Market.HONG_KONG,
        "^GSPTSE" to Market.CANADA, "^AXJO" to Market.AUSTRALIA, "^STI" to Market.SINGAPORE
    )
    private val exchanges = mapOf(
        "NSE" to Market.INDIA, "BSE" to Market.INDIA, "NASDAQ" to Market.UNITED_STATES, "NYSE" to Market.UNITED_STATES,
        "NYSE ARCA" to Market.UNITED_STATES, "AMEX" to Market.UNITED_STATES, "CBOE" to Market.UNITED_STATES,
        "LSE" to Market.UNITED_KINGDOM, "TSE" to Market.JAPAN, "HKEX" to Market.HONG_KONG, "TSX" to Market.CANADA, "ASX" to Market.AUSTRALIA, "SGX" to Market.SINGAPORE
    )

    /**
     * [exchange] is the short name the app keeps (NSE, NASDAQ), blank when unknown. A bare ticker with no exchange is read as a
     * US listing, as the price service does. Currencies, commodities and crypto belong to [Market.OTHER].
     */
    fun of(symbol: String, exchange: String = ""): Market {
        val s = symbol.trim().uppercase()
        if (s.isEmpty() || s.endsWith("=X") || s.endsWith("=F")) return Market.OTHER
        indices[s]?.let { return it }
        if (s.startsWith("^")) return exchanges[exchange.trim().uppercase()] ?: Market.OTHER
        suffixes.entries.firstOrNull { s.endsWith(it.key) }?.let { return it.value }
        if (Regex("-[A-Z]{3,4}$").containsMatchIn(s) && !s.contains('.')) return Market.OTHER // BTC-USD
        exchanges[exchange.trim().uppercase()]?.let { return it }
        return Market.UNITED_STATES
    }

    fun of(row: MarketRow): Market = when {
        row.kind == MarketKind.CURRENCY || row.kind == MarketKind.COMMODITY -> Market.OTHER
        else -> of(row.symbol, row.exchange)
    }
}

/** One group of the market-watch list: a market, the rows under it and, when it is known, that market's index. */
data class MarketGroup(val market: Market, val rows: List<MarketRow>, val index: MarketRow?)

/** Splits rows by market. Pure; the page decides whether to show groups at all. */
object MarketGrouping {
    /**
     * Groups of [rows] in the order each market first appears, with [Market.OTHER] always last. A group's [MarketGroup.index] is
     * the first index row of that market found in [indexSource] (default [rows]), so an index shown elsewhere still heads its group.
     * Index rows themselves stay in [rows] only if the caller left them there.
     */
    fun group(rows: List<MarketRow>, indexSource: List<MarketRow> = rows): List<MarketGroup> {
        val order = LinkedHashMap<Market, MutableList<MarketRow>>()
        rows.forEach { order.getOrPut(MarketOf.of(it)) { mutableListOf() }.add(it) }
        val keys = order.keys.filter { it != Market.OTHER } + order.keys.filter { it == Market.OTHER }
        return keys.map { m ->
            val index = indexSource.firstOrNull { it.kind == MarketKind.INDEX && MarketOf.of(it) == m && it.changePercent != null }
                ?: indexSource.firstOrNull { it.kind == MarketKind.INDEX && MarketOf.of(it) == m }
            MarketGroup(m, order.getValue(m), index)
        }
    }

    /** How many real markets (not [Market.OTHER]) the rows span. */
    fun marketCount(rows: List<MarketRow>): Int = rows.map { MarketOf.of(it) }.filter { it != Market.OTHER }.toSet().size

    /** The default for "Group by market" when the person never chose: on when the list spans more than one market. */
    fun defaultOn(rows: List<MarketRow>): Boolean = marketCount(rows) > 1

    /** Whether to show the toggle at all: only with more than one market in the list. */
    fun offered(rows: List<MarketRow>): Boolean = marketCount(rows) > 1

    /** Whether the list is drawn in groups: never with a single market, else the person's choice, on until they say otherwise. */
    fun shown(preference: Boolean?, rows: List<MarketRow>): Boolean = offered(rows) && (preference ?: defaultOn(rows))
}
