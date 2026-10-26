package com.zyke.message.presentation.dashboard.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.dashboard.BillEntry
import com.zyke.message.domain.dashboard.CardBills
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
import com.zyke.message.presentation.common.FilterSheet
import com.zyke.message.presentation.common.TileGrid
import com.zyke.message.presentation.common.TileOption
import com.zyke.message.presentation.common.UnderlineTabs
import com.zyke.message.presentation.common.SheetSection
import com.zyke.message.presentation.messages.components.MerchantChip
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.SegmentedControl
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private fun BigDecimal.fractionOf(total: BigDecimal): Float =
    if (total.signum() <= 0) 0f else (toDouble() / total.toDouble()).toFloat().coerceIn(0f, 1f)

private fun plural(count: Int, one: String, many: String) = if (count == 1) "$count $one" else "$count $many"

/** A titled, rounded panel. Every section of the dashboard sits in one so they line up. */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        if (subtitle != null) Caption(subtitle, Modifier.padding(top = 2.dp))
        Spacer(Modifier.height(12.dp))
        content()
    }
}

/** The Spent / Received switch; period tabs sit above and the category filter lives in the sheet. */
@Composable
internal fun DashboardControls(
    direction: FlowDirection,
    onDirection: (FlowDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    SegmentedControl(
        options = FlowDirection.entries.map { it.label },
        selectedIndex = direction.ordinal,
        onSelect = { onDirection(FlowDirection.entries[it]) },
        modifier = modifier
    )
}

/** Period as quick tabs along the top of the Money screen. */
@Composable
internal fun PeriodTabs(period: DashboardPeriod, onSelect: (DashboardPeriod) -> Unit, modifier: Modifier = Modifier) {
    UnderlineTabs(
        labels = DashboardPeriod.entries.map { it.label },
        counts = emptyList(),
        selectedIndex = period.ordinal,
        onSelect = { onSelect(DashboardPeriod.entries[it]) },
        modifier = modifier
    )
}

@Composable
internal fun DashboardFilterSheet(
    category: MessageCategory?,
    dashboard: Dashboard,
    canReset: Boolean,
    onCategory: (MessageCategory?) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    FilterSheet(
        title = "Filter by category",
        doneLabel = "Show ${plural(dashboard.count, "payment", "payments")}",
        canReset = canReset,
        onReset = onReset,
        onDismiss = onDismiss
    ) {
        SheetSection("Category")
        TileGrid(
            buildList {
                add(TileOption("All categories", category == null, { onCategory(null) }))
                dashboard.categories.forEach {
                    add(
                        TileOption(
                            it.category.displayString, it.category == category, { onCategory(it.category) },
                            DisplayFormatters.rupees(it.total)
                        )
                    )
                }
            }
        )
    }
}

/** The headline number, with a comparison against the previous period. */
@Composable
internal fun HeroCard(dashboard: Dashboard, period: DashboardPeriod, modifier: Modifier = Modifier) {
    val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    val verb = dashboard.direction.label.lowercase()
    val caption = when (period) {
        DashboardPeriod.THIS_MONTH, DashboardPeriod.LAST_MONTH -> "$verb in ${monthFmt.format(dashboard.range.start)}"
        DashboardPeriod.LAST_3_MONTHS -> "$verb in the last 3 months"
        DashboardPeriod.ALL_TIME -> "$verb in total"
    }
    val comparison = when (period) {
        DashboardPeriod.THIS_MONTH -> "vs same days last month"
        DashboardPeriod.LAST_MONTH -> "vs the month before"
        DashboardPeriod.LAST_3_MONTHS -> "vs the previous 3 months"
        DashboardPeriod.ALL_TIME -> null
    }
    val onHero = MaterialTheme.colorScheme.onSecondaryContainer

    Column(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(20.dp))
            .padding(20.dp)
    ) {
        Text(
            text = DisplayFormatters.rupees(dashboard.total),
            style = MaterialTheme.typography.displaySmall,
            maxLines = 1,
            color = onHero
        )
        Caption(
            "$caption · ${plural(dashboard.count, "payment", "payments")}",
            Modifier.padding(top = 2.dp),
            color = onHero.copy(alpha = 0.7f),
            maxLines = 2
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

        HorizontalDivider(Modifier.padding(vertical = 12.dp), color = onHero.copy(alpha = 0.15f))
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Caption("Spent", color = onHero.copy(alpha = 0.7f))
                Text(DisplayFormatters.rupees(dashboard.spentTotal), style = MaterialTheme.typography.titleSmall, color = onHero, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Caption("Received", color = onHero.copy(alpha = 0.7f))
                Text(DisplayFormatters.rupees(dashboard.receivedTotal), style = MaterialTheme.typography.titleSmall, color = onHero, maxLines = 1)
            }
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
    val summary = if (max.signum() > 0) "Peak ${DisplayFormatters.rupees(max)} on ${fmt.format(peak.start)}" else "No activity"

    Column(modifier.fillMaxWidth()) {
        Caption(summary, Modifier.padding(bottom = 8.dp), color = MaterialTheme.colorScheme.secondary)
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
                                .height(2.dp)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f))
                        )
                    } else {
                        StackedBar(point, max, ranking)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(fmt.format(points.first().start))
            Caption(fmt.format(points.last().start), textAlign = TextAlign.End)
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
        modifier = modifier.fillMaxWidth(),
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
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(
                    item.category.displayString + if (selected) "  ✓" else "",
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                )
                Caption(
                    if (item.count == 0) "Nothing yet"
                    else "${plural(item.count, "payment", "payments")} · ${(item.total.fractionOf(total) * 100).roundToInt()}%"
                )
            }
            Spacer(Modifier.width(12.dp))
            Text(DisplayFormatters.rupees(item.total), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }

        if (budget != null) {
            BudgetMeter(item, budget, onEditBudget, canSetBudget)
        } else {
            ShareBar(item.total.fractionOf(total), color = color)
            if (canSetBudget) {
                TextButton(onClick = onEditBudget, contentPadding = PaddingValues(0.dp)) {
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
            color = if (over) MaterialTheme.colorScheme.error else mutedColor(),
            maxLines = 2
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
    val last = Instant.ofEpochMilli(vendor.lastEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(onClickLabel = if (expanded) "Hide payments" else "Show payments", onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantChip(vendor.merchant.logo, description = vendor.merchant.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(vendor.merchant.displayName)
                Caption("${vendor.merchant.category.displayString} · ${plural(vendor.count, "payment", "payments")}")
                Caption("Last ${DateFormatters.dayHeader(last).substringBefore(",")}")
            }
            Spacer(Modifier.width(12.dp))
            Text(DisplayFormatters.rupees(vendor.total), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        ShareBar(vendor.total.fractionOf(total), Modifier.padding(start = 56.dp))

        if (expanded) {
            Column(Modifier.padding(start = 56.dp, top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vendor.recent.forEach { payment ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Caption(DateFormatters.detailTimestamp(payment.epochMillis), color = MaterialTheme.colorScheme.secondary)
                            Caption(payment.source)
                        }
                        Spacer(Modifier.width(12.dp))
                        Text(DisplayFormatters.rupees(payment.amount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
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
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(item.label, color = MaterialTheme.colorScheme.secondary)
                Caption(plural(item.count, "payment", "payments"))
            }
            Spacer(Modifier.width(12.dp))
            Text(DisplayFormatters.rupees(item.total), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary, maxLines = 1)
        }
        ShareBar(item.total.fractionOf(total))
    }
}

/** One credit card: its latest statement, with earlier ones on tap. */
@Composable
internal fun CardBillRow(
    bills: CardBills,
    today: LocalDate,
    expanded: Boolean,
    onToggle: () -> Unit,
    onPay: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val latest = bills.latest
    val due = latest.dueDate
    val passed = due != null && due.isBefore(today)
    val urgent = due != null && !passed && DisplayFormatters.isUrgent(due, today)
    val dayFmt = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())
    val status = when {
        due == null -> "Statement · ${dayFmt.format(statementDate(latest))}"
        passed -> "Due date passed · ${dayFmt.format(due)}"
        else -> DisplayFormatters.dueLabel(due, today)
    }

    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable(
                enabled = bills.earlier.isNotEmpty(),
                onClickLabel = if (expanded) "Hide earlier bills" else "Show earlier bills",
                onClick = onToggle
            )
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                RowTitle(bills.label)
                Caption(status, color = if (urgent) MaterialTheme.colorScheme.error else mutedColor())
                latest.minimumDue?.let { Caption("Minimum ${DisplayFormatters.rupees(it)}") }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                DisplayFormatters.rupees(latest.amount),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1
            )
        }

        if (latest.paymentLink != null && !passed) {
            TextButton(onClick = { onPay(latest.paymentLink) }, contentPadding = PaddingValues(0.dp)) {
                Text("View / pay bill", style = MaterialTheme.typography.bodySmall)
            }
        }

        if (expanded) {
            Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                bills.earlier.forEach { bill -> EarlierBill(bill, dayFmt) }
            }
        } else if (bills.earlier.isNotEmpty()) {
            Caption("${bills.earlier.size} earlier ${if (bills.earlier.size == 1) "bill" else "bills"}")
        }
    }
}

@Composable
private fun EarlierBill(bill: BillEntry, dayFmt: DateTimeFormatter) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Caption(
            "Statement ${dayFmt.format(statementDate(bill))}",
            Modifier.weight(1f),
            color = MaterialTheme.colorScheme.secondary
        )
        Text(DisplayFormatters.rupees(bill.amount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1)
    }
}

private fun statementDate(bill: BillEntry): LocalDate =
    Instant.ofEpochMilli(bill.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
