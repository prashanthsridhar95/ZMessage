package com.zyke.message.presentation.trains

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.R
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.train.BookedTrain
import com.zyke.message.domain.train.ManualTrain
import com.zyke.message.domain.train.ManualTrainCheck
import com.zyke.message.domain.train.ManualTrainRules
import com.zyke.message.domain.train.ManualTrains
import com.zyke.message.domain.train.TrainLive
import com.zyke.message.domain.train.TrainLookup
import com.zyke.message.notifications.TrainTrackingControl
import com.zyke.message.presentation.common.AdaptiveSheet
import com.zyke.message.presentation.common.AppButton
import com.zyke.message.presentation.common.ButtonSize
import com.zyke.message.presentation.common.ButtonStyle
import com.zyke.message.presentation.common.DateField
import com.zyke.message.presentation.common.FormField
import com.zyke.message.presentation.common.FormSheet
import com.zyke.message.presentation.common.LocalAppSnackbar
import com.zyke.message.presentation.common.ResponsiveRow
import com.zyke.message.presentation.common.Text
import com.zyke.message.presentation.format.FormatCache
import com.zyke.message.presentation.messages.components.Caption
import com.zyke.message.presentation.messages.components.RowTitle
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The editor key that means "a new train" rather than the id of one to change. */
internal const val NEW_TRAIN = "new"

/** Saving and removing a train journey by hand, with the notice and Undo, and the alarm moved to follow. Built by the screen that shows the form. */
internal class ManualTrainActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState
) {
    private val container get() = (context.applicationContext as ZykeMessagesApplication).container

    fun save(train: ManualTrain, added: Boolean) {
        container.manualTrains.save(train)
        // A journey that was stopped, then added again, is tracked again.
        container.trainPlan.resume(train.key)
        TrainTrackingControl.onForeground(context, container.trainPlan.fromTexts())
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(if (added) "Train added" else "Train updated", duration = SnackbarDuration.Short)
        }
    }

    fun remove(train: ManualTrain) {
        val removed = container.manualTrains.remove(train.id) ?: return
        TrainTrackingControl.rearm(context)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Removed train ${removed.number}", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                container.manualTrains.save(removed)
                TrainTrackingControl.rearm(context)
            }
        }
    }
}

@Composable
internal fun rememberManualTrainActions(): ManualTrainActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    return remember(context, scope, snackbar) { ManualTrainActions(context, scope, snackbar) }
}

/** Opens the form for a new train ([editId] = [NEW_TRAIN]) or the journey with that id. */
@Composable
internal fun ManualTrainSheetHost(editId: String, onClose: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val actions = rememberManualTrainActions()
    val manual by container.manualTrains.trains.collectAsStateWithLifecycle()
    val initial = if (editId == NEW_TRAIN) null else manual.firstOrNull { it.id == editId }
    if (editId != NEW_TRAIN && initial == null) {
        LaunchedEffect(editId) { onClose() }
        return
    }
    ManualTrainSheet(initial, onClose, actions)
}

/** What the look-up came to; kept only while the form is open. */
private sealed interface Lookup {
    data object Idle : Lookup
    data object Looking : Lookup
    data class Found(val live: TrainLive) : Lookup
    /** Not found, unreadable or not reachable: the journey can still be saved. */
    data object Unconfirmed : Lookup
}

/**
 * Adding (or changing) a train by hand: the train number, the day of journey, the station you board at (optional, a code like MAS or a
 * name) and an optional PNR note for yourself. With live train status on, "Look up train" first checks the name and schedule with the
 * live source (the train number and the day it started are sent, nothing else: not the station, not the PNR); if that cannot confirm the
 * train it can still be saved, marked "Schedule not confirmed". Nothing is asked of the network in the demo.
 */
