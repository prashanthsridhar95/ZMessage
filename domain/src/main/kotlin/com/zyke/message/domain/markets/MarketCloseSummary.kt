package com.zyke.message.domain.markets

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** The text of the notification: [title] for the collapsed form, [text] its second line, [body] one line per index when expanded. */
data class CloseSummaryText(val title: String, val text: String, val body: String)

/** What to do when the close-of-market alarm fires. */
sealed interface CloseDecision {
    /** Send this; every row is from today's close. */
    data class Send(val summary: CloseSummaryText) : CloseDecision
    /** Not a trading day (weekend), or today's close never arrived after the one retry (an exchange holiday, or no data). Send nothing. */
    data object Skip : CloseDecision
    /** Today's close is not in the data (yet, or offline). Try once more a little later. */
    data object RetryLater : CloseDecision
}

/**
 * The notification sent after a market's regular session ends (India 15:30 IST, United States 16:00 ET, Monday to Friday),
 * a few minutes later by the person's offset. Pure: the time and the quotes come in, the decision and the text come out.
 * It never guesses: a quote counts only if its last trade is today (market time) and at or after five minutes before the
 * close, so on an exchange holiday (holidays are not modelled), or when the service returns yesterday's figures, nothing is
 * sent rather than stale numbers. It covers the market's indices and the watched stocks listed on that market.
 */
object MarketCloseSummary {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    /** The default wait after the close, so the final prices are in. India's 15:30 plus this is the earlier fixed 15:45. */
    const val DEFAULT_OFFSET_MINUTES = 15
    /** The offsets offered in Settings. */
    val OFFSETS: List<Int> = listOf(5, 15, 30, 60)
    /** A last trade earlier than this long before the close is the market still open (or a late feed), not the close. */
    const val CLOSE_SLACK_MINUTES = 5L
    /** How long after a failed attempt the single retry waits. Well inside half an hour. */
    const val RETRY_MINUTES = 20
    /** Most stock lines in the expanded text; the rest are "+N more". */
    const val MAX_STOCK_LINES = 8

    /** The markets that have a close summary switch. */
    val MARKETS: List<Market> = listOf(Market.INDIA, Market.UNITED_STATES)

    /** India's indices, kept for callers that only know India. */
    val INDICES: List<Pair<String, String>> = Market.INDIA.indices

    fun symbols(market: Market = Market.INDIA): List<String> = market.indices.map { it.first }

    /** The watched stocks that belong to [market]: stocks only, never an index, a currency or a commodity. */
    fun stocksOf(market: Market, watched: List<WatchSymbol>): List<WatchSymbol> =
        watched.filter { MarketSymbols.kindOf(it.symbol) == MarketKind.STOCK && MarketOf.of(it.symbol, it.exchange) == market }

    /** Everything to ask the price service for: the market's indices and its watched stocks, no duplicates. */
    fun tickersFor(market: Market, watched: List<WatchSymbol>): List<String> =
        (symbols(market) + stocksOf(market, watched).map { it.symbol }).distinct()

    fun isTradingWeekday(date: LocalDate): Boolean = MarketCalendar.isWeekday(date)

    /** The next summary time (close plus [offsetMinutes]) on a weekday strictly after [now]. */
    fun nextFire(now: Instant, market: Market = Market.INDIA, offsetMinutes: Int = DEFAULT_OFFSET_MINUTES): Instant =
        MarketCalendar.nextCloseSummary(market, offsetMinutes, now) ?: now.plusSeconds(86_400)

    /** Whether this quote is a close from [today] in the market's time: last traded today, at or after the closing time less the slack. */
    fun isTodaysClose(quote: Quote, today: LocalDate, market: Market = Market.INDIA): Boolean {
        val zone = market.zone ?: return false
        val closes = market.closes ?: return false
        val traded = quote.tradedAt ?: return false
        val at = Instant.ofEpochSecond(traded).atZone(zone)
        return at.toLocalDate() == today && !at.toLocalTime().isBefore(closes.minusMinutes(CLOSE_SLACK_MINUTES))
    }

    /**
     * [retried]: this is the second and last attempt. A first attempt that finds no close for today asks for a retry; the
     * second skips, which is what an exchange holiday looks like. [watched] is the whole watch list; only this market's stocks count.
     */
    fun decide(
        now: Instant,
        quotes: Collection<Quote>,
        retried: Boolean,
        market: Market = Market.INDIA,
        watched: List<WatchSymbol> = emptyList()
    ): CloseDecision {
        val zone = market.zone ?: return CloseDecision.Skip
        val today = now.atZone(zone).toLocalDate()
        if (!isTradingWeekday(today)) return CloseDecision.Skip
        val fresh = quotes.filter { isTodaysClose(it, today, market) }
        val summary = build(fresh, market, watched)
        return when {
            summary != null -> CloseDecision.Send(summary)
            retried -> CloseDecision.Skip
            else -> CloseDecision.RetryLater
        }
    }

