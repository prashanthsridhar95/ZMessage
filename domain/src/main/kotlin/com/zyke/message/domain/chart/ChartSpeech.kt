package com.zyke.message.domain.chart

/**
 * What a screen reader says about a chart, one sentence per chart: the title, then the biggest parts with their amounts
 * and shares. The caller passes amounts already formatted (and masks the whole sentence while amounts are hidden).
 */
object ChartSpeech {
    /** One part of a whole: a category with its formatted [amount] and whole [percent]. */
    data class Slice(val label: String, val amount: String, val percent: Int)

    /** "Spending by category: Food ₹8,200, 31%; Travel ₹4,000, 15%; and 3 more." Empty [slices] say there is nothing yet. */
    fun shares(title: String, slices: List<Slice>, maxNamed: Int = 5): String {
        if (slices.isEmpty()) return "$title: nothing yet."
        val named = slices.take(maxNamed.coerceAtLeast(1))
        val body = named.joinToString("; ") { "${it.label} ${it.amount}, ${it.percent}%" }
        val more = slices.size - named.size
        return if (more > 0) "$title: $body; and $more more." else "$title: $body."
    }

    /**
     * "Spending by day: 30 days, total ₹12,000, highest ₹1,800 on 12 Oct." [peak] is the formatted highest amount and
     * [peakLabel] when it was; both are left out when there was no activity.
     */
    fun trend(title: String, count: Int, unit: String, total: String, peak: String?, peakLabel: String?): String {
        if (count <= 0) return "$title: nothing yet."
        val units = if (count == 1) "1 $unit" else "$count ${unit}s"
        val high = if (peak != null && peakLabel != null) ", highest $peak on $peakLabel" else ""
        return "$title: $units, total $total$high."
    }

    /** "Income vs spending: income ₹50,000, spent ₹30,000." */
    fun pair(title: String, firstLabel: String, firstAmount: String, secondLabel: String, secondAmount: String): String =
        "$title: $firstLabel $firstAmount, $secondLabel $secondAmount."
}
