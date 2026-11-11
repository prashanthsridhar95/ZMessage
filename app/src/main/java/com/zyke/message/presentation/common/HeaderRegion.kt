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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
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
 * Cards stacked: the ones behind peek out above the front card, and a swipe moves to the next. In Lite mode or with animations
 * off it is a plain pager (no tilt, no scaling). [onOpen] is called with the page that was tapped.
 */
@Composable
fun StackedDeck(
    count: Int,
    pageKey: (Int) -> Any,
    pagerState: PagerState,
    modifier: Modifier = Modifier,
    page: @Composable (Int) -> Unit
) {
    if (count <= 0) return
    val reduce = rememberReduceMotion()
    val behind = DeckModel.layersBehind(count)
    val layerColor = headerContentColor()
    val peek = 8.dp
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.fillMaxWidth().padding(top = peek * behind)) {
            // The cards behind: thin tinted slabs above the front card, narrower each step.
            for (i in behind downTo 1) {
                Box(
                    Modifier.align(Alignment.TopCenter).fillMaxWidth()
                        .padding(horizontal = 14.dp * i)
                        .height(36.dp)
                        .graphicsLayer { translationY = -(peek * i).toPx() }
                        .background(layerColor.copy(alpha = if (i == 1) 0.20f else 0.12f), RoundedCornerShape(18.dp))
                )
            }
            HorizontalPager(pagerState, Modifier.fillMaxWidth(), pageSpacing = 12.dp, key = { pageKey(it.coerceIn(0, count - 1)) }) { index ->
                Box(
                    Modifier.fillMaxWidth().graphicsLayer {
                        if (!reduce) {
                            val offset = (pagerState.currentPage - index) + pagerState.currentPageOffsetFraction
                            val away = abs(offset).coerceIn(0f, 1f)
                            val scale = 1f - 0.06f * away
                            scaleX = scale
                            scaleY = scale
                            rotationZ = -3f * offset.coerceIn(-1f, 1f)
                            alpha = 1f - 0.4f * away
                        }
                    }
                ) { page(index.coerceIn(0, count - 1)) }
            }
        }
        DeckDots(count, pagerState.currentPage, Modifier.padding(top = 12.dp))
    }
}

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
