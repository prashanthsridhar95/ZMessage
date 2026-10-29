package com.zyke.message.presentation.dashboard.components

import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.domain.dashboard.DateRange
import com.zyke.message.presentation.common.fullWidthClickable
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.dashboard.BillEntry
import com.zyke.message.domain.cards.CardCycles
import com.zyke.message.domain.model.CardType
import com.zyke.message.domain.dashboard.CardBills
import com.zyke.message.domain.dashboard.CategoryLimits
import com.zyke.message.presentation.common.LimitStatus
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
import com.zyke.message.presentation.common.rememberAnimatedRupees
import com.zyke.message.presentation.common.rememberGrowingFraction
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.format.displayName
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.common.FilterSheet
import com.zyke.message.presentation.common.TileGrid
import com.zyke.message.presentation.common.TileOption
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

@Composable
private fun MoneyText(amount: BigDecimal, style: TextStyle, color: Color) {
    Text(DisplayFormatters.rupees(amount), style = style, color = color, maxLines = 1)
}

private fun plural(count: Int, one: String, many: String) = if (count == 1) "$count $one" else "$count $many"

/** "3 payments" when looking at money out, "3 credits" when looking at money in. */
internal fun FlowDirection.countLabel(count: Int) =
    if (this == FlowDirection.SPENT) plural(count, "payment", "payments") else plural(count, "credit", "credits")

/** When true, [SectionCard] is a row that opens a bottom sheet instead of unfolding in place. */
internal val LocalSectionsAsSheets = androidx.compose.runtime.compositionLocalOf { false }

/**
 * A titled section on the plain page: a title, a short [summary] and a chevron on one line. It unfolds in place,
 * or, where [LocalSectionsAsSheets] is set, opens its detail in a bottom sheet.
 */
