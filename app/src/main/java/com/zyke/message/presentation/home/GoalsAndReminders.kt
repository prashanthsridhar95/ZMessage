package com.zyke.message.presentation.home

import com.zyke.message.domain.goals.Goals
import com.zyke.message.presentation.common.ChipOption
import com.zyke.message.presentation.common.Chips
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.FormGroup
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.heading
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.goals.GoalProgress
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.ReminderDue
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import com.zyke.message.presentation.messages.components.mutedColor
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private val DayFormat = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())

@Composable
internal fun GoalDialog(goal: Goal?, onSave: (Goal) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    var name by rememberSaveable { mutableStateOf(goal?.name.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(goal?.target?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var saved by rememberSaveable { mutableStateOf(goal?.saved?.takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dateMillis by rememberSaveable { mutableStateOf(goal?.targetDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val amount = target.toBigDecimalOrNull()
    val date = dateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
    val presets = listOf("3 months" to today.plusMonths(3), "6 months" to today.plusMonths(6), "1 year" to today.plusYears(1))
    val preview = if (amount != null && amount.signum() > 0) {
        Goals.progress(Goal("preview", name, amount, saved.toBigDecimalOrNull() ?: BigDecimal.ZERO, date), today).neededPerMonth
    } else {
        null
    }

    FormSheet(
        title = if (goal == null) "New goal" else "Edit goal",
        onDismiss = onDismiss,
        primaryLabel = if (goal == null) "Create goal" else "Save changes",
        primaryEnabled = name.isNotBlank() && amount != null && amount.signum() > 0,
        onPrimary = { onSave(Goal(goal?.id ?: UUID.randomUUID().toString(), name.trim(), amount!!, saved.toBigDecimalOrNull() ?: BigDecimal.ZERO, date)) },
        secondaryLabel = if (onRemove != null) "Delete goal" else null,
        onSecondary = onRemove
    ) {
        FormField("Goal", name, { name = it }, placeholder = "Goa trip, new laptop, rainy-day fund")
        AmountField("Target amount", target, { target = it })
        FormGroup("Target date (optional)") {
            Chips(
                buildList {
                    add(ChipOption("None", date == null))
                    presets.forEach { (label, day) -> add(ChipOption(label, date == day)) }
                    add(ChipOption(if (date != null && presets.none { it.second == date }) DayFormat.format(date) else "Pick a date", date != null && presets.none { it.second == date }))
                },
                onSelect = { index ->
                    when {
                        index == 0 -> dateMillis = null
                        index <= presets.size -> dateMillis = presets[index - 1].second.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                        else -> picking = true
                    }
                }
            )
            preview?.let { Caption("${DisplayFormatters.rupees(it)} a month gets you there") }
        }
        if (goal == null) AmountField("Already saved (optional)", saved, { saved = it })
    }
    if (picking) DayPickerDialog(dateMillis, onPick = { dateMillis = it; picking = false }, onDismiss = { picking = false })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DayPickerDialog(initial: Long?, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(initialSelectedDateMillis = initial)
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            AppButton("Use this day", { state.selectedDateMillis?.let(onPick) }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = state.selectedDateMillis != null)
        },
        dismissButton = { AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
    ) { DatePicker(state) }
}

@Composable
internal fun ReminderDialog(reminder: Reminder?, today: LocalDate, onSave: (Reminder) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(reminder?.title.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(reminder?.amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dateMillis by rememberSaveable {
        mutableStateOf((reminder?.date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    }
    var monthly by rememberSaveable { mutableStateOf(reminder?.repeatsMonthly ?: false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val day = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()
    val presets = listOf("Today" to today, "Tomorrow" to today.plusDays(1), "In a week" to today.plusWeeks(1))
    val custom = presets.none { it.second == day }

    FormSheet(
        title = if (reminder == null) "New reminder" else "Edit reminder",
        onDismiss = onDismiss,
        primaryLabel = if (reminder == null) "Add reminder" else "Save changes",
        primaryEnabled = title.isNotBlank(),
        onPrimary = { onSave(Reminder(reminder?.id ?: UUID.randomUUID().toString(), title.trim(), amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }, day, monthly)) },
        secondaryLabel = if (onRemove != null) "Delete reminder" else null,
        onSecondary = onRemove
    ) {
        FormField("Reminder", title, { title = it }, placeholder = "Rent, insurance premium, pay Ravi")
        AmountField("Amount (optional)", amount, { amount = it })
        FormGroup("When") {
            Chips(
                buildList {
                    presets.forEach { (label, d) -> add(ChipOption(label, day == d)) }
                    add(ChipOption(if (custom) DayFormat.format(day) else "Pick a date", custom))
                },
                onSelect = { index ->
                    if (index < presets.size) dateMillis = presets[index].second.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else picking = true
                }
            )
        }
        FormGroup("Repeat") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Every month", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    Caption("on day ${day.dayOfMonth}")
                }
                Switch(checked = monthly, onCheckedChange = { monthly = it })
            }
        }
    }
    if (picking) DayPickerDialog(dateMillis, onPick = { dateMillis = it; picking = false }, onDismiss = { picking = false })
}

/** One line on Home for something planned ahead; it opens its own sheet. */
@Composable
internal fun PlanTile(title: String, summary: String, onOpen: () -> Unit) {
    FintechCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTitle(title, Modifier.weight(1f))
            Caption(summary)
            Spacer(Modifier.width(8.dp))
            Text("\u203A", color = mutedColor())
        }
    }
}

/** A square button with an icon and a small name beneath it, for a page of its own. */
@Composable
internal fun ShortcutTile(label: String, icon: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    FintechCard(modifier.aspectRatio(1f), onClick = onOpen) {
        Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )
            Text(
                label,
                Modifier.padding(top = 10.dp),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 1
            )
        }
    }
}

/** The page a plan shortcut opens: its card, with room round it. */
@Composable
internal fun PlanPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    com.zyke.message.presentation.common.AppPage(title, onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** The sheet a plan tile opens: its card, with room round it. */
@Composable
internal fun PlanSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    com.zyke.message.presentation.common.AppBottomSheet(onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(title, Modifier.padding(horizontal = 4.dp).heading(), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}
