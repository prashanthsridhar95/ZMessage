package com.zyke.message.presentation.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.motion.MotionMath

/**
 * [target] (clamped to 0..1) approached from zero when first shown, then eased to any new value. Read the result only
 * while drawing (drawBehind, graphicsLayer, Canvas): a frame then repaints the bar and does not recompose it.
 */
@Composable
fun rememberProgressReveal(target: Float): State<Float> {
    val motion = rememberMotion()
    val goal = MotionMath.clampProgress(target)
    if (motion.reduced) return rememberUpdatedState(goal)
    val shown = remember { Animatable(0f) }
    LaunchedEffect(goal) { shown.animateTo(goal, motion.tween(Motion.EMPHASIZED_MS, Motion.Standard)) }
    return shown.asState()
}

/** A thin progress bar whose fill grows from empty to [fraction] once when first shown (see [rememberProgressReveal]). */
@Composable
fun ProgressReveal(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f),
    height: Dp = 4.dp
) {
    val shown = rememberProgressReveal(fraction)
    val radius = height / 2
    Box(modifier.fillMaxWidth().height(height).background(track, RoundedCornerShape(radius))) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                val width = size.width * shown.value
                if (width > 0f) drawRoundRect(color, size = Size(width, size.height), cornerRadius = CornerRadius(radius.toPx()))
            }
        )
    }
}