@Composable
internal fun SectionCard(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    summary: String? = null,
    startOpen: Boolean = false,
    content: @Composable ColumnScope.() -> Unit
) {
    val asSheet = LocalSectionsAsSheets.current && !startOpen
    var open by rememberSaveable(title) { mutableStateOf(startOpen) }
    if (asSheet) {
        // A tile on the page; its detail is in a sheet.
        FintechCard(modifier, onClick = { open = true }) {
            Row(Modifier.semantics { contentDescription = "$title, ${summary.orEmpty()}. Opens details" }, verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).heading(), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                if (summary != null) {
                    Caption(summary)
                    Spacer(Modifier.width(8.dp))
                }
                Text("\u203A", color = mutedColor())
            }
        }
        if (open) {
            AppBottomSheet({ open = false }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                    Text(title, Modifier.heading().padding(bottom = 4.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                    if (subtitle != null) Caption(subtitle, Modifier.padding(bottom = 12.dp), maxLines = 3)
                    content()
                }
            }
        }
        return
    }
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
        Row(
            Modifier
                .fillMaxWidth()
                .semantics { if (!asSheet) stateDescription = if (open) "Expanded" else "Collapsed" }
                .fullWidthClickable(role = Role.Button, onClickLabel = if (asSheet) "Open $title" else if (open) "Fold $title" else "Open $title") {
                    open = !open
                }
                .padding(vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                title,
                Modifier.weight(1f).heading(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            if (summary != null) {
                Caption(summary)
                Spacer(Modifier.width(8.dp))
            }
            Text(if (asSheet) "\u203A" else if (open) "\u2303" else "\u2304", color = mutedColor())
        }
        if (asSheet) {
            if (open) {
                AppBottomSheet({ open = false }) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
                        Text(title, Modifier.heading().padding(bottom = 4.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                        if (subtitle != null) Caption(subtitle, Modifier.padding(bottom = 12.dp), maxLines = 3)
                        content()
                    }
                }
            }
        } else {
            AnimatedVisibility(open) {
                Column(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                    if (subtitle != null) Caption(subtitle, Modifier.padding(bottom = 10.dp), maxLines = 3)
                    content()
                }
            }
        }
    }
}

/**
 * What the hero card turns into once it has scrolled away: the same total, small, beside the Spent / Received
 * switch, so the switch is never more than a glance away.
 */
@Composable
internal fun CollapsedHeroBar(
    dashboard: Dashboard,
    onDirection: (FlowDirection) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            modifier = Modifier.weight(1f).padding(end = 12.dp),
            text = rememberAnimatedRupees(dashboard.total),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        DashboardControls(dashboard.direction, onDirection, Modifier.weight(1.4f))
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

@Composable
internal fun DashboardFilterSheet(
    period: DashboardPeriod,
    customRange: DateRange?,
    category: MessageCategory?,
    source: com.zyke.message.domain.dashboard.SourceFilter,
    sourceOptions: List<com.zyke.message.domain.dashboard.SourceOption>,
    sourceLabel: (com.zyke.message.domain.dashboard.SourceFilter) -> String,
    dashboard: Dashboard,
    canReset: Boolean,
    onPeriod: (DashboardPeriod) -> Unit,
    onCustomRange: (java.time.LocalDate, java.time.LocalDate) -> Unit,
    onCategory: (MessageCategory?) -> Unit,
    onSource: (com.zyke.message.domain.dashboard.SourceFilter) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    FilterSheet(
        title = "Filter dashboard",
        doneLabel = "Show ${dashboard.direction.countLabel(dashboard.count)}",
        canReset = canReset,
        onReset = onReset,
        onDismiss = onDismiss
    ) {
        var pickingRange by rememberSaveable { mutableStateOf(false) }
        SheetSection("Period")
        TileGrid(
            DashboardPeriod.entries.map { option ->
                if (option == DashboardPeriod.CUSTOM) {
                    val chosen = customRange?.takeIf { period == DashboardPeriod.CUSTOM }
                    TileOption(
                        option.label, option == period, { pickingRange = true },
                        chosen?.let { rangeLabel(it) } ?: "Pick the days"
                    )
                } else {
                    TileOption(option.label, option == period, { onPeriod(option) })
                }
            }
        )
        if (pickingRange) {
            CustomRangeDialog(
                initial = customRange,
                onPick = { start, end -> onCustomRange(start, end); pickingRange = false },
                onDismiss = { pickingRange = false }
            )
        }

        // Where the money moved through: UPI, one bank account or one card.
        if (sourceOptions.isNotEmpty()) {
            SheetSection("Paid through")
            TileGrid(
                buildList {
                    add(TileOption("Everything", !source.isActive, { onSource(com.zyke.message.domain.dashboard.SourceFilter()) }))
                    sourceOptions.forEach { option ->
                        add(
                            TileOption(
                                sourceLabel(option.filter), option.filter == source, { onSource(option.filter) },
                                DisplayFormatters.count(option.count, "payment", "payments")
                            )
                        )
                    }
                }
            )
        }

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

/**
 * The headline number: how much went out (or came in) this period, in one glance.
 *
 * One container colour, one "on" colour: every piece of text sits on the card in the colour the theme
 * guarantees is readable there. Direction of change is said in words, never by hue alone.
 */
@Composable
internal fun HeroCard(
    dashboard: Dashboard,
    period: DashboardPeriod,
    onDirection: (FlowDirection) -> Unit,
    modifier: Modifier = Modifier,
    /** True on the Money tab, where the totals are a switch and one is chosen; false where they only link. */
    showSelection: Boolean = true
) {
    val monthFmt = DateTimeFormatter.ofPattern("MMMM", Locale.getDefault())
    val verb = dashboard.direction.label
    val caption = when (period) {
        DashboardPeriod.THIS_MONTH, DashboardPeriod.LAST_MONTH, DashboardPeriod.CUSTOM -> "$verb · ${periodName(dashboard.range)}"
        DashboardPeriod.LAST_3_MONTHS -> "$verb in the last 3 months"
        DashboardPeriod.ALL_TIME -> "$verb in total"
    }
    val comparison = when (period) {
        DashboardPeriod.THIS_MONTH -> "than the same days last month"
        DashboardPeriod.LAST_MONTH -> "than the month before"
        DashboardPeriod.LAST_3_MONTHS -> "than the previous 3 months"
        DashboardPeriod.CUSTOM -> "than the days just before"
        DashboardPeriod.ALL_TIME -> null
    }
    val onHero = MaterialTheme.colorScheme.onSurface

    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .padding(20.dp)
    ) {
        Text(
            text = caption.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = onHero.copy(alpha = 0.8f),
            maxLines = 1
        )
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = rememberAnimatedRupees(dashboard.total),
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            color = MaterialTheme.colorScheme.primary
        )

        val change = dashboard.changePercent
        val note = if (change != null && comparison != null) {
            val percent = abs(change).roundToInt()
            val direction = if (percent == 0) "About the same" else "$percent% ${if (change >= 0) "more" else "less"}"
            "$direction $comparison"
        } else {
            null
        }
        Text(
            modifier = Modifier.padding(top = 4.dp),
            text = listOfNotNull(note, dashboard.direction.countLabel(dashboard.count).takeIf { note == null }).joinToString(),
            style = MaterialTheme.typography.bodyMedium,
            color = onHero.copy(alpha = 0.9f),
            maxLines = 2
        )

        // Both totals are always visible; tapping one switches the whole screen to it.
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowTotal(
                "Spent", dashboard.spentTotal, showSelection, showSelection && dashboard.direction == FlowDirection.SPENT,
                Modifier.weight(1f)
            ) { onDirection(FlowDirection.SPENT) }
            FlowTotal(
                "Received", dashboard.receivedTotal, showSelection, showSelection && dashboard.direction == FlowDirection.RECEIVED,
                Modifier.weight(1f)
            ) { onDirection(FlowDirection.RECEIVED) }
        }
    }
}

@Composable
private fun FlowTotal(
    label: String,
    amount: BigDecimal,
    isSwitch: Boolean,
    selected: Boolean,
    modifier: Modifier,
    onClick: () -> Unit
) {
    val onHero = MaterialTheme.colorScheme.onSurface
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            // The chosen one is lifted onto a lighter plate so the choice is visible without colour.
            .background(if (selected) onHero.copy(alpha = 0.14f) else Color.Transparent)
            .clickable(role = Role.Tab, onClickLabel = "Show $label", onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        // As a plain link both totals are equal; as a switch the unchosen one steps back.
        Text(label, style = MaterialTheme.typography.labelMedium, color = onHero.copy(alpha = if (selected || !isSwitch) 1f else 0.8f))
        Text(
            rememberAnimatedRupees(amount),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = onHero,
            maxLines = 1
        )
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
    val height = rememberGrowingFraction(point.amount.fractionOf(max).coerceAtLeast(0.04f)).coerceAtLeast(0.01f)
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
    direction: FlowDirection,
    onClick: () -> Unit,
    onEditBudget: () -> Unit,
    modifier: Modifier = Modifier
) {
    val budget = item.budget
    Column(
        modifier
            .fillMaxWidth()
            .fullWidthClickable(onClickLabel = "Show ${item.category.displayString} payments", onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).background(color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle(
                        item.category.displayString,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
                    )
                    if (selected) {
                        Image(
                            modifier = Modifier.padding(start = 6.dp).size(16.dp),
                            painter = painterResource(R.drawable.baseline_check_24),
                            colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.primary),
                            contentDescription = "Filtering by this category"
                        )
                    }
                }
                Caption(
                    if (item.count == 0) "Nothing yet"
                    else "${direction.countLabel(item.count)} · ${(item.total.fractionOf(total) * 100).roundToInt()}%"
                )
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(item.total, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.primary)
        }

        if (budget != null) {
            BudgetMeter(item, budget, onEditBudget, canSetBudget)
        } else {
            ShareBar(item.total.fractionOf(total), color = color)
            if (canSetBudget) {
                AppButton("Set monthly budget", onEditBudget, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
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
            AppButton("Edit", onEdit, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    }
}

@Composable
internal fun ShareBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    val shown = rememberGrowingFraction(fraction.coerceIn(0.01f, 1f))
    Box(
        modifier
            .padding(top = 6.dp)
            .fillMaxWidth()
            .height(4.dp)
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(2.dp))
    ) {
        Box(
            Modifier
                .fillMaxWidth(shown.coerceAtLeast(0.01f))
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
    direction: FlowDirection,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val last = Instant.ofEpochMilli(vendor.lastEpochMillis).atZone(ZoneId.systemDefault()).toLocalDate()
    Column(
        modifier
            .fillMaxWidth()
            .animateContentSize()
            .fullWidthClickable(onClickLabel = if (expanded) "Hide payments" else "Show payments", onClick = onClick)
            .padding(vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MerchantChip(vendor.merchant.logo, imageKey = vendor.merchant.imageKey, name = vendor.merchant.name, description = vendor.merchant.name, size = 44.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(vendor.merchant.displayName)
                Caption("${vendor.merchant.category.displayString} · ${direction.countLabel(vendor.count)}")
                Caption("Last ${DateFormatters.dayHeader(last).substringBefore(",")}")
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(vendor.total, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.primary)
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
                        MoneyText(payment.amount, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.secondary)
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
internal fun SourceRow(item: SourceTotal, total: BigDecimal, direction: FlowDirection, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(item.label, color = MaterialTheme.colorScheme.secondary)
                Caption(direction.countLabel(item.count))
            }
            Spacer(Modifier.width(12.dp))
            MoneyText(item.total, MaterialTheme.typography.bodyLarge, MaterialTheme.colorScheme.primary)
        }
        ShareBar(item.total.fractionOf(total))
    }
}

/** One credit card: its latest statement. Tapping it opens the card's sheet with the usage trend. */
@Composable
internal fun CardBillRow(
    bills: CardBills,
    today: LocalDate,
    onOpen: () -> Unit,
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

    Row(
        modifier
            .fillMaxWidth()
            .fullWidthClickable(onClickLabel = "Open ${bills.label}", onClick = onOpen)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
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
        // A signifier: the row opens something.
        Image(
            modifier = Modifier.size(24.dp),
            painter = painterResource(R.drawable.baseline_chevron_right_24),
            colorFilter = ColorFilter.tint(mutedColor()),
            contentDescription = null
        )
    }
}

/** How a card has been used: a bar per statement, oldest to newest, the bill to pay, and every statement listed. */
@Composable
internal fun CardBillSheet(
    bills: CardBills,
    today: LocalDate,
    onPay: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val dayFmt = DateTimeFormatter.ofPattern("dd MMM", Locale.getDefault())
    val all = listOf(bills.latest) + bills.earlier
    val oldestFirst = all.sortedBy { it.epochMillis }
    val latest = bills.latest
    val previous = bills.earlier.firstOrNull()
    val due = latest.dueDate

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(bills.label, Modifier.heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Caption(
                    if (due != null) DisplayFormatters.dueLabel(due, today) else "Statement · ${dayFmt.format(statementDate(latest))}",
                    color = if (due != null && !due.isBefore(today) && DisplayFormatters.isUrgent(due, today)) {
                        MaterialTheme.colorScheme.error
                    } else {
                        mutedColor()
                    }
                )
            }

            Column {
                Text(
                    DisplayFormatters.rupees(latest.amount),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                latest.minimumDue?.let { Caption("Minimum ${DisplayFormatters.rupees(it)}") }
                if (previous != null) {
                    val change = latest.amount.subtract(previous.amount)
                    val direction = when {
                        change.signum() > 0 -> "Up ${DisplayFormatters.rupees(change)}"
                        change.signum() < 0 -> "Down ${DisplayFormatters.rupees(change.negate())}"
                        else -> "Same"
                    }
                    Caption("$direction from the previous statement")
                }
            }

            val payLink = latest.paymentLink
            if (payLink != null) {
                AppButton("Pay bill", { onPay(payLink) }, Modifier.fillMaxWidth())
            }

            if (oldestFirst.size > 1) {
                Column {
                    RowTitle("Usage trend")
                    CardBillTrend(oldestFirst, dayFmt, Modifier.padding(top = 8.dp))
                }
            } else {
                Caption("Needs two statements")
            }

            if (bills.earlier.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    RowTitle("Earlier statements")
                    bills.earlier.forEach { bill -> EarlierBill(bill, dayFmt) }
                }
            }
        }
    }
}

@Composable
private fun CardBillTrend(statements: List<BillEntry>, dayFmt: DateTimeFormatter, modifier: Modifier = Modifier) {
    LineTrend(
        amounts = statements.map { it.amount },
        firstLabel = dayFmt.format(statementDate(statements.first())),
        lastLabel = dayFmt.format(statementDate(statements.last())),
        description = "Line chart of statement amounts. " +
            statements.joinToString(", ") { "${dayFmt.format(statementDate(it))} ${DisplayFormatters.rupees(it.amount)}" },
        modifier = modifier
    )
}

/** A line over [amounts] (oldest first) that rises from the baseline when it appears, with its first and last labels beneath. */
@Composable
internal fun LineTrend(amounts: List<BigDecimal>, firstLabel: String, lastLabel: String, description: String, modifier: Modifier = Modifier) {
    val max = amounts.maxOf { it }.coerceAtLeast(BigDecimal.ONE)
    val values = amounts.map { it.fractionOf(max) }
    val rise = rememberGrowingFraction(1f)
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)

    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(120.dp)
                .semantics { contentDescription = description }
        ) {
            val pad = 6.dp.toPx()
            val usableHeight = size.height - 2 * pad
            val step = if (values.size > 1) (size.width - 2 * pad) / (values.size - 1) else 0f
            val points = values.mapIndexed { i, v ->
                Offset(pad + step * i, size.height - pad - usableHeight * v.coerceIn(0f, 1f) * rise)
            }

            drawLine(gridColor, Offset(0f, size.height - pad), Offset(size.width, size.height - pad), strokeWidth = 1.dp.toPx())

            val line = Path().apply {
                points.forEachIndexed { i, p -> if (i == 0) moveTo(p.x, p.y) else lineTo(p.x, p.y) }
            }
            val area = Path().apply {
                addPath(line)
                lineTo(points.last().x, size.height - pad)
                lineTo(points.first().x, size.height - pad)
                close()
            }
            drawPath(area, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.25f), Color.Transparent)))
            drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            points.forEachIndexed { i, p ->
                drawCircle(lineColor, radius = if (i == points.lastIndex) 5.dp.toPx() else 3.dp.toPx(), center = p)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Caption(firstLabel)
            Caption(lastLabel, textAlign = TextAlign.End)
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
        MoneyText(bill.amount, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.secondary)
    }
}

