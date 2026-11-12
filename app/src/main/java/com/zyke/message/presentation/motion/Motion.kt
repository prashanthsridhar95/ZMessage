package com.zyke.message.presentation.motion

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import com.zyke.message.presentation.common.rememberReduceMotion

/**
 * Motion tokens. The principle: movement explains a change (something entered or left, a value changed, a control was
 * touched); it is never decoration. So there is nothing that loops, everything runs once, and everything is short.
 *
 * Every animation in the app asks [rememberMotion] for its spec. It returns instant specs (and no-op transitions) when
 * the person turned system animations off or lite mode is on (see rememberReduceMotion), so there is one switch and
 * nothing can forget it. Raw `tween(` / `spring(` outside this package is caught by MotionPolicyTest.
 */
object Motion {
    /** A touch acknowledged. */
    const val INSTANT_MS = 90

    /** Small state changes: a switch, a press release, an icon fill, an item leaving. */
    const val QUICK_MS = 160

    /** The default: an item entering, a card expanding, a page or sheet moving. */
    const val STANDARD_MS = 240

    /** Bigger arrivals: a bar growing to its value, a hero figure settling. */
    const val EMPHASIZED_MS = 360

    /** Switching tab: a short shared-axis slide. */
    const val TAB_MS = 200

    /** A money figure counting to its new value. */
    const val COUNT_MS = 400

    /** How far a tab's content travels sideways (dp, at most). */
    const val TAB_SLIDE_DP = 24

    /** How far an entering item rises (dp). */
    const val ENTER_RISE_DP = 12

    /** A pushed page starts this share of the width to the side and fades in. */
    const val PAGE_SLIDE_FRACTION = 0.3f

    const val PRESSED_SCALE = 0.96f
    const val CARD_PRESSED_SCALE = 0.98f
    const val PRESSED_ALPHA = 0.92f

    /** Settles gently: things arriving. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)

    /** Starts slowly, ends fast: things leaving. */
    val Accelerate: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)

    /** Material's emphasized deceleration: the biggest arrivals. */
    val Emphasized: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f)

    /** A quick, firm spring without bounce: press feedback. */
    const val SNAPPY_DAMPING = Spring.DampingRatioNoBouncy
    const val SNAPPY_STIFFNESS = Spring.StiffnessMedium

    /** A slower spring without bounce: things finding their place. */
    const val GENTLE_DAMPING = Spring.DampingRatioNoBouncy
    const val GENTLE_STIFFNESS = Spring.StiffnessMediumLow
}

/**
 * The specs and transitions of the moment: real ones normally, instant ones when [reduced]. Obtained from
 * [rememberMotion]; never constructed elsewhere.
 */
@Immutable
class MotionSpecs internal constructor(val reduced: Boolean) {

    /** A timed animation of [durationMs]; instant when reduced. */
    fun <T> tween(
        durationMs: Int = Motion.STANDARD_MS,
        easing: Easing = Motion.Standard,
        delayMs: Int = 0
    ): FiniteAnimationSpec<T> =
        if (reduced) snap() else androidx.compose.animation.core.tween(durationMs, delayMs, easing)

    /** Something leaving: [durationMs], speeding up; instant when reduced. */
    fun <T> exit(durationMs: Int = Motion.QUICK_MS): FiniteAnimationSpec<T> = tween(durationMs, Motion.Accelerate)

    /** The firm spring for touch feedback; instant when reduced. */
    fun <T> snappy(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(Motion.SNAPPY_DAMPING, Motion.SNAPPY_STIFFNESS)

    /** The soft spring for things finding their place; instant when reduced. */
    fun <T> gentle(): FiniteAnimationSpec<T> =
        if (reduced) snap() else spring(Motion.GENTLE_DAMPING, Motion.GENTLE_STIFFNESS)

    /** A card or row appearing: it fades in while it opens downward from its top edge. */
    val appear: EnterTransition
        get() = if (reduced) EnterTransition.None else
            fadeIn(tween(Motion.STANDARD_MS)) + expandVertically(tween(Motion.STANDARD_MS), expandFrom = Alignment.Top)

    /** A card or row leaving: it fades out quickly while it closes upward. */
    val disappear: ExitTransition
        get() = if (reduced) ExitTransition.None else
            fadeOut(exit(Motion.QUICK_MS)) + shrinkVertically(exit(Motion.STANDARD_MS), shrinkTowards = Alignment.Top)
}

/** The one place a screen gets its motion from; instant when animations are off or lite mode is on. */
@Composable
fun rememberMotion(): MotionSpecs {
    val reduced = rememberReduceMotion()
    return remember(reduced) { MotionSpecs(reduced) }
}
