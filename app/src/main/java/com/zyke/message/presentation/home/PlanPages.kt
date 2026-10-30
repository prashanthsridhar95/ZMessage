package com.zyke.message.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.goals.GoalProgress
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.domain.reminders.ReminderDue
import com.zyke.message.presentation.common.AmountField
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FintechCard
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.dashboard.components.ShareBar
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * What is saved against what is wanted, then each goal as a card: a ring for how far along it is, a worded state
 * (never colour alone), and the one next step, adding money. Changes say they happened, and a deletion can be undone.
 */
@Composable
internal fun GoalsContent(
    goals: List<GoalProgress>,
    onSave: (Goal) -> Unit,
    onRemove: (String) -> Unit,
    onAdd: (String, BigDecimal) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var topping by rememberSaveable { mutableStateOf<String?>(null) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    if (goals.isEmpty()) {
        EmptyPlan(
            icon = R.drawable.ic_goal_24,
            title = "Save for something specific",
            body = "A trip, a gadget, a rainy-day fund. Set a target and a date, and see what to put aside each month.",
            actionLabel = "Create your first goal",
            onAction = { adding = true }
        )
    } else {
        val saved = goals.fold(BigDecimal.ZERO) { sum, it -> sum + it.goal.saved.min(it.goal.target) }
        val target = goals.fold(BigDecimal.ZERO) { sum, it -> sum + it.goal.target }
        val overall = if (target.signum() > 0) (saved.toFloat() / target.toFloat()).coerceIn(0f, 1f) else 0f
        val reached = goals.count { it.isReached }
        PageSummary(
            label = "Saved so far",
            value = DisplayFormatters.rupees(saved),
            caption = "of ${DisplayFormatters.rupees(target)} across ${DisplayFormatters.count(goals.size, "goal", "goals")}" +
                if (reached > 0) " · $reached reached" else ""
        ) { ShareBar(overall, Modifier.padding(top = 12.dp)) }

        AppButton("New goal", { adding = true }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)

        val active = goals.filter { !it.isReached }
        val done = goals.filter { it.isReached }
        if (active.isNotEmpty()) {
            SectionHeader("In progress", active.size)
            active.forEach { GoalCard(it, onEdit = { editing = it.goal.id }, onAddMoney = { topping = it.goal.id }) }
        }
        if (done.isNotEmpty()) {
            SectionHeader("Reached", done.size)
            done.forEach { GoalCard(it, onEdit = { editing = it.goal.id }, onAddMoney = null) }
        }
    }

    if (adding) GoalDialog(null, onSave = { onSave(it); adding = false }, onRemove = null, onDismiss = { adding = false })
    goals.firstOrNull { it.goal.id == editing }?.let { progress ->
        GoalDialog(
            progress.goal,
            onSave = { onSave(it); editing = null },
            onRemove = {
                val removed = progress.goal
                onRemove(removed.id)
                editing = null
                scope.launch {
                    val result = snackbar.showSnackbar("Deleted ${removed.name}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) onSave(removed)
                }
            },
            onDismiss = { editing = null }
        )
    }
    goals.firstOrNull { it.goal.id == topping }?.let { progress ->
        var amount by rememberSaveable { mutableStateOf("") }
        val value = amount.toBigDecimalOrNull()
        FormSheet(
            title = "Add to ${progress.goal.name}",
            onDismiss = { topping = null },
            primaryLabel = value?.let { "Add ${DisplayFormatters.rupees(it)}" } ?: "Add",
            primaryEnabled = value != null && value.signum() > 0,
            hint = "Enter how much to add",
            onPrimary = {
                val added = value!!
                onAdd(progress.goal.id, added)
                topping = null
                scope.launch { snackbar.showSnackbar("Added ${DisplayFormatters.rupees(added)} to ${progress.goal.name}") }
            }
        ) {
            AmountField(
                label = "How much", value = amount, onChange = { amount = it },
                supporting = "${DisplayFormatters.rupees(progress.remaining)} to go",
                quickAdds = listOf(500, 1_000, 5_000, 10_000)
            )
        }
    }
}

@Composable
private fun GoalCard(progress: GoalProgress, onEdit: () -> Unit, onAddMoney: (() -> Unit)?) {
    val goal = progress.goal
    val tone = when {
        progress.isReached -> Tone.GOOD
        progress.isLate -> Tone.BAD
        else -> Tone.NEUTRAL
    }
    FintechCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProgressRing(progress.fraction, tone)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(goal.name)
                Text(
                    "${DisplayFormatters.rupees(goal.saved)} of ${DisplayFormatters.rupees(goal.target)}",
                    Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary, maxLines = 1
                )
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    when {
                        progress.isReached -> StatusPill("Reached", Tone.GOOD)
                        progress.isLate -> StatusPill("Past due", Tone.BAD)
                        progress.neededPerMonth != null -> StatusPill("${DisplayFormatters.rupees(progress.neededPerMonth!!)} a month", Tone.NEUTRAL)
                        else -> StatusPill("No date set", Tone.NEUTRAL)
                    }
                    goal.targetDate?.let { Caption("by ${DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()).format(it)}") }
                }
            }
        }
        if (onAddMoney != null) {
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Caption("${DisplayFormatters.rupees(progress.remaining)} to go", Modifier.weight(1f))
                AppButton("Add money", onAddMoney, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
            }
        }
    }
}

