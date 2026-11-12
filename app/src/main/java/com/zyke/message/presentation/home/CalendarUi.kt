package com.zyke.message.presentation.home

import androidx.compose.runtime.ReadOnlyComposable
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.theme.AppColors
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
import androidx.compose.foundation.layout.navigationBarsPadding
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
import com.zyke.message.presentation.common.Text
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import java.util.UUID
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.unit.sp
import com.zyke.message.domain.calendar.CalendarPaging
import com.zyke.message.domain.calendar.PageDirection
import com.zyke.message.presentation.common.EmptyState
import com.zyke.message.presentation.common.LocalResponsive
import com.zyke.message.presentation.messages.components.RowAmount
import com.zyke.message.presentation.motion.AnimatedColumnItems
import com.zyke.message.presentation.motion.CrossfadeKeyed
import com.zyke.message.presentation.motion.Motion
import com.zyke.message.presentation.motion.SlideSwap
import com.zyke.message.presentation.motion.pressFeedback
import com.zyke.message.presentation.motion.rememberHaptics
import com.zyke.message.presentation.motion.rememberMotion
import com.zyke.message.presentation.theme.AppShapes
import com.zyke.message.presentation.theme.Radius
import com.zyke.message.presentation.theme.Spacing

@Composable
@ReadOnlyComposable
private fun kindColor(kind: CalendarKind): Color = when (kind) {
    CalendarKind.EVENT -> AppColors.info
    CalendarKind.REMINDER -> AppColors.warning
    CalendarKind.BILL -> AppColors.danger
    CalendarKind.DEBIT -> Color(0xFFF08A3C)
    CalendarKind.REGULAR -> Color(0xFF9B6BE0)
    CalendarKind.SALARY -> AppColors.success
    CalendarKind.LIFE -> Color(0xFF2FB8C8)
    CalendarKind.WARRANTY -> Color(0xFFC2689A)
    CalendarKind.GOAL -> Color(0xFF7AA33A)
    CalendarKind.MATURITY -> MaterialTheme.colorScheme.tertiary
}

