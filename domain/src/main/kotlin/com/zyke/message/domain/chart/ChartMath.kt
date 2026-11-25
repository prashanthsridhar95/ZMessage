package com.zyke.message.domain.chart

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The arithmetic behind the Insights charts, kept pure so it is tested without a screen: how tall a bar is, whole-number
 * percentages that add up to 100, which bar a finger is over, when a touch earns a haptic tick, and how tall a chart is
 * for the width it is given.
 */
object ChartMath {
    /** The shortest gap between two haptic ticks while a finger slides across a chart. */
    const val TICK_GAP_MS = 60L

    /**
     * [value] as a share of [max] between 0 and 1. Zero stays zero; any positive value is at least [minVisible], so a
     * small bar is still a bar. No [max] to be a share of (zero or less) gives 0.
     */
    fun barFraction(value: BigDecimal, max: BigDecimal, minVisible: Float = 0f): Float {
        if (max.signum() <= 0 || value.signum() <= 0) return 0f
        val share = value.divide(max, 6, RoundingMode.HALF_UP).toFloat().coerceIn(0f, 1f)
        return share.coerceAtLeast(minVisible.coerceIn(0f, 1f))
    }

    /**
     * Whole percentages of [values] that add up to exactly 100 (largest remainder), so a legend never says 99% or 101%.
     * Negative values count as nothing; when nothing is positive every entry is 0.
     */
    fun percentages(values: List<BigDecimal>): List<Int> {
        val positive = values.map { it.max(BigDecimal.ZERO) }
        val total = positive.fold(BigDecimal.ZERO, BigDecimal::add)
        if (total.signum() <= 0) return values.map { 0 }
        val hundred = BigDecimal(100)
        val exact = positive.map { it.multiply(hundred).divide(total, 8, RoundingMode.HALF_UP) }
        val floors = exact.map { it.setScale(0, RoundingMode.FLOOR).toInt() }.toMutableList()
        var left = 100 - floors.sum()
        val order = exact.indices.sortedWith(
            compareByDescending<Int> { exact[it].subtract(BigDecimal(floors[it])) }.thenBy { it }
        )
        var i = 0
        while (left > 0 && order.isNotEmpty()) {
            val at = order[i % order.size]
            if (positive[at].signum() > 0) {
                floors[at] = floors[at] + 1
                left--
            }
            i++
        }
        return floors
    }

    /** Which of [count] equal-width bars across [width] the horizontal position [x] is over; clamped to the ends, -1 with no bars. */
    fun barIndexAt(x: Float, width: Float, count: Int): Int {
        if (count <= 0) return -1
        if (width <= 0f || x.isNaN()) return 0
        return (x / width * count).toInt().coerceIn(0, count - 1)
    }

    /** Which of [count] points spread edge to edge across [width] is nearest to [x]; clamped, -1 with no points. */
    fun pointIndexAt(x: Float, width: Float, count: Int): Int {
        if (count <= 0) return -1
        if (count == 1 || width <= 0f || x.isNaN()) return 0
        return Math.round(x / width * (count - 1)).coerceIn(0, count - 1)
    }

    /**
     * Whether moving a finger from [previous] to [next] earns a tick: only when it lands on a different bar, and not
     * more often than every [minGapMs], so a quick swipe does not buzz continuously.
     */
    fun shouldTick(previous: Int, next: Int, nowMs: Long, lastTickMs: Long, minGapMs: Long = TICK_GAP_MS): Boolean =
        next >= 0 && next != previous && nowMs - lastTickMs >= minGapMs

    /**
     * How tall a chart is for the [widthDp] it is given: [aspect] of the width, a little taller for large text (labels
     * take room), never below [minDp] or above [maxDp].
     */
    fun chartHeightDp(widthDp: Float, fontScale: Float, aspect: Float, minDp: Float, maxDp: Float): Float {
        if (widthDp.isNaN() || widthDp <= 0f) return minDp
        val scale = if (fontScale.isNaN()) 1f else fontScale.coerceIn(1f, 2f)
        val text = 1f + 0.15f * (scale - 1f)
        return (widthDp * aspect * text).coerceIn(minDp, maxOf(minDp, maxDp))
    }

    /** How many legend chips fit in a row: at least one, never more than [max]. */
    fun legendColumns(widthDp: Float, chipDp: Float, gapDp: Float, max: Int = 4): Int {
        if (widthDp.isNaN() || widthDp <= 0f || chipDp <= 0f) return 1
        return ((widthDp + gapDp) / (chipDp + gapDp)).toInt().coerceIn(1, max.coerceAtLeast(1))
    }

