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
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.privateSpeech
import com.zyke.message.presentation.motion.AppearVisibility
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
        AppearVisibility(expanded) {
            val actions = buildList {
                onOpen?.let { add(RowAction("Open", ButtonStyle.TEXT, "Open $name") { it() }) }
                extras.forEach { extra -> add(RowAction(extra.label, ButtonStyle.TEXT, extra.describe, extra.onClick)) }
                if (hide == null) {
                    add(RowAction("Snooze", ButtonStyle.TEXT, "Snooze $name") { picking = true })
                    add(RowAction("Dismiss", ButtonStyle.TEXT, "Dismiss $name") { handler.dismiss(key, name, kind); expanded = false })
                } else {
                    add(RowAction("Restore", ButtonStyle.TEXT, "Restore $name") { handler.restore(key); expanded = false })
                }
            }
            ActionButtons(actions, Modifier.padding(bottom = Spacing.sm))
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

/**
 * Buttons on their own full-width line with equal widths: side by side up to three, two to a line beyond that, and one under
 * the other on a narrow phone or at large text (see [ResponsiveRow]). A last button alone on its line keeps the width of
 * the others, so the wrapping is never uneven.
 */
@Composable
internal fun ActionButtons(actions: List<RowAction>, modifier: Modifier = Modifier) {
    val perLine = if (actions.size <= MAX_ACTIONS_PER_LINE) actions.size.coerceAtLeast(1) else 2
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        actions.chunked(perLine).forEach { line ->
            ResponsiveRow(spacing = Spacing.sm) {
                line.forEach { action ->
                    val spoken = action.description?.let { privateSpeech(it) }
                    AppButton(
                        text = action.label, onClick = action.onClick,
                        modifier = Modifier.cell().then(
                            if (spoken != null) Modifier.semantics { contentDescription = spoken } else Modifier
                        ),
                        style = action.style, size = ButtonSize.COMPACT
                    )
                }
                if (!stacked) repeat(perLine - line.size) { Spacer(Modifier.cell()) }
            }
        }
    }
}

private const val MAX_ACTIONS_PER_LINE = 3
