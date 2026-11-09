package com.zyke.message.presentation.flights

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import com.zyke.message.presentation.messages.components.AirlineLogo
import com.zyke.message.presentation.common.ButtonSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zyke.message.ZykeMessagesApplication
import com.zyke.message.domain.flight.Airline
import com.zyke.message.domain.flight.Airlines
import com.zyke.message.domain.flight.Airports
import com.zyke.message.domain.flight.FlightLookup
import com.zyke.message.domain.flight.LiveStatus
import com.zyke.message.domain.flight.ManualFlight
import com.zyke.message.domain.flight.ManualFlightCheck
import com.zyke.message.domain.flight.ManualFlightRules
import com.zyke.message.domain.flight.ManualFlights
import com.zyke.message.notifications.FlightTrackingControl
import com.zyke.message.presentation.common.AppButton
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
import java.time.ZoneId
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** The editor key that means "a new flight" rather than the id of one to change. */
internal const val NEW_FLIGHT = "new"

/** Saving and removing a flight by hand, with the notice and Undo, and the alarm moved to follow. Built by the screen that shows the form. */
internal class ManualFlightActions(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState
) {
    private val container get() = (context.applicationContext as ZykeMessagesApplication).container

    fun save(flight: ManualFlight, added: Boolean) {
        container.manualFlights.save(flight)
        // A flight that was stopped, then added again, is tracked again.
        container.flightPlan.resume(flight.key)
        // Sets the alarm for it, and starts the service if it is already due and this screen is in front.
        FlightTrackingControl.onForeground(context, container.flightPlan.fromTexts())
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(if (added) "Flight added" else "Flight updated", duration = SnackbarDuration.Short)
        }
    }

    fun remove(flight: ManualFlight) {
        val removed = container.manualFlights.remove(flight.id) ?: return
        FlightTrackingControl.rearm(context)
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar("Removed ${removed.code}", actionLabel = "Undo", duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) {
                container.manualFlights.save(removed)
                FlightTrackingControl.rearm(context)
            }
        }
    }
}

@Composable
internal fun rememberManualFlightActions(): ManualFlightActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = LocalAppSnackbar.current
    return remember(context, scope, snackbar) { ManualFlightActions(context, scope, snackbar) }
}

/** Opens the form for a new flight ([editId] = [NEW_FLIGHT]) or the flight with that id. */
@Composable
internal fun ManualFlightSheetHost(editId: String, onClose: () -> Unit) {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val actions = rememberManualFlightActions()
    val manual by container.manualFlights.flights.collectAsStateWithLifecycle()
    val initial = if (editId == NEW_FLIGHT) null else manual.firstOrNull { it.id == editId }
    if (editId != NEW_FLIGHT && initial == null) {
        LaunchedEffect(editId) { onClose() }
        return
    }
    ManualFlightSheet(initial, onClose, actions)
}

/** What the look-up came to; kept only while the form is open. */
private sealed interface Lookup {
    data object Idle : Lookup
    data object Looking : Lookup
    data class Found(val status: LiveStatus) : Lookup
    /** Not found, unreadable or not reachable: the flight can still be saved. */
    data object Unconfirmed : Lookup
}

/**
 * Adding (or changing) a flight by hand: airline from a searchable list, number, day and an optional PNR note for yourself.
 * With live status on, "Look up flight" first checks the route and times with the live source (the airline code and flight number
 * are sent, nothing else); if that cannot confirm the flight it can still be saved, marked "Schedule not confirmed". Nothing is
 * asked of the network in the demo.
 */
