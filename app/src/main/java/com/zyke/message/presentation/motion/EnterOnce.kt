package com.zyke.message.presentation.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.motion.MotionMath
import kotlinx.coroutines.delay

/**
 * Remembers whether a screen's entrance has already played, so it plays once when the screen is first shown and never
 * again: not when a lazy list scrolls items back into view, not when the screen recomposes, not after a rotation and
 * not when the person comes back to the tab. Create it high enough that it outlives the content (above a loading
 * state, not inside it) with [rememberEnterGate].
 */
@Stable
class EnterGate internal constructor(played: Boolean) {
    /** True once any item of this screen has finished entering; items shown after that simply appear. */
    var played: Boolean by mutableStateOf(played)
        internal set

    internal fun shouldAnimate(index: Int): Boolean = !played && MotionMath.staggers(index)

    companion object {
        internal val Saver: Saver<EnterGate, Boolean> = Saver(save = { it.played }, restore = { EnterGate(it) })
    }
}

/** The gate for one screen; survives rotation and leaving and returning to the tab. */
@Composable
fun rememberEnterGate(): EnterGate = rememberSaveable(saver = EnterGate.Saver) { EnterGate(false) }

/**
 * True when the item at [index] is part of the entrance that is about to play (so a number in it may count up from zero
 * too). Decided once, when the item is first composed; false when motion is reduced.
 */
@Composable
fun EnterGate.rememberIsEntering(index: Int): Boolean {
    val reduced = rememberMotion().reduced
    return remember { !reduced && shouldAnimate(index) }
}

/**
 * Fades an item in while it rises [Motion.ENTER_RISE_DP] dp, each a little after the one before (see MotionMath: 40 ms
 * apart, only the first eight, over in about half a second). Only on the first showing of the screen (see [EnterGate]);
 * after that, for items beyond the eighth and when motion is reduced, it returns the modifier unchanged and nothing is
 * allocated. The progress is read inside graphicsLayer, so a frame redraws the item and does not recompose it.
 */
@Composable
fun Modifier.enterOnce(gate: EnterGate, index: Int): Modifier {
    val motion = rememberMotion()
    val run = remember { !motion.reduced && gate.shouldAnimate(index) }
    if (!run) return this
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(MotionMath.staggerDelayMs(index).toLong())
        progress.animateTo(1f, motion.tween(Motion.STANDARD_MS, Motion.Standard))
        gate.played = true
    }
    val rise = with(LocalDensity.current) { Motion.ENTER_RISE_DP.dp.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * rise
    }
}
