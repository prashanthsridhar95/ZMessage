package com.zyke.message.presentation.dashboard.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.CategoryTotal
import com.zyke.message.domain.dashboard.Dashboard
import com.zyke.message.domain.dashboard.DashboardPeriod
import com.zyke.message.domain.dashboard.FlowDirection
import com.zyke.message.domain.dashboard.SourceTotal
import com.zyke.message.domain.dashboard.TrendPoint
import com.zyke.message.domain.dashboard.TrendUnit
import com.zyke.message.domain.dashboard.VendorSummary
import com.zyke.message.domain.model.MessageCategory
import com.zyke.message.presentation.format.DateFormatters
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.FilterChip
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private val SECTION_PADDING = 16.dp

private fun BigDecimal.fractionOf(total: BigDecimal): Float =
    if (total.signum() <= 0) 0f else (toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

@Composable
internal fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        modifier = modifier.fillMaxWidth().padding(start = SECTION_PADDING, end = SECTION_PADDING, top = 24.dp, bottom = 8.dp),
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

/** Period chips, spent / received toggle and the active category filter. */
@Composable
internal fun DashboardControls(
    period: DashboardPeriod,
    direction: FlowDirection,
    category: MessageCategory?,
    onPeriod: (DashboardPeriod) -> Unit,
    onDirection: (FlowDirection) -> Unit,
    onClearCategory: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            items(DashboardPeriod.entries, key = { it.name }) {
                FilterChip(it.label, it == period) { onPeriod(it) }
            }
        }
        LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            items(FlowDirection.entries, key = { it.name }) {
                FilterChip(it.label, it == direction) { onDirection(it) }
            }
            if (category != null) {
                item(key = "category") { FilterChip("${category.displayString}  ✕", true, onClearCategory) }
            }
        }
    }
}

/** The headline number, with a comparison against the previous period. */
@Composable
internal fun HeroCard(dashboard: Dashboard, period: DashboardPeriod, modifier: Modifier = Modifier) {
    val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    val caption = when (period) {
        DashboardPeriod.THIS_MONTH -> "${dashboard.direction.label.lowercase()} in ${monthFmt.format(dashboard.range.start)}"
        DashboardPeriod.LAST_MONTH -> "${dashboard.direction.label.lowercase()} in ${monthFmt.format(dashboard.range.start)}"
        DashboardPeriod.LAST_3_MONTHS -> "${dashboard.direction.label.lowercase()} in the last 3 months"
        DashboardPeriod.ALL_TIME -> "${dashboard.direction.label.lowercase()} in total"
    }
    val comparison = when (period) {
        DashboardPeriod.THIS_MONTH -> "vs same days last month"
        DashboardPeriod.LAST_MONTH -> "vs the month before"
        DashboardPeriod.LAST_3_MONTHS -> "vs the previous 3 months"
        DashboardPeriod.ALL_TIME -> null
    }

    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = SECTION_PADDING, vertical = 12.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Text(
            text = DisplayFormatters.rupees(dashboard.total),
            style = MaterialTheme.typography.displaySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer
        )
        Caption(
            "$caption · ${dashboard.count} ${if (dashboard.count == 1) "payment" else "payments"}",
            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f)
        )

        val change = dashboard.changePercent
        if (change != null && comparison != null) {
            val up = change >= 0
            // More spending is bad news, more income is good news.
            val good = (up && dashboard.direction == FlowDirection.RECEIVED) ||
                (!up && dashboard.direction == FlowDirection.SPENT)
            Text(
                modifier = Modifier.padding(top = 8.dp),
                text = "${if (up) "▲" else "▼"} ${abs(change).roundToInt()}% $comparison",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = if (good) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
            )
        }

        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Caption("Spent ${DisplayFormatters.rupees(dashboard.spentTotal)}", color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
            Caption("Received ${DisplayFormatters.rupees(dashboard.receivedTotal)}", color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f))
        }
    }
}

/** One stacked bar per day (or month), split by category; tallest = 100%. */
@Composable
internal fun TrendChart(
    points: List<TrendPoint>,
    unit: TrendUnit,
    ranking: List<MessageCategory>,
    modifier: Modifier = Modifier
) {
    if (points.isEmpty()) return
    val peak = points.maxBy { it.amount }
    val max = peak.amount
    val fmt = DateTimeFormatter.ofPattern(if (unit == TrendUnit.DAY) "d MMM" else "MMM yy", Locale.getDefault())

    val summary = if (max.signum() > 0) {
        "Peak ${DisplayFormatters.rupees(max)} on ${fmt.format(peak.start)}"
    } else {
        "No activity"
    }

    Column(modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING)) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .semantics { contentDescription = "Chart. $summary" },
            horizontalArrangement = Arrangement.spacedBy(if (points.size > 20) 2.dp else 4.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            points.forEach { point ->
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    if (point.amount.signum() <= 0) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .fillMaxHeight(0.02f)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        )
                    } else {
                        StackedBar(point, max, ranking)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(fmt.format(points.first().start))
            Caption(summary)
            Caption(fmt.format(points.last().start))
        }
    }
}