@Composable
internal fun ManualFlightSheet(initial: ManualFlight?, onDismiss: () -> Unit, actions: ManualFlightActions) {
    val context = LocalContext.current
    val container = (context.applicationContext as ZykeMessagesApplication).container
    val service = container.flights
    val liveOn by service.liveEnabled.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val zone = remember { ZoneId.systemDefault() }
    val today = remember { LocalDate.now() }

    var airlineCode by rememberSaveable { mutableStateOf(initial?.airlineCode) }
    var numberText by rememberSaveable { mutableStateOf(initial?.number.orEmpty()) }
    var dateText by rememberSaveable { mutableStateOf((initial?.date ?: today).toString()) }
    var pnr by rememberSaveable { mutableStateOf(initial?.pnrNote.orEmpty()) }
    var pickerOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }

    val airline: Airline? = airlineCode?.let { Airlines.byCode(it) }
    val date = runCatching { LocalDate.parse(dateText) }.getOrNull()
    val check = ManualFlightRules.check(airline, numberText, date, pnr, today)
    val ok = check as? ManualFlightCheck.Ok
    val problem = check as? ManualFlightCheck.Problem
    // Changing what was asked about makes an earlier look-up meaningless.
    LaunchedEffect(airlineCode, numberText, dateText) { lookup = Lookup.Idle }

    val canLookUp = liveOn && !container.demo
    val state = lookup
    val primary = when {
        state is Lookup.Looking -> "Looking up…"
        canLookUp && state is Lookup.Idle -> "Look up flight"
        state is Lookup.Unconfirmed -> "Save anyway"
        initial == null -> "Save flight"
        else -> "Save changes"
    }

    fun save(found: LiveStatus?) {
        val flight = ok ?: return
        val base = ManualFlight(
            id = initial?.id ?: "m${System.currentTimeMillis()}", airlineCode = flight.airline.code, number = flight.number, date = flight.date,
            pnrNote = flight.pnrNote, addedAt = initial?.addedAt ?: System.currentTimeMillis()
        )
        // The same flight keeps what an earlier look-up found; a different one starts clean.
        val carried = if (initial != null && initial.key == base.key) {
            base.copy(originCode = initial.originCode, destinationCode = initial.destinationCode, departure = initial.departure, arrival = initial.arrival, confirmed = initial.confirmed)
        } else base
        actions.save(found?.let { ManualFlights.confirmed(carried, it, zone) } ?: carried, added = initial == null)
        onDismiss()
    }

    FormSheet(
        title = if (initial == null) "Add a flight" else "Change flight",
        subtitle = "Track a flight that is not in your messages",
        onDismiss = onDismiss,
        primaryLabel = primary,
        primaryEnabled = ok != null && state !is Lookup.Looking,
        hint = problem?.message,
        onPrimary = {
            val flight = ok ?: return@FormSheet
            when {
                canLookUp && state is Lookup.Idle -> {
                    lookup = Lookup.Looking
                    scope.launch {
                        val base = ManualFlight("", flight.airline.code, flight.number, flight.date).toBooked()
                        val answer = service.lookup(base, zone)
                        lookup = if (answer is FlightLookup.Found) Lookup.Found(answer.status) else Lookup.Unconfirmed
                    }
                }
                else -> save((state as? Lookup.Found)?.status)
            }
        },
        secondaryLabel = if (initial != null) "Remove flight" else null,
        onSecondary = if (initial != null) ({ actions.remove(initial); onDismiss() }) else null
    ) {
        AirlineField(airline, pickerOpen, query, { pickerOpen = !pickerOpen }, { query = it }) { picked ->
            airlineCode = picked.code
            pickerOpen = false
            query = ""
        }
        FormField(
            label = "Flight number", value = numberText, onChange = { numberText = it.take(8) },
            placeholder = "e.g. 2345", supporting = airline?.let { "Together: ${it.code} ${ManualFlightRules.numberDigits(numberText, it.code).ifEmpty { "…" }}" },
            error = ManualFlightRules.numberProblem(numberText, airline?.code), capitalization = KeyboardCapitalization.Characters
        )
        DateField(
            "Day of departure", date, { dateText = (it ?: today).toString() },
            presets = listOf("Today" to today, "Tomorrow" to today.plusDays(1))
        )
        if (problem?.field == "date") Caption(problem?.message.orEmpty(), maxLines = 2, color = MaterialTheme.colorScheme.error)
        FormField(
            label = "PNR (optional)", value = pnr, onChange = { pnr = it.take(12) }, placeholder = "A note for yourself",
            supporting = "Kept on this phone and never sent anywhere.",
            error = if (problem?.field == "pnr") problem?.message else null, capitalization = KeyboardCapitalization.Characters,
            imeAction = androidx.compose.ui.text.input.ImeAction.Done
        )
        LookupPanel(state, canLookUp, container.demo, ok, zone)
    }
}

@Composable
private fun AirlineField(
    selected: Airline?, open: Boolean, query: String, onToggle: () -> Unit, onQuery: (String) -> Unit, onPick: (Airline) -> Unit
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Airline", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.secondary)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(RoundedCornerShape(16.dp))
                .background05()
                .clickable(role = Role.Button, onClickLabel = if (open) "Close the airline list" else "Choose the airline", onClick = onToggle)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selected != null) {
                AirlineLogo(selected.code, size = 32.dp)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                selected?.let { "${it.name} (${it.code})" } ?: "Choose the airline",
                Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                color = if (selected != null) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.secondary.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.width(8.dp))
            Text(if (open) "▴" else "▾", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.secondary)
        }
        if (open) {
            FormField(label = "Search airlines", value = query, onChange = onQuery, placeholder = "Name or code, like IndiGo or 6E", imeAction = androidx.compose.ui.text.input.ImeAction.Done)
            val matches = remember(query) { ManualFlightRules.airlines(query) }
            val shown = matches.take(MAX_LISTED)
            if (shown.isEmpty()) Caption("No airline matches. Try its name or two-character code.", maxLines = 2)
            Column {
                shown.forEachIndexed { index, airline ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button, onClickLabel = "Choose ${airline.name}") { onPick(airline) }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AirlineLogo(airline.code, size = 32.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(airline.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.width(8.dp))
                        Text(airline.code, style = MaterialTheme.typography.labelLarge.copy(fontFeatureSettings = "tnum"), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary, maxLines = 1, softWrap = false)
                    }
                }
            }
            if (matches.size > shown.size) Caption("${matches.size - shown.size} more. Type to narrow the list.", maxLines = 1)
        }
    }
}

