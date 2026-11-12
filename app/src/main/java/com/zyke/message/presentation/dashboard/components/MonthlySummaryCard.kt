package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import com.zyke.message.presentation.common.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.CategoryChange
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.domain.chart.ChartMath
import com.zyke.message.presentation.common.Choice
import com.zyke.message.presentation.motion.AnimatedAmount
import com.zyke.message.presentation.motion.CrossfadeContent
import com.zyke.message.presentation.motion.ProgressReveal
import com.zyke.message.presentation.motion.rememberProgressReveal
import com.zyke.message.presentation.theme.Spacing
import androidx.compose.ui.graphics.drawscope.clipRect
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.home.toneColor
import com.zyke.message.presentation.messages.components.Caption
import java.math.BigDecimal

private val TABS = listOf("Overview", "By day", "Categories")
private const val COLLAPSED_CATEGORIES = 5

/**
 * "This month vs last month": a compact hero (spent so far, the change against last month, one plain sentence) and,
 * under it, one view at a time chosen by a segmented control: Overview (paired bars, income against spending and the
 * month-end projection), By day (the running-total chart you can scrub) and Categories (the biggest movers).
 */
@Composable
internal fun MonthlySummaryCard(summary: MonthlySummary, countFromZero: Boolean = false, onOpenCategory: (MessageCategory) -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    InsightCard(
        "This month vs last month",
        subtitle = "Compared with the same days of last month",
        summary = summary.spentDeltaPercent?.let { if (it > 0) "+$it%" else "$it%" }
    ) {
        Hero(summary, countFromZero)
        CardBillLine(summary)
        SlidingSegments(TABS.mapIndexed { i, label -> Choice(i, label) }, tab, { tab = it }, Modifier.padding(top = Spacing.md), role = Role.Tab)
        CrossfadeContent(tab, Modifier.fillMaxWidth().padding(top = Spacing.md), label = "monthTab") { shown ->
            Column(Modifier.fillMaxWidth()) {
                when (shown) {
                    0 -> Overview(summary)
                    1 -> ByDay(summary)
                    else -> Categories(summary, onOpenCategory)
                }
            }
        }
    }
}

private val BillDayFormat = com.zyke.message.presentation.format.FormatCache.pattern("d MMM", java.util.Locale.getDefault())

/**
 * One quiet line under the headline when a credit card's bill was paid this month: the money left the bank, but it is not
 * spending again (the card's swipes were counted on the day of each swipe). Tap for the payments themselves.
 */
