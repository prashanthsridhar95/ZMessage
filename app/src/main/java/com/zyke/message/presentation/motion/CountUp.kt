package com.zyke.message.presentation.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import kotlin.math.roundToInt

/**
 * A whole number that counts to its new value in [Motion.COUNT_MS] and, with [fromZero], counts up from zero the first time it is
 * shown ("8 to go" on Home's deck). It is not money, so it is not private; it is still static when motion is reduced, and it always
 * ends on the exact figure. The value is read through a derived state, so only the digits' own leaf recomposes, once per whole number.
 */
@Composable
fun rememberCountUpInt(value: Int, fromZero: Boolean = false): Int {
    val motion = rememberMotion()
    val progress = remember { Animatable(if (fromZero && !motion.reduced) 0f else value.toFloat()) }
    LaunchedEffect(value, motion.reduced) {
        if (motion.reduced) progress.snapTo(value.toFloat()) else progress.animateTo(value.toFloat(), motion.tween(Motion.COUNT_MS))
    }
    val shown by remember { derivedStateOf { progress.value.roundToInt() } }
    return shown
}
