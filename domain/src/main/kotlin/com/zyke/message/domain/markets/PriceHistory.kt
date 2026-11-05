package com.zyke.message.domain.markets

import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** The periods the stock sheet offers. [range] and [interval] are what Yahoo's chart endpoint is asked for. */
enum class ChartRange(val label: String, val range: String, val interval: String, val period: String, val intraday: Boolean) {
    DAY("1D", "1d", "5m", "Today", true),
    WEEK("1W", "5d", "30m", "Past Week", true),
    MONTH("1M", "1mo", "1d", "Past Month", false),
    QUARTER("3M", "3mo", "1d", "Past 3 Months", false),
    HALF_YEAR("6M", "6mo", "1d", "Past 6 Months", false),
    YEAR_TO_DATE("YTD", "ytd", "1d", "Year to Date", false),
    YEAR("1Y", "1y", "1wk", "Past Year", false),
    FIVE_YEARS("5Y", "5y", "1mo", "Past 5 Years", false);

    /** How long a fetched chart is reused before asking again. */
    val freshSeconds: Int get() = if (this == DAY) 60 else 300
}

/** One point of a chart: seconds since 1970 and the price (or level) then. */
data class PricePoint(val time: Long, val value: Double)

/** A chart and the day's figures for one ticker, as Yahoo's chart reply gave them. Anything it did not say is null. */
data class PriceHistory(
    val symbol: String,
    val range: ChartRange,
    val name: String,
    val currency: String,
    val exchange: String,
    /** Oldest first, at most [PriceHistories.MAX_POINTS], gaps (null closes) already dropped. */
    val points: List<PricePoint>,
    val price: Double?,
    val previousClose: Double?,
    val open: Double?,
    val dayHigh: Double?,
    val dayLow: Double?,
    val volume: Long?,
    val high52: Double?,
    val low52: Double?,
    /** The exchange's offset from UTC in seconds, to show its own local dates and hours. */
    val offsetSeconds: Int = 0
) {
    /** The price now: the service's, else the last point of the chart. */
    val last: Double? get() = price ?: points.lastOrNull()?.value

    /**
     * What the change is measured from: for one day the previous close; for longer periods the first point of the chart,
     * which is how Apple Stocks and Yahoo show it.
     */
    val baseline: Double?
        get() = if (range == ChartRange.DAY) previousClose ?: points.firstOrNull()?.value else points.firstOrNull()?.value

    val change: Double? get() = last?.let { now -> baseline?.let { now - it } }

    val changePercent: Double? get() = change?.let { c -> baseline?.takeIf { it != 0.0 }?.let { c / it * 100.0 } }

    val hasChart: Boolean get() = points.size >= 2

    /**
     * This chart with the day's figures it lacks filled in from [earlier] (the same ticker's chart for another period).
     * Open and previous close come from a one-day chart only, so switching to a longer period keeps showing them.
     */
    fun withStatsFrom(earlier: PriceHistory?): PriceHistory {
        if (earlier == null || earlier.symbol != symbol) return this
        return copy(
            previousClose = previousClose ?: earlier.previousClose,
            open = open ?: earlier.open,
            dayHigh = dayHigh ?: earlier.dayHigh,
            dayLow = dayLow ?: earlier.dayLow,
            volume = volume ?: earlier.volume,
            high52 = high52 ?: earlier.high52,
            low52 = low52 ?: earlier.low52,
            name = name.ifBlank { earlier.name },
            currency = currency.ifBlank { earlier.currency },
            exchange = exchange.ifBlank { earlier.exchange }
        )
    }
}

/** One label under the chart: where it sits (0 at the left edge, 1 at the right) and what it says. */
data class AxisLabel(val fraction: Float, val text: String)

object PriceHistories {
    const val MAX_POINTS = 200

    /**
     * At most [max] points that keep the shape: the first and last point, and the lowest and highest of each stretch
     * between, in time order. A chart drawn from these looks like the original, with its peaks and dips.
     */
    fun downsample(points: List<PricePoint>, max: Int = MAX_POINTS): List<PricePoint> {
        if (max < 4 || points.size <= max) return points
        val inner = points.subList(1, points.size - 1)
        val buckets = (max - 2) / 2
        val out = ArrayList<PricePoint>(max)
        out += points.first()
        for (b in 0 until buckets) {
            val from = b * inner.size / buckets
            val to = (b + 1) * inner.size / buckets
            if (from >= to) continue
            var lo = from
            var hi = from
            for (i in from until to) {
                if (inner[i].value < inner[lo].value) lo = i
                if (inner[i].value > inner[hi].value) hi = i
            }
            if (lo == hi) out += inner[lo] else if (lo < hi) { out += inner[lo]; out += inner[hi] } else { out += inner[hi]; out += inner[lo] }
        }
        out += points.last()
        return out
    }