/**
 * The app's own calendar: a month at a time, a dot under each day for what is on it, and the day's list below. It
 * brings together the person's events and reminders, the card bills and auto-debits from their messages, orders and
 * appointments, regular payments, the salary day and goal dates.
 *
 * Paging: the arrows, a sideways swipe on the grid and the Today button all turn the page the same way. The grid slides at
 * most 24 dp (30 % of its width on a very small one) in the direction of time, fading, while the title crossfades, with one
 * light tick; under reduced motion or Lite the page just changes. The swipe is a horizontal drag only, so scrolling the
 * page is not affected.
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
    val snackbar = com.zyke.message.presentation.common.LocalAppSnackbar.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val haptics = rememberHaptics()

    val container = (androidx.compose.ui.platform.LocalContext.current.applicationContext as com.zyke.message.ZykeMessagesApplication).container
    val holdings by container.investments.state.collectAsState()
    // A month is built once per set of inputs; the page that is leaving keeps drawing the month it was made for.
    val monthCache = remember(messages, reminders, events, goals, plan, now, holdings) { HashMap<YearMonth, Map<LocalDate, List<CalendarEntry>>>() }
    val entriesFor: (YearMonth) -> Map<LocalDate, List<CalendarEntry>> = { m ->
        monthCache.getOrPut(m) { CalendarBuilder.month(m, messages, reminders, events, goals, plan, now, zone, holdings.holdings) }
    }
    val onThisMonth = month == YearMonth.from(today)
    val goTo: (YearMonth) -> Unit = { target ->
        if (target != month) {
            haptics.tick()
            monthText = target.toString()
        }
    }
    val widthDp = LocalResponsive.current.widthDp.toFloat()
    val thresholdPx = with(LocalDensity.current) { CalendarPaging.swipeThreshold(widthDp).dp.toPx() }

    AppPage(
        "Calendar", onDismiss,
        floating = {
            androidx.compose.material3.ExtendedFloatingActionButton(
                onClick = { adding = true },
                modifier = Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(Spacing.lg),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) { Text("+ Event", fontWeight = FontWeight.SemiBold) }
        }
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            // Where am I, and how do I move: the month with a large arrow either side, Today when away from it.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                MonthArrow("\u2039", "Previous month") { goTo(month.minusMonths(1)) }
                CrossfadeKeyed(month, contentKey = { it }, modifier = Modifier.weight(1f), label = "month-title") { shown ->
                    val shownEntries = entriesFor(shown).values.flatten()
                    val unsettled = shownEntries.filter { (it.kind == CalendarKind.BILL || it.kind == CalendarKind.DEBIT) && !it.done && it.amount != null }
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            FormatCache.pattern("MMMM yyyy", Locale.getDefault()).format(shown.atDay(1)),
                            Modifier.heading(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center
                        )
                        Caption(
                            when {
                                shownEntries.isEmpty() -> "Nothing planned"
                                unsettled.isEmpty() -> DisplayFormatters.count(shownEntries.size, "thing", "things") + " this month"
                                else -> DisplayFormatters.count(unsettled.size, "bill or debit", "bills and debits") + " still to pay \u00B7 " +
                                    DisplayFormatters.rupees(unsettled.fold(java.math.BigDecimal.ZERO) { sum, it -> sum + it.amount!! })
                            },
                            textAlign = TextAlign.Center, maxLines = 2
                        )
                    }
                }
                MonthArrow("\u203A", "Next month") { goTo(month.plusMonths(1)) }
            }
            FintechCard(
                Modifier.pointerInput(month, thresholdPx) {
                    // Swiping the month sideways moves through time, the way the arrows suggest.
                    var travelled = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { travelled = 0f },
                        onDragEnd = {
                            val way = CalendarPaging.swipe(travelled, thresholdPx)
                            if (way != PageDirection.NONE) goTo(CalendarPaging.shifted(month, way))
                            travelled = 0f
                        },
                        onDragCancel = { travelled = 0f }
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
                SlideSwap(
                    targetState = month,
                    sign = { from, to -> CalendarPaging.enterSign(CalendarPaging.direction(from, to)) },
                    maxSlideDp = CalendarPaging.SLIDE_MAX_DP, maxShare = CalendarPaging.SLIDE_MAX_SHARE,
                    label = "month-grid"
                ) { shown ->
                    MonthGrid(shown, entriesFor(shown), today, selected) { selectedText = it.toString() }
                }
            }

            // The chosen day: what it is, how busy, and the one action for it.
            DayHeader(selected, today, entriesFor(YearMonth.from(selected))[selected].orEmpty().size, showToday = !onThisMonth || selected != today) {
                goTo(YearMonth.from(today))
                selectedText = today.toString()
            }
            CrossfadeKeyed(selected, contentKey = { it }, modifier = Modifier.fillMaxWidth(), label = "day-detail") { day ->
                val dayEntries = entriesFor(YearMonth.from(day))[day].orEmpty()
                if (dayEntries.isEmpty()) {
                    EmptyState("A free day", "Add an appointment or a plan, and it shows here and on the dot above.", actionLabel = "Add an event", onAction = { adding = true })
                } else {
                    val keyed = remember(dayEntries) { keyedEntries(dayEntries) }
                    AnimatedColumnItems(keyed, itemKey = { it.first }) { (_, entry), _ ->
                        Box(Modifier.padding(bottom = Spacing.md)) {
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
            Spacer(Modifier.height(72.dp))
        }
    }

    if (adding) {
        EventForm(initial = null, startDate = selected, onSave = { onSaveEvent(it); adding = false; selectedText = it.date.toString(); monthText = YearMonth.from(it.date).toString() }, onRemove = null, onDismiss = { adding = false })
    }
    editing?.let { event ->
        EventForm(
            initial = event, startDate = event.date,
            onSave = { onSaveEvent(it); editing = null; selectedText = it.date.toString(); monthText = YearMonth.from(it.date).toString() },
            onRemove = {
                onRemoveEvent(event.id)
                editing = null
                scope.launch {
                    val result = snackbar.showSnackbar("Deleted ${event.title}", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Long)
                    if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) onSaveEvent(event)
                }
            },
            onDismiss = { editing = null }
        )
    }
}

/** Entries with a key each, unique even when two are alike, so they can open and close one by one. */
private fun keyedEntries(entries: List<CalendarEntry>): List<Pair<String, CalendarEntry>> {
    val seen = HashMap<String, Int>()
    return entries.map { entry ->
        val base = "${entry.kind}|${entry.title}|${entry.eventId ?: entry.messageId ?: ""}|${entry.time ?: ""}"
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        "$base#$n" to entry
    }
}

