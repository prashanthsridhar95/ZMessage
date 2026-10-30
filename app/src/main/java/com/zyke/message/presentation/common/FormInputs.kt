package com.zyke.message.presentation.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.presentation.messages.components.Caption
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Inputs shaped by what goes into them: a date is picked from a calendar, a time from a clock, one of a known set from
 * a list, a day of the month from the thirty-one. Nothing that has a right form is typed free.
 */

/** The look all of them share: the filled field box, with the value (or a hint) and what sits at its end. */
@Composable
private fun FieldBox(
    label: String,
    modifier: Modifier,
    supporting: String?,
    onOpen: () -> Unit,
    openLabel: String,
    leading: (@Composable () -> Unit)?,
    trailing: @Composable () -> Unit,
    value: @Composable () -> Unit,
    below: (@Composable () -> Unit)? = null,
    anchor: (@Composable () -> Unit)? = null
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        Box {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
                    .clickable(role = Role.Button, onClickLabel = openLabel, onClick = onOpen)
                    .padding(start = 16.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (leading != null) {
                    leading()
                    Spacer(Modifier.width(12.dp))
                }
                Box(Modifier.weight(1f)) { value() }
                trailing()
            }
            anchor?.invoke()
        }
        if (supporting != null) Caption(supporting, maxLines = 2)
        below?.invoke()
    }
}

@Composable
private fun CalendarGlyph(drawable: Int) {
    Image(
        painter = painterResource(drawable), contentDescription = null, modifier = Modifier.size(22.dp),
        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.secondary)
    )
}

@Composable
private fun ClearButton(visible: Boolean, description: String, onClear: () -> Unit) {
    if (visible) {
        Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClickLabel = description, onClick = onClear), contentAlignment = Alignment.Center) {
            Text("✕", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        }
    } else {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
            Text("▾", Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        }
    }
}

@Composable
private fun ValueText(text: String?, hint: String) {
    Text(
        text ?: hint,
        style = MaterialTheme.typography.bodyLarge,
        color = if (text != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
        maxLines = 1
    )
}

/** Quick picks under a date or time field. */
@Composable
private fun <T> PresetChips(presets: List<Pair<String, T>>, current: T?, onPick: (T) -> Unit) {
    if (presets.isEmpty()) return
    Chips(presets.map { ChipOption(it.first, it.second == current) }, onSelect = { onPick(presets[it].second) })
}

/** A day, chosen from a calendar. [clearable] lets it be left empty again. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onPick: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose a date",
    clearable: Boolean = false,
    supporting: String? = null,
    presets: List<Pair<String, LocalDate>> = emptyList()
) {
    var open by remember { mutableStateOf(false) }
    FieldBox(
        label, modifier, supporting, onOpen = { open = true }, openLabel = "Choose a date",
        leading = { CalendarGlyph(R.drawable.baseline_event_24) },
        trailing = { ClearButton(clearable && date != null, "Clear the date") { onPick(null) } },
        value = { ValueText(date?.let { DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault()).format(it) }, placeholder) },
        below = { PresetChips(presets, date) { onPick(it) } }
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (date ?: LocalDate.now()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                AppButton(
                    "Use this day",
                    { state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }; open = false },
                    style = ButtonStyle.TEXT, size = ButtonSize.COMPACT, enabled = state.selectedDateMillis != null
                )
            },
            dismissButton = { AppButton("Cancel", { open = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) }
        ) { DatePicker(state) }
    }
}

/** A time of day, chosen from a clock. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    time: LocalTime?,
    onPick: (LocalTime?) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose a time",
    supporting: String? = null,
    presets: List<Pair<String, LocalTime>> = emptyList()
) {
    var open by remember { mutableStateOf(false) }
    FieldBox(
        label, modifier, supporting, onOpen = { open = true }, openLabel = "Choose a time",
        leading = null,
        trailing = { ClearButton(time != null, "Clear the time") { onPick(null) } },
        value = { ValueText(time?.let { DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()).format(it) }, placeholder) },
        below = { PresetChips(presets, time) { onPick(it) } }
    )
    if (open) {
        val initial = time ?: LocalTime.of(9, 0)
        val state = rememberTimePickerState(initial.hour, initial.minute, is24Hour = false)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                AppButton("Use this time", { onPick(LocalTime.of(state.hour, state.minute)); open = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT)
            },
            dismissButton = { AppButton("Cancel", { open = false }, style = ButtonStyle.TEXT, size = ButtonSize.COMPACT) },
            text = { TimePicker(state) }
        )
    }
}

/** One of a known set, chosen from a list that opens under the field. */
@Composable
fun PickField(
    label: String,
    options: List<String>,
    selected: String?,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "Choose",
    supporting: String? = null
) {
    var open by remember { mutableStateOf(false) }
    FieldBox(
        label, modifier, supporting, onOpen = { open = true }, openLabel = "Choose from the list",
        leading = null,
        trailing = { ClearButton(false, "") {} },
        value = { ValueText(selected, placeholder) },
        anchor = {
            DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = 320.dp)) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                option,
                                fontWeight = if (option == selected) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.primary
                            )
                        },
                        onClick = { onPick(option); open = false }
                    )
                }
            }
        }
    )
}

/** "1st", "2nd", "3rd", "4th", … "31st". */
fun ordinalDay(day: Int): String {
    val suffix = if (day in 11..13) "th" else when (day % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
    return "$day$suffix"
}

/** A day of the month, picked from 1st to 31st instead of typed; "Not set" clears it. The value is the day as text, or blank. */
@Composable
fun DayOfMonthField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null
) {
    val notSet = "Not set"
    val options = listOf(notSet) + (1..31).map { ordinalDay(it) }
    PickField(
        label, options, value.toIntOrNull()?.let { ordinalDay(it) },
        onPick = { picked -> onChange(if (picked == notSet) "" else picked.filter { it.isDigit() }) },
        modifier = modifier, placeholder = notSet, supporting = supporting
    )
}
