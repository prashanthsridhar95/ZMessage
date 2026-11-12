package com.zyke.message.presentation.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Swaps one page of a sequence for the next with a short shared-axis slide: the old page leaves towards the side it came
 * from while the new one arrives from the other, both fading, over [Motion.TAB_MS]. [sign] says which way the change went
 * (+1: the new page comes from the right, as for the next month; -1: from the left; 0: a plain fade). The travel is at most
 * [maxSlideDp] and at most [maxShare] of the page's width, whichever is smaller. Instant when motion is reduced.
 */
@Composable
fun <S> SlideSwap(
    targetState: S,
    sign: (from: S, to: S) -> Int,
    modifier: Modifier = Modifier,
    maxSlideDp: Float = Motion.TAB_SLIDE_DP.toFloat(),
    maxShare: Float = 0.3f,
    label: String = "slide-swap",
    content: @Composable (S) -> Unit
) {
    val motion = rememberMotion()
    val maxPx = with(LocalDensity.current) { maxSlideDp.dp.toPx() }
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (motion.reduced) return@AnimatedContent EnterTransition.None togetherWith ExitTransition.None
            val direction = sign(initialState, targetState)
            val travel: (Int) -> Int = { fullWidth -> min(maxPx, fullWidth * maxShare).roundToInt() }
            (fadeIn(motion.tween(Motion.TAB_MS, delayMs = Motion.INSTANT_MS)) +
                slideInHorizontally(motion.tween(Motion.TAB_MS, Motion.Standard)) { direction * travel(it) })
                .togetherWith(
                    fadeOut(motion.exit(Motion.INSTANT_MS)) +
                        slideOutHorizontally(motion.exit(Motion.TAB_MS)) { -direction * travel(it) }
                )
                .using(SizeTransform(clip = false) { _, _ -> motion.tween(Motion.TAB_MS) })
        },
        label = label
    ) { shown -> content(shown) }
}