/** The name of the chosen day with how much is on it, and Today when it is not the day shown. */
@Composable
private fun DayHeader(selected: LocalDate, today: LocalDate, count: Int, showToday: Boolean, onToday: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = Spacing.xs), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    FormatCache.pattern("EEEE d MMMM", Locale.getDefault()).format(selected),
                    Modifier.heading(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.primary
                )
                if (selected == today) StatusPill("Today", Tone.NEUTRAL, Modifier.padding(start = Spacing.sm))
            }
            Caption(if (count == 0) "Nothing on this day" else DisplayFormatters.count(count, "thing", "things"))
        }
        // Beside the day it leaves, not above the list, so showing or hiding it moves nothing below.
        if (showToday) {
            AppButton("Today", onToday, Modifier.padding(end = Spacing.xs), style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
        }
    }
}

/** One month as weeks of day cells, with the key to the colours that appear in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MonthGrid(
    month: YearMonth,
    entries: Map<LocalDate, List<CalendarEntry>>,
    today: LocalDate,
    selected: LocalDate,
    onSelect: (LocalDate) -> Unit
) {
    val leading = (month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val cells = leading + month.lengthOfMonth()
    Column(Modifier.fillMaxWidth()) {
        (0 until (cells + 6) / 7).forEach { week ->
            Row(Modifier.fillMaxWidth().padding(top = Spacing.xs)) {
                (0..6).forEach { column ->
                    val dayNumber = week * 7 + column - leading + 1
                    Box(Modifier.weight(1f).heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
                        if (dayNumber in 1..month.lengthOfMonth()) {
                            val date = month.atDay(dayNumber)
                            DayCell(date, today, entries[date].orEmpty(), isSelected = date == selected) { onSelect(date) }
                        }
                    }
                }
            }
        }
        // Only the colours that appear this month, each with its name.
        val present = entries.values.flatten().map { it.kind }.distinct()
        if (present.isNotEmpty()) {
            FlowRow(
                Modifier.fillMaxWidth().padding(top = Spacing.lg),
                horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                present.forEach { kind ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(Spacing.sm).background(kindColor(kind), CircleShape))
                        Spacer(Modifier.width(Spacing.sm))
                        Caption(kind.label)
                    }
                }
            }
        }
    }
}

@Composable
private fun MonthArrow(glyph: String, description: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).pressFeedback(source).clip(CircleShape)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(glyph, Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * One day: its number, up to three dots for the kinds of thing on it (and a "+" when there are more), a ring when it is
 * today, and a fill that fades in when it is chosen. A screen reader hears "Tuesday 12 November, today, 2 bills due" and a
 * button that selects the day.
 */
