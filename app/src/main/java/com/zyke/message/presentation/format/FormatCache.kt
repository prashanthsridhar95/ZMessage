package com.zyke.message.presentation.format

import java.text.NumberFormat
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Formatters are costly to build (a pattern is parsed, a locale looked up) and a list of rows asks for the same few
 * hundreds of times while it scrolls. They are built once per pattern and locale and reused. A change of the phone's
 * language is a different [Locale], so it gets its own.
 */
object FormatCache {
    private val patterns = ConcurrentHashMap<Pair<String, Locale>, DateTimeFormatter>()

    /** A [DateTimeFormatter] is immutable and safe to share between threads. */
    fun pattern(pattern: String, locale: Locale): DateTimeFormatter =
        patterns.getOrPut(pattern to locale) { DateTimeFormatter.ofPattern(pattern, locale) }

    private data class NumberKey(val locale: Locale, val minFraction: Int, val maxFraction: Int)

    // A NumberFormat is not safe to share between threads, so each thread keeps its own.
    private val numbers = object : ThreadLocal<HashMap<NumberKey, NumberFormat>>() {
        override fun initialValue() = HashMap<NumberKey, NumberFormat>()
    }

    fun number(locale: Locale, minFraction: Int, maxFraction: Int): NumberFormat =
        numbers.get()!!.getOrPut(NumberKey(locale, minFraction, maxFraction)) {
            NumberFormat.getNumberInstance(locale).apply {
                minimumFractionDigits = minFraction
                maximumFractionDigits = maxFraction
            }
        }
}