private fun statementDate(bill: BillEntry): LocalDate =
    Instant.ofEpochMilli(bill.epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

/** Each card or account as a row with this cycle's spending; tapping one opens its trend, as the card bills do. */
@Composable
internal fun CardCyclesSection(cards: List<CardCycles>, onOpen: (String) -> Unit, modifier: Modifier = Modifier) {
    SectionCard(
        title = "Cards & accounts",
        modifier = modifier,
        summary = DisplayFormatters.count(cards.size, "card or account", "cards and accounts")
    ) {
        cards.forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider()
            CardCycleRow(entry) { onOpen(entry.card.id) }
        }
    }
}

@Composable
private fun CardCycleRow(entry: CardCycles, onOpen: () -> Unit) {
    val current = entry.cycles.firstOrNull { it.isCurrent } ?: entry.cycles.firstOrNull()
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open ${entry.card.label}", onClick = onOpen).padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Column(Modifier.weight(1f)) {
            RowTitle(entry.card.label)
            Caption(
                current?.let { "This cycle · ${plural(it.spendCount, "payment", "payments")}" }
                    ?: (entry.card.statementDay?.let { "Statement on day $it" } ?: "Calendar months")
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            DisplayFormatters.rupees(current?.spent ?: BigDecimal.ZERO),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1
        )
        Image(
            modifier = Modifier.size(24.dp),
            painter = painterResource(R.drawable.baseline_chevron_right_24),
            colorFilter = ColorFilter.tint(mutedColor()),
            contentDescription = null
        )
    }
}