@Composable
private fun DayCell(date: LocalDate, today: LocalDate, entries: List<CalendarEntry>, isSelected: Boolean, onClick: () -> Unit) {
    val motion = rememberMotion()
    val ink = MaterialTheme.colorScheme.primary
    val onInk = MaterialTheme.colorScheme.onPrimary
    val isToday = date == today
    val source = remember { MutableInteractionSource() }
    val fill = animateColorAsState(if (isSelected) ink else Color.Transparent, motion.tween(Motion.QUICK_MS), label = "day-fill")
    val numberColor = animateColorAsState(if (isSelected) onInk else ink, motion.tween(Motion.QUICK_MS), label = "day-number")
    val spoken = remember(date, today, entries) { CalendarPaging.spoken(date, today, entries) }
    val kinds = remember(entries) { entries.map { it.kind }.distinct() }
    val dots = CalendarPaging.dots(kinds.size)
    val corner = with(LocalDensity.current) { Radius.chip.toPx() }
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .pressFeedback(source)
            .clip(AppShapes.chip)
            .drawBehind { drawRoundRect(fill.value, cornerRadius = CornerRadius(corner)) }
            .then(if (isToday && !isSelected) Modifier.border(1.5.dp, ink, AppShapes.chip) else Modifier)
            .clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClickLabel = "Show this day", onClick = onClick)
            .semantics {
                contentDescription = spoken
                selected = isSelected
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            date.dayOfMonth.toString(),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (isToday || isSelected) FontWeight.Bold else FontWeight.Normal,
            color = numberColor.value
        )
        Row(Modifier.padding(top = Spacing.xs).heightIn(min = Spacing.sm), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
            kinds.take(dots.shown).forEach { kind ->
                Box(Modifier.size(6.dp).background(kindColor(kind), CircleShape).then(if (isSelected) Modifier.border(1.dp, onInk, CircleShape) else Modifier))
            }
            if (dots.more) {
                Text(
                    "+",
                    Modifier.clearAndSetSemantics { },
                    style = ComposeTextStyle(fontSize = 9.sp, lineHeight = 9.sp, fontWeight = FontWeight.Bold),
                    color = numberColor.value, maxLines = 1
                )
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
            Box(Modifier.width(Spacing.xs).height(44.dp).background(color, RoundedCornerShape(2.dp)))
            Spacer(Modifier.width(Spacing.md))
            Column(Modifier.weight(1f)) {
                RowTitle(entry.title)
                Row(Modifier.padding(top = Spacing.xs), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        entry.kind.label,
                        Modifier.background(color.copy(alpha = 0.14f), AppShapes.chip).padding(horizontal = Spacing.sm, vertical = Spacing.xs),
                        style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1
                    )
                    entry.time?.let { Caption(FormatCache.pattern("h:mm a", Locale.getDefault()).format(it)) }
                }
                if (entry.detail.isNotBlank()) Caption(entry.detail, Modifier.padding(top = Spacing.xs), maxLines = 2)
            }
            Column(horizontalAlignment = Alignment.End) {
                entry.amount?.let { RowAmount(DisplayFormatters.rupees(it), MaterialTheme.colorScheme.primary) }
                if (entry.done) StatusPill("Done", Tone.GOOD, Modifier.padding(top = Spacing.sm))
            }
        }
    }
}

/** Add or change one of the person's own events: a title, the day, an optional time and a note. */
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
    val date = LocalDate.parse(dateText)
    val time = timeText.takeIf { it.isNotBlank() }?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

    var confirming by remember { mutableStateOf(false) }
    FormSheet(
        title = if (initial == null) "Add an event" else "Edit event",
        onDismiss = onDismiss,
        asPage = true,
        primaryLabel = "Save",
        primaryEnabled = title.isNotBlank(),
        hint = "Name the event",
        onPrimary = { onSave(CalendarEvent(initial?.id ?: UUID.randomUUID().toString(), title.trim(), date, time, note.trim())) },
        secondaryLabel = if (onRemove != null) "Remove" else null,
        onSecondary = if (onRemove != null) ({ confirming = true }) else null
    ) {
        FormField(
            label = "What is it?", value = title, onChange = { title = it }, placeholder = "e.g. Dentist, Dinner with Ravi",
            suggestions = PlanSuggestions.events, capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Sentences
        )
        com.zyke.message.presentation.common.DateField("Day", date, { dateText = (it ?: date).toString() })
        com.zyke.message.presentation.common.TimeField(
            "Time (optional)", time, { timeText = it?.toString().orEmpty() },
            placeholder = "All day",
            presets = listOf("9:00 AM" to LocalTime.of(9, 0), "12:00 PM" to LocalTime.of(12, 0), "6:00 PM" to LocalTime.of(18, 0))
        )
        FormField(label = "Note (optional)", value = note, onChange = { note = it }, placeholder = "Where, who with, what to bring")
    }
    if (confirming && onRemove != null) {
        ConfirmDelete(
            "Remove this event?", "It leaves your calendar. You can still undo right after.", "Remove",
            onConfirm = { confirming = false; onRemove() }, onDismiss = { confirming = false }
        )
    }
}
