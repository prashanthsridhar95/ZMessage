package com.zyke.message.presentation.home

import com.zyke.message.presentation.theme.Spacing
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import com.zyke.message.presentation.common.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.zyke.message.R
import com.zyke.message.domain.plan.PlanHide
import com.zyke.message.domain.plan.PlanKind
import com.zyke.message.domain.plan.SnoozeOption
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.home.HomeContract.Intent
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale
import kotlinx.coroutines.launch

private val DayFormat = FormatCache.pattern("d MMM", Locale.getDefault())

/** Dismissing, snoozing and restoring plan items, each followed by a snackbar whose Undo puts things back. */
internal class PlanActionHandler(
    val dismiss: (key: String, name: String, kind: PlanKind) -> Unit,
    val snooze: (key: String, name: String, kind: PlanKind, until: LocalDate) -> Unit,
    val restore: (key: String) -> Unit,
    /** Shows [text] with an Undo that runs [onUndo]. */
    val announce: (text: String, onUndo: () -> Unit) -> Unit
)

@Composable
internal fun rememberPlanActionHandler(onIntent: (Intent) -> Unit): PlanActionHandler {
    val snackbar = LocalAppSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(onIntent, snackbar) {
        val announce: (String, () -> Unit) -> Unit = { text, onUndo ->
            scope.launch {
                snackbar.currentSnackbarData?.dismiss()
                val result = snackbar.showSnackbar(text, actionLabel = "Undo", duration = SnackbarDuration.Long)
                if (result == SnackbarResult.ActionPerformed) onUndo()
            }
        }
        PlanActionHandler(
            dismiss = { key, name, kind ->
                onIntent(Intent.PlanDismiss(key, name, kind))
                announce("Dismissed $name") { onIntent(Intent.PlanRestore(key)) }
            },
            snooze = { key, name, kind, until ->
                onIntent(Intent.PlanSnooze(key, name, kind, until))
                announce("Snoozed $name until ${DayFormat.format(until)}") { onIntent(Intent.PlanRestore(key)) }
            },
            restore = { key -> onIntent(Intent.PlanRestore(key)) },
            announce = announce
        )
    }
}

/** One more button in the revealed row, such as "Paid" or "Remind". */
internal class PlanExtraAction(val label: String, val describe: String, val onClick: () -> Unit)

