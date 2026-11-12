package com.zyke.message.presentation.motion

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * [CrossfadeContent] for a state that changes in ways that should not animate: only a change of [contentKey] fades (a
 * period or a Spent / Received side), while other changes to the state are drawn in place. While it fades out, the old
 * content still shows the state it was drawn for. Instant when motion is reduced.
 */
@Composable
fun <S> CrossfadeKeyed(
    targetState: S,
    contentKey: (S) -> Any?,
    modifier: Modifier = Modifier,
    label: String = "crossfade-keyed",
    content: @Composable (S) -> Unit
) {
    val motion = rememberMotion()
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            if (motion.reduced) {
                EnterTransition.None togetherWith ExitTransition.None
            } else {
                (fadeIn(motion.tween(Motion.STANDARD_MS, delayMs = Motion.INSTANT_MS)) togetherWith
                    fadeOut(motion.tween(Motion.INSTANT_MS))) using
                    SizeTransform(clip = false) { _, _ -> motion.tween(Motion.STANDARD_MS) }
            }
        },
        contentKey = { contentKey(it) ?: Unit },
        label = label
    ) { state -> content(state) }
}
