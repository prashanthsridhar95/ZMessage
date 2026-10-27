package com.zyke.message.presentation.home

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

/** A pay cycle of days, each green when it stayed within the daily budget and red when it went over; the streak above. */
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
    val dayFormat = DateTimeFormatter.ofPattern("d MMM", Locale.getDefault())
    val leading = (range.start.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7

    AppBottomSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Budget calendar", Modifier.heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Caption(
                "${DisplayFormatters.count(daily.currentStreak, "day", "days")} in a row · best ${daily.bestStreak}",
                Modifier.padding(top = 2.dp),
                color = if (daily.currentStreak > 0) UnderColor else mutedColor()
            )
            Caption("Daily budget ${DisplayFormatters.rupees(daily.allowance)}. Investments, savings, insurance, charity and card bills don't count.", Modifier.padding(top = 2.dp), maxLines = 3)

            Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                AppButton("‹", { onShift(-1) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                Text(
                    "${dayFormat.format(range.start)} – ${dayFormat.format(range.endInclusive)}", Modifier.weight(1f), textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary
                )
                AppButton("›", { onShift(1) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = offset < 0)
            }

            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                (0..6).forEach { offsetDay ->
                    Caption(
                        DayOfWeek.MONDAY.plus(offsetDay.toLong()).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        Modifier.weight(1f), textAlign = TextAlign.Center
                    )
                }
            }
            val cells = leading + days.size
            (0 until (cells + 6) / 7).forEach { week ->
                Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    (0..6).forEach { column ->
                        val index = week * 7 + column - leading
                        val day = days.getOrNull(index)
                        Box(Modifier.weight(1f).aspectRatio(1f).padding(3.dp), contentAlignment = Alignment.Center) {
                            if (day != null) DayCell(day, isToday = day.date == today)
                        }
                    }
                }
            }
            Row(Modifier.padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Legend(UnderColor, "Within budget")
                Legend(OverColor, "Over budget")
            }
        }
    }
}

@Composable
private fun DayCell(day: BudgetDay, isToday: Boolean) {
    val fill = when (day.status) {
        DayStatus.UNDER -> UnderColor.copy(alpha = 0.22f)
        DayStatus.OVER -> OverColor.copy(alpha = 0.22f)
        DayStatus.NONE -> Color.Transparent
    }
    val text = when (day.status) {
        DayStatus.UNDER -> UnderColor
        DayStatus.OVER -> OverColor
        DayStatus.NONE -> mutedColor()
    }
    val spoken = when (day.status) {
        DayStatus.UNDER -> "within budget, spent ${DisplayFormatters.rupees(day.spent)}"
        DayStatus.OVER -> "over budget, spent ${DisplayFormatters.rupees(day.spent)}"
        DayStatus.NONE -> "no data"
    }
    Box(
        Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(fill)
            .then(if (isToday) Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)) else Modifier)
            .semantics { contentDescription = "${day.date.dayOfMonth}, $spoken" },
        contentAlignment = Alignment.Center
    ) {
        Text(
            day.date.dayOfMonth.toString(), style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, color = text
        )
    }
}

@Composable
private fun Legend(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
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
