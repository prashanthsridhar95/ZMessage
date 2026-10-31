package com.zyke.message.presentation.dashboard.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.CategoryChange
import com.zyke.message.domain.dashboard.MonthlySummary
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.common.LocalPrivacyMode
import com.zyke.message.presentation.common.PrivateAmount
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.home.StatusPill
import com.zyke.message.presentation.home.Tone
import com.zyke.message.presentation.messages.components.Caption
import java.math.BigDecimal

/**
 * "This month vs last month": a hero (spent so far against the same point last month), a two-line running-total chart
 * with a projected month-end, income against spending, and per-category paired bars with the biggest movers first.
 * Tapping a category opens its payments.
 */
@Composable
internal fun MonthlySummaryCard(summary: MonthlySummary, onOpenCategory: (MessageCategory) -> Unit) {
    SectionCard(
        "This month vs last month",
        subtitle = "Compared with the same days of last month",
        summary = summary.spentDeltaPercent?.let { if (it > 0) "+$it%" else "$it%" }
    ) {
        Hero(summary)
        if (summary.cumulativeSpent.size >= 2 || summary.previousCumulativeSpent.size >= 2) {
            CumulativeChart(summary, Modifier.padding(top = 16.dp))
        }
        IncomeVersusSpend(summary, Modifier.padding(top = 16.dp))
        if (summary.changes.isEmpty()) {
            Caption("No category has changed yet.", Modifier.padding(top = 12.dp))
        } else {
            val widest = remember(summary) { summary.changes.maxOf { maxOf(it.current, it.previous) } }
            Caption("What changed most", Modifier.padding(top = 16.dp, bottom = 4.dp))
            summary.changes.forEachIndexed { index, change ->
                if (index > 0) HorizontalDivider()
                CategoryRow(change, widest) { onOpenCategory(change.category) }
            }
        }
    }
}

@Composable
private fun Hero(summary: MonthlySummary) {
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
        Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            PrivateAmount(
                DisplayFormatters.rupees(summary.spent),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            if (pill != null) {
                Spacer(Modifier.width(12.dp))
                StatusPill(pill, tone)
            }
        }
        if (!hidden) Text(summary.heroLine, Modifier.padding(top = 4.dp), style = MaterialTheme.typography.bodyMedium)
        PairedBars(
            thisValue = summary.spent,
            lastValue = summary.previousSpent,
            thisLabel = "This month",
            lastLabel = "Last month, same days",
            modifier = Modifier.padding(top = 12.dp)
        )
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
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BarLine(thisLabel, thisValue, top, thisColor)
        BarLine(lastLabel, lastValue, top, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f))
    }
}

@Composable
private fun BarLine(label: String, value: BigDecimal, top: BigDecimal, color: Color) {
    val fraction = if (top.signum() > 0) (value.toDouble() / top.toDouble()).toFloat().coerceIn(0f, 1f) else 0f
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(label, Modifier.weight(1f))
            PrivateAmount(DisplayFormatters.rupees(value), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(8.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f), RoundedCornerShape(4.dp))) {
            if (fraction > 0f) Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f)).height(8.dp).background(color, RoundedCornerShape(4.dp)))
        }
    }
}

/** Running spend by day: this month solid, last month dashed, and a dotted run-rate guess to the month's end. */
@Composable
private fun CumulativeChart(summary: MonthlySummary, modifier: Modifier = Modifier) {
    val thisColor = MaterialTheme.colorScheme.primary
    val lastColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val projected = summary.projectedSpent
    val days = maxOf(summary.cycleDays, summary.previousCumulativeSpent.size, summary.cumulativeSpent.size).coerceAtLeast(2)
    val peak = remember(summary) {
        listOfNotNull(
            summary.cumulativeSpent.lastOrNull(), summary.previousCumulativeSpent.lastOrNull(), projected
        ).maxOrNull()?.toFloat()?.takeIf { it > 0f } ?: 1f
    }
    val hidden = LocalPrivacyMode.current.hidden
    val spoken = if (hidden) "Running total chart, amounts hidden." else buildString {
        append("Running total chart. This month ${DisplayFormatters.rupees(summary.spent)} after ${summary.cumulativeSpent.size} days. ")
        summary.previousCumulativeSpent.lastOrNull()?.let { append("Last month finished at ${DisplayFormatters.rupees(it)}. ") }
        if (projected != null) append("Projected month end ${DisplayFormatters.rupees(projected)}.")
    }
    Column(modifier.fillMaxWidth().semantics { contentDescription = spoken }) {
        Caption("Running total by day")
        Canvas(Modifier.fillMaxWidth().padding(top = 6.dp).height(120.dp)) {
            val w = size.width
            val h = size.height
            fun x(day: Int) = w * day / (days - 1).toFloat()
            fun y(value: Float) = h - (h - 6f) * (value / peak)
            drawLine(gridColor, Offset(0f, h), Offset(w, h), 1.dp.toPx())
            drawLine(gridColor, Offset(0f, 0f), Offset(w, 0f), 1.dp.toPx())
            fun line(values: List<BigDecimal>): Path = Path().apply {
                values.forEachIndexed { i, v -> if (i == 0) moveTo(x(0), y(v.toFloat())) else lineTo(x(i), y(v.toFloat())) }
            }
            if (summary.previousCumulativeSpent.size >= 2) {
                drawPath(line(summary.previousCumulativeSpent), lastColor, style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
            }
            val cur = summary.cumulativeSpent
            if (cur.isNotEmpty() && projected != null && cur.size < days) {
                val p = Path().apply {
                    moveTo(x(cur.size - 1), y(cur.last().toFloat()))
                    lineTo(x(days - 1), y(projected.toFloat()))
                }
                drawPath(p, thisColor.copy(alpha = 0.6f), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 8f)), cap = androidx.compose.ui.graphics.StrokeCap.Round))
                drawCircle(thisColor.copy(alpha = 0.6f), 5.dp.toPx(), Offset(x(days - 1), y(projected.toFloat())), style = Stroke(2.dp.toPx()))
            }
            if (cur.size >= 2) drawPath(line(cur), thisColor, style = Stroke(3.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round))
            if (cur.isNotEmpty()) drawCircle(thisColor, 4.5.dp.toPx(), Offset(x(cur.size - 1), y(cur.last().toFloat())))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption("Day 1")
            Caption("Day $days")
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            LegendItem("This month", thisColor)
            LegendItem("Last month", lastColor)
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
