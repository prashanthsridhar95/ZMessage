package com.zyke.message.presentation.home

import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import com.zyke.message.domain.cycle.CycleRule
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.budget.BudgetDay
import com.zyke.message.domain.budget.DailyBudgetStatus
import com.zyke.message.domain.budget.DailyBudgets
import com.zyke.message.domain.budget.DayStatus
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.plan.QuickAdd
import com.zyke.message.presentation.common.AppBottomSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val UnderColor = Color(0xFF34A853)
private val OverColor = Color(0xFFE8505B)

/** Today's share of what is spendable, how much of it is left, and the run of days kept within it. Tap for the calendar. */
@Composable
internal fun TodayBudget(daily: DailyBudgetStatus, onOpen: () -> Unit) {
    val over = daily.isOver
    val color = if (over) OverColor else UnderColor
    val used = if (daily.allowance.signum() > 0) (daily.spentToday.toFloat() / daily.allowance.toFloat()) else 1f

    Column(Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Open budget calendar", onClick = onOpen)) {
        Caption("TODAY'S BUDGET")
        Text(
            DisplayFormatters.rupees(if (over) daily.leftToday.negate() else daily.leftToday),
            Modifier.padding(top = 4.dp),
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1
        )
        Caption(
            if (over) "over today's ${DisplayFormatters.rupees(daily.allowance)}"
            else "left of ${DisplayFormatters.rupees(daily.allowance)} · spent ${DisplayFormatters.rupees(daily.spentToday)}"
        )
        ShareBar(used, color = color)
        Caption(
            if (daily.currentStreak > 0) {
                "${DisplayFormatters.count(daily.currentStreak, "day", "days")} within budget in a row"
            } else {
                "Stay within budget today to start a streak"
            },
            Modifier.padding(top = 8.dp),
            color = if (daily.currentStreak > 0) UnderColor else mutedColor()
        )
    }
}

/**
 * The budget calendar: the streak as the headline, then the pay cycle as a plain grid. Days are not painted red or
 * green; a small mark under the date says whether the day stayed within budget, the days of the current streak
 * share one soft band, today has an outline, and tapping a day shows what was spent on it.
 */
@Composable
internal fun BudgetCalendarSheet(
    messages: List<ClassifiedMessage>,
    daily: DailyBudgetStatus,
    cycle: CycleRule,
    offset: Int,
    now: Instant,
    onShift: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val today = java.time.LocalDate.ofInstant(now, zone)
    val range = remember(cycle, offset, today) { cycle.shifted(today, offset) }
    val days = remember(messages, daily.allowance, range, now) { DailyBudgets.days(messages, daily.allowance, range, now, zone) }
    val streakDays = remember(daily.currentStreak, today) { (0 until daily.currentStreak).map { today.minusDays(it.toLong()) }.toSet() }
    var selected by rememberSaveable(range.start) { mutableStateOf<java.time.LocalDate?>(null) }
    val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val leading = (range.start.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val within = days.count { it.status == DayStatus.UNDER }
    val over = days.count { it.status == DayStatus.OVER }

    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Caption("DAILY BUDGET STREAK")
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
                Text(
                    daily.currentStreak.toString(),
                    Modifier.heading(),
                    style = MaterialTheme.typography.displayLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    if (daily.currentStreak == 1) "day" else "days",
                    Modifier.padding(start = 8.dp, bottom = 10.dp),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }
            Caption(
                if (daily.currentStreak > 0) "within ${DisplayFormatters.rupees(daily.allowance)} a day · best ${daily.bestStreak}"
                else "Stay within ${DisplayFormatters.rupees(daily.allowance)} today to start one · best ${daily.bestStreak}",
                maxLines = 2
            )

            HorizontalDivider(Modifier.padding(vertical = 18.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                AppButton("‹", { onShift(-1) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "${dayFormat.format(range.start)} – ${dayFormat.format(range.endInclusive)}",
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary
                    )
                    Caption("$within within budget · $over over")
                }
                AppButton("›", { onShift(1) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = offset < 0)
            }

            Row(Modifier.fillMaxWidth().padding(top = 14.dp)) {
                (0..6).forEach { offsetDay ->
                    Caption(
                        DayOfWeek.MONDAY.plus(offsetDay.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2).uppercase(),
                        Modifier.weight(1f), textAlign = TextAlign.Center
                    )
                }
            }
            val cells = leading + days.size
            (0 until (cells + 6) / 7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                    (0..6).forEach { column ->
                        val day = days.getOrNull(week * 7 + column - leading)
                        Box(Modifier.weight(1f).height(48.dp), contentAlignment = Alignment.Center) {
                            if (day != null) {
                                DayCell(
                                    day,
                                    isToday = day.date == today,
                                    inStreak = day.date in streakDays,
                                    isSelected = day.date == selected,
                                    onClick = { selected = if (selected == day.date) null else day.date }
                                )
                            }
                        }
                    }
                }
            }

            val chosen = days.firstOrNull { it.date == selected }
            Column(Modifier.fillMaxWidth().padding(top = 16.dp).heightIn(min = 44.dp)) {
                if (chosen != null) {
                    Text(
                        DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(chosen.date),
                        style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary
                    )
                    Caption(
                        when (chosen.status) {
                            DayStatus.UNDER -> "${DisplayFormatters.rupees(chosen.spent)} of ${DisplayFormatters.rupees(daily.allowance)} · within budget"
                            DayStatus.OVER -> "${DisplayFormatters.rupees(chosen.spent)} of ${DisplayFormatters.rupees(daily.allowance)} · over by ${DisplayFormatters.rupees(chosen.spent - daily.allowance)}"
                            DayStatus.NONE -> "No data for this day"
                        },
                        maxLines = 2
                    )
                } else {
                    Caption("Tap a day to see what was spent. Investments, savings, insurance, charity and card bills don't count.", maxLines = 3)
                }
            }

            Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Legend(UnderColor, "Within budget")
                Legend(OverColor, "Over budget")
            }
        }
    }
}