    // ---- Scrubbing: a finger slid across a chart reads one point at a time ----

    /** How long a scrubbed point stays after the finger lifts, before the readout goes back to the latest value. */
    const val SCRUB_HOLD_MS = 1500L

    /**
     * Which of [count] points spread edge to edge from [left] to [right] is nearest to [x]; clamped to the ends, -1 with
     * no points, 0 with one (or a plot with no width).
     */
    fun nearestIndex(x: Float, left: Float, right: Float, count: Int): Int {
        if (count <= 0) return -1
        if (count == 1 || x.isNaN() || right - left <= 0f) return 0
        return Math.round((x - left) / (right - left) * (count - 1)).coerceIn(0, count - 1)
    }

    /**
     * Like [nearestIndex] for a chart zoomed to a window: the plot from [left] to [right] shows [span] points starting at
     * point [viewStart] (fractional while it pans); the nearest whole point under [x], clamped to the [count] there are.
     */
    fun windowIndexAt(x: Float, left: Float, right: Float, viewStart: Float, span: Float, count: Int): Int {
        if (count <= 0) return -1
        if (count == 1 || x.isNaN() || right - left <= 0f) return 0
        val at = viewStart + (x - left) / (right - left) * span
        return Math.round(at).coerceIn(0, count - 1)
    }

    /** One step (-1 or 1) from [current] among [count] points; from nothing selected the first step goes to the last point (the latest). */
    fun stepIndex(current: Int, delta: Int, count: Int): Int {
        if (count <= 0) return -1
        if (current < 0) return count - 1
        return (current + delta).coerceIn(0, count - 1)
    }

    /**
     * The left edge of a tooltip [width] wide that should sit centred on [anchor] but never leave a container [container]
     * wide (keeping [margin] to either edge). A tooltip wider than the container starts at the margin.
     */
    fun tooltipLeft(anchor: Float, width: Float, container: Float, margin: Float = 0f): Float {
        val room = container - width - margin
        if (room < margin) return margin.coerceAtLeast(0f)
        return (anchor - width / 2f).coerceIn(margin, room)
    }

    /**
     * The top edge of a tooltip [height] tall for a dot at [dotY]: above it with [gap] between when it fits, else below,
     * and always inside a container [container] tall.
     */
    fun tooltipTop(dotY: Float, height: Float, container: Float, gap: Float): Float {
        val above = dotY - gap - height
        val top = if (above >= 0f) above else dotY + gap
        return top.coerceIn(0f, (container - height).coerceAtLeast(0f))
    }

    /** [value] minus [reference]; zero when either is missing. */
    fun change(value: BigDecimal, reference: BigDecimal?): BigDecimal = if (reference == null) BigDecimal.ZERO else value.subtract(reference)

    /**
     * The change from [reference] to [value] in percent, rounded half up to [decimals] places; null when there is no
     * reference or it is zero (nothing to be a percentage of).
     */
    fun changePercent(value: BigDecimal, reference: BigDecimal?, decimals: Int = 1): BigDecimal? {
        if (reference == null || reference.signum() == 0) return null
        return value.subtract(reference).multiply(BigDecimal(100)).divide(reference.abs(), decimals, RoundingMode.HALF_UP)
    }

    /** Same as [changePercent] for plain numbers (prices). Null for a missing, zero or non-finite reference. */
    fun changePercent(value: Double, reference: Double?, decimals: Int = 2): Double? {
        if (reference == null || reference == 0.0 || !reference.isFinite() || !value.isFinite()) return null
        return BigDecimal((value - reference) / Math.abs(reference) * 100.0).setScale(decimals, RoundingMode.HALF_UP).toDouble()
    }

    /** The triangle that goes with a change: up, down, or nothing for no change. */
    fun changeArrow(delta: Double): String = if (delta > 0.0) "\u25B2" else if (delta < 0.0) "\u25BC" else ""

    /** "+4.2%", "-1.0%" or "0.0%": a signed percentage with [decimals] places, in a locale-independent way. */
    fun signedPercent(percent: Double, decimals: Int = 1): String {
        val text = BigDecimal(Math.abs(percent)).setScale(decimals, RoundingMode.HALF_UP).toPlainString()
        val zero = text.all { it == '0' || it == '.' }
        return (if (zero) "" else if (percent < 0) "-" else "+") + text + "%"
    }
}