    /** Round-number values from just below [min] to just above [max], about [count] of them, for the side of the chart. */
    fun niceTicks(min: Double, max: Double, count: Int = 4): List<Double> {
        if (!min.isFinite() || !max.isFinite() || count < 2) return emptyList()
        val span = max - min
        if (span <= 0.0) return listOf(min)
        val rough = span / (count - 1)
        val magnitude = 10.0.pow(floor(log10(rough)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * magnitude }.first { it >= rough }
        val start = ceil(min / step) * step
        val ticks = ArrayList<Double>()
        var v = start
        var guard = 0
        while (v <= max + step * 1e-9 && guard++ < 12) {
            ticks += if (abs(v) < step * 1e-9) 0.0 else v
            v += step
        }
        return ticks
    }

    /** Decimal places that tell the [ticks] apart: none for whole-number steps, otherwise as many as the step needs (at most 4). */
    fun tickDigits(ticks: List<Double>): Int {
        if (ticks.size < 2) return 2
        val step = abs(ticks[1] - ticks[0])
        for (digits in 0..3) {
            val scaled = step * 10.0.pow(digits)
            if (abs(scaled - Math.rint(scaled)) < 1e-6) return digits
        }
        return 4
    }

    /**
     * The date or hour labels under the chart. The chart is drawn by position in the list (so a closed market leaves no
     * gap), so labels sit at evenly spaced points; for a week they are the first point of each day. They are written in
     * the exchange's own time.
     */
    fun xLabels(history: PriceHistory, count: Int = 4, locale: Locale = Locale.getDefault()): List<AxisLabel> {
        val points = history.points
        if (points.size < 2) return emptyList()
        val zone = ZoneOffset.ofTotalSeconds(history.offsetSeconds.coerceIn(-64_800, 64_800))
        val pattern = when (history.range) {
            ChartRange.DAY -> "h a"
            ChartRange.WEEK -> "EEE"
            ChartRange.MONTH, ChartRange.QUARTER, ChartRange.HALF_YEAR, ChartRange.YEAR_TO_DATE -> "d MMM"
            ChartRange.YEAR -> "MMM"
            ChartRange.FIVE_YEARS -> "yyyy"
        }
        val format = DateTimeFormatter.ofPattern(pattern, locale).withZone(zone)
        val last = (points.size - 1).toFloat()
        val indices: List<Int> = if (history.range == ChartRange.WEEK) {
            val days = ArrayList<Int>()
            var previous: Long? = null
            points.forEachIndexed { i, p ->
                val day = Math.floorDiv(p.time + history.offsetSeconds, 86_400L)
                if (day != previous) days += i
                previous = day
            }
            if (days.size in 2..6) days else evenly(points.size, count)
        } else {
            evenly(points.size, count)
        }
        return indices.map { i -> AxisLabel(i / last, format.format(Instant.ofEpochSecond(points[i].time))) }
    }

    /** The label for one moment (while a finger is on the chart): the hour for a day or week, the date otherwise. */
    fun momentLabel(history: PriceHistory, time: Long, locale: Locale = Locale.getDefault()): String {
        val zone = ZoneOffset.ofTotalSeconds(history.offsetSeconds.coerceIn(-64_800, 64_800))
        val pattern = when (history.range) {
            ChartRange.DAY -> "h:mm a"
            ChartRange.WEEK -> "EEE d MMM, h:mm a"
            ChartRange.FIVE_YEARS, ChartRange.YEAR -> "d MMM yyyy"
            else -> "EEE d MMM yyyy"
        }
        return DateTimeFormatter.ofPattern(pattern, locale).withZone(zone).format(Instant.ofEpochSecond(time))
    }

    private fun evenly(size: Int, count: Int): List<Int> {
        val n = count.coerceIn(2, size)
        // The last point is left unlabelled at the very edge: a label centred on it would run off the screen.
        return (0 until n).map { it * (size - 1) / n }.distinct()
    }
}

/**
 * Reads Yahoo Finance's chart reply (`chart.result[0]`: `meta`, `timestamp`, `indicators.quote[0]`). Only the fields the
 * stock sheet needs are looked at, a missing or odd one is simply null, and a reply without a usable price or chart
 * (unknown ticker, error body, garbage) gives null.
 */
object PriceHistoryParser {
    private val SUB_UNITS = setOf("GBp", "ZAc")

