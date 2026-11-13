package com.zyke.message.presentation.home

import androidx.compose.animation.core.animate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.accounts.PullReveal
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import kotlin.math.max

/** The settings-store key of the first-time hint under the Overview header ("Pull down to see net worth"); gone after the first reveal. */
internal const val NET_WORTH_PULL_TIP = "tip-networth-pull"

/** How tall the quiet line that the list slides down to uncover is. */
private val PULL_STRIP_HEIGHT = 56.dp

/**
 * Pull down at the top of the Overview tab, as a gesture that only decides something at the end of it. The finger's drag at the top
 * (what the list itself had no use for) stretches the content down with rubber-band resistance ([PullReveal.stretch]); crossing the
 * threshold arms the pull with one light tick; letting go while armed calls the reveal (which asks for the screen lock when amounts
 * are hidden), letting go early only springs back. The numbers and the decisions are in the tested [PullReveal]; this only
 * feeds it. [dragPx] and [stretchPx] are read in the draw phase (`graphicsLayer`), so a pull recomposes nothing.
 */
@Stable
internal class NetWorthPull(
    private val thresholdPx: Float,
    private val enabled: () -> Boolean,
    private val onArmed: () -> Unit,
    private val onReveal: () -> Unit,
    private val settle: suspend (from: Float, update: (Float) -> Unit) -> Unit
) {
    /** How far the finger has dragged down in all, in px (the stretch is less: it is resisted). */
    var dragPx by mutableFloatStateOf(0f)
        private set
    var phase by mutableStateOf(PullReveal.Phase.IDLE)
        private set

    val stretchPx: Float get() = PullReveal.stretch(dragPx, thresholdPx)
    val fraction: Float get() = PullReveal.fraction(stretchPx, thresholdPx)

    fun reset() = moveTo(0f)

    private fun moveTo(drag: Float) {
        val before = phase
        dragPx = max(0f, drag)
        phase = PullReveal.phase(stretchPx, thresholdPx, before)
        if (before != PullReveal.Phase.ARMED && phase == PullReveal.Phase.ARMED) onArmed()
    }

    private val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // Pushing back up while a pull is under way takes the pull back first, so the list does not move under it.
            if (dragPx <= 0f || available.y >= 0f) return Offset.Zero
            val back = max(available.y, -dragPx)
            moveTo(dragPx + back)
            return Offset(0f, back)
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (!enabled() || available.y <= 0f || source != NestedScrollSource.UserInput) return Offset.Zero
            moveTo(dragPx + available.y)
            return Offset(0f, available.y)
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (dragPx <= 0f) return Velocity.Zero
            // Letting go: an armed pull opens at once (the lock screen, if any, appears while the content settles back).
            if (PullReveal.opensOnRelease(phase)) onReveal()
            settle(dragPx) { moveTo(it) }
            reset()
            return available
        }
    }

    val modifier: Modifier get() = Modifier.nestedScroll(connection)

    /** Drops a pull that is no longer allowed (the panel opened, the tab changed). */
    fun cancelIfDisabled() { if (!enabled() && dragPx > 0f) reset() }
}

@Composable
internal fun rememberNetWorthPull(enabled: Boolean, onReveal: () -> Unit): NetWorthPull {
    val density = LocalDensity.current
    val threshold = remember(density) { with(density) { PullReveal.THRESHOLD_DP.dp.toPx() } }
    val haptics = rememberHaptics()
    val motion = rememberMotion()
    val on = rememberUpdatedState(enabled)
    val reveal = rememberUpdatedState(onReveal)
    val tick = rememberUpdatedState(haptics)
    val spec = rememberUpdatedState(motion)
    val pull = remember(threshold) {
        NetWorthPull(
            threshold, { on.value }, { tick.value.tick() }, { reveal.value() },
            { from, update -> animate(from, 0f, animationSpec = spec.value.tween<Float>(Motion.QUICK_MS, Motion.Standard)) { value, _ -> update(value) } }
        )
    }
    LaunchedEffect(enabled) { pull.cancelIfDisabled() }
    return pull
}

/**
 * The quiet line the list slides down to uncover: it fades in with the pull and says what letting go will do. It is decoration for
 * a gesture; screen-reader users have the "Show net worth" action and button instead.
 */
@Composable
internal fun NetWorthPullStrip(pull: NetWorthPull, modifier: Modifier = Modifier) {
    val armed = pull.phase == PullReveal.Phase.ARMED
    Box(
        modifier.fillMaxWidth().height(PULL_STRIP_HEIGHT).graphicsLayer {
            alpha = pull.fraction
            // Its bottom edge follows the top edge of the list as the list is drawn down.
            translationY = pull.stretchPx - size.height
        }.clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Caption(if (armed) "Release to see net worth" else "Pull down to see net worth")
    }
}
