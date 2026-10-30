package com.zyke.message.presentation.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import com.zyke.message.presentation.common.FintechCard
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zyke.message.domain.calendar.CalendarBuilder
import com.zyke.message.domain.calendar.CalendarEntry
import com.zyke.message.domain.calendar.CalendarEvent
import com.zyke.message.domain.calendar.CalendarKind
import com.zyke.message.domain.goals.Goal
import com.zyke.message.domain.model.ClassifiedMessage
import com.zyke.message.domain.plan.MoneyPlan
import com.zyke.message.domain.reminders.Reminder
import com.zyke.message.presentation.common.AppPage
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.fullWidthClickable
import com.zyke.message.presentation.common.heading
import com.zyke.message.presentation.format.DisplayFormatters
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID

private fun kindColor(kind: CalendarKind): Color = when (kind) {
    CalendarKind.EVENT -> Color(0xFF4C8DF6)
    CalendarKind.REMINDER -> Color(0xFFE0A526)
    CalendarKind.BILL -> Color(0xFFE8505B)
    CalendarKind.DEBIT -> Color(0xFFF08A3C)
    CalendarKind.REGULAR -> Color(0xFF9B6BE0)
    CalendarKind.SALARY -> Color(0xFF34A853)
    CalendarKind.LIFE -> Color(0xFF2FB8C8)
    CalendarKind.GOAL -> Color(0xFF7AA33A)
}

