package com.zyke.message.presentation.dashboard.components

import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.chart.ChartMath
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.CardHeader
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.common.ChromeScale
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.common.rememberTick
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.mutedColor
import com.zyke.message.presentation.motion.Haptics
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.SkeletonBlock
import com.zyke.message.presentation.motion.fadeInOnce
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing
import com.zyke.message.presentation.theme.Tonal
import com.zyke.message.presentation.theme.TypeRole

/*
 * The small kit the Insights tab is built from (docs/DESIGN.md, Insights): one card, one chevron, charts sized from the
 * width they are given, a legend that never wraps unevenly, a period switch whose indicator slides, a still skeleton.
 */

/** The chevron at the end of anything that opens: the same size and colour everywhere on the tab. Decorative. */
@Composable
internal fun Chevron(modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.baseline_chevron_right_24),
        contentDescription = null,
        modifier = modifier.size(20.dp),
        colorFilter = ColorFilter.tint(mutedColor())
    )
}

/**
 * A titled card of the Insights tab: [CardHeader], then the content. Where [LocalSectionsAsSheets] is set it is a tile (the
 * header with a chevron and a one-line [summary]) that opens its detail in a sheet; a [startOpen] card, or one outside a page
 * of tiles, always shows its content. [level] is [Tonal.Quiet] except for the one card the page is about. [spoken] replaces
 * what a screen reader says for the tile (a chart's text alternative); amounts in it are masked while hidden.
 */
@Composable
internal fun InsightCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    summary: String? = null,
    spoken: String? = null,
    startOpen: Boolean = false,
    level: Tonal = Tonal.Quiet,
    /** What the open / closed choice is remembered under; keep it fixed when the title changes with the data. */
    stateKey: String = title,
    content: @Composable ColumnScope.() -> Unit
) {
    val asSheet = LocalSectionsAsSheets.current && !startOpen
    if (!asSheet) {
        FintechCard(modifier, level = level) {
            CardHeader(title)
            if (subtitle != null) Caption(subtitle, Modifier.padding(bottom = Spacing.xs), maxLines = 3)
            Column(Modifier.fillMaxWidth().padding(top = Spacing.xs), content = content)
        }
        return
    }
    var open by rememberSaveable(stateKey) { mutableStateOf(false) }
    val tileSpoken = privateSpeech(spoken?.let { "$it Opens details" } ?: "$title, ${summary.orEmpty()}. Opens details")
    FintechCard(modifier, onClick = { open = true }, level = level) {
        Column(Modifier.semantics { contentDescription = tileSpoken }) {
            CardHeader(title, trailing = { Chevron() })
            if (summary != null) Text(summary, style = TypeRole.body, color = MaterialTheme.colorScheme.primary, maxLines = 2)
        }
    }
    if (open) {
        AdaptiveSheet(title, { open = false }, subtitle = subtitle) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
    }
}

/**
 * A box for a chart whose height follows the width it is given (and grows a little with the text size), instead of a fixed
 * dp: [aspect] is height over width, kept between [minHeight] and [maxHeight]. The chart draws into the box it gets.
 */
@Composable
internal fun ChartBox(
    aspect: Float,
    minHeight: Dp,
    maxHeight: Dp,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val height = ChartMath.chartHeightDp(maxWidth.value, fontScale, aspect, minHeight.value, maxHeight.value).dp
        Box(Modifier.fillMaxWidth().height(height), content = content)
    }
}

/**
 * The tick under a finger sliding across a chart: one light haptic each time it lands on a different bar, at most one
 * every [ChartMath.TICK_GAP_MS]. Follows the Haptic feedback setting.
 */
internal class ChartTicker(private val haptics: Haptics) {
    private var last = -1
    private var lastAt = 0L

    /** The finger is now over [index]. */
    fun moved(index: Int) {
        val now = SystemClock.uptimeMillis()
        if (ChartMath.shouldTick(last, index, now, lastAt)) {
            haptics.tick()
            lastAt = now
        }
        if (index >= 0) last = index
    }

    /** The finger left the chart. */
    fun release() {
        last = -1
    }
}

