package com.zyke.message.presentation.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.motion.rememberCountUpText
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberProgressReveal
import androidx.compose.ui.graphics.graphicsLayer
import java.math.BigDecimal

/*
 * Legacy entry points kept for the many callers that use them; they now delegate to the motion package
 * (presentation/motion), where the tokens and the reduce-motion switch live. See docs/DESIGN.md.
 */

/**
 * The rupee text for [target], counting from the value on screen to the new one when it changes. The first time it shows
 * it simply appears, so scrolling a list or redrawing an item never replays it; while amounts are hidden or motion is
 * reduced it never animates. Prefer [com.zyke.message.presentation.motion.AnimatedAmount] in new code.
 */
@Composable
fun rememberAnimatedRupees(target: BigDecimal): String = rememberCountUpText(target)

/** [target] approached from zero when first shown, then eased to any new value; for bars that grow into place. */
@Composable
fun rememberGrowingFraction(target: Float): Float = rememberProgressReveal(target).value

/**
 * [rememberGrowingFraction] as a [State]. Read it only while drawing (inside drawBehind, graphicsLayer or a Canvas),
 * so each animation frame repaints a bar instead of recomposing and re-measuring it and everything beside it.
 */
@Composable
fun rememberGrowingState(target: Float): State<Float> = rememberProgressReveal(target)

/** A light tick under the finger for a switch, an undo or a mark-as-paid. Follows the Haptic feedback setting. */
@Composable
fun rememberTick(): () -> Unit {
    val haptics = rememberHaptics()
    return remember(haptics) { { haptics.tick() } }
}

/** A small dip while pressed, so a tap is felt before anything else changes. Buttons use this; cards use [pressFeedback] with its gentler defaults. */
@Composable
fun Modifier.pressScale(source: InteractionSource, pressed: Float = Motion.PRESSED_SCALE): Modifier =
    pressFeedback(source, pressedScale = pressed, pressedAlpha = 1f)

private const val BORDER_SWEEP_MS = 3500
private const val BORDER_BASE_ALPHA = 0.25f

/**
 * A thin border with a bright stretch that travels slowly round it, for the one place that asks for
 * attention. A faint full outline stays visible, so the shape is clear even where the light has not
 * reached and when the system has animations turned off.
 */
fun Modifier.movingBorder(color: Color, corner: Dp = 20.dp, width: Dp = 1.5.dp): Modifier = composed {
    val reduce = rememberReduceMotion()
    // Held as State and read only while drawing, so each frame redraws and does not recompose.
    val angle = if (reduce) {
        null
    } else {
        rememberInfiniteTransition(label = "border").animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(tween(BORDER_SWEEP_MS, easing = LinearEasing)),
            label = "border-angle"
        )
    }
    // The ring and the sweep brush depend only on the size, so they are built once per size, not once per frame.
    drawWithCache {
        val stroke = width.toPx()
        val outer = corner.toPx()
        val ring = Path().apply {
            fillType = PathFillType.EvenOdd
            addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(outer)))
            addRoundRect(
                RoundRect(stroke, stroke, size.width - stroke, size.height - stroke, CornerRadius((outer - stroke).coerceAtLeast(0f)))
            )
        }
        val middle = androidx.compose.ui.geometry.Offset(size.width / 2f, size.height / 2f)
        val faint = color.copy(alpha = BORDER_BASE_ALPHA)
        val sweep = Brush.sweepGradient(listOf(Color.Transparent, color, Color.Transparent, Color.Transparent), center = middle)
        val radius = hypot(size.width, size.height) / 2f
        onDrawBehind {
            drawPath(ring, faint)
            // With animations off, only the faint full outline stays; the travelling light would be a still smear.
            if (angle != null) clipPath(ring) {
                rotate(angle.value, pivot = middle) {
                    drawCircle(brush = sweep, radius = radius, center = middle)
                }
            }
        }
    }
}
