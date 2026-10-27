package com.zyke.message.presentation.home

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

/** The things being saved for, each with a bar and what it still needs; money is added by hand. */
@Composable
internal fun GoalsCard(
    goals: List<GoalProgress>,
    onSave: (Goal) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String, BigDecimal) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var topping by rememberSaveable { mutableStateOf<String?>(null) }

    FintechCard {
        RowTitle("Goals")
        if (goals.isEmpty()) Caption("Nothing yet", Modifier.padding(top = 2.dp))
        goals.forEachIndexed { index, progress ->
            if (index > 0) HorizontalDivider(Modifier.padding(top = 14.dp), color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Column(Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Edit ${progress.goal.name}") { editing = progress.goal.id }.padding(top = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RowTitle(progress.goal.name, Modifier.weight(1f))
                    Text(
                        "${DisplayFormatters.rupees(progress.goal.saved)} of ${DisplayFormatters.rupees(progress.goal.target)}",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1
                    )
                }
                ShareBar(progress.fraction)
                Caption(
                    when {
                        progress.isReached -> "Reached"
                        progress.isLate -> "Past due · ${DisplayFormatters.rupees(progress.remaining)} to go"
                        progress.neededPerMonth != null ->
                            "${DisplayFormatters.rupees(progress.neededPerMonth!!)} a month"
                        else -> "${DisplayFormatters.rupees(progress.remaining)} to go"
                    },
                    Modifier.padding(top = 6.dp), maxLines = 2,
                    color = if (progress.isLate) MaterialTheme.colorScheme.error else mutedColor()
                )
                if (!progress.isReached) {
                    AppButton("Add money", { topping = progress.goal.id }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        AppButton("New goal", { adding = true }, style = ButtonStyle.OUTLINE, modifier = Modifier.padding(top = 16.dp))
    }

    if (adding) GoalDialog(null, onSave = { onSave(it); adding = false }, onRemove = null, onDismiss = { adding = false })
    goals.firstOrNull { it.goal.id == editing }?.let { progress ->
        GoalDialog(progress.goal, onSave = { onSave(it); editing = null }, onRemove = { onRemove(progress.goal.id); editing = null }, onDismiss = { editing = null })
    }
    goals.firstOrNull { it.goal.id == topping }?.let { progress ->
        var amount by rememberSaveable { mutableStateOf("") }
        val value = amount.toBigDecimalOrNull()
        AlertDialog(
            onDismissRequest = { topping = null },
            title = { Text("Add to ${progress.goal.name}") },
            text = {
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    prefix = { Text("₹") }, label = { Text("Amount") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
            },
            confirmButton = {
                AppButton("Add", { onAdd(progress.goal.id, value!!); topping = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = value != null && value.signum() > 0)
            },
            dismissButton = { AppButton("Cancel", { topping = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GoalDialog(goal: Goal?, onSave: (Goal) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var name by rememberSaveable { mutableStateOf(goal?.name.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(goal?.target?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dateMillis by rememberSaveable { mutableStateOf(goal?.targetDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val amount = target.toBigDecimalOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (goal == null) "New goal" else "Edit goal") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("What for") })
                OutlinedTextField(
                    value = target, onValueChange = { target = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    prefix = { Text("₹") }, label = { Text("Target") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(dateMillis?.let { "By ${DayFormat.format(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate())}" } ?: "No date", Modifier.weight(1f))
                    AppButton(if (dateMillis == null) "Pick a date" else "Change", { picking = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                    if (dateMillis != null) AppButton("Clear", { dateMillis = null }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
            }
        },
        confirmButton = {
            AppButton(
                "Save",
                {
                    onSave(
                        Goal(
                            goal?.id ?: UUID.randomUUID().toString(), name.trim(), amount!!, goal?.saved ?: BigDecimal.ZERO,
                            dateMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        )
                    )
                },
                style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = name.isNotBlank() && amount != null && amount.signum() > 0
            )
        },
        dismissButton = {
            if (onRemove != null) AppButton("Remove", onRemove, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            else AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    )
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

/** Days the person asked to be told about, soonest first; the morning digest mentions the ones that are today or tomorrow. */
@Composable
internal fun RemindersCard(
    reminders: List<ReminderDue>,
    today: LocalDate,
    onSave: (Reminder) -> Unit,
    onRemove: (String) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    FintechCard {
        RowTitle("Reminders")
        if (reminders.isEmpty()) Caption("Nothing yet", Modifier.padding(top = 2.dp))
        reminders.forEachIndexed { index, due ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Row(
                Modifier.fillMaxWidth().fullWidthClickable(onClickLabel = "Edit ${due.reminder.title}") { editing = due.reminder.id }.padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    RowTitle(due.reminder.title)
                    Caption(DisplayFormatters.dueLabel(due.on, today) + if (due.reminder.repeatsMonthly) " · every month" else "")
                }
                due.reminder.amount?.let {
                    Spacer(Modifier.width(12.dp))
                    Text(DisplayFormatters.rupees(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        AppButton("New reminder", { adding = true }, style = ButtonStyle.OUTLINE, modifier = Modifier.padding(top = 8.dp))
    }

    if (adding) ReminderDialog(null, today, onSave = { onSave(it); adding = false }, onRemove = null, onDismiss = { adding = false })
    reminders.firstOrNull { it.reminder.id == editing }?.let { due ->
        ReminderDialog(due.reminder, today, onSave = { onSave(it); editing = null }, onRemove = { onRemove(due.reminder.id); editing = null }, onDismiss = { editing = null })
    }
}

@Composable
private fun ReminderDialog(reminder: Reminder?, today: LocalDate, onSave: (Reminder) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    var title by rememberSaveable { mutableStateOf(reminder?.title.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(reminder?.amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dateMillis by rememberSaveable {
        mutableStateOf((reminder?.date ?: today).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
    }
    var monthly by rememberSaveable { mutableStateOf(reminder?.repeatsMonthly ?: false) }
    var picking by rememberSaveable { mutableStateOf(false) }
    val day = Instant.ofEpochMilli(dateMillis).atZone(ZoneOffset.UTC).toLocalDate()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (reminder == null) "New reminder" else "Edit reminder") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, singleLine = true, label = { Text("What") })
                OutlinedTextField(
                    value = amount, onValueChange = { amount = it.filter { c -> c.isDigit() || c == '.' } }, singleLine = true,
                    prefix = { Text("₹") }, label = { Text("Amount (optional)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption(DayFormat.format(day), Modifier.weight(1f))
                    AppButton("Change day", { picking = true }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Caption("Every month on this day", Modifier.weight(1f))
                    Switch(checked = monthly, onCheckedChange = { monthly = it })
                }
            }
        },
        confirmButton = {
            AppButton(
                "Save",
                { onSave(Reminder(reminder?.id ?: UUID.randomUUID().toString(), title.trim(), amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }, day, monthly)) },
                style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = title.isNotBlank()
            )
        },
        dismissButton = {
            if (onRemove != null) AppButton("Remove", onRemove, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            else AppButton("Cancel", onDismiss, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    )
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
