package com.zyke.message.presentation.motion

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback

/** Whether the person allows haptic feedback (Settings, Appearance, Haptic feedback; on by default). Provided once at the root. */
val LocalHapticsEnabled = staticCompositionLocalOf { true }

/**
 * The app's touch feedback, in two strengths, both off when the person has turned haptics off. Compose 1.7 offers only
 * two feedback types, so [tick] is the light one (a switch, a choice, a confirmed save) and [reject] the heavier one
 * (confirming something destructive).
 */
@Stable
class Haptics internal constructor(private val feedback: HapticFeedback, private val enabled: Boolean) {
    /** A light tick: a switch, a selection, the primary action confirmed. */
    fun tick() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** The primary action went through. Same light tick as [tick]. */
    fun confirm() = tick()

    /** A long press recognised (selecting a row to start a selection). Same firmer buzz as [reject]. */
    fun longPress() {
        if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress)
    }

    /** A firmer buzz: a destructive action confirmed. */
    fun reject() = longPress()
}

@Composable
fun rememberHaptics(): Haptics {
    val feedback = LocalHapticFeedback.current
    val enabled = LocalHapticsEnabled.current
    return remember(feedback, enabled) { Haptics(feedback, enabled) }
}
