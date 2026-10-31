package com.zyke.message.presentation.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
 * An item already out of sight (the bills tab keeps showing every bill) says so and offers Restore instead.
 */
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
    content: @Composable () -> Unit
) {
    var expanded by rememberSaveable(key) { mutableStateOf(false) }
    var picking by rememberSaveable(key) { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) { content() }
            IconButton(
                onClick = { expanded = !expanded },
                Modifier.semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            ) {
                Icon(painterResource(R.drawable.baseline_more_horiz_24), contentDescription = "Actions for $name")
            }
        }
        if (hide != null) {
            StatusPill(hide.until?.let { "Snoozed until ${DayFormat.format(it)}" } ?: "Dismissed", Tone.NEUTRAL, Modifier.padding(bottom = 4.dp))
        }
        if (expanded) {
            Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
    TextButton(onClick, Modifier.heightIn(min = 48.dp).semantics { contentDescription = describe }) {
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

/** The list of what is dismissed or snoozed, each with Restore, so nothing is lost silently. */
@Composable
internal fun HiddenPlanItems(hidden: List<PlanHide>, handler: PlanActionHandler) {
    if (hidden.isEmpty()) {
        Caption("Nothing is snoozed or dismissed.")
        return
    }
    hidden.forEachIndexed { index, hide ->
        if (index > 0) HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RowTitle(hide.label)
                Caption(
                    (when (hide.kind) { PlanKind.AGENDA -> "Plan"; PlanKind.BILL -> "Bill"; PlanKind.REMINDER -> "Reminder" }) +
                        " · " + (hide.until?.let { "Snoozed until ${DayFormat.format(it)}" } ?: "Dismissed")
                )
            }
            PlanActionButton("Restore", "Restore ${hide.label}") { handler.restore(hide.key) }
        }
    }
}

/** "Snoozed & dismissed (n)": a link that opens the list with Restore. Nothing shows when nothing is hidden. */
@Composable
internal fun HiddenPlanLink(hidden: List<PlanHide>, handler: PlanActionHandler, modifier: Modifier = Modifier) {
    if (hidden.isEmpty()) return
    var open by rememberSaveable { mutableStateOf(false) }
    com.zyke.message.presentation.common.LinkButton("Snoozed & dismissed (${hidden.size})", { open = true }, modifier)
    if (open) PlanPage("Snoozed & dismissed", { open = false }) { HiddenPlanItems(hidden, handler) }
}