/** One card or account: what this cycle has cost against the one before, the trend over the cycles, and each cycle. */
@Composable
internal fun CardCycleSheet(entry: CardCycles, onDismiss: () -> Unit) {
    val dayFmt = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val inWord = if (entry.card.type == CardType.ACCOUNT) "received" else "paid"
    val oldestFirst = entry.cycles.sortedBy { it.start }
    val current = entry.cycles.firstOrNull { it.isCurrent } ?: entry.cycles.firstOrNull()
    val previous = entry.cycles.firstOrNull { it !== current }

    AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column {
                Text(entry.card.label, Modifier.heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
                Caption(entry.card.statementDay?.let { "Statement on day $it" } ?: "Calendar months")
            }

            if (current != null) {
                Column {
                    Text(
                        DisplayFormatters.rupees(current.spent),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Caption("Spent ${dayFmt.format(current.start)} – ${dayFmt.format(current.end)}${if (current.isCurrent) " · now" else ""}")
                    if (current.paid.signum() > 0) Caption("${inWord.replaceFirstChar { it.uppercase() }} ${DisplayFormatters.rupees(current.paid)}")
                    if (previous != null) {
                        val change = current.spent.subtract(previous.spent)
                        val direction = when {
                            change.signum() > 0 -> "Up ${DisplayFormatters.rupees(change)}"
                            change.signum() < 0 -> "Down ${DisplayFormatters.rupees(change.negate())}"
                            else -> "Same"
                        }
                        Caption("$direction from the previous cycle")
                    }
                }
            }

            if (oldestFirst.size > 1) {
                Column {
                    RowTitle("Usage trend")
                    LineTrend(
                        amounts = oldestFirst.map { it.spent },
                        firstLabel = dayFmt.format(oldestFirst.first().start),
                        lastLabel = dayFmt.format(oldestFirst.last().start),
                        description = "Line chart of spending by cycle. " +
                            oldestFirst.joinToString(", ") { "${dayFmt.format(it.start)} ${DisplayFormatters.rupees(it.spent)}" },
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            } else {
                Caption("Needs two cycles")
            }

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                RowTitle("Cycles")
                entry.cycles.forEach { cycle ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Caption(
                                "${dayFmt.format(cycle.start)} – ${dayFmt.format(cycle.end)}" + if (cycle.isCurrent) " · now" else "",
                                color = MaterialTheme.colorScheme.secondary
                            )
                            Caption(
                                buildString {
                                    append(plural(cycle.spendCount, "payment", "payments"))
                                    if (cycle.paid.signum() > 0) append(" · $inWord ${DisplayFormatters.rupees(cycle.paid)}")
                                }
                            )
                        }
                        MoneyText(cycle.spent, MaterialTheme.typography.bodyMedium, MaterialTheme.colorScheme.secondary)
                    }
                }
            }
        }
    }
}

