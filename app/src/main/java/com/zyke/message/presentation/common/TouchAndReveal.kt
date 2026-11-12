package com.zyke.message.presentation.common

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.semantics.Role
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberProgressReveal

/**
 * A tappable chip, tile or row: it dips a little while held (see pressFeedback) and keeps its ripple, role and
 * semantics. [tick] gives the light haptic tick on a tap (a choice or a switch); leave it off where the tap only opens
 * something. Cards use FintechCard(onClick), which already does this.
 */
@Composable
fun Modifier.tappable(
    onClick: () -> Unit,
    enabled: Boolean = true,
    role: Role = Role.Button,
    onClickLabel: String? = null,
    tick: Boolean = false
): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptics = rememberHaptics()
    return this
        .pressFeedback(source, pressedScale = Motion.PRESSED_SCALE, pressedAlpha = 1f)
        .clickable(
            role = role, enabled = enabled, onClickLabel = onClickLabel,
            interactionSource = source, indication = LocalIndication.current,
            onClick = { if (tick) haptics.tick(); onClick() }
        )
}

/**
 * Reveals what is drawn, left to right, once, when first shown (a segmented bar growing into place). The animation is
 * read while drawing, so a frame repaints the bar and nothing recomposes; instant when motion is reduced.
 */
@Composable
fun Modifier.revealOnce(): Modifier {
    val shown = rememberProgressReveal(1f)
    return drawWithContent {
        val share = shown.value
        if (share >= 1f) drawContent() else clipRect(right = size.width * share) { this@drawWithContent.drawContent() }
    }
}
