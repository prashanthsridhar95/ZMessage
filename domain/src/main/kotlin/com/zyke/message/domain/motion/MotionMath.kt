package com.zyke.message.domain.motion

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The arithmetic behind the app's motion, kept pure so it is tested without a screen: how long each item of a list waits
 * before it enters, how a money figure counts from one value to another, and how a progress value is kept in range.
 */
object MotionMath {
    /** The gap between one item starting to enter and the next. */
    const val STAGGER_STEP_MS = 40

    /** Only this many items (from the top) are staggered; the rest simply appear, so a long list never makes anyone wait. */
    const val STAGGER_CAP = 8

    /** The longest any item waits, so the whole entrance is over within about half a second. */
    const val STAGGER_MAX_DELAY_MS = STAGGER_STEP_MS * (STAGGER_CAP - 1)

    /** Whether the item at [index] takes part in the staggered entrance at all. */
    fun staggers(index: Int, cap: Int = STAGGER_CAP): Boolean = index in 0 until cap

    /** How long the item at [index] waits before it starts to enter; 0 for the first, never more than [STAGGER_MAX_DELAY_MS]. */
    fun staggerDelayMs(index: Int, stepMs: Int = STAGGER_STEP_MS, cap: Int = STAGGER_CAP): Int {
        if (index <= 0) return 0
        return (index.coerceAtMost(cap - 1) * stepMs).coerceAtLeast(0)
    }

    /** [value] as a share between 0 and 1; NaN and infinity count as 0, so a bar never draws outside its track. */
    fun clampProgress(value: Float): Float = when {
        value.isNaN() || value.isInfinite() -> 0f
        value < 0f -> 0f
        value > 1f -> 1f
        else -> value
    }

    /** [part] of [whole] as a share between 0 and 1; 1 when there is no whole to be a part of (a budget of nothing is used up). */
    fun share(part: BigDecimal, whole: BigDecimal): Float {
        if (whole.signum() <= 0) return 1f
        return clampProgress(part.divide(whole, 6, RoundingMode.HALF_UP).toFloat())
    }

    /**
     * The value a counting figure shows [fraction] of the way (0 to 1) from [from] to [to], with [scale] decimal places.
     * At 1 and beyond it is exactly [to] (with its own scale), so a count never ends a rupee off.
     */
    fun countUp(from: BigDecimal, to: BigDecimal, fraction: Float, scale: Int): BigDecimal {
        val f = clampProgress(fraction)
        if (f >= 1f) return to
        if (f <= 0f) return from.setScale(scale, RoundingMode.HALF_UP)
        val step = to.subtract(from).multiply(BigDecimal(f.toDouble()))
        return from.add(step).setScale(scale, RoundingMode.HALF_UP)
    }

    /** Decimal places a figure counts with: 2 when [value] has paise, otherwise 0, so the precision does not jump when it ends. */
    fun countScale(value: BigDecimal): Int = if (value.stripTrailingZeros().scale() > 0) 2 else 0

    /** Rounds an animation's 0..1 [fraction] to one of [steps] steps, so a counting figure is redrawn [steps] times, not every frame. */
    fun quantise(fraction: Float, steps: Int): Int = (clampProgress(fraction) * steps).toInt().coerceIn(0, steps)
}
