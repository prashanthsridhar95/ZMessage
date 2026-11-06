package com.zyke.message.domain.markets

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
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
 * The notification sent after the Indian market (NSE and BSE) shuts at 15:30 IST, Monday to Friday. Pure: the time and the
 * quotes come in, the decision and the text come out. It never guesses: an index counts only if its last trade is today's
 * and at or after the closing auction, so on an exchange holiday (no new trading day in the data), or when the service
 * returns yesterday's figures, nothing is sent rather than stale numbers.
 */
object MarketCloseSummary {
    val ZONE: ZoneId = ZoneId.of("Asia/Kolkata")
    /** The alarm: a quarter of an hour after the close, so the final prices are in. */
    val FIRE_AT: LocalTime = LocalTime.of(15, 45)
    /** A last trade before this on the day is the market still open (or a late feed), not the close. */
    val CLOSED_FROM: LocalTime = LocalTime.of(15, 25)
    /** How long after a failed attempt the single retry waits. Well inside half an hour. */
    const val RETRY_MINUTES = 20

    /** The indices shown, in order. */
    val INDICES: List<Pair<String, String>> = listOf(MarketSymbols.NIFTY to "Nifty 50", MarketSymbols.SENSEX to "Sensex")

    fun symbols(): List<String> = INDICES.map { it.first }

    fun isTradingWeekday(date: LocalDate): Boolean = date.dayOfWeek != DayOfWeek.SATURDAY && date.dayOfWeek != DayOfWeek.SUNDAY

    /** The next 15:45 IST on a weekday strictly after [now]. */
    fun nextFire(now: Instant): Instant {
        val zoned = now.atZone(ZONE)
        var day = zoned.toLocalDate()
        var at = ZonedDateTime.of(day, FIRE_AT, ZONE)
        while (!at.toInstant().isAfter(now) || !isTradingWeekday(day)) {
            day = day.plusDays(1)
            at = ZonedDateTime.of(day, FIRE_AT, ZONE)
        }
        return at.toInstant()
    }

    /** Whether this quote is a close from [today] (IST): last traded today, at or after the closing time. */
    fun isTodaysClose(quote: Quote, today: LocalDate): Boolean {
        val traded = quote.tradedAt ?: return false
        val at = Instant.ofEpochSecond(traded).atZone(ZONE)
        return at.toLocalDate() == today && !at.toLocalTime().isBefore(CLOSED_FROM)
    }

    /**
     * [retried]: this is the second and last attempt. A first attempt that finds no close for today asks for a retry; the
     * second skips, which is what an exchange holiday looks like.
     */
    fun decide(now: Instant, quotes: Collection<Quote>, retried: Boolean): CloseDecision {
        val today = now.atZone(ZONE).toLocalDate()
        if (!isTradingWeekday(today)) return CloseDecision.Skip
        val fresh = quotes.filter { isTodaysClose(it, today) }
        val summary = build(fresh)
        return when {
            summary != null -> CloseDecision.Send(summary)
            retried -> CloseDecision.Skip
            else -> CloseDecision.RetryLater
        }
    }

    /** The text for whichever of [quotes] are the indices wanted; null if none is. A missing index is left out, never shown as zero. */
    fun build(quotes: Collection<Quote>): CloseSummaryText? {
        val lines = INDICES.mapNotNull { (symbol, name) -> quotes.firstOrNull { it.symbol == symbol }?.let { Line(name, it) } }
        if (lines.isEmpty()) return null
        val first = lines.first()
        val title = "Markets closed · ${first.name} ${first.level} ${first.move(false)}".trimEnd()
        val text = if (lines.size > 1) lines.drop(1).joinToString(" · ") { "${it.name} ${it.level} ${it.move(false)}".trimEnd() } else "NSE and BSE closed for the day"
        val body = lines.joinToString("\n") { "${it.name}  ${it.level}  ${it.move(true)}".trimEnd() }
        return CloseSummaryText(title, text, body)
    }

    private class Line(val name: String, quote: Quote) {
        val level: String = formatLevel(quote.price)
        private val before = quote.previousClose
        private val change: BigDecimal? = before?.let { quote.price - it }
        private val percent: BigDecimal? = if (before != null && before.signum() != 0) {
            change!!.multiply(BigDecimal(100)).divide(before, 2, RoundingMode.HALF_UP)
        } else null

        /** "▲ 1.23%", "▼ 0.31%", "0.00%", or blank with no previous close. [withPoints] adds the points: "▲ 297.60 (1.23%)". */
        fun move(withPoints: Boolean): String {
            val c = change ?: return ""
            val p = percent ?: return ""
            val arrow = when {
                p.signum() > 0 -> "▲ "
                p.signum() < 0 -> "▼ "
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