/**
 * What is coming up, grouped by how soon (today, this week, later) so the page answers "what do I need to do about
 * it now?" before anything else. Each row shows the date as a badge on the left and the amount on the right.
 */
@Composable
internal fun RemindersContent(
    reminders: List<ReminderDue>,
    today: LocalDate,
    onSave: (Reminder) -> Unit,
    onRemove: (String) -> Unit
) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()

    if (reminders.isEmpty()) {
        EmptyPlan(
            icon = R.drawable.ic_reminder_24,
            title = "Never miss a payment day",
            body = "Rent, a premium, money you owe. Add a date and it is mentioned in your morning summary when it is near.",
            actionLabel = "Add your first reminder",
            onAction = { adding = true }
        )
    } else {
        val next = reminders.first()
        val thisWeek = reminders.count { it.daysFrom(today) in 0..6 }
        PageSummary(
            label = "Next up",
            value = next.reminder.title,
            caption = DisplayFormatters.dueLabel(next.on, today).replaceFirst("Due ", "").replaceFirstChar { it.uppercase() } +
                (next.reminder.amount?.let { " · ${DisplayFormatters.rupees(it)}" } ?: "") +
                " · " + if (thisWeek == 0) "nothing else this week" else "${DisplayFormatters.count(thisWeek, "reminder", "reminders")} this week"
        )

        AppButton("New reminder", { adding = true }, Modifier.fillMaxWidth(), style = ButtonStyle.TONAL)

        val groups = listOf(
            "Today" to reminders.filter { it.daysFrom(today) <= 0 },
            "This week" to reminders.filter { it.daysFrom(today) in 1..6 },
            "Later" to reminders.filter { it.daysFrom(today) >= 7 }
        )
        groups.filter { it.second.isNotEmpty() }.forEach { (title, items) ->
            SectionHeader(title, items.size)
            items.forEach { ReminderCard(it, today) { editing = it.reminder.id } }
        }
    }

    if (adding) ReminderDialog(null, today, onSave = { onSave(it); adding = false }, onRemove = null, onDismiss = { adding = false }, known = reminders.map { it.reminder.title })
    reminders.firstOrNull { it.reminder.id == editing }?.let { due ->
        ReminderDialog(
            due.reminder, today,
            onSave = { onSave(it); editing = null },
            onRemove = {
                val removed = due.reminder
                onRemove(removed.id)
                editing = null
                scope.launch {
                    val result = snackbar.showSnackbar("Deleted ${removed.title}", actionLabel = "Undo", duration = SnackbarDuration.Long)
                    if (result == SnackbarResult.ActionPerformed) onSave(removed)
                }
            },
            onDismiss = { editing = null },
            known = reminders.map { it.reminder.title }
        )
    }
}

@Composable
private fun ReminderCard(due: ReminderDue, today: LocalDate, onEdit: () -> Unit) {
    val days = due.daysFrom(today)
    val tone = when {
        days <= 0 -> Tone.BAD
        days <= 2 -> Tone.WARN
        else -> Tone.NEUTRAL
    }
    FintechCard(onClick = onEdit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            DateBadge(due.on, toneColor(tone))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(due.reminder.title)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    StatusPill(
                        when {
                            days < 0 -> "Overdue"
                            days == 0L -> "Today"
                            days == 1L -> "Tomorrow"
                            else -> "In $days days"
                        },
                        tone
                    )
                    due.reminder.time?.let { Caption(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(it)) }
                    if (due.reminder.repeatsMonthly) Caption("every month")
                }
            }
            due.reminder.amount?.let {
                Spacer(Modifier.width(12.dp))
                Text(DisplayFormatters.rupees(it), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
            }
        }
    }
}

/** A small calendar leaf: the month above and the day number beneath, tinted by how urgent it is. */
@Composable
internal fun DateBadge(date: LocalDate, tint: androidx.compose.ui.graphics.Color) {
    Column(
        Modifier.size(52.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(14.dp)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            DateTimeFormatter.ofPattern("MMM", Locale.getDefault()).format(date).uppercase(),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = tint, textAlign = TextAlign.Center
        )
        Text(date.dayOfMonth.toString(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
    }
}