/**
 * How the spending limits are going, in green and red. Limits are set in Settings, so this only shows them and
 * points the way there.
 */
@Composable
internal fun LimitsCard(limits: List<CategoryLimits>, onManage: () -> Unit, modifier: Modifier = Modifier) {
    SectionCard(title = "Spending limits", modifier = modifier) {
        if (limits.isEmpty()) {
            Caption("No limits set")
        } else {
            limits.take(MAX_LIMITS_SHOWN).forEachIndexed { index, entry ->
                if (index > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                RowTitle(entry.category.displayString)
                entry.daily?.let { LimitStatus(it, Modifier.padding(top = 4.dp)) }
                entry.monthly?.let { LimitStatus(it, Modifier.padding(top = 8.dp)) }
            }
            if (limits.size > MAX_LIMITS_SHOWN) {
                Caption("${limits.size - MAX_LIMITS_SHOWN} more in Settings", Modifier.padding(top = 10.dp))
            }
        }
        AppButton(
            text = if (limits.isEmpty()) "Set limits in Settings" else "Manage limits in Settings",
            onClick = onManage,
            style = ButtonStyle.TONAL,
            size = ButtonSize.COMPACT,
            modifier = Modifier.padding(top = 12.dp)
        )
    }
}

private const val MAX_LIMITS_SHOWN = 4

private val RangeFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())