@Composable
private fun StackedBar(point: TrendPoint, max: BigDecimal, ranking: List<MessageCategory>) {
    val height = point.amount.fractionOf(max).coerceAtLeast(0.04f)
    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(height)
            .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
    ) {
        // Largest on top; weights are the amounts themselves.
        point.byCategory.forEach { (category, amount) ->
            val weight = amount.toFloat()
            if (weight > 0f) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(weight)
                        .background(ChartColors.forCategory(category, ranking))
                )
            }
        }
    }
}

/** Colour key for the chart: the biggest categories, plus "Other" if there are more. */
@Composable
internal fun ChartLegend(ranking: List<MessageCategory>, modifier: Modifier = Modifier) {
    if (ranking.isEmpty()) return
    LazyRow(
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = SECTION_PADDING),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(ranking.take(ChartColors.NAMED), key = { it.name }) { category ->
            LegendItem(ChartColors.forCategory(category, ranking), category.displayString)
        }
        if (ranking.size > ChartColors.NAMED) {
            item(key = "other") { LegendItem(ChartColors.other, "Other") }
        }
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Caption(label)
    }
}

@Composable
internal fun CategoryRow(
    item: CategoryTotal,
    total: BigDecimal,
    color: Color,
    selected: Boolean,
    canSetBudget: Boolean,
    onClick: () -> Unit,
    onEditBudget: () -> Unit,
    modifier: Modifier = Modifier
) {
    val budget = item.budget
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Filter by ${item.category.displayString}", onClick = onClick)
            .background(if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = SECTION_PADDING, vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(item.category.displayString, color = MaterialTheme.colorScheme.secondary)
                Caption(
                    if (item.count == 0) "Nothing yet"
                    else "${item.count} ${if (item.count == 1) "payment" else "payments"} · ${(item.total.fractionOf(total) * 100).roundToInt()}%"
                )
            }
            Text(DisplayFormatters.rupees(item.total), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }

        if (budget != null) {
            BudgetMeter(item, budget, onEditBudget, canSetBudget)
        } else {
            ShareBar(item.total.fractionOf(total), color = color)
            if (canSetBudget) {
                TextButton(onClick = onEditBudget, contentPadding = PaddingValues(horizontal = 0.dp)) {
                    Text("Set monthly budget", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Progress against a monthly limit: amber from 80%, red once over. */
@Composable
private fun BudgetMeter(item: CategoryTotal, budget: BigDecimal, onEdit: () -> Unit, editable: Boolean) {
    val used = item.usedFraction ?: 0.0
    val over = used > 1.0
    val color = when {
        over -> MaterialTheme.colorScheme.error
        used >= 0.8 -> ChartColors.warning
        else -> MaterialTheme.colorScheme.primary
    }
    val remaining = item.remaining ?: BigDecimal.ZERO

    ShareBar(used.toFloat(), color = color)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Caption(
            text = if (over) "${DisplayFormatters.rupees(remaining.negate())} over ${DisplayFormatters.rupees(budget)} budget"
            else "${DisplayFormatters.rupees(remaining)} left of ${DisplayFormatters.rupees(budget)}",
            modifier = Modifier.weight(1f).padding(top = 4.dp),
            color = if (over) MaterialTheme.colorScheme.error else mutedColor()
        )
        if (editable) {
            TextButton(onClick = onEdit, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text("Edit", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
internal fun ShareBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0.01f, 1f))
                .fillMaxHeight()
                .background(color, RoundedCornerShape(2.dp))
        )
    }
}

/** A vendor with its total; tap to see the latest payments to it. */
@Composable
internal fun VendorRow(
    vendor: VendorSummary,
    total: BigDecimal,
    expanded: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClickLabel = if (expanded) "Hide payments" else "Show payments", onClick = onClick)
            .padding(horizontal = SECTION_PADDING, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantChip(vendor.merchant.logo, description = vendor.merchant.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(vendor.merchant.displayName)
                Caption(
                    "${vendor.merchant.category.displayString} · ${vendor.count} ${if (vendor.count == 1) "payment" else "payments"} · " +
                        "last ${DateFormatters.dayHeader(java.time.Instant.ofEpochMilli(vendor.lastEpochMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()).substringBefore(",")}"
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(DisplayFormatters.rupees(vendor.total), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        ShareBar(vendor.total.fractionOf(total), Modifier.padding(start = 56.dp))

        if (expanded) {
            Column(Modifier.padding(start = 56.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vendor.recent.forEach { payment ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Caption(DateFormatters.detailTimestamp(payment.epochMillis), color = MaterialTheme.colorScheme.secondary)
                            Caption(payment.source)
                        }
                        Text(DisplayFormatters.rupees(payment.amount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
                    }
                }
                if (vendor.count > vendor.recent.size) {
                    Caption("+ ${vendor.count - vendor.recent.size} older")
                }
            }
        }
    }
}

@Composable
internal fun SourceRow(item: SourceTotal, total: BigDecimal, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(horizontal = SECTION_PADDING, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(item.label, color = MaterialTheme.colorScheme.secondary)
                Caption("${item.count} ${if (item.count == 1) "payment" else "payments"}", color = mutedColor())
            }
            Text(DisplayFormatters.rupees(item.total), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
        }
        ShareBar(item.total.fractionOf(total))
    }
}
