package com.zyke.message.domain.accounts

import kotlin.math.exp

/**
 * The decision behind "pull down to reveal": how far a finger's drag stretches the header (with rubber-band resistance), and
 * whether the stretch has reached the point where letting go opens the panel. Pure, so the feel is tested without a phone.
 */
object PullReveal {
    /** The stretch, in dp, at which the pull is "armed" (a tick is felt and letting go opens the panel). */
    const val THRESHOLD_DP = 96f

    /** How much of the finger's travel becomes stretch before the threshold. */
    const val RESISTANCE = 0.6f

    /** The stretch can never pass this multiple of the threshold. */
    const val MAX_FACTOR = 1.5f

    /** Once armed, the pull stays armed until the stretch falls below this share of the threshold (no flicker at the line). */
    const val DISARM_AT = 0.85f

    enum class Phase { IDLE, PULLING, ARMED }

    /** The stretch for a finger that has dragged [dragPx] down: linear (resisted) to the threshold, then a soft band to the maximum. */
    fun stretch(dragPx: Float, thresholdPx: Float): Float {
        if (dragPx <= 0f || thresholdPx <= 0f) return 0f
        val linear = dragPx * RESISTANCE
        if (linear <= thresholdPx) return linear
        val room = thresholdPx * (MAX_FACTOR - 1f)
        return thresholdPx + room * (1f - exp(-(linear - thresholdPx) / room))
    }

    /** The drag that gives [stretchPx] (the inverse of [stretch]); used to take a pull back when the finger moves up. */
    fun dragFor(stretchPx: Float, thresholdPx: Float): Float {
        if (stretchPx <= 0f || thresholdPx <= 0f) return 0f
        val room = thresholdPx * (MAX_FACTOR - 1f)
        val linear = if (stretchPx <= thresholdPx) stretchPx
        else thresholdPx - room * kotlin.math.ln(1f - ((stretchPx - thresholdPx) / room).coerceAtMost(0.999f))
        return linear / RESISTANCE
    }

    fun phase(stretchPx: Float, thresholdPx: Float, previous: Phase): Phase = when {
        stretchPx <= 0f -> Phase.IDLE
        stretchPx >= thresholdPx -> Phase.ARMED
        previous == Phase.ARMED && stretchPx >= thresholdPx * DISARM_AT -> Phase.ARMED
        else -> Phase.PULLING
    }

    /** 0 to 1 towards the threshold. */
    fun fraction(stretchPx: Float, thresholdPx: Float): Float = if (thresholdPx <= 0f) 0f else (stretchPx / thresholdPx).coerceIn(0f, 1f)

    /** Letting go opens the panel only from the armed phase. */
    fun opensOnRelease(phase: Phase): Boolean = phase == Phase.ARMED
}
