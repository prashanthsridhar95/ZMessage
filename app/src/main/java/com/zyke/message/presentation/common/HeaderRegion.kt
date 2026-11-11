package com.zyke.message.presentation.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.ui.graphics.lerp
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.cards.CategoryShare
import com.zyke.message.domain.cards.CardSpend
import com.zyke.message.domain.cards.DeckModel
import kotlin.math.abs

/*
 * The shared look of the Accounts tab's three sub-tabs (see docs/UNIFICATION.md, "Header region and sheet"):
 *   HeaderRegion       a dark, full-width region with large bottom corners: the hero number or the deck of cards.
 *   StackedDeck        cards that peek out behind each other, swipe to switch, page dots (a plain pager in Lite mode).
 *   ActionCircleRow    up to four round actions with a label under each; a 2 x 2 grid at very large text.
 *   SpendCard          the "Spent this month" structure: big amount, limit, thin bar, note, segmented bar, category rows.
 * Only theme tokens are used, so the same code reads correctly in light, dark and true-black themes.
 */

private val REGION_CORNER = 28.dp

/** The region is dark in both themes: the inverse surface in light, a raised surface in dark. */
@Composable
private fun isLightTheme(): Boolean = MaterialTheme.colorScheme.background.luminance() > 0.5f

@Composable
internal fun headerContentColor(): Color = if (isLightTheme()) MaterialTheme.colorScheme.inverseOnSurface else MaterialTheme.colorScheme.onSurface

/** The header region's own (opaque) colour. */
@Composable
internal fun headerContainerColor(): Color =
    if (isLightTheme()) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHigh

/** A solid tile colour for something lying on the header: the region colour mixed with the content colour, never see-through. */
@Composable
internal fun headerTileColor(strength: Float = 0.10f): Color = lerp(headerContainerColor(), headerContentColor(), strength)

@Composable
internal fun headerMutedColor(): Color = headerContentColor().copy(alpha = 0.72f)

/**
 * The dark region at the top of a sub-tab. It runs edge to edge even when its parent pads the sides by [bleed], and its bottom
 * corners are large, so the lighter content below reads as a sheet lying over it.
 */
@Composable
fun HeaderRegion(modifier: Modifier = Modifier, bleed: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    val container = if (isLightTheme()) MaterialTheme.colorScheme.inverseSurface else MaterialTheme.colorScheme.surfaceContainerHigh
    Column(
        modifier
            .layout { measurable, constraints ->
                val extra = bleed.roundToPx()
                val wide = constraints.copy(minWidth = constraints.maxWidth + 2 * extra, maxWidth = constraints.maxWidth + 2 * extra)
                val placeable = measurable.measure(wide)
                layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
            }
            .background(container, RoundedCornerShape(bottomStart = REGION_CORNER, bottomEnd = REGION_CORNER))
            .padding(horizontal = 16.dp + bleed, vertical = 16.dp),
        content = content
    )
}

/**
 * Which card of a [StackedDeck] is in front, and where the finger has dragged it. [dragPx] is read only in the draw phase, so a
 * drag does not recompose the cards. The deck is saved across rotation by its front page.
 */
@Stable
class DeckState(initialPage: Int = 0) {
    var currentPage by mutableIntStateOf(initialPage)
        internal set
    internal var dragPx by mutableFloatStateOf(0f)
    internal var widthPx by mutableFloatStateOf(0f)
    private var settling = false
    private var job: Job? = null

    /** The way the front card is heading: 1 = to the next card (dragged left), -1 = to the one before, 0 = nowhere. */
    internal fun direction(count: Int): Int = when {
        dragPx < 0f && currentPage < count - 1 -> 1
        dragPx > 0f && currentPage > 0 -> -1
        else -> 0
    }

    internal fun cancelSettle() {
        if (!settling) job?.cancel()
    }

    internal fun dragBy(delta: Float, count: Int) {
        if (settling) return
        val next = dragPx + delta
        val open = (next < 0f && currentPage < count - 1) || (next > 0f && currentPage > 0)
        // With no card that way, the front card only gives a little (a rubber band) and comes back.
        dragPx = if (open) next else (dragPx + delta * RUBBER).coerceIn(-widthPx * 0.25f, widthPx * 0.25f)
    }

    /** Called when the finger lifts: the card flies away if it was dragged far enough or flicked, otherwise it springs back. */
    internal fun release(scope: CoroutineScope, velocity: Float, count: Int, instant: Boolean) {
        if (settling) return
        val dx = dragPx
        val width = widthPx.coerceAtLeast(1f)
        val dir = direction(count)
        val flicked = abs(velocity) > FLICK_PX_PER_S && (velocity < 0f) == (dx < 0f)
        val away = dir != 0 && (abs(dx) > width * DRAG_FRACTION || flicked)
        job?.cancel()
        job = scope.launch { if (away) fly(dir, instant) else settleTo(0f, instant, back = true) }
    }

