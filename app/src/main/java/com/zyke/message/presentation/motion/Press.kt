package com.zyke.message.presentation.motion

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.flow.collectLatest

/**
 * A tappable card or row dips a little while it is held (a scale of [pressedScale] and a slightly lower opacity) and
 * springs back on release, so a tap is felt before anything else changes. Pass the same [source] the clickable uses:
 * the ripple, the role and everything TalkBack hears stay exactly as they were, this only watches the presses.
 *
 * Nothing is recomposed while pressing: the value lives in an Animatable that is read inside graphicsLayer. Does nothing
 * (no layer is added) when motion is reduced.
 */
@Composable
fun Modifier.pressFeedback(
    source: InteractionSource,
    pressedScale: Float = Motion.CARD_PRESSED_SCALE,
    pressedAlpha: Float = Motion.PRESSED_ALPHA
): Modifier {
    val motion = rememberMotion()
    if (motion.reduced) return this
    val dip = remember { Animatable(0f) }
    LaunchedEffect(source, motion) {
        source.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> dip.animateTo(1f, motion.snappy())
                is PressInteraction.Release, is PressInteraction.Cancel -> dip.animateTo(0f, motion.snappy())
            }
        }
    }
    return graphicsLayer {
        val d = dip.value
        val s = 1f - (1f - pressedScale) * d
        scaleX = s
        scaleY = s
        alpha = 1f - (1f - pressedAlpha) * d
    }
}