/**
 * The app's own calendar: a month at a time, a dot under each day for what is on it, and the day's list below. It
 * brings together the person's events and reminders, the card bills and auto-debits from their messages, orders and
 * appointments, regular payments, the salary day and goal dates.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CalendarPage(
    messages: List<ClassifiedMessage>,
    reminders: List<Reminder>,
    events: List<CalendarEvent>,
    goals: List<Goal>,
    plan: MoneyPlan,
    now: Instant,
    onSaveEvent: (CalendarEvent) -> Unit,
    onRemoveEvent: (String) -> Unit,
    onOpenMessage: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val zone = ZoneId.systemDefault()
    val today = remember(now) { LocalDate.ofInstant(now, zone) }
    var monthText by rememberSaveable { mutableStateOf(YearMonth.from(today).toString()) }
    var selectedText by rememberSaveable { mutableStateOf(today.toString()) }
    val month = remember(monthText) { YearMonth.parse(monthText) }
    val selected = remember(selectedText) { LocalDate.parse(selectedText) }
    var editing by remember { mutableStateOf<CalendarEvent?>(null) }
    var adding by rememberSaveable { mutableStateOf(false) }

    val entries = remember(month, messages, reminders, events, goals, plan, now) {
        CalendarBuilder.month(month, messages, reminders, events, goals, plan, now, zone)
    }
    val leading = (month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7

    val monthName = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.getDefault()).format(month.atDay(1))
    val monthEntries = entries.values.flatten()
    val unsettled = monthEntries.filter { (it.kind == CalendarKind.BILL || it.kind == CalendarKind.DEBIT) && !it.done && it.amount != null }
    val dayEntries = entries[selected].orEmpty()
    val onThisMonth = month == YearMonth.from(today)

    AppPage("Calendar", onDismiss) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            // Where am I, and how do I move: the month with a large arrow either side, Today when away from it.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonthArrow("‹", "Previous month") { monthText = month.minusMonths(1).toString() }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(monthName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Caption(
                        when {
                            monthEntries.isEmpty() -> "Nothing planned"
                            unsettled.isEmpty() -> DisplayFormatters.count(monthEntries.size, "thing", "things") + " this month"
                            else -> DisplayFormatters.count(unsettled.size, "bill or debit", "bills and debits") + " still to pay · " +
                                DisplayFormatters.rupees(unsettled.fold(java.math.BigDecimal.ZERO) { sum, it -> sum + it.amount!! })
                        },
                        textAlign = TextAlign.Center, maxLines = 2
                    )
                }
                MonthArrow("›", "Next month") { monthText = month.plusMonths(1).toString() }
            }
            if (!onThisMonth || selected != today) {
                AppButton(
                    "Back to today", { monthText = YearMonth.from(today).toString(); selectedText = today.toString() },
                    Modifier.align(Alignment.CenterHorizontally), style = ButtonStyle.TONAL, size = ButtonSize.COMPACT
                )
            }

            FintechCard(
                Modifier.pointerInput(month) {
                    // Swiping the month sideways moves through time, the way the arrows suggest.
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onDragEnd = {
                            if (travelled < -120f) monthText = month.plusMonths(1).toString()
                            else if (travelled > 120f) monthText = month.minusMonths(1).toString()
                        }
                    ) { _, delta -> travelled += delta }
                }
            ) {
                Row(Modifier.fillMaxWidth()) {
                    (0..6).forEach { offset ->
                        Caption(
                            DayOfWeek.MONDAY.plus(offset.toLong()).getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(2).uppercase(),
                            Modifier.weight(1f), textAlign = TextAlign.Center
                        )
                    }
                }
                val cells = leading + month.lengthOfMonth()
                (0 until (cells + 6) / 7).forEach { week ->
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                        (0..6).forEach { column ->
                            val dayNumber = week * 7 + column - leading + 1
                            Box(Modifier.weight(1f).height(52.dp), contentAlignment = Alignment.Center) {
                                if (dayNumber in 1..month.lengthOfMonth()) {
                                    val date = month.atDay(dayNumber)
                                    DayCell(date, entries[date].orEmpty(), isToday = date == today, isSelected = date == selected) { selectedText = date.toString() }
                                }
                            }
                        }
                    }
                }
                // Only the colours that appear this month, each with its name.
                val present = monthEntries.map { it.kind }.distinct()
                if (present.isNotEmpty()) {
                    FlowRow(Modifier.fillMaxWidth().padding(top = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        present.forEach { kind ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(kindColor(kind), CircleShape))
                                Spacer(Modifier.width(6.dp))
                                Caption(kind.label)
                            }
                        }
                    }
                }
            }

            // The chosen day: what it is, how busy, and the one action for it.
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.getDefault()).format(selected),
                            Modifier.heading(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                        )
                        if (selected == today) StatusPill("Today", Tone.NEUTRAL, Modifier.padding(start = 8.dp))
                    }
                    Caption(if (dayEntries.isEmpty()) "Nothing on this day" else DisplayFormatters.count(dayEntries.size, "thing", "things"))
                }
                AppButton("Add event", { adding = true }, style = ButtonStyle.TONAL, size = ButtonSize.COMPACT)
            }

            if (dayEntries.isEmpty()) {
                FintechCard {
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("A free day", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary)
                        Caption("Add an appointment or a plan, and it shows here and on the dot above.", Modifier.padding(top = 4.dp), maxLines = 3, textAlign = TextAlign.Center)
                    }
                }
            } else {
                dayEntries.forEach { entry ->
                    EntryRow(entry) {
                        when {
                            entry.eventId != null -> editing = events.firstOrNull { it.id == entry.eventId }
                            entry.messageId != null -> onOpenMessage(entry.messageId!!)
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        EventForm(initial = null, startDate = selected, onSave = { onSaveEvent(it); adding = false; selectedText = it.date.toString(); monthText = YearMonth.from(it.date).toString() }, onRemove = null, onDismiss = { adding = false })
    }
    editing?.let { event ->
        EventForm(
            initial = event, startDate = event.date,
            onSave = { onSaveEvent(it); editing = null; selectedText = it.date.toString(); monthText = YearMonth.from(it.date).toString() },
            onRemove = { onRemoveEvent(event.id); editing = null },
            onDismiss = { editing = null }
        )
    }
}

@Composable
private fun MonthArrow(glyph: String, description: String, onClick: () -> Unit) {
    Box(
        Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun DayCell(date: LocalDate, entries: List<CalendarEntry>, isToday: Boolean, isSelected: Boolean, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(shape)
            .background(if (isSelected) ink else Color.Transparent)
            .then(if (isToday && !isSelected) Modifier.border(1.5.dp, ink, shape) else Modifier)
            .clickable(role = Role.Button, onClickLabel = "Show ${date.dayOfMonth}", onClick = onClick)
            .semantics {
                contentDescription = "${date.dayOfMonth}" + (if (isToday) ", today" else "") +
                    if (entries.isEmpty()) "" else ", ${DisplayFormatters.count(entries.size, "thing", "things")}"
                selected = isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            color = if (isSelected) MaterialTheme.colorScheme.onPrimary else ink
        )
        Row(Modifier.padding(top = 3.dp).height(6.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            entries.map { it.kind }.distinct().take(3).forEach { kind ->
                Box(Modifier.size(6.dp).background(kindColor(kind), CircleShape).then(if (isSelected) Modifier.border(1.dp, MaterialTheme.colorScheme.onPrimary, CircleShape) else Modifier))
            }
        }
    }
}

/** One thing on the day: a stripe in its colour, its name and time, a word for its kind, the amount, and whether it is settled. */
@Composable
private fun EntryRow(entry: CalendarEntry, onClick: () -> Unit) {
    val clickable = entry.eventId != null || entry.messageId != null
    val color = kindColor(entry.kind)
    FintechCard(onClick = if (clickable) onClick else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(4.dp).height(44.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                RowTitle(entry.title)
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.kind.label,
                        Modifier.background(color.copy(alpha = 0.14f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1
                    )
                    entry.time?.let { Caption(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(it)) }
                }
                if (entry.detail.isNotBlank()) Caption(entry.detail, Modifier.padding(top = 4.dp), maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End) {
                entry.amount?.let {
                    Text(DisplayFormatters.rupees(it), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                }
                if (entry.done) StatusPill("Done", Tone.GOOD, Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Add or change one of the person's own events: a title, the day, an optional time and a note. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EventForm(
    initial: CalendarEvent?,
    startDate: LocalDate,
    onSave: (CalendarEvent) -> Unit,
    onRemove: (() -> Unit)?,
    onDismiss: () -> Unit
) {
    var title by rememberSaveable { mutableStateOf(initial?.title.orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf(startDate.toString()) }
    var timeText by rememberSaveable { mutableStateOf(initial?.time?.toString().orEmpty()) }
    var note by rememberSaveable { mutableStateOf(initial?.note.orEmpty()) }
    var pickingDate by remember { mutableStateOf(false) }
    val date = LocalDate.parse(dateText)
    val time = timeText.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
    val timeValid = timeText.isBlank() || time != null

    FormSheet(
        title = if (initial == null) "Add an event" else "Edit event",
        onDismiss = onDismiss,
        primaryLabel = "Save",
        primaryEnabled = title.isNotBlank() && timeValid,
        onPrimary = { onSave(CalendarEvent(initial?.id ?: UUID.randomUUID().toString(), title.trim(), date, time, note.trim())) },
        secondaryLabel = if (onRemove != null) "Remove" else null,
        onSecondary = onRemove
    ) {
        FormField(label = "What is it?", value = title, onChange = { title = it }, placeholder = "e.g. Dentist, Dinner with Ravi")

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Day", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
            AppButton(
                DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.getDefault()).format(date), { pickingDate = true },
                Modifier.fillMaxWidth(), style = ButtonStyle.OUTLINE
            )
        }

        FormField(
            label = "Time (optional)",
            value = timeText,
            onChange = { timeText = it.filter { c -> c.isDigit() || c == ':' }.take(5) },
            placeholder = "e.g. 18:30",
            supporting = if (timeValid) "24-hour, like 09:00 or 18:30" else "Use hours and minutes, like 18:30",
            keyboardType = KeyboardType.Number
        )
        FormField(label = "Note (optional)", value = note, onChange = { note = it }, placeholder = "Where, who with, what to bring")
    }

    if (pickingDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingDate = false },
            confirmButton = {
                AppButton(
                    "Done",
                    {
                        state.selectedDateMillis?.let { dateText = Instant.ofEpochMilli(it).atZone(java.time.ZoneOffset.UTC).toLocalDate().toString() }
                        pickingDate = false
                    },
                    style = ButtonStyle.TEXT, size = ButtonSize.COMPACT
                )
            },
            dismissButton = { AppButton("Cancel", { pickingDate = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        ) { DatePicker(state = state) }
    }
}