@Composable
internal fun rememberChartTicker(): ChartTicker {
    val haptics = rememberHaptics()
    return remember(haptics) { ChartTicker(haptics) }
}

/** One colour key entry. */
internal class LegendEntry(val color: Color, val label: String)

/** The colour key of a chart: equal chips (a swatch and a name) that wrap together, never one long line that scrolls. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LegendChips(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    if (entries.isEmpty()) return
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xs)
    ) {
        entries.forEach { entry ->
            Row(Modifier.heightIn(min = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(entry.color, CircleShape))
                Spacer(Modifier.width(6.dp))
                Caption(entry.label)
            }
        }
    }
}

/**
 * A few short options side by side whose chosen indicator slides to the new option (a spring from the motion tokens, drawn
 * while painting, so nothing recomposes per frame) and ticks once. Each segment is at least 48 dp tall and the group is one
 * selectable group for a screen reader. Instant when motion is reduced.
 */
@Composable
internal fun <T> SlidingSegments(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    description: String? = null,
    role: Role = Role.RadioButton
) {
    ChromeScale { SlidingSegmentsRow(choices, selected, onSelect, modifier, description, role) }
}

@Composable
private fun <T> SlidingSegmentsRow(
    choices: List<Choice<T>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier,
    description: String?,
    role: Role
) {
    val motion = rememberMotion()
    val tick = rememberTick()
    val index = choices.indexOfFirst { it.value == selected }.coerceAtLeast(0)
    val slide = remember { Animatable(index.toFloat()) }
    LaunchedEffect(index) { slide.animateTo(index.toFloat(), motion.snappy()) }
    val track = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)
    val indicator = MaterialTheme.colorScheme.primary
    val inset = Spacing.xs
    Row(
        modifier
            .fillMaxWidth()
            .clip(AppShapes.control)
            .background(track)
            .drawBehind {
                val count = choices.size.coerceAtLeast(1)
                val pad = inset.toPx()
                val cell = (size.width - 2 * pad) / count
                drawRoundRect(
                    indicator,
                    topLeft = Offset(pad + slide.value * cell, pad),
                    size = Size(cell, size.height - 2 * pad),
                    cornerRadius = CornerRadius(Radius.chip.toPx())
                )
            }
            .padding(inset)
            .selectableGroup()
            .semantics { if (description != null) contentDescription = description },
        verticalAlignment = Alignment.CenterVertically
    ) {
        choices.forEach { choice ->
            val chosen = choice.value == selected
            val ink by animateColorAsState(
                if (chosen) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                motion.tween(Motion.QUICK_MS),
                label = "segment-ink"
            )
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(AppShapes.chip)
                    .selectable(selected = chosen, role = role, onClick = { if (!chosen) tick(); onSelect(choice.value) })
                    .padding(horizontal = Spacing.sm),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    choice.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (chosen) FontWeight.Bold else FontWeight.Medium,
                    color = ink,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * What the Insights tab shows while its numbers are being worked out: the shapes of the cards, still (no shimmer, nothing
 * that loops), faded in once. A screen reader hears one "Loading insights".
 */
@Composable
internal fun InsightsSkeleton(modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .padding(Spacing.lg)
            .fadeInOnce()
            .clearAndSetSemantics { contentDescription = "Loading insights" },
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        FintechCard(level = Tonal.Hero) {
            SkeletonBlock(Modifier.width(96.dp).height(12.dp), corner = 4.dp)
            SkeletonBlock(Modifier.padding(top = Spacing.md).width(168.dp).height(28.dp), corner = 6.dp)
            SkeletonBlock(Modifier.padding(top = Spacing.lg).fillMaxWidth().height(96.dp), corner = Radius.chip)
        }
        repeat(3) {
            FintechCard {
                SkeletonBlock(Modifier.width(120.dp).height(12.dp), corner = 4.dp)
                SkeletonBlock(Modifier.padding(top = Spacing.md).fillMaxWidth(0.6f).height(16.dp), corner = 4.dp)
            }
        }
    }
}
