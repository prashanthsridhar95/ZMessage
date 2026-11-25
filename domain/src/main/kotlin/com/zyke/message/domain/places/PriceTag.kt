package com.zyke.message.domain.places

import java.math.BigDecimal
import java.math.RoundingMode

/** The words on a price tag: "₹1,240" up close, "₹1.2k" when zoomed out, and dots while amounts are hidden. */
object PriceTag {
    const val HIDDEN = "₹ ••••"

    fun label(amount: BigDecimal, compact: Boolean, hidden: Boolean = false): String {
        if (hidden) return HIDDEN
        val a = amount.setScale(0, RoundingMode.HALF_UP)
        if (!compact || a < BigDecimal(1000)) return "₹" + grouped(a.toLong())
        val v = a.toDouble()
        return when {
            v < 100_000 -> "₹" + one(v / 1_000) + "k"
            v < 10_000_000 -> "₹" + one(v / 100_000) + "L"
            else -> "₹" + one(v / 10_000_000) + "Cr"
        }
    }

    /** Whether a tag at [zoom] should use the short form. */
    fun compactAt(zoom: Double): Boolean = zoom < 15.0

    private fun one(x: Double): String {
        val t = Math.floor(x * 10 + 1e-9) / 10.0
        return if (t == Math.floor(t)) t.toLong().toString() else "%.1f".format(java.util.Locale.ROOT, t)
    }

    /** Indian grouping: 12,34,567. */
    private fun grouped(n: Long): String {
        val s = Math.abs(n).toString()
        if (s.length <= 3) return (if (n < 0) "-" else "") + s
        val head = s.dropLast(3)
        val parts = ArrayList<String>()
        var rest = head
        while (rest.length > 2) { parts.add(0, rest.takeLast(2)); rest = rest.dropLast(2) }
        if (rest.isNotEmpty()) parts.add(0, rest)
        return (if (n < 0) "-" else "") + parts.joinToString(",") + "," + s.takeLast(3)
    }
}