    fun parse(symbol: String, range: ChartRange, json: String): PriceHistory? {
        val root = MiniJson.parse(json) as? Map<*, *> ?: return null
        val chart = root["chart"] as? Map<*, *> ?: return null
        val result = (chart["result"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return null
        val meta = result["meta"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
        val quote = (((result["indicators"] as? Map<*, *>)?.get("quote")) as? List<*>)?.firstOrNull() as? Map<*, *>
        val times = result["timestamp"] as? List<*> ?: emptyList<Any?>()
        val closes = quote?.get("close") as? List<*> ?: emptyList<Any?>()
        val opens = quote?.get("open") as? List<*> ?: emptyList<Any?>()

        val raw = ArrayList<PricePoint>(times.size)
        for (i in 0 until minOf(times.size, closes.size)) {
            val t = (times[i] as? Double)?.toLong() ?: continue
            val v = (closes[i] as? Double)?.takeIf { it.isFinite() && it > 0.0 } ?: continue
            if (raw.isEmpty() || t > raw.last().time) raw += PricePoint(t, v)
        }
        val price = number(meta["regularMarketPrice"])?.takeIf { it > 0.0 }
        if (price == null && raw.size < 2) return null

        val currency = (meta["currency"] as? String)?.trim().orEmpty().let { if (it in SUB_UNITS) it else it.uppercase() }.takeIf { it.length == 3 }.orEmpty()
        val name = ((meta["longName"] as? String) ?: (meta["shortName"] as? String))?.trim().orEmpty().take(MAX_NAME)
        // Yahoo's "previousClose" is missing from many replies; "chartPreviousClose" is the close before the chart began.
        val previous = number(meta["previousClose"]) ?: if (range == ChartRange.DAY) number(meta["chartPreviousClose"]) else null
        return PriceHistory(
            symbol = symbol,
            range = range,
            name = name,
            currency = currency,
            exchange = MarketNames.exchange(meta["exchangeName"] as? String, meta["fullExchangeName"] as? String),
            points = PriceHistories.downsample(raw),
            price = price,
            previousClose = previous?.takeIf { it > 0.0 },
            // Only an intraday chart knows today's opening price; a longer one would give the first day's, not today's.
            open = if (range == ChartRange.DAY) opens.firstNotNullOfOrNull { number(it)?.takeIf { v -> v > 0.0 } } else null,
            dayHigh = number(meta["regularMarketDayHigh"])?.takeIf { it > 0.0 },
            dayLow = number(meta["regularMarketDayLow"])?.takeIf { it > 0.0 },
            volume = number(meta["regularMarketVolume"])?.takeIf { it > 0.0 }?.toLong(),
            high52 = number(meta["fiftyTwoWeekHigh"])?.takeIf { it > 0.0 },
            low52 = number(meta["fiftyTwoWeekLow"])?.takeIf { it > 0.0 },
            offsetSeconds = number(meta["gmtoffset"])?.toInt() ?: 0
        )
    }

    private fun number(value: Any?): Double? = (value as? Double)?.takeIf { it.isFinite() }

    private const val MAX_NAME = 60
}

/** A number of shares traded, short: 1234 stays, 12,500 becomes 12.5K, 3,400,000 becomes 3.4M. */
object VolumeText {
    fun short(volume: Long): String = when {
        volume >= 1_000_000_000L -> scaled(volume / 1_000_000_000.0, "B")
        volume >= 1_000_000L -> scaled(volume / 1_000_000.0, "M")
        volume >= 10_000L -> scaled(volume / 1_000.0, "K")
        else -> volume.toString()
    }

    private fun scaled(v: Double, unit: String): String {
        val one = Math.round(v * 10) / 10.0
        return (if (one >= 100 || one == Math.rint(one)) Math.round(one).toString() else one.toString()) + unit
    }
}