@Composable
internal fun ManualTrainSheet(initial: ManualTrain?, onDismiss: () -> Unit, actions: ManualTrainActions) {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val service = container.trains
    val liveOn by service.liveEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now(com.zyke.message.domain.train.Stations.ZONE) }

    var numberText by rememberSaveable { mutableStateOf(initial?.number.orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf((initial?.date ?: today).toString()) }
    var stationText by rememberSaveable { mutableStateOf(initial?.boardingCode.orEmpty()) }
    var pnr by rememberSaveable { mutableStateOf(initial?.pnrNote.orEmpty()) }
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }

    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val check = ManualTrainRules.check(numberText, date, stationText, pnr, today)
    val ok = check as? ManualTrainCheck.Ok
    val problem = check as? ManualTrainCheck.Problem
    // Changing what was asked about makes an earlier look-up meaningless.
    LaunchedEffect(numberText, dateText, stationText) { lookup = Lookup.Idle }

    val canLookUp = liveOn && !container.demo
    val state = lookup
    val primary = when {
        state is Lookup.Looking -> "Looking up…"
        canLookUp && state is Lookup.Idle -> "Look up train"
        state is Lookup.Unconfirmed -> "Save anyway"
        initial == null -> "Save train"
        else -> "Save changes"
    }

    fun save(found: TrainLive?) {
        val train = ok ?: return
        val base = ManualTrain(
            id = initial?.id ?: "t${System.currentTimeMillis()}", number = train.number, date = train.date,
            boardingCode = train.boarding?.code, boardingName = train.boarding?.city, pnrNote = train.pnrNote,
            addedAt = initial?.addedAt ?: System.currentTimeMillis()
        )
        // The same journey keeps what an earlier look-up found; a different one starts clean.
        val carried = if (initial != null && initial.key == base.key && initial.boardingCode == base.boardingCode) {
            base.copy(
                name = initial.name, destinationCode = initial.destinationCode, destinationName = initial.destinationName, departure = initial.departure,
                arrival = initial.arrival, arrivalDayOffset = initial.arrivalDayOffset, boardingDay = initial.boardingDay, confirmed = initial.confirmed
            )
        } else base
        actions.save(found?.let { ManualTrains.confirmed(carried, it) } ?: carried, added = initial == null)
        onDismiss()
    }

    FormSheet(
        title = if (initial == null) "Add a train" else "Change train",
        subtitle = "Track a train that is not in your messages",
        onDismiss = onDismiss,
        primaryLabel = primary,
        primaryEnabled = ok != null && state !is Lookup.Looking,
        hint = problem?.message,
        onPrimary = {
            val train = ok ?: return@FormSheet
            when {
                canLookUp && state is Lookup.Idle -> {
                    lookup = Lookup.Looking
                    scope.launch {
                        val base = BookedTrain(train.number, null, train.date, train.boarding, null, null, null)
                        val answer = service.lookup(base)
                        lookup = if (answer is TrainLookup.Found) Lookup.Found(answer.live) else Lookup.Unconfirmed
                    }
                }
                else -> save((state as? Lookup.Found)?.live)
            }
        },
        secondaryLabel = if (initial != null) "Remove train" else null,
        onSecondary = if (initial != null) ({ actions.remove(initial); onDismiss() }) else null
    ) {
        FormField(
            label = "Train number", value = numberText, onChange = { numberText = it.filter { c -> c.isDigit() || c == ' ' || c == '-' }.take(8) },
            placeholder = "e.g. 12633", keyboardType = KeyboardType.Number, error = ManualTrainRules.numberProblem(numberText)
        )
        DateField(
            "Day of journey", date, { dateText = (it ?: today).toString() },
            presets = listOf("Today" to today, "Tomorrow" to today.plusDays(1))
        )
        if (problem?.field == "date") Caption(problem?.message.orEmpty(), maxLines = 2, color = MaterialTheme.colorScheme.error)
        FormField(
            label = "Boarding station (optional)", value = stationText, onChange = { stationText = it.take(40) }, placeholder = "A code like MAS, or a name",
            supporting = "Blank: from the train's first station to its last.", error = if (problem?.field == "station") problem?.message else null,
            capitalization = KeyboardCapitalization.Words
        )
        FormField(
            label = "PNR (optional)", value = pnr, onChange = { pnr = it.filter { c -> c.isDigit() || c == ' ' }.take(12) }, placeholder = "A note for yourself",
            supporting = "Kept on this phone and never sent anywhere.", error = if (problem?.field == "pnr") problem?.message else null,
            keyboardType = KeyboardType.Number, imeAction = ImeAction.Done
        )
        LookupPanel(state, canLookUp, container.demo, ok)
    }
}