    /**
     * The text for whichever of [quotes] are the market's indices or watched stocks; null if there is none. A missing index
     * is left out, never shown as zero. Stocks are listed biggest mover first (by percent, either way), at most [MAX_STOCK_LINES].
     */
    fun build(quotes: Collection<Quote>, market: Market = Market.INDIA, watched: List<WatchSymbol> = emptyList()): CloseSummaryText? {
        val lines = market.indices.mapNotNull { (symbol, name) -> quotes.firstOrNull { it.symbol == symbol }?.let { Line(name, it) } }
        val stocks = stocksOf(market, watched).mapNotNull { w -> quotes.firstOrNull { it.symbol == w.symbol }?.let { Line(w.label, it) } }
            .sortedByDescending { it.percent?.abs() ?: BigDecimal(-1) }
        if (lines.isEmpty() && stocks.isEmpty()) return null
        val lead = "${market.title} market closed"
        val tally = tally(stocks)
        val title = if (lines.isNotEmpty()) "$lead \u00B7 ${lines.first().short()}" else "$lead \u00B7 $tally"
        val text = buildList {
            lines.drop(1).forEach { add(it.short()) }
            if (stocks.isNotEmpty() && lines.isNotEmpty()) add(tally)
        }.joinToString(" \u00B7 ").ifEmpty { if (stocks.isEmpty()) "${market.exchanges} closed for the day" else stocks.first().short() }
        val body = buildString {
            append(lines.joinToString("\n") { "${it.name}  ${it.level}  ${it.move(true)}".trimEnd() })
            if (stocks.isNotEmpty()) {
                if (isNotEmpty()) append("\n\n")
                append("Your stocks\n")
                append(stocks.take(MAX_STOCK_LINES).joinToString("\n") { it.short(spaced = true) })
                if (stocks.size > MAX_STOCK_LINES) append("\n+${stocks.size - MAX_STOCK_LINES} more")
            }
        }
        return CloseSummaryText(title, text, body)
    }

    /** "5 up, 2 down" or "3 up, 1 down, 1 flat" for the watched stocks that have a move. */
    private fun tally(stocks: List<Line>): String {
        val up = stocks.count { (it.percent?.signum() ?: 0) > 0 }
        val down = stocks.count { (it.percent?.signum() ?: 0) < 0 }
        val flat = stocks.count { it.percent?.signum() == 0 }
        return listOfNotNull(
            if (up > 0) "$up up" else null, if (down > 0) "$down down" else null, if (flat > 0) "$flat flat" else null
        ).joinToString(", ").ifEmpty { "${stocks.size} watched" }
    }

    private class Line(val name: String, quote: Quote) {
        val level: String = formatLevel(quote.price)
        private val sign = CurrencySymbols.prefix(quote.currency)
        private val unit = CurrencySymbols.suffix(quote.currency)
        private val price = sign + formatLevel(quote.price) + unit
        private val before = quote.previousClose
        private val change: BigDecimal? = before?.let { quote.price - it }
        val percent: BigDecimal? = if (before != null && before.signum() != 0) {
            change!!.multiply(BigDecimal(100)).divide(before, 2, RoundingMode.HALF_UP)
        } else null

        /** "Nifty 50 24,386.10 \u25B2 1.21%": an index. */
        fun short(spaced: Boolean = false): String =
            if (spaced) "$name  $price  ${move(false)}".trimEnd() else "$name $level ${move(false)}".trimEnd()

        /** "\u25B2 1.23%", "\u25BC 0.31%", "0.00%", or blank with no previous close. [withPoints] adds the points: "\u25B2 297.60 (1.23%)". */
        fun move(withPoints: Boolean): String {
            val c = change ?: return ""
            val p = percent ?: return ""
            val arrow = when {
                p.signum() > 0 -> "\u25B2 "
                p.signum() < 0 -> "\u25BC "
                else -> ""
            }
            val pct = formatLevel(p.abs()) + "%"
            return if (withPoints) "$arrow${formatLevel(c.abs())} ($pct)" else "$arrow$pct"
        }
    }

    /** 24,386.10: grouped thousands and two decimals, whatever the phone's language. */
    fun formatLevel(value: BigDecimal): String =
        DecimalFormat("#,##0.00", DecimalFormatSymbols(Locale.US)).apply { roundingMode = RoundingMode.HALF_UP }.format(value)
}