/** "28 Sep – 5 Oct", or just the month's name when the days are a whole calendar month. */
internal fun rangeLabel(range: DateRange): String = "${RangeFormat.format(range.start)} – ${RangeFormat.format(range.endInclusive)}"

/** What to call a period on screen: the month's name for a calendar month so far, else the days it covers. */
internal fun periodName(range: DateRange): String {
    val calendarMonth = range.start.dayOfMonth == 1 && java.time.YearMonth.from(range.start) == java.time.YearMonth.from(range.endInclusive)
    return if (calendarMonth) DateTimeFormatter.ofPattern("MMMM", Locale.getDefault()).format(range.start) else rangeLabel(range)
}

/** Pick any two days from a calendar. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun CustomRangeDialog(initial: DateRange?, onPick: (java.time.LocalDate, java.time.LocalDate) -> Unit, onDismiss: () -> Unit) {
    fun millis(date: java.time.LocalDate?) = date?.atStartOfDay(java.time.ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
    fun day(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate()
    val state = androidx.compose.material3.rememberDateRangePickerState(
        initialSelectedStartDateMillis = millis(initial?.start),
        initialSelectedEndDateMillis = millis(initial?.endInclusive)
    )
    val start = state.selectedStartDateMillis
    val end = state.selectedEndDateMillis
    androidx.compose.material3.DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            AppButton(
                "Use these days",
                { if (start != null && end != null) onPick(day(start), day(end)) },
                style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = start != null && end != null
            )
        },
        dismissButton = { AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
    ) {
        androidx.compose.material3.DateRangePicker(state, Modifier.height(480.dp))
    }
}
