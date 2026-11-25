package com.zyke.message.presentation.common

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.chart.ChartMath
import com.zyke.message.domain.privacy.AmountMasker
import com.zyke.message.presentation.motion.Haptics
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Spacing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The one scrub for every time-series chart (docs/DESIGN.md, Insights, "Scrubbing"): a finger down anywhere on a chart puts a
 * crosshair and a dot on the nearest point at once, sliding moves it from point to point with a light tick, and a tooltip
 * and the chart's readout follow. Vertical drags go to the page. The chart body never recomposes while scrubbing: the index
 * lives in a [ScrubState] that is read only while drawing (crosshair, dot, bar highlight), while laying out (the tooltip) and
 * in the small [ScrubReadout] / [ScrubTooltip] scopes.
 */

/**
 * Which point a finger is on, shared between the gesture, the drawing, the tooltip and the readout. [index] is -1 when no
 * point is selected. After the finger lifts the point stays for [ChartMath.SCRUB_HOLD_MS], fades, and the chart goes back
 * to showing the latest value (a point chosen with TalkBack's actions stays until the next touch).
 */
@Stable
class ScrubState internal constructor(private val haptics: Haptics) {
    /** The selected point, or [NONE]. Read in draw / layout lambdas or inside [ScrubReadout]. */
    var index by mutableIntStateOf(NONE)
        internal set

    internal var touching by mutableStateOf(false)
    internal var sticky by mutableStateOf(false)
    internal val fade = Animatable(1f)

    private var lastTick = NONE
    private var lastTickAt = 0L

    /** 1 while the point is fully shown, easing to 0 as it goes away. Read while drawing. */
    val strength: Float get() = fade.value

    /** True while a point is selected. */
    val active: Boolean get() = index >= 0

    internal fun press(at: Int) {
        touching = true
        sticky = false
        select(at)
    }

    internal fun move(at: Int) = select(at)

    internal fun lift() {
        touching = false
        lastTick = NONE
    }

    /** The page took the gesture (a vertical drag) or a second finger came down: nothing stays selected. */
    internal fun cancel() {
        touching = false
        lastTick = NONE
        index = NONE
    }

    /** A TalkBack action: [delta] points from the current one among [count]; stays until the next touch. */
    fun step(delta: Int, count: Int) {
        sticky = true
        touching = false
        index = ChartMath.stepIndex(index, delta, count)
    }

    private fun select(at: Int) {
        if (at < 0) return
        val now = SystemClock.uptimeMillis()
        if (ChartMath.shouldTick(lastTick, at, now, lastTickAt)) {
            haptics.tick()
            lastTickAt = now
        }
        lastTick = at
        index = at
    }

    companion object {
        const val NONE = -1
    }
}

/** A [ScrubState] that is reset when [key] changes (a different series or period). */
@Composable
fun rememberScrubState(key: Any? = null): ScrubState {
    val haptics = rememberHaptics()
    val motion = rememberMotion()
    val state = remember(key, haptics) { ScrubState(haptics) }
    LaunchedEffect(state, motion) {
        snapshotFlow { Triple(state.touching, state.index, state.sticky) }.collectLatest { (touching, index, sticky) ->
            if (touching || index < 0 || sticky) {
                state.fade.snapTo(1f)
            } else {
                // Let go: hold for a moment so the figure can be read, then ease away (at once when motion is reduced).
                delay(ChartMath.SCRUB_HOLD_MS)
                state.fade.animateTo(0f, motion.tween(Motion.STANDARD_MS))
                state.index = ScrubState.NONE
            }
        }
    }
    return state
}

/**
 * Makes the chart scrubbable. [indexAt] maps a finger's x (px) and the chart's width (px) to a point (use
 * [ChartMath.nearestIndex], [ChartMath.barIndexAt] or [ChartMath.windowIndexAt] so the touch agrees with the drawing).
 * A touch selects immediately; once the finger has moved sideways past the touch slop it keeps selecting and consumes the
 * drag, but a mostly-vertical drag is left to the page's scroll (and clears the selection); a second finger leaves the
 * gesture to pinch-to-zoom. [speak] says one point for a screen reader (amounts are masked while hidden) and
 * TalkBack gets "Previous point" / "Next point" actions over [count] points.
 */
