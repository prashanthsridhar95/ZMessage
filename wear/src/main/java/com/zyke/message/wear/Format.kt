package com.zyke.message.wear

import com.zyke.message.domain.wear.WatchSnapshot
import java.text.NumberFormat
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs

/** Money on a small screen. Hidden amounts show as dots; nothing is ever guessed. */
object Format {
    const val DOTS = "••••"
    private val india = Locale.Builder().setLanguage("en").setRegion("IN").build()

    fun rupees(value: Long?, masked: Boolean): String = when {
        masked -> "₹$DOTS"
        value == null -> "—"
        else -> (if (value < 0) "-" else "") + "₹" + NumberFormat.getIntegerInstance(india).format(abs(value))
    }

    /** Short enough for a watch-face complication: 1,079 / 12k / 1.2L. */
    fun compact(value: Long?, masked: Boolean): String {
        if (masked) return "₹$DOTS"
        if (value == null) return "—"
        val a = abs(value)
        val sign = if (value < 0) "-" else ""
        val text = when {
            a >= 10_000_000 -> "%.1fCr".format(Locale.ROOT, a / 10_000_000.0)
            a >= 100_000 -> "%.1fL".format(Locale.ROOT, a / 100_000.0)
            a >= 10_000 -> "${a / 1000}k"
            else -> NumberFormat.getIntegerInstance(india).format(a)
        }
        return "$sign₹$text"
    }

    fun delta(percent: Int?): String? = percent?.let { (if (it > 0) "+" else "") + "$it%" }

    fun dueIn(days: Int) = when (days) { 0 -> "today"; 1 -> "tomorrow"; else -> "in $days days" }

    /** The "today" figure is only true on the day the phone worked it out. */
    fun isStale(snapshot: WatchSnapshot) = !snapshot.isFromToday(LocalDate.now())
}
