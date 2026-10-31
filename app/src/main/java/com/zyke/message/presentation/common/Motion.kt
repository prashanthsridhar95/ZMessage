package com.zyke.message.presentation.common

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.zyke.message.presentation.format.DisplayFormatters
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The app's motion in one place. Movement is short and always says something: a number that changes
 * counts to its new value, a bar grows to its share, a tab slides the way you moved, a pressed button
 * dips. Durations are brief so nothing ever makes the user wait.
 */
object Motion {
    const val QUICK_MS = 150
    const val STANDARD_MS = 250
    const val COUNT_MS = 600
    const val GROW_MS = 500
    const val PRESSED_SCALE = 0.96f
}

/**
 * The rupee text for [target], counting from the value on screen to the new one when it changes.
 * The first time it shows it simply appears (no animation is started at all), so scrolling a list or
 * redrawing an item never replays it. While counting it keeps the target's decimal places, so the
 * precision does not jump when the count ends.
 */
@Composable
fun rememberAnimatedRupees(target: BigDecimal): String {
    if (rememberReduceMotion()) return DisplayFormatters.rupees(target)
    val goal = target.toFloat()
    val progress = remember { Animatable(goal) }
    LaunchedEffect(goal) {
        if (progress.targetValue != goal) {
            progress.animateTo(goal, tween(Motion.COUNT_MS, easing = FastOutSlowInEasing))
        }
    }
    return if (progress.isRunning) {
        val digits = if (target.stripTrailingZeros().scale() > 0) 2 else 0
        DisplayFormatters.rupeesFixed(BigDecimal(progress.value.toDouble()).setScale(digits, RoundingMode.HALF_UP), digits)
    } else {
        DisplayFormatters.rupees(target)
    }
}

/** [target] approached from zero when first shown, then eased to any new value; for bars that grow into place. */
@Composable
fun rememberGrowingFraction(target: Float): Float {
    if (rememberReduceMotion()) return target
    val grown = remember { Animatable(0f) }
    LaunchedEffect(target) { grown.animateTo(target, tween(Motion.GROW_MS, easing = FastOutSlowInEasing)) }
    return grown.value
}

/** A small dip while pressed, so a tap is felt before anything else changes. */
@Composable
fun Modifier.pressScale(source: InteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val reduce = rememberReduceMotion()
    val scale by animateFloatAsState(if (pressed && !reduce) Motion.PRESSED_SCALE else 1f, tween(Motion.QUICK_MS), label = "press")
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

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