@Composable
fun Modifier.chartScrub(
    state: ScrubState,
    count: Int,
    indexAt: (x: Float, width: Float) -> Int,
    speak: (index: Int) -> String,
    pointName: String = "point"
): Modifier {
    val hidden = LocalPrivacyMode.current.hidden
    val currentIndexAt = rememberUpdatedState(indexAt)
    val currentSpeak = rememberUpdatedState(speak)
    val currentCount = rememberUpdatedState(count)
    return this
        .pointerInput(state) { scrubGestures(state, currentIndexAt) }
        .semantics {
            customActions = listOf(
                CustomAccessibilityAction("Previous $pointName") { state.step(-1, currentCount.value); true },
                CustomAccessibilityAction("Next $pointName") { state.step(1, currentCount.value); true }
            )
            val at = state.index
            if (at >= 0) {
                val said = currentSpeak.value(at)
                stateDescription = if (hidden) AmountMasker.maskSpoken(said) else said
            }
        }
}

private suspend fun PointerInputScope.scrubGestures(state: ScrubState, indexAt: State<(Float, Float) -> Int>) {
    val slop = viewConfiguration.touchSlop
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val width = size.width.toFloat()
        state.press(indexAt.value(down.position.x, width))
        var travelled = Offset.Zero
        var horizontal = false
        var cancelled = false
        var finished = false
        while (!cancelled && !finished) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id }
            if (change == null || event.changes.count { it.pressed } > 1 || (change.isConsumed && !horizontal)) {
                cancelled = true
            } else if (!change.pressed) {
                finished = true
            } else {
                travelled += change.positionChange()
                if (!horizontal) {
                    if (abs(travelled.y) > slop && abs(travelled.y) > abs(travelled.x)) cancelled = true
                    else if (abs(travelled.x) > slop) horizontal = true
                }
                if (horizontal) {
                    change.consume()
                    state.move(indexAt.value(change.position.x, width))
                }
            }
        }
        if (cancelled) state.cancel() else state.lift()
    }
}

/** The part of a chart's header that follows the finger: [content] gets the selected point, or [ScrubState.NONE]. Only this scope recomposes. */
@Composable
fun ScrubReadout(state: ScrubState, content: @Composable (index: Int) -> Unit) {
    content(state.index)
}

/** The crosshair and dot colours: tokens, no literals. */
@Composable
fun scrubLineColor(): Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

/** The vertical crosshair line at [x] from [top] to [bottom]. [alpha] is [ScrubState.strength]. */
fun DrawScope.drawScrubLine(x: Float, top: Float, bottom: Float, color: Color, alpha: Float, width: Dp = 1.5.dp) {
    drawLine(color.copy(alpha = color.alpha * alpha), Offset(x, top), Offset(x, bottom), width.toPx())
}

/** A dot on the series at [center]: [color] inside a ring of [ring] (the surface), so it reads on top of any line. */
fun DrawScope.drawScrubDot(center: Offset, color: Color, ring: Color, alpha: Float, radius: Dp = 5.dp) {
    drawCircle(ring.copy(alpha = alpha), radius.toPx() + 2.dp.toPx(), center)
    drawCircle(color.copy(alpha = alpha), radius.toPx(), center)
}

/**
 * The floating label that follows the selected point: a small flat, rounded tonal chip holding [content]. It fills its
 * parent (the chart's box), sits centred on [anchorX] and above [anchorY] (below it when there is no room above), and is
 * always inside the chart, however wide the text. Flat (a hairline border, no shadow) in every mode. Draws nothing while no
 * point is selected, takes no touches, and fades with [ScrubState.strength].
 */
@Composable
fun ScrubTooltip(
    state: ScrubState,
    anchorX: (index: Int, width: Float) -> Float,
    anchorY: (index: Int, height: Float) -> Float,
    modifier: Modifier = Modifier,
    content: @Composable (index: Int) -> Unit
) {
    val shown = state.index
    if (shown < 0) return
    val gap = with(androidx.compose.ui.platform.LocalDensity.current) { Spacing.md.toPx() }
    Layout(
        content = {
            Box(
                Modifier
                    .background(MaterialTheme.colorScheme.surfaceVariant, AppShapes.chip)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, AppShapes.chip)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) { content(shown) }
        },
        modifier = modifier.fillMaxSize().graphicsLayer { alpha = state.strength }
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable = measurables.first().measure(Constraints(maxWidth = width, maxHeight = height))
        layout(width, height) {
            val at = state.index.coerceAtLeast(0)
            val left = ChartMath.tooltipLeft(anchorX(at, width.toFloat()), placeable.width.toFloat(), width.toFloat())
            val top = ChartMath.tooltipTop(anchorY(at, height.toFloat()), placeable.height.toFloat(), height.toFloat(), gap)
            placeable.place(left.roundToInt(), top.roundToInt())
        }
    }
}