/**
 * A plan item with a small "actions" button beside it. Tapping that reveals a row of text buttons (Open, Snooze,
 * Dismiss and whatever [extras] the kind of item adds), each with a 48dp target and the item's name in its description.
 * A snoozed item (the bills tab keeps showing every bill) says so and offers Restore instead.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanItemFrame(
    name: String,
    key: String,
    kind: PlanKind,
    today: LocalDate,
    handler: PlanActionHandler,
    modifier: Modifier = Modifier,
    hide: PlanHide? = null,
    onOpen: (() -> Unit)? = null,
    extras: List<PlanExtraAction> = emptyList(),
    content: @Composable (more: @Composable () -> Unit) -> Unit
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var picking by rememberSaveable(key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        // The row decides where the button sits (the very end of its top line), so it never takes width from the text or buttons.
        content {
            IconButton(
                onClick = { expanded = !expanded },
                Modifier.size(48.dp).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            ) {
                Icon(painterResource(R.drawable.baseline_more_horiz_24), contentDescription = "Actions for $name")
            }
        }
        if (hide != null) {
            StatusPill("Snoozed until ${DayFormat.format(hide.until ?: today)}", Tone.NEUTRAL, Modifier.padding(bottom = 4.dp))
        }
        if (expanded) {
            FlowRow(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                onOpen?.let { PlanActionButton("Open", "Open $name", it) }
                extras.forEach { PlanActionButton(it.label, it.describe, it.onClick) }
                if (hide == null) {
                    PlanActionButton("Snooze", "Snooze $name") { picking = true }
                    PlanActionButton("Dismiss", "Dismiss $name") { handler.dismiss(key, name, kind); expanded = false }
                } else {
                    PlanActionButton("Restore", "Restore $name") { handler.restore(key); expanded = false }
                }
            }
        }
    }
    if (picking) {
        SnoozeDialog(name, today, onDismiss = { picking = false }) { until ->
            picking = false
            expanded = false
            handler.snooze(key, name, kind, until)
        }
    }
}

@Composable
private fun PlanActionButton(label: String, describe: String, onClick: () -> Unit) {
    val spokenDescribe = com.zyke.message.presentation.common.privateSpeech(describe)
    TextButton(onClick, Modifier.heightIn(min = 48.dp).semantics { contentDescription = spokenDescribe }) {
        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

/** 1 day, 3 days, next week, or a date of the person's choosing. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SnoozeDialog(name: String, today: LocalDate, onDismiss: () -> Unit, onPick: (LocalDate) -> Unit) {
    var datePicker by rememberSaveable { mutableStateOf(false) }
    if (datePicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = today.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli())
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) } ?: onDismiss()
                }) { Text("Snooze") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        ) { DatePicker(state) }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Snooze $name") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Caption("It comes back on the day you pick.", Modifier.padding(bottom = 4.dp))
                SnoozeOption.values().forEach { option ->
                    val until = option.until(today)
                    TextButton(
                        onClick = { onPick(until) },
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Snooze $name for ${option.label}" }
                    ) { Text("${option.label}  ·  ${DayFormat.format(until)}", Modifier.fillMaxWidth()) }
                }
                TextButton(
                    onClick = { datePicker = true },
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = "Pick a date to snooze $name until" }
                ) { Text("Pick a date", Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** A button in a [RowActions] line: one label on one line, never narrower than the label. */
internal class RowAction(val label: String, val style: ButtonStyle, val description: String? = null, val onClick: () -> Unit)

/**
 * The shared shape of a plan, bill or reminder row. Top line: [leading] chip, then title and caption (up to two lines,
 * so the caption is not cut to "..." while there is room), then the amount, then [more] (the 48dp actions button) at the very end.
 * Below, on their own full-width line, [actions] sit side by side with equal weight and wrap to a second line when the
 * labels do not fit, so a button is never squeezed to nothing or to half a label.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlanRow(
    title: String,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    caption: (@Composable () -> Unit)? = null,
    amount: (@Composable () -> Unit)? = null,
    more: (@Composable () -> Unit)? = null,
    actions: List<RowAction> = emptyList(),
    /** Under the top line and above the buttons, across the full width: pills, notes. */
    below: (@Composable ColumnScope.() -> Unit)? = null
) {
    Column(modifier.fillMaxWidth().padding(vertical = Spacing.xs)) {
        Row(verticalAlignment = Alignment.Top) {
            if (leading != null) {
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) { leading() }
                Spacer(Modifier.width(Spacing.md))
            }
            Column(Modifier.weight(1f).heightIn(min = 48.dp), verticalArrangement = Arrangement.Center) {
                RowTitle(title)
                caption?.invoke()
            }
            if (amount != null) {
                Spacer(Modifier.width(Spacing.sm))
                Box(Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.CenterEnd) { amount() }
            }
            if (more != null) more() else Spacer(Modifier.width(Spacing.sm))
        }
        below?.invoke(this)
        if (actions.isNotEmpty()) ActionButtons(actions, Modifier.padding(top = Spacing.sm, bottom = Spacing.xs))
    }
}

/** Buttons on their own full-width line, equal weight, wrapping to a second line when the labels do not fit. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionButtons(actions: List<RowAction>, modifier: Modifier = Modifier) {
    FlowRow(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        actions.forEach { action ->
            AppButton(
                text = action.label, onClick = action.onClick,
                modifier = Modifier.weight(1f).then(
                    if (action.description != null) Modifier.semantics { contentDescription = action.description } else Modifier
                ),
                style = action.style, size = ButtonSize.COMPACT
            )
        }
    }
}