    /** Moves to the next (1) or previous (-1) card as a swipe would; used by screen-reader actions. */
    internal fun step(scope: CoroutineScope, dir: Int, count: Int, instant: Boolean) {
        if (settling || currentPage + dir !in 0 until count) return
        dragPx = if (dir > 0) -1f else 1f
        job?.cancel()
        job = scope.launch { fly(dir, instant) }
    }

    private suspend fun fly(dir: Int, instant: Boolean) {
        settling = true
        try {
            settleTo(if (dir > 0) -widthPx * 1.25f else widthPx * 1.25f, instant, back = false)
            currentPage += dir
        } finally {
            dragPx = 0f
            settling = false
        }
    }

    private suspend fun settleTo(target: Float, instant: Boolean, back: Boolean) {
        if (instant) {
            dragPx = target
            return
        }
        val spec: AnimationSpec<Float> = if (back) spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessMediumLow) else tween(FLY_MS, easing = FastOutSlowInEasing)
        animate(dragPx, target, animationSpec = spec) { value, _ -> dragPx = value }
    }

    private companion object {
        const val RUBBER = 0.35f
        const val DRAG_FRACTION = 0.30f
        const val FLICK_PX_PER_S = 1_200f
        const val FLY_MS = 260
    }
}

@Composable
fun rememberDeckState(): DeckState = rememberSaveable(saver = Saver<DeckState, Int>(save = { it.currentPage }, restore = { DeckState(it) })) { DeckState() }

/** How far behind the front card a card in [slot] sits: smaller and tucked up so only a sliver of its top shows. */
private fun GraphicsLayerScope.stackSlot(slot: Float, peekPx: Float) {
    val scale = 1f - STACK_SCALE_STEP * slot
    transformOrigin = TransformOrigin(0.5f, 1f)
    scaleX = scale
    scaleY = scale
    translationY = -(peekPx * slot + size.height * (1f - scale))
}

private const val STACK_SCALE_STEP = 0.06f

/**
 * Cards stacked like a deck. Drag the front card sideways and it moves away with the finger, tilting, while the card behind it
 * grows forward into its place; let go past a third of the width (or flick) and it flies off, otherwise it springs back. The
 * cards are opaque and there is no pager: nothing slides in from the side. With animations off (Lite mode or the system setting)
 * the swap is instant. Screen readers get "next card" / "previous card" actions. [onOpen] stays with the card's own click.
 */
@Composable
fun StackedDeck(
    count: Int,
    pageKey: (Int) -> Any,
    state: DeckState,
    modifier: Modifier = Modifier,
    page: @Composable (Int) -> Unit
) {
    if (count <= 0) return
    val reduce = rememberReduceMotion()
    val scope = rememberCoroutineScope()
    LaunchedEffect(count) { if (state.currentPage > count - 1) state.currentPage = count - 1 }
    val current = DeckModel.clamp(state.currentPage, count)
    val layers = DeckModel.layersBehind(count)
    val peekPx = with(LocalDensity.current) { 10.dp.toPx() }
    val direction by remember(count) { derivedStateOf { state.direction(count) } }
    val dragState = rememberDraggableState { delta -> state.dragBy(delta, count) }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.fillMaxWidth().padding(top = 10.dp * layers)
                .onSizeChanged { state.widthPx = it.width.toFloat() }
                .draggable(
                    dragState, Orientation.Horizontal, enabled = count > 1,
                    onDragStarted = { state.cancelSettle() },
                    onDragStopped = { velocity -> state.release(scope, velocity, count, reduce) }
                )
                .semantics {
                    contentDescription = "Card ${current + 1} of $count"
                    customActions = buildList {
                        if (current < count - 1) add(CustomAccessibilityAction("Next card") { state.step(scope, 1, count, reduce); true })
                        if (current > 0) add(CustomAccessibilityAction("Previous card") { state.step(scope, -1, count, reduce); true })
                    }
                }
        ) {
            // The deepest card first, the front card last (drawn on top). While the front card is being dragged to the card
            // before it, that card is the one coming up from behind; otherwise the next cards are.
            val coming = if (direction < 0) current - 1 else current + 1
            if (direction >= 0 && !reduce && current + 2 < count) {
                key(pageKey(current + 2)) {
                    Box(Modifier.fillMaxWidth().graphicsLayer {
                        val progress = (abs(state.dragPx) / (state.widthPx * 0.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        stackSlot(2f - progress, peekPx)
                    }) { page(current + 2) }
                }
            }
            if (coming in 0 until count) {
                key(pageKey(coming)) {
                    Box(Modifier.fillMaxWidth().graphicsLayer {
                        val progress = (abs(state.dragPx) / (state.widthPx * 0.5f).coerceAtLeast(1f)).coerceIn(0f, 1f)
                        stackSlot(1f - progress, peekPx)
                    }) { page(coming) }
                }
            }
            key(pageKey(current)) {
                Box(Modifier.fillMaxWidth().graphicsLayer {
                    translationX = state.dragPx
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    if (!reduce) rotationZ = TILT_DEGREES * (state.dragPx / state.widthPx.coerceAtLeast(1f)).coerceIn(-1.25f, 1.25f)
                }) { page(current) }
            }
        }
        DeckDots(count, current, Modifier.padding(top = 12.dp))
    }
}

