package com.zyke.message.presentation.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.zyke.message.domain.motion.MotionMath
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.format.DisplayFormatters
import java.math.BigDecimal

private const val COUNT_STEPS = 16

/**
 * A money figure that counts to its new value when it changes (400 ms, once per change), and optionally counts up from
 * zero the first time it is shown ([fromZero], for a hero figure on the screen's entrance).
 *
 * It is a [PrivateAmount], so hidden amounts stay dots and the screen lock still guards them; while amounts are hidden,
 * or motion is reduced, nothing is animated at all (and revealing does not count either). With [fit] the size is
 * worked out for the final figure, so the digits do not make it shrink and grow while counting. Only this small leaf
 * recomposes, about sixteen times, and never on scroll.
 */
@Composable
fun AnimatedAmount(
    value: BigDecimal,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
    textAlign: TextAlign? = null,
    maxLines: Int = 1,
    fit: Boolean = false,
    fromZero: Boolean = false
) {
    val shown = rememberCountUpText(value, fromZero)
    PrivateAmount(
        shown, modifier, color = color, style = style, fontWeight = fontWeight, textAlign = textAlign,
        maxLines = maxLines, fit = fit, sizeAgainst = DisplayFormatters.rupees(value)
    )
}

/**
 * The rupee text for [value]: the final figure when nothing is changing, an in-between figure while it counts. The
 * first showing is not animated unless [fromZero]. Never starts an animation while amounts are hidden or motion is reduced.
 */
@Composable
fun rememberCountUpText(value: BigDecimal, fromZero: Boolean = false): String {
    val motion = rememberMotion()
    val hidden = LocalPrivacyMode.current.hidden
    val still = motion.reduced || hidden
    val progress = remember { Animatable(if (fromZero && !still) 0f else 1f) }
    var from by remember { mutableStateOf(if (fromZero && !still) BigDecimal.ZERO else value) }
    var to by remember { mutableStateOf(value) }

    LaunchedEffect(value, still) {
        if (still) {
            progress.snapTo(1f)
            from = value
            to = value
            return@LaunchedEffect
        }
        if (value.compareTo(to) != 0 || progress.value < 1f) {
            // Start from the figure on screen right now, so a change in the middle of a count does not jump.
            val onScreen = MotionMath.countUp(from, to, progress.value, MotionMath.countScale(value))
            from = onScreen
            to = value
            progress.snapTo(0f)
            progress.animateTo(1f, motion.tween(Motion.COUNT_MS, Motion.Standard))
        }
    }

    // Quantised, so the text is rebuilt a few times and not on every frame.
    val step by remember { derivedStateOf { MotionMath.quantise(progress.value, COUNT_STEPS) } }
    // Nothing is changing: the figure the count last settled on (the old one until the effect has seen a new value).
    if (still) return DisplayFormatters.rupees(value)
    val goal = to
    if (step >= COUNT_STEPS) return DisplayFormatters.rupees(goal)
    val digits = MotionMath.countScale(goal)
    return DisplayFormatters.rupeesFixed(MotionMath.countUp(from, goal, step / COUNT_STEPS.toFloat(), digits), digits)
}
