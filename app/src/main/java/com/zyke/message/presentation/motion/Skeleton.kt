package com.zyke.message.presentation.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A static, rounded placeholder block. It does not shimmer or pulse (a loop keeps the GPU busy for as long as the data
 * is slow): it fades in once and then stays still. Size it with the caller's modifier.
 */
@Composable
fun SkeletonBlock(modifier: Modifier = Modifier, corner: Dp = 6.dp) {
    Spacer(modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f), RoundedCornerShape(corner)))
}

/** A modifier that fades its content in once (200 ms) when it first appears; for a whole skeleton, so it does not blink on. */
@Composable
fun Modifier.fadeInOnce(): Modifier {
    val motion = rememberMotion()
    if (motion.reduced) return this
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) { alpha.animateTo(1f, motion.tween(Motion.STANDARD_MS)) }
    return graphicsLayer { this.alpha = alpha.value }
}