@Composable
private fun CardBillLine(summary: MonthlySummary) {
    if (summary.cardBillPayments.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    val total = DisplayFormatters.rupees(summary.cardBillTotal)
    val hidden = LocalPrivacyMode.current.hidden
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Show the card bill payments") { open = true }
            .semantics(mergeDescendants = true) { contentDescription = if (hidden) "Card bill payments, not counted" else "Card bill payments $total, not counted" },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Caption("Card bill payments \u00B7 not counted", Modifier.weight(1f), maxLines = 2)
        PrivateAmount(total, Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        Text("\u203A", Modifier.padding(start = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
    }
    if (open) {
        com.zyke.message.presentation.common.AdaptiveSheet("Card bill payments", { open = false }) {
            Caption("These paid off your credit card. They are not counted as spending, because each swipe was already counted on the day you made it.", maxLines = 4)
            summary.cardBillPayments.forEach { payment ->
                key(payment.messageId) {
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(payment.label, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Caption(BillDayFormat.format(java.time.Instant.ofEpochMilli(payment.atMillis).atZone(java.time.ZoneId.systemDefault())))
                        }
                        PrivateAmount(DisplayFormatters.rupees(payment.amount), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                    }
                }
            }
            Caption("Not right? Open the payment and choose \"This is not a card bill payment\".", Modifier.padding(top = 12.dp), maxLines = 3)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(summary: MonthlySummary, countFromZero: Boolean) {
    val percent = summary.spentDeltaPercent
    val tone = when {
        summary.spentDelta.signum() < 0 -> Tone.GOOD
        summary.spentDelta.signum() > 0 -> Tone.BAD
        else -> Tone.NEUTRAL
    }
    val pill = when {
        percent == null -> null
        percent > 0 -> "+$percent% vs last month"
        percent < 0 -> "$percent% vs last month"
        else -> "Same as last month"
    }
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = if (hidden) "Spent so far, amount hidden. ${percent?.let { "$it percent against last month at this point." }.orEmpty()}" else "Spent ${DisplayFormatters.rupees(summary.spent)} so far. ${summary.heroLine}. " +
        "Last month at this point: ${DisplayFormatters.rupees(summary.previousSpent)}."
    Column(Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
        Caption("SPENT SO FAR")
        FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            AnimatedAmount(
                summary.spent,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                fit = true,
                fromZero = countFromZero
            )
            if (pill != null) StatusPill(pill, tone)
        }
        if (!hidden) Text(summary.heroLine, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

// ---- Overview ----

@Composable
private fun Overview(summary: MonthlySummary) {
    PairedBars(
        thisValue = summary.spent,
        lastValue = summary.previousSpent,
        thisLabel = "This month",
        lastLabel = "Last month, same days"
    )
    IncomeVersusSpend(summary, Modifier.padding(top = Spacing.lg))
    ProjectionChip(summary, Modifier.padding(top = Spacing.md))
}

@Composable
private fun ProjectionChip(summary: MonthlySummary, modifier: Modifier = Modifier) {
    val projected = summary.projectedSpent
    if (projected == null) {
        Caption("A month-end estimate appears after a few days of spending.", modifier, maxLines = 2)
        return
    }
    val delta = summary.projectedVsLastMonth
    val tone = when {
        delta == null || delta.signum() == 0 -> Tone.NEUTRAL
        delta.signum() < 0 -> Tone.GOOD
        else -> Tone.BAD
    }
    val color = toneColor(tone)
    val hidden = LocalPrivacyMode.current.hidden
    val versus = delta?.let { d ->
        val amount = DisplayFormatters.rupees(d.abs())
        when {
            d.signum() > 0 -> "$amount more than last month's total"
            d.signum() < 0 -> "$amount less than last month's total"
            else -> "the same as last month's total"
        }
    }
    val spoken = if (hidden) "Projection to month end, amount hidden" else "At this pace you will spend about ${DisplayFormatters.rupees(projected)} by month end" + (versus?.let { ", $it" } ?: "")
    Column(
        modifier.fillMaxWidth()
            .background(color.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
            .padding(12.dp)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
    ) {
        Caption("AT THIS PACE, BY MONTH END")
        PrivateAmount(DisplayFormatters.rupees(projected), Modifier.padding(top = 2.dp), color = color, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, fit = true)
        if (versus != null && !hidden) Text(versus, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Two horizontal bars on one scale: this month in the accent, last month in a quiet neutral. */
@Composable
private fun PairedBars(
    thisValue: BigDecimal,
    lastValue: BigDecimal,
    thisLabel: String,
    lastLabel: String,
    modifier: Modifier = Modifier,
    thisColor: Color = MaterialTheme.colorScheme.primary
) {
    val top = maxOf(thisValue, lastValue)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        BarLine(thisLabel, thisValue, top, thisColor)
        BarLine(lastLabel, lastValue, top, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

@Composable
private fun BarLine(label: String, value: BigDecimal, top: BigDecimal, color: Color) {
    val fraction = ChartMath.barFraction(value, top, minVisible = 0.02f)
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(label, Modifier.weight(1f))
            AnimatedAmount(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
        }
        // Grows once when first shown, drawn without recomposing.
        ProgressReveal(fraction, Modifier.padding(top = 2.dp), color = color, height = 8.dp)
    }
}

@Composable
private fun LegendItem(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(4.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(6.dp))
        Caption(label)
    }
}

@Composable
private fun IncomeVersusSpend(summary: MonthlySummary, modifier: Modifier = Modifier) {
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = if (hidden) "Income against spending, amounts hidden." else "Income ${DisplayFormatters.rupees(summary.income)}, spent ${DisplayFormatters.rupees(summary.spent)}. " +
        (summary.savingsRatePercent?.let { "Savings rate $it percent." } ?: "Savings rate not available.")
    Column(modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = spoken }) {
        Caption("Income vs spending")
        val top = maxOf(summary.income, summary.spent)
        Column(Modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BarLine("Income", summary.income, top, com.zyke.message.presentation.home.Good)
            BarLine("Spent", summary.spent, top, MaterialTheme.colorScheme.primary)
        }
        if (!hidden) Caption(summary.lines.drop(2).joinToString("  ·  "), Modifier.padding(top = 4.dp), maxLines = 2)
    }
}

// ---- By day ----

private fun valueAt(values: List<BigDecimal>, index: Int): BigDecimal? = values.getOrNull(index)

/** Running spend by day: this month solid, last month dashed, a dotted run-rate guess to the month's end, and a scrubber. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ByDay(summary: MonthlySummary) {
    val thisColor = MaterialTheme.colorScheme.primary
    val lastColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val surface = MaterialTheme.colorScheme.surface
    val cur = summary.cumulativeSpent
    val prev = summary.previousCumulativeSpent
    val projected = summary.projectedSpent
    val days = maxOf(summary.cycleDays, prev.size, cur.size).coerceAtLeast(2)
    if (cur.size < 2 && prev.size < 2) {
        Caption("Not enough days yet to draw a running total.", maxLines = 2)
        return
    }
    val peak = remember(summary) {
        listOfNotNull(cur.lastOrNull(), prev.lastOrNull(), projected).maxOrNull()?.toFloat()?.takeIf { it > 0f } ?: 1f
    }
    val hidden = LocalPrivacyMode.current.hidden
    var picked by remember(summary) { mutableIntStateOf(-1) }
    val ticker = rememberChartTicker()
    val reveal = rememberProgressReveal(1f)
    val day = if (picked in 0 until days) picked else (cur.size - 1).coerceAtLeast(0)
    val spoken = if (hidden) "Running total chart, amounts hidden." else buildString {
        append("Running total chart. This month ${DisplayFormatters.rupees(summary.spent)} after ${cur.size} days. ")
        prev.lastOrNull()?.let { append("Last month finished at ${DisplayFormatters.rupees(it)}. ") }
        if (projected != null) append("Projected month end ${DisplayFormatters.rupees(projected)}. ")
        append("Use the actions menu for the next or previous day.")
    }
    val dayText = "Day ${day + 1}"
    val readout = if (hidden) "$dayText, amounts hidden" else buildString {
        append(dayText).append(". This month ")
        append(valueAt(cur, day)?.let { DisplayFormatters.rupees(it) } ?: if (day == days - 1 && projected != null) "about ${DisplayFormatters.rupees(projected)} (projected)" else "not yet")
        append(". Last month ")
        append(valueAt(prev, day)?.let { DisplayFormatters.rupees(it) } ?: "no data")
    }

    Column(Modifier.fillMaxWidth()) {
        // The readout the scrubber drives: both months' totals on the chosen day.
        Row(
            Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = readout; liveRegion = LiveRegionMode.Polite },
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f)) {
                Caption(dayText.uppercase())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(10.dp).background(thisColor, RoundedCornerShape(5.dp)))
                    Spacer(Modifier.width(6.dp))
                    PrivateAmount(
                        valueAt(cur, day)?.let { DisplayFormatters.rupees(it) } ?: projected?.takeIf { day == days - 1 }?.let { "~" + DisplayFormatters.rupees(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Caption("LAST MONTH")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(10.dp).height(10.dp).background(lastColor, RoundedCornerShape(5.dp)))
                    Spacer(Modifier.width(6.dp))
                    PrivateAmount(
                        valueAt(prev, day)?.let { DisplayFormatters.rupees(it) } ?: "—",
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1
                    )
                }
            }
        }
        ChartBox(aspect = 0.5f, minHeight = 130.dp, maxHeight = 200.dp, modifier = Modifier.padding(top = Spacing.sm)) {
        Box(
            Modifier
                .fillMaxSize()
                .semantics {
                    contentDescription = spoken
                    customActions = listOf(
                        CustomAccessibilityAction("Next day") { picked = (day + 1).coerceAtMost(days - 1); true },
                        CustomAccessibilityAction("Previous day") { picked = (day - 1).coerceAtLeast(0); true }
                    )
                }
                .pointerInput(days) {
                    detectTapGestures { o ->
                        picked = ChartMath.pointIndexAt(o.x, size.width.toFloat(), days)
                        ticker.moved(picked)
                    }
                }
                .pointerInput(days) {
                    detectHorizontalDragGestures(onDragEnd = { ticker.release() }, onDragCancel = { ticker.release() }) { change, _ ->
                        change.consume()
                        picked = ChartMath.pointIndexAt(change.position.x, size.width.toFloat(), days)
                        ticker.moved(picked)
                    }
                }
                .drawWithCache {
                    val w = size.width
                    val h = size.height
                    fun x(i: Int) = w * i / (days - 1).toFloat()
                    fun y(v: Float) = h - (h - 8f) * (v / peak)
                    fun line(values: List<BigDecimal>): Path = Path().apply {
                        values.forEachIndexed { i, v -> if (i == 0) moveTo(x(0), y(v.toFloat())) else lineTo(x(i), y(v.toFloat())) }
                    }
                    val prevPath = if (prev.size >= 2) line(prev) else null
                    val curPath = if (cur.size >= 2) line(cur) else null
                    val projPath = if (cur.isNotEmpty() && projected != null && cur.size < days) Path().apply {
                        moveTo(x(cur.size - 1), y(cur.last().toFloat()))
                        lineTo(x(days - 1), y(projected.toFloat()))
                    } else null
                    val dashed = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                    val dotted = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f)), cap = StrokeCap.Round)
                    val solid = Stroke(3.dp.toPx(), cap = StrokeCap.Round)
                    onDrawBehind {
                        drawLine(gridColor, Offset(0f, h), Offset(w, h), 1.dp.toPx())
                        drawLine(gridColor, Offset(0f, h / 2f), Offset(w, h / 2f), 1.dp.toPx())
                        drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), 1.dp.toPx())
                        // The lines are uncovered from the left once, when the chart is first shown (read while drawing).
                        clipRect(right = w * reveal.value) {
                            prevPath?.let { drawPath(it, lastColor, style = dashed) }
                            projPath?.let {
                                drawPath(it, thisColor.copy(alpha = 0.6f), style = dotted)
                                drawCircle(thisColor.copy(alpha = 0.6f), 5.dp.toPx(), Offset(x(days - 1), y(projected!!.toFloat())), style = Stroke(2.dp.toPx()))
                            }
                            curPath?.let { drawPath(it, thisColor, style = solid) }
                        }
                        val sel = if (picked in 0 until days) picked else (cur.size - 1).coerceAtLeast(0)
                        drawLine(gridColor.copy(alpha = 0.35f), Offset(x(sel), 0f), Offset(x(sel), h), 1.5.dp.toPx())
                        prev.getOrNull(sel)?.let {
                            drawCircle(surface, 6.dp.toPx(), Offset(x(sel), y(it.toFloat())))
                            drawCircle(lastColor, 4.dp.toPx(), Offset(x(sel), y(it.toFloat())))
                        }
                        cur.getOrNull(sel)?.let {
                            drawCircle(surface, 7.dp.toPx(), Offset(x(sel), y(it.toFloat())))
                            drawCircle(thisColor, 5.dp.toPx(), Offset(x(sel), y(it.toFloat())))
                        }
                    }
                }
        )
        }
        Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("Day 1")
            Caption("Tap or drag to inspect", maxLines = 1)
            Caption("Day $days")
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            LegendItem("This month", thisColor)
            LegendItem("Last month", lastColor)
            if (projected != null) LegendItem("Projected", thisColor.copy(alpha = 0.6f))
        }
        if (projected != null && !hidden) {
            val versus = summary.projectedVsLastMonth?.let { d ->
                val amount = DisplayFormatters.rupees(d.abs())
                when {
                    d.signum() > 0 -> ", $amount more than last month's total"
                    d.signum() < 0 -> ", $amount less than last month's total"
                    else -> ", the same as last month's total"
                }
            }.orEmpty()
            Text(
                "At this pace you will spend about ${DisplayFormatters.rupees(projected)} by month end$versus.",
                Modifier.padding(top = 8.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

// ---- Categories ----

@Composable
private fun Categories(summary: MonthlySummary, onOpenCategory: (MessageCategory) -> Unit) {
    if (summary.changes.isEmpty()) {
        Caption("No category has changed yet.", maxLines = 2)
        return
    }
    var expanded by rememberSaveable { mutableStateOf(false) }
    val widest = remember(summary) { summary.changes.maxOf { maxOf(it.current, it.previous) } }
    val shown = if (expanded) summary.changes else summary.changes.take(COLLAPSED_CATEGORIES)
    Caption("What changed most")
    shown.forEachIndexed { index, change ->
        key(change.category) {
            if (index > 0) HorizontalDivider()
            CategoryRow(change, widest) { onOpenCategory(change.category) }
        }
    }
    if (summary.changes.size > COLLAPSED_CATEGORIES) {
        val label = if (expanded) "Show fewer" else "Show all ${summary.changes.size}"
        Text(
            label,
            Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(role = Role.Button, onClickLabel = label) { expanded = !expanded }
                .padding(vertical = 12.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CategoryRow(change: CategoryChange, widest: BigDecimal, onClick: () -> Unit) {
    val percent = change.percent
    val (chip, tone) = when {
        change.previous.signum() <= 0 -> "New" to Tone.WARN
        change.delta.signum() > 0 -> "+${percent}%" to Tone.BAD
        else -> "${percent}%" to Tone.GOOD
    }
    val name = change.category.displayString
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = if (hidden) "$name, ${chip}. Opens payments" else "$name. ${change.text}. This month ${DisplayFormatters.rupees(change.current)}, last month ${DisplayFormatters.rupees(change.previous)}. Opens payments"
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clickable(role = Role.Button, onClickLabel = "Open $name", onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken }
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            PrivateAmount(DisplayFormatters.rupees(change.current), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            StatusPill(chip, tone)
        }
        PairedBars(change.current, change.previous, "This month", "Last month", Modifier.padding(top = 6.dp))
        if (!hidden) Caption(change.text, Modifier.padding(top = 4.dp), maxLines = 2)
    }
}