private const val MAX_LISTED = 8

@Composable
private fun Modifier.background05(): Modifier = this.then(Modifier.background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f)))

/** What the look-up found, or what it could not, under the fields. */
@Composable
private fun LookupPanel(state: Lookup, canLookUp: Boolean, demo: Boolean, ok: ManualFlightCheck.Ok?, zone: ZoneId) {
    when {
        state is Lookup.Found && ok != null -> {
            val preview = ManualFlights.confirmed(ManualFlight("", ok.airline.code, ok.number, ok.date), state.status, zone)
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background05().padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text("Found", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                RowTitle(
                    listOfNotNull(preview.originCode, preview.destinationCode).joinToString(" → ")
                        .ifEmpty { "${ok.airline.name} ${ok.airline.code} ${ok.number}" }
                )
                Caption(
                    listOfNotNull(
                        preview.departure?.let { "Departs " + clockText(it) }, preview.arrival?.let { "Arrives " + clockText(it) },
                        preview.originCode?.let { Airports.city(it) }
                    ).joinToString(" · ").ifEmpty { "No times given yet" },
                    maxLines = 3
                )
            }
        }
        state is Lookup.Unconfirmed ->
            Caption("Couldn’t confirm this flight: the service doesn’t list it yet, or couldn’t be reached. You can save it anyway; it will say “Schedule not confirmed”.", maxLines = 5)
        state is Lookup.Looking -> Caption("Asking for the route and times…", maxLines = 2)
        demo -> Caption("Demo: nothing is asked of the internet. The flight is saved on this demo only.", maxLines = 3)
        canLookUp -> Caption("Look up flight checks the route and times before saving. Only the airline code and flight number are sent.", maxLines = 3)
        else -> Caption("Turn on live status in Settings to confirm the route and times. Without it the flight is saved as “Schedule not confirmed”.", maxLines = 4)
    }
}

private fun clockText(time: java.time.LocalTime): String = FormatCache.pattern("HH:mm", Locale.getDefault()).format(time)

// ---- the list in Settings

/** The flights added by hand, each with Edit and Remove (equal-width, stacked on a narrow phone), and the button to add one. */
@Composable
internal fun ManualFlightsSection() {
    val container = (LocalContext.current.applicationContext as ZykeMessagesApplication).container
    val manual by container.manualFlights.flights.collectAsStateWithLifecycle()
    val actions = rememberManualFlightActions()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Flights you added", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.secondary)
        if (manual.isEmpty()) {
            Caption("None yet. Add one that is not in your messages, to see it on Home on its day and be told when it is in the air.", maxLines = 4)
        }
        manual.forEachIndexed { index, flight ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    AirlineLogo(flight.airline.code, size = 40.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        RowTitle("${flight.airline.name} ${flight.code}")
                        Caption(manualCaption(flight), maxLines = 2)
                    }
                }
                ResponsiveRow(Modifier.fillMaxWidth(), spacing = 8.dp) {
                    AppButton("Edit", { editing = flight.id }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                    AppButton("Remove", { actions.remove(flight) }, Modifier.cell(), style = ButtonStyle.OUTLINE, size = ButtonSize.COMPACT)
                }
            }
        }
        AppButton("Add a flight", { editing = NEW_FLIGHT }, Modifier.fillMaxWidth().padding(top = 4.dp), style = if (manual.isEmpty()) ButtonStyle.PRIMARY else ButtonStyle.OUTLINE)
    }
    editing?.let { ManualFlightSheetHost(it) { editing = null } }
}

/** "Thu 12 Nov · DEL → MAA · departs 14:35", or "… · Schedule not confirmed". */
internal fun manualCaption(f: ManualFlight): String {
    val day = FormatCache.pattern("EEE d MMM", Locale.getDefault()).format(f.date)
    val route = listOfNotNull(f.originCode, f.destinationCode).joinToString(" → ").ifEmpty { null }
    val tail = if (f.confirmed) f.departure?.let { "departs " + clockText(it) } else "Schedule not confirmed"
    return listOfNotNull(day, route, tail).joinToString(" · ")
}
