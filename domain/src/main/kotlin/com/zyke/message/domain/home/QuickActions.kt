package com.zyke.message.domain.home

/**
 * Home's two quick actions: "Add entry" (the main one) and "Ask AI" (small, above it, only with the on-device AI switched on).
 * The pure rules, so they can be tested: when the stack shows, when "Add entry" carries its words, and how much room the list keeps
 * at its end so the stack never covers an amount.
 */
object QuickActions {
    /** The main button's size, and the small one's (both are at least a 48 dp target). */
    const val ADD_DP = 56
    const val ASK_DP = 48
    const val GAP_DP = 12
    const val MARGIN_DP = 16

    /** A scroll shorter than this (in pixels) never flips the stack: a finger resting on the list is not a direction. */
    const val SCROLL_SLOP_PX = 8f

    /** Down the list hides the stack, up brings it back, at the very top it is always there. [delta] is the finger's travel (negative = list moving up). */
    fun shown(current: Boolean, delta: Float, atTop: Boolean): Boolean = when {
        atTop -> true
        delta <= -SCROLL_SLOP_PX -> false
        delta >= SCROLL_SLOP_PX -> true
        else -> current
    }

    /** "Add entry" shows its words only at the top of Home; scrolled, it is the plus alone. */
    fun extended(atTop: Boolean): Boolean = atTop

    /** The room kept under the last item of Home so the stack never rests on an amount. */
    fun reservedBottomDp(askShown: Boolean): Int = MARGIN_DP + ADD_DP + (if (askShown) GAP_DP + ASK_DP else 0) + MARGIN_DP
}