/** What the look-up found, or what it could not, under the fields. */
@Composable
private fun LookupPanel(state: Lookup, canLookUp: Boolean, demo: Boolean, ok: ManualTrainCheck.Ok?) {
    when {
        state is Lookup.Found && ok != null -> {
            val preview = ManualTrains.confirmed(ManualTrain("", ok.number, ok.date, ok.boarding?.code, ok.boarding?.city), state.live)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Found", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                RowTitle(listOfNotNull(ok.number, preview.name).joinToString(" "))
                Caption(
                    listOfNotNull(
                        listOfNotNull(preview.boardingName ?: preview.boardingCode, preview.destinationName ?: preview.destinationCode).joinToString(" → ").ifEmpty { null },
                        preview.departure?.let { "Leaves " + clockText(it) }, preview.arrival?.let { "Arrives " + clockText(it) }
                    ).joinToString(" · ").ifEmpty { "No times given yet" },
                    maxLines = 3
                )
            }
        }
        state is Lookup.Unconfirmed ->
            Caption("Couldn’t confirm this train: the service doesn’t list it yet, or couldn’t be reached. You can save it anyway; it will say “Schedule not confirmed”.", maxLines = 5)
        state is Lookup.Looking -> Caption("Asking for the name and schedule…", maxLines = 2)
        demo -> Caption("Demo: nothing is asked of the internet. The train is saved on this demo only.", maxLines = 3)
        canLookUp -> Caption("Look up train checks the name and schedule before saving. Only the train number and the day it started are sent.", maxLines = 3)
        else -> Caption("Turn on live train status in Settings to confirm the schedule. Without it the train is saved as “Schedule not confirmed”.", maxLines = 4)
    }
}

private fun clockText(time: java.time.LocalTime): String = FormatCache.pattern("HH:mm", Locale.getDefault()).format(time)

// ---- the list in Settings

/** The journeys added by hand, each with Edit and Remove (equal-width, stacked on a narrow phone), and the button to add one. */
@Composable
internal fun ManualTrainsSection() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val manual by container.manualTrains.trains.collectAsStateWithLifecycle()
    val actions = rememberManualTrainActions()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Trains you added", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
        if (manual.isEmpty()) {
            Caption("None yet. Add one that is not in your messages, to see it on Home on its day and be told while it is on the way.", maxLines = 4)
        }
        manual.forEachIndexed { index, train ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TrainTile(40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        RowTitle(listOfNotNull(train.number, train.name).joinToString(" "))
                        Caption(manualTrainCaption(train), maxLines = 2)
                    }
                }
                ResponsiveRow(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    AppButton("Edit", { editing = train.id }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                    AppButton("Remove", { actions.remove(train) }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                }
            }
        }
        AppButton("Add a train", { editing = NEW_TRAIN }, Modifier.fillMaxWidth().padding(top = 4.dp), style = if (manual.isEmpty()) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE)
    }
    editing?.let { ManualTrainSheetHost(it) { editing = null } }
}

/** "Thu 12 Nov · MS → TPJ · leaves 21:30", or "… · Schedule not confirmed". */
internal fun manualTrainCaption(t: ManualTrain): String {
    val day = FormatCache.pattern("EEE d MMM", Locale.getDefault()).format(t.date)
    val route = listOfNotNull(t.boardingCode, t.destinationCode).joinToString(" → ").ifEmpty { null }
    val tail = if (t.confirmed) t.departure?.let { "leaves " + clockText(it) } else "Schedule not confirmed"
    return listOfNotNull(day, route, tail).joinToString(" · ")
}

// ---- one entry for "Add a trip"

/** "Add a trip": which kind? A flight or a train, as two large rows, then the matching form. */
@Composable
internal fun TripChoiceSheet(onFlight: () -> Unit, onTrain: () -> Unit, onDismiss: () -> Unit) {
    AdaptiveSheet(title = "Add a trip", subtitle = "Track one that is not in your messages", onDismiss = onDismiss, autoPage = false) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            TripChoiceRow(R.drawable.baseline_flight_24, "Flight", "Airline and flight number", onFlight)
            TripChoiceRow(R.drawable.baseline_train_24, "Train", "Train number and day of journey", onTrain)
        }
    }
}

@Composable
private fun TripChoiceRow(icon: Int, title: String, caption: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f))
            .clickable(role = Role.Button, onClickLabel = "Add a $title", onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        androidx.compose.foundation.Image(
            painter = androidx.compose.ui.res.painterResource(icon), contentDescription = null, modifier = Modifier.width(28.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(MaterialTheme.colorScheme.primary)
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Caption(caption, maxLines = 2)
        }
    }
}
