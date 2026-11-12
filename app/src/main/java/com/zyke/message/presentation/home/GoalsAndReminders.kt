package com.zyke.message.presentation.home

import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.domain.goals.Goals
import com.zyke.message.presentation.format.FormatCache
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
import com.zyke.message.presentation.common.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.zyke.message.presentation.theme.IconSize
import com.zyke.message.presentation.theme.Spacing
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import com.zyke.message.presentation.common.DateField
import com.zyke.message.presentation.common.TimeField
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
import java.util.Locale
import java.util.UUID

private val DayFormat = FormatCache.pattern("d MMM yyyy", Locale.getDefault())

@Composable
internal fun GoalDialog(goal: Goal?, onSave: (Goal) -> Unit, onRemove: (() -> Unit)?, onDismiss: () -> Unit) {
    val today = LocalDate.now()
    var name by rememberSaveable { mutableStateOf(goal?.name.orEmpty()) }
    var target by rememberSaveable { mutableStateOf(goal?.target?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var saved by rememberSaveable { mutableStateOf(goal?.saved?.takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf(goal?.targetDate?.toString().orEmpty()) }
    val amount = target.toBigDecimalOrNull()
    val date = dateText.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val presets = listOf("3 months" to today.plusMonths(3), "6 months" to today.plusMonths(6), "1 year" to today.plusYears(1))
    val preview = if (amount != null && amount.signum() > 0) {
        Goals.progress(Goal("preview", name, amount, saved.toBigDecimalOrNull() ?: BigDecimal.ZERO, date), today).neededPerMonth
    } else {
        null
    }

    var confirming by remember { mutableStateOf(false) }
    FormSheet(
        title = if (goal == null) "New goal" else "Edit goal",
        onDismiss = onDismiss,
        primaryLabel = if (goal == null) "Create goal" else "Save changes",
        primaryEnabled = name.isNotBlank() && amount != null && amount.signum() > 0,
        hint = if (name.isBlank()) "Give the goal a name" else "Enter the amount you are aiming for",
        onPrimary = { onSave(Goal(goal?.id ?: UUID.randomUUID().toString(), name.trim(), amount!!, saved.toBigDecimalOrNull() ?: BigDecimal.ZERO, date)) },
        secondaryLabel = if (onRemove != null) "Delete goal" else null,
        onSecondary = if (onRemove != null) ({ confirming = true }) else null
    ) {
        FormField(
            "Goal", name, { name = it }, placeholder = "Goa trip, new laptop, rainy-day fund",
            suggestions = PlanSuggestions.goals, capitalization = KeyboardCapitalization.Sentences
        )
        AmountField("Target amount", target, { target = it }, quickAdds = listOf(10_000, 50_000, 100_000))
        DateField(
            "Target date (optional)", date, { dateText = it?.toString().orEmpty() },
            placeholder = "No date", clearable = true, presets = presets,
            supporting = preview?.let { "${DisplayFormatters.rupees(it)} a month gets you there" }
        )
        if (goal == null) AmountField("Already saved (optional)", saved, { saved = it })
    }
    if (confirming && onRemove != null) {
        ConfirmDelete(
            "Delete this goal?", "The goal and its saving history go. You can still undo right after.", "Delete goal",
            onConfirm = { confirming = false; onRemove() }, onDismiss = { confirming = false }
        )
    }
}

@Composable
internal fun ReminderDialog(
    reminder: Reminder?,
    today: LocalDate,
    onSave: (Reminder) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit,
    /** Titles already used, offered along with the usual ones. */
    known: List<String> = emptyList()
) {
    var title by rememberSaveable { mutableStateOf(reminder?.title.orEmpty()) }
    var amount by rememberSaveable { mutableStateOf(reminder?.amount?.stripTrailingZeros()?.toPlainString().orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf((reminder?.date ?: today).toString()) }
    var monthly by rememberSaveable { mutableStateOf(reminder?.repeatsMonthly ?: false) }
    var timeText by rememberSaveable { mutableStateOf(reminder?.time?.toString().orEmpty()) }
    val time = timeText.takeIf { it.isNotEmpty() }?.let { runCatching { java.time.LocalTime.parse(it) }.getOrNull() }
    val day = runCatching { LocalDate.parse(dateText) }.getOrDefault(today)
    val datePresets = listOf("Today" to today, "Tomorrow" to today.plusDays(1), "In a week" to today.plusWeeks(1))
    val timePresets = listOf("9:00 AM" to java.time.LocalTime.of(9, 0), "12:00 PM" to java.time.LocalTime.of(12, 0), "6:00 PM" to java.time.LocalTime.of(18, 0))

    var confirming by remember { mutableStateOf(false) }
    FormSheet(
        title = if (reminder == null) "New reminder" else "Edit reminder",
        onDismiss = onDismiss,
        primaryLabel = if (reminder == null) "Add reminder" else "Save changes",
        primaryEnabled = title.isNotBlank(),
        hint = "Give the reminder a name",
        onPrimary = { onSave(Reminder(reminder?.id ?: UUID.randomUUID().toString(), title.trim(), amount.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }, day, monthly, time)) },
        secondaryLabel = if (onRemove != null) "Delete reminder" else null,
        onSecondary = if (onRemove != null) ({ confirming = true }) else null
    ) {
        FormField(
            "Reminder", title, { title = it }, placeholder = "Rent, insurance premium, pay Ravi",
            suggestions = (known + PlanSuggestions.reminders).distinct(), capitalization = KeyboardCapitalization.Sentences
        )
        AmountField("Amount (optional)", amount, { amount = it }, quickAdds = listOf(500, 1_000, 5_000))
        DateField("Date", day, { dateText = (it ?: today).toString() }, presets = datePresets)
        TimeField(
            "Notify me at (optional)", time, { timeText = it?.toString().orEmpty() },
            placeholder = "Morning summary only", presets = timePresets,
            supporting = if (time == null) "It is mentioned in the morning summary on the day"
            else "A notification at ${FormatCache.pattern("h:mm a", Locale.getDefault()).format(time)} on the day"
        )
        FormGroup("Repeat") {
            // One labelled switch: TalkBack reads "Every month, on day 5, switch, on" and the whole row toggles.
            com.zyke.message.presentation.settings.ToggleRow(
                title = "Every month", description = "on day ${day.dayOfMonth}", checked = monthly, onCheckedChange = { monthly = it },
                modifier = Modifier.heightIn(min = 48.dp)
            )
        }
    }
    if (confirming && onRemove != null) {
        ConfirmDelete(
            "Delete this reminder?", "It stops reminding you. You can still undo right after.", "Delete reminder",
            onConfirm = { confirming = false; onRemove() }, onDismiss = { confirming = false }
        )
    }
}

/** One line on Home for something planned ahead; it opens its own sheet. */
@Composable
internal fun PlanTile(title: String, summary: String, onOpen: () -> Unit) {
    FintechCard(onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            RowTitle(title, Modifier.weight(1f))
            Caption(summary)
            Spacer(Modifier.width(Spacing.sm))
            Text("\u203A", color = mutedColor())
        }
    }
}

/** A wide button with an icon and its name beside it, for a page of its own. Two sit side by side on a phone. */
@Composable
internal fun ShortcutTile(label: String, icon: Int, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    FintechCard(modifier, onClick = onOpen) {
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.Image(
                painter = androidx.compose.ui.res.painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(IconSize.tile),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
            )
            Text(
                label,
                Modifier.padding(start = Spacing.md),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.secondary,
                maxLines = 2
            )
        }
    }
}

/** The page a plan shortcut opens: its card, with room round it. */
@Composable
internal fun PlanPage(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    com.zyke.message.presentation.common.AppPage(title, onBack) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) { content() }
    }
}

/** The sheet a plan tile opens: its card, with room round it. */
@Composable
internal fun PlanSheet(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    AdaptiveSheet(title, onDismiss) {
        Column(
            Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Spacing.md)
        ) {
            content()
        }
    }
}