private const val TILT_DEGREES = 12f

/** The page dots; at most seven show, a window that follows the current card. */
@Composable
fun DeckDots(count: Int, page: Int, modifier: Modifier = Modifier, active: Color = headerContentColor()) {
    val dots = DeckModel.dots(count, page)
    if (dots.isEmpty()) return
    Row(
        modifier.clearAndSetSemantics { contentDescription = "Card ${DeckModel.clamp(page, count) + 1} of $count" },
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        dots.forEach { dot ->
            Box(Modifier.height(6.dp).width(if (dot.on) 18.dp else 6.dp).clip(CircleShape).background(active.copy(alpha = if (dot.on) 1f else 0.3f)))
        }
    }
}

/** One round action: a drawable, a label under it, and what a tap does. At most four go in a row. */
data class ActionSpec(
    val label: String,
    val icon: Int,
    val onClick: () -> Unit,
    val primary: Boolean = false,
    val enabled: Boolean = true,
    /** What a screen reader says when the label alone is not enough; null uses the label. */
    val description: String? = null,
    /** A determinate ring (0..1) shown in the circle in place of the icon while the action is working; the action is not tappable then. */
    val progress: Float? = null
)

/**
 * Up to four round actions with equal spacing and a label under each. Every cell is the same width and reserves two lines for its
 * label, so the circles line up whether a label wraps or not; at very large text the row becomes a 2 x 2 grid instead.
 */
@Composable
fun ActionCircleRow(actions: List<ActionSpec>, modifier: Modifier = Modifier) {
    val shown = actions.take(4)
    if (shown.isEmpty()) return
    val grid = LocalResponsive.current.fontScale >= 1.8f && shown.size > 2
    if (grid) {
        Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            shown.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    pair.forEach { ActionCell(it, Modifier.weight(1f)) }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    } else {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            shown.forEach { ActionCell(it, Modifier.weight(1f)) }
            // A short row keeps the cell width of a full one, so two actions do not stretch.
            repeat(4 - shown.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ActionCell(action: ActionSpec, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val fill = if (action.primary) colors.primary else colors.surfaceContainerHigh
    val ink = if (action.primary) colors.onPrimary else colors.onSurface
    Column(
        modifier
            .heightIn(min = 48.dp)
            .clickable(enabled = action.enabled, role = Role.Button, onClickLabel = action.description) { action.onClick() }
            .semantics { contentDescription = action.description ?: action.label }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            Modifier.size(48.dp).background(if (action.enabled) fill else fill.copy(alpha = 0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            if (action.progress != null) {
                // Determinate, driven by the caller's tick: no looping animation, so it is calm in Lite mode.
                androidx.compose.material3.CircularProgressIndicator(progress = { action.progress }, modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = colors.primary)
            } else {
                Icon(painterResource(action.icon), contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
            }
        }
        Text(
            action.label, Modifier.fillMaxWidth(), style = MaterialTheme.typography.labelMedium,
            color = if (action.enabled) colors.onSurface else colors.onSurface.copy(alpha = 0.5f),
            textAlign = TextAlign.Center, maxLines = 2, minLines = 2, overflow = TextOverflow.Ellipsis
        )
    }
}

/** Slot colours of the category bar and the rows under it: the same order in both, so a swatch names its segment. */
@Composable
fun categorySlotColors(): List<Color> = MaterialTheme.colorScheme.let { listOf(it.primary, it.secondary, it.tertiary, it.onSurfaceVariant, it.outline) }

/** A segmented bar: one rounded segment per share, with a small gap, never thinner than a few dp. */
@Composable
fun CategoryBar(shares: List<CategoryShare>, modifier: Modifier = Modifier) {
    if (shares.isEmpty()) return
    val weights = CardSpend.segments(shares)
    val colors = categorySlotColors()
    Row(modifier.fillMaxWidth().height(8.dp).clearAndSetSemantics { }, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        weights.forEachIndexed { i, w ->
            Box(Modifier.weight(w).height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors[i.coerceAtMost(colors.lastIndex)]))
        }
    }
}

/**
 * One category row: a colour swatch that matches its segment, the name, the amount (private when amounts are hidden) and the
 * share. At large text the amount and share drop under the name instead of squeezing it.
 */
@Composable
fun CategoryRow(name: String, amount: String, percent: Int, color: Color, modifier: Modifier = Modifier) {
    val stacked = LocalResponsive.current.fontScale >= 1.5f
    if (stacked) {
        Row(modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row {
                    PrivateAmount(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                    Text("  $percent%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(color, RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(12.dp))
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.width(8.dp))
            PrivateAmount(amount, style = MaterialTheme.typography.bodyMedium, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
            Text("$percent%", Modifier.width(44.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1)
        }
    }
}