/** A date with a small mark beneath it: green within budget, red over, none for days that have not happened. */
@Composable
private fun DayCell(day: BudgetDay, isToday: Boolean, inStreak: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val mark = when (day.status) {
        DayStatus.UNDER -> UnderColor
        DayStatus.OVER -> OverColor
        DayStatus.NONE -> Color.Transparent
    }
    val shape = RoundedCornerShape(10.dp)
    val ink = MaterialTheme.colorScheme.primary
    val spoken = when (day.status) {
        DayStatus.UNDER -> "within budget, spent ${DisplayFormatters.rupees(day.spent)}"
        DayStatus.OVER -> "over budget, spent ${DisplayFormatters.rupees(day.spent)}"
        DayStatus.NONE -> "no data"
    }
    Column(
        Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .background(
                when {
                    isSelected -> ink.copy(alpha = 0.14f)
                    inStreak -> ink.copy(alpha = 0.07f)
                    else -> Color.Transparent
                }
            )
            .then(if (isToday) Modifier.border(1.5.dp, ink, shape) else Modifier)
            .clickable(role = androidx.compose.ui.semantics.Role.Button, onClickLabel = "Show spending for day ${day.date.dayOfMonth}", onClick = onClick)
            .semantics { contentDescription = "${day.date.dayOfMonth}, $spoken" },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            day.date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (day.status == DayStatus.NONE) mutedColor() else MaterialTheme.colorScheme.primary
        )
        Box(Modifier.padding(top = 3.dp).size(width = 14.dp, height = 3.dp).background(mark, RoundedCornerShape(2.dp)))
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = 14.dp, height = 3.dp).background(color, RoundedCornerShape(2.dp)))
        Spacer(Modifier.width(8.dp))
        Caption(label)
    }
}

/** The one line on Home that says the messages show things to add. */
@Composable
internal fun QuickAddsPrompt(count: Int, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Review what was found", onClick = onOpen).padding(vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RowTitle("We found ${DisplayFormatters.count(count, "thing", "things")} to add", Modifier.weight(1f))
        Caption("Review")
    }
}

/** Salary, regular outflows, investments, cards and accounts found in the messages, each added in one tap. */
@Composable
internal fun QuickAddSheet(
    items: List<QuickAdd>,
    onAdd: (QuickAdd) -> Unit,
    onDismissItem: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Found in your messages", Modifier.heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Caption("Nothing is added until you tap Add. This is worked out on your phone.", Modifier.padding(bottom = 8.dp), maxLines = 2)
            if (items.isEmpty()) Caption("All done.", Modifier.padding(vertical = 16.dp))
            items.forEachIndexed { index, quick ->
                if (index > 0) HorizontalDivider()
                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                    RowTitle(quick.title)
                    Caption(quick.detail, maxLines = 2)
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AppButton("Add", { onAdd(quick) }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
                        AppButton("Not now", { onDismissItem(quick.key) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                    }
                }
            }
        }
    }
}
